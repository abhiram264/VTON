package com.aifashionvault.integrity;

import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultAppIntegrityVerifier implements AppIntegrityVerifier {
    private static final Set<String> USED_ASSERTIONS = ConcurrentHashMap.newKeySet();
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
            ProviderVerification v = evidence.platform() == AppIntegrityPlatform.ANDROID ? android.verify(evidence) : ios.verify(evidence);
            if (v == null) return result(AppIntegrityFailureReason.INTERNAL_ERROR);
            if (v.status() != AppIntegrityVerdictStatus.VERIFIED) return new AppIntegrityVerdict(
                    required ? AppIntegrityVerdictStatus.DENIED : v.status(), safeReason(v.reason()));
            if (v.platform() != evidence.platform()) return result(AppIntegrityFailureReason.PLATFORM_MISMATCH);
            if (v.operation() != evidence.operation()) return result(AppIntegrityFailureReason.OPERATION_MISMATCH);
            if (!evidence.requestNonce().equals(v.requestNonce())) return result(AppIntegrityFailureReason.NONCE_MISMATCH);
            if (v.expiresAt() == null || !v.expiresAt().isAfter(clock.instant())) return result(AppIntegrityFailureReason.EXPIRED);
            if (v.assertionId() == null || !USED_ASSERTIONS.add(v.assertionId())) return result(AppIntegrityFailureReason.REPLAYED);
            return new AppIntegrityVerdict(AppIntegrityVerdictStatus.VERIFIED, AppIntegrityFailureReason.NONE);
        } catch (RuntimeException ignored) { return result(AppIntegrityFailureReason.INTERNAL_ERROR); }
    }

    private AppIntegrityVerdict result(AppIntegrityFailureReason reason) {
        return new AppIntegrityVerdict(required ? AppIntegrityVerdictStatus.DENIED : AppIntegrityVerdictStatus.INDETERMINATE, reason);
    }
    private static AppIntegrityFailureReason safeReason(AppIntegrityFailureReason reason) {
        return reason == null || reason == AppIntegrityFailureReason.NONE ? AppIntegrityFailureReason.PROVIDER_REJECTED : reason;
    }
}
