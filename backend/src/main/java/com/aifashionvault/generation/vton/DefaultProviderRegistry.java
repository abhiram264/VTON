package com.aifashionvault.generation.vton;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DefaultProviderRegistry implements ProviderRegistry {
    private final Map<ProviderKey, VtonProvider> providers;
    private final ProviderKey selectedProviderKey;
    private final Map<ProviderKey, ProviderKey> aliases;

    public DefaultProviderRegistry(Collection<? extends VtonProvider> providers, ProviderKey selectedProviderKey, Map<ProviderKey, ProviderKey> aliases) {
        if (providers == null || providers.isEmpty()) throw new IllegalArgumentException("at least one VTON provider is required");
        Map<ProviderKey, VtonProvider> indexed = new LinkedHashMap<>();
        for (VtonProvider provider : providers) {
            if (provider == null || provider.providerKey() == null) throw new IllegalArgumentException("provider and provider key are required");
            if (indexed.putIfAbsent(provider.providerKey(), provider) != null) throw new IllegalArgumentException("duplicate provider key: " + provider.providerKey().value());
        }
        this.providers = Map.copyOf(indexed);
        if (selectedProviderKey == null || !this.providers.containsKey(selectedProviderKey)) throw new IllegalArgumentException("selected provider must be registered");
        this.selectedProviderKey = selectedProviderKey;
        if (aliases == null) throw new IllegalArgumentException("provider aliases are required");
        for (Map.Entry<ProviderKey, ProviderKey> alias : aliases.entrySet()) {
            if (alias.getKey() == null || alias.getValue() == null || this.providers.containsKey(alias.getKey()) || !this.providers.containsKey(alias.getValue())) {
                throw new IllegalArgumentException("provider alias must be unique and target a registered provider");
            }
        }
        this.aliases = Map.copyOf(aliases);
    }

    @Override public VtonProvider resolve(ProviderKey providerKey) {
        if (providerKey == null) throw new IllegalArgumentException("provider key is required");
        VtonProvider direct = providers.get(providerKey);
        if (direct != null) return direct;
        ProviderKey target = aliases.get(providerKey);
        if (target != null) return providers.get(target);
        throw new UnknownProviderException(providerKey);
    }

    @Override public ProviderKey selectedProviderKey() { return selectedProviderKey; }

    @Override public Collection<ProviderKey> providerKeys() { return providers.keySet(); }
}
