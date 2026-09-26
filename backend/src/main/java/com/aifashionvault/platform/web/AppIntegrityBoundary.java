package com.aifashionvault.platform.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;

import com.aifashionvault.integrity.AppIntegrityEvidence;
import com.aifashionvault.integrity.AppIntegrityOperation;
import com.aifashionvault.integrity.AppIntegrityPlatform;
import com.aifashionvault.integrity.AppIntegrityVerifier;
import com.aifashionvault.integrity.AppIntegrityVerdictStatus;

// App-attestation gate for provider-cost write paths.
//
// WHAT THIS DOES: when `required` is true, it requires a well-formed platform header
// plus a VERIFIED verdict from the configured AppIntegrityVerifier, and otherwise
// fails closed with 403 APP_INTEGRITY_REQUIRED. It is checked before any provider-cost
// work, so a rejected attestation never reaches the generation job, the avatar workflow
// or the regeneration path.
//
// WHAT THIS DOES NOT DO TODAY: it is inert whenever `required` is false, and `required`
// is false in every real deployment. No IntegrityEvidenceProvider is registered, and the
// androidAppId/iosAppId on the vault.integrity AppIntegrityConfiguration Properties record
// are unset, so DefaultAppIntegrityVerifier returns PROVIDER_UNAVAILABLE before it can
// reach VERIFIED: enabling the flag today would reject 100% of traffic rather than
// enforce attestation. Read a false flag here as NOT-YET-ENFORCING,
// never as "attestation is on". Enabling the flag is fail-closed and stays that way;
// the missing piece is a real provider plus app-id configuration, deliberately out of
// scope here rather than stubbed.
final class AppIntegrityBoundary {
    static final String CODE = "APP_INTEGRITY_REQUIRED";

    private final AppIntegrityVerifier verifier;
    private final boolean required;

    AppIntegrityBoundary(AppIntegrityVerifier verifier, boolean required) {
        this.verifier = verifier;
        this.required = required;
    }

    void verify(HttpServletRequest request, AppIntegrityOperation operation) {
        if (!required) return;
        AppIntegrityPlatform platform;
        try {
            platform = AppIntegrityPlatform.valueOf(request.getHeader("X-App-Integrity-Platform"));
        }
        catch (RuntimeException ex) {
            reject();
            return;
        }
        var verdict = verifier.verify(new AppIntegrityEvidence(
            operation,
            platform,
            request.getHeader("X-App-Integrity-Evidence"),
            request.getHeader("X-App-Integrity-Nonce")));
        if (verdict == null || verdict.status() != AppIntegrityVerdictStatus.VERIFIED) reject();
    }

    private static void reject() {
        throw new Problem(HttpStatus.FORBIDDEN, CODE);
    }

    static final class Problem extends RuntimeException {
        private final HttpStatus status;
        private final String code;
        Problem(HttpStatus status, String code) { this.status = status; this.code = code; }
        HttpStatus status() { return status; }
        String code() { return code; }
    }
}
