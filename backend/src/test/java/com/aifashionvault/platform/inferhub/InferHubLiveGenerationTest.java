package com.aifashionvault.platform.inferhub;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.aifashionvault.generation.vton.GenerationStatus;
import com.aifashionvault.generation.vton.GenerationSubmission;
import com.aifashionvault.generation.vton.ItemCategory;
import com.aifashionvault.generation.vton.PrivateMediaRef;
import com.aifashionvault.generation.vton.ProviderJobId;
import com.aifashionvault.generation.vton.ProviderMediaSource;
import com.aifashionvault.generation.vton.StatusQueryResult;
import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.generation.vton.VtonMode;
import com.aifashionvault.generation.vton.VtonProductAsset;
import com.aifashionvault.generation.vton.VtonRequest;
import com.aifashionvault.platform.environment.AppEnv;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;
import com.aifashionvault.platform.objectstore.ImageMediaType;
import com.aifashionvault.platform.objectstore.LoggingTempObjectAgeAlert;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectStoreGenerationOutputStore;
import com.aifashionvault.platform.objectstore.S3ObjectStore;
import com.aifashionvault.platform.objectstore.StorageKeys;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * LIVE end-to-end generation against the real InferHub provider, at the adapter seam.
 *
 * <p>This test exists to prove the REAL provider path end to end on this machine: real
 * InferHubClient (300s read timeout), real LocalStack-backed object store, real asset
 * upload, real ~170s generation, real inline PNG decode, real persistence. It costs a
 * paid provider call, so it self-skips unless {@code VTON_IMAGE_API_KEY} is exported
 * (CI and normal runs never attempt it, and no key is ever embedded here).
 *
 * <p>The authenticated HTTP leg (POST /generations) is NOT exercised and is
 * OWNER-BLOCKED: see C:/Users/ramab/_vtonfix-ceo2/e2e-inferhub-evidence.md.
 */
@EnabledIfEnvironmentVariable(named = "VTON_IMAGE_API_KEY", matches = ".+")
class InferHubLiveGenerationTest {

    private static final Path WORKTREE_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final String TEMP_BUCKET = "ai-fashion-vault-local-generation-temp";
    private static final String LOCALSTACK = "http://127.0.0.1:4566";
    /**
     * Owner-verified latency is ~170s. The assertion below is the point of the 300s read
     * timeout: the provider must never be torn down by a sub-5-minute read limit. No
     * upper bound below 300s is asserted — only the read-timeout ceiling.
     */
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration BACKOFF = Duration.ofSeconds(10);

    @Test
    void realInferHubGenerationProducesPngAndPersistsThroughObjectStore() throws Exception {
        String apiKey = System.getenv("VTON_IMAGE_API_KEY");
        assertThat(apiKey).isNotBlank();

        // Real object store over LocalStack, built through the production wiring factory.
        VaultEnvironmentProperties properties = new VaultEnvironmentProperties(
            AppEnv.development,
            new VaultEnvironmentProperties.Db("unused", "unused", "unused"),
            new VaultEnvironmentProperties.Aws("us-east-1", LOCALSTACK, null, "test", "test"),
            new VaultEnvironmentProperties.Sqs(null),
            new VaultEnvironmentProperties.S3(
                "ai-fashion-vault-local-avatar-private",
                "ai-fashion-vault-local-vault-assets",
                "ai-fashion-vault-local-look-private",
                TEMP_BUCKET),
            new VaultEnvironmentProperties.Fashn(null),
            new VaultEnvironmentProperties.Inferhub(apiKey),
            new VaultEnvironmentProperties.Firebase("unused"),
            new VaultEnvironmentProperties.Otel("unused", null, null, null));
        S3Client s3 = S3Client.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(java.net.URI.create(LOCALSTACK))
            .forcePathStyle(true)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .build();
        S3Presigner presigner = S3Presigner.builder()
            .region(Region.US_EAST_1)
            .endpointOverride(java.net.URI.create(LOCALSTACK))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .serviceConfiguration(software.amazon.awssdk.services.s3.S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build())
            .build();
        S3ObjectStore objects = new S3ObjectStore(s3, presigner, properties.s3(), Clock.systemUTC(),
            new LoggingTempObjectAgeAlert());

        // The two real assets from the worktree root, uploaded through the real store.
        StorageKey avatarKey = uploadAsset(s3, "temp/live-e2e/person_portrait.jpg",
            WORKTREE_ROOT.resolve("person_portrait.jpg"));
        StorageKey garmentKey = uploadAsset(s3, "temp/live-e2e/myntra_shirt.jpg",
            WORKTREE_ROOT.resolve("myntra_shirt.jpg"));

        // Real adapter path: real client (300s read timeout via the production builder).
        InferHubMapper mapper = new InferHubMapper();
        InferHubClient client = new InferHubClient(
            InferHubConfig.inferHubRestClientBuilder(apiKey).build());
        InferHubVtonProvider provider = new InferHubVtonProvider(mapper, client, objects, objects);
        assertThat(provider.providerKey().value()).isEqualTo("inferhub");

        VtonRequest request = new VtonRequest(
            new PrivateMediaRef(avatarKey, OptionalLong.of(Files.size(WORKTREE_ROOT.resolve("person_portrait.jpg")))),
            List.of(new VtonProductAsset(
                new PrivateMediaRef(garmentKey, OptionalLong.of(Files.size(WORKTREE_ROOT.resolve("myntra_shirt.jpg")))),
                new ItemCategory("tops"))),
            Optional.empty(),
            new VtonMode("standard"));

        GenerationSubmission.Accepted accepted = null;
        String lastFailure = "no attempt made";
        long observedLatencyMs = -1;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            long started = System.currentTimeMillis();
            GenerationSubmission submission = provider.submit(request, new SubmissionKey(UUID.randomUUID()));
            long latencyMs = System.currentTimeMillis() - started;
            System.out.printf("[live-e2e] attempt=%d outcome=%s latencyMs=%d%n",
                attempt, submission.getClass().getSimpleName(), latencyMs);
            if (submission instanceof GenerationSubmission.Accepted a) {
                accepted = a;
                // The 300s read timeout is the hard ceiling; the Fashn 30s read teardown
                // is the comparison floor the setting exists to beat. Owner-verified
                // history says ~170s; the OBSERVED value is recorded verbatim and the
                // artifact states honestly how it compares to 30s.
                assertThat(latencyMs).as("generation latency; 300s read timeout must be the ceiling")
                    .isLessThan(300_000L);
                observedLatencyMs = latencyMs;
                break;
            }
            if (submission instanceof GenerationSubmission.Rejected rejected) {
                lastFailure = "Rejected/" + rejected.failure().safeCode();
                if (!rejected.failure().retryable()) {
                    break;
                }
            } else if (submission instanceof GenerationSubmission.Indeterminate indeterminate) {
                lastFailure = "Indeterminate/" + indeterminate.failure().safeCode();
            }
            if (attempt < MAX_ATTEMPTS) {
                Thread.sleep(BACKOFF.toMillis());
            }
        }
        assertThat(accepted)
            .as("real generation accepted after at most %d attempts (last failure: %s)", MAX_ATTEMPTS, lastFailure)
            .isNotNull();

