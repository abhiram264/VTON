package com.aifashionvault.platform.web;
import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@AutoConfigureMockMvc
class GenerationHttpContractTest {

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
		jdbc.update("TRUNCATE TABLE generation_admission_counter, look_item, look, quality_result, "
			+ "generation_callback_inbox, generation_work_outbox, generation_quota_reservation, "
			+ "generation_notification_delivery, generation_notification_event, generation_provider_capacity_slot, "
			+ "generation_attempt, generation_render_step_item, generation_render_step, generation_job_product, "
			+ "generation_job, vault_item, studio_item, studio_session, avatar_image, avatar_profile, identity_mapping, "
			+ "users CASCADE");
	}

	@Test
	void createReturnsExact202ResourceAndDoesNotCallProvider() throws Exception {
		Owner owner = seedOwner(true);
		MvcResult result = create(owner, "key-1", owner.uploadId());
		JsonNode body = MAPPER.readTree(result.getResponse().getContentAsString());

		assertThat(body.propertyNames()).containsExactly(
			"id", "status", "phase", "statusVersion", "createdAt", "updatedAt");
		assertThat(body.get("status").stringValue()).isEqualTo("QUEUED");
		assertThat(body.get("phase").stringValue()).isEqualTo("QUEUED");
		assertThat(body.get("statusVersion").intValue()).isZero();
		Instant.parse(body.get("createdAt").stringValue());
		Instant.parse(body.get("updatedAt").stringValue());
		UUID id = UUID.fromString(body.get("id").stringValue());
		assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/generations/" + id);
		assertThat(count("generation_job")).isEqualTo(1);
		assertThat(count("generation_work_outbox")).isEqualTo(1);
		assertThat(count("generation_attempt")).isZero();
		assertThat(provider.submitCount()).isZero();
	}


	@Test
	void missingAndMalformedInputUseStableCodes() throws Exception {
		Owner owner = seedOwner(true);
		mockMvc.perform(post("/generations").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.contentType(MediaType.APPLICATION_JSON).content(body(owner.uploadId())))
			.andExpect(status().isBadRequest())
			.andExpect(content().string("{\"code\":\"IDEMPOTENCY_KEY_REQUIRED\"}"));
		mockMvc.perform(post("/generations").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "key-malformed").contentType(MediaType.APPLICATION_JSON).content("{"))
			.andExpect(status().isBadRequest())
			.andExpect(content().string("{\"code\":\"PRODUCT_UPLOAD_INVALID\"}"));
		mockMvc.perform(post("/generations").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "key-missing-upload").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().string("{\"code\":\"PRODUCT_UPLOAD_MISSING\"}"));
	}

	@Test
	void myModelMissingAndMediaMissingUseStableCodesWithoutPersistence() throws Exception {
		Owner missing = seedOwner(false);
		mockMvc.perform(post("/generations").header(HttpHeaders.AUTHORIZATION, bearer(missing.token()))
				.header("Idempotency-Key", "key-no-model").contentType(MediaType.APPLICATION_JSON).content(body(missing.uploadId())))
			.andExpect(status().isNotFound())
			.andExpect(content().string("{\"code\":\"MY_MODEL_MISSING\"}"));

		UUID profile = insertProfile(missing.userId(), null);
		mockMvc.perform(post("/generations").header(HttpHeaders.AUTHORIZATION, bearer(missing.token()))
				.header("Idempotency-Key", "key-no-media").contentType(MediaType.APPLICATION_JSON).content(body(missing.uploadId())))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(content().string("{\"code\":\"MY_MODEL_MEDIA_MISSING\"}"));
		assertThat(profile).isNotNull();
		assertThat(count("generation_job")).isZero();
		assertThat(provider.submitCount()).isZero();
	}

	@Test
	void sameKeyReplayAndDifferentRequestConflict() throws Exception {
		Owner owner = seedOwner(true);
		JsonNode first = json(create(owner, "stable-key", owner.uploadId()));
		JsonNode replay = json(create(owner, "stable-key", owner.uploadId()));
		assertThat(replay.get("id").stringValue()).isEqualTo(first.get("id").stringValue());

		mockMvc.perform(post("/generations").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "stable-key").contentType(MediaType.APPLICATION_JSON)
				.content(body(UUID.randomUUID())))
			.andExpect(status().isConflict())
			.andExpect(content().string("{\"code\":\"IDEMPOTENCY_KEY_REUSED\"}"));
		assertThat(count("generation_job")).isEqualTo(1);
		assertThat(count("generation_work_outbox")).isEqualTo(1);
	}

	@Test
	void distinctIntentCannotClaimSameProductUpload() throws Exception {
		Owner owner = seedOwner(true);
		create(owner, "first-key", owner.uploadId());
		mockMvc.perform(post("/generations").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "second-key").contentType(MediaType.APPLICATION_JSON).content(body(owner.uploadId())))
			.andExpect(status().isConflict())
			.andExpect(content().string("{\"code\":\"PRODUCT_UPLOAD_ALREADY_CLAIMED\"}"));
	}

	@Test
	void getIsNonDisclosingAndConditionalFieldsAreAbsent() throws Exception {
		Owner owner = seedOwner(true);
		UUID id = UUID.fromString(json(create(owner, "get-key", owner.uploadId())).get("id").stringValue());
		JsonNode queued = get(owner, id);
		assertThat(queued.propertyNames()).containsExactly("id", "status", "phase", "statusVersion", "createdAt", "updatedAt");

		String stranger = InProcessJwtFixture.bearerTokenForSubject("foreign-generation-owner");
		mockMvc.perform(MockMvcRequestBuilders.get("/me").header(HttpHeaders.AUTHORIZATION, bearer(stranger))).andExpect(status().isOk());
		for (String candidate : Set.of(id.toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
			mockMvc.perform(MockMvcRequestBuilders.get("/generations/" + candidate).header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
				.andExpect(status().isNotFound())
				.andExpect(content().string("{\"code\":\"UNAUTHORIZED_OR_NOT_FOUND\"}"));
		}
	}

	@Test
	void completedAndFailedExposeOnlyTheirConditionalField() throws Exception {
		Owner owner = seedOwner(true);
		UUID completed = insertJob(owner, "COMPLETED", null);
		UUID lookId = UUID.randomUUID();
		jdbc.update("INSERT INTO look (id, user_id, avatar_profile_id, generation_job_id, storage_key, provider, provider_model, quality_status, created_at) VALUES (?, ?, ?, ?, ?, 'provider-secret', 'model-secret', 'PASS', ?)",
			lookId, owner.userId().value(), owner.profileId(), completed, "private/secret", Timestamp.from(Instant.now()));
		JsonNode completedBody = get(owner, completed);
		assertThat(completedBody.propertyNames()).containsExactly(
			"id", "status", "phase", "statusVersion", "createdAt", "updatedAt", "lookId");
		assertThat(completedBody.get("lookId").stringValue()).isEqualTo(lookId.toString());

		UUID failed = insertJob(owner, "FAILED", "QUALITY_REJECTED");
		JsonNode failedBody = get(owner, failed);
		assertThat(failedBody.propertyNames()).containsExactly(
			"id", "status", "phase", "statusVersion", "createdAt", "updatedAt", "failureCode");
		assertThat(failedBody.get("failureCode").stringValue()).isEqualTo("QUALITY_REJECTED");
	}

	@Test
	void cancellationIsIdempotentAndLateWorkCannotChangeTerminalState() throws Exception {
		Owner owner = seedOwner(true);
		UUID id = UUID.fromString(json(create(owner, "cancel-key", owner.uploadId())).get("id").stringValue());
		JsonNode first = json(mockMvc.perform(post("/generations/" + id + "/cancel")
				.header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
			.andExpect(status().isOk()).andReturn());
		assertThat(first.get("status").stringValue()).isEqualTo("FAILED");
		assertThat(first.get("phase").stringValue()).isEqualTo("FAILED");
		assertThat(first.get("failureCode").stringValue()).isEqualTo("CANCELLED_BY_USER");
		JsonNode replay = json(mockMvc.perform(post("/generations/" + id + "/cancel")
				.header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
			.andExpect(status().isOk()).andReturn());
		assertThat(replay).isEqualTo(first);
		assertThat(count("look")).isZero();
	}

	@Test
	void failedResourcesExposeStableSafeCodesVerbatim() throws Exception {
		Owner owner = seedOwner(true);
		for (String code : Set.of(
			"PROVIDER_SUBMISSION_AMBIGUOUS", "PROVIDER_UNAVAILABLE", "PROVIDER_OUTPUT_INVALID",
			"QUALITY_REJECTED", "QUALITY_RETRY_EXHAUSTED", "QUALITY_EVALUATOR_UNAVAILABLE",
			"CANCELLED_BY_USER", "SUBJECT_DELETED", "ORCHESTRATION_DEADLINE_EXCEEDED")) {
			JsonNode resource = get(owner, insertJob(owner, "FAILED", code));
			assertThat(resource.get("failureCode").stringValue()).isEqualTo(code);
			assertThat(resource.toString()).doesNotContain("provider-secret", "http://", "https://");
		}
	}

	private MvcResult create(Owner owner, String key, UUID uploadId) throws Exception {
		return mockMvc.perform(post("/generations").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body(uploadId)))
			.andExpect(status().isAccepted()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(header().exists(HttpHeaders.LOCATION)).andReturn();
	}

	private JsonNode get(Owner owner, UUID id) throws Exception {
		return json(mockMvc.perform(MockMvcRequestBuilders.get("/generations/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
			.andExpect(status().isOk()).andReturn());
	}

	private static JsonNode json(MvcResult result) throws Exception { return MAPPER.readTree(result.getResponse().getContentAsString()); }
	private static String body(UUID uploadId) { return "{\"productUploadId\":\"" + uploadId + "\"}"; }
	private static String bearer(String token) { return "Bearer " + token; }

	private Owner seedOwner(boolean withModel) throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		mockMvc.perform(MockMvcRequestBuilders.get("/me").header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());
		UUID user = jdbc.queryForObject("SELECT user_id FROM identity_mapping WHERE subject = ?", UUID.class, InProcessJwtFixture.subject());
		UserId userId = new UserId(user);
		UUID profile = withModel ? insertProfileWithImage(userId) : null;
		return new Owner(token, userId, profile, UUID.randomUUID());
	}

	private UUID insertProfileWithImage(UserId userId) {
		UUID image = UUID.randomUUID();
		UUID profile = insertProfile(userId, image);
		jdbc.update("INSERT INTO avatar_image (id, profile_id, storage_key, quality_status, created_at) VALUES (?, ?, ?, 'ACCEPTED', ?)",
			image, profile, "users/" + userId.value() + "/avatars/" + image + ".jpg", Timestamp.from(Instant.now()));
		jdbc.update("UPDATE avatar_profile SET primary_image_id = ? WHERE id = ?", image, profile);
		return profile;
	}

	private UUID insertProfile(UserId userId, UUID image) {
		UUID profile = UUID.randomUUID();
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.update("INSERT INTO avatar_profile (id, user_id, primary_image_id, status, revision, created_at, updated_at) VALUES (?, ?, NULL, 'ACTIVE', 1, ?, ?)",
			profile, userId.value(), now, now);
		return profile;
	}

	private UUID insertJob(Owner owner, String status, String reason) {
		UUID id = UUID.randomUUID();
		UUID image = jdbc.queryForObject("SELECT primary_image_id FROM avatar_profile WHERE id = ?", UUID.class, owner.profileId());
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.update("INSERT INTO generation_job (id, user_id, status, requested_mode, client_request_id, created_at, updated_at, completed_at, avatar_image_id, terminal_reason, status_version) VALUES (?, ?, ?, 'STANDARD', ?, ?, ?, ?, ?, ?, 0)",
			id, owner.userId().value(), status, "request-" + id, now, now,
			Set.of("COMPLETED", "FAILED").contains(status) ? now : null, image, reason);
		return id;
	}

	private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
	private record Owner(String token, UserId userId, UUID profileId, UUID uploadId) {}

    @TestConfiguration
    static class Configuration {
        @Bean @Primary ExternalTokenVerifier verifier() { return InProcessJwtFixture.verifier(); }
        @Bean @Primary RecordingVtonProvider provider() { return new RecordingVtonProvider(); }
        @Bean @Primary GenerationMediaStore generationMediaStore() {
            return new GenerationMediaStore() {
                @Override public void putBounded(StorageKey dest, InputStream source, long maxBytes, String mediaType) { }
                @Override public ClaimResult claim(com.aifashionvault.platform.objectstore.GenerationClaimCommand command) {
                    throw new UnsupportedOperationException("generation claim");
                }
				@Override public void preflightProductInput(ProductInputClaimCommand command) { }
				@Override public ClaimResult claimProductInput(ProductInputClaimCommand command) { return new ClaimResult.Copied(); }
				@Override public void releaseProductInput(ProductInputClaimCommand command) { }
            };
        }
    }
}
