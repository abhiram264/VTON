package com.aifashionvault.platform.generation;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import com.aifashionvault.generation.vton.InputTransferMethod;
import com.aifashionvault.generation.vton.PrivacyTransferPolicy;
import com.aifashionvault.generation.vton.PrivateMediaRef;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.privacy.ConsentPurpose;
import com.aifashionvault.privacy.ConsentService;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.shared.UserId;

public final class PrivacyApprovedInputTransfer {

    private final PrivacyTransferPolicy policy;
    private final ObjectStore store;
    private final ConsentService consents;

    public PrivacyApprovedInputTransfer(PrivacyTransferPolicy policy, ObjectStore store) {
        this(policy, store, null);
    }

    /**
     * @param consents the owner's consent records. When absent the transfer is
     *     not consent-checked, which is the posture of the slim contexts that
     *     build this class without a DataSource; every deployment wires it.
     */
    public PrivacyApprovedInputTransfer(PrivacyTransferPolicy policy, ObjectStore store, ConsentService consents) {
        if (policy == null) {
            throw new IllegalArgumentException("privacy transfer policy is required");
        }
        if (store == null) {
            throw new IllegalArgumentException("object store is required");
        }
        this.policy = policy;
        this.store = store;
        this.consents = consents;
    }

    public PreparedInput prepare(
        PrivateMediaRef media,
        Set<InputTransferMethod> providerSupported,
        Duration providerRequiredTtl
    ) {
        return prepare(media, providerSupported, providerRequiredTtl, null);
    }

    /**
     * Consent-checked transfer. A body image may only reach a third-party
     * provider while its owner holds an active third-party AI transfer consent;
     * the check runs before the object is even read, so a withdrawal stops the
     * next transfer rather than the next upload.
     */
    public PreparedInput prepare(
        PrivateMediaRef media,
        Set<InputTransferMethod> providerSupported,
        Duration providerRequiredTtl,
        UserId owner
    ) {
        requireTransferConsent(owner);
        if (media == null) {
            throw new IllegalArgumentException("private media is required");
        }
        ObjectRef object = objectRef(media.storageKey().value());
        ObjectMetadata metadata = null;
        OptionalLong knownSize = media.sizeBytes();
        long sizeBytes;
        if (knownSize.isPresent()) {
            sizeBytes = knownSize.getAsLong();
        } else {
            metadata = store.head(object);
            sizeBytes = metadata.contentLength();
        }
        PrivacyTransferPolicy.TransferDecision decision = policy.chooseInputTransfer(
            providerSupported,
            sizeBytes,
            providerRequiredTtl);
        if (decision.method() == InputTransferMethod.BOUNDED_INLINE_DATA) {
            String version = metadata == null ? null : metadata.version();
            byte[] bytes = store.readBounded(object, version, boundedReadCap(policy.maxInlineBytes()));
            return new PreparedInput(decision.method(), Optional.of(bytes), Optional.empty());
        }
        Duration ttl = shorter(providerRequiredTtl, policy.maxSignedReadTtl());
        PresignedAccess access = store.presignGet(object, ttl);
        return new PreparedInput(decision.method(), Optional.empty(), Optional.of(access));
    }

    private void requireTransferConsent(UserId owner) {
        if (consents == null) {
            return;
        }
        if (owner == null || !consents.isActive(owner, ConsentPurpose.THIRD_PARTY_AI_TRANSFER)) {
            throw new ConsentRequiredForTransferException(
                "third-party AI transfer consent is not active for this owner");
        }
    }

    private static ObjectRef objectRef(String key) {
        if (key.startsWith("temp/")) {
            return new ObjectRef(StorageDomain.TEMP, key);
        }
        if (key.contains("/avatars/")) {
            return new ObjectRef(StorageDomain.AVATAR, key);
        }
        if (key.contains("/vault/")) {
            return new ObjectRef(StorageDomain.VAULT, key);
        }
        if (key.contains("/generation-inputs/")) {
            return new ObjectRef(StorageDomain.TEMP, key);
        }
        if (key.contains("/looks/")) {
            return new ObjectRef(StorageDomain.LOOK, key);
        }
        throw new IllegalArgumentException("media object is unresolvable");
    }

    private static Duration shorter(Duration providerRequiredTtl, Duration maxSignedReadTtl) {
        return providerRequiredTtl.compareTo(maxSignedReadTtl) <= 0 ? providerRequiredTtl : maxSignedReadTtl;
    }

    private static int boundedReadCap(long maxInlineBytes) {
        return (int) Math.min(maxInlineBytes, Integer.MAX_VALUE);
    }

    public record PreparedInput(
        InputTransferMethod method,
        Optional<byte[]> inlineBytes,
        Optional<PresignedAccess> signedRead
    ) {
        public PreparedInput {
            if (method == null) {
                throw new IllegalArgumentException("input transfer method is required");
            }
            if (inlineBytes == null) {
                throw new IllegalArgumentException("inline bytes optional is required");
            }
            if (signedRead == null) {
                throw new IllegalArgumentException("signed-read optional is required");
            }
            if (method == InputTransferMethod.BOUNDED_INLINE_DATA) {
                if (inlineBytes.isEmpty()) {
                    throw new IllegalArgumentException("inline transfer requires bytes");
                }
                if (signedRead.isPresent()) {
                    throw new IllegalArgumentException("inline transfer must not carry a signed read");
                }
            } else if (method == InputTransferMethod.EPHEMERAL_SIGNED_READ) {
                if (signedRead.isEmpty()) {
                    throw new IllegalArgumentException("signed-read transfer requires access");
                }
                if (inlineBytes.isPresent()) {
                    throw new IllegalArgumentException("signed-read transfer must not carry inline bytes");
                }
            } else {
                throw new IllegalArgumentException("unsupported input transfer method");
            }
        }

        @Override
        public String toString() {
            return "PreparedInput[method=" + method
                + ", inlineBytes=" + (inlineBytes.isPresent() ? inlineBytes.get().length + "B" : "absent")
                + ", signedRead=" + signedRead.map(access -> access.toString()).orElse("absent")
                + "]";
        }
    }
}
