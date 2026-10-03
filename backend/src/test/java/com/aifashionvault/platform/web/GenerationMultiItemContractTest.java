package com.aifashionvault.platform.web;

import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.generation.vton.DefaultProviderRegistry;
import com.aifashionvault.generation.vton.ProviderRegistry;
import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.GenerationMediaStore;
import com.aifashionvault.platform.objectstore.ProductInputClaimCommand;
import com.aifashionvault.platform.generation.RecordingVtonProvider;
import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * C12 contract: the Studio multi-item create form freezes avatar revision, ordered
 * owned Vault items, mode and request identity into one durable accepted intent
 * through the existing outbox pipeline.
 */
@SpringBootTest(properties = {
	"spring.autoconfigure.exclude=",
	"vault.generation.multi-item-enabled=true"
})
@AutoConfigureMockMvc
class GenerationMultiItemContractTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres").withStartupTimeout(Duration.ofMinutes(5));
	private static final ObjectMapper MAPPER = new ObjectMapper();

	static { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); }

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired RecordingVtonProvider provider;

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		POSTGRES.start();
		registry.add("vault.db.url", POSTGRES::getJdbcUrl);
		registry.add("vault.db.username", POSTGRES::getUsername);
		registry.add("vault.db.password", POSTGRES::getPassword);
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@BeforeEach
	void clearTables() {
		jdbc.update("TRUNCATE TABLE generation_admission_counter, generation_admission_reservation, look_item, look, quality_result, "
			+ "generation_callback_inbox, generation_work_outbox, generation_quota_reservation, "
			+ "generation_notification_delivery, generation_notification_event, generation_provider_capacity_slot, "
			+ "generation_attempt, generation_render_step_item, generation_render_step, generation_job_product, "
			+ "generation_job, vault_item, studio_item, studio_session, avatar_image, avatar_profile, identity_mapping, "
			+ "users CASCADE");
	}

	@Test
	void multiCreateFreezesOrderedItemsAndEnqueuesExactlyOneJob() throws Exception {
		Owner owner = seedOwner();
		List<UUID> items = List.of(insertVaultItem(owner.userId(), "TOPS"), insertVaultItem(owner.userId(), "BOTTOMS"),
			insertVaultItem(owner.userId(), "FOOTWEAR"));

		MvcResult result = createMulti(owner, "multi-key-1", items, "standard", null);
		JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());
		assertThat(body.propertyNames()).containsExactly("id", "status", "phase", "statusVersion", "createdAt", "updatedAt");
		assertThat(body.get("status").stringValue()).isEqualTo("QUEUED");
		UUID id = UUID.fromString(body.get("id").stringValue());
		assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/generations/" + id);

		assertThat(count("generation_job")).isEqualTo(1);
		assertThat(count("generation_work_outbox")).isEqualTo(1);
		assertThat(count("generation_quota_reservation")).isEqualTo(1);
		assertThat(count("generation_job_product")).isEqualTo(3);
		assertThat(jdbc.queryForObject("SELECT requested_mode FROM generation_job WHERE id = ?", String.class, id))
			.isEqualTo("standard");
		assertThat(jdbc.queryForObject("SELECT total_steps FROM generation_job WHERE id = ?", Integer.class, id)).isEqualTo(3);
		assertThat(jdbc.queryForObject("SELECT studio_session_id FROM generation_job WHERE id = ?", UUID.class, id)).isNull();
		// The submitted ORDER is the frozen order: one render step per product, in order.
		assertThat(renderStepOrder(id)).containsExactlyElementsOf(items);
		// No provider call happens at accept time.
		assertThat(provider.submitCount()).isZero();
	}

	@Test
	void multiCreateRecordsOwnedStudioSessionLineage() throws Exception {
		Owner owner = seedOwner();
		UUID session = insertStudioSession(owner.userId());
		List<UUID> items = List.of(insertVaultItem(owner.userId(), "TOPS"), insertVaultItem(owner.userId(), "BOTTOMS"));

		UUID id = UUID.fromString(json(createMulti(owner, "multi-session-key", items, "standard", session)).get("id").stringValue());

		assertThat(jdbc.queryForObject("SELECT studio_session_id FROM generation_job WHERE id = ?", UUID.class, id))
			.isEqualTo(session);
	}

	@Test
	void multiCreateCommitsTheSameAbuseControlAdmissionAsTheManualPath() throws Exception {
		Owner owner = seedOwner();
		List<UUID> items = List.of(insertVaultItem(owner.userId(), "TOPS"), insertVaultItem(owner.userId(), "FEET"));

		createMulti(owner, "admission-commit-key", items, "standard", null);

		// Three bucket rows (user/device/ip) committed together is the rate limiter's
		// observable footprint; the manual path leaves the same trace.
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_admission_reservation WHERE committed_at IS NOT NULL",
			Integer.class)).isEqualTo(3);
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_admission_reservation WHERE released_at IS NOT NULL",
			Integer.class)).isZero();
	}

	@Test
	void multiCreateIsRateLimitedLikeTheManualPathAndReservesNothing() throws Exception {
		Owner owner = seedOwner();
		List<UUID> items = List.of(insertVaultItem(owner.userId(), "TOPS"), insertVaultItem(owner.userId(), "FEET"));
		// Exhaust this user's daily admission bucket for today (UTC), the day the limiter uses.
		jdbc.update("INSERT INTO generation_admission_counter (id, bucket_key, bucket_day, count) VALUES (?, ?, CURRENT_DATE, ?)",
			UUID.randomUUID(), "user:" + owner.userId().value(), 100000L);

		mockMvc.perform(post("/generations/multi").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "rate-limited-key").contentType(MediaType.APPLICATION_JSON)
				.content(multiBody(null, items, "standard")))
			.andExpect(status().isTooManyRequests())
			.andExpect(content().string("{\"code\":\"USER_DAILY_LIMIT\"}"));

		assertThat(count("generation_job")).isZero();
		assertThat(count("generation_work_outbox")).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_admission_reservation WHERE committed_at IS NOT NULL",
			Integer.class)).isZero();
	}

	@Test
	void duplicateMultiCreateReplaysOneLogicalJob() throws Exception {
		Owner owner = seedOwner();
		List<UUID> items = List.of(insertVaultItem(owner.userId(), "TOPS"), insertVaultItem(owner.userId(), "FOOTWEAR"));

		JsonNode first = json(createMulti(owner, "stable-multi", items, "standard", null));
		JsonNode replay = json(createMulti(owner, "stable-multi", items, "standard", null));

		assertThat(replay.get("id").stringValue()).isEqualTo(first.get("id").stringValue());
		assertThat(count("generation_job")).isEqualTo(1);
		assertThat(count("generation_work_outbox")).isEqualTo(1);
		assertThat(count("generation_quota_reservation")).isEqualTo(1);
	}

	@Test
	void sameKeyWithDifferentItemsOrOrderConflicts() throws Exception {
		Owner owner = seedOwner();
		UUID top = insertVaultItem(owner.userId(), "TOPS");
		UUID bottom = insertVaultItem(owner.userId(), "BOTTOMS");
		createMulti(owner, "conflict-key", List.of(top, bottom), "standard", null);

		mockMvc.perform(post("/generations/multi").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "conflict-key").contentType(MediaType.APPLICATION_JSON)
				.content(multiBody(null, List.of(bottom, top), "standard")))
			.andExpect(status().isConflict())
			.andExpect(content().string("{\"code\":\"IDEMPOTENCY_KEY_REUSED\"}"));
		mockMvc.perform(post("/generations/multi").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "conflict-key").contentType(MediaType.APPLICATION_JSON)
				.content(multiBody(null, List.of(top), "standard")))
			.andExpect(status().isConflict())
			.andExpect(content().string("{\"code\":\"IDEMPOTENCY_KEY_REUSED\"}"));

		assertThat(count("generation_job")).isEqualTo(1);
		assertThat(count("generation_work_outbox")).isEqualTo(1);
	}

	@Test
	void foreignUnownedAndDuplicateItemsAreRejectedWithoutSideEffects() throws Exception {
		Owner owner = seedOwner();
		Owner stranger = seedOwnerForSubject("foreign-multi-owner");
		UUID owned = insertVaultItem(owner.userId(), "TOPS");
		UUID foreign = insertVaultItem(stranger.userId(), "BOTTOMS");

		mockMvc.perform(post("/generations/multi").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "foreign-key").contentType(MediaType.APPLICATION_JSON)
				.content(multiBody(null, List.of(owned, foreign), "standard")))
			.andExpect(status().isNotFound())
			.andExpect(content().string("{\"code\":\"UNAUTHORIZED_OR_NOT_FOUND\"}"));
		mockMvc.perform(post("/generations/multi").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "unknown-key").contentType(MediaType.APPLICATION_JSON)
				.content(multiBody(null, List.of(owned, UUID.randomUUID()), "standard")))
			.andExpect(status().isNotFound())
			.andExpect(content().string("{\"code\":\"UNAUTHORIZED_OR_NOT_FOUND\"}"));
		mockMvc.perform(post("/generations/multi").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "duplicate-key").contentType(MediaType.APPLICATION_JSON)
				.content(multiBody(null, List.of(owned, owned), "standard")))
			.andExpect(status().isBadRequest())
			.andExpect(content().string("{\"code\":\"MULTI_ITEM_INVALID\"}"));
		mockMvc.perform(post("/generations/multi").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "empty-key").contentType(MediaType.APPLICATION_JSON)
				.content(multiBody(null, List.of(), "standard")))
			.andExpect(status().isBadRequest())
			.andExpect(content().string("{\"code\":\"MULTI_ITEM_INVALID\"}"));
		mockMvc.perform(post("/generations/multi").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "bad-mode-key").contentType(MediaType.APPLICATION_JSON)
				.content(multiBody(null, List.of(owned), "NOT A MODE")))
			.andExpect(status().isBadRequest())
			.andExpect(content().string("{\"code\":\"MULTI_ITEM_INVALID\"}"));
		mockMvc.perform(post("/generations/multi").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.contentType(MediaType.APPLICATION_JSON).content(multiBody(null, List.of(owned), "standard")))
			.andExpect(status().isBadRequest())
			.andExpect(content().string("{\"code\":\"IDEMPOTENCY_KEY_REQUIRED\"}"));

		assertThat(count("generation_job")).isZero();
		assertThat(count("generation_work_outbox")).isZero();
		assertThat(count("generation_quota_reservation")).isZero();
		assertThat(count("generation_job_product")).isZero();
		assertThat(provider.submitCount()).isZero();
	}

	@Test
	void foreignStudioSessionLineageIsRejectedWithoutSideEffects() throws Exception {
		Owner owner = seedOwner();
		Owner stranger = seedOwnerForSubject("foreign-session-owner");
		UUID foreignSession = insertStudioSession(stranger.userId());
		UUID owned = insertVaultItem(owner.userId(), "TOPS");

		mockMvc.perform(post("/generations/multi").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "foreign-session-key").contentType(MediaType.APPLICATION_JSON)
				.content(multiBody(foreignSession, List.of(owned), "standard")))
			.andExpect(status().isNotFound())
			.andExpect(content().string("{\"code\":\"UNAUTHORIZED_OR_NOT_FOUND\"}"));

		assertThat(count("generation_job")).isZero();
		assertThat(count("generation_work_outbox")).isZero();
	}

	private List<UUID> renderStepOrder(UUID jobId) {
		return jdbc.query(
			"SELECT i.vault_item_id FROM generation_render_step s "
				+ "JOIN generation_render_step_item i ON i.render_step_id = s.id "
				+ "WHERE s.generation_job_id = ? ORDER BY s.step_index, i.item_ordinal",
			(rs, row) -> rs.getObject("vault_item_id", UUID.class),
			jobId);
	}

	private MvcResult createMulti(Owner owner, String key, List<UUID> items, String mode, UUID session) throws Exception {
		return mockMvc.perform(post("/generations/multi").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
				.content(multiBody(session, items, mode)))
			.andExpect(status().isAccepted()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(header().exists(HttpHeaders.LOCATION)).andReturn();
	}

	private static String multiBody(UUID session, List<UUID> items, String mode) {
		StringBuilder builder = new StringBuilder("{\"studioSessionId\":");
		builder.append(session == null ? "null" : "\"" + session + "\"");
		builder.append(",\"productVaultItemIds\":[");
		for (int i = 0; i < items.size(); i++) {
			if (i > 0) builder.append(',');
			builder.append('"').append(items.get(i)).append('"');
		}
		builder.append("],\"mode\":").append(mode == null ? "null" : "\"" + mode + "\"");
		return builder.append('}').toString();
	}

	private static JsonNode json(MvcResult result) throws Exception {
		return MAPPER.readTree(result.getResponse().getContentAsString());
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private Owner seedOwner() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		mockMvc.perform(MockMvcRequestBuilders.get("/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk());
		UUID user = jdbc.queryForObject("SELECT user_id FROM identity_mapping WHERE subject = ?", UUID.class,
			InProcessJwtFixture.subject());
		UserId userId = new UserId(user);
		insertProfileWithImage(userId);
		return new Owner(token, userId);
	}

	/** A second identity in the same database, used to prove owner scoping. */
	private Owner seedOwnerForSubject(String subject) throws Exception {
		String token = InProcessJwtFixture.bearerTokenForSubject(subject);
		mockMvc.perform(MockMvcRequestBuilders.get("/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk());
		UUID user = jdbc.queryForObject("SELECT user_id FROM identity_mapping WHERE subject = ?", UUID.class, subject);
		UserId userId = new UserId(user);
		insertProfileWithImage(userId);
		return new Owner(token, userId);
	}

	private void insertProfileWithImage(UserId userId) {
		UUID image = UUID.randomUUID();
		UUID profile = UUID.randomUUID();
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.update("INSERT INTO avatar_profile (id, user_id, primary_image_id, status, revision, created_at, updated_at) VALUES (?, ?, NULL, 'ACTIVE', 1, ?, ?)",
			profile, userId.value(), now, now);
		jdbc.update("INSERT INTO avatar_image (id, profile_id, storage_key, quality_status, created_at) VALUES (?, ?, ?, 'ACCEPTED', ?)",
			image, profile, "users/" + userId.value() + "/avatars/" + image + ".jpg", now);
		jdbc.update("UPDATE avatar_profile SET primary_image_id = ? WHERE id = ?", image, profile);
	}

	private UUID insertVaultItem(UserId userId, String category) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO vault_item (id, user_id, storage_key, name, category, created_at) VALUES (?, ?, ?, ?, ?, ?)",
			id, userId.value(), "users/" + userId.value() + "/vault/" + id + ".jpg", "item-" + id, category,
			Timestamp.from(Instant.now()));
		return id;
	}

	private UUID insertStudioSession(UserId userId) {
		UUID id = UUID.randomUUID();
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.update("INSERT INTO studio_session (id, user_id, avatar_profile_id, created_at, updated_at) VALUES (?, ?, NULL, ?, ?)",
			id, userId.value(), now, now);
		return id;
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
	}

	private record Owner(String token, UserId userId) {}

	@TestConfiguration
	static class Configuration {
		@Bean @Primary ExternalTokenVerifier verifier() {
			return InProcessJwtFixture.verifier();
		}

		@Bean @Primary RecordingVtonProvider provider() {
			return new RecordingVtonProvider();
		}

		@Bean @Primary ProviderRegistry testProviderRegistry(RecordingVtonProvider recordingProvider) {
			return new DefaultProviderRegistry(
				List.of(recordingProvider), recordingProvider.providerKey(), java.util.Map.of());
		}

		// RULE: never reuse a production bean name for a test bean (BeanDefinitionOverrideException).
		@Bean GenerationMediaStore stubGenerationMediaStore() {
			return new GenerationMediaStore() {
				@Override public void putBounded(StorageKey dest, InputStream source, long maxBytes, String mediaType) { }
				@Override public ClaimResult claim(com.aifashionvault.platform.objectstore.GenerationClaimCommand command) {
					throw new UnsupportedOperationException("generation claim");
				}
				@Override public void preflightProductInput(ProductInputClaimCommand command) { }
				@Override public ClaimResult claimProductInput(ProductInputClaimCommand command) {
					return new ClaimResult.Copied();
				}
				@Override public void releaseProductInput(ProductInputClaimCommand command) { }
			};
		}
	}
}
