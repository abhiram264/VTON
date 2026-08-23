package com.aifashionvault.platform.web;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.URI;
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

import com.aifashionvault.platform.objectstore.ClaimCommand;
import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.ImageInspector;
import com.aifashionvault.platform.objectstore.MediaDeclaration;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.platform.objectstore.StorageKeys;
import com.aifashionvault.platform.objectstore.UploadCapability;
import com.aifashionvault.platform.objectstore.UploadWorkflow;
import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@AutoConfigureMockMvc
class UploadsApiTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final String OTHER_SUBJECT = "other-external-subject";
	private static final String CREATE_BODY = "{\"contentType\":\"image/jpeg\",\"contentLength\":1024}";
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
	private FakeUploadStore fakeUploadStore;

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
	void clearIdentityTables() {
		jdbc.update("DELETE FROM identity_mapping");
		jdbc.update("DELETE FROM users");
		this.fakeUploadStore.reset();
	}

	@Test
	void unauthenticatedPostIs401() throws Exception {
		mockMvc.perform(post("/uploads").contentType(MediaType.APPLICATION_JSON).content(CREATE_BODY))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void unauthenticatedCompleteIs401() throws Exception {
		mockMvc.perform(post("/uploads/" + UUID.randomUUID() + "/complete"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void unauthenticatedDeleteIs401() throws Exception {
		mockMvc.perform(delete("/uploads/" + UUID.randomUUID()))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void authenticatedCreateReturnsTicketJson() throws Exception {
		MvcResult result = mockMvc.perform(post("/uploads")
				.header(HttpHeaders.AUTHORIZATION, bearer(InProcessJwtFixture.bearerToken()))
				.contentType(MediaType.APPLICATION_JSON)
				.content(CREATE_BODY))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andReturn();

		String body = result.getResponse().getContentAsString();
		JsonNode node = MAPPER.readTree(body);
		assertThat(node.get("id").stringValue()).isNotBlank();
		assertThat(node.get("method").stringValue()).isEqualTo("PUT");
		assertThat(node.get("url").stringValue()).isEqualTo("https://example.invalid/put");
		assertThat(node.get("headers").propertyNames())
			.contains("Content-Type", "Content-Length", "If-None-Match");
		assertThat(node.get("expiresAt").isNull()).isFalse();
		assertThat(body).contains("\"url\"");
		assertThat(body).doesNotContain("bucket", "storageKey", "objectKey");
	}

	@Test
	void webpIs415() throws Exception {
		mockMvc.perform(post("/uploads")
				.header(HttpHeaders.AUTHORIZATION, bearer(InProcessJwtFixture.bearerToken()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"contentType\":\"image/webp\",\"contentLength\":1024}"))
			.andExpect(status().isUnsupportedMediaType());
	}

	@Test
	void zeroLengthIs400() throws Exception {
		mockMvc.perform(post("/uploads")
				.header(HttpHeaders.AUTHORIZATION, bearer(InProcessJwtFixture.bearerToken()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"contentType\":\"image/jpeg\",\"contentLength\":0}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void malformedJsonIs400() throws Exception {
		mockMvc.perform(post("/uploads")
				.header(HttpHeaders.AUTHORIZATION, bearer(InProcessJwtFixture.bearerToken()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void completeMissingIs404() throws Exception {
		mockMvc.perform(post("/uploads/" + UUID.randomUUID() + "/complete")
				.header(HttpHeaders.AUTHORIZATION, bearer(InProcessJwtFixture.bearerToken())))
			.andExpect(status().isNotFound());
	}

	@Test
	void completeReplayReturnsReadyTwice() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		String id = createUpload(token);

		String first = complete(token, id);
		String second = complete(token, id);

		assertReadyWithoutStorageKeys(first, id);
		assertReadyWithoutStorageKeys(second, id);
	}

	@Test
	void abortPresentIs204() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		String id = createUpload(token);

		MvcResult result = mockMvc.perform(delete("/uploads/" + id)
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNoContent())
			.andReturn();

		assertThat(result.getResponse().getContentAsString()).isEmpty();
	}

	@Test
	void abortAbsentIs204() throws Exception {
		MvcResult result = mockMvc.perform(delete("/uploads/" + UUID.randomUUID())
				.header(HttpHeaders.AUTHORIZATION, bearer(InProcessJwtFixture.bearerToken())))
			.andExpect(status().isNoContent())
			.andReturn();

		assertThat(result.getResponse().getContentAsString()).isEmpty();
	}

	@Test
	void invalidUuidIs400() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		mockMvc.perform(post("/uploads/not-a-uuid/complete")
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isBadRequest());
		mockMvc.perform(delete("/uploads/not-a-uuid")
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isBadRequest());
	}

	@Test
	void crossUserCompleteIs404() throws Exception {
		String tokenA = InProcessJwtFixture.bearerToken();
		String tokenB = InProcessJwtFixture.bearerTokenForSubject(OTHER_SUBJECT);
		String id = createUpload(tokenA);

		mockMvc.perform(post("/uploads/" + id + "/complete")
				.header(HttpHeaders.AUTHORIZATION, bearer(tokenB)))
			.andExpect(status().isNotFound());
	}

	@Test
	void completeAfterDeleteMeIs401() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		String id = createUpload(token);

		mockMvc.perform(delete("/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNoContent());

		mockMvc.perform(post("/uploads/" + id + "/complete")
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void imageValidationIs422() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		String id = createUpload(token);
		this.fakeUploadStore.failComplete(new ObjectStoreExceptions.ImageValidationException("invalid image"));

		mockMvc.perform(post("/uploads/" + id + "/complete")
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isUnprocessableContent());
	}

	@Test
	void unavailableIs503() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		String id = createUpload(token);
		this.fakeUploadStore.failComplete(new ObjectStoreExceptions.ObjectStoreUnavailableException("store down"));

		mockMvc.perform(post("/uploads/" + id + "/complete")
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isServiceUnavailable());
	}

	@Test
	void controllerMethodsDoNotAcceptMultipartFile() {
		for (Method method : UploadsController.class.getDeclaredMethods()) {
			for (Parameter parameter : method.getParameters()) {
				assertThat(parameter.getType().getName()).doesNotContain("MultipartFile");
			}
		}
	}

	private String createUpload(String token) throws Exception {
		MvcResult result = mockMvc.perform(post("/uploads")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.contentType(MediaType.APPLICATION_JSON)
				.content(CREATE_BODY))
			.andExpect(status().isOk())
			.andReturn();
		return MAPPER.readTree(result.getResponse().getContentAsString()).get("id").stringValue();
	}

	private String complete(String token, String id) throws Exception {
		MvcResult result = mockMvc.perform(post("/uploads/" + id + "/complete")
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andReturn();
		return result.getResponse().getContentAsString();
	}

	private static void assertReadyWithoutStorageKeys(String body, String id) {
		JsonNode node = MAPPER.readTree(body);
		assertThat(node.get("id").stringValue()).isEqualTo(id);
		assertThat(node.get("status").stringValue()).isEqualTo("READY");
		assertThat(node.get("mediaType").stringValue()).isIn("image/jpeg", "image/png");
		assertThat(node.get("contentLength").isNumber()).isTrue();
		assertThat(node.get("width").isNumber()).isTrue();
		assertThat(node.get("height").isNumber()).isTrue();
		assertThat(body).doesNotContain("bucket", "storageKey", "objectKey");
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	@TestConfiguration
	static class UploadsTestConfiguration {

		@Bean
		@Primary
		ExternalTokenVerifier inProcessExternalTokenVerifier() {
			return InProcessJwtFixture.verifier();
		}

		@Bean
		FakeUploadStore fakeUploadStore() {
			return new FakeUploadStore();
		}

		@Bean
		@Primary
		UploadWorkflow fakeUploadWorkflow(FakeUploadStore store) {
			return new UploadWorkflow(store, new ImageInspector());
		}
	}

	static final class FakeUploadStore implements ObjectStore {

		private static final byte[] STAGED_JPEG = rasterJpeg();

		private final ConcurrentHashMap<String, MediaDeclaration> staged = new ConcurrentHashMap<>();
		private volatile RuntimeException completeFailure;
		volatile UserId lastUserId;
		volatile UploadId lastUploadId;
		volatile UploadId lastAbortedId;

		void reset() {
			this.staged.clear();
			this.completeFailure = null;
			this.lastUserId = null;
			this.lastUploadId = null;
			this.lastAbortedId = null;
		}

		void failComplete(RuntimeException failure) {
			this.completeFailure = failure;
		}

		@Override
		public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration) {
			this.lastUserId = userId;
			this.lastUploadId = uploadId;
			this.staged.put(StorageKeys.stagingKey(userId, uploadId).value(), declaration);
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
			if (this.completeFailure != null) {
				throw this.completeFailure;
			}
			MediaDeclaration declaration = this.staged.get(object.objectKey());
			if (declaration == null) {
				throw new ObjectStoreExceptions.StagedObjectMissingException("missing staged object");
			}
			return new ObjectMetadata(STAGED_JPEG.length, declaration.contentType(), "etag-1");
		}

		@Override
		public byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes) {
			if (this.completeFailure != null) {
				throw this.completeFailure;
			}
			if (!this.staged.containsKey(object.objectKey())) {
				throw new ObjectStoreExceptions.StagedObjectMissingException("missing staged object");
			}
			return STAGED_JPEG;
		}

		@Override
		public void delete(ObjectRef object) {
			String objectKey = object.objectKey();
			int slash = objectKey.lastIndexOf('/');
			this.lastAbortedId = new UploadId(UUID.fromString(objectKey.substring(slash + 1)));
			this.staged.remove(objectKey);
		}

		@Override
		public ClaimResult claim(ClaimCommand command) {
			throw new AssertionError("claim must not be called");
		}

		@Override
		public void purgeUserObjects(UserId userId) {
			throw new AssertionError("purgeUserObjects must not be called");
		}

		@Override
		public void cleanupTemp() {
			throw new AssertionError("cleanupTemp must not be called");
		}

		@Override
		public PresignedAccess presignGet(ObjectRef object, Duration ttl) {
			throw new AssertionError("presignGet must not be called");
		}

		private static byte[] rasterJpeg() {
			try {
				BufferedImage image = new BufferedImage(15, 15, BufferedImage.TYPE_INT_RGB);
				Graphics2D graphics = image.createGraphics();
				graphics.setColor(Color.RED);
				graphics.fillRect(0, 0, 15, 15);
				graphics.dispose();
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
	}
}
