package com.aifashionvault.generation.vton;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;

public record VtonRequest(PrivateMediaRef avatar, List<VtonProductAsset> productAssets, Optional<String> instructions, VtonMode mode) {
    public VtonRequest {
        if (avatar == null) throw new IllegalArgumentException("avatar is required");
        if (productAssets == null || productAssets.isEmpty()) throw new IllegalArgumentException("at least one product asset is required");
        if (productAssets.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("product assets must not contain null");
        productAssets = List.copyOf(productAssets);
        if (new HashSet<>(productAssets).size() != productAssets.size()) throw new IllegalArgumentException("duplicate product assets are not allowed");
        if (instructions == null) throw new IllegalArgumentException("instructions optional is required");
        instructions = instructions.map(String::strip).filter(value -> !value.isEmpty());
        if (mode == null) throw new IllegalArgumentException("VTON mode is required");
    }
}
