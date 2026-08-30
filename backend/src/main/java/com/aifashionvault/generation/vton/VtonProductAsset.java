package com.aifashionvault.generation.vton;

public record VtonProductAsset(PrivateMediaRef media, ItemCategory category) {
    public VtonProductAsset {
        if (media == null) throw new IllegalArgumentException("product media is required");
        if (category == null) throw new IllegalArgumentException("product category is required");
    }
}
