package com.aifashionvault.platform.inferhub;

import java.io.ByteArrayInputStream;
import java.util.Optional;
import java.util.OptionalLong;

import com.aifashionvault.generation.vton.GenerationStatus;
import com.aifashionvault.generation.vton.GenerationSubmission;
import com.aifashionvault.generation.vton.InputTransferMethod;
import com.aifashionvault.generation.vton.PrivacyTransferPolicy;
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
import com.aifashionvault.generation.vton.UnsupportedProviderCapabilityException;
import com.aifashionvault.generation.vton.ValidatingVtonProvider;
import com.aifashionvault.generation.vton.VtonProductAsset;
import com.aifashionvault.generation.vton.VtonRequest;
import com.aifashionvault.platform.objectstore.GenerationMediaStore;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.shared.StorageKey;

public final class InferHubVtonProvider extends ValidatingVtonProvider {
    /**
     * Durable handoff prefix for synchronous provider outputs. The decoded image is written
     * into the private TEMP boundary before the submission is accepted, so a worker restart
     * or eviction between submit and openOutput cannot lose an already-paid result.
     */
    private static final String OUTPUT_KEY_PREFIX = "temp/inferhub-outputs/";

    private final InferHubMapper mapper;
    private final InferHubClient client;
    private final ObjectStore objectStore;
    private final GenerationMediaStore mediaStore;
    private final PrivacyTransferPolicy privacyPolicy;

    public InferHubVtonProvider(
        InferHubMapper mapper,
        InferHubClient client,
        ObjectStore objectStore,
        GenerationMediaStore mediaStore
    ) {
        if (mapper == null) {
            throw new IllegalArgumentException("mapper is required");
        }
        if (client == null) {
            throw new IllegalArgumentException("client is required");
        }
        if (objectStore == null) {
            throw new IllegalArgumentException("object store is required");
        }
        if (mediaStore == null) {
            throw new IllegalArgumentException("generation media store is required");
        }
        this.mapper = mapper;
        this.client = client;
        this.objectStore = objectStore;
        this.mediaStore = mediaStore;
        this.privacyPolicy = mapper.privacyPolicy();
    }

    @Override
    public ProviderKey providerKey() {
        return mapper.providerKey();
    }

    @Override
    public ProviderCapabilities capabilities() {
        return mapper.capabilities();
    }

    /**
     * InferHub is synchronous: this call performs the whole generation (~170s) and the
     * accepted submission carries a synthetic job id whose decoded image is written into the
     * private TEMP boundary for the subsequent getStatus/openOutput handoff.
     */
    @Override
    protected GenerationSubmission submitValid(VtonRequest request, SubmissionKey submissionKey) {
        Transfer avatar = transfer(request.avatar());
        if (avatar.failure() != null) {
            return new GenerationSubmission.Rejected(avatar.failure());
        }
        // Single-asset by design: multi-item Looks are sequential steps.
        VtonProductAsset product = request.productAssets().get(0);
        Transfer garment = transfer(product.media());
        if (garment.failure() != null) {
            return new GenerationSubmission.Rejected(garment.failure());
        }
        InferHubMapper.Mapped run = mapper.mapRun(client.postRun(mapper.runRequest(
            request.mode(),
            avatar.wireValue(),
            garment.wireValue(),
            product.category(),
            request.instructions())));
        if (run.submission() instanceof GenerationSubmission.Accepted accepted) {
            ProviderFailure persistFailure = persistOutput(accepted.providerJobId(), run.dataUri());
            if (persistFailure != null) {
                return new GenerationSubmission.Rejected(persistFailure);
            }
        }
        return run.submission();
    }

    @Override
    public StatusQueryResult getStatus(ProviderJobId providerJobId) {
        if (providerJobId == null) {
            throw new IllegalArgumentException("provider job id is required");
        }
        try {
            objectStore.head(outputObject(mapper.outputRefValue(providerJobId)));
            return mapper.mapStatus(providerJobId, true);
        } catch (ObjectStoreExceptions.StagedObjectMissingException ex) {
            return mapper.mapStatus(providerJobId, false);
        } catch (ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
            // A store outage is not evidence the paid output is gone: report it as retryable
            // rather than the terminal OUTPUT_MISSING a genuine miss produces.
            return new StatusQueryResult.Observed(new GenerationStatus.Failed(
                InferHubMapper.failure(ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "STORE_UNAVAILABLE"),
                Optional.empty()));
        }
    }

