package com.aifashionvault.platform.inferhub;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.imageio.ImageIO;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.client.match.MockRestRequestMatchers;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.generation.vton.ProviderRegistry;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;
import com.aifashionvault.platform.fashn.FashnConfig;
import com.aifashionvault.platform.fashn.FashnVtonProvider;
import com.aifashionvault.platform.generation.GenerationMetrics;
import com.aifashionvault.platform.generation.GenerationWorkProcessor;
import com.aifashionvault.platform.objectstore.ClaimCommand;
import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.GenerationClaimCommand;
import com.aifashionvault.platform.objectstore.GenerationMediaStore;
import com.aifashionvault.platform.objectstore.MediaDeclaration;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.platform.objectstore.ProductInputClaimCommand;
import com.aifashionvault.platform.objectstore.UploadCapability;
import com.aifashionvault.platform.queue.GenerationWorkLease;
import com.aifashionvault.platform.queue.GenerationWorkQueue;
import com.aifashionvault.platform.security.ExternalTokenVerifier;
import com.aifashionvault.platform.security.InProcessJwtFixture;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * t301/U83: the LOCAL-RUN slice end to end with InferHub selected and the FASHN key BLANK —
 * the exact posture a {@code VTON_IMAGE_API_KEY}-only deployment produces.
 *
 * <p>The FASHN side is the SHIPPED present-empty form ({@code vault.fashn.api-key=} resolves to
 * ""), not a removed property: with {@code ${FASHN_API_KEY:}} an unset variable yields a
 * PRESENT-EMPTY value, which is what {@code NonBlankApiKeyCondition} rejects. A removed property
 * would test dead semantics.
 *
 * <p>The stub is at the HTTP seam, not the provider seam. {@link MockRestServiceServer} is bound
 * to the SAME {@link RestClient.Builder} {@link InferHubConfig#inferHubRestClientBuilder} builds
 * and is installed over the product's {@code inferHubRestClient} bean, so the REAL
 * {@link InferHubVtonProvider}, mapper, config and selection composition all run and the bytes on
 * the wire are the ones asserted. No {@link com.aifashionvault.generation.vton.VtonProvider}
 * double is registered anywhere. No network; the key is a literal that exists only in this file.
 *
 * <p>Chain covered, link by link: selection, admission, queue, provider, media persistence and
 * attempt attribution. Deliberately not covered: SQS delivery itself (the recorded lease is handed
 * over directly, as {@code GenerationHttpContractTest} does), and anything past COMPLETED.
 */
@SpringBootTest(properties = {
	"spring.autoconfigure.exclude=",
	// The local-run posture: InferHub keyed, FASHN blank (present-empty, not removed).
	"vault.inferhub.api-key=t301-local-run-inferhub-key",
	"vault.fashn.api-key="
})
@AutoConfigureMockMvc
class InferHubGenerationIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres").withStartupTimeout(Duration.ofMinutes(5));

	private static final ObjectMapper JSON = new ObjectMapper();
	private static final String KEY = "t301-local-run-inferhub-key";
	private static final String ENDPOINT = InferHubConfig.BASE_URL + "/v1/chat/completions";
	/**
	 * The application-owned quality gate RETRYs below 256px on a side and the media policy
	 * rejects below 15px, so 256x256 is the smallest candidate that survives both and still
	 * reaches publication.
	 */
	private static final int CANDIDATE_SIDE = 256;

	/** The stub seam over the adapter's own client; see {@link #stubbedInferHubClient}. */
	private static MockRestServiceServer http;

	/**
	 * The HTTP seam, installed OVER the product's own {@code inferHubRestClient} bean by name.
	 * {@code @TestBean} is used rather than a same-named {@code @Bean} because bean-definition
	 * overriding is disabled in this project; {@code enforceOverride} makes the override a real
	 * assertion — the context fails if the product bean is ever renamed or removed.
	 */
	@TestBean(name = "inferHubRestClient", enforceOverride = true)
	RestClient stubbedInferHubClient;

	static { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); }

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired ProviderRegistry registry;
	@Autowired ObjectProvider<FashnVtonProvider> fashnProviders;
	@Autowired ObjectProvider<InferHubVtonProvider> inferHubProviders;
	@Autowired VaultEnvironmentProperties properties;
	@Autowired GenerationWorkProcessor processor;
	@Autowired RecordingWorkQueue queue;
	@Autowired InMemoryObjectStore objects;

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
	void resetState() {
		jdbc.update("TRUNCATE TABLE generation_admission_counter, generation_admission_reservation, look_item, look, "
			+ "quality_result, generation_callback_inbox, generation_work_outbox, generation_quota_reservation, "
			+ "generation_notification_delivery, generation_notification_event, generation_provider_capacity_slot, "
			+ "generation_attempt, generation_render_step_item, generation_render_step, generation_job_product, "
			+ "generation_job, vault_item, studio_item, studio_session, avatar_image, avatar_profile, identity_mapping, "
			+ "users CASCADE");
		queue.sent.clear();
		queue.deleted.clear();
		objects.clear();
	}

	/**
	 * (1) Selection: with the FASHN key blank the context starts, the registry names
	 * {@code inferhub}, the admission predicate is true, and NO FASHN provider bean is registered.
	 */
	@Test
	void inferhubIsSelectedAndNoFashnProviderBeanExists() {
		assertThat(registry.selectedProviderKey().value()).isEqualTo("inferhub");
		assertThat(registry.providerKeys()).map(com.aifashionvault.generation.vton.ProviderKey::value)
			.containsExactly("inferhub");
		assertThat(inferHubProviders.getIfAvailable()).isNotNull();
		assertThat(fashnProviders.getIfAvailable()).isNull();
		// Provider-agnostic admission predicate is true; the FASHN-specific one is not.
		assertThat(FashnConfig.anyProviderConfigured(properties)).isTrue();
		assertThat(FashnConfig.providerConfigured(properties)).isFalse();
	}

	/**
	 * (2) Admission: an InferHub-keyed, FASHN-blank deployment ADMITS the create. A FASHN-only
	 * admission predicate answers 503 GENERATION_PROVIDER_UNAVAILABLE here — this is the cell
	 * that distinguishes the two.
	 */
	@Test
	void createIsAdmittedNot503AndTheJobIsQueued() throws Exception {
		Owner owner = seedOwner();
		MvcResult result = mockMvc.perform(post("/generations")
				.header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "t301-admit")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"productUploadId\":\"" + UUID.randomUUID() + "\"}"))
			.andExpect(status().isAccepted())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andReturn();

		JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
		UUID jobId = UUID.fromString(body.get("id").stringValue());
		assertThat(body.get("status").stringValue()).isEqualTo("QUEUED");
		assertThat(result.getResponse().getContentAsString()).doesNotContain("GENERATION_PROVIDER_UNAVAILABLE");
		// The create path enqueues DURABLY: the queue hand-off is the outbox relay's job, and
		// that relay is off by default (vault.generation.queue.relay-enabled), so this row is
		// the queue link here, not queue.send.
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_work_outbox WHERE generation_job_id = ?", Integer.class, jobId))
			.isEqualTo(1);
		assertThat(count("generation_job")).isEqualTo(1);
		assertThat(count("generation_attempt")).isZero();
		assertThat(queue.sent).isEmpty();
	}

	/**
	 * (3)+(4)+(5): the recorded queue hands the real job to the real processor, the real adapter
	 * issues its request through the stubbed seam, the owner-verified response shape is consumed,
	 * the output media is PERSISTED, and the attempt is attributed to {@code inferhub}.
	 */
	@Test
	void theQueuedJobRunsTheRealAdapterThroughTheStubbedSeamAndPersistsTheOutput() throws Exception {
		byte[] candidate = expectOwnerVerifiedRun();

		Owner owner = seedOwner();
		UUID jobId = create(owner, "t301-slice");

		processor.process(new GenerationWorkLease(jobId, "t301-receipt"));

		http.verify();
		assertThat(queue.deleted).map(GenerationWorkLease::generationJobId).containsExactly(jobId);

		// (4) The output media reference is PERSISTED, not merely produced: one published look
		// whose storage key is the promoted LOOK key carrying the attempt id.
		Map<String, Object> look = jdbc.queryForMap(
			"SELECT storage_key, provider, quality_status FROM look WHERE generation_job_id = ?", jobId);
		UUID attemptId = jdbc.queryForObject(
			"SELECT id FROM generation_attempt WHERE generation_job_id = ?", UUID.class, jobId);
		assertThat(look.get("storage_key")).isEqualTo("users/" + owner.userId() + "/looks/" + attemptId + ".png");
		assertThat(look.get("provider")).isEqualTo("inferhub");
		assertThat(look.get("quality_status")).isEqualTo("PASS");

		// The exact decoded bytes reached the store: the promoted object holds the PNG the
		// owner-verified data-URI carried, not an empty or placeholder blob.
		assertThat(objects.bytes(StorageDomain.LOOK, (String) look.get("storage_key"))).isEqualTo(candidate);

		// (5) The provider recorded on the attempt is the selected provider.
		assertThat(jdbc.queryForObject(
			"SELECT provider FROM generation_attempt WHERE generation_job_id = ?", String.class, jobId))
			.isEqualTo("inferhub");
		assertThat(jdbc.queryForObject(
			"SELECT technical_outcome FROM generation_attempt WHERE generation_job_id = ?", String.class, jobId))
			.isEqualTo("SUCCESS");
	}

	/**
	 * The stubbed request IS the wire contract: one authenticated chat-completions POST whose
	 * content array is a text part then two {@code image_url} parts carrying inline data-URIs —
	 * the avatar and the garment, in that order, with no signed-read surface.
	 */
	@Test
	void theAdapterSendsTheOwnerVerifiedRequestShape() throws Exception {
		expectOwnerVerifiedRun();

		Owner owner = seedOwner();
		processor.process(new GenerationWorkLease(create(owner, "t301-wire"), "t301-receipt"));

		http.verify();
		// The body is captured at the seam, where the real adapter's real bytes arrive.
		JsonNode sent = JSON.readTree(Wire.lastBody);
		assertThat(sent.get("model").stringValue()).isEqualTo("cb/gemini-3.1-flash-image");
		JsonNode content = sent.get("messages").get(0).get("content");
		assertThat(content).hasSize(3);
		assertThat(content.get(0).get("type").stringValue()).isEqualTo("text");
		assertThat(content.get(0).get("text").asString()).isNotBlank();
		assertThat(content.get(1).get("type").stringValue()).isEqualTo("image_url");
		assertThat(content.get(1).get("image_url").get("url").stringValue())
			.startsWith("data:image/png;base64,");
		assertThat(content.get(2).get("type").stringValue()).isEqualTo("image_url");
		assertThat(content.get(2).get("image_url").get("url").stringValue())
			.startsWith("data:image/png;base64,");
		// Inline only: the wire contract has no signed-read surface.
		assertThat(Wire.lastBody).doesNotContain("http://", "https://");
	}

	/**
	 * The bytes the real adapter actually put on the wire, captured at the seam. Recording the
	 * body is the point of the seam: the same bytes the mock matched are the ones asserted.
	 */
	private static final class Wire {
		private static String lastBody = "";

		private Wire() {
		}

		static void record(org.springframework.http.client.ClientHttpRequest request) {
			lastBody = new String(((org.springframework.mock.http.MockHttpOutputMessage) request)
				.getBodyAsBytes(), java.nio.charset.StandardCharsets.UTF_8);
		}
	}

	// --- helpers -----------------------------------------------------------------

	/**
	 * The {@code @TestBean} factory for {@link #stubbedInferHubClient}. The builder comes from
	 * the product's own {@link InferHubConfig#inferHubRestClientBuilder}, so the base URL and the
	 * bearer default header are the adapter's real ones; {@link MockRestServiceServer} then
	 * intercepts that builder, and the real {@link InferHubVtonProvider} issues its real
	 * request through it. No network, and the provider is not replaced.
	 */
	private static RestClient inferHubRestClient() {
		RestClient.Builder builder = InferHubConfig.inferHubRestClientBuilder(KEY);
		http = MockRestServiceServer.bindTo(builder).build();
		return builder.build();
	}

	/**
	 * Declares the one expected wire call for this test: the real endpoint, the key the product
	 * config puts on every request, and the owner-verified response body.
	 *
	 * <p>The seam is a single context-cached server (the {@code @TestBean} factory runs once),
	 * so it is reset here: a {@code MockRestServiceServer} refuses new expectations once real
	 * requests have been made, and a leaked expectation from a prior test would silently
	 * satisfy this one instead of the bytes this test declared.
	 *
	 * @return the candidate PNG the response carries, for the persistence assertion
	 */
	private static byte[] expectOwnerVerifiedRun() throws Exception {
		byte[] candidate = png(CANDIDATE_SIDE, CANDIDATE_SIDE);
		http.reset();
		Wire.lastBody = "";
		// Recording the body is part of matching: the same bytes the seam matched are the ones
		// the wire-shape test asserts, so a shape change fails there rather than passing silently.
		http.expect(once(), request -> {
			MockRestRequestMatchers.header(HttpHeaders.AUTHORIZATION, "Bearer " + KEY).match(request);
			MockRestRequestMatchers.requestTo(ENDPOINT).match(request);
			Wire.record(request);
		})
			.andRespond(withSuccess(ownerVerifiedResponse(candidate), MediaType.APPLICATION_JSON));
		return candidate;
	}

	/**
	 * The owner-verified response shape: {@code choices[0].message.content} is a text part
	 * followed by an inline base64 data-URI image part.
	 */
	private static String ownerVerifiedResponse(byte[] candidate) {
		return "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":["
			+ "{\"type\":\"text\",\"text\":\"Here is the generated look.\"},"
			+ "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64,"
			+ Base64.getEncoder().encodeToString(candidate) + "\"}}"
			+ "]}}]}";
	}

	/**
	 * A valid PNG of the requested size, unchecked-failure by design: {@link ImageIO} writing to
	 * a {@link ByteArrayOutputStream} cannot fail for the JDK-bundled PNG writer, and the object
	 * store double needs the bytes without a checked-exception signature.
	 */
	private static byte[] png(int width, int height) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try {
			ImageIO.write(new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_RGB),
				"png", bytes);
		}
		catch (java.io.IOException ex) {
			throw new IllegalStateException("in-memory PNG encoding", ex);
		}
		return bytes.toByteArray();
	}

	private UUID create(Owner owner, String key) throws Exception {
		MvcResult result = mockMvc.perform(post("/generations")
				.header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"productUploadId\":\"" + UUID.randomUUID() + "\"}"))
			.andExpect(status().isAccepted())
			.andReturn();
		return UUID.fromString(JSON.readTree(result.getResponse().getContentAsString()).get("id").stringValue());
	}

	private Owner seedOwner() throws Exception {
		String token = InProcessJwtFixture.bearerToken();
		mockMvc.perform(get("/me").header(HttpHeaders.AUTHORIZATION, bearer(token)))
			.andExpect(status().isOk());
		UUID userId = jdbc.queryForObject(
			"SELECT user_id FROM identity_mapping WHERE subject = ?", UUID.class, InProcessJwtFixture.subject());
		objects.put(StorageDomain.AVATAR, avatarKey(userId), png(CANDIDATE_SIDE, CANDIDATE_SIDE));
		UUID profile = UUID.randomUUID();
		UUID image = UUID.randomUUID();
		Timestamp now = Timestamp.from(Instant.now());
		jdbc.update("INSERT INTO avatar_profile (id, user_id, primary_image_id, status, revision, created_at, updated_at) "
			+ "VALUES (?, ?, NULL, 'ACTIVE', 1, ?, ?)", profile, userId, now, now);
		jdbc.update("INSERT INTO avatar_image (id, profile_id, storage_key, quality_status, created_at) "
			+ "VALUES (?, ?, ?, 'ACCEPTED', ?)", image, profile, avatarKey(userId), now);
		jdbc.update("UPDATE avatar_profile SET primary_image_id = ? WHERE id = ?", image, profile);
		return new Owner(token, userId);
	}

	private static String avatarKey(UUID userId) {
		return "users/" + userId + "/avatars/model.png";
	}

	private int count(String table) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private record Owner(String token, UUID userId) {
	}

	// --- test doubles at the INFRASTRUCTURE seams only ---------------------------
	// The provider is never doubled: the real InferHub adapter, mapper and config run against
	// the stubbed HTTP seam installed by the @TestBean factory below.

	@TestConfiguration
	static class Configuration {
		@Bean @Primary ExternalTokenVerifier verifier() {
			return InProcessJwtFixture.verifier();
		}

		@Bean @Primary SimpleMeterRegistry testGenerationMeterRegistry() {
			return new SimpleMeterRegistry();
		}

		@Bean @Primary GenerationMetrics testGenerationMetrics(SimpleMeterRegistry testGenerationMeterRegistry) {
			return new GenerationMetrics(testGenerationMeterRegistry);
		}

		@Bean @Primary InMemoryObjectStore inMemoryObjectStore() {
			return new InMemoryObjectStore();
		}

		// RULE: never reuse a production bean name for a test bean. The product
		// generationMediaStore is @Primary and delegates to this stub, so the request path and
		// the worker still use the double.
		@Bean GenerationMediaStore stubGenerationMediaStore(InMemoryObjectStore objects) {
			return objects.mediaStore();
		}

		@Bean @Primary GenerationWorkQueue generationWorkQueueDouble() {
			return new RecordingWorkQueue();
		}

	}


	static final class RecordingWorkQueue implements GenerationWorkQueue {
		private final List<UUID> sent = new CopyOnWriteArrayList<>();
		private final List<GenerationWorkLease> deleted = new CopyOnWriteArrayList<>();

		@Override public void send(UUID generationJobId) {
			sent.add(generationJobId);
		}

		@Override public Optional<GenerationWorkLease> receive() {
			return Optional.empty();
		}

		@Override public void extendVisibility(GenerationWorkLease lease, Duration extension) {
		}

		@Override public void delete(GenerationWorkLease lease) {
			deleted.add(lease);
		}
	}

	/**
	 * A real in-memory {@link ObjectStore} so media persistence, versioning and the
	 * head/readBounded/claim round-trip are exercised rather than mocked away. Only S3 and SQS
	 * are replaced; the object semantics this slice depends on stay product behaviour.
	 */
	static final class InMemoryObjectStore implements ObjectStore {
		private final Map<String, byte[]> content = new LinkedHashMap<>();
		private final Map<String, ObjectMetadata> metadata = new LinkedHashMap<>();

		private static String id(StorageDomain boundary, String key) {
			return boundary + "|" + key;
		}

		private static String id(ObjectRef ref) {
			return id(ref.boundary(), ref.objectKey());
		}

		void put(StorageDomain boundary, String key, byte[] bytes) {
			content.put(id(boundary, key), bytes);
			metadata.put(id(boundary, key), new ObjectMetadata(bytes.length, "image/png", "v1-" + key.hashCode()));
		}

		byte[] bytes(StorageDomain boundary, String key) {
			return content.get(id(boundary, key));
		}

		void clear() {
			content.clear();
			metadata.clear();
		}

		@Override public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration) {
			throw new UnsupportedOperationException("presignStagingPut");
		}

		@Override public ObjectMetadata head(ObjectRef object) {
			ObjectMetadata found = metadata.get(id(object));
			if (found == null) {
				throw new ObjectStoreExceptions.StagedObjectMissingException("missing " + object.objectKey());
			}
			return found;
		}

		@Override public byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes) {
			byte[] bytes = content.get(id(object));
			if (bytes == null) {
				throw new ObjectStoreExceptions.StagedObjectMissingException("missing " + object.objectKey());
			}
			return bytes;
		}

		@Override public void delete(ObjectRef object) {
			content.remove(id(object));
			metadata.remove(id(object));
		}

		@Override public ClaimResult claim(ClaimCommand command) {
			put(command.destinationBoundary(), command.destination().value(), new byte[0]);
			return new ClaimResult.Copied();
		}

		@Override public void purgeUserObjects(UserId userId) {
		}

		@Override public void cleanupTemp() {
		}

		@Override public PresignedAccess presignGet(ObjectRef object, Duration ttl) {
			return new PresignedAccess(object, "GET",
				URI.create("https://private.invalid/read?signature=t301"), Map.of(), Instant.now().plus(ttl));
		}

		/**
		 * The {@link GenerationMediaStore} view: the product wires one object store into both, so
		 * this is the same object semantics, not a second implementation.
		 */
		GenerationMediaStore mediaStore() {
			return new GenerationMediaStore() {
				@Override public void putBounded(StorageKey dest, InputStream source, long maxBytes, String mediaType) {
					try {
						put(StorageDomain.TEMP, dest.value(), source.readAllBytes());
					}
					catch (java.io.IOException ex) {
						throw new IllegalStateException("in-memory put", ex);
					}
				}

				@Override public ClaimResult claim(GenerationClaimCommand command) {
					// Promote: the TEMP bytes become the LOOK object under the destination key.
					byte[] temp = content.get(id(StorageDomain.TEMP, command.sourceTempKey().value()));
					if (temp == null) {
						throw new ObjectStoreExceptions.StagedObjectMissingException(
							"missing temp " + command.sourceTempKey().value());
					}
					put(StorageDomain.LOOK, command.destinationLookKey().value(), temp);
					return new ClaimResult.Copied();
				}

				@Override public void preflightProductInput(ProductInputClaimCommand command) {
				}

				@Override public ClaimResult claimProductInput(ProductInputClaimCommand command) {
					// The product input lands in the private generation-inputs boundary the
					// authorized media resolver reads back for this job.
					put(StorageDomain.TEMP, generationInputKey(command), png(CANDIDATE_SIDE, CANDIDATE_SIDE));
					return new ClaimResult.Copied();
				}

				@Override public void releaseProductInput(ProductInputClaimCommand command) {
				}
			};
		}
	}

	/** The frozen single-item input key the product media resolver reads back for the job. */
	private static String generationInputKey(ProductInputClaimCommand command) {
		return "users/" + command.ownerId().value() + "/generation-inputs/"
			+ command.generationId().value() + "/" + command.destinationMediaId().value();
	}
}
