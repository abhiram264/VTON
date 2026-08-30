package com.aifashionvault.generation.vton;

import java.util.Optional;

public sealed interface GenerationSubmission permits GenerationSubmission.Accepted, GenerationSubmission.Rejected, GenerationSubmission.Indeterminate {
    record Accepted(ProviderJobId providerJobId, Optional<ProviderUsage> usage) implements GenerationSubmission {
        public Accepted {
            if (providerJobId == null) throw new IllegalArgumentException("provider job id is required");
            if (usage == null) throw new IllegalArgumentException("usage optional is required");
        }
    }

    record Rejected(ProviderFailure failure) implements GenerationSubmission {
        public Rejected {
            if (failure == null) throw new IllegalArgumentException("provider failure is required");
        }
    }

    record Indeterminate(ProviderFailure failure) implements GenerationSubmission {
        public Indeterminate {
            if (failure == null) throw new IllegalArgumentException("provider failure is required");
        }
    }
}