    @Override
    public ProviderMediaSource openOutput(ProviderOutputRef outputRef) {
        if (outputRef == null) {
            throw new IllegalArgumentException("provider output reference is required");
        }
        ObjectRef object = outputObject(mapper.outputRefValue(outputRef));
        ObjectMetadata metadata;
        try {
            metadata = objectStore.head(object);
        } catch (ObjectStoreExceptions.StagedObjectMissingException ex) {
            throw new InferHubProviderException(InferHubMapper.failure(
                ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MISSING"));
        } catch (ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
            throw new InferHubProviderException(InferHubMapper.failure(
                ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "STORE_UNAVAILABLE"));
        }
        String mediaType = mapper.normalizeImageMediaType(metadata.contentType());
        if (mediaType == null) {
            throw new InferHubProviderException(InferHubMapper.failure(
                ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_NOT_IMAGE"));
        }
        byte[] bytes;
        try {
            bytes = objectStore.readBounded(object, metadata.version(), intBound(InferHubMapper.MAX_OUTPUT_BYTES));
        } catch (ObjectStoreExceptions.StagedObjectMissingException ex) {
            throw new InferHubProviderException(InferHubMapper.failure(
                ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MISSING"));
        } catch (ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
            throw new InferHubProviderException(InferHubMapper.failure(
                ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "STORE_UNAVAILABLE"));
        }
        if (bytes.length < 1 || bytes.length > InferHubMapper.MAX_OUTPUT_BYTES) {
            throw new InferHubProviderException(InferHubMapper.failure(
                ProviderFailureCategory.PROTOCOL_VIOLATION, false, "OUTPUT_MALFORMED"));
        }
        return ProviderMediaSource.of(
            new ByteArrayInputStream(bytes),
            OptionalLong.of(bytes.length),
            InferHubMapper.MAX_OUTPUT_BYTES,
            mediaType);
    }

    /**
     * Writes the synchronous result into the private TEMP boundary before the submission is
     * accepted. A store failure is classified and returned so the caller rejects honestly
     * instead of handing back an output reference that no restart could resolve.
     */
    private ProviderFailure persistOutput(ProviderJobId providerJobId, String dataUri) {
        InferHubMapper.DecodedImage image;
        try {
            image = mapper.decodeDataUri(dataUri);
        } catch (InferHubProviderException ex) {
            return ex.failure();
        }
        try {
            mediaStore.putBounded(
                outputKey(mapper.outputRefValue(providerJobId)),
                new ByteArrayInputStream(image.bytes()),
                InferHubMapper.MAX_OUTPUT_BYTES,
                image.mediaType());
            return null;
        } catch (ObjectStoreExceptions.StagedObjectMissingException | ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
            // Either store fault means the paid output is not durably persisted yet: reject
            // retryably rather than accept a reference no restart could resolve.
            return InferHubMapper.failure(
                ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "STORE_UNAVAILABLE");
        }
    }

    private static ObjectRef outputObject(String outputRefValue) {
        return new ObjectRef(StorageDomain.TEMP, outputKey(outputRefValue).value());
    }

    private static StorageKey outputKey(String outputRefValue) {
        return new StorageKey(OUTPUT_KEY_PREFIX + outputRefValue);
    }

    @Override
    public String toString() {
        return "InferHubVtonProvider[providerKey=inferhub]";
    }

    private Transfer transfer(PrivateMediaRef media) {
        Optional<ObjectRef> resolved = mapper.objectRef(media.storageKey());
        if (resolved.isEmpty()) {
            return Transfer.failed(InferHubMapper.failure(ProviderFailureCategory.INVALID_REQUEST, false, "MEDIA_UNRESOLVABLE"));
        }
        ObjectRef object = resolved.get();
        ObjectMetadata metadata;
        try {
            metadata = objectStore.head(object);
        } catch (ObjectStoreExceptions.StagedObjectMissingException ex) {
            return Transfer.failed(InferHubMapper.failure(ProviderFailureCategory.INVALID_REQUEST, false, "MEDIA_MISSING"));
        } catch (ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
            return Transfer.failed(InferHubMapper.failure(ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "STORE_UNAVAILABLE"));
        }
        if (metadata.contentLength() < 1 || metadata.contentLength() > InferHubMapper.MAX_ASSET_BYTES) {
            return Transfer.failed(InferHubMapper.failure(ProviderFailureCategory.INVALID_REQUEST, false, "INPUT_SIZE_EXCEEDED"));
        }
        String mediaType = mapper.normalizeImageMediaType(metadata.contentType());
        if (mediaType == null) {
            return Transfer.failed(InferHubMapper.failure(ProviderFailureCategory.INVALID_REQUEST, false, "MEDIA_TYPE"));
        }
        long knownBytes = metadata.contentLength();
        PrivacyTransferPolicy.TransferDecision decision;
        try {
            decision = privacyPolicy.chooseInputTransfer(
                capabilities().inputTransfers(),
                knownBytes,
                InferHubMapper.SIGNED_READ_TTL);
        } catch (UnsupportedProviderCapabilityException ex) {
            return Transfer.failed(InferHubMapper.failure(ProviderFailureCategory.UNSUPPORTED_CAPABILITY, false, "TRANSFER_UNSUPPORTED"));
        }
        // Inline-only wire contract: capabilities expose BOUNDED_INLINE_DATA alone, so the
        // privacy decision can only land here or throw above.
        if (decision.method() != InputTransferMethod.BOUNDED_INLINE_DATA) {
            return Transfer.failed(InferHubMapper.failure(ProviderFailureCategory.UNSUPPORTED_CAPABILITY, false, "TRANSFER_UNSUPPORTED"));
        }
        byte[] bytes;
        try {
            bytes = objectStore.readBounded(object, metadata.version(), intBound(InferHubMapper.MAX_INLINE_BYTES));
        } catch (ObjectStoreExceptions.StagedObjectMissingException ex) {
            return Transfer.failed(InferHubMapper.failure(ProviderFailureCategory.INVALID_REQUEST, false, "MEDIA_MISSING"));
        } catch (ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
            return Transfer.failed(InferHubMapper.failure(ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "STORE_UNAVAILABLE"));
        }
        if (bytes.length < 1 || bytes.length > InferHubMapper.MAX_INLINE_BYTES) {
            return Transfer.failed(InferHubMapper.failure(ProviderFailureCategory.INVALID_REQUEST, false, "INPUT_SIZE_EXCEEDED"));
        }
        return Transfer.ok(mapper.inlineDataUri(mediaType, bytes));
    }

    private static int intBound(long value) {
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    private record Transfer(String wireValue, ProviderFailure failure) {
        private static Transfer ok(String wireValue) {
            return new Transfer(wireValue, null);
        }

        private static Transfer failed(ProviderFailure failure) {
            return new Transfer(null, failure);
        }
    }
}
