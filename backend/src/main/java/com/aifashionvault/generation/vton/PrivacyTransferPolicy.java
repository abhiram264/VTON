package com.aifashionvault.generation.vton;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;

public record PrivacyTransferPolicy(Set<InputTransferMethod> allowedInputTransfers, Set<OutputDeliveryMethod> allowedOutputDeliveries, long maxInlineBytes, Duration maxSignedReadTtl, boolean opaqueAliasRequired) {
    public PrivacyTransferPolicy {
        if (allowedInputTransfers == null || allowedInputTransfers.isEmpty() || allowedInputTransfers.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("allowed input transfers are required");
        allowedInputTransfers = Set.copyOf(allowedInputTransfers);
        if (allowedOutputDeliveries == null || allowedOutputDeliveries.isEmpty() || allowedOutputDeliveries.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("allowed output deliveries are required");
        allowedOutputDeliveries = Set.copyOf(allowedOutputDeliveries);
        if (maxInlineBytes < 1) throw new IllegalArgumentException("maximum inline bytes must be positive");
        if (maxSignedReadTtl == null || maxSignedReadTtl.isZero() || maxSignedReadTtl.isNegative()) throw new IllegalArgumentException("maximum signed-read TTL must be positive");
    }

    public TransferDecision chooseInputTransfer(Set<InputTransferMethod> providerSupported, long contentBytes, Duration providerRequiredTtl) {
        if (providerSupported == null) throw new IllegalArgumentException("provider transfer capabilities are required");
        if (contentBytes < 0) throw new IllegalArgumentException("content bytes must be non-negative");
        if (contentBytes <= maxInlineBytes && allowedInputTransfers.contains(InputTransferMethod.BOUNDED_INLINE_DATA)
            && providerSupported.contains(InputTransferMethod.BOUNDED_INLINE_DATA)) {
            return new TransferDecision(InputTransferMethod.BOUNDED_INLINE_DATA, Optional.empty(), opaqueAliasRequired);
        }
        if (allowedInputTransfers.contains(InputTransferMethod.EPHEMERAL_SIGNED_READ)
            && providerSupported.contains(InputTransferMethod.EPHEMERAL_SIGNED_READ)) {
            if (providerRequiredTtl == null || providerRequiredTtl.isZero() || providerRequiredTtl.isNegative()) throw new IllegalArgumentException("provider signed-read TTL must be positive");
            if (providerRequiredTtl.compareTo(maxSignedReadTtl) > 0) {
                throw new UnsupportedProviderCapabilityException("provider requires a signed-read TTL above the application privacy bound");
            }
            return new TransferDecision(InputTransferMethod.EPHEMERAL_SIGNED_READ, Optional.of(providerRequiredTtl), opaqueAliasRequired);
        }
        throw new UnsupportedProviderCapabilityException("provider has no privacy-eligible input transfer");
    }

    public OutputDeliveryMethod chooseOutputDelivery(Set<OutputDeliveryMethod> providerSupported) {
        if (providerSupported == null) throw new IllegalArgumentException("provider output capabilities are required");
        if (allowedOutputDeliveries.contains(OutputDeliveryMethod.BOUNDED_INLINE_DATA)
            && providerSupported.contains(OutputDeliveryMethod.BOUNDED_INLINE_DATA)) {
            return OutputDeliveryMethod.BOUNDED_INLINE_DATA;
        }
        if (allowedOutputDeliveries.contains(OutputDeliveryMethod.EPHEMERAL_PROVIDER_READ)
            && providerSupported.contains(OutputDeliveryMethod.EPHEMERAL_PROVIDER_READ)) {
            return OutputDeliveryMethod.EPHEMERAL_PROVIDER_READ;
        }
        throw new UnsupportedProviderCapabilityException("provider has no privacy-eligible output delivery");
    }

    public record TransferDecision(InputTransferMethod method, Optional<Duration> signedReadTtl, boolean opaqueAliasRequired) {
        public TransferDecision {
            if (method == null) throw new IllegalArgumentException("input transfer method is required");
            if (signedReadTtl == null) throw new IllegalArgumentException("signed-read TTL optional is required");
            if (method == InputTransferMethod.EPHEMERAL_SIGNED_READ && signedReadTtl.isEmpty()) throw new IllegalArgumentException("signed-read transfer requires a TTL");
            if (method != InputTransferMethod.EPHEMERAL_SIGNED_READ && signedReadTtl.isPresent()) throw new IllegalArgumentException("inline transfer must not carry a signed-read TTL");
        }
    }
}
