package com.aifashionvault.platform.web;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
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

import com.aifashionvault.importer.ImportMediaAccess;
import com.aifashionvault.importer.ImportResolver;
import com.aifashionvault.importer.ResolutionReason;
import com.aifashionvault.importer.ResolutionResult;
import com.aifashionvault.importer.ResolutionStatus;
import com.aifashionvault.importer.SourceReference;
import com.aifashionvault.platform.importer.ImportDns;
import com.aifashionvault.platform.importer.ImportHttpFetcher;
import com.aifashionvault.platform.importer.ImportHttpFetcher.ImportHttpResponse;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;
import com.aifashionvault.platform.importer.GenericOpenGraphResolver;
import com.aifashionvault.platform.importer.GenericUrlResolver;
import com.aifashionvault.platform.importer.ImporterRetryableException;
import com.aifashionvault.platform.objectstore.ImageInspector;
import com.aifashionvault.platform.objectstore.S3ObjectStore;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;
import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Share-import HTTP contract: concurrent same-key claims, cross-user
 * isolation, invalid input rejection, recovery, and 404 opacity. Test output
 * carries no URL, URI, object key, HTML, or bytes (ADR-015).
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@AutoConfigureMockMvc
class ShareImportHttpTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final String OTHER_SUBJECT = "other-external-subject";
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String KEY = "share-key-0123456789abcdef";
	private static final String OTHER_KEY = "share-key-fedcba9876543210";
	private static final String IMAGE_URL = "https://example.test/item";

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
	private FakeShareMedia fakeMedia;

	@Autowired
	private FakeShareChain fakeChain;

	@DynamicPropertySource
	static void registerDatasource(DynamicPropertyRegistry registry) {
		POSTGRES.start();
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();
		registry.add("vault.db.url", POSTGRES::getJdbcUrl);
		registry.add("vault.db.username", POSTGRES::getUsername);
		registry.add("vault.db.password", POSTGRES::getPassword);
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@BeforeEach
	void clearTables() {
		jdbc.update("DELETE FROM share_import");
		jdbc.update("DELETE FROM identity_mapping");
		jdbc.update("DELETE FROM users");
		this.fakeMedia.reset();
		this.fakeChain.reset();
	}

	@Test
	void unauthenticatedRequestsAre401() throws Exception {
		mockMvc.perform(post("/share/imports")
				.header("Idempotency-Key", KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"URL\",\"sourceUrl\":\"" + IMAGE_URL + "\"}"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/share/imports/" + UUID.randomUUID()))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(put("/share/imports/" + UUID.randomUUID() + "/image")
				.header("Idempotency-Key", KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"uploadId\":\"" + UUID.randomUUID() + "\"}"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/share/imports/" + UUID.randomUUID()))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void urlImportWithoutUsableImageResolvesNeedsImageWithRecoveryActions() throws Exception {
		MvcResult result = postImport(token(), KEY, "{\"kind\":\"URL\",\"sourceUrl\":\"" + IMAGE_URL + "\"}", 201);

		String body = result.getResponse().getContentAsString();
		JsonNode node = MAPPER.readTree(body);
		assertThat(node.get("importId").stringValue()).isNotBlank();
		assertThat(node.get("status").stringValue()).isEqualTo("NEEDS_IMAGE");
		assertThat(node.get("reason").stringValue()).isEqualTo("URL_RESOLUTION_FAILED");
		assertThat(node.get("recoveryActions").toString())
			.isEqualTo("[\"CHOOSE_SCREENSHOT\",\"UPLOAD_IMAGE\"]");
		assertThat(body).doesNotContain(IMAGE_URL, "storageKey", "objectKey", "bucket");
	}

	@Test
	void imageImportWithVerifiedUploadIsReady() throws Exception {
		String uploadId = fakeMedia.stageVerifiedUpload();

		MvcResult result = postImport(token(), KEY,
			"{\"kind\":\"IMAGE\",\"uploadId\":\"" + uploadId + "\"}", 201);

		String body = result.getResponse().getContentAsString();
		assertThat(MAPPER.readTree(body).get("status").stringValue()).isEqualTo("READY");
		assertThat(body).doesNotContain("storageKey", "objectKey", "bucket");
	}

	@Test
	void replayedTerminalCreateReturns200WithSameImport() throws Exception {
		MvcResult first = postImport(token(), KEY,
			"{\"kind\":\"URL\",\"sourceUrl\":\"" + IMAGE_URL + "\"}", 201);
		String importId = MAPPER.readTree(first.getResponse().getContentAsString()).get("importId").stringValue();

		MvcResult second = postImport(token(), KEY,
			"{\"kind\":\"URL\",\"sourceUrl\":\"" + IMAGE_URL + "\"}", 200);

		assertThat(MAPPER.readTree(second.getResponse().getContentAsString()).get("importId").stringValue())
			.isEqualTo(importId);
		assertThat(rowCount()).isEqualTo(1);
	}

	@Test
	void differentFingerprintSameKeyConflictsWithoutMutation() throws Exception {
		postImport(token(), KEY, "{\"kind\":\"URL\",\"sourceUrl\":\"" + IMAGE_URL + "\"}", 201);
		String existingUrl = jdbc.queryForObject(
			"SELECT source_url FROM share_import", String.class);

		MvcResult conflict = postImport(token(), KEY,
			"{\"kind\":\"URL\",\"sourceUrl\":\"https://example.test/other\"}", 409);

		assertProblem(conflict, 409, "IDEMPOTENCY_KEY_REUSED");
		assertThat(jdbc.queryForObject("SELECT source_url FROM share_import", String.class))
			.isEqualTo(existingUrl);
		assertThat(rowCount()).isEqualTo(1);
	}

	@Test
	void concurrentSameKeyClaimsYieldOneImportAndSameId() throws Exception {
		String token = token();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		AtomicReference<Result> first = new AtomicReference<>();
		AtomicReference<Result> second = new AtomicReference<>();

		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			pool.submit(() -> invokeCreate(token, KEY, ready, start, first));
			pool.submit(() -> invokeCreate(token, KEY, ready, start, second));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(first.get().failure()).isNull();
		assertThat(second.get().failure()).isNull();
		assertThat(first.get().status() + second.get().status()).isEqualTo(401);
		assertThat(second.get().importId()).isEqualTo(first.get().importId());
		assertThat(rowCount()).isEqualTo(1);
	}

	@Test
	void sameKeyUnderTwoUsersCreatesDistinctIsolatedImports() throws Exception {
		MvcResult mine = postImport(token(), KEY,
			"{\"kind\":\"URL\",\"sourceUrl\":\"" + IMAGE_URL + "\"}", 201);
		MvcResult theirs = postImport(otherToken(), KEY,
			"{\"kind\":\"URL\",\"sourceUrl\":\"" + IMAGE_URL + "\"}", 201);

		String mineId = MAPPER.readTree(mine.getResponse().getContentAsString()).get("importId").stringValue();
		String theirsId = MAPPER.readTree(theirs.getResponse().getContentAsString()).get("importId").stringValue();
		assertThat(mineId).isNotEqualTo(theirsId);
		assertThat(rowCount()).isEqualTo(2);

		mockMvc.perform(get("/share/imports/" + theirsId)
				.header(HttpHeaders.AUTHORIZATION, bearer(token())))
			.andExpect(status().isNotFound());
		mockMvc.perform(delete("/share/imports/" + mineId)
				.header(HttpHeaders.AUTHORIZATION, bearer(otherToken())))
			.andExpect(status().isNotFound());
	}

	@Test
	void getReturnsCurrentOwnerScopedState() throws Exception {
		String importId = createNeedsImage(token(), KEY);

		mockMvc.perform(get("/share/imports/" + importId)
				.header(HttpHeaders.AUTHORIZATION, bearer(token())))
			.andExpect(status().isOk())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andExpect(result -> {
				JsonNode node = MAPPER.readTree(result.getResponse().getContentAsString());
				assertThat(node.get("importId").stringValue()).isEqualTo(importId);
				assertThat(node.get("status").stringValue()).isEqualTo("NEEDS_IMAGE");
			});
	}

	@Test
	void recoveryFromNeedsImageBecomesReadyAndReplays() throws Exception {
		String importId = createNeedsImage(token(), KEY);
		String uploadId = fakeMedia.stageVerifiedUpload();

		MvcResult recovered = mockMvc.perform(put("/share/imports/" + importId + "/image")
				.header(HttpHeaders.AUTHORIZATION, bearer(token()))
				.header("Idempotency-Key", KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"uploadId\":\"" + uploadId + "\"}"))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(MAPPER.readTree(recovered.getResponse().getContentAsString()).get("status").stringValue())
			.isEqualTo("READY");

		MvcResult replay = mockMvc.perform(put("/share/imports/" + importId + "/image")
				.header(HttpHeaders.AUTHORIZATION, bearer(token()))
				.header("Idempotency-Key", KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"uploadId\":\"" + uploadId + "\"}"))
			.andExpect(status().isOk())
			.andReturn();
		assertThat(MAPPER.readTree(replay.getResponse().getContentAsString()).get("status").stringValue())
			.isEqualTo("READY");
		assertThat(rowCount()).isEqualTo(1);
	}

	@Test
	void recoveryAttachAfterCompletionConflictsOnReusedKeyOrDifferentUpload() throws Exception {
		// Verified defect: after recovery completes with upload A, a PUT with
		// any valid key and different upload B used to return 200 READY as if
		// replayed, stranding B. The persisted (key, upload) binding must make
		// a different upload under the recovery key 409 IDEMPOTENCY_KEY_REUSED
		// and a different key after recovery 409 IMPORT_STATE_CONFLICT.
		String importId = createNeedsImage(token(), KEY);
		String uploadA = fakeMedia.stageVerifiedUpload();
		String uploadB = fakeMedia.stageVerifiedUpload();
		attachImage(token(), importId, uploadA, 200);

		MvcResult differentUpload = attachImage(token(), importId, uploadB, 409);
		assertProblem(differentUpload, 409, "IDEMPOTENCY_KEY_REUSED");

		MvcResult differentKey = mockMvc.perform(put("/share/imports/" + importId + "/image")
				.header(HttpHeaders.AUTHORIZATION, bearer(token()))
				.header("Idempotency-Key", OTHER_KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"uploadId\":\"" + uploadB + "\"}"))
			.andExpect(status().isConflict())
			.andReturn();
		assertProblem(differentKey, 409, "IMPORT_STATE_CONFLICT");

		assertThat(rowCount()).isEqualTo(1);
		Map<String, Object> row = jdbc.queryForMap(
			"SELECT resolver_state, storage_key, recovery_key, recovery_upload_id FROM share_import");
		assertThat(row.get("resolver_state")).isEqualTo("READY");
		assertThat(row.get("recovery_key")).isEqualTo(KEY);
		assertThat(row.get("recovery_upload_id")).isEqualTo(UUID.fromString(uploadA));
	}

	@Test
	void recoveryFromFailedStateRecoversReady() throws Exception {
		fakeChain.retryable = ResolutionReason.DEPENDENCY_UNAVAILABLE;
		postImport(token(), KEY, "{\"kind\":\"URL\",\"sourceUrl\":\"" + IMAGE_URL + "\"}", 503);
		fakeChain.retryable = null;

		String failedImportId = jdbc.queryForObject("SELECT id FROM share_import", java.util.UUID.class).toString();
		String uploadId = fakeMedia.stageVerifiedUpload();
		MvcResult recovered = attachImage(token(), failedImportId, uploadId, 200);

		assertThat(MAPPER.readTree(recovered.getResponse().getContentAsString()).get("status").stringValue())
			.isEqualTo("READY");
		assertThat(rowCount()).isEqualTo(1);
		assertThat(fakeMedia.publishedCount()).isEqualTo(1);
	}

	@Test
	void unclaimableUploadIsRejectedBeforeCreatingAnImport() throws Exception {
		MvcResult result = postImport(token(), KEY,
			"{\"kind\":\"IMAGE\",\"uploadId\":\"" + UUID.randomUUID() + "\"}", 409);
		assertProblem(result, 409, "UPLOAD_NOT_CLAIMABLE");
		assertThat(rowCount()).isEqualTo(0);
	}

	@Test
	void unclaimableRecoveryUploadConflicts() throws Exception {
		String importId = createNeedsImage(token(), KEY);

		MvcResult result = attachImage(token(), importId, UUID.randomUUID().toString(), 409);
		assertProblem(result, 409, "UPLOAD_NOT_CLAIMABLE");
	}

	@Test
	void invalidJsonIs400WithOpaqueCode() throws Exception {
		MvcResult result = mockMvc.perform(post("/share/imports")
				.header(HttpHeaders.AUTHORIZATION, bearer(token()))
				.header("Idempotency-Key", KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{"))
			.andExpect(status().isBadRequest())
			.andReturn();
		assertProblem(result, 400, "INVALID_SHARE_IMPORT");
	}

	@Test
	void missingOrBlankIdempotencyKeyIs400() throws Exception {
		mockMvc.perform(post("/share/imports")
				.header(HttpHeaders.AUTHORIZATION, bearer(token()))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"URL\",\"sourceUrl\":\"" + IMAGE_URL + "\"}"))
			.andExpect(status().isBadRequest());
		mockMvc.perform(post("/share/imports")
				.header(HttpHeaders.AUTHORIZATION, bearer(token()))
				.header("Idempotency-Key", " ")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"URL\",\"sourceUrl\":\"" + IMAGE_URL + "\"}"))
			.andExpect(status().isBadRequest());
	}

	@Test
	void invalidImportIdIs400() throws Exception {
		mockMvc.perform(get("/share/imports/not-a-uuid")
				.header(HttpHeaders.AUTHORIZATION, bearer(token())))
			.andExpect(status().isBadRequest());
	}

	@Test
	void discriminantMismatchIs400() throws Exception {
		postProblem(token(), "{\"kind\":\"URL\",\"sourceUrl\":\"" + IMAGE_URL + "\",\"uploadId\":\""
			+ UUID.randomUUID() + "\"}", 400, "INVALID_SHARE_IMPORT");
		postProblem(token(), "{\"kind\":\"IMAGE\",\"sourceUrl\":\"" + IMAGE_URL + "\",\"uploadId\":\""
			+ UUID.randomUUID() + "\"}", 400, "INVALID_SHARE_IMPORT");
		postProblem(token(), "{\"kind\":\"URL\"}", 400, "INVALID_SHARE_IMPORT");
		postProblem(token(), "{\"kind\":\"IMAGE\",\"uploadId\":\"not-a-uuid\"}", 400, "INVALID_SHARE_IMPORT");
		postProblem(token(), "{\"kind\":\"UNKNOWN\",\"sourceUrl\":\"" + IMAGE_URL + "\"}", 400,
			"INVALID_SHARE_IMPORT");
		postProblem(token(), "{\"kind\":\"URL\",\"sourceUrl\":\"ftp://example.test/x\"}", 400,
			"INVALID_SHARE_IMPORT");
	}

	@Test
	void unknownDeletedAndForeignImportsAre404WithoutDisclosure() throws Exception {
		String token = token();
		mockMvc.perform(get("/share/imports/" + UUID.randomUUID())
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNotFound())
			.andExpect(result -> assertThat(result.getResponse().getContentAsString())
				.doesNotContain("user", "owner", "userId"));

		String importId = createNeedsImage(token, KEY);
		mockMvc.perform(delete("/share/imports/" + importId)
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNoContent());
		mockMvc.perform(get("/share/imports/" + importId)
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNotFound());
		mockMvc.perform(delete("/share/imports/" + importId)
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNoContent());

		String otherImportId = createNeedsImage(otherToken(), OTHER_KEY);
		MvcResult foreign = mockMvc.perform(get("/share/imports/" + otherImportId)
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNotFound())
			.andReturn();
		assertProblem(foreign, 404, "IMPORT_NOT_FOUND");
	}

	@Test
	void deleteIsIdempotent204() throws Exception {
		String token = token();
		String importId = createNeedsImage(token, KEY);

		mockMvc.perform(delete("/share/imports/" + importId)
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNoContent());
		mockMvc.perform(delete("/share/imports/" + importId)
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNoContent());
		mockMvc.perform(delete("/share/imports/" + UUID.randomUUID())
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNotFound());
	}

	@Test
	void resolverTimeoutMapsTo504AndDependencyFailureTo503() throws Exception {
		fakeChain.retryable = ResolutionReason.RESOLUTION_TIMEOUT;
		MvcResult timeout = postImport(token(), KEY,
			"{\"kind\":\"URL\",\"sourceUrl\":\"" + IMAGE_URL + "\"}", 504);
		assertProblem(timeout, 504, "RESOLUTION_TIMEOUT");

		fakeChain.retryable = ResolutionReason.DEPENDENCY_UNAVAILABLE;
		MvcResult unavailable = postImport(token(), OTHER_KEY,
			"{\"kind\":\"URL\",\"sourceUrl\":\"" + IMAGE_URL + "\"}", 503);
		assertProblem(unavailable, 503, "DEPENDENCY_UNAVAILABLE");
	}

	@Test
	void realChainOpenGraphPublishNoLongerThrowsOnUsableMetadata() {
		// Defect 1: production PlatformResolverChain built its owner-scoped media
		// adapter without an ImportObjectWriter, so every production OG publish hit
		// the owner-scoped guard and threw. Observable check against a mock S3
		// client: the chain resolves READY and the conditional create reaches S3.
		S3Client s3 = mock(S3Client.class);
		when(s3.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
			.thenReturn(PutObjectResponse.builder().build());
		S3ObjectStore store = new S3ObjectStore(s3, testPresigner(), BUCKETS, Clock.systemUTC(), (object, age) -> {
		});
		ShareImportResolverChain chain = ShareImportConfiguration.PlatformResolverChain.over(store,
				new ImageInspector(), s3, null);
		assertThat(chain).isNotNull();
		UserId owner = new UserId(UUID.randomUUID());
		ShareImportId importId = new ShareImportId(UUID.randomUUID());
		assertThat(chain.chain(owner, importId, "attempt-token-1")).isNotNull();

		RecordingTransport transport = new RecordingTransport();
		transport.serveHtml("https://origin.example.test/item", ogHtml("https://cdn.example.test/pic.png"));
		transport.servePng("https://cdn.example.test/pic.png");
		GatedDns dns = new GatedDns("origin.example.test", "cdn.example.test");


		ImportResolver resolver = new ImportResolver(List.of(
			new GenericOpenGraphResolver(transport, dns, new ImageInspector(),
					((ShareImportConfiguration.PlatformResolverChain) chain).ownerMedia(owner), owner, importId,
					"attempt-token-1"),
			new GenericUrlResolver()));
		ResolutionResult result = resolver.resolve(
			new SourceReference(URI.create("https://origin.example.test/item"), null));

		assertThat(result.status()).isEqualTo(ResolutionStatus.RESOLVED);
		assertThat(result.storageKey()).isNotNull();
		verify(s3, times(1)).putObject(any(PutObjectRequest.class), any(RequestBody.class));
	}

	@Test
	void attachOnExpiredResolvingLeaseWithUnclaimableUploadLeavesRowUnmutated() throws Exception {
		// Defect 2: an expired URL-import lease with an unclaimable upload used to
		// mutate the row to FAILED_TERMINAL before rejecting. Now the request data
		// is validated first and the row stays RESOLVING with no reason set.
		String importId = strandResolvingImportWithExpiredLease();

		MvcResult result = attachImage(token(), importId, UUID.randomUUID().toString(), 409);
		assertProblem(result, 409, "UPLOAD_NOT_CLAIMABLE");

		Map<String, Object> row = jdbc.queryForMap(
			"SELECT resolver_state, terminal_reason_code, attempt_count FROM share_import");
		assertThat(row.get("resolver_state")).isEqualTo("RESOLVING");
		assertThat(row.get("terminal_reason_code")).isNull();
		assertThat(((Number) row.get("attempt_count")).intValue()).isEqualTo(1);
	}

	@Test
	void concurrentDeleteDuringAttemptRemovesTheClaimedObject() throws Exception {
		// Defect 3: a resolver publishes an object, then blocks until a concurrent
		// DELETE has fenced the attempt; the losing attempt must best-effort delete
		// the object it claimed (observable via the fake media double).
		CountDownLatch published = new CountDownLatch(1);
		CountDownLatch deleted = new CountDownLatch(1);
		fakeChain.blockAfterPublish(published, deleted);
		AtomicReference<Throwable> createFailure = new AtomicReference<>();

		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			pool.submit(() -> {
				try {
					// The DELETE fences the attempt mid-flight; the replayed
					// create observes the deleted row as 404 IMPORT_NOT_FOUND.
					postImport(token(), KEY, "{\"kind\":\"URL\",\"sourceUrl\":\"" + IMAGE_URL + "\"}", 404);
				}
				catch (Throwable failure) {
					createFailure.set(failure);
				}
			});
			assertThat(published.await(10, TimeUnit.SECONDS)).isTrue();
			String importId = jdbc.queryForObject("SELECT id FROM share_import", java.util.UUID.class).toString();
			mockMvc.perform(delete("/share/imports/" + importId)
					.header(HttpHeaders.AUTHORIZATION, bearer(token())))
				.andExpect(status().isNoContent());
			deleted.countDown();
		}
		finally {
			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}
		assertThat(createFailure.get()).isNull();
		// The losing attempt removed the object it had already published.
		assertThat(fakeMedia.deletedEveryPublishedObject()).isTrue();
		assertThat(fakeMedia.deletedCount()).isEqualTo(1);
	}


	private static final VaultEnvironmentProperties.S3 BUCKETS = new VaultEnvironmentProperties.S3(
		"test-avatar", "test-vault", "test-look", "test-temp");

	private static S3Presigner testPresigner() {
		S3Presigner presigner = S3Presigner.builder()
			.region(Region.US_EAST_1)
			.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
			.build();
		// ponytail: never closed; the JVM owns it for the single test use
		return presigner;
	}

	private static String ogHtml(String imageUrl) {
		return "<html><head><meta property=\"og:image\" content=\"" + imageUrl + "\"></head><body></body></html>";
	}

	/** Strands one RESOLVING URL import whose lease is already expired. */
	private String strandResolvingImportWithExpiredLease() throws Exception {
		// Prime the identity mapping with one authenticated 404 lookup, then
		// strand a row whose lease expired well past RESOLUTION_LEASE.
		mockMvc.perform(get("/share/imports/" + UUID.randomUUID())
				.header(HttpHeaders.AUTHORIZATION, bearer(token())))
			.andExpect(status().isNotFound());
		jdbc.update(
			"INSERT INTO share_import (id, user_id, source_kind, source_url, storage_key, resolver_state, "
				+ "idempotency_key, request_fingerprint, attempt_count, attempt_leased_at, attempt_token, "
				+ "created_at, updated_at) "
				+ "VALUES (?, (SELECT user_id FROM identity_mapping WHERE subject = ?), 'URL', ?, NULL, 'RESOLVING', "
				+ "?, ?, 1, ?, ?, now() - interval '10 minutes', now() - interval '10 minutes')",
			UUID.randomUUID(), InProcessJwtFixture.subject(), IMAGE_URL, "stranded-key-0000000001",
			"URL|" + IMAGE_URL + "|", java.sql.Timestamp.from(Instant.now().minus(Duration.ofMinutes(11))),
			UUID.randomUUID());
		return jdbc.queryForObject("SELECT id FROM share_import", java.util.UUID.class).toString();
	}

	/** Scriptable transport: the only URL origin these tests can ever see. */
	static final class RecordingTransport implements ImportHttpFetcher {

		private final Map<String, Hop> hops = new ConcurrentHashMap<>();

		void serveHtml(String uri, String html) {
			hops.put(uri, new Hop(200, "text/html", html.getBytes(StandardCharsets.UTF_8)));
		}

		void servePng(String uri) {
			hops.put(uri, new Hop(200, "image/png", syntheticPng()));
		}

		@Override
		public ImportHttpResponse get(URI target, long maxBytes) {
			Hop hop = hops.get(target.toString());
			if (hop == null) {
				return new ImportHttpResponse(404, "application/octet-stream", 0L, false,
						java.io.InputStream.nullInputStream(), target, null);
			}
			return new ImportHttpResponse(hop.status(), hop.contentType(), (long) hop.body().length, false,
					new ByteArrayInputStream(hop.body()), target, null);
		}

		private record Hop(int status, String contentType, byte[] body) {
		}
	}

	/** DNS gate: only explicitly allowed hostnames pass; everything else fails closed. */
	static final class GatedDns extends ImportDns {

		private static final InetAddress PUBLIC_V4 = publicV4();

		private final java.util.Set<String> allowed = ConcurrentHashMap.newKeySet();

		GatedDns(String... hosts) {
			for (String host : hosts) {
				allowed.add(host);
			}
		}

		@Override
		public ResolvedTarget resolveHost(URI httpUri) {
			String host = httpUri.getHost();
			if (host == null || !allowed.contains(host)) {
				throw new IllegalStateException("controlled DNS gate rejected an origin");
			}
			return new ResolvedTarget(host, new InetAddress[] { PUBLIC_V4 });
		}

		private static InetAddress publicV4() {
			try {
				return InetAddress.getByName("93.184.216.34");
			}
			catch (UnknownHostException ex) {
				throw new IllegalStateException(ex);
			}
		}
	}

	private static byte[] syntheticPng() {
		try {
			BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			javax.imageio.ImageIO.write(image, "png", out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException("synthetic fixture could not be produced", ex);
		}
	}

	@Test
	void problemResponsesContainOnlyOpaqueCodes() throws Exception {
		MvcResult result = postProblem(token(), "{\"kind\":\"URL\"}", 400, "INVALID_SHARE_IMPORT");
		assertThat(result.getResponse().getContentAsString())
			.doesNotContain(IMAGE_URL, "storageKey", "objectKey", "bucket", "stack", "exception");
	}

	private MvcResult postImport(String token, String key, String body, int expectedStatus) throws Exception {
		return mockMvc.perform(post("/share/imports")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().is(expectedStatus))
			.andExpect(result -> assertThat(result.getResponse().getContentAsString())
				.doesNotContain(IMAGE_URL, "storageKey", "objectKey", "bucket"))
			.andReturn();
	}

	private MvcResult postProblem(String token, String body, int expectedStatus, String expectedCode)
			throws Exception {
		MvcResult result = mockMvc.perform(post("/share/imports")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.header("Idempotency-Key", KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().is(expectedStatus))
			.andReturn();
		assertProblem(result, expectedStatus, expectedCode);
		return result;
	}

	private MvcResult attachImage(String token, String importId, String uploadId, int expectedStatus)
			throws Exception {
		return mockMvc.perform(put("/share/imports/" + importId + "/image")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.header("Idempotency-Key", KEY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"uploadId\":\"" + uploadId + "\"}"))
			.andExpect(status().is(expectedStatus))
			.andReturn();
	}

	private String createNeedsImage(String token, String key) throws Exception {
		MvcResult result = postImport(token, key,
			"{\"kind\":\"URL\",\"sourceUrl\":\"" + IMAGE_URL + "\"}", 201);
		return MAPPER.readTree(result.getResponse().getContentAsString()).get("importId").stringValue();
	}

	private static void assertProblem(MvcResult result, int expectedStatus, String expectedCode) {
		assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
		try {
			JsonNode node = MAPPER.readTree(result.getResponse().getContentAsString());
			assertThat(node.has("code")).isTrue();
			assertThat(node.get("code").stringValue()).isEqualTo(expectedCode);
			assertThat(node.size()).isEqualTo(1);
		}
		catch (Exception ex) {
			throw new AssertionError("problem body is not parseable JSON with a code", ex);
		}
	}

	private void invokeCreate(String token, String key, CountDownLatch ready, CountDownLatch start,
			AtomicReference<Result> target) {
		ready.countDown();
		try {
			if (!start.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("start latch timed out");
			}
			MvcResult response = mockMvc.perform(post("/share/imports")
					.header(HttpHeaders.AUTHORIZATION, bearer(token))
					.header("Idempotency-Key", key)
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"kind\":\"URL\",\"sourceUrl\":\"" + IMAGE_URL + "\"}"))
				.andReturn();
			String body = response.getResponse().getContentAsString();
			String importId = body.isBlank() ? null
				: MAPPER.readTree(body).get("importId").stringValue();
			target.set(new Result(response.getResponse().getStatus(), importId, null));
		}
		catch (Throwable failure) {
			target.set(new Result(0, null, failure));
		}
	}

	private int rowCount() {
		return jdbc.queryForObject("SELECT COUNT(*) FROM share_import", Integer.class);
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private static String token() {
		return InProcessJwtFixture.bearerToken();
	}

	private static String otherToken() {
		return InProcessJwtFixture.bearerTokenForSubject(OTHER_SUBJECT);
	}

	private record Result(int status, String importId, Throwable failure) {
	}

	@TestConfiguration
	static class ShareImportTestConfiguration {

		@Bean
		@Primary
		ExternalTokenVerifier inProcessExternalTokenVerifier() {
			return InProcessJwtFixture.verifier();
		}

		@Bean
		FakeShareMedia fakeShareMedia() {
			return new FakeShareMedia();
		}

		@Bean
		FakeShareChain fakeShareChain(FakeShareMedia media) {
			return new FakeShareChain(media);
		}

		@Bean
		@Primary
		ShareImportMediaAccess fakeShareMediaAccess(FakeShareMedia media) {
			return media;
		}

		@Bean
		@Primary
		ShareImportResolverChain fakeShareResolverChain(FakeShareChain chain) {
			return chain;
		}

		@Bean
		@Primary
		ShareImportService fakeShareImportService(
			javax.sql.DataSource dataSource,
			ShareImportMediaAccess media,
			ShareImportResolverChain chains
		) {
			return new ShareImportService(
				new com.aifashionvault.importer.JdbcShareImportStore(dataSource), media, chains);
		}
	}

	/** In-memory owner-scoped media access; no object store is touched. */
	static final class FakeShareMedia implements ShareImportMediaAccess {

		private final Map<UploadId, ImportMediaAccess.VerifiedImage> verified = new ConcurrentHashMap<>();
		private final List<StorageKey> published = new CopyOnWriteArrayList<>();
		private final List<StorageKey> deleted = new CopyOnWriteArrayList<>();

		String stageVerifiedUpload() {
			UploadId id = new UploadId(UUID.randomUUID());
			verified.put(id, new ImportMediaAccess.VerifiedImage(id, "image/jpeg", 2048, "version-1"));
			return id.value().toString();
		}

		int publishedCount() {
			return published.size();
		}

		int deletedCount() {
			return deleted.size();
		}

		boolean deletedEveryPublishedObject() {
			return !published.isEmpty() && deleted.containsAll(published);
		}

		void reset() {
			verified.clear();
			published.clear();
			deleted.clear();
		}

		@Override
		public Optional<ImportMediaAccess.VerifiedImage> findVerifiedUpload(UserId ownerId, UploadId uploadId) {
			return Optional.ofNullable(verified.get(uploadId));
		}

		@Override
		public ImportMediaAccess.ClaimOutcome claimVerifiedUpload(
			UserId ownerId,
			ImportMediaAccess.VerifiedImage upload,
			StorageKey destination
		) {
			return ImportMediaAccess.ClaimOutcome.CLAIMED;
		}

		@Override
		public boolean publishFetchedImage(
			UserId ownerId, InputStream content, long contentLength, StorageKey destination) {
			published.add(destination);
			return true;
		}

		@Override
		public void deleteObject(StorageKey key) {
			deleted.add(key);
		}
	}
	/** Fake resolver chain: shared image wins, URL falls through to NEEDS_IMAGE. */
	static final class FakeShareChain implements ShareImportResolverChain {

		volatile ResolutionReason retryable;
		volatile CountDownLatch publishLatch;
		volatile CountDownLatch resumeLatch;

		private final FakeShareMedia media;

		FakeShareChain(FakeShareMedia media) {
			this.media = media;
		}

		void reset() {
			retryable = null;
			publishLatch = null;
			resumeLatch = null;
		}

		/** Makes the next URL resolution publish an object, then block until released. */
		void blockAfterPublish(CountDownLatch published, CountDownLatch resume) {
			this.publishLatch = published;
			this.resumeLatch = resume;
		}

		@Override
		public ImportResolver chain(UserId ownerId, ShareImportId importId, String attemptToken) {
			return new ImportResolver(List.of(
				new FakeSharedImage(media),
				new FakeGenericUrl(this, ownerId, importId, attemptToken)));
		}
	}

	private static final class FakeSharedImage implements
			com.aifashionvault.importer.SourceResolver {

		private final FakeShareMedia media;

		FakeSharedImage(FakeShareMedia media) {
			this.media = media;
		}

		@Override
		public boolean supports(SourceReference source) {
			return source.verifiedUploadId() != null;
		}

		@Override
		public ResolutionResult resolve(SourceReference source) {
			if (media.findVerifiedUpload(null, source.verifiedUploadId()).isEmpty()) {
				return unsupported();
			}
			return new ResolutionResult(ResolutionStatus.RESOLVED,
				new StorageKey("imports/fake/" + UUID.randomUUID()), source.url(), null, null, null);
		}
	}

	private static final class FakeGenericUrl implements com.aifashionvault.importer.SourceResolver {

		private final FakeShareChain owner;
		private final UserId ownerId;
		private final ShareImportId importId;
		private final String attemptToken;

		FakeGenericUrl(FakeShareChain owner, UserId ownerId, ShareImportId importId, String attemptToken) {
			this.owner = owner;
			this.ownerId = ownerId;
			this.importId = importId;
			this.attemptToken = attemptToken;
		}

		@Override
		public boolean supports(SourceReference source) {
			return source.url() != null;
		}

		@Override
		public ResolutionResult resolve(SourceReference source) {
			if (owner.retryable != null) {
				throw new ImporterRetryableException(owner.retryable, "import dependency failed");
			}
			if (owner.publishLatch != null) {
				// Defect-3 path: publish a real object, signal, then hold the
				// attempt open until the concurrent DELETE has fenced it.
				StorageKey destination = new StorageKey(
					"users/" + ownerId.value() + "/imports/" + importId.value() + "-" + attemptToken + ".jpg");
				owner.media.publishFetchedImage(ownerId, InputStream.nullInputStream(), 0, destination);
				owner.publishLatch.countDown();
				try {
					assertThat(owner.resumeLatch.await(30, TimeUnit.SECONDS)).isTrue();
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("resume latch interrupted", ex);
				}
				return new ResolutionResult(ResolutionStatus.RESOLVED, destination, source.url(), null, null, null);
			}
			return new ResolutionResult(ResolutionStatus.NEEDS_IMAGE, null, source.url(), null, null,
				ResolutionReason.URL_RESOLUTION_FAILED);
		}
	}

	private static ResolutionResult unsupported() {
		return new ResolutionResult(ResolutionStatus.UNSUPPORTED, null, null, null, null, null);
	}
}
