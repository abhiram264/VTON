package com.aifashionvault.generation.vton;

public record ProviderOutputRef(String value) {
    public ProviderOutputRef { value = OpaqueValue.requireSafe(value, "provider output reference"); }
}
