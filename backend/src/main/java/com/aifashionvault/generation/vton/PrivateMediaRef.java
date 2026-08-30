package com.aifashionvault.generation.vton;

import java.util.OptionalLong;

import com.aifashionvault.shared.StorageKey;

public record PrivateMediaRef(StorageKey storageKey, OptionalLong sizeBytes) {
    public PrivateMediaRef {
        if (storageKey == null) throw new IllegalArgumentException("private media reference is required");
        if (sizeBytes == null) throw new IllegalArgumentException("media size knowledge is required");
        if (sizeBytes.isPresent() && sizeBytes.getAsLong() < 1) throw new IllegalArgumentException("known media size must be positive");
    }

    @Override public String toString() {
        return "PrivateMediaRef[sizeBytes=" + (sizeBytes.isPresent() ? sizeBytes.getAsLong() : "unknown") + "]";
    }
}
