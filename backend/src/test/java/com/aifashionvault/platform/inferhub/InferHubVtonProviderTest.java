package com.aifashionvault.platform.inferhub;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.AbstractClientHttpRequest;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import com.aifashionvault.generation.vton.GenerationStatus;
import com.aifashionvault.generation.vton.GenerationSubmission;
import com.aifashionvault.generation.vton.ItemCategory;
import com.aifashionvault.generation.vton.PrivateMediaRef;
import com.aifashionvault.generation.vton.ProviderFailureCategory;
import com.aifashionvault.generation.vton.ProviderMediaSource;
import com.aifashionvault.generation.vton.StatusQueryResult;
import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.generation.vton.VtonMode;
import com.aifashionvault.generation.vton.VtonProductAsset;
import com.aifashionvault.generation.vton.VtonRequest;
import com.aifashionvault.platform.objectstore.ClaimCommand;
import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.GenerationMediaStore;
import com.aifashionvault.platform.objectstore.MediaDeclaration;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.platform.objectstore.UploadCapability;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end unit pass over the InferHub provider with the HTTP seam stubbed:
 * submit serializes the multimodal request, the synchronous response is decoded,
 * the output is persisted to the private TEMP boundary for getStatus/openOutput,
 * and provider errors surface as classified failures. No network, no key material.
 */
class InferHubVtonProviderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    private static final String AVATAR_URI = "data:image/png;base64,"
        + Base64.getEncoder().encodeToString(PNG);

    private static final String AVATAR_KEY = "temp/11111111-1111-1111-1111-111111111111/uploads/avatar";
    private static final String GARMENT_KEY = "temp/11111111-1111-1111-1111-111111111111/uploads/product";

    private static final VtonMode STANDARD = new VtonMode("standard");
    private static final ItemCategory CLOTHING = new ItemCategory("clothing");

    /** Minimal ObjectStore: an in-memory map backing head/readBounded; rest forbidden. */
    private static final class LocalStore implements ObjectStore, GenerationMediaStore {
        private final Map<String, byte[]> objects = new HashMap<>();
        private final Map<String, String> contentTypes = new HashMap<>();

        void put(String key, byte[] bytes, String contentType) {
            objects.put(key, bytes);
            contentTypes.put(key, contentType);
        }

        @Override
        public void putBounded(StorageKey dest, InputStream source, long maxBytes, String mediaType) {
            try {
                objects.put(dest.value(), source.readAllBytes());
                contentTypes.put(dest.value(), mediaType);
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
        }

        @Override
        public ClaimResult claim(com.aifashionvault.platform.objectstore.GenerationClaimCommand command) {
            throw new AssertionError("claim must not be called");
        }

        @Override
        public void preflightProductInput(com.aifashionvault.platform.objectstore.ProductInputClaimCommand command) {
            throw new AssertionError("preflightProductInput must not be called");
        }

        @Override
        public ClaimResult claimProductInput(com.aifashionvault.platform.objectstore.ProductInputClaimCommand command) {
            throw new AssertionError("claimProductInput must not be called");
        }

        @Override
        public void releaseProductInput(com.aifashionvault.platform.objectstore.ProductInputClaimCommand command) {
            throw new AssertionError("releaseProductInput must not be called");
        }

        @Override
        public ObjectMetadata head(ObjectRef object) {
            byte[] bytes = objects.get(object.objectKey());
            if (bytes == null) {
                throw new ObjectStoreExceptions.StagedObjectMissingException("missing object");
            }
            return new ObjectMetadata(bytes.length, contentTypes.get(object.objectKey()), "\"v1\"");
        }

        @Override
        public byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes) {
            byte[] bytes = objects.get(object.objectKey());
            if (bytes == null) {
                throw new ObjectStoreExceptions.StagedObjectMissingException("missing object");
            }
            return bytes;
        }

        @Override
        public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration) {
            throw new AssertionError("presignStagingPut must not be called");
        }

        @Override
        public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration, com.aifashionvault.platform.objectstore.UploadPurpose purpose) {
            throw new AssertionError("presignStagingPut must not be called");
        }

        @Override
        public void delete(ObjectRef object) {
            throw new AssertionError("delete must not be called");
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
    }

    private static final class StubFactory implements ClientHttpRequestFactory {
        private final ClientHttpResponse response;
        byte[] sentBody;

        StubFactory(ClientHttpResponse response) {
            this.response = response;
        }

        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod method) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            return new AbstractClientHttpRequest() {
                @Override
                public HttpMethod getMethod() {
                    return method;
                }

                @Override
                public URI getURI() {
                    return uri;
                }

                @Override
                protected OutputStream getBodyInternal(HttpHeaders headers) {
                    return out;
                }

                @Override
                protected ClientHttpResponse executeInternal(HttpHeaders headers) {
                    sentBody = out.toByteArray();
                    return response;
                }
            };
        }
    }

    private record Harness(InferHubVtonProvider provider, StubFactory factory, LocalStore store) {
    }

    private static Harness harness(int status, String responseJson) {
        LocalStore store = new LocalStore();
        store.put(AVATAR_KEY, PNG, "image/png");
        store.put(GARMENT_KEY, PNG, "image/png");
        StubFactory factory = new StubFactory(
            new MockClientHttpResponse(responseJson.getBytes(StandardCharsets.UTF_8), status));
        return new Harness(provider(store, factory), factory, store);
    }

    /**
     * A fresh provider instance over a shared durable store. The HTTP stub response body is
     * single-read, so every submission needs its own factory; the store is deliberately shared
     * to model the durability the handoff must provide across provider instances.
     */
    private static InferHubVtonProvider providerOver(LocalStore store, int status, String responseJson) {
        return provider(store, new StubFactory(
            new MockClientHttpResponse(responseJson.getBytes(StandardCharsets.UTF_8), status)));
    }

    private static InferHubVtonProvider provider(LocalStore store, StubFactory factory) {
        RestClient restClient = RestClient.builder()
            .baseUrl("https://api.inferhub.test")
            .requestFactory(factory)
            .build();
        return new InferHubVtonProvider(
            new InferHubMapper(), new InferHubClient(restClient), store, store);
    }

    private static VtonRequest request(LocalStore store) {
        if (store == null) {
            store = new LocalStore();
            store.put(AVATAR_KEY, PNG, "image/png");
            store.put(GARMENT_KEY, PNG, "image/png");
        }
        return new VtonRequest(
            new PrivateMediaRef(new StorageKey(AVATAR_KEY), OptionalLong.of(PNG.length)),
            List.of(new VtonProductAsset(
                new PrivateMediaRef(new StorageKey(GARMENT_KEY), OptionalLong.of(PNG.length)), CLOTHING)),
            Optional.empty(),
            STANDARD);
    }

    private static SubmissionKey key() {
        return new SubmissionKey(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    }

    private static String successBody(String dataUri) {
        return "{\"choices\":[{\"message\":{\"content\":["
            + "{\"type\":\"text\",\"text\":\"done\"},"
            + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"" + dataUri + "\"}}"
            + "]}}]}";
    }

    @Test
    void submitSerializesMultimodalRequestAndAcceptsSynchronousResult() throws Exception {
        LocalStore store = new LocalStore();
        store.put(AVATAR_KEY, PNG, "image/png");
        store.put(GARMENT_KEY, PNG, "image/png");
        Harness harness = harness(200, successBody(AVATAR_URI));

        GenerationSubmission submission = harness.provider().submit(request(store), key());

        assertThat(submission).isInstanceOf(GenerationSubmission.Accepted.class);

        JsonNode sent = MAPPER.readTree(harness.factory().sentBody);
        assertThat(sent.get("model").asText()).isEqualTo("cb/gemini-3.1-flash-image");
        JsonNode content = sent.get("messages").get(0).get("content");
        assertThat(content.isArray()).isTrue();
        assertThat(content.size()).isEqualTo(3);
        assertThat(content.get(0).get("type").asText()).isEqualTo("text");
        assertThat(content.get(1).get("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).get("image_url").get("url").asText()).isEqualTo(AVATAR_URI);
        assertThat(content.get(2).get("type").asText()).isEqualTo("image_url");
        assertThat(content.get(2).get("image_url").get("url").asText()).contains("data:image/png;base64,");
    }

    @Test
    void acceptedOutputIsFetchableThroughGetStatusAndOpenOutput() throws Exception {
        Harness harness = harness(200, successBody(AVATAR_URI));

        GenerationSubmission.Accepted accepted =
            (GenerationSubmission.Accepted) harness.provider().submit(request(null), key());

        StatusQueryResult.Observed status =
            (StatusQueryResult.Observed) harness.provider().getStatus(accepted.providerJobId());
        GenerationStatus.Succeeded succeeded = (GenerationStatus.Succeeded) status.status();
        assertThat(succeeded.outputs()).hasSize(1);

        try (ProviderMediaSource source = harness.provider().openOutput(succeeded.outputs().get(0))) {
            assertThat(source.mediaType()).isEqualTo("image/png");
            ByteArrayOutputStream image = new ByteArrayOutputStream();
            try (InputStream in = source.stream()) {
                in.transferTo(image);
            }
            assertThat(image.toByteArray()).isEqualTo(PNG);
        }
    }

    /**
     * The previous handoff kept at most eight outputs in a process-local map, so the ninth
     * accepted submission evicted the first and openOutput failed with OUTPUT_MISSING. The
     * durable TEMP handoff must retain every accepted output regardless of count, and a fresh
     * provider instance (a restart) must still resolve outputs accepted by an earlier one.
     */
    @Test
    void acceptedOutputsBeyondTheOldCacheCapacityRemainFetchable() throws Exception {
        LocalStore store = new LocalStore();
        store.put(AVATAR_KEY, PNG, "image/png");
        store.put(GARMENT_KEY, PNG, "image/png");

        List<GenerationSubmission.Accepted> accepted = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            InferHubVtonProvider writer = providerOver(store, 200, successBody(AVATAR_URI));
            accepted.add((GenerationSubmission.Accepted) writer.submit(request(store), key()));
        }

        // A brand-new instance over the same store models the backend restart the old
        // in-memory map could not survive.
        InferHubVtonProvider restarted = providerOver(store, 200, successBody(AVATAR_URI));
        for (GenerationSubmission.Accepted submission : accepted) {
            StatusQueryResult.Observed status =
                (StatusQueryResult.Observed) restarted.getStatus(submission.providerJobId());
            GenerationStatus.Succeeded succeeded = (GenerationStatus.Succeeded) status.status();
            try (ProviderMediaSource source = restarted.openOutput(succeeded.outputs().get(0))) {
                assertThat(source.mediaType()).isEqualTo("image/png");
                assertThat(source.stream().readAllBytes()).isEqualTo(PNG);
            }
        }
    }

    @Test
    void malformedResponseIsRejectedAsClassifiedFailureAndNothingIsCached() {
        Harness harness = harness(200, "{\"choices\":[]}");

        GenerationSubmission submission = harness.provider().submit(request(null), key());

        GenerationSubmission.Rejected rejected = (GenerationSubmission.Rejected) submission;
        assertThat(rejected.failure().category()).isEqualTo(ProviderFailureCategory.PROTOCOL_VIOLATION);
        assertThat(rejected.failure().safeCode()).isEqualTo("OUTPUT_MISSING");

        var jobId = new com.aifashionvault.generation.vton.ProviderJobId("inferhub-probe");
        StatusQueryResult.Observed status =
            (StatusQueryResult.Observed) harness.provider().getStatus(jobId);
        GenerationStatus.Failed failed = (GenerationStatus.Failed) status.status();
        assertThat(failed.failure().safeCode()).isEqualTo("OUTPUT_MISSING");
    }

    @Test
    void upstream502IsARetryableRejection() {
        Harness harness = harness(502, "{\"error\":\"bad gateway\"}");

        GenerationSubmission submission = harness.provider().submit(request(null), key());

        GenerationSubmission.Rejected rejected = (GenerationSubmission.Rejected) submission;
        assertThat(rejected.failure().category()).isEqualTo(ProviderFailureCategory.TEMPORARY_UNAVAILABLE);
        assertThat(rejected.failure().retryable()).isTrue();
    }

    @Test
    void authFailureIsAPermanentRejection() {
        Harness harness = harness(401, "{\"error\":\"unauthorized\"}");

        GenerationSubmission submission = harness.provider().submit(request(null), key());

        GenerationSubmission.Rejected rejected = (GenerationSubmission.Rejected) submission;
        assertThat(rejected.failure().category()).isEqualTo(ProviderFailureCategory.AUTH_CONFIGURATION);
        assertThat(rejected.failure().retryable()).isFalse();
    }

    @Test
    void missingMediaInStoreIsRejectedAsInvalidRequest() {
        Harness harness = harness(200, successBody(AVATAR_URI));
        VtonRequest request = new VtonRequest(
            new PrivateMediaRef(new StorageKey("temp/11111111-1111-1111-1111-111111111111/uploads/gone"),
                OptionalLong.of(PNG.length)),
            List.of(new VtonProductAsset(
                new PrivateMediaRef(new StorageKey(GARMENT_KEY), OptionalLong.of(PNG.length)), CLOTHING)),
            Optional.empty(),
            STANDARD);

        GenerationSubmission submission = harness.provider().submit(request, key());

        GenerationSubmission.Rejected rejected = (GenerationSubmission.Rejected) submission;
        assertThat(rejected.failure().category()).isEqualTo(ProviderFailureCategory.INVALID_REQUEST);
        assertThat(rejected.failure().safeCode()).isEqualTo("MEDIA_MISSING");
    }
}
