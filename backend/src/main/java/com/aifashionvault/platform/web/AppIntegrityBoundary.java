package com.aifashionvault.platform.web;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;

import com.aifashionvault.integrity.AppIntegrityEvidence;
import com.aifashionvault.integrity.AppIntegrityOperation;
import com.aifashionvault.integrity.AppIntegrityPlatform;
import com.aifashionvault.integrity.AppIntegrityVerifier;
import com.aifashionvault.integrity.AppIntegrityVerdictStatus;

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
