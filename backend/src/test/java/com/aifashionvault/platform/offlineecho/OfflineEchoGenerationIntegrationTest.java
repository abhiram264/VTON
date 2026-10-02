package com.aifashionvault.platform.offlineecho;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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

import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.generation.vton.ProviderRegistry;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OFFLINE-ECHO golden path: with NO provider key at all, the offline echo backstop is the
 * selected provider and the FULL real pipeline runs end to end — {@code POST /generations}
 * admits the create, the real worker drives the real processor, the offline provider echoes
 * the submitted avatar unchanged, and a Look is published carrying those exact bytes.
 *
 * <p>This is the acceptance clause the objective calls out ("integration test runs the golden
 * path against it") and the gap the provider-seam unit tests cannot cover: the unit tests
 * prove submit/getStatus/openOutput on the provider, this proves it is REACHABLE through the
 * create endpoint with the mandated keyless posture. A key-based admission predicate would
 * answer {@code 503 GENERATION_PROVIDER_UNAVAILABLE} here — that is the cell this test pins.
 *
 * <p>No paid call and no image synthesis are possible: the offline provider has no HTTP client
 * and returns the avatar bytes it read from the object store. {@code VTON_IMAGE_API_KEY} stays
 * absent; the two real adapter keys are explicitly blanked.
 */
@SpringBootTest(properties = {
	"spring.autoconfigure.exclude=",
	// The mandated keyless offline posture: development, offline backstop explicitly enabled,
	// both real provider keys present-but-blank (not removed) so no real adapter is constructed.
	"vault.env=development",
	"vault.offline.provider.enabled=true",
	"vault.inferhub.api-key=",
	"vault.fashn.api-key="
})
@AutoConfigureMockMvc
class OfflineEchoGenerationIntegrationTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres").withStartupTimeout(Duration.ofMinutes(5));

	private static final ObjectMapper JSON = new ObjectMapper();

	/**
	 * The application-owned quality gate RETRYs below 256px on a side and the media policy
	 * rejects below 15px, so 256x256 is the smallest candidate that survives both and still
	 * reaches publication.
	 */
	private static final int CANDIDATE_SIDE = 256;

	static { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); }

	@Autowired MockMvc mockMvc;
	@Autowired JdbcTemplate jdbc;
	@Autowired ProviderRegistry registry;
	@Autowired OfflineEchoVtonProvider offlineEchoProvider;
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
	 * Selection: with both real keys blank the context still starts and the registry names the
	 * offline echo backstop as the selected provider. No real adapter bean exists, so no paid
	 * provider is even constructed.
	 */
	@Test
	void offlineEchoIsSelectedWhenNoRealKeyIsConfigured() {
		assertThat(registry.selectedProviderKey()).isEqualTo(new ProviderKey("offline-echo"));
		assertThat(registry.providerKeys()).map(ProviderKey::value).containsExactly("offline-echo");
		assertThat(registry.selectedProvider()).isSameAs(offlineEchoProvider);
	}

	/**
	 * Reachability: the create endpoint ADMITS the job (202) instead of answering 503
	 * GENERATION_PROVIDER_UNAVAILABLE, and the create path enqueues durably. This is the
	 * acceptance gap the key-based admission predicate produced.
	 */
	@Test
	void createIsAdmittedNotProviderUnavailable() throws Exception {
		Owner owner = seedOwner();
		MvcResult result = mockMvc.perform(post("/generations")
				.header(HttpHeaders.AUTHORIZATION, bearer(owner.token()))
				.header("Idempotency-Key", "offline-echo-admit")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"productUploadId\":\"" + UUID.randomUUID() + "\"}"))
			.andExpect(status().isAccepted())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
			.andReturn();

		JsonNode body = JSON.readTree(result.getResponse().getContentAsString());
		UUID jobId = UUID.fromString(body.get("id").stringValue());
		assertThat(body.get("status").stringValue()).isEqualTo("QUEUED");
		assertThat(result.getResponse().getContentAsString()).doesNotContain("GENERATION_PROVIDER_UNAVAILABLE");
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_work_outbox WHERE generation_job_id = ?", Integer.class, jobId))
			.isEqualTo(1);
		assertThat(count("generation_job")).isEqualTo(1);
	}

	/**
	 * The golden path end to end: the recorded queue hands the real job to the real processor,
	 * the offline provider echoes the submitted avatar unchanged, the output media is persisted,
	 * and the published Look carries exactly those avatar bytes.
	 */
	@Test
	void theGoldenPathPublishesTheEchoedAvatarBytes() throws Exception {
		Owner owner = seedOwner();
		byte[] avatar = objects.bytes(StorageDomain.AVATAR, avatarKey(owner.userId()));
		UUID jobId = create(owner, "offline-echo-golden");

		processor.process(new GenerationWorkLease(jobId, "offline-echo-receipt"));

		assertThat(queue.deleted).map(GenerationWorkLease::generationJobId).containsExactly(jobId);

		Map<String, Object> look = jdbc.queryForMap(
			"SELECT storage_key, provider, quality_status FROM look WHERE generation_job_id = ?", jobId);
		assertThat(look.get("provider")).isEqualTo("offline-echo");
		assertThat(look.get("quality_status")).isEqualTo("PASS");
		// The exact avatar bytes were echoed and promoted: not an empty or placeholder blob.
		assertThat(objects.bytes(StorageDomain.LOOK, (String) look.get("storage_key"))).isEqualTo(avatar);

		assertThat(jdbc.queryForObject(
			"SELECT provider FROM generation_attempt WHERE generation_job_id = ?", String.class, jobId))
			.isEqualTo("offline-echo");
		assertThat(jdbc.queryForObject(
			"SELECT technical_outcome FROM generation_attempt WHERE generation_job_id = ?", String.class, jobId))
			.isEqualTo("SUCCESS");
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
		// C06: the worker refuses to transfer the body image to a provider without an
		// active third-party AI transfer consent (TerminalReason CONSENT_REQUIRED). The
		// golden path is about provider reachability, so the owner grants it first.
		jdbc.update("INSERT INTO consent (id, user_id, purpose, version, granted_at, revoked_at) "
			+ "VALUES (?, ?, 'third_party_ai_transfer', '2026-10-03', ?, NULL)", UUID.randomUUID(), userId, now);
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

	private record Owner(String token, UUID userId) {
	}

	// --- test doubles at the INFRASTRUCTURE seams only ---------------------------
	// The offline provider is NEVER doubled: the real bean, selected through the real registry,
	// runs the real worker. Only S3/SQS/metering are replaced.

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
				URI.create("https://private.invalid/read?signature=offline-echo"), Map.of(), Instant.now().plus(ttl));
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
