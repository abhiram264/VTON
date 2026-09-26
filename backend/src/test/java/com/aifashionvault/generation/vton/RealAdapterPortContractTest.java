package com.aifashionvault.generation.vton;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.aifashionvault.platform.fashn.FashnClient;
import com.aifashionvault.platform.fashn.FashnMapper;
import com.aifashionvault.platform.fashn.FashnVtonProvider;
import com.aifashionvault.platform.inferhub.InferHubClient;
import com.aifashionvault.platform.inferhub.InferHubMapper;
import com.aifashionvault.platform.inferhub.InferHubVtonProvider;
import com.aifashionvault.platform.objectstore.ClaimCommand;
import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.MediaDeclaration;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.platform.objectstore.StoredObject;
import com.aifashionvault.platform.objectstore.UploadCapability;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ADR-008:36 closure — "Add AND CONTRACT-TEST a replacement adapter on the same VTON port".
 *
 * <p>{@link VtonProviderContractsTest} pins the PORT's invariants against synthetic
 * implementations. This class runs the same class of invariants against the TWO REAL
 * shipped adapters ({@link FashnVtonProvider}, {@link InferHubVtonProvider}) with only
 * their TRANSPORTS mocked, so the port cannot drift from the adapters that actually
 * serve traffic while the fake-based suite stays green.
 *
 * <p>No live network and no provider key: both clients are built here over a
 * {@link MockRestServiceServer}. Every assertion names the adapter it ran against.
 */