        StatusQueryResult status = provider.getStatus(accepted.providerJobId());
        assertThat(status).isInstanceOf(StatusQueryResult.Observed.class);
        GenerationStatus observed = ((StatusQueryResult.Observed) status).status();
        assertThat(observed).isInstanceOf(GenerationStatus.Succeeded.class);
        List<com.aifashionvault.generation.vton.ProviderOutputRef> outputs =
            ((GenerationStatus.Succeeded) observed).outputs();
        assertThat(outputs).isNotEmpty();
        System.out.printf("[live-e2e] OBSERVED_GENERATION_LATENCY_MS=%d%n", observedLatencyMs);

        ProviderMediaSource source = provider.openOutput(outputs.get(0));
        byte[] image;
        try (InputStream in = source.stream()) {
            image = in.readAllBytes();
        }
        System.out.printf("[live-e2e] output mediaType=%s bytes=%d%n",
            source.mediaType(), image.length);
        assertThat(source.mediaType()).isEqualTo("image/png");
        assertThat(image.length).isGreaterThan(1024);
        // PNG magic: 89 50 4E 47 0D 0A 1A 0A
        assertThat(image[0]).isEqualTo((byte) 0x89);
        assertThat(image[1]).isEqualTo((byte) 0x50);
        assertThat(image[2]).isEqualTo((byte) 0x4E);
        assertThat(image[3]).isEqualTo((byte) 0x47);
        long width = unsigned(image, 16);
        long height = unsigned(image, 20);
        System.out.printf("[live-e2e] decoded PNG dimensions=%dx%d%n", width, height);
        assertThat(width).isBetween(1L, 20_000L);
        assertThat(height).isBetween(1L, 20_000L);

        // Persist through the REAL media/object-store path (attempt-pinned TEMP key).
        UUID jobId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        ObjectStoreGenerationOutputStore outputStore = new ObjectStoreGenerationOutputStore(
            objects, objects, new UserId(UUID.fromString("00000000-0000-0000-0000-00000000e2e1")),
            jobId, attemptId, ImageMediaType.PNG);
        PrivateMediaRef persisted = outputStore.writePrivateTemp(
            ProviderMediaSource.of(new java.io.ByteArrayInputStream(image), OptionalLong.of(image.length),
                4L * 1024 * 1024, "image/png"));
        ObjectMetadata metadata = objects.head(new com.aifashionvault.platform.objectstore.ObjectRef(
            com.aifashionvault.shared.StorageDomain.TEMP, persisted.storageKey().value()));
        System.out.printf("[live-e2e] persisted storageKey=%s contentLength=%d contentType=%s%n",
            persisted.storageKey().value(), metadata.contentLength(), metadata.contentType());
        assertThat(metadata.contentLength()).isEqualTo(image.length);
        assertThat(persisted.storageKey()).isEqualTo(
            StorageKeys.generationTempKey(new UserId(UUID.fromString("00000000-0000-0000-0000-00000000e2e1")),
                jobId, attemptId, ImageMediaType.PNG));
    }

    private static StorageKey uploadAsset(S3Client s3, String key, Path file) throws Exception {
        byte[] bytes = Files.readAllBytes(file);
        s3.putObject(PutObjectRequest.builder()
            .bucket(TEMP_BUCKET)
            .key(key)
            .contentType("image/jpeg")
            .contentLength((long) bytes.length)
            .build(), RequestBody.fromBytes(bytes));
        System.out.printf("[live-e2e] uploaded %s (%d bytes) to %s%n", file.getFileName(), bytes.length, key);
        return new StorageKey(key);
    }

    private static long unsigned(byte[] image, int offset) {
        return ((long) (image[offset] & 0xFF) << 24)
            | ((long) (image[offset + 1] & 0xFF) << 16)
            | ((long) (image[offset + 2] & 0xFF) << 8)
            | (image[offset + 3] & 0xFF);
    }
}
