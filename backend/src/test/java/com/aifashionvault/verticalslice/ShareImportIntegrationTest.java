package com.aifashionvault.verticalslice;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;
import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
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
import com.aifashionvault.importer.JdbcShareImportStore;
import com.aifashionvault.importer.ResolutionContext;
import com.aifashionvault.importer.SourceResolver;
import com.aifashionvault.importer.SourceResolverPrecedence;
import com.aifashionvault.importer.SourceResolverRegistration;
import com.aifashionvault.importer.SourceResolverRegistry;
import com.aifashionvault.platform.importer.GenericOpenGraphResolver;
import com.aifashionvault.platform.importer.GenericUrlResolver;
import com.aifashionvault.platform.importer.ImportDns;
import com.aifashionvault.platform.importer.ImportHttpFetcher;
import com.aifashionvault.platform.importer.SharedImageResolver;
import com.aifashionvault.platform.importer.SourceResolverMetrics;
import com.aifashionvault.platform.objectstore.ImageInspector;
import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;
import com.aifashionvault.platform.web.ShareImportMediaAccess;
import com.aifashionvault.platform.web.ShareImportResolverChain;
import com.aifashionvault.platform.web.ShareImportService;
import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end share-import contract over the complete mobile-request to
 * API to resolver-chain path: real controller, real security filter, real
 * service, real PostgreSQL-arbitrated store, and the real resolver trio
 * (shared image, Open Graph, generic URL) behind the production
 * ShareImportResolverChain seam. Only the outbound URL origin (scripted
 * transport behind a controlled DNS gate), the object store, and the token
 * verifier are in-memory fakes. No public Internet call and no S3 dependency
 * ever happens, and no URL, URI, object key, HTML, or bytes appear in test
 * output (ADR-015).
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
@AutoConfigureMockMvc
class ShareImportIntegrationTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final String OTHER_SUBJECT = "integration-other-subject";
	private static final ObjectMapper JSON = new ObjectMapper();

	// Controlled origins; never resolved through the real network.
	private static final String PAGE_URI = "https://source.example.test/item";
	private static final String OTHER_PAGE_URI = "https://source.example.test/other";
	private static final String IMAGE_URI = "https://cdn.example.test/pic.png";
	private static final String PAGE_HOST = "source.example.test";
	private static final String IMAGE_HOST = "cdn.example.test";

	// Opaque-idempotency-key literals (printable ASCII, 16+ characters).
	private static final String KEY_URL = "integration-url-key-0001";
	private static final String KEY_URL_OG = "integration-og-key-0002";
	private static final String KEY_IMAGE = "integration-image-key-0003";
	private static final String KEY_IMAGE_URL = "integration-mixed-key-0004";
	private static final String KEY_RECOVERY = "integration-recovery-key-05";
	private static final String KEY_REPLAY = "integration-replay-key-0006";

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	private static final byte[] PNG = syntheticPng();

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private ControlledMedia media;

	@Autowired
	private ControlledTransport transport;

	@Autowired
	private ControlledDns dns;

	@Autowired
	private ControlledResolverChain resolverChain;

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
	void initFreshState() {
		jdbc.update("DELETE FROM share_import");
		jdbc.update("DELETE FROM identity_mapping");
		jdbc.update("DELETE FROM users");
		media.reset();
		transport.reset();
		dns.reset();
		resolverChain.reset();
	}

	@Test
	void resolverRegistrationsCreateFreshAttemptIsolatedResolversInFrozenOrder() {
		UserId firstOwner = new UserId(UUID.randomUUID());
		ShareImportId firstImport = new ShareImportId(UUID.randomUUID());
		UserId secondOwner = new UserId(UUID.randomUUID());
		ShareImportId secondImport = new ShareImportId(UUID.randomUUID());

		resolverChain.chain(firstOwner, firstImport, "first-attempt-token");
		resolverChain.chain(secondOwner, secondImport, "second-attempt-token");

		assertThat(resolverChain.registrationOrder(0))
			.containsExactly("SHARED_IMAGE(100)", "GENERIC_OPEN_GRAPH(900)", "GENERIC_URL(1000)");
		assertThat(resolverChain.registrationOrder(1))
			.containsExactly("SHARED_IMAGE(100)", "GENERIC_OPEN_GRAPH(900)", "GENERIC_URL(1000)");
		assertThat(resolverChain.resolverTypes(0))
			.containsExactly(SharedImageResolver.class, GenericOpenGraphResolver.class, GenericUrlResolver.class);
		assertThat(resolverChain.resolverTypes(1))
			.containsExactly(SharedImageResolver.class, GenericOpenGraphResolver.class, GenericUrlResolver.class);
		assertThat(resolverChain.hasDistinctResolverInstances(0, 1)).isTrue();
		assertThat(resolverChain.contextMatches(0, firstOwner, firstImport, "first-attempt-token")).isTrue();
		assertThat(resolverChain.contextMatches(1, secondOwner, secondImport, "second-attempt-token")).isTrue();
		assertThat(resolverChain.hasDistinctAttemptMedia(0, 1)).isTrue();
	}



	@Test
	void unauthenticatedRequestsAreRejectedOnAllFourVerbs() throws Exception {
		mockMvc.perform(post("/share/imports")
				.header("Idempotency-Key", KEY_URL)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"kind\":\"URL\",\"sourceUrl\":\"" + PAGE_URI + "\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(this::assertBodyOpaque);
		mockMvc.perform(get("/share/imports/" + UUID.randomUUID()))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(put("/share/imports/" + UUID.randomUUID() + "/image")
				.header("Idempotency-Key", KEY_RECOVERY)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"uploadId\":\"" + UUID.randomUUID() + "\"}"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/share/imports/" + UUID.randomUUID()))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void urlImportWithoutUsableImageReturnsFrozenNeedsImageContract() throws Exception {
		allowPageOrigin();
		transport.serveHtml(PAGE_URI, "<html><head><title>bounded</title></head><body></body></html>");

		MvcResult result = postImport(token(), KEY_URL,
			"{\"kind\":\"URL\",\"sourceUrl\":\"" + PAGE_URI + "\"}", 201);

		String body = result.getResponse().getContentAsString();
		JsonNode node = JSON.readTree(body);
		assertThat(node.get("importId").stringValue()).isNotBlank();
		assertThat(node.get("status").stringValue()).isEqualTo("NEEDS_IMAGE");
		assertThat(node.get("reason").stringValue()).isEqualTo("URL_RESOLUTION_FAILED");
		assertThat(node.get("recoveryActions").toString())
			.isEqualTo("[\"CHOOSE_SCREENSHOT\",\"UPLOAD_IMAGE\"]");
		assertBodyOpaque(result);
		assertThat(count("share_import")).isEqualTo(1);
	}

	@Test
	void urlImportWithSafeOpenGraphImageBecomesReadyAndEmitsNoQueueOrNotificationWork() throws Exception {
		allowPageOrigin();
		allowImageOrigin();
		transport.serveHtml(PAGE_URI, ogHtml(IMAGE_URI));
		transport.serve(IMAGE_URI, 200, "image/png", PNG);

		MvcResult result = postImport(token(), KEY_URL_OG,
			"{\"kind\":\"URL\",\"sourceUrl\":\"" + PAGE_URI + "\"}", 201);

		String body = result.getResponse().getContentAsString();
		assertThat(JSON.readTree(body).get("status").stringValue()).isEqualTo("READY");
		assertBodyOpaque(result);

		// Exactly the two controlled fetches happened: page then image.
		assertThat(transport.requestCount()).isEqualTo(2);

		// The resolved image was published once, under an owner/import-derived private key.
		assertThat(media.publishedCount()).isEqualTo(1);
		assertThat(media.publishedUnderOwnerImportPrefix(ownerIdOfSubject())).isTrue();

		// Resolution is bounded request work: no generation job, queue message, or notification.
		assertThat(count("generation_job")).isZero();
		assertThat(count("generation_work_outbox")).isZero();
		assertThat(count("generation_notification_event")).isZero();

		Map<String, Object> row = jdbc.queryForMap(
			"SELECT resolver_state, storage_key IS NOT NULL AS has_key FROM share_import");
		assertThat(row.get("resolver_state")).isEqualTo("READY");
		assertThat(row.get("has_key")).isEqualTo(Boolean.TRUE);
		resolverChain.assertSafeMeters();
	}

	@Test
	void retryableOriginFailurePreservesDependencyUnavailableContract() throws Exception {
		allowPageOrigin();
		transport.serve(PAGE_URI, 503, "text/html", new byte[0]);

		MvcResult result = postImport(token(), KEY_URL,
			"{\"kind\":\"URL\",\"sourceUrl\":\"" + PAGE_URI + "\"}", 503);

		assertProblem(result, 503, "DEPENDENCY_UNAVAILABLE");
		assertThat(jdbc.queryForObject("SELECT resolver_state FROM share_import", String.class))
			.isEqualTo("FAILED_RETRYABLE");
		resolverChain.assertSafeMeters();
	}



	@Test
	void imageImportUsesVerifiedUploadDirectlyWithoutAnyNetworkFetch() throws Exception {
		String uploadId = media.stageVerifiedUpload();

		MvcResult result = postImport(token(), KEY_IMAGE,
			"{\"kind\":\"IMAGE\",\"uploadId\":\"" + uploadId + "\"}", 201);

		assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("status").stringValue())
			.isEqualTo("READY");
		assertBodyOpaque(result);
		assertThat(transport.requestCount()).isZero();
		assertThat(media.claimedCount()).isEqualTo(1);
		assertThat(media.publishedCount()).isZero();
	}

	@Test
	void imageUrlImportPrefersTheUploadAndRetainsTheSourceReferenceWithoutFetching() throws Exception {
		String uploadId = media.stageVerifiedUpload();

		MvcResult result = postImport(token(), KEY_IMAGE_URL,
			"{\"kind\":\"IMAGE_URL\",\"sourceUrl\":\"" + PAGE_URI + "\",\"uploadId\":\"" + uploadId + "\"}", 201);

		String importId = JSON.readTree(result.getResponse().getContentAsString()).get("importId").stringValue();
		assertThat(JSON.readTree(result.getResponse().getContentAsString()).get("status").stringValue())
			.isEqualTo("READY");
		assertBodyOpaque(result);

		// User media outranks optional metadata enrichment: no URL fetch in the critical path.
		assertThat(transport.requestCount()).isZero();
		assertThat(media.claimedCount()).isEqualTo(1);

		// The URL is retained as bounded source metadata on the same import.
		assertThat(jdbc.queryForObject(
			"SELECT source_url IS NOT NULL AND resolver_state = 'READY' FROM share_import", Boolean.class))
			.isTrue();

		mockMvc.perform(get("/share/imports/" + importId)
				.header(HttpHeaders.AUTHORIZATION, bearer(token())))
			.andExpect(status().isOk())
			.andExpect(response -> assertThat(
				JSON.readTree(response.getResponse().getContentAsString()).get("status").stringValue())
				.isEqualTo("READY"))
			.andExpect(this::assertBodyOpaque);
	}

	@Test
	void recoveryFromNeedsImageAttachesTheSameImportAndReplaysTheSameResult() throws Exception {
		allowPageOrigin();
		transport.serveHtml(PAGE_URI, "<html><head></head><body></body></html>");
		String importId = createNeedsImage(token(), KEY_RECOVERY, PAGE_URI);
		String uploadId = media.stageVerifiedUpload();
		assertThat(transport.requestCount()).isEqualTo(1);

		MvcResult recovered = attachImage(token(), importId, KEY_RECOVERY, uploadId, 200);
		assertThat(JSON.readTree(recovered.getResponse().getContentAsString()).get("status").stringValue())
			.isEqualTo("READY");
		assertThat(JSON.readTree(recovered.getResponse().getContentAsString()).get("importId").stringValue())
			.isEqualTo(importId);
		assertBodyOpaque(recovered);

		// Recovery never refetches the URL: the shared image resolver wins.
		assertThat(transport.requestCount()).isEqualTo(1);
		assertThat(media.claimedCount()).isEqualTo(1);

		MvcResult replay = attachImage(token(), importId, KEY_RECOVERY, uploadId, 200);
		assertThat(JSON.readTree(replay.getResponse().getContentAsString()).get("status").stringValue())
			.isEqualTo("READY");
		assertBodyOpaque(replay);

		assertThat(count("share_import")).isEqualTo(1);
		assertThat(media.claimedCount()).isEqualTo(1);
	}

	@Test
	void createReplayReturnsTheSameImportAndChangedFingerprintConflictsWithoutMutation() throws Exception {
		allowPageOrigin();
		transport.serveHtml(PAGE_URI, "<html><head></head><body></body></html>");
		String first = createNeedsImage(token(), KEY_REPLAY, PAGE_URI);

		MvcResult replay = postImport(token(), KEY_REPLAY,
			"{\"kind\":\"URL\",\"sourceUrl\":\"" + PAGE_URI + "\"}", 200);
		assertThat(JSON.readTree(replay.getResponse().getContentAsString()).get("importId").stringValue())
			.isEqualTo(first);
		assertBodyOpaque(replay);
		assertThat(count("share_import")).isEqualTo(1);

		MvcResult conflict = postImport(token(), KEY_REPLAY,
			"{\"kind\":\"URL\",\"sourceUrl\":\"" + OTHER_PAGE_URI + "\"}", 409);
		assertProblem(conflict, 409, "IDEMPOTENCY_KEY_REUSED");
		assertThat(count("share_import")).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM share_import WHERE attempt_token IS NOT NULL",
			Integer.class)).isZero();
	}

	@Test
	void deletionIsIdempotentRemovesOwnedMediaAndMakesReadsNotFound() throws Exception {
		allowPageOrigin();
		allowImageOrigin();
		transport.serveHtml(PAGE_URI, ogHtml(IMAGE_URI));
		transport.serve(IMAGE_URI, 200, "image/png", PNG);
		String token = token();
		String importId = createReady(token, KEY_URL_OG, PAGE_URI);
		assertThat(media.publishedCount()).isEqualTo(1);

		mockMvc.perform(delete("/share/imports/" + importId)
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNoContent());
		assertThat(media.deletedCount()).isEqualTo(1);
		assertThat(media.deletedEveryPublishedObject()).isTrue();

		// Deleting again is still 204; the object removal is not repeated.
		mockMvc.perform(delete("/share/imports/" + importId)
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNoContent());
		assertThat(media.deletedCount()).isEqualTo(1);

		MvcResult missing = mockMvc.perform(get("/share/imports/" + importId)
				.header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isNotFound())
			.andReturn();
		assertProblem(missing, 404, "IMPORT_NOT_FOUND");

		MvcResult foreign = mockMvc.perform(get("/share/imports/" + importId)
				.header(HttpHeaders.AUTHORIZATION, bearer(otherToken())))
			.andExpect(status().isNotFound())
			.andReturn();
		assertProblem(foreign, 404, "IMPORT_NOT_FOUND");
	}

	// ---------- helpers ----------

	private static String ogHtml(String imageUrl) {
		return "<html><head>"
			+ "<meta property=\"og:image\" content=\"" + imageUrl + "\">"
			+ "<meta property=\"og:title\" content=\"Bounded Fixture Title\">"
			+ "<meta property=\"og:site_name\" content=\"Bounded Fixture Site\">"
			+ "</head><body></body></html>";
	}

	private static byte[] syntheticPng() {
		try {
			BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(image, "png", out);
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException("synthetic fixture could not be produced", ex);
		}
	}

	private void allowPageOrigin() {
		dns.allow(PAGE_HOST);
	}

	private void allowImageOrigin() {
		dns.allow(IMAGE_HOST);
	}

	private UUID ownerIdOfSubject() {
		return jdbc.queryForObject("SELECT user_id FROM identity_mapping WHERE subject = ?",
			UUID.class, InProcessJwtFixture.subject());
	}

	private MvcResult postImport(String token, String key, String body, int expectedStatus) throws Exception {
		MvcResult result = mockMvc.perform(post("/share/imports")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(body))
			.andExpect(status().is(expectedStatus))
			.andReturn();
		assertBodyOpaque(result);
		return result;
	}

	private MvcResult attachImage(String token, String importId, String key, String uploadId, int expectedStatus)
			throws Exception {
		MvcResult result = mockMvc.perform(put("/share/imports/" + importId + "/image")
				.header(HttpHeaders.AUTHORIZATION, bearer(token))
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"uploadId\":\"" + uploadId + "\"}"))
			.andExpect(status().is(expectedStatus))
			.andReturn();
		assertBodyOpaque(result);
		return result;
	}

	private String createNeedsImage(String token, String key, String pageUri) throws Exception {
		MvcResult result = postImport(token, key, "{\"kind\":\"URL\",\"sourceUrl\":\"" + pageUri + "\"}", 201);
		String body = result.getResponse().getContentAsString();
		assertThat(JSON.readTree(body).get("status").stringValue()).isEqualTo("NEEDS_IMAGE");
		return JSON.readTree(body).get("importId").stringValue();
	}

	private String createReady(String token, String key, String pageUri) throws Exception {
		MvcResult result = postImport(token, key, "{\"kind\":\"URL\",\"sourceUrl\":\"" + pageUri + "\"}", 201);
		String body = result.getResponse().getContentAsString();
		assertThat(JSON.readTree(body).get("status").stringValue()).isEqualTo("READY");
		return JSON.readTree(body).get("importId").stringValue();
	}

	private static void assertProblem(MvcResult result, int expectedStatus, String expectedCode) {
		assertThat(result.getResponse().getStatus()).isEqualTo(expectedStatus);
		try {
			JsonNode node = JSON.readTree(result.getResponse().getContentAsString());
			assertThat(node.has("code")).isTrue();
			assertThat(node.get("code").stringValue()).isEqualTo(expectedCode);
			assertThat(node.size()).isEqualTo(1);
		}
		catch (IOException ex) {
			throw new AssertionError("problem body is not parseable JSON with an opaque code", ex);
		}
	}

	/** ADR-015: bodies never carry a source URL, object key, or media data. */
	private void assertBodyOpaque(MvcResult result) throws Exception {
		assertThat(result.getResponse().getContentAsString())
			.doesNotContain("http", "users/", "storageKey", "objectKey", "bucket", "sourceUrl");
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
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

	// ---------- controlled doubles ----------

	/** Scriptable transport: the only URL origin the resolver chain can ever see. */
	static final class ControlledTransport implements ImportHttpFetcher {

		private final Map<String, Hop> hops = new ConcurrentHashMap<>();
		private final AtomicInteger requests = new AtomicInteger();

		void serve(String uri, int status, String contentType, byte[] body) {
			hops.put(uri, new Hop(status, contentType, body));
		}

		void serveHtml(String uri, String html) {
			serve(uri, 200, "text/html", html.getBytes(StandardCharsets.UTF_8));
		}

		int requestCount() {
			return requests.get();
		}

		void reset() {
			hops.clear();
			requests.set(0);
		}

		@Override
		public ImportHttpResponse get(URI target, long maxBytes) {
			requests.incrementAndGet();
			Hop hop = hops.get(target.toString());
			int status = hop == null ? 404 : hop.status();
			String contentType = hop == null || hop.contentType() == null ? "application/octet-stream"
					: hop.contentType();
			byte[] body = hop == null ? new byte[0] : hop.body();
			return new ImportHttpResponse(status, contentType, (long) body.length, false,
					new ByteArrayInputStream(body), target, null);
		}

		private record Hop(int status, String contentType, byte[] body) {
		}
	}

	/** DNS gate: only explicitly allowed hostnames pass; everything else fails closed. */
	static final class ControlledDns extends ImportDns {

		private static final InetAddress PUBLIC_V4 = publicV4();

		private final java.util.Set<String> allowed = ConcurrentHashMap.newKeySet();

		void allow(String host) {
			allowed.add(host);
		}

		void reset() {
			allowed.clear();
		}

		@Override
		public ResolvedTarget resolveHost(URI httpUri) {
			String host = httpUri.getHost();
			if (host == null || !allowed.contains(host)) {
				// Fails closed: an unscripted origin is a test bug, never a real lookup.
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

	/**
	 * In-memory object-storage double covering both the owner-scoped service seam
	 * and the resolver media seam. Counters and booleans only: no key text ever
	 * reaches an assertion failure message (ADR-015).
	 */
	static final class ControlledMedia implements ShareImportMediaAccess, ImportMediaAccess {

		private final Map<UploadId, ImportMediaAccess.VerifiedImage> verified = new ConcurrentHashMap<>();
		private final List<StorageKey> claimed = new CopyOnWriteArrayList<>();
		private final List<StorageKey> published = new CopyOnWriteArrayList<>();
		private final List<StorageKey> deleted = new CopyOnWriteArrayList<>();

		String stageVerifiedUpload() {
			UploadId id = new UploadId(UUID.randomUUID());
			verified.put(id, new ImportMediaAccess.VerifiedImage(id, "image/png", 2048, "version-1"));
			return id.value().toString();
		}

		int claimedCount() {
			return claimed.size();
		}

		int publishedCount() {
			return published.size();
		}

		int deletedCount() {
			return deleted.size();
		}

		boolean publishedUnderOwnerImportPrefix(UUID ownerId) {
			String prefix = "users/" + ownerId + "/imports/";
			return !published.isEmpty() && published.stream().allMatch(key -> key.value().startsWith(prefix));
		}

		boolean deletedEveryPublishedObject() {
			return !published.isEmpty() && deleted.containsAll(published);
		}

		void reset() {
			verified.clear();
			claimed.clear();
			published.clear();
			deleted.clear();
		}

		@Override
		public Optional<ImportMediaAccess.VerifiedImage> findVerifiedUpload(UserId ownerId, UploadId uploadId) {
			return Optional.ofNullable(verified.get(uploadId));
		}

		@Override
		public ImportMediaAccess.ClaimOutcome claimVerifiedUpload(
			UserId ownerId, ImportMediaAccess.VerifiedImage upload, StorageKey destination) {
			claimed.add(destination);
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

		@Override
		public Optional<ImportMediaAccess.VerifiedImage> findVerifiedUpload(UploadId uploadId) {
			return Optional.ofNullable(verified.get(uploadId));
		}

		@Override
		public ImportMediaAccess.ClaimOutcome claimVerifiedUpload(
			ImportMediaAccess.VerifiedImage upload, StorageKey destination) {
			claimed.add(destination);
			return ImportMediaAccess.ClaimOutcome.CLAIMED;
		}

		@Override
		public boolean publishFetchedImage(InputStream content, long contentLength, StorageKey destination,
				String contentType) {
			published.add(destination);
			return true;
		}
	}

	/** Production registration shape over controlled transport, DNS, storage, and meters. */
	static final class ControlledResolverChain implements ShareImportResolverChain {

		private static final Set<String> SAFE_METER_TAG_KEYS = Set.of("resolver", "status", "reason");
		private static final Set<String> SAFE_RESOLVER_TAGS = Set.of(
			"SHARED_IMAGE", "GENERIC_OPEN_GRAPH", "GENERIC_URL");
		private static final Set<String> SAFE_STATUS_TAGS = Set.of(
			"RESOLVED", "NEEDS_IMAGE", "RETRYABLE_FAILURE", "TERMINAL_FAILURE", "UNSUPPORTED", "EXCEPTION");
		private static final Set<String> SAFE_REASON_TAGS = Set.of(
			"NONE", "URL_RESOLUTION_FAILED", "DEPENDENCY_UNAVAILABLE", "RESOLUTION_TIMEOUT", "INVALID_SOURCE");

		private final ControlledMedia media;
		private final ControlledTransport transport;
		private final ControlledDns dns;
		private final List<Attempt> attempts = new CopyOnWriteArrayList<>();
		private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
		private final Map<ResolutionContext, List<SourceResolver>> created = new ConcurrentHashMap<>();
		private final SourceResolverRegistry registry;

		ControlledResolverChain(ControlledMedia media, ControlledTransport transport, ControlledDns dns) {
			this.media = media;
			this.transport = transport;
			this.dns = dns;
			SourceResolverMetrics metrics = new SourceResolverMetrics(meters);
			this.registry = new SourceResolverRegistry(List.of(
				new SourceResolverRegistration("GENERIC_URL", SourceResolverPrecedence.GENERIC_URL,
					context -> registered(context, "GENERIC_URL", new GenericUrlResolver(), metrics)),
				new SourceResolverRegistration("GENERIC_OPEN_GRAPH", SourceResolverPrecedence.GENERIC_OPEN_GRAPH,
					context -> registered(context, "GENERIC_OPEN_GRAPH", new GenericOpenGraphResolver(
						transport, dns, new ImageInspector(), context.mediaAccess(), context.ownerId(), context.importId(),
						context.attemptToken()), metrics)),
				new SourceResolverRegistration("SHARED_IMAGE", SourceResolverPrecedence.SHARED_IMAGE,
					context -> registered(context, "SHARED_IMAGE", new SharedImageResolver(
						context.mediaAccess(), context.ownerId(), context.importId()), metrics))));
		}

		@Override
		public ImportResolver chain(UserId ownerId, ShareImportId importId, String attemptToken) {
			ImportMediaAccess attemptMedia = new AttemptMedia(media);
			ResolutionContext context = new ResolutionContext(ownerId, importId, attemptToken, attemptMedia);
			List<SourceResolver> resolvers = registry.createResolvers(context);
			attempts.add(new Attempt(context, List.copyOf(created.remove(context))));
			return new ImportResolver(resolvers);
		}

		List<String> registrationOrder(int attempt) {
			return resolverTypes(attempt).stream()
				.map(type -> type == SharedImageResolver.class ? "SHARED_IMAGE(100)"
					: type == GenericOpenGraphResolver.class ? "GENERIC_OPEN_GRAPH(900)" : "GENERIC_URL(1000)")
				.toList();
		}

		List<Class<?>> resolverTypes(int attempt) {
			return attempts.get(attempt).resolvers().stream().map(Object::getClass).toList();
		}

		boolean hasDistinctResolverInstances(int first, int second) {
			List<SourceResolver> left = attempts.get(first).resolvers();
			List<SourceResolver> right = attempts.get(second).resolvers();
			return left.size() == right.size()
				&& java.util.stream.IntStream.range(0, left.size()).allMatch(index -> left.get(index) != right.get(index));
		}

		boolean contextMatches(int attempt, UserId owner, ShareImportId importId, String token) {
			ResolutionContext context = attempts.get(attempt).context();
			return context.ownerId().equals(owner) && context.importId().equals(importId)
				&& context.attemptToken().equals(token);
		}

		boolean hasDistinctAttemptMedia(int first, int second) {
			return attempts.get(first).context().mediaAccess() != attempts.get(second).context().mediaAccess();
		}

		void assertSafeMeters() {
			assertThat(meters.getMeters()).isNotEmpty();
			assertThat(meters.getMeters())
				.allSatisfy(meter -> assertThat(meter.getId().getName())
					.isIn(SourceResolverMetrics.OUTCOME, SourceResolverMetrics.DURATION));
			for (Meter meter : meters.getMeters()) {
				assertThat(Set.copyOf(meter.getId().getTags().stream().map(tag -> tag.getKey()).toList()))
					.isSubsetOf(SAFE_METER_TAG_KEYS);
				meter.getId().getTags().forEach(tag -> assertThat(switch (tag.getKey()) {
					case "resolver" -> SAFE_RESOLVER_TAGS;
					case "status" -> SAFE_STATUS_TAGS;
					case "reason" -> SAFE_REASON_TAGS;
					default -> Set.<String>of();
				}).contains(tag.getValue()));
			}
		}

		void reset() {
			attempts.clear();
			meters.clear();
			created.clear();
		}

		private SourceResolver registered(ResolutionContext context, String id, SourceResolver resolver,
				SourceResolverMetrics metrics) {
			created.computeIfAbsent(context, ignored -> new CopyOnWriteArrayList<>()).add(resolver);
			return metrics.decorate(id, resolver);
		}

		private record Attempt(ResolutionContext context, List<SourceResolver> resolvers) {
		}

		private record AttemptMedia(ControlledMedia delegate) implements ImportMediaAccess {

			@Override
			public Optional<VerifiedImage> findVerifiedUpload(UploadId uploadId) {
				return delegate.findVerifiedUpload(uploadId);
			}

			@Override
			public ClaimOutcome claimVerifiedUpload(VerifiedImage upload, StorageKey destination) {
				return delegate.claimVerifiedUpload(upload, destination);
			}

			@Override
			public boolean publishFetchedImage(InputStream content, long contentLength, StorageKey destination,
					String contentType) {
				return delegate.publishFetchedImage(content, contentLength, destination, contentType);
			}

			@Override
			public void deleteObject(StorageKey key) {
				delegate.deleteObject(key);
			}
		}
	}

	@TestConfiguration
	static class ShareImportIntegrationTestConfiguration {

		@Bean
		@Primary
		ExternalTokenVerifier inProcessExternalTokenVerifier() {
			return InProcessJwtFixture.verifier();
		}

		@Bean
		ControlledMedia controlledMedia() {
			return new ControlledMedia();
		}

		@Bean
		ControlledTransport controlledTransport() {
			return new ControlledTransport();
		}

		@Bean
		ControlledDns controlledDns() {
			return new ControlledDns();
		}

		@Bean
		@Primary
		ShareImportMediaAccess controlledShareMedia(ControlledMedia media) {
			return media;
		}

		@Bean
		@Primary
		ShareImportResolverChain controlledResolverChain(
			ControlledMedia media, ControlledTransport transport, ControlledDns dns) {
			return new ControlledResolverChain(media, transport, dns);
		}

		@Bean
		@Primary
		ShareImportService controlledShareImportService(
			DataSource dataSource, ControlledMedia media, ControlledResolverChain chain) {
			return new ShareImportService(new JdbcShareImportStore(dataSource), media, chain);
		}
	}
}
