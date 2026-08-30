package com.aifashionvault.platform.fashn;

import com.aifashionvault.generation.vton.ProviderFailure;

public final class FashnProviderException extends RuntimeException {
    private final ProviderFailure failure;

    public FashnProviderException(ProviderFailure failure) {
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
