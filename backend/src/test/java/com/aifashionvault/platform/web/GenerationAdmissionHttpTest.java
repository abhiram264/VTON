package com.aifashionvault.platform.web;

import java.io.InputStream;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.GenerationMediaStore;
import com.aifashionvault.platform.objectstore.ProductInputClaimCommand;
import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@AutoConfigureMockMvc
class GenerationAdmissionHttpTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres").withStartupTimeout(Duration.ofMinutes(5));
	static { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); }

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired GenerationApiProperties properties;

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
	void reset() {
		properties.setAdmissionPaused(false);
		jdbc.update("TRUNCATE TABLE look_item, look, quality_result, generation_callback_inbox, generation_work_outbox, "
			+ "generation_quota_reservation, generation_notification_delivery, generation_notification_event, "
			+ "generation_provider_capacity_slot, generation_attempt, generation_render_step_item, generation_render_step, "
			+ "generation_job_product, generation_job, vault_item, studio_item, studio_session, avatar_image, "
			+ "avatar_profile, identity_mapping, users CASCADE");
	}

	@Test
	void authenticationPrecedesRequestAndAdmissionChecks() throws Exception {
		properties.setAdmissionPaused(true);
		mockMvc.perform(post("/generations").contentType(MediaType.APPLICATION_JSON).content("{"))
			.andExpect(status().isUnauthorized());
		assertNoEffects();
	}

	@Test
	void idempotencyKeyPrecedesBodyAdmissionAndMyModelChecks() throws Exception {
		Owner owner = seedOwner(false);
		properties.setAdmissionPaused(true);
		mockMvc.perform(post("/generations").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().string("{\"code\":\"IDEMPOTENCY_KEY_REQUIRED\"}"));
		assertNoEffects();
	}

	@Test
	void requestShapePrecedesAdmissionAndMyModelChecks() throws Exception {
		Owner owner = seedOwner(false);
		properties.setAdmissionPaused(true);
		mockMvc.perform(post("/generations").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "request-before-admission")
				.contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().string("{\"code\":\"PRODUCT_UPLOAD_MISSING\"}"));
		assertNoEffects();
	}

	@Test
	void admissionPausePrecedesMyModelAndUploadClaims() throws Exception {
		Owner owner = seedOwner(false);
		properties.setAdmissionPaused(true);
		mockMvc.perform(post("/generations").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "pause-before-model")
				.contentType(MediaType.APPLICATION_JSON).content(body(owner.uploadId())))
			.andExpect(status().isServiceUnavailable())
			.andExpect(content().string("{\"code\":\"ADMISSION_PAUSED\"}"));
		assertNoEffects();
	}

	@Test
	void myModelValidationPrecedesProductClaimAndPersistence() throws Exception {
		Owner owner = seedOwner(false);
		mockMvc.perform(post("/generations").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "model-before-claim")
				.contentType(MediaType.APPLICATION_JSON).content(body(owner.uploadId())))
			.andExpect(status().isNotFound())
			.andExpect(content().string("{\"code\":\"MY_MODEL_MISSING\"}"));
		assertNoEffects();
	}

	@Test
	void invalidUploadsDoNotConsumeQuotaAndAcceptedCreationConsumesOne() throws Exception {
		Owner owner = seedOwner(true);
		for (int attempt = 0; attempt < 6; attempt++) {
			mockMvc.perform(post("/generations").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
					.header("Idempotency-Key", "invalid-upload-" + attempt)
					.contentType(MediaType.APPLICATION_JSON).content(body(new UUID(0, 0))))
				.andExpect(status().isUnprocessableEntity())
				.andExpect(content().string("{\"code\":\"PRODUCT_UPLOAD_MISSING\"}"));
		}
		assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(count), 0) FROM generation_admission_counter", Long.class)).isZero();

		mockMvc.perform(post("/generations").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "accepted-upload")
				.contentType(MediaType.APPLICATION_JSON).content(body(owner.uploadId())))
			.andExpect(status().isAccepted());
		assertThat(jdbc.queryForList("SELECT count FROM generation_admission_counter", Long.class))
			.containsExactlyInAnyOrder(1L, 1L, 1L);
	}

	private Owner seedOwner(boolean model) throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		mockMvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());
		UserId userId = new UserId(jdbc.queryForObject(
			"SELECT user_id FROM identity_mapping WHERE subject = ?", UUID.class, InProcessJwtFixture.subject()));
		if (model) insertModel(userId);
		return new Owner(token, UUID.randomUUID());
	}

	private void insertModel(UserId userId) {
		UUID profile = UUID.randomUUID();
		UUID image = UUID.randomUUID();
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.update("INSERT INTO avatar_profile (id, user_id, primary_image_id, status, revision, created_at, updated_at) VALUES (?, ?, NULL, 'ACTIVE', 1, ?, ?)", profile, userId.value(), now, now);
		jdbc.update("INSERT INTO avatar_image (id, profile_id, storage_key, quality_status, created_at) VALUES (?, ?, ?, 'ACCEPTED', ?)", image, profile, "users/" + userId.value() + "/avatars/" + image, now);
		jdbc.update("UPDATE avatar_profile SET primary_image_id = ? WHERE id = ?", image, profile);
	}

	private void assertNoEffects() {
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_job", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_work_outbox", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_attempt", Integer.class)).isZero();
	}

	private static String body(UUID uploadId) { return "{\"productUploadId\":\"" + uploadId + "\"}"; }
	private static String bearer(String token) { return "Bearer " + token; }
	private record Owner(String token, UUID uploadId) {}

    @TestConfiguration
    static class Configuration {
        @Bean @Primary ExternalTokenVerifier verifier() { return InProcessJwtFixture.verifier(); }
		@Bean @Primary GenerationMediaStore generationMediaStore() {
			return new GenerationMediaStore() {
				@Override public void putBounded(StorageKey dest, InputStream source, long maxBytes, String mediaType) { }
				@Override public ClaimResult claim(com.aifashionvault.platform.objectstore.GenerationClaimCommand command) { throw new UnsupportedOperationException(); }
				@Override public ClaimResult claimProductInput(ProductInputClaimCommand command) {
					if (command.productUploadId().value().equals(new UUID(0, 0)))
						throw new com.aifashionvault.platform.objectstore.ObjectStoreExceptions.StagedObjectMissingException("missing staged object");
					return new ClaimResult.Copied();
				}
			};
		}
        }
}
