package com.aifashionvault.platform.web;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.generation.GenerationJob;
import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.generation.RenderPlan;
import com.aifashionvault.generation.RenderStep;
import com.aifashionvault.generation.RetryPolicySnapshot;
import com.aifashionvault.look.JdbcLookStore;
import com.aifashionvault.platform.objectstore.ClaimCommand;
import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.MediaDeclaration;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.platform.objectstore.StoredObject;
import com.aifashionvault.platform.objectstore.UploadCapability;
import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;
import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@AutoConfigureMockMvc
class LooksHttpContractTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres").withStartupTimeout(Duration.ofMinutes(5));
	private static final ObjectMapper JSON = new ObjectMapper();
	private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
	private static final String NOT_FOUND = "{\"code\":\"UNAUTHORIZED_OR_NOT_FOUND\"}";

	static { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); }

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired GenerationStore generations;
	@Autowired FakeLooksObjectStore objects;

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		POSTGRES.start();
		registry.add("vault.db.url", POSTGRES::getJdbcUrl);
		registry.add("vault.db.username", POSTGRES::getUsername);
		registry.add("vault.db.password", POSTGRES::getPassword);
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@BeforeEach
	void resetState() {
		jdbc.update("TRUNCATE look_item, look, generation_job, identity_mapping, users CASCADE");
		reset(generations);
		objects.reset();
	}

	@Test
	void everyLooksEndpointRequiresAuthentication() throws Exception {
		String id = UUID.randomUUID().toString();
		mockMvc.perform(get("/looks")).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/looks/" + id)).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/looks/" + id + "/share-link")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/looks/" + id + "/regenerate").header("Idempotency-Key", "key"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/looks/" + id)).andExpect(status().isUnauthorized());
	}

	@Test
	void listDetailShareAndDeleteRunThroughHttpWithoutLeakingStorageIdentity() throws Exception {
		Owner owner = owner();
		UUID id = insertLook(owner);

		JsonNode listed = json(mockMvc.perform(get("/looks").header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
			.andExpect(status().isOk()).andReturn());
		assertThat(listed).hasSize(1);
		assertPublicLook(listed.get(0), id);

		JsonNode detail = json(mockMvc.perform(get("/looks/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
			.andExpect(status().isOk()).andReturn());
		assertPublicLook(detail, id);

		mockMvc.perform(post("/looks/" + id + "/share-link").header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
			.andExpect(status().isOk())
			.andExpect(content().string("{\"url\":\"https://example.invalid/read\",\"expiresAt\":\"2026-08-30T12:05:00Z\"}"));

		mockMvc.perform(delete("/looks/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
			.andExpect(status().isNoContent()).andExpect(content().string(""));
		assertThat(objects.deleted).isEqualTo(new ObjectRef(StorageDomain.LOOK, storageKey(owner, id)));
		mockMvc.perform(get("/looks/" + id).header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
			.andExpect(status().isNotFound()).andExpect(content().string(NOT_FOUND));
	}

	@Test
	void missingForeignAndMalformedIdentifiersAreOpaqueForDetailDeleteShareAndRegenerate() throws Exception {
		Owner owner = owner();
		UUID owned = insertLook(owner);
		Owner foreign = owner("foreign-looks-owner");
		for (String candidate : List.of(owned.toString(), UUID.randomUUID().toString(), "not-a-uuid")) {
			mockMvc.perform(get("/looks/" + candidate).header(HttpHeaders.AUTHORIZATION, bearer(foreign.token())))
				.andExpect(status().isNotFound()).andExpect(content().string(NOT_FOUND));
			mockMvc.perform(post("/looks/" + candidate + "/share-link").header(HttpHeaders.AUTHORIZATION, bearer(foreign.token())))
				.andExpect(status().isNotFound()).andExpect(content().string(NOT_FOUND));
			mockMvc.perform(delete("/looks/" + candidate).header(HttpHeaders.AUTHORIZATION, bearer(foreign.token())))
				.andExpect(status().isNotFound()).andExpect(content().string(NOT_FOUND));
			mockMvc.perform(post("/looks/" + candidate + "/regenerate")
					.header(HttpHeaders.AUTHORIZATION, bearer(foreign.token())).header("Idempotency-Key", "opaque-key"))
				.andExpect(status().isNotFound()).andExpect(content().string(NOT_FOUND));
		}
	}

	@Test
	void regenerateRequiresKeyAndMapsKeyConflictAndQuotaToExactProblems() throws Exception {
		Owner owner = owner();
		UUID id = insertLook(owner);
		stubRegeneratable(owner);

		mockMvc.perform(post("/looks/" + id + "/regenerate").header(HttpHeaders.AUTHORIZATION, bearer(owner.token())))
			.andExpect(status().isBadRequest()).andExpect(content().string("{\"code\":\"IDEMPOTENCY_KEY_REQUIRED\"}"));

		when(generations.createAccepted(any())).thenAnswer(invocation ->
			new GenerationStore.CreateAcceptedResult.FingerprintConflict(invocation.getArgument(0)));
		mockMvc.perform(post("/looks/" + id + "/regenerate").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "same-key"))
			.andExpect(status().isConflict()).andExpect(content().string("{\"code\":\"IDEMPOTENCY_KEY_REUSED\"}"));

		when(generations.createAccepted(any())).thenReturn(new GenerationStore.CreateAcceptedResult.QuotaRejected("ACTIVE_LIMIT"));
		mockMvc.perform(post("/looks/" + id + "/regenerate").header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "quota-key"))
			.andExpect(status().isTooManyRequests()).andExpect(content().string("{\"code\":\"ACTIVE_LIMIT\"}"));
	}

	@Test
	void regenerateReturnsExactAcceptedGenerationResourceAndLocation() throws Exception {
		Owner owner = owner();
		UUID id = insertLook(owner);
		stubRegeneratable(owner);
		when(generations.createAccepted(any())).thenAnswer(invocation ->
			new GenerationStore.CreateAcceptedResult.Created(invocation.getArgument(0)));

		MvcResult result = mockMvc.perform(post("/looks/" + id + "/regenerate")
				.header(HttpHeaders.AUTHORIZATION, bearer(owner.token())).header("Idempotency-Key", "regenerate-key"))
			.andExpect(status().isAccepted()).andExpect(header().exists(HttpHeaders.LOCATION)).andReturn();
		JsonNode body = json(result);
		assertThat(body.propertyNames()).containsExactly("id", "status", "phase", "statusVersion", "createdAt", "updatedAt");
		assertThat(body.get("status").stringValue()).isEqualTo("QUEUED");
		assertThat(body.get("phase").stringValue()).isEqualTo("QUEUED");
		assertThat(result.getResponse().getHeader(HttpHeaders.LOCATION)).isEqualTo("/generations/" + body.get("id").stringValue());
	}

	private void stubRegeneratable(Owner owner) {
		GenerationJob source = new GenerationJob(new GenerationJobId(owner.jobId()), owner.userId(), null,
			GenerationJob.COMPLETED, "STANDARD", null, NOW.minusSeconds(60), NOW,
			new AvatarImageId(UUID.randomUUID()), List.of(), null, null, "source", 1, 3, NOW, null,
			1, 1, 1, NOW.plusSeconds(1200), null, RetryPolicySnapshot.forStepCountV2(1), Optional.empty());
		when(generations.findJobForUser(new GenerationJobId(owner.jobId()), owner.userId())).thenReturn(Optional.of(source));
		when(generations.findRenderPlan(new GenerationJobId(owner.jobId()))).thenReturn(Optional.of(
			new RenderPlan(1, List.of(new RenderStep(UUID.randomUUID(), 1, null, List.of())))));
	}

	private Owner owner() throws Exception { return owner(InProcessJwtFixture.subject()); }

	private Owner owner(String subject) throws Exception {
		String token = subject.equals(InProcessJwtFixture.subject())
			? InProcessJwtFixture.bearerToken() : InProcessJwtFixture.bearerTokenForSubject(subject);
		mockMvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, bearer(token))).andExpect(status().isOk());
		UUID user = jdbc.queryForObject("SELECT user_id FROM identity_mapping WHERE subject = ?", UUID.class, subject);
		UUID job = UUID.randomUUID();
		jdbc.update("INSERT INTO generation_job (id, user_id, status, requested_mode, client_request_id, created_at, updated_at, completed_at, status_version) VALUES (?, ?, 'COMPLETED', 'STANDARD', ?, ?, ?, ?, 0)",
			job, user, "source-" + job, Timestamp.from(NOW.minusSeconds(60)), Timestamp.from(NOW), Timestamp.from(NOW));
		return new Owner(token, new UserId(user), job);
	}

	private UUID insertLook(Owner owner) {
		UUID id = UUID.randomUUID();
		jdbc.update("INSERT INTO look (id, user_id, generation_job_id, storage_key, render_plan, provider, provider_model, quality_status, created_at) VALUES (?, ?, ?, ?, '{\"version\":1}', 'safe-provider', 'safe-model', 'PASS', ?)",
			id, owner.userId().value(), owner.jobId(), storageKey(owner, id), Timestamp.from(NOW));
		return id;
	}

	private static String storageKey(Owner owner, UUID id) {
		return "users/" + owner.userId().value() + "/looks/" + id + ".jpg";
	}

	private static JsonNode json(MvcResult result) throws Exception {
		return JSON.readTree(result.getResponse().getContentAsString());
	}

	private static void assertPublicLook(JsonNode node, UUID id) {
		assertThat(node.propertyNames()).containsExactly("id", "avatarId", "generationId", "studioSessionId", "renderPlan",
			"provider", "providerModel", "qualityStatus", "createdAt", "items", "image");
		assertThat(node.get("id").stringValue()).isEqualTo(id.toString());
		assertThat(node.toString()).doesNotContain("storageKey", "userId", "bucket", "objectKey");
	}

	private static String bearer(String token) { return "Bearer " + token; }
	private record Owner(String token, UserId userId, UUID jobId) {}

	@TestConfiguration
	static class TestBeans {
		@Bean @Primary ExternalTokenVerifier verifier() { return InProcessJwtFixture.verifier(); }
		@Bean @Primary GenerationStore generations() { return mock(GenerationStore.class); }
		@Bean @Primary FakeLooksObjectStore objects() { return new FakeLooksObjectStore(); }
	}

	static final class FakeLooksObjectStore implements ObjectStore {
		private ObjectRef deleted;
		void reset() { deleted = null; }
		@Override public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration) { throw new UnsupportedOperationException(); }
		@Override public ObjectMetadata head(ObjectRef object) { throw new UnsupportedOperationException(); }
		@Override public byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes) { throw new UnsupportedOperationException(); }
		@Override public void delete(ObjectRef object) { deleted = object; }
		@Override public ClaimResult claim(ClaimCommand command) { throw new UnsupportedOperationException(); }
		@Override public void purgeUserObjects(UserId userId) { }
		@Override public void cleanupTemp() { }
		@Override public PresignedAccess presignGet(ObjectRef object, Duration ttl) {
			return new PresignedAccess(object, "GET", URI.create("https://example.invalid/read"), Map.of(), NOW.plus(ttl));
		}
		@Override public List<StoredObject> list(StorageDomain boundary, String prefix) { return List.of(); }
	}
}
