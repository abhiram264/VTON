package com.aifashionvault.generation.vton;

public interface ProviderRegistry {
    VtonProvider resolve(ProviderKey providerKey);
    ProviderKey selectedProviderKey();

    default VtonProvider selectedProvider() {
        return resolve(selectedProviderKey());
    }
}
