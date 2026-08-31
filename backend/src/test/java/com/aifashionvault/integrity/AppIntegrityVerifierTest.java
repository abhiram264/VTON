package com.aifashionvault.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AppIntegrityVerifierTest {
    private static final Instant NOW = Instant.parse("2026-08-31T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    @Test void validEvidenceVerifiesAllOperationsAndPlatforms() {
        var v = verifier(true, valid(), valid());
        assertThat(v.verify(e(AppIntegrityOperation.GENERATION_CREATION, AppIntegrityPlatform.ANDROID, "a")).status()).isEqualTo(AppIntegrityVerdictStatus.VERIFIED);
        assertThat(v.verify(e(AppIntegrityOperation.AVATAR_UPLOAD, AppIntegrityPlatform.IOS, "b")).status()).isEqualTo(AppIntegrityVerdictStatus.VERIFIED);
        assertThat(v.verify(e(AppIntegrityOperation.PROMO_CREDIT_REDEMPTION, AppIntegrityPlatform.ANDROID, "c")).status()).isEqualTo(AppIntegrityVerdictStatus.VERIFIED);
    }
    @Test void invalidInputsMapToSafeReasons() {
        var v = verifier(true, valid(), valid());
        assertThat(v.verify(new AppIntegrityEvidence(AppIntegrityOperation.GENERATION_CREATION, AppIntegrityPlatform.ANDROID, "", "n"))).isEqualTo(new AppIntegrityVerdict(AppIntegrityVerdictStatus.DENIED, AppIntegrityFailureReason.MISSING_EVIDENCE));
        assertThat(v.verify(new AppIntegrityEvidence(null, AppIntegrityPlatform.ANDROID, "x", "n"))).isEqualTo(new AppIntegrityVerdict(AppIntegrityVerdictStatus.DENIED, AppIntegrityFailureReason.OPERATION_MISMATCH));
        assertThat(v.verify(new AppIntegrityEvidence(AppIntegrityOperation.GENERATION_CREATION, null, "x", "n"))).isEqualTo(new AppIntegrityVerdict(AppIntegrityVerdictStatus.DENIED, AppIntegrityFailureReason.UNSUPPORTED_PLATFORM));
        assertThat(v.verify(new AppIntegrityEvidence(AppIntegrityOperation.GENERATION_CREATION, AppIntegrityPlatform.ANDROID, "x", ""))).isEqualTo(new AppIntegrityVerdict(AppIntegrityVerdictStatus.DENIED, AppIntegrityFailureReason.NONCE_MISMATCH));
    }
    @Test void providerFailuresAndReplayNeverVerify() {
        var unavailable = verifier(true, ignored -> ProviderVerification.unavailable(), valid());
        assertThat(unavailable.verify(e(AppIntegrityOperation.GENERATION_CREATION, AppIntegrityPlatform.ANDROID, "x"))).isEqualTo(new AppIntegrityVerdict(AppIntegrityVerdictStatus.DENIED, AppIntegrityFailureReason.PROVIDER_UNAVAILABLE));
        var v = verifier(true, valid(), valid()); var e = e(AppIntegrityOperation.GENERATION_CREATION, AppIntegrityPlatform.ANDROID, "replay");
        assertThat(v.verify(e).status()).isEqualTo(AppIntegrityVerdictStatus.VERIFIED);
        assertThat(v.verify(e)).isEqualTo(new AppIntegrityVerdict(AppIntegrityVerdictStatus.DENIED, AppIntegrityFailureReason.REPLAYED));
    }
    @Test void optionalUnavailableIsIndeterminateAndDoesNotExposeEvidence() {
        var v = verifier(false, ignored -> ProviderVerification.unavailable(), valid());
        var verdict = v.verify(e(AppIntegrityOperation.GENERATION_CREATION, AppIntegrityPlatform.ANDROID, "secret"));
        assertThat(verdict).isEqualTo(new AppIntegrityVerdict(AppIntegrityVerdictStatus.INDETERMINATE, AppIntegrityFailureReason.PROVIDER_UNAVAILABLE));
        assertThat(verdict.toString()).doesNotContain("secret");
    }
    private static IntegrityEvidenceProvider valid() { return x -> ProviderVerification.verified(x, NOW.plusSeconds(60), x.opaqueEvidence()); }
    private static AppIntegrityVerifier verifier(boolean required, IntegrityEvidenceProvider a, IntegrityEvidenceProvider i) { return new DefaultAppIntegrityVerifier(required, new AndroidIntegrityAdapter(a), new IosIntegrityAdapter(i), CLOCK); }
    private static AppIntegrityEvidence e(AppIntegrityOperation o, AppIntegrityPlatform p, String x) { return new AppIntegrityEvidence(o, p, x, "n"); }
}
