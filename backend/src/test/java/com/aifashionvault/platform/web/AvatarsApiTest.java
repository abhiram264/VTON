package com.aifashionvault.platform.web;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.imageio.ImageIO;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;

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

import com.aifashionvault.avatar.AvatarPreflight;
import com.aifashionvault.avatar.PersonCountClass;
import com.aifashionvault.avatar.SemanticInspection;
import com.aifashionvault.avatar.SemanticInspectionUnavailableException;
import com.aifashionvault.avatar.SemanticInspector;
import com.aifashionvault.avatar.AvatarWorkflow;
import com.aifashionvault.avatar.JdbcAvatarStore;
import com.aifashionvault.platform.objectstore.ClaimCommand;
import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.ImageInspector;
import com.aifashionvault.platform.objectstore.ImageInspectorAvatarBytesInspector;
import com.aifashionvault.platform.objectstore.ObjectStoreAvatarMediaAccess;
import com.aifashionvault.platform.objectstore.MalwareScanResult;
import com.aifashionvault.platform.objectstore.MalwareScanner;
import com.aifashionvault.platform.objectstore.MalwareVerdict;
import com.aifashionvault.platform.objectstore.MediaDeclaration;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.platform.objectstore.StorageKeys;
import com.aifashionvault.platform.objectstore.UploadCapability;
import com.aifashionvault.platform.objectstore.UploadPurpose;
import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@AutoConfigureMockMvc
class AvatarsApiTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final String OTHER_SUBJECT = "other-external-subject";
	private static final String CREATE_UPLOAD_BODY = "{\"contentType\":\"image/jpeg\",\"contentLength\":1024}";
	private static final String DISPLAY_URL = "https://example.invalid/display?X-Amz-Signature=redact-me-token";
	private static final String NOT_FOUND_BODY = "{\"code\":\"AVATAR_NOT_FOUND\"}";
	private static final String STALE_BODY = "{\"code\":\"AVATAR_STALE_REVISION\"}";
	private static final String NOT_CLAIMABLE_BODY = "{\"code\":\"AVATAR_UPLOAD_NOT_CLAIMABLE\"}";
	private static final ObjectMapper MAPPER = new ObjectMapper();

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private FakeAvatarObjectStore fakeObjectStore;

	@Autowired
	private FakeSemanticInspector fakeSemanticInspector;

	@DynamicPropertySource
	static void registerDatasource(DynamicPropertyRegistry registry) {
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
		jdbc.update("TRUNCATE TABLE avatar_image, avatar_profile, identity_mapping, users CASCADE");
		this.fakeObjectStore.reset();
		this.fakeSemanticInspector.reset();
	}

	@Test
	void unauthenticatedRequestsAre401() throws Exception {
		UUID id = UUID.randomUUID();
		mockMvc.perform(get("/avatars")).andExpect(status().isUnauthorized());
		mockMvc.perform(post("/avatars").contentType(MediaType.APPLICATION_JSON).content(uploadBody(id)))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/avatars/" + id)).andExpect(status().isUnauthorized());
		mockMvc.perform(patch("/avatars/" + id).contentType(MediaType.APPLICATION_JSON).content(uploadBody(id)))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/avatars/" + id)).andExpect(status().isUnauthorized());
	}

	@Test
	void authenticatedGetEmptyIsExactEmptyArray() throws Exception {
		MvcResult result = mockMvc.perform(get("/avatars").header(HttpHeaders.AUTHORIZATION, bearer(token())))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andReturn();

		assertThat(result.getResponse().getContentAsString()).isEqualTo("[]");
	}

	@Test
	void createReturns201ResourceAndQuotedEtag() throws Exception {
		String token = token();
		String uploadId = completeAvatarUpload(token);

		MvcResult result = mockMvc.perform(post("/avatars")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(uploadBody(uploadId)))
			.andExpect(status().isCreated())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andReturn();

		JsonNode node = assertResource(result.getResponse().getContentAsString());
		assertThat(node.get("revision").intValue()).isEqualTo(1);
		assertThat(node.get("displayUrl").stringValue()).isEqualTo(DISPLAY_URL);
	}

	@Test
	void listReturnsCreatedAvatar() throws Exception {
		String token = token();
		JsonNode created = createAvatar(token);

		MvcResult result = mockMvc.perform(get("/avatars").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andReturn();

		JsonNode list = MAPPER.readTree(result.getResponse().getContentAsString());
		assertThat(list.isArray()).isTrue();
		assertThat(list.size()).isEqualTo(1);
		assertResource(list.get(0).toString());
		assertThat(list.get(0).get("id").stringValue()).isEqualTo(created.get("id").stringValue());
		assertThat(list.get(0).get("imageId").stringValue()).isEqualTo(created.get("imageId").stringValue());
	}

	@Test
	void getByIdReturnsCreatedAvatar() throws Exception {
		String token = token();
		JsonNode created = createAvatar(token);

		MvcResult result = mockMvc.perform(get("/avatars/" + created.get("id").stringValue())
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
			.andReturn();

		JsonNode node = assertResource(result.getResponse().getContentAsString());
		assertThat(node.get("id").stringValue()).isEqualTo(created.get("id").stringValue());
		assertThat(node.get("imageId").stringValue()).isEqualTo(created.get("imageId").stringValue());
	}

	@Test
	void foreignIdIs404SameBodyAsMissing() throws Exception {
		String ownerToken = token();
		JsonNode created = createAvatar(ownerToken);
		String foreignToken = InProcessJwtFixture.bearerTokenForSubject(OTHER_SUBJECT);
		String missingId = UUID.randomUUID().toString();

		MvcResult foreign = mockMvc.perform(get("/avatars/" + created.get("id").stringValue())
				.header(HttpHeaders.AUTHORIZATION, bearer(foreignToken)))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andReturn();
		MvcResult missing = mockMvc.perform(get("/avatars/" + missingId)
				.header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
			.andExpect(status().isNotFound())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andReturn();

		assertThat(foreign.getResponse().getContentAsString()).isEqualTo(NOT_FOUND_BODY);
		assertThat(missing.getResponse().getContentAsString()).isEqualTo(NOT_FOUND_BODY);
		assertThat(foreign.getResponse().getContentAsString()).isEqualTo(missing.getResponse().getContentAsString());
	}

	@Test
	void invalidPathUuidIs404NotFound() throws Exception {
		mockMvc.perform(get("/avatars/not-a-uuid").header(HttpHeaders.AUTHORIZATION, bearer(token())))
			.andExpect(status().isNotFound())
			.andExpect(content().string(NOT_FOUND_BODY));
	}

	@Test
	void replaceReturns200ResourceAndIncrementedQuotedEtag() throws Exception {
		String token = token();
		JsonNode created = createAvatar(token);
		String firstImageId = created.get("imageId").stringValue();
		String nextUploadId = completeAvatarUpload(token);

		MvcResult result = mockMvc.perform(patch("/avatars/" + created.get("id").stringValue())
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.header(HttpHeaders.IF_MATCH, "\"1\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content(uploadBody(nextUploadId)))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
			.andReturn();

		JsonNode replaced = assertResource(result.getResponse().getContentAsString());
		assertThat(replaced.get("id").stringValue()).isEqualTo(created.get("id").stringValue());
		assertThat(replaced.get("imageId").stringValue()).isNotEqualTo(firstImageId);
		assertThat(replaced.get("revision").intValue()).isEqualTo(2);
	}


	@Test
	void staleIfMatchIs412() throws Exception {
		String token = token();
		JsonNode created = createAvatar(token);
		String nextUploadId = completeAvatarUpload(token);

		mockMvc.perform(patch("/avatars/" + created.get("id").stringValue())
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.header(HttpHeaders.IF_MATCH, "\"99\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content(uploadBody(nextUploadId)))
			.andExpect(status().isPreconditionFailed())
			.andExpect(content().string(STALE_BODY));
	}

	@Test
	void missingIfMatchOnActivePatchIs412() throws Exception {
		String token = token();
		JsonNode created = createAvatar(token);
		String nextUploadId = completeAvatarUpload(token);

		mockMvc.perform(patch("/avatars/" + created.get("id").stringValue())
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(uploadBody(nextUploadId)))
			.andExpect(status().isPreconditionFailed())
			.andExpect(content().string(STALE_BODY));
	}

	@Test
	void replaySameUploadIdReturnsSameImageId() throws Exception {
		String token = token();
		String uploadId = completeAvatarUpload(token);
		JsonNode first = createAvatar(token, uploadId);

		MvcResult replayed = mockMvc.perform(post("/avatars")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(uploadBody(uploadId)))
			.andReturn();

		assertThat(replayed.getResponse().getStatus()).isIn(200, 201);
		JsonNode second = assertResource(replayed.getResponse().getContentAsString());
		assertThat(second.get("id").stringValue()).isEqualTo(first.get("id").stringValue());
		assertThat(second.get("imageId").stringValue()).isEqualTo(first.get("imageId").stringValue());
	}

	@Test
	void semanticRejectionsReturnExact422CodeOnlyJson() throws Exception {
		assertSemanticProblem(
			new SemanticInspection(PersonCountClass.ZERO, false, false, "private-zero"),
			"AVATAR_PERSON_NOT_SINGLE");
		assertSemanticProblem(
			new SemanticInspection(PersonCountClass.ONE, false, true, "private-face"),
			"AVATAR_FACE_NOT_VISIBLE");
		assertSemanticProblem(
			new SemanticInspection(PersonCountClass.ONE, true, false, "private-body"),
			"AVATAR_BODY_NOT_VISIBLE");
	}

	@Test
	void unavailableSemanticInspectionReturnsExact503CodeOnlyJson() throws Exception {
		String token = token();
		String uploadId = completeAvatarUpload(token);
		fakeSemanticInspector.unavailable = true;

		mockMvc.perform(post("/avatars")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(uploadBody(uploadId)))
			.andExpect(status().isServiceUnavailable())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(content().string("{\"code\":\"AVATAR_PREFLIGHT_UNAVAILABLE\"}"));
	}

	@Test
	void deleteWithoutIfMatchIsUnconditionalThenRepeatIsIdempotent() throws Exception {
		String token = token();
		JsonNode created = createAvatar(token);
		String id = created.get("id").stringValue();

		MvcResult first = mockMvc.perform(delete("/avatars/" + id)
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNoContent())
			.andReturn();
		assertThat(first.getResponse().getContentAsString()).isEmpty();
		assertThat(jdbc.queryForObject(
			"SELECT status FROM avatar_profile WHERE id = ?",
			String.class,
			UUID.fromString(id))).isEqualTo("DELETING");
		assertThat(jdbc.queryForObject(
			"SELECT revision FROM avatar_profile WHERE id = ?",
			Integer.class,
			UUID.fromString(id))).isEqualTo(2);

		mockMvc.perform(get("/avatars").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk())
			.andExpect(content().string("[]"));

		MvcResult second = mockMvc.perform(delete("/avatars/" + id)
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.header(HttpHeaders.IF_MATCH, "\"99\""))
			.andExpect(status().isNoContent())
			.andReturn();
		assertThat(second.getResponse().getContentAsString()).isEmpty();
	}

	@Test
	void deleteWithMatchingIfMatchSucceedsAndStaleIfMatchIs412() throws Exception {
		String token = token();
		JsonNode matching = createAvatar(token);

		mockMvc.perform(delete("/avatars/" + matching.get("id").stringValue())
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.header(HttpHeaders.IF_MATCH, "\"1\""))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		String otherToken = InProcessJwtFixture.bearerTokenForSubject(OTHER_SUBJECT);
		JsonNode stale = createAvatar(otherToken);
		mockMvc.perform(delete("/avatars/" + stale.get("id").stringValue())
				.header(HttpHeaders.AUTHORIZATION, bearer(otherToken))
				.header(HttpHeaders.IF_MATCH, "\"99\""))
			.andExpect(status().isPreconditionFailed())
			.andExpect(content().string(STALE_BODY));
	}

	@Test
	void deleteOfForeignAvatarIsNonEnumerating404WithOrWithoutIfMatch() throws Exception {
		String ownerToken = token();
		JsonNode created = createAvatar(ownerToken);
		String strangerToken = InProcessJwtFixture.bearerTokenForSubject(OTHER_SUBJECT);

		mockMvc.perform(delete("/avatars/" + created.get("id").stringValue())
				.header(HttpHeaders.AUTHORIZATION, bearer(strangerToken)))
			.andExpect(status().isNotFound())
			.andExpect(content().string(NOT_FOUND_BODY));
		mockMvc.perform(delete("/avatars/" + created.get("id").stringValue())
				.header(HttpHeaders.AUTHORIZATION, bearer(strangerToken))
				.header(HttpHeaders.IF_MATCH, "\"1\""))
			.andExpect(status().isNotFound())
			.andExpect(content().string(NOT_FOUND_BODY));
	}

	@Test
	void deleteMarksReferencingGenerationJobsAndLeavesOtherUsersUntouched() throws Exception {
		String token = token();
		JsonNode created = createAvatar(token);
		UUID profileId = UUID.fromString(created.get("id").stringValue());
		UUID imageId = UUID.fromString(created.get("imageId").stringValue());
		UUID userId = jdbc.queryForObject(
			"SELECT user_id FROM avatar_profile WHERE id = ?",
			UUID.class,
			profileId);

		String otherToken = InProcessJwtFixture.bearerTokenForSubject(OTHER_SUBJECT);
		JsonNode other = createAvatar(otherToken);
		UUID otherImageId = UUID.fromString(other.get("imageId").stringValue());
		UUID otherUserId = jdbc.queryForObject(
			"SELECT user_id FROM avatar_profile WHERE id = ?",
			UUID.class,
			UUID.fromString(other.get("id").stringValue()));

		UUID matchingJob = insertGenerationJob(userId, imageId);
		UUID otherJob = insertGenerationJob(otherUserId, otherImageId);

		mockMvc.perform(delete("/avatars/" + profileId)
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNoContent());

		assertThat(jdbc.queryForObject(
			"SELECT subject_deleted_at FROM generation_job WHERE id = ?",
			Timestamp.class,
			matchingJob)).isNotNull();
		assertThat(jdbc.queryForObject(
			"SELECT subject_deleted_at FROM generation_job WHERE id = ?",
			Timestamp.class,
			otherJob)).isNull();
	}

	@Test
	void missingUploadIdIs400NotClaimable() throws Exception {
		String token = token();
		mockMvc.perform(post("/avatars")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().string(NOT_CLAIMABLE_BODY));
		mockMvc.perform(post("/avatars")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"uploadId\":\"not-a-uuid\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(content().string(NOT_CLAIMABLE_BODY));
	}

	@Test
	void setPrimaryIsNotMapped() throws Exception {
		String token = token();
		JsonNode created = createAvatar(token);

		MvcResult result = mockMvc.perform(post("/avatars/" + created.get("id").stringValue() + "/set-primary")
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andReturn();

		assertThat(result.getResponse().getStatus()).isIn(404, 405);
		for (Method method : AvatarsController.class.getDeclaredMethods()) {
			assertThat(method.getName().toLowerCase()).doesNotContain("primary");
		}
	}

	@Test
	void controllerMethodsDoNotAcceptMultipartFile() {
		for (Method method : AvatarsController.class.getDeclaredMethods()) {
			for (Parameter parameter : method.getParameters()) {
				assertThat(parameter.getType().getName()).doesNotContain("MultipartFile");
			}
		}
	}

	private void assertSemanticProblem(SemanticInspection inspection, String code) throws Exception {
		String token = token();
		String uploadId = completeAvatarUpload(token);
		fakeSemanticInspector.inspection = inspection;

		mockMvc.perform(post("/avatars")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(uploadBody(uploadId)))
			.andExpect(status().isUnprocessableEntity())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(content().string("{\"code\":\"" + code + "\"}"));
	}

	private JsonNode createAvatar(String token) throws Exception {
		return createAvatar(token, completeAvatarUpload(token));
	}

	private JsonNode createAvatar(String token, String uploadId) throws Exception {
		MvcResult result = mockMvc.perform(post("/avatars")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(uploadBody(uploadId)))
			.andExpect(status().isCreated())
			.andReturn();
		return assertResource(result.getResponse().getContentAsString());
	}

	private String completeAvatarUpload(String token) throws Exception {
		MvcResult created = mockMvc.perform(post("/uploads")
				.queryParam("purpose", "avatar")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(CREATE_UPLOAD_BODY))
			.andExpect(status().isOk())
			.andReturn();
		String id = MAPPER.readTree(created.getResponse().getContentAsString()).get("id").stringValue();
		mockMvc.perform(post("/uploads/" + id + "/complete")
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk());
		return id;
	}

	private UUID insertGenerationJob(UUID userId, UUID avatarImageId) {
		UUID jobId = UUID.randomUUID();
		jdbc.update(
			"INSERT INTO generation_job "
				+ "(id, user_id, studio_session_id, status, requested_mode, client_request_id, "
				+ "created_at, completed_at, avatar_image_id, cancelled_at, subject_deleted_at) "
				+ "VALUES (?, ?, NULL, 'QUEUED', NULL, ?, ?, NULL, ?, NULL, NULL)",
			jobId,
			userId,
			"req-" + jobId,
			Timestamp.from(Instant.now()),
			avatarImageId);
		return jobId;
	}

	private static JsonNode assertResource(String body) {
		JsonNode node = MAPPER.readTree(body);
		assertThat(node.propertyNames()).containsExactly(
			"id",
			"imageId",
			"revision",
			"width",
			"height",
			"mediaType",
			"displayUrl",
			"displayExpiresAt",
			"warnings");
		assertThat(node.get("id").stringValue()).isNotBlank();
		assertThat(node.get("imageId").stringValue()).isNotBlank();
		assertThat(node.get("revision").intValue()).isGreaterThanOrEqualTo(1);
		assertThat(node.get("width").intValue()).isPositive();
		assertThat(node.get("height").intValue()).isPositive();
		assertThat(node.get("mediaType").stringValue()).isIn("image/jpeg", "image/png");
		assertThat(node.get("displayUrl").stringValue()).isEqualTo(DISPLAY_URL);
		assertThat(node.get("displayExpiresAt").stringValue()).isNotBlank();
		assertThat(node.get("warnings").isArray()).isTrue();
		assertThat(body).doesNotContain("storageKey", "objectKey", "bucket");
		return node;
	}

	private static String uploadBody(Object uploadId) {
		return "{\"uploadId\":\"" + uploadId + "\"}";
	}

	private static String token() {
		return InProcessJwtFixture.bearerToken();
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	@TestConfiguration
	static class AvatarsTestConfiguration {

		@Bean
		@Primary
		ExternalTokenVerifier inProcessExternalTokenVerifier() {
			return InProcessJwtFixture.verifier();
		}

		@Bean
		@Primary
		FakeAvatarObjectStore fakeAvatarObjectStore() {
			return new FakeAvatarObjectStore();
		}

		@Bean
		@Primary
		MalwareScanner cleanMalwareScanner() {
			return (bytes, contentType) -> new MalwareScanResult(MalwareVerdict.CLEAN, "clean test fixture");
		}

		@Bean
		@Primary
		FakeSemanticInspector fakeSemanticInspector() {
			return new FakeSemanticInspector();
		}

		@Bean
		JdbcAvatarStore jdbcAvatarStore(DataSource dataSource) {
			return new JdbcAvatarStore(dataSource);
		}

		@Bean
		AvatarPreflight avatarPreflight(ImageInspector imageInspector, SemanticInspector semanticInspector) {
			return new AvatarPreflight(
				new ImageInspectorAvatarBytesInspector(imageInspector),
				semanticInspector);
		}

		@Bean
		AvatarWorkflow avatarWorkflow(
			JdbcAvatarStore store,
			FakeAvatarObjectStore objectStore,
			AvatarPreflight preflight
		) {
			return new AvatarWorkflow(store, new ObjectStoreAvatarMediaAccess(objectStore), preflight);
		}
	}

	static final class FakeSemanticInspector implements SemanticInspector {

		private SemanticInspection inspection;
		private boolean unavailable;

		FakeSemanticInspector() {
			reset();
		}

		void reset() {
			this.inspection =
				new SemanticInspection(PersonCountClass.ONE, true, true, "deterministic-test-evaluator");
			this.unavailable = false;
		}

		@Override
		public SemanticInspection inspect(byte[] imageBytes) throws SemanticInspectionUnavailableException {
			if (unavailable) {
				throw new SemanticInspectionUnavailableException();
			}
			return inspection;
		}
	}

	static final class FakeAvatarObjectStore implements ObjectStore {

		private static final byte[] TEXTURED_JPEG = texturedJpeg();

		private final ConcurrentHashMap<ObjectRef, Stored> objects = new ConcurrentHashMap<>();

		void reset() {
			this.objects.clear();
		}

		@Override
		public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration) {
			return presignStagingPut(userId, uploadId, declaration, UploadPurpose.GENERIC);
		}

		@Override
		public UploadCapability presignStagingPut(
			UserId userId,
			UploadId uploadId,
			MediaDeclaration declaration,
			UploadPurpose purpose
		) {
			ObjectRef ref = new ObjectRef(StorageDomain.TEMP, StorageKeys.stagingKey(userId, uploadId, purpose).value());
			this.objects.put(ref, new Stored(
				new ObjectMetadata(TEXTURED_JPEG.length, "image/jpeg", "etag-1"),
				TEXTURED_JPEG));
			return new UploadCapability(
				uploadId,
				"PUT",
				URI.create("https://example.invalid/put"),
				Map.of(
					"Content-Type", declaration.contentType(),
					"Content-Length", Long.toString(declaration.contentLength()),
					"If-None-Match", "*"),
				Instant.now().plus(Duration.ofMinutes(10)));
		}

		@Override
		public ObjectMetadata head(ObjectRef object) {
			return stored(object).metadata();
		}

		@Override
		public byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes) {
			byte[] payload = stored(object).payload();
			if (payload.length <= maxBytes) {
				return payload;
			}
			byte[] bounded = new byte[maxBytes];
			System.arraycopy(payload, 0, bounded, 0, maxBytes);
			return bounded;
		}

		@Override
		public void delete(ObjectRef object) {
			if (this.objects.remove(object) == null) {
				throw new ObjectStoreExceptions.StagedObjectMissingException("missing staged object");
			}
		}

		@Override
		public ClaimResult claim(ClaimCommand command) {
			ObjectRef source = new ObjectRef(
				StorageDomain.TEMP,
				StorageKeys.stagingKey(command.userId(), command.uploadId(), UploadPurpose.AVATAR).value());
			Stored stored = this.objects.get(source);
			if (stored == null) {
				throw new ObjectStoreExceptions.StagedObjectMissingException("missing staged object");
			}
			this.objects.put(new ObjectRef(command.destinationBoundary(), command.destination().value()), stored);
			return new ClaimResult.Copied();
		}

		@Override
		public void purgeUserObjects(UserId userId) {
		}

		@Override
		public void cleanupTemp() {
		}

		@Override
		public PresignedAccess presignGet(ObjectRef object, Duration ttl) {
			stored(object);
			return new PresignedAccess(
				object,
				"GET",
				URI.create(DISPLAY_URL),
				Map.of(),
				Instant.now().plus(ttl));
		}

		private Stored stored(ObjectRef object) {
			Stored stored = this.objects.get(object);
			if (stored == null) {
				throw new ObjectStoreExceptions.StagedObjectMissingException("missing staged object");
			}
			return stored;
		}

		private static byte[] texturedJpeg() {
			try {
				BufferedImage image = new BufferedImage(512, 512, BufferedImage.TYPE_INT_RGB);
				for (int y = 0; y < 512; y++) {
					for (int x = 0; x < 512; x++) {
						int light = ((x / 32) + (y / 32)) % 2 == 0 ? 220 : 40;
						image.setRGB(x, y, (light << 16) | (light << 8) | light);
					}
				}
				ByteArrayOutputStream bytes = new ByteArrayOutputStream();
				Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("JPEG");
				if (!writers.hasNext()) {
					throw new IllegalStateException("no ImageWriter for JPEG");
				}
				ImageWriter writer = writers.next();
				try (ImageOutputStream output = ImageIO.createImageOutputStream(bytes)) {
					writer.setOutput(output);
					writer.write(image);
				}
				finally {
					writer.dispose();
				}
				return bytes.toByteArray();
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
		}

		private record Stored(ObjectMetadata metadata, byte[] payload) {
		}
	}
}
