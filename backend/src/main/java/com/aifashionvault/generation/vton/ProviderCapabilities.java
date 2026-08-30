package com.aifashionvault.generation.vton;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

public record ProviderCapabilities(Map<VtonMode, ModeCapabilities> modes, Set<InputTransferMethod> inputTransfers, Set<OutputDeliveryMethod> outputDeliveries, SubmissionReplaySafety submissionReplaySafety) {
    public ProviderCapabilities {
        if (modes == null || modes.isEmpty()) throw new IllegalArgumentException("mode capabilities are required");
        if (modes.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) throw new IllegalArgumentException("mode capabilities must not contain null");
        modes = Map.copyOf(modes);
        if (inputTransfers == null || inputTransfers.isEmpty() || inputTransfers.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("input transfer capabilities are required");
        inputTransfers = Set.copyOf(inputTransfers);
        if (outputDeliveries == null || outputDeliveries.isEmpty() || outputDeliveries.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("output delivery capabilities are required");
        outputDeliveries = Set.copyOf(outputDeliveries);
        if (submissionReplaySafety == null) throw new IllegalArgumentException("submission replay safety is required");
    }

    public Optional<ModeCapabilities> forMode(VtonMode mode) {
        if (mode == null) return Optional.empty();
        return Optional.ofNullable(modes.get(mode));
    }
}
