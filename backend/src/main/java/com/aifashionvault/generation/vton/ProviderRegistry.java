package com.aifashionvault.generation.vton;

import java.util.Collection;
import java.util.List;

public interface ProviderRegistry {
    VtonProvider resolve(ProviderKey providerKey);
    ProviderKey selectedProviderKey();

    /**
     * Registered provider keys considered routing candidates. The default exposes only the
     * bootstrap selection; multi-provider registries override. Additive routing seam
     * (slice-74); existing callers are unaffected.
     */
    default Collection<ProviderKey> providerKeys() {
        return List.of(selectedProviderKey());
    }

    default VtonProvider selectedProvider() {
        return resolve(selectedProviderKey());
    }
}
