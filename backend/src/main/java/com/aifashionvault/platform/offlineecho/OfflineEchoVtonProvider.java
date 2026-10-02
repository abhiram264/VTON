package com.aifashionvault.platform.offlineecho;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import com.aifashionvault.generation.vton.GenerationStatus;
import com.aifashionvault.generation.vton.GenerationSubmission;
import com.aifashionvault.generation.vton.InputSizeLimit;
import com.aifashionvault.generation.vton.InputTransferMethod;
import com.aifashionvault.generation.vton.ItemCategory;
import com.aifashionvault.generation.vton.ModeCapabilities;
import com.aifashionvault.generation.vton.OutputDeliveryMethod;
import com.aifashionvault.generation.vton.PrivateMediaRef;
import com.aifashionvault.generation.vton.ProviderCapabilities;
import com.aifashionvault.generation.vton.ProviderFailure;
import com.aifashionvault.generation.vton.ProviderFailureCategory;
import com.aifashionvault.generation.vton.ProviderJobId;
import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.generation.vton.ProviderMediaSource;
import com.aifashionvault.generation.vton.ProviderOutputRef;
import com.aifashionvault.generation.vton.StatusQueryResult;
import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.generation.vton.SubmissionReplaySafety;
import com.aifashionvault.generation.vton.ValidatingVtonProvider;
import com.aifashionvault.generation.vton.VtonMode;
import com.aifashionvault.generation.vton.VtonProductAsset;
import com.aifashionvault.generation.vton.VtonRequest;
import com.aifashionvault.platform.objectstore.ImageMediaPolicy;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.shared.StorageKey;

/**
 * OFFLINE / DEVELOPMENT-ONLY echo backstop through the existing {@link
 * com.aifashionvault.generation.vton.VtonProvider} seam.
 *
 * <p>It performs NO image synthesis and makes NO external call: it reads the submitted
 * avatar object from the {@link ObjectStore} and returns those exact bytes back as the
 * "generated" output. That is enough to exercise the complete real pipeline
 * (admission -> worker -> submit -> status -> openOutput -> temp object -> quality gate
 * -> published Look) against a provider that cannot cost money or leak media.
 *
 * <p>Fail-closed by construction: the bean is only registered when
 * {@code vault.offline.provider.enabled=true} <em>and</em> {@code vault.env=development}
 * (see {@link ConditionalOnOfflineEchoProvider}). Staging and production can never
 * register it, so with no real provider key the deployment keeps the honest-unavailable
 * posture instead of silently serving echoes.
 *
 * <p>The output handoff is a deliberately ephemeral in-memory map: this is a dev fixture,
 * not the durable provider path (that is {@code InferHubVtonProvider}'s C13 concern).
 */
public final class OfflineEchoVtonProvider extends ValidatingVtonProvider {

    public static final ProviderKey PROVIDER_KEY = new ProviderKey("offline-echo");

    /** Diagnostic contract token stamped on every failure this fixture produces. */
    public static final String ERROR_MAPPING_CONTRACT_VERSION = "offline-echo-v1";

    /** One bound for both the read input and the echoed output, so an echo can never exceed its own source. */
    static final long MAX_ECHO_BYTES = 8L * 1024 * 1024;

    private static final String OUTPUT_INDEX = "0";
    private static final int MAX_CACHED_OUTPUTS = 32;

    private static final VtonMode FAST_PREVIEW = new VtonMode("fast_preview");
    private static final VtonMode STANDARD = new VtonMode("standard");
    private static final VtonMode HD = new VtonMode("hd");

    /**
     * Deliberately permissive: the backstop stands in for the real single-asset image
     * provider, so it accepts the same categories and instructions a real request carries.
     * Instructions are accepted and ignored — the output is the unchanged avatar.
     */
    private static final Set<ItemCategory> CATEGORIES = Set.of(
        new ItemCategory("clothing"),
        new ItemCategory("tops"),
        new ItemCategory("bottoms"),
        new ItemCategory("one-pieces"),
        new ItemCategory("footwear"),
        new ItemCategory("headwear"),
        new ItemCategory("jewellery"),
        new ItemCategory("jewelry"),
        new ItemCategory("bag"),
        new ItemCategory("accessory"));