class RealAdapterPortContractTest {

    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final String PNG_DATA_URI = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG);
    private static final String FASHN_RUN_URL = "https://api.fashn.ai/v1/run";
    private static final String FASHN_PREDICTION = "contract-prediction-1";
    private static final String INFERHUB_URL = "https://api.inferhub.dev/v1/chat/completions";
    private static final String AVATAR_KEY = "temp/contract/avatar.png";
    private static final String PRODUCT_KEY = "temp/contract/product.png";
    private static final ItemCategory CLOTHING = new ItemCategory("clothing");
    private static final VtonMode STANDARD = new VtonMode("standard");
    /** Deliberately outside both adapters' declared mode sets. */
    private static final VtonMode UNSUPPORTED = new VtonMode("contract-unsupported-mode");

    /** One real adapter with a mocked transport; {@code server} scripts its wire responses. */
    private record Harness(String name, MockRestServiceServer server, ObjectStore store, VtonProvider provider) {
    }

    private static List<Harness> realAdapters() {
        List<Harness> harnesses = new ArrayList<>();
        harnesses.add(fashn());
        harnesses.add(inferHub());
        return harnesses;
    }

    private static Harness fashn() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.fashn.ai");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FakeStore store = new FakeStore();
        FashnVtonProvider provider = new FashnVtonProvider(new FashnMapper(), new FashnClient(builder.build()), store);
        return new Harness("fashn", server, store, provider);
    }

    private static Harness inferHub() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://api.inferhub.dev");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FakeStore store = new FakeStore();
        InferHubVtonProvider provider =
            new InferHubVtonProvider(new InferHubMapper(), new InferHubClient(builder.build()), store);
        return new Harness("inferhub", server, store, provider);
    }

    private static VtonRequest request(VtonMode mode) {
        return new VtonRequest(
            new PrivateMediaRef(new StorageKey(AVATAR_KEY), OptionalLong.of(PNG.length)),
            List.of(new VtonProductAsset(new PrivateMediaRef(new StorageKey(PRODUCT_KEY), OptionalLong.of(PNG.length)), CLOTHING)),
            Optional.empty(),
            mode);
    }

    private static SubmissionKey key() {
        return new SubmissionKey(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    }

    private static String fashnRunSuccess() {
        return "{\"id\":\"" + FASHN_PREDICTION + "\",\"error\":null}";
    }

    private static String fashnCompleted() {
        return "{\"id\":\"" + FASHN_PREDICTION + "\",\"status\":\"completed\",\"output\":[\"" + PNG_DATA_URI + "\"],\"error\":null}";
    }

    private static String inferHubSuccess() {
        return "{\"choices\":[{\"message\":{\"content\":["
            + "{\"type\":\"text\",\"text\":\"done\"},"
            + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"" + PNG_DATA_URI + "\"}}]}}]}";
    }

    /** Submits successfully and returns the accepted submission plus the harness, wire scripted. */
    private static GenerationSubmission.Accepted accepted(Harness harness) {
        if (harness.name().equals("fashn")) {
            harness.server().expect(requestTo(FASHN_RUN_URL))
                .andRespond(withSuccess(fashnRunSuccess(), MediaType.APPLICATION_JSON));
        }
        else {
            harness.server().expect(requestTo(INFERHUB_URL))
                .andRespond(withSuccess(inferHubSuccess(), MediaType.APPLICATION_JSON));
        }
        GenerationSubmission submission = harness.provider().submit(request(STANDARD), key());
        harness.server().verify();
        assertThat(submission).as("%s: a valid request is accepted through the real client", harness.name())
            .isInstanceOf(GenerationSubmission.Accepted.class);
        return (GenerationSubmission.Accepted) submission;
    }

    @Test
    void realAdaptersDeclareAStableKeyAndUsableCapabilities() {
        for (Harness harness : realAdapters()) {
            VtonProvider provider = harness.provider();
            assertThat(provider.providerKey().value()).as("%s provider key", harness.name())
                .isEqualTo(harness.name());
            ProviderCapabilities capabilities = provider.capabilities();
            assertThat(capabilities.forMode(STANDARD)).as("%s supports the standard mode", harness.name())
                .isPresent();
            assertThat(capabilities.inputTransfers()).as("%s input transfer methods", harness.name()).isNotEmpty();
            assertThat(capabilities.outputDeliveries()).as("%s output delivery methods", harness.name()).isNotEmpty();
            assertThat(capabilities.submissionReplaySafety()).as("%s replay safety", harness.name()).isNotNull();
            assertThat(capabilities.forMode(UNSUPPORTED)).as("%s does not claim an undeclared mode", harness.name())
                .isEmpty();
        }
    }

    /**
     * The port contract's central safety property: a locally-invalid request is refused by
     * {@link ValidatingVtonProvider} WITHOUT touching the provider. Zero expectations are
     * scripted, so any provider call would fail the mock server outright.
     */
    @Test
    void realAdaptersRejectLocallyWithoutAnyProviderCall() {
        for (Harness harness : realAdapters()) {
            GenerationSubmission submission = harness.provider().submit(request(UNSUPPORTED), key());
            assertThat(submission).as("%s rejects an unsupported mode locally", harness.name())
                .isInstanceOf(GenerationSubmission.Rejected.class);
            harness.server().verify();
        }
    }

    @Test
    void realAdaptersSubmitThroughTheRealClientAndExposeAnOpaqueJobId() {
        for (Harness harness : realAdapters()) {
            GenerationSubmission.Accepted accepted = accepted(harness);
            assertThat(accepted.providerJobId().value()).as("%s returns a non-blank job id", harness.name())
                .isNotBlank();
        }
    }

    @Test
    void realAdaptersExposeKnownJobStatusAsObserved() {
        for (Harness harness : realAdapters()) {
            GenerationSubmission.Accepted accepted = submittedWithStatusScripted(harness);
            StatusQueryResult status = harness.provider().getStatus(accepted.providerJobId());
            assertThat(status).as("%s reports a known job as Observed", harness.name())
                .isInstanceOf(StatusQueryResult.Observed.class);
            harness.server().verify();
        }
    }

    /** Scripts submit + one status fetch up front (see the note below on late expectations). */
    private static GenerationSubmission.Accepted submittedWithStatusScripted(Harness harness) {
        if (harness.name().equals("fashn")) {
            harness.server().expect(requestTo(FASHN_RUN_URL))
                .andRespond(withSuccess(fashnRunSuccess(), MediaType.APPLICATION_JSON));
            harness.server().expect(requestTo("https://api.fashn.ai/v1/status/" + FASHN_PREDICTION))
                .andRespond(withSuccess(fashnCompleted(), MediaType.APPLICATION_JSON));
        }
        else {
            harness.server().expect(requestTo(INFERHUB_URL))
                .andRespond(withSuccess(inferHubSuccess(), MediaType.APPLICATION_JSON));
        }
        GenerationSubmission submission = harness.provider().submit(request(STANDARD), key());
        assertThat(submission).as("%s: a valid request is accepted through the real client", harness.name())
            .isInstanceOf(GenerationSubmission.Accepted.class);
        return (GenerationSubmission.Accepted) submission;
    }

    /**
     * Scripts the WHOLE wire exchange before the first call, because MockRestServiceServer
     * refuses to add expectations once a request has been made. FASHN re-fetches the status
     * document inside openOutput, so it needs two status responses; InferHub is synchronous
     * and serves its output from the adapter's own cache with no second call.
     */
    private static GenerationSubmission.Accepted submittedWithOutputScripted(Harness harness) {
        if (harness.name().equals("fashn")) {
            harness.server().expect(requestTo(FASHN_RUN_URL))
                .andRespond(withSuccess(fashnRunSuccess(), MediaType.APPLICATION_JSON));
            harness.server().expect(requestTo("https://api.fashn.ai/v1/status/" + FASHN_PREDICTION))
                .andRespond(withSuccess(fashnCompleted(), MediaType.APPLICATION_JSON));
            harness.server().expect(requestTo("https://api.fashn.ai/v1/status/" + FASHN_PREDICTION))
                .andRespond(withSuccess(fashnCompleted(), MediaType.APPLICATION_JSON));
        }
        else {
            harness.server().expect(requestTo(INFERHUB_URL))
                .andRespond(withSuccess(inferHubSuccess(), MediaType.APPLICATION_JSON));
        }
        GenerationSubmission submission = harness.provider().submit(request(STANDARD), key());
        assertThat(submission).as("%s: a valid request is accepted through the real client", harness.name())
            .isInstanceOf(GenerationSubmission.Accepted.class);
        return (GenerationSubmission.Accepted) submission;
    }

    /**
     * The output surface the port promises: a bounded {@code image/*} stream. This is where a
     * real adapter would leak a locator or an unbounded body if it drifted.
     */
    @Test
    void realAdaptersStreamBoundedImageOutput() throws Exception {
        for (Harness harness : realAdapters()) {
            GenerationSubmission.Accepted accepted = submittedWithOutputScripted(harness);
            StatusQueryResult.Observed observed =
                (StatusQueryResult.Observed) harness.provider().getStatus(accepted.providerJobId());
            assertThat(observed.status()).as("%s reports the generated output as Succeeded", harness.name())
                .isInstanceOf(GenerationStatus.Succeeded.class);
            String outputRef = ((GenerationStatus.Succeeded) observed.status()).outputs().get(0).value();
            try (ProviderMediaSource source = harness.provider().openOutput(new ProviderOutputRef(outputRef))) {
                assertThat(source.mediaType()).as("%s output media type", harness.name())
                    .startsWith("image/");
                assertThat(source.maxBytes()).as("%s declares an output bound", harness.name()).isPositive();
                byte[] bytes = source.stream().readAllBytes();
                assertThat(bytes).as("%s streams the real image bytes", harness.name()).isEqualTo(PNG);
                if (source.contentLength().isPresent()) {
                    assertThat(source.contentLength().getAsLong()).as("%s declared length matches", harness.name())
                        .isEqualTo(bytes.length);
                }
            }
        }
    }

    /**
     * The port is a domain seam: no adapter may expose a cloud-SDK type on its public surface,
     * or the port would leak the transport it is supposed to hide.
     */
    @Test
    void realAdapterPortSurfaceExposesNoCloudSdkTypes() {
        for (Harness harness : realAdapters()) {
            Class<?> type = harness.provider().getClass();
            for (Method method : type.getMethods()) {
                assertThat(isCloudSdk(method.getReturnType())).as("%s#%s return type leaks a cloud SDK type",
                    harness.name(), method.getName()).isFalse();
                for (Class<?> parameter : method.getParameterTypes()) {
                    assertThat(isCloudSdk(parameter)).as("%s#%s parameter leaks a cloud SDK type",
                        harness.name(), method.getName()).isFalse();
                }
            }
        }
    }

    private static boolean isCloudSdk(Class<?> type) {
        String name = type.getName();
        return name.startsWith("software.amazon.") || name.startsWith("com.amazonaws.");
    }

    /** Minimal store: enough for the input-transfer path, no cloud client anywhere. */
    private static final class FakeStore implements ObjectStore {
        private final List<ObjectRef> heads = new ArrayList<>();

        @Override
        public ObjectMetadata head(ObjectRef object) {
            heads.add(object);
            return new ObjectMetadata(PNG.length, "image/png", "v1");
        }

        @Override
        public byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes) {
            return PNG;
        }

        @Override
        public PresignedAccess presignGet(ObjectRef object, Duration ttl) {
            return new PresignedAccess(
                object,
                "GET",
                java.net.URI.create("https://objects.test/ephemeral/media?X-Amz-Signature=contract"),
                java.util.Map.of(),
                java.time.Instant.parse("2026-08-26T12:05:00Z"));
        }

        @Override
        public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClaimResult claim(ClaimCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(ObjectRef object) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void purgeUserObjects(UserId userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cleanupTemp() {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<StoredObject> list(StorageDomain boundary, String prefix) {
            return List.of();
        }
    }
}
