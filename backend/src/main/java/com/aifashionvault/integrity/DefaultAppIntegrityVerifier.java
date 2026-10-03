package com.aifashionvault.integrity;

import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

public final class DefaultAppIntegrityVerifier implements AppIntegrityVerifier {
    private static final int MAX_ASSERTION_ID_LENGTH = 256;
    private static final int MAX_TRACKED_ASSERTIONS = 10_000;
    // ponytail: bounded local fallback only; a durable shared store is required for multi-replica replay safety.
    private static final Map<String, Instant> USED_ASSERTIONS = new HashMap<>();
    private final boolean required;
    private final AndroidIntegrityAdapter android;
    private final IosIntegrityAdapter ios;
    private final Clock clock;

    public DefaultAppIntegrityVerifier(boolean required, AndroidIntegrityAdapter android, IosIntegrityAdapter ios, Clock clock) {
        this.required = required; this.android = android; this.ios = ios; this.clock = clock;
    }

    @Override public AppIntegrityVerdict verify(AppIntegrityEvidence evidence) {
        if (evidence == null || evidence.opaqueEvidence() == null || evidence.opaqueEvidence().isBlank())
            return result(AppIntegrityFailureReason.MISSING_EVIDENCE);
        if (evidence.requestNonce() == null || evidence.requestNonce().isBlank())
            return result(AppIntegrityFailureReason.NONCE_MISMATCH);
        if (evidence.operation() == null) return result(AppIntegrityFailureReason.OPERATION_MISMATCH);
        if (evidence.platform() == null) return result(AppIntegrityFailureReason.UNSUPPORTED_PLATFORM);
        try {
            AppIntegrityPlatform platform = evidence.platform();
            ProviderVerification v = platform == AppIntegrityPlatform.ANDROID ? android.verify(evidence) : ios.verify(evidence);
            if (v == null) return result(AppIntegrityFailureReason.INTERNAL_ERROR);
            if (v.status() != AppIntegrityVerdictStatus.VERIFIED || v.reason() != AppIntegrityFailureReason.NONE) {
                AppIntegrityVerdictStatus status = v.status() == AppIntegrityVerdictStatus.VERIFIED
                        ? (required ? AppIntegrityVerdictStatus.DENIED : AppIntegrityVerdictStatus.INDETERMINATE)
                        : (required ? AppIntegrityVerdictStatus.DENIED : v.status());
                return new AppIntegrityVerdict(status, safeReason(v.reason()));
            }
            if (v.platform() != platform) return result(AppIntegrityFailureReason.PLATFORM_MISMATCH);
            if (v.operation() != evidence.operation()) return result(AppIntegrityFailureReason.OPERATION_MISMATCH);
            if (!evidence.requestNonce().equals(v.requestNonce())) return result(AppIntegrityFailureReason.NONCE_MISMATCH);
            String expectedAppId = platform == AppIntegrityPlatform.ANDROID ? android.expectedAppId() : ios.expectedAppId();
            if (expectedAppId == null || expectedAppId.isBlank()) return result(AppIntegrityFailureReason.PROVIDER_UNAVAILABLE);
            if (!expectedAppId.equals(v.appId())) return result(AppIntegrityFailureReason.APP_MISMATCH);
            Instant now = clock.instant();
            if (v.expiresAt() == null || !v.expiresAt().isAfter(now)) return result(AppIntegrityFailureReason.EXPIRED);
            if (v.assertionId() == null || v.assertionId().isBlank() || v.assertionId().length() > MAX_ASSERTION_ID_LENGTH) {
                return result(AppIntegrityFailureReason.MALFORMED_EVIDENCE);
            }
            AppIntegrityFailureReason replayFailure = rememberAssertion(v.assertionId(), v.expiresAt(), now);
            if (replayFailure != null) return result(replayFailure);
            return new AppIntegrityVerdict(AppIntegrityVerdictStatus.VERIFIED, AppIntegrityFailureReason.NONE);
        } catch (RuntimeException ignored) { return result(AppIntegrityFailureReason.INTERNAL_ERROR); }
    }

    private AppIntegrityVerdict result(AppIntegrityFailureReason reason) {
        return new AppIntegrityVerdict(required ? AppIntegrityVerdictStatus.DENIED : AppIntegrityVerdictStatus.INDETERMINATE, reason);
    }
    private static AppIntegrityFailureReason rememberAssertion(String assertionId, Instant expiresAt, Instant now) {
        synchronized (USED_ASSERTIONS) {
            USED_ASSERTIONS.entrySet().removeIf(entry -> !entry.getValue().isAfter(now));
            if (USED_ASSERTIONS.containsKey(assertionId)) return AppIntegrityFailureReason.REPLAYED;
            if (USED_ASSERTIONS.size() >= MAX_TRACKED_ASSERTIONS) return AppIntegrityFailureReason.INTERNAL_ERROR;
            USED_ASSERTIONS.put(assertionId, expiresAt);
            return null;
        }
    }
    private static AppIntegrityFailureReason safeReason(AppIntegrityFailureReason reason) {
        return reason == null || reason == AppIntegrityFailureReason.NONE ? AppIntegrityFailureReason.PROVIDER_REJECTED : reason;
    }
}
