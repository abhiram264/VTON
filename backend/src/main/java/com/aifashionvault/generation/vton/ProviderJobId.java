package com.aifashionvault.generation.vton;

public record ProviderJobId(String value) {
    public ProviderJobId { value = OpaqueValue.requireSafe(value, "provider job id"); }
}