    private final ObjectStore objectStore;

    private final Map<String, Echoed> outputs = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Echoed> eldest) {
            return size() > MAX_CACHED_OUTPUTS;
        }
    };

    public OfflineEchoVtonProvider(ObjectStore objectStore) {
        if (objectStore == null) {
            throw new IllegalArgumentException("object store is required");
        }
        this.objectStore = objectStore;
    }

    @Override
    public ProviderKey providerKey() {
        return PROVIDER_KEY;
    }

    @Override
    public ProviderCapabilities capabilities() {
        ModeCapabilities mode = new ModeCapabilities(CATEGORIES, 1, true, new InputSizeLimit.Known(MAX_ECHO_BYTES));
        return new ProviderCapabilities(
            Map.of(FAST_PREVIEW, mode, STANDARD, mode, HD, mode),
            Set.of(InputTransferMethod.BOUNDED_INLINE_DATA),
            Set.of(OutputDeliveryMethod.BOUNDED_INLINE_DATA),
            SubmissionReplaySafety.SAME_KEY_SAFE);
    }

    @Override
    protected GenerationSubmission submitValid(VtonRequest request, SubmissionKey submissionKey) {
        ReadMedia avatar = read(request.avatar());
        if (!avatar.ok()) {
            return new GenerationSubmission.Rejected(avatar.failure());
        }
        // Resolve every product asset too, so the offline path exercises the same
        // multi-item input resolution a real provider would before it echoes.
        for (VtonProductAsset asset : request.productAssets()) {
            ReadMedia product = read(asset.media());
            if (!product.ok()) {
                return new GenerationSubmission.Rejected(product.failure());
            }
        }
        String jobId = PROVIDER_KEY.value() + "-" + submissionKey.value();
        synchronized (outputs) {
            outputs.put(jobId, new Echoed(avatar.bytes(), avatar.mediaType()));
        }
        return new GenerationSubmission.Accepted(new ProviderJobId(jobId), Optional.empty());
    }

    @Override
    public StatusQueryResult getStatus(ProviderJobId providerJobId) {
        if (providerJobId == null) {
            throw new IllegalArgumentException("provider job id is required");
        }
        boolean cached;
        synchronized (outputs) {
            cached = outputs.containsKey(providerJobId.value());
        }
        if (cached) {
            return new StatusQueryResult.Observed(
                new GenerationStatus.Succeeded(List.of(outputRef(providerJobId)), Optional.empty()));
        }
        return new StatusQueryResult.Observed(new GenerationStatus.Failed(
            failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MISSING"), Optional.empty()));
    }

    @Override
    public ProviderMediaSource openOutput(ProviderOutputRef outputRef) {
        if (outputRef == null) {
            throw new IllegalArgumentException("provider output reference is required");
        }
        String jobId = jobIdOf(outputRef);
        Echoed echo;
        synchronized (outputs) {
            echo = outputs.get(jobId);
        }
        if (echo == null) {
            throw new OfflineEchoProviderException(
                failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MISSING"));
        }
        return ProviderMediaSource.of(
            new ByteArrayInputStream(echo.bytes()),
            OptionalLong.of(echo.bytes().length),
            MAX_ECHO_BYTES,
            echo.mediaType());
    }

    @Override
    public String toString() {
        return "OfflineEchoVtonProvider[providerKey=offline-echo]";
    }

    private ReadMedia read(PrivateMediaRef media) {
        Optional<ObjectRef> resolved = objectRef(media.storageKey());
        if (resolved.isEmpty()) {
            return ReadMedia.failed(failure(ProviderFailureCategory.INVALID_REQUEST, false, "MEDIA_UNRESOLVABLE"));
        }
        ObjectRef object = resolved.get();
        ObjectMetadata metadata;
        try {
            metadata = objectStore.head(object);
        }
        catch (ObjectStoreExceptions.StagedObjectMissingException ex) {
            return ReadMedia.failed(failure(ProviderFailureCategory.INVALID_REQUEST, false, "MEDIA_MISSING"));
        }
        catch (ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
            return ReadMedia.failed(failure(ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "STORE_UNAVAILABLE"));
        }
        if (metadata.contentLength() < 1 || metadata.contentLength() > MAX_ECHO_BYTES) {
            return ReadMedia.failed(failure(ProviderFailureCategory.INVALID_REQUEST, false, "INPUT_SIZE_EXCEEDED"));
        }
        String mediaType;
        try {
            mediaType = ImageMediaPolicy.requireDeclaration(metadata.contentType(), metadata.contentLength());
        }
        catch (IllegalArgumentException ex) {
            return ReadMedia.failed(failure(ProviderFailureCategory.INVALID_REQUEST, false, "MEDIA_TYPE"));
        }
        byte[] bytes;
        try {
            bytes = objectStore.readBounded(object, metadata.version(), (int) MAX_ECHO_BYTES);
        }
        catch (ObjectStoreExceptions.StagedObjectMissingException ex) {
            return ReadMedia.failed(failure(ProviderFailureCategory.INVALID_REQUEST, false, "MEDIA_MISSING"));
        }
        catch (ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
            return ReadMedia.failed(failure(ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "STORE_UNAVAILABLE"));
        }
        if (bytes == null || bytes.length < 1) {
            return ReadMedia.failed(failure(ProviderFailureCategory.INVALID_REQUEST, false, "MEDIA_EMPTY"));
        }
        return new ReadMedia(bytes, mediaType, null);
    }

    private static ProviderOutputRef outputRef(ProviderJobId jobId) {
        return new ProviderOutputRef(jobId.value() + ":" + OUTPUT_INDEX);
    }

    private static String jobIdOf(ProviderOutputRef outputRef) {
        String value = outputRef.value();
        int split = value.lastIndexOf(':');
        if (split <= 0 || split == value.length() - 1 || !OUTPUT_INDEX.equals(value.substring(split + 1))) {
            throw new OfflineEchoProviderException(
                failure(ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_REF"));
        }
        return value.substring(0, split);
    }

    private static Optional<ObjectRef> objectRef(StorageKey storageKey) {
        String value = storageKey.value();
        if (value.startsWith("temp/")) {
            return Optional.of(new ObjectRef(StorageDomain.TEMP, value));
        }
        if (value.contains("/avatars/")) {
            return Optional.of(new ObjectRef(StorageDomain.AVATAR, value));
        }
        if (value.contains("/vault/")) {
            return Optional.of(new ObjectRef(StorageDomain.VAULT, value));
        }
        if (value.contains("/generation-inputs/")) {
            return Optional.of(new ObjectRef(StorageDomain.TEMP, value));
        }
        if (value.contains("/looks/")) {
            return Optional.of(new ObjectRef(StorageDomain.LOOK, value));
        }
        return Optional.empty();
    }

    static ProviderFailure failure(ProviderFailureCategory category, boolean retryable, String code) {
        return new ProviderFailure(category, retryable, code, null, ERROR_MAPPING_CONTRACT_VERSION);
    }

    /** One resolved media object: the read bytes and their normalized image media type. */
    private record ReadMedia(byte[] bytes, String mediaType, ProviderFailure failure) {
        static ReadMedia failed(ProviderFailure failure) {
            return new ReadMedia(null, null, failure);
        }

        boolean ok() {
            return failure == null;
        }
    }

    /** The echoed output: the avatar bytes returned unchanged. */
    private record Echoed(byte[] bytes, String mediaType) {
    }
}
