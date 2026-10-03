package com.aifashionvault.platform.inferhub;

import com.aifashionvault.generation.vton.ProviderFailure;

/**
 * Carries a classified {@link ProviderFailure} out of the adapter's output
 * path (openOutput), mirroring {@code FashnProviderException}: never an
 * unclassified crash reaching the worker thread.
 */
public final class InferHubProviderException extends RuntimeException {
    private final ProviderFailure failure;

    public InferHubProviderException(ProviderFailure failure) {
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
