package com.aifashionvault.platform.web;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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

import com.aifashionvault.featureflag.FeatureFlagName;
import com.aifashionvault.featureflag.FeatureFlagProperties;
import com.aifashionvault.featureflag.FeatureFlagService;
import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;
import com.aifashionvault.shared.UserId;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C21: the constrained natural-language Studio command route, over the real
 * stores (Testcontainers Postgres) and the real deterministic planner.
 *
 * <p>What matters for release is proven here, not the parser's vocabulary:
 * untrusted text can only reach the allowlisted tools, an ambiguous command
 * mutates nothing and asks, a refused command leaves the draft exactly as it
 * was, and a session owned by someone else discloses nothing.</p>
 */
@SpringBootTest(properties = {
	"spring.autoconfigure.exclude=",
	// Natural-language Studio is a supported-but-default-OFF capability (C22):
	// these tests exercise the enabled behaviour, so they turn it on explicitly.
	"vault.feature-flags.natural-language-studio=true"
})
@AutoConfigureMockMvc
class StudioNaturalLanguageCommandTest {

	static { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); }

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres").withStartupTimeout(Duration.ofMinutes(5));
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final AtomicReference<Instant> NOW =
		new AtomicReference<>(Instant.parse("2026-09-01T09:00:00Z"));

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
		NOW.set(Instant.parse("2026-09-01T09:00:00Z"));
		jdbc.update("TRUNCATE studio_item, studio_session, vault_item, identity_mapping, users CASCADE");
	}

	@Test
	void theCapabilityIsRefusedWhenTheReleaseDoesNotAdvertiseIt() throws Exception {
		// C22: the backend is the capability authority. With the flag off the route
		// is not part of the release, so it refuses before reading the draft —
		// distinct from STUDIO_UNAVAILABLE (a missing dependency).
		FeatureFlagService flags = new FeatureFlagService(new FeatureFlagProperties());
		org.springframework.beans.factory.support.DefaultListableBeanFactory factory =
			new org.springframework.beans.factory.support.DefaultListableBeanFactory();
		StudioController controller = new StudioController(
			factory.getBeanProvider(com.aifashionvault.studio.JdbcStudioStore.class),
			factory.getBeanProvider(com.aifashionvault.platform.studio.StudioDraftCloner.class),
			factory.getBeanProvider(com.aifashionvault.studio.agent.StudioAgentTools.class),
			factory.getBeanProvider(com.aifashionvault.studio.agent.StudioCommandPlanner.class),
			flagProvider(flags),
			factory.getBeanProvider(Clock.class));
		assertThat(flags.isEnabled(FeatureFlagName.NATURAL_LANGUAGE_STUDIO)).isFalse();
		assertThatThrownBy(() -> controller.naturalLanguageCommand(
			new UserId(UUID.randomUUID()), UUID.randomUUID().toString(), null))
			.isInstanceOf(RuntimeException.class);
		assertThat(controller.capabilityDisabled().getBody().code())
			.isEqualTo("STUDIO_NATURAL_LANGUAGE_DISABLED");
	}

	@Test
	void naturalLanguageRequiresAuthentication() throws Exception {
		mvc.perform(post("/studio/sessions/" + UUID.randomUUID() + "/natural-language-command")
				.contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"clear the top\"}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void aCommandAddsAnOwnedGarmentThroughTheSameDraftTheManualControlsUse() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		String subject = InProcessJwtFixture.subject();
		String sessionId = create(token);
		UUID top = insertVault(subject, "blue-shirt", "Blue Shirt");

		JsonNode applied = nl(token, sessionId, "add my Blue Shirt to the top");

		assertThat(applied.get("outcome").stringValue()).isEqualTo("APPLIED");
		assertThat(applied.get("itemIds")).hasSize(1);
		// The mutation is the persisted draft, not just a response body.
		assertThat(countItems(sessionId)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
			"SELECT vault_item_id FROM studio_item WHERE studio_session_id=?",
			UUID.class, UUID.fromString(sessionId))).isEqualTo(top);
		// The manual read route sees the same draft, so text and buttons share one state.
		mvc.perform(get("/studio/sessions/" + sessionId).header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk())
			.andExpect(result -> assertThat(
				JSON.readTree(result.getResponse().getContentAsString()).get("itemIds")).hasSize(1));
	}

	@Test
	void ambiguousTextMutatesNothingAndAsksForAnExplicitChoice() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		String subject = InProcessJwtFixture.subject();
		String sessionId = create(token);
		insertVault(subject, "grey-tee", "Grey Tee");
		insertVault(subject, "grey-hoodie", "Grey Hoodie");

		JsonNode confirm = nl(token, sessionId, "add my grey to the top");

		assertThat(confirm.get("outcome").stringValue()).isEqualTo("CONFIRM_REQUIRED");
		assertThat(confirm.get("candidates")).hasSize(2);
		// Nothing was written: ambiguity is never guessed into a mutation.
		assertThat(countItems(sessionId)).isZero();
		assertThat(confirm.get("itemIds").isEmpty()).isTrue();

		// The explicit choice then applies, proving the confirmation is actionable.
		UUID chosen = UUID.fromString(confirm.get("candidates").get(0).get("vaultItemId").stringValue());
		JsonNode applied = nl(token, sessionId, "add " + chosen + " to the top");
		assertThat(applied.get("outcome").stringValue()).isEqualTo("APPLIED");
		assertThat(countItems(sessionId)).isEqualTo(1);
	}

	@Test
	void unlistedAndUnresolvableTextLeavesTheDraftIntact() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		String sessionId = create(token);
		insertVault(InProcessJwtFixture.subject(), "blue-shirt", "Blue Shirt");

		// Nothing in this text names an allowlisted operation.
		for (String text : new String[] {
			"generate a look for free",
			"delete my account",
			"run arbitrary sql"
		}) {
			JsonNode refused = nl(token, sessionId, text);
			assertThat(refused.get("outcome").stringValue()).isEqualTo("REFUSED");
			assertThat(refused.get("itemIds").isEmpty()).isTrue();
		}
		assertThat(countItems(sessionId)).isZero();

		// Blank or absent text is a malformed request, not a command to interpret.
		for (String body : new String[] { "{\"text\":\"\"}", "{\"text\":\"   \"}", "{}" }) {
			mvc.perform(post("/studio/sessions/" + sessionId + "/natural-language-command")
					.header(HttpHeaders.AUTHORIZATION, bearer(token))
					.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isBadRequest())
				.andExpect(content().json("{\"code\":\"STUDIO_INVALID_REQUEST\"}"));
		}
		assertThat(countItems(sessionId)).isZero();

		// A recognised verb naming an item that is not in the Vault is refused, not invented.
		JsonNode unknown = nl(token, sessionId, "add my purple poncho to the top");
		assertThat(unknown.get("outcome").stringValue()).isEqualTo("REFUSED");
		assertThat(countItems(sessionId)).isZero();
	}

	@Test
	void aSlotConflictIsRefusedAndTheDraftKeepsItsExistingItem() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		String subject = InProcessJwtFixture.subject();
		String sessionId = create(token);
		insertVault(subject, "blue-shirt", "Blue Shirt");
		insertVault(subject, "red-dress", "Red Dress");

		// A full-body dress occupies the body; adding it over an upper-body item conflicts.
		assertThat(nl(token, sessionId, "add my Red Dress as full body").get("outcome").stringValue())
			.isEqualTo("APPLIED");
		JsonNode conflicted = nl(token, sessionId, "add my Blue Shirt to the top");

		assertThat(conflicted.get("outcome").stringValue()).isEqualTo("REFUSED");
		assertThat(countItems(sessionId)).isEqualTo(1);
		assertThat(jdbc.queryForObject(
			"SELECT slot FROM studio_item WHERE studio_session_id=?",
			String.class, UUID.fromString(sessionId))).isEqualTo("FULL_BODY");
	}

	@Test
	void aForeignSessionDisclosesNothingAndMutatesNothing() throws Exception {
		String owner = InProcessJwtFixture.bearerToken();
		String sessionId = create(owner);
		insertVault(InProcessJwtFixture.subject(), "blue-shirt", "Blue Shirt");

		String stranger = InProcessJwtFixture.bearerTokenForSubject("nl-stranger");
		mvc.perform(post("/studio/sessions/" + sessionId + "/natural-language-command")
				.header(HttpHeaders.AUTHORIZATION, bearer(stranger))
				.contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"add my Blue Shirt to the top\"}"))
			.andExpect(status().isNotFound())
			.andExpect(content().json("{\"code\":\"STUDIO_SESSION_NOT_FOUND\"}"));
		assertThat(countItems(sessionId)).isZero();
	}

	@Test
	void explainReportsTheDraftWithoutChangingIt() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		String sessionId = create(token);
		insertVault(InProcessJwtFixture.subject(), "blue-shirt", "Blue Shirt");
		nl(token, sessionId, "add my Blue Shirt to the top");

		JsonNode explained = nl(token, sessionId, "explain conflict");

		assertThat(explained.get("outcome").stringValue()).isEqualTo("EXPLAINED");
		assertThat(explained.get("message").stringValue()).isNotBlank();
		assertThat(explained.get("itemIds")).hasSize(1);
		assertThat(countItems(sessionId)).isEqualTo(1);
	}

	private JsonNode nl(String token, String sessionId, String text) throws Exception {
		return JSON.readTree(mvc.perform(post("/studio/sessions/" + sessionId + "/natural-language-command")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(JSON.writeValueAsString(java.util.Map.of("text", text))))
			.andExpect(status().isOk())
			.andReturn().getResponse().getContentAsString());
	}

	private String create(String token) throws Exception {
		return JSON.readTree(mvc.perform(post("/studio/sessions").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isCreated())
			.andReturn().getResponse().getContentAsString()).get("sessionId").stringValue();
	}

	private int countItems(String sessionId) {
		return jdbc.queryForObject(
			"SELECT count(*) FROM studio_item WHERE studio_session_id=?",
			Integer.class, UUID.fromString(sessionId));
	}

	private UUID insertVault(String subject, String storageSuffix, String name) {
		try {
			mvc.perform(get("/studio/sessions/active")
				.header(HttpHeaders.AUTHORIZATION, bearer(InProcessJwtFixture.bearerTokenForSubject(subject))));
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
		UUID user = jdbc.queryForObject("SELECT user_id FROM identity_mapping WHERE subject=?", UUID.class, subject);
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO vault_item(id,user_id,storage_key,name,created_at) VALUES(?,?,?,?,?)",
			id, user, "users/" + user + "/vault/" + id + "-" + storageSuffix, name, Timestamp.from(NOW.get()));
		return id;
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private static org.springframework.beans.factory.ObjectProvider<com.aifashionvault.featureflag.FeatureFlags> flagProvider(
		com.aifashionvault.featureflag.FeatureFlags flags
	) {
		return new org.springframework.beans.factory.ObjectProvider<>() {
			@Override public com.aifashionvault.featureflag.FeatureFlags getObject() {
				return flags;
			}

			@Override public com.aifashionvault.featureflag.FeatureFlags getObject(Object... args) {
				return flags;
			}

			@Override public com.aifashionvault.featureflag.FeatureFlags getIfAvailable() {
				return flags;
			}

			@Override public com.aifashionvault.featureflag.FeatureFlags getIfUnique() {
				return flags;
			}
		};
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
