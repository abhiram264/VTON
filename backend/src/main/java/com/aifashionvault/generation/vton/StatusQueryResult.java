package com.aifashionvault.generation.vton;

public sealed interface StatusQueryResult permits StatusQueryResult.Observed, StatusQueryResult.Unavailable {
    record Observed(GenerationStatus status) implements StatusQueryResult {
        public Observed {
            if (status == null) throw new IllegalArgumentException("observed status is required");
        }
    }

    record Unavailable(ProviderFailure failure) implements StatusQueryResult {
        public Unavailable {
            if (failure == null) throw new IllegalArgumentException("status query failure is required");
        }
    }
}
