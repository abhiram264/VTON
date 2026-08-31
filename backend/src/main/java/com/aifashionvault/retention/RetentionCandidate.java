package com.aifashionvault.retention;

import java.time.Instant;

public record RetentionCandidate(RetentionClass retentionClass, String candidateId, Instant lastModified) {
    public RetentionCandidate {
        if (retentionClass == null) throw new IllegalArgumentException("retentionClass is required");
        if (candidateId == null || candidateId.isBlank()) throw new IllegalArgumentException("candidateId is required");
    }
}
