package com.aifashionvault.verticalslice;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import javax.sql.DataSource;

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

import com.aifashionvault.look.JdbcLookStore;
import com.aifashionvault.look.Look;
import com.aifashionvault.notification.NotificationEvent;
import com.aifashionvault.platform.environment.AppEnv;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;
import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.GenerationMediaStore;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;
import com.aifashionvault.platform.queue.SqsGenerationWorkQueue;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@AutoConfigureMockMvc
class FirstVerticalSliceIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres").withStartupTimeout(Duration.ofMinutes(5));
	private static final ObjectMapper JSON = new ObjectMapper();

	static { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); }

    @Autowired MockMvc http;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired JdbcLookStore looks;
    @Autowired ObjectStore objects;

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
    void initFreshState() {
        // Reuse the application bean so direct setup and controller reads share the same store and DataSource.
        jdbc.update("TRUNCATE TABLE look_item, look, quality_result, generation_callback_inbox, generation_work_outbox, "
            + "generation_quota_reservation, generation_notification_delivery, generation_notification_event, "
            + "generation_provider_capacity_slot, generation_attempt, generation_render_step_item, generation_render_step, "
            + "generation_job_product, generation_job, vault_item, studio_item, studio_session, avatar_image, "
            + "avatar_profile, identity_mapping, users CASCADE");
        reset(objects);
    }

	@Test
	void createReplayAndClaimAreOneDurableSingleItemIntentAndQueueBodyIsUuidOnly() throws Exception {
		Owner owner = owner("vertical-owner");
		UUID upload = UUID.randomUUID();
		JsonNode first = create(owner, "same-key", upload, 202);
		JsonNode replay = create(owner, "same-key", upload, 202);

		UUID generationId = UUID.fromString(first.get("id").stringValue());
		assertThat(replay.get("id").stringValue()).isEqualTo(generationId.toString());
		Map<String, Object> frozen = jdbc.queryForMap(
			"SELECT user_id, avatar_image_id, product_source_upload_id, product_media_id, render_step_ordinal, render_step_count FROM generation_job WHERE id = ?",
			generationId);
		assertThat(frozen.get("user_id")).isEqualTo(owner.userId());
		assertThat(frozen.get("product_source_upload_id")).isEqualTo(upload);
		assertThat(frozen.get("avatar_image_id")).isNotNull();
		assertThat(frozen.get("product_media_id")).isNotNull();
		assertThat(frozen.get("render_step_ordinal")).isEqualTo(1);
		assertThat(frozen.get("render_step_count")).isEqualTo(1);
		assertThat(count("generation_job")).isEqualTo(1);
		assertThat(count("generation_work_outbox")).isEqualTo(1);
		assertThat(count("generation_attempt")).isZero();

		create(owner, "different-intent", upload, 409);
		assertThat(count("generation_job")).isEqualTo(1);

		SqsClient sqs = mock(SqsClient.class);
		new SqsGenerationWorkQueue(sqs, queueProperties()).send(generationId);
		verify(sqs).sendMessage(org.mockito.ArgumentMatchers.argThat((SendMessageRequest request) ->
			request.messageBody().equals(generationId.toString())
				&& !request.messageBody().contains("http")
				&& !request.messageBody().contains("base64")
				&& !request.messageBody().contains("product")));
	}

	@Test
	void ownerIsolationAndPassOnlyPublicationProduceOneLookAndExactSafeNotification() throws Exception {
		Owner owner = owner("publication-owner");
		Owner stranger = owner("publication-stranger");
		UUID generationId = UUID.fromString(create(owner, "publish-key", UUID.randomUUID(), 202).get("id").stringValue());
		Look look = look(owner.userId(), generationId);

		assertThat(looks.insertIfJobActive(look)).map(Look::id).contains(look.id());
		assertThat(looks.insertIfJobActive(look)).isEmpty();
		assertThat(count("look")).isEqualTo(1);
		assertThat(count("generation_notification_event")).isEqualTo(1);

		Map<String, Object> event = jdbc.queryForMap(
			"SELECT type, look_id, generation_job_id FROM generation_notification_event WHERE generation_job_id = ?",
			generationId);
		NotificationEvent notification = NotificationEvent.lookReady(
			UUID.randomUUID(), new UserId(owner.userId()), new GenerationJobId(generationId),
			new LookId((UUID) event.get("look_id")), Instant.now(), Instant.now().plusSeconds(300));
		assertThat(event.get("type")).isEqualTo("LOOK_READY");
		assertThat(notification.pushPayload()).containsExactlyInAnyOrderEntriesOf(Map.of(
			"type", "LOOK_READY", "lookId", look.id().value().toString()));

		when(objects.presignGet(any(), any())).thenReturn(new PresignedAccess(
			new ObjectRef(com.aifashionvault.platform.objectstore.StorageBoundary.LOOK, look.storageKey().value()),
			"GET", URI.create("https://private.invalid/read?signature=short-lived"), Map.of(), Instant.now().plusSeconds(300)));
		http.perform(get("/looks/" + look.id().value()).header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
			.andExpect(status().isOk());
		http.perform(get("/looks/" + look.id().value()).header(HttpHeaders.AUTHORIZATION, bearer(stranger.token())))
			.andExpect(status().isNotFound())
			.andExpect(content().string("{\"code\":\"UNAUTHORIZED_OR_NOT_FOUND\"}"));
		verify(objects).presignGet(any(), any());
	}

	@Test
	void cancellationWinsBeforePublicationAndMintsNoCapabilityOrSuccessEvent() throws Exception {
		Owner owner = owner("cancel-owner");
		UUID generationId = UUID.fromString(create(owner, "cancel-key", UUID.randomUUID(), 202).get("id").stringValue());
		http.perform(post("/generations/" + generationId + "/cancel")
			.header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))).andExpect(status().isOk());

		assertThat(looks.insertIfJobActive(look(owner.userId(), generationId))).isEmpty();
		assertThat(count("look")).isZero();
		assertThat(count("generation_notification_event")).isZero();
		verify(objects, never()).presignGet(any(), any());
	}
	@Test
	void subjectDeletionAlsoPreventsLatePublicationAndNotification() throws Exception {
		Owner owner = owner("deleted-subject-owner");
		UUID generationId = UUID.fromString(create(owner, "delete-key", UUID.randomUUID(), 202).get("id").stringValue());
		jdbc.update("UPDATE generation_job SET status = 'FAILED', terminal_reason = 'SUBJECT_DELETED', subject_deleted_at = ?, completed_at = ?, updated_at = ? WHERE id = ?",
			Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), generationId);

		assertThat(looks.insertIfJobActive(look(owner.userId(), generationId))).isEmpty();
		assertThat(count("look")).isZero();
		assertThat(count("generation_notification_event")).isZero();
	}


	private JsonNode create(Owner owner, String key, UUID upload, int expectedStatus) throws Exception {
		MvcResult result = http.perform(post("/generations")
			.header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
			.header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"productUploadId\":\"" + upload + "\"}"))
			.andExpect(status().is(expectedStatus)).andReturn();
		return JSON.readTree(result.getResponse().getContentAsString());
	}

	private Owner owner(String subject) throws Exception {
		String token = InProcessJwtFixture.bearerTokenForSubject(subject);
		http.perform(get("/me").header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());
		UUID userId = jdbc.queryForObject("SELECT user_id FROM identity_mapping WHERE subject = ?", UUID.class, subject);
		UUID profile = UUID.randomUUID();
		UUID image = UUID.randomUUID();
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.update("INSERT INTO avatar_profile (id, user_id, primary_image_id, status, revision, created_at, updated_at) VALUES (?, ?, NULL, 'ACTIVE', 1, ?, ?)", profile, userId, now, now);
		jdbc.update("INSERT INTO avatar_image (id, profile_id, storage_key, quality_status, created_at) VALUES (?, ?, ?, 'ACCEPTED', ?)", image, profile, "users/" + userId + "/avatars/model.jpg", now);
		jdbc.update("UPDATE avatar_profile SET primary_image_id = ? WHERE id = ?", image, profile);
		return new Owner(token, userId);
	}

	private static Look look(UUID owner, UUID generationId) {
		return new Look(new LookId(UUID.nameUUIDFromBytes(generationId.toString().getBytes())), new UserId(owner), null,
			new GenerationJobId(generationId), null, new StorageKey("users/" + owner + "/looks/" + generationId + ".jpg"),
			null, "fake", "fake-model", QualityDecision.PASS, Instant.now(), List.of());
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
	}

	private static String bearer(String token) { return "Bearer " + token; }

	private static VaultEnvironmentProperties queueProperties() {
		return new VaultEnvironmentProperties(AppEnv.development, null, null,
			new VaultEnvironmentProperties.Sqs("https://sqs.invalid/generation"), null, null, null, null);
	}

	private record Owner(String token, UUID userId) {}

	@TestConfiguration
	static class Fakes {
		@Bean @Primary ExternalTokenVerifier verifier() { return InProcessJwtFixture.verifier(); }
		@Bean @Primary ObjectStore objects() { return mock(ObjectStore.class); }
		@Bean @Primary GenerationMediaStore generationMediaStore() {
			GenerationMediaStore mediaStore = mock(GenerationMediaStore.class);
			when(mediaStore.claimProductInput(any())).thenReturn(new ClaimResult.Copied());
			return mediaStore;
		}
		@Bean JdbcLookStore jdbcLookStore(DataSource dataSource) { return new JdbcLookStore(dataSource); }
	}
}
