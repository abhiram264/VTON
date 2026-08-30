package com.aifashionvault.generation.vton;

import java.util.Set;

public record ModeCapabilities(Set<ItemCategory> supportedCategories, int maxProductAssets, boolean instructionsSupported, InputSizeLimit inputSizeLimit) {
    public ModeCapabilities {
        if (supportedCategories == null || supportedCategories.isEmpty()) throw new IllegalArgumentException("supported categories are required");
        if (supportedCategories.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("supported categories must not contain null");
        supportedCategories = Set.copyOf(supportedCategories);
        if (maxProductAssets < 1) throw new IllegalArgumentException("maximum product assets must be positive");
        if (inputSizeLimit == null) throw new IllegalArgumentException("input size limit knowledge is required");
    }
}
