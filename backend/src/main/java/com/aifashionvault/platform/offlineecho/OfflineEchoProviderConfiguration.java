package com.aifashionvault.platform.offlineecho;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.aifashionvault.platform.objectstore.ObjectStore;

/**
 * Registers the offline echo backstop provider through the existing provider seam.
 *
 * <p>Off by default. Enable for local wiring/diagnostic runs with
 * {@code vault.offline.provider.enabled=true} while {@code vault.env=development}; the
 * {@link ConditionalOnOfflineEchoProvider} gate keeps it out of staging and production
 * entirely. It requires no provider API key — that is the whole point: it proves the
 * generation pipeline end to end with zero paid calls and zero image synthesis.
 */
@Configuration(proxyBeanMethods = false)
public class OfflineEchoProviderConfiguration {

    @Bean
    @ConditionalOnOfflineEchoProvider
    OfflineEchoVtonProvider offlineEchoVtonProvider(ObjectStore objectStore) {
        return new OfflineEchoVtonProvider(objectStore);
    }
}
