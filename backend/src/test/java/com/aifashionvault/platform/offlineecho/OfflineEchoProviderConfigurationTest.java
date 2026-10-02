package com.aifashionvault.platform.offlineecho;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.generation.vton.ProviderRegistry;
import com.aifashionvault.generation.vton.ProviderRegistryComposition;
import com.aifashionvault.platform.objectstore.ObjectStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Fail-closed gate for the offline echo backstop. The fixture may register ONLY with an
 * explicit opt-in AND a development environment; every other combination (disabled,
 * staging, production, missing env) must leave no provider and therefore no registry —
 * the honest-unavailable posture that keeps a real deployment from serving echoes.
 */
class OfflineEchoProviderConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(OfflineEchoProviderConfiguration.class, ProviderRegistryComposition.class)
        .withBean(ObjectStore.class, () -> mock(ObjectStore.class));

    @Test
    void registersInDevelopmentWhenExplicitlyEnabled() {
        runner.withPropertyValues("vault.env=development", "vault.offline.provider.enabled=true")
            .run(context -> {
                assertThat(context).hasSingleBean(OfflineEchoVtonProvider.class);
                assertThat(context).hasSingleBean(ProviderRegistry.class);
                ProviderRegistry registry = context.getBean(ProviderRegistry.class);
                assertThat(registry.selectedProviderKey()).isEqualTo(new ProviderKey("offline-echo"));
                assertThat(registry.selectedProvider()).isSameAs(context.getBean(OfflineEchoVtonProvider.class));
            });
    }

    @Test
    void doesNotRegisterWhenTheOptInIsAbsent() {
        runner.withPropertyValues("vault.env=development")
            .run(context -> {
                assertThat(context).doesNotHaveBean(OfflineEchoVtonProvider.class);
                assertThat(context.getBeanProvider(ProviderRegistry.class).getIfAvailable()).isNull();
            });
    }

    @Test
    void doesNotRegisterWhenTheOptInIsFalse() {
        runner.withPropertyValues("vault.env=development", "vault.offline.provider.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(OfflineEchoVtonProvider.class));
    }

    @Test
    void neverRegistersInStagingEvenWhenEnabled() {
        runner.withPropertyValues("vault.env=staging", "vault.offline.provider.enabled=true")
            .run(context -> {
                assertThat(context).doesNotHaveBean(OfflineEchoVtonProvider.class);
                assertThat(context.getBeanProvider(ProviderRegistry.class).getIfAvailable()).isNull();
            });
    }

    @Test
    void neverRegistersInProductionEvenWhenEnabled() {
        runner.withPropertyValues("vault.env=production", "vault.offline.provider.enabled=true")
            .run(context -> {
                assertThat(context).doesNotHaveBean(OfflineEchoVtonProvider.class);
                assertThat(context.getBeanProvider(ProviderRegistry.class).getIfAvailable()).isNull();
            });
    }

    @Test
    void neverRegistersWhenTheEnvironmentIsUnset() {
        runner.withPropertyValues("vault.offline.provider.enabled=true")
            .run(context -> assertThat(context).doesNotHaveBean(OfflineEchoVtonProvider.class));
    }
}
