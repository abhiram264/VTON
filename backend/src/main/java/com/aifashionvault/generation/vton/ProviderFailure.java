package com.aifashionvault.generation.vton;

import java.time.Instant;

public record ProviderFailure(
    ProviderFailureCategory category,
    boolean retryable,
    String safeCode,
    Instant retryAfterAt,
    String mappingContractVersion) {
    public ProviderFailure(ProviderFailureCategory category, boolean retryable, String safeCode) {
        this(category, retryable, safeCode, null, null);
    }

    public ProviderFailure(
        ProviderFailureCategory category,
        boolean retryable,
        String safeCode,
        Instant retryAfterAt
    ) {
        this(category, retryable, safeCode, retryAfterAt, null);
    }

    public ProviderFailure {
        if (category == null) throw new IllegalArgumentException("provider failure category is required");
        if (safeCode == null || !safeCode.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException("safe diagnostic code is required and must contain no provider payload");
        }
        if (mappingContractVersion != null && !mappingContractVersion.matches("[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}")) {
            throw new IllegalArgumentException("mapping contract version must be null or a normalized adapter token");
        }
    }
}
