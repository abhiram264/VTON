package com.aifashionvault.platform.fashn;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
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
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.platform.objectstore.PresignedAccess;

public final class FashnVtonProvider extends ValidatingVtonProvider {
    private final FashnMapper mapper;
    private final FashnClient client;
    private final ObjectStore objectStore;
    private final PrivacyTransferPolicy privacyPolicy;

    public FashnVtonProvider(FashnMapper mapper, FashnClient client, ObjectStore objectStore) {
        if (mapper == null) {
            throw new IllegalArgumentException("mapper is required");
        }
        if (client == null) {
            throw new IllegalArgumentException("client is required");
        }
        if (objectStore == null) {
            throw new IllegalArgumentException("object store is required");
        }
        this.mapper = mapper;
        this.client = client;
        this.objectStore = objectStore;
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

    @Override
    protected GenerationSubmission submitValid(VtonRequest request, SubmissionKey submissionKey) {
        Transfer avatar = transfer(request.avatar());
        if (avatar.failure() != null) {
            return new GenerationSubmission.Rejected(avatar.failure());
        }
        VtonProductAsset product = request.productAssets().get(0);
        Transfer garment = transfer(product.media());
        if (garment.failure() != null) {
            return new GenerationSubmission.Rejected(garment.failure());
        }
        return mapper.mapRun(client.postRun(mapper.runRequest(
            request.mode(),
            avatar.wireValue(),
            garment.wireValue(),
            product.category(),
            request.instructions())));
    }

    @Override
    public StatusQueryResult getStatus(ProviderJobId providerJobId) {
        if (providerJobId == null) {
            throw new IllegalArgumentException("provider job id is required");
        }
        String predictionId = mapper.predictionId(providerJobId);
        return mapper.mapStatus(predictionId, client.getStatus(predictionId));
    }

    @Override
    public ProviderMediaSource openOutput(ProviderOutputRef outputRef) {
        if (outputRef == null) {
            throw new IllegalArgumentException("provider output reference is required");
        }
        String predictionId = mapper.predictionId(outputRef);
        FashnMapper.DecodedImage image = mapper.openOutputImage(predictionId, client.getStatus(predictionId));
        return ProviderMediaSource.of(
            new ByteArrayInputStream(image.bytes()),
            OptionalLong.of(image.bytes().length),
            FashnMapper.MAX_OUTPUT_BYTES,
            image.mediaType());
    }

    @Override
    public String toString() {
        return "FashnVtonProvider[providerKey=fashn]";
    }

    private Transfer transfer(PrivateMediaRef media) {
        Optional<ObjectRef> resolved = mapper.objectRef(media.storageKey());
        if (resolved.isEmpty()) {
            return Transfer.failed(FashnMapper.failure(ProviderFailureCategory.INVALID_REQUEST, false, "MEDIA_UNRESOLVABLE"));
        }
        ObjectRef object = resolved.get();
        ObjectMetadata metadata;
        try {
            metadata = objectStore.head(object);
        } catch (ObjectStoreExceptions.StagedObjectMissingException ex) {
            return Transfer.failed(FashnMapper.failure(ProviderFailureCategory.INVALID_REQUEST, false, "MEDIA_MISSING"));
        } catch (ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
            return Transfer.failed(FashnMapper.failure(ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "STORE_UNAVAILABLE"));
        }
        if (metadata.contentLength() < 1 || metadata.contentLength() > FashnMapper.MAX_ASSET_BYTES) {
            return Transfer.failed(FashnMapper.failure(ProviderFailureCategory.INVALID_REQUEST, false, "INPUT_SIZE_EXCEEDED"));
        }
        String mediaType = mapper.normalizeImageMediaType(metadata.contentType());
        if (mediaType == null) {
            return Transfer.failed(FashnMapper.failure(ProviderFailureCategory.INVALID_REQUEST, false, "MEDIA_TYPE"));
        }
        long knownBytes = metadata.contentLength();
        PrivacyTransferPolicy.TransferDecision decision;
        try {
            decision = privacyPolicy.chooseInputTransfer(
                capabilities().inputTransfers(),
                knownBytes,
                FashnMapper.SIGNED_READ_TTL);
        } catch (UnsupportedProviderCapabilityException ex) {
            return Transfer.failed(FashnMapper.failure(ProviderFailureCategory.UNSUPPORTED_CAPABILITY, false, "TRANSFER_UNSUPPORTED"));
        }
        if (decision.method() == InputTransferMethod.BOUNDED_INLINE_DATA) {
            byte[] bytes;
            try {
                bytes = objectStore.readBounded(object, metadata.version(), intBound(FashnMapper.MAX_INLINE_BYTES));
            } catch (ObjectStoreExceptions.StagedObjectMissingException ex) {
                return Transfer.failed(FashnMapper.failure(ProviderFailureCategory.INVALID_REQUEST, false, "MEDIA_MISSING"));
            } catch (ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
                return Transfer.failed(FashnMapper.failure(ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "STORE_UNAVAILABLE"));
            }
            if (bytes.length < 1 || bytes.length > FashnMapper.MAX_INLINE_BYTES) {
                return Transfer.failed(FashnMapper.failure(ProviderFailureCategory.INVALID_REQUEST, false, "INPUT_SIZE_EXCEEDED"));
            }
            return Transfer.ok(mapper.inlineDataUri(mediaType, bytes));
        }
        Duration ttl = decision.signedReadTtl().orElse(FashnMapper.SIGNED_READ_TTL);
        PresignedAccess access;
        try {
            access = objectStore.presignGet(object, ttl);
        } catch (ObjectStoreExceptions.StagedObjectMissingException ex) {
            return Transfer.failed(FashnMapper.failure(ProviderFailureCategory.INVALID_REQUEST, false, "MEDIA_MISSING"));
        } catch (ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
            return Transfer.failed(FashnMapper.failure(ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "STORE_UNAVAILABLE"));
        }
        return Transfer.ok(access.uri().toString());
    }

    private static int intBound(long value) {
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    private record Transfer(String wireValue, ProviderFailure failure) {
        static Transfer ok(String wireValue) {
            return new Transfer(wireValue, null);
        }

        static Transfer failed(ProviderFailure failure) {
            return new Transfer(null, failure);
        }

        @Override
        public String toString() {
            return failure == null ? "Transfer[ok]" : "Transfer[" + failure.safeCode() + "]";
        }
    }
}
