package com.aifashionvault.platform.fashn;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.aifashionvault.generation.vton.ItemCategory;
import com.aifashionvault.generation.vton.PrivateMediaRef;
import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.generation.vton.VtonMode;
import com.aifashionvault.generation.vton.VtonProductAsset;
import com.aifashionvault.generation.vton.VtonRequest;
import com.aifashionvault.platform.objectstore.ClaimCommand;
import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.MediaDeclaration;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.platform.objectstore.UploadCapability;
import com.aifashionvault.platform.objectstore.UploadPurpose;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

final class FashnTestSupport {
    static final String API_KEY = "test-fashn-key";
    static final String PREDICTION_ID = "123a87r9-4129-4bb3-be18-9c9fb5bd7fc1-u1";
    static final String RUN_URL = "https://api.fashn.ai/v1/run";
    static final String STATUS_URL = "https://api.fashn.ai/v1/status/" + PREDICTION_ID;
    static final String AVATAR_KEY = "temp/11111111-1111-1111-1111-111111111111/uploads/avatar";
    static final String PRODUCT_KEY = "temp/11111111-1111-1111-1111-111111111111/uploads/product";
    static final String SIGNED_URI = "https://objects.test/ephemeral/media?X-Amz-Algorithm=AWS4-HMAC-SHA256&X-Amz-Signature=test";
    static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
    static final String PNG_DATA_URI = "data:image/png;base64," + Base64.getEncoder().encodeToString(PNG);
    static final VtonMode FAST = new VtonMode("fast_preview");
    static final VtonMode STANDARD = new VtonMode("standard");
    static final VtonMode HD = new VtonMode("hd");
    static final ItemCategory CLOTHING = new ItemCategory("clothing");

    private FashnTestSupport() {
    }

    static Harness harness() {
        return harness(FashnConfig.fashnRestClientBuilder(API_KEY));
    }

    static Harness harness(RestClient.Builder builder) {
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        FakeObjectStore store = new FakeObjectStore();
        store.put(AVATAR_KEY, PNG, "image/png");
        store.put(PRODUCT_KEY, PNG, "image/png");
        FashnMapper mapper = new FashnMapper();
        FashnVtonProvider provider = new FashnVtonProvider(mapper, new FashnClient(builder.build()), store);
        return new Harness(server, store, mapper, provider);
    }

    static VtonRequest request(VtonMode mode) {
        return request(mode, CLOTHING, Optional.empty(), OptionalLong.of(PNG.length), AVATAR_KEY, PRODUCT_KEY);
    }

    static VtonRequest request(VtonMode mode, ItemCategory category, Optional<String> instructions) {
        return request(mode, category, instructions, OptionalLong.of(PNG.length), AVATAR_KEY, PRODUCT_KEY);
    }

    static VtonRequest request(
        VtonMode mode,
        ItemCategory category,
        Optional<String> instructions,
        OptionalLong size,
        String avatarKey,
        String productKey
    ) {
        return new VtonRequest(
            new PrivateMediaRef(new StorageKey(avatarKey), size),
            List.of(new VtonProductAsset(new PrivateMediaRef(new StorageKey(productKey), size), category)),
            instructions,
            mode);
    }

