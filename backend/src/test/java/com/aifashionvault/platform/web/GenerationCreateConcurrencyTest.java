package com.aifashionvault.platform.web;

import java.io.InputStream;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.GenerationMediaStore;
import com.aifashionvault.platform.objectstore.ProductInputClaimCommand;
import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@AutoConfigureMockMvc
class GenerationCreateConcurrencyTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres").withStartupTimeout(Duration.ofMinutes(5));
	private static final ObjectMapper MAPPER = new ObjectMapper();

	static { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); }
	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbc;

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
		jdbc.update("TRUNCATE TABLE look_item, look, quality_result, generation_callback_inbox, generation_work_outbox, "
			+ "generation_quota_reservation, generation_notification_delivery, generation_notification_event, "
			+ "generation_provider_capacity_slot, generation_attempt, generation_render_step_item, generation_render_step, "
			+ "generation_job_product, generation_job, vault_item, studio_item, studio_session, avatar_image, "
			+ "avatar_profile, identity_mapping, users CASCADE");
	}

	@Test
	void concurrentSameKeySameUploadProducesOneGeneration() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		mockMvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());
		UserId userId = new UserId(jdbc.queryForObject(
			"SELECT user_id FROM identity_mapping WHERE subject = ?", UUID.class, InProcessJwtFixture.subject()));
		insertModel(userId);
		UUID uploadId = UUID.randomUUID();
		String body = "{\"productUploadId\":\"" + uploadId + "\"}";
		String key = "simultaneous-create";
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		AtomicReference<Result> first = new AtomicReference<>();
		AtomicReference<Result> second = new AtomicReference<>();

		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			pool.submit(() -> invoke(token, key, body, ready, start, first));
			pool.submit(() -> invoke(token, key, body, ready, start, second));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(first.get().failure()).isNull();
		assertThat(second.get().failure()).isNull();
		assertThat(first.get().status()).isEqualTo(202);
		assertThat(second.get().status()).isEqualTo(202);
		assertThat(first.get().id()).isEqualTo(second.get().id());
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_job", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_work_outbox", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT product_source_upload_id FROM generation_job", UUID.class)).isEqualTo(uploadId);
	}

	private void invoke(String token, String key, String body, CountDownLatch ready, CountDownLatch start,
		AtomicReference<Result> target) {
		ready.countDown();
		try {
			if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start latch timed out");
			MvcResult response = mockMvc.perform(post("/generations")
					.header(HttpHeaders.AUTHORIZATION, bearer(token)).header("Idempotency-Key", key)
					.contentType(MediaType.APPLICATION_JSON).content(body)).andReturn();
			String id = response.getResponse().getStatus() == 202
				? MAPPER.readTree(response.getResponse().getContentAsString()).get("id").stringValue() : null;
			target.set(new Result(response.getResponse().getStatus(), id, null));
		}
		catch (Throwable failure) { target.set(new Result(0, null, failure)); }
	}

	private void insertModel(UserId userId) {
		UUID profile = UUID.randomUUID();
		UUID image = UUID.randomUUID();
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.update("INSERT INTO avatar_profile (id, user_id, primary_image_id, status, revision, created_at, updated_at) VALUES (?, ?, NULL, 'ACTIVE', 1, ?, ?)", profile, userId.value(), now, now);
		jdbc.update("INSERT INTO avatar_image (id, profile_id, storage_key, quality_status, created_at) VALUES (?, ?, ?, 'ACCEPTED', ?)", image, profile, "users/" + userId.value() + "/avatars/" + image, now);
		jdbc.update("UPDATE avatar_profile SET primary_image_id = ? WHERE id = ?", image, profile);
	}

	private static String bearer(String token) { return "Bearer " + token; }
	private record Result(int status, String id, Throwable failure) {}

    @TestConfiguration
    static class Configuration {
        @Bean @Primary ExternalTokenVerifier verifier() { return InProcessJwtFixture.verifier(); }
        @Bean GenerationMediaStore stubGenerationMediaStore() {
            return new GenerationMediaStore() {
                @Override public void putBounded(StorageKey dest, InputStream source, long maxBytes, String mediaType) { }
                @Override public ClaimResult claim(com.aifashionvault.platform.objectstore.GenerationClaimCommand command) { throw new UnsupportedOperationException(); }
				@Override public void preflightProductInput(ProductInputClaimCommand command) { }
				@Override public ClaimResult claimProductInput(ProductInputClaimCommand command) { return new ClaimResult.Copied(); }
				@Override public void releaseProductInput(ProductInputClaimCommand command) { }
            };
        }
    }
}
