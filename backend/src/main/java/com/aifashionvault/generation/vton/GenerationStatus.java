package com.aifashionvault.generation.vton;

import java.util.List;
import java.util.Optional;

public sealed interface GenerationStatus permits GenerationStatus.Queued, GenerationStatus.Running, GenerationStatus.Succeeded, GenerationStatus.Failed {
    default GenerationLifecycle lifecycle() {
        return switch (this) {
            case Queued ignored -> GenerationLifecycle.QUEUED;
            case Running ignored -> GenerationLifecycle.RUNNING;
            case Succeeded ignored -> GenerationLifecycle.SUCCEEDED;
            case Failed ignored -> GenerationLifecycle.FAILED;
        };
    }

    record Queued() implements GenerationStatus { }
    record Running() implements GenerationStatus { }

    record Succeeded(List<ProviderOutputRef> outputs, Optional<ProviderUsage> usage) implements GenerationStatus {
        public Succeeded {
            if (outputs == null || outputs.isEmpty() || outputs.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("successful status requires output references");
            outputs = List.copyOf(outputs);
            if (usage == null) throw new IllegalArgumentException("usage optional is required");
        }
    }

    record Failed(ProviderFailure failure, Optional<ProviderUsage> usage) implements GenerationStatus {
        public Failed {
            if (failure == null) throw new IllegalArgumentException("provider failure is required");
            if (usage == null) throw new IllegalArgumentException("usage optional is required");
        }
    }
}
