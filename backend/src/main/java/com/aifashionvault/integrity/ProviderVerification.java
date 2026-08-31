package com.aifashionvault.integrity;

import java.time.Instant;

public record ProviderVerification(AppIntegrityVerdictStatus status, AppIntegrityFailureReason reason,
        AppIntegrityPlatform platform, AppIntegrityOperation operation, String requestNonce,
        String appId, Instant expiresAt, String assertionId) {
    public static ProviderVerification verified(AppIntegrityEvidence e, Instant expiresAt, String assertionId) {
        return new ProviderVerification(AppIntegrityVerdictStatus.VERIFIED, AppIntegrityFailureReason.NONE,
                e.platform(), e.operation(), e.requestNonce(), "app", expiresAt, assertionId);
    }
    public static ProviderVerification denied(AppIntegrityFailureReason reason) {
        return new ProviderVerification(AppIntegrityVerdictStatus.DENIED, reason, null, null, null, null, null, null);
    }
    public static ProviderVerification malformed() { return denied(AppIntegrityFailureReason.MALFORMED_EVIDENCE); }
    public static ProviderVerification unavailable() { return new ProviderVerification(AppIntegrityVerdictStatus.INDETERMINATE,
            AppIntegrityFailureReason.PROVIDER_UNAVAILABLE, null, null, null, null, null, null); }
}
