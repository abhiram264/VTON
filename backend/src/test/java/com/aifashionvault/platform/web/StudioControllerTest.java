package com.aifashionvault.platform.web;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;
import com.aifashionvault.platform.studio.StudioDraftCloner;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.studio.JdbcStudioStore;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C10 mount: the Studio HTTP surface the mobile client already calls, over the
 * REAL {@link JdbcStudioStore} and a Testcontainers Postgres — the same
 * full-stack posture {@code CollectionsControllerTest} uses. Proves owner scope,
 * the optimistic-concurrency token, and the domain conflict rules end to end.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@AutoConfigureMockMvc
class StudioControllerTest {

	static { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); }

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres").withStartupTimeout(Duration.ofMinutes(5));
	private static final ObjectMapper JSON = new ObjectMapper();

	/** Mutable clock: whole-second tokens so the optimistic token is test-controllable. */
	static final AtomicReference<Instant> NOW = new AtomicReference<>(Instant.parse("2026-08-30T12:00:00Z"));

	@Autowired MockMvc mvc;
	@Autowired JdbcTemplate jdbc;

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		POSTGRES.start();
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@BeforeEach
	void reset() {
		NOW.set(Instant.parse("2026-08-30T12:00:00Z"));
		jdbc.update("TRUNCATE studio_item, studio_session, vault_item, identity_mapping, users CASCADE");
	}

	@Test
	void allRoutesRequireAuthentication() throws Exception {
		String id = UUID.randomUUID().toString();
		mvc.perform(post("/studio/sessions")).andExpect(status().isUnauthorized());
		mvc.perform(get("/studio/sessions/" + id)).andExpect(status().isUnauthorized());
		mvc.perform(get("/studio/sessions/active")).andExpect(status().isUnauthorized());
		mvc.perform(post("/studio/sessions/" + id + "/commands").contentType(MediaType.APPLICATION_JSON)
			.content("{\"command\":\"clearOutfit\",\"expectedUpdatedAt\":\"2026-08-30T12:00:00Z\"}")).andExpect(status().isUnauthorized());
	}

	@Test
	void createIsOwnerScopedAndActiveRestoresTheLatestDraft() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		JsonNode created = create(token);
		assertThat(created.get("sessionId").stringValue()).isNotBlank();
		assertThat(created.get("avatarProfileId").isNull()).isTrue();
		assertThat(created.get("itemIds").isEmpty()).isTrue();
		String id = created.get("sessionId").stringValue();

		mvc.perform(get("/studio/sessions/" + id).header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessionId").value(id))
			.andExpect(jsonPath("$.itemIds.length()").value(0));
		mvc.perform(get("/studio/sessions/active").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sessionId").value(id));

		// A foreign account sees nothing: unknown and unowned ids are both 404.
		String foreign = InProcessJwtFixture.bearerTokenForSubject("foreign-studio-owner");
		for (String candidate : List.of(id, UUID.randomUUID().toString(), "bad")) {
			mvc.perform(get("/studio/sessions/" + candidate).header(HttpHeaders.AUTHORIZATION, bearer(foreign)))
				.andExpect(status().isNotFound())
				.andExpect(content().json("{\"code\":\"STUDIO_SESSION_NOT_FOUND\"}"));
		}
		mvc.perform(get("/studio/sessions/active").header(HttpHeaders.AUTHORIZATION, bearer(foreign)))
			.andExpect(status().isNotFound());
	}

	@Test
	void commandsMutateTheDraftAndAStaleTokenCannotSilentlyWin() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		String sessionId = create(token).get("sessionId").stringValue();
		UUID top = insertVaultForSubject(InProcessJwtFixture.subject(), "top");
		UUID bottom = insertVaultForSubject(InProcessJwtFixture.subject(), "bottom");
		Instant t0 = NOW.get();

		// addItem: expect the stored token, stamp a new one.
		advance();
		JsonNode added = command(token, sessionId, t0, "{\"command\":\"addItem\",\"slot\":\"UPPER_BODY\",\"vaultItemId\":\"" + top + "\"}");
		assertThat(added.get("itemIds")).hasSize(1);
		Instant stored = NOW.get();

		// A stale token (the draft already moved t0 -> stored) must 409, not overwrite.
		advance();
		mvc.perform(post("/studio/sessions/" + sessionId + "/commands").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"command\":\"addItem\",\"slot\":\"LOWER_BODY\",\"vaultItemId\":\"" + bottom + "\",\"expectedUpdatedAt\":\"" + t0 + "\"}"))
			.andExpect(status().isConflict())
			.andExpect(content().json("{\"code\":\"STUDIO_STALE_REVISION\"}"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM studio_item WHERE studio_session_id=?", Integer.class, UUID.fromString(sessionId))).isOne();

		// Replace by slot (a fresh token): the upper-body slot is swapped, not duplicated.
		JsonNode replaced = command(token, sessionId, stored, "{\"command\":\"replaceItem\",\"slot\":\"UPPER_BODY\",\"vaultItemId\":\"" + bottom + "\"}");
		assertThat(replaced.get("itemIds")).hasSize(1);
		stored = NOW.get();

		// Duplicate garment across slots is refused by the domain before persistence.
		advance();
		mvc.perform(post("/studio/sessions/" + sessionId + "/commands").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"command\":\"addItem\",\"slot\":\"LOWER_BODY\",\"vaultItemId\":\"" + bottom + "\",\"expectedUpdatedAt\":\"" + stored + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().json("{\"code\":\"STUDIO_INVALID_REQUEST\"}"));

		// removeItem and clearOutfit drain the draft (rejected requests never advanced the token).
		String itemId = jdbc.queryForObject("SELECT id FROM studio_item WHERE studio_session_id=?", UUID.class, UUID.fromString(sessionId)).toString();
		advance();
		JsonNode removed = command(token, sessionId, stored, "{\"command\":\"removeItem\",\"itemId\":\"" + itemId + "\"}");
		assertThat(removed.get("itemIds").isEmpty()).isTrue();
		stored = NOW.get();
		advance();
		JsonNode cleared = command(token, sessionId, stored, "{\"command\":\"clearOutfit\"}");
		assertThat(cleared.get("itemIds").isEmpty()).isTrue();
	}

	@Test
	void fullBodyConflictsWithUpperBodyAndBadInputsAreRejected() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		String sessionId = create(token).get("sessionId").stringValue();
		UUID top = insertVaultForSubject(InProcessJwtFixture.subject(), "top");
		UUID dress = insertVaultForSubject(InProcessJwtFixture.subject(), "dress");

		advance();
		command(token, sessionId, Instant.parse("2026-08-30T12:00:00Z"),
			"{\"command\":\"addItem\",\"slot\":\"UPPER_BODY\",\"vaultItemId\":\"" + top + "\"}");
		Instant after = NOW.get();

		// FULL_BODY conflicts with the occupied UPPER_BODY slot.
		advance();
		mvc.perform(post("/studio/sessions/" + sessionId + "/commands").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"command\":\"addItem\",\"slot\":\"FULL_BODY\",\"vaultItemId\":\"" + dress + "\",\"expectedUpdatedAt\":\"" + after + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().json("{\"code\":\"STUDIO_INVALID_REQUEST\"}"));

		// Unknown command, missing/blank token, and an unowned vault item are all 400.
		for (String body : List.of(
			"{\"command\":\"explode\",\"expectedUpdatedAt\":\"" + after + "\"}",
			"{\"command\":\"addItem\",\"slot\":\"UPPER_BODY\",\"vaultItemId\":\"" + dress + "\"}",
			"{\"command\":\"addItem\",\"slot\":\"UPPER_BODY\",\"vaultItemId\":\"" + dress + "\",\"expectedUpdatedAt\":\" \"}",
			"{\"command\":\"addItem\",\"slot\":\"NOPE\",\"vaultItemId\":\"" + dress + "\",\"expectedUpdatedAt\":\"" + after + "\"}")) {
			mvc.perform(post("/studio/sessions/" + sessionId + "/commands").header(HttpHeaders.AUTHORIZATION, bearer(token))
					.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(content().json("{\"code\":\"STUDIO_INVALID_REQUEST\"}"));
		}

		// A vault item owned by someone else is refused on write.
		UUID foreignItem = insertVaultForSubject("foreign-item-owner", "foreign");
		advance();
		mvc.perform(post("/studio/sessions/" + sessionId + "/commands").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"command\":\"addItem\",\"slot\":\"LOWER_BODY\",\"vaultItemId\":\"" + foreignItem + "\",\"expectedUpdatedAt\":\"" + after + "\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void withoutAStoreBeanTheSurfaceIsHonestlyUnavailable() {
		org.springframework.beans.factory.support.DefaultListableBeanFactory factory =
			new org.springframework.beans.factory.support.DefaultListableBeanFactory();
		StudioController controller = new StudioController(
			factory.getBeanProvider(JdbcStudioStore.class),
			factory.getBeanProvider(StudioDraftCloner.class),
			factory.getBeanProvider(com.aifashionvault.studio.agent.StudioAgentTools.class),
			factory.getBeanProvider(com.aifashionvault.studio.agent.StudioCommandPlanner.class),
			factory.getBeanProvider(com.aifashionvault.featureflag.FeatureFlags.class),
			factory.getBeanProvider(Clock.class));
		assertThatThrownBy(() -> controller.get(new UserId(UUID.randomUUID()), UUID.randomUUID().toString()))
			.isInstanceOf(RuntimeException.class);
		assertThat(controller.unavailable().getStatusCode().value()).isEqualTo(503);
		assertThat(controller.unavailable().getBody().code()).isEqualTo("STUDIO_UNAVAILABLE");
	}

	@Test
	void cloneFromLookPersistsADraftAndReportsUnresolvedGarments() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		String subject = InProcessJwtFixture.subject();
		UUID top = insertVaultForSubject(subject, "top");
		UUID bottom = insertVaultForSubject(subject, "bottom");
		UUID foreignItem = insertVaultForSubject("foreign-item-owner", "foreign");

		UUID ownedLook = insertLook(subject, new String[][] {
			{ "UPPER_BODY", top.toString() },
			{ "LOWER_BODY", bottom.toString() } });
		JsonNode cloned = JSON.readTree(mvc.perform(post("/studio/sessions/from-look")
				.header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"lookId\":\"" + ownedLook + "\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.unresolved.length()").value(0))
			.andReturn().getResponse().getContentAsString());
		assertThat(cloned.get("itemIds")).hasSize(2);
		String newSessionId = cloned.get("sessionId").stringValue();
		// The clone is a real persisted draft, not just an in-memory response.
		mvc.perform(get("/studio/sessions/" + newSessionId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.itemIds.length()").value(2));

		// A garment owned by someone else is reported, never silently dropped.
		UUID mixedLook = insertLook(subject, new String[][] {
			{ "UPPER_BODY", top.toString() },
			{ "LOWER_BODY", foreignItem.toString() } });
		JsonNode mixed = JSON.readTree(mvc.perform(post("/studio/sessions/from-look")
				.header(HttpHeaders.AUTHORIZATION, bearer(token)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"lookId\":\"" + mixedLook + "\"}"))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
		assertThat(mixed.get("itemIds")).hasSize(1);
		assertThat(mixed.get("unresolved")).hasSize(1);
		assertThat(mixed.get("unresolved").get(0).get("reason").stringValue()).isEqualTo("GARMENT_NOT_OWNED");

		// A foreign-owned or unknown look id discloses nothing; malformed is a 400.
		UUID foreignLook = insertLook("foreign-item-owner", new String[][] { { "UPPER_BODY", foreignItem.toString() } });
		for (String candidate : List.of(foreignLook.toString(), UUID.randomUUID().toString())) {
			mvc.perform(post("/studio/sessions/from-look").header(HttpHeaders.AUTHORIZATION, bearer(token))
					.contentType(MediaType.APPLICATION_JSON).content("{\"lookId\":\"" + candidate + "\"}"))
				.andExpect(status().isNotFound())
				.andExpect(content().json("{\"code\":\"STUDIO_SESSION_NOT_FOUND\"}"));
		}
		mvc.perform(post("/studio/sessions/from-look").header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content("{\"lookId\":\"bad\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().json("{\"code\":\"STUDIO_INVALID_REQUEST\"}"));
	}

	private UUID insertLook(String subject, String[][] items) {
		UUID user = jdbc.queryForObject("SELECT user_id FROM identity_mapping WHERE subject=?", UUID.class, subject);
		UUID lookId = UUID.randomUUID();
		jdbc.update("INSERT INTO look(id,user_id,storage_key,quality_status,created_at) VALUES(?,?,?,?,?)",
			lookId, user, "users/" + user + "/looks/" + lookId + ".png", "PASS", Timestamp.from(NOW.get()));
		for (String[] item : items) {
			jdbc.update("INSERT INTO look_item(look_id,vault_item_id,slot) VALUES(?,?,?)",
				lookId, UUID.fromString(item[1]), item[0]);
		}
		return lookId;
	}

	private JsonNode create(String token) throws Exception {
		return JSON.readTree(mvc.perform(post("/studio/sessions").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString());
	}

	private JsonNode command(String token, String sessionId, Instant expected, String body) throws Exception {
		String payload = body.substring(0, body.length() - 1) + ",\"expectedUpdatedAt\":\"" + expected + "\"}";
		return JSON.readTree(mvc.perform(post("/studio/sessions/" + sessionId + "/commands")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON).content(payload))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
	}

	private static void advance() {
		NOW.set(NOW.get().plusSeconds(1));
	}

	private UUID insertVaultForSubject(String subject, String label) {
		String token = InProcessJwtFixture.bearerTokenForSubject(subject);
		try {
			mvc.perform(get("/studio/sessions/active").header(HttpHeaders.AUTHORIZATION, bearer(token)));
		} catch (Exception e) {
			throw new RuntimeException(e);
		}
		UUID user = jdbc.queryForObject("SELECT user_id FROM identity_mapping WHERE subject=?", UUID.class, subject);
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO vault_item(id,user_id,storage_key,created_at) VALUES(?,?,?,?)",
			id, user, "users/" + user + "/vault/" + id + label, Timestamp.from(NOW.get()));
		return id;
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	@TestConfiguration
	static class Beans {
		@Bean @Primary ExternalTokenVerifier verifier() {
			return InProcessJwtFixture.verifier();
		}

		@Bean @Primary Clock tickingClock() {
			return new Clock() {
				@Override public ZoneId getZone() {
					return ZoneOffset.UTC;
				}

				@Override public Clock withZone(ZoneId zone) {
					return this;
				}

				@Override public Instant instant() {
					return NOW.get();
				}
			};
		}
	}
}
