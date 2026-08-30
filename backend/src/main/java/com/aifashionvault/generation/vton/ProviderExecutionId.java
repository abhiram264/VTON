package com.aifashionvault.generation.vton;

public record ProviderExecutionId(ProviderKey providerKey, ProviderJobId providerJobId) {
    public ProviderExecutionId {
        if (providerKey == null) throw new IllegalArgumentException("provider key is required");
        if (providerJobId == null) throw new IllegalArgumentException("provider job id is required");
    }
}