    static SubmissionKey key() {
        return new SubmissionKey(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
    }

    static String runSuccess() {
        return "{\"id\":\"" + PREDICTION_ID + "\",\"error\":null}";
    }

    static String status(String token, String outputJson) {
        return "{\"id\":\"" + PREDICTION_ID + "\",\"status\":\"" + token + "\",\"output\":" + outputJson + ",\"error\":null}";
    }

    static String completedImage() {
        return status("completed", "[\"" + PNG_DATA_URI + "\"]");
    }

    static String apiError(String name, String message) {
        return "{\"error\":\"" + name + "\",\"message\":\"" + message + "\"}";
    }

    static String runtimeFailed(String name, String message) {
        return "{\"id\":\"" + PREDICTION_ID + "\",\"status\":\"failed\",\"error\":{\"name\":\"" + name + "\",\"message\":\"" + message + "\"}}";
    }

    static ClientHttpRequestFactory timeoutFactory() {
        return new ClientHttpRequestFactory() {
            @Override
            public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) {
                return new org.springframework.http.client.AbstractClientHttpRequest() {
                    @Override
                    public HttpMethod getMethod() {
                        return httpMethod;
                    }

                    @Override
                    public URI getURI() {
                        return uri;
                    }

                    @Override
                    protected java.io.OutputStream getBodyInternal(org.springframework.http.HttpHeaders headers) {
                        return new java.io.ByteArrayOutputStream();
                    }

                    @Override
                    protected ClientHttpResponse executeInternal(org.springframework.http.HttpHeaders headers) throws java.io.IOException {
                        throw new SocketTimeoutException("Read timed out");
                    }
                };
            }
        };
    }

    record Harness(MockRestServiceServer server, FakeObjectStore store, FashnMapper mapper, FashnVtonProvider provider) {
    }

    static final class FakeObjectStore implements ObjectStore {
        private final Map<String, Stored> objects = new HashMap<>();
        private final URI signedUri = URI.create(SIGNED_URI);
        private final List<ObjectRef> headRefs = new ArrayList<>();
        private final List<BoundedRead> boundedReads = new ArrayList<>();
        private final List<ObjectRef> presignRefs = new ArrayList<>();
        int headCount;
        int readCount;
        int presignCount;
        Duration lastTtl;
        RuntimeException headFailure;

        void put(String key, byte[] bytes, String contentType) {
            objects.put(key, new Stored(bytes, contentType, "\"v1\"", bytes.length));
        }

        void putSized(String key, byte[] bytes, String contentType, long reportedLength) {
            objects.put(key, new Stored(bytes, contentType, "\"v1\"", reportedLength));
        }

        List<ObjectRef> headRefs() {
            return List.copyOf(headRefs);
        }

        List<BoundedRead> boundedReads() {
            return List.copyOf(boundedReads);
        }

        List<ObjectRef> presignRefs() {
            return List.copyOf(presignRefs);
        }

        @Override
        public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration) {
            throw new AssertionError("presignStagingPut must not be called");
        }

        @Override
        public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration, UploadPurpose purpose) {
            throw new AssertionError("presignStagingPut must not be called");
        }

        @Override
        public ObjectMetadata head(ObjectRef object) {
            headCount++;
            headRefs.add(object);
            if (headFailure != null) {
                throw headFailure;
            }
            Stored stored = objects.get(object.objectKey());
            if (stored == null) {
                throw new ObjectStoreExceptions.StagedObjectMissingException("missing object");
            }
            return new ObjectMetadata(stored.reportedLength, stored.contentType, stored.version);
        }

        @Override
        public byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes) {
            readCount++;
            boundedReads.add(new BoundedRead(object, expectedVersion, maxBytes));
            Stored stored = objects.get(object.objectKey());
            if (stored == null) {
                throw new ObjectStoreExceptions.StagedObjectMissingException("missing object");
            }
            if (!stored.version.equals(expectedVersion)) {
                throw new IllegalArgumentException("unexpected object version");
            }
            if (maxBytes < 0 || stored.bytes.length > maxBytes) {
                throw new IllegalArgumentException("bounded read exceeds maxBytes");
            }
            return stored.bytes;
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
            presignCount++;
            presignRefs.add(object);
            lastTtl = ttl;
            if (!objects.containsKey(object.objectKey())) {
                throw new ObjectStoreExceptions.StagedObjectMissingException("missing object");
            }
            return new PresignedAccess(object, "GET", signedUri, Map.of(), Instant.parse("2026-08-25T12:15:00Z"));
        }

        record BoundedRead(ObjectRef object, String expectedVersion, int maxBytes) {
        }

        private record Stored(byte[] bytes, String contentType, String version, long reportedLength) {
        }
    }
}
