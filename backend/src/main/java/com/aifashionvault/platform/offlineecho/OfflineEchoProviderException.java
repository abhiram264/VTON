package com.aifashionvault.platform.offlineecho;

import com.aifashionvault.generation.vton.ProviderFailure;

/**
 * Carries a classified {@link ProviderFailure} out of the offline echo backstop's output
 * path, mirroring {@code FashnProviderException} / {@code InferHubProviderException}:
 * a missing or malformed output reference never escapes as an unclassified crash.
 */
public final class OfflineEchoProviderException extends RuntimeException {

    private final ProviderFailure failure;

    public OfflineEchoProviderException(ProviderFailure failure) {
        super(require(failure).safeCode());
        this.failure = failure;
    }

    public ProviderFailure failure() {
        return failure;
    }

    private static ProviderFailure require(ProviderFailure failure) {
        if (failure == null) {
            throw new IllegalArgumentException("provider failure is required");
        }
        return failure;
    }
}
