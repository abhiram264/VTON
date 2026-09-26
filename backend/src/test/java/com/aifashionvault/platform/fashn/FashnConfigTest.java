package com.aifashionvault.platform.fashn;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.generation.vton.ProviderRegistry;
import com.aifashionvault.generation.vton.ProviderRegistryComposition;
import com.aifashionvault.generation.vton.UnknownProviderException;
import com.aifashionvault.platform.environment.AppEnv;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;
import com.aifashionvault.platform.objectstore.ObjectStore;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class FashnConfigTest {
    // RE-PINNED after t205 (candidate 7584f9d), re-adapted after t220: FashnConfig no longer
    // declares a ProviderRegistry bean — the composed ProviderRegistryComposition collects every
    // registered VtonProvider and applies the deterministic selection (forced-provider, then
    // inferhub, then fashn). The four FASHN beans now carry @ConditionalOnNonBlankApiKey
    // ("vault.fashn.api-key") — present-but-EMPTY no longer matches, so the ${FASHN_API_KEY:}
    // default in application.yaml cannot half-create the provider. Supplying
    // VaultEnvironmentProperties with a key was never enough: the condition reads the
    // Environment, not the bound properties object. This runner now sets the property and
    // includes ProviderRegistryComposition, the seam that actually owns the registry.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(FashnConfig.class, ProviderRegistryComposition.class)
        .withPropertyValues("vault.fashn.api-key=" + FashnTestSupport.API_KEY)
        .withBean(ObjectStore.class, FashnTestSupport.FakeObjectStore::new)
        .withBean(VaultEnvironmentProperties.class, FashnConfigTest::properties);

    @Test
    void registersFashnWithoutNetworkCalls() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FashnMapper.class);
            assertThat(context).hasSingleBean(FashnClient.class);
            assertThat(context).hasSingleBean(FashnVtonProvider.class);
            assertThat(context).hasSingleBean(ProviderRegistry.class);
            ProviderRegistry registry = context.getBean(ProviderRegistry.class);
            assertThat(registry.resolve(new ProviderKey("fashn"))).isSameAs(context.getBean(FashnVtonProvider.class));
            assertThat(context.getBean(FashnTestSupport.FakeObjectStore.class).headCount).isZero();
            assertThat(context.getBean(FashnTestSupport.FakeObjectStore.class).readCount).isZero();
            assertThat(context.getBean(FashnTestSupport.FakeObjectStore.class).presignCount).isZero();
        });
    }

    @Test
    void selectedProviderIsFashnOnlyBecauseItIsTheSoleRegistration() {
        runner.run(context -> {
            ProviderRegistry registry = context.getBean(ProviderRegistry.class);
            assertThat(registry.selectedProviderKey()).isEqualTo(new ProviderKey("fashn"));
            assertThat(registry.selectedProvider()).isSameAs(registry.resolve(new ProviderKey("fashn")));
            assertThatExceptionOfType(UnknownProviderException.class)
                .isThrownBy(() -> registry.resolve(new ProviderKey("tryon")));
            assertThatExceptionOfType(UnknownProviderException.class)
                .isThrownBy(() -> registry.resolve(new ProviderKey("fashn-v1")));
        });
    }

    @Test
    void unknownProviderKeysDoNotFallBackToFashn() {
        runner.run(context -> {
            ProviderRegistry registry = context.getBean(ProviderRegistry.class);
            assertThatExceptionOfType(UnknownProviderException.class)
                .isThrownBy(() -> registry.resolve(new ProviderKey("missing")));
            assertThat(registry.resolve(new ProviderKey("fashn")).providerKey().value()).isEqualTo("fashn");
        });
    }

    /**
     * The other half of the t205 guarantee, and the one that matters for the owner decision: with
     * NO api-key property the provider beans must not exist at all, so the default provider is
     * honestly unavailable rather than silently selected to a vendor nobody approved. This is a
     * product behaviour, not a test convenience, so it is pinned here rather than assumed.
     */
    @Test
    void withoutAnApiKeyNoProviderIsRegisteredSoTheDefaultIsHonestlyUnavailable() {
        new ApplicationContextRunner()
            .withUserConfiguration(FashnConfig.class, ProviderRegistryComposition.class)
            .withBean(ObjectStore.class, FashnTestSupport.FakeObjectStore::new)
            .withBean(VaultEnvironmentProperties.class, FashnConfigTest::properties)
            .run(context -> {
                assertThat(context).hasNotFailed();
                // FactoryBean products resolvable by type; a null product is a NullBean.
                assertThat(context.getBeanProvider(ProviderRegistry.class).getIfAvailable()).isNull();
                assertThat(context).doesNotHaveBean(FashnVtonProvider.class);
                // The mapper is NOT gated: it is pure translation logic and safe to construct.
                assertThat(context).hasSingleBean(FashnMapper.class);
            });
    }

    private static VaultEnvironmentProperties properties() {
        return new VaultEnvironmentProperties(
            AppEnv.development,
            new VaultEnvironmentProperties.Db("jdbc:postgresql://127.0.0.1:5432/postgres", "postgres", "test"),
            new VaultEnvironmentProperties.Aws("us-east-1", "http://127.0.0.1:4566", "http://127.0.0.1:4566", "test", "test"),
            new VaultEnvironmentProperties.Sqs("http://127.0.0.1:4566/000000000000/q"),
            new VaultEnvironmentProperties.S3("avatar", "vault", "look", "temp"),
            new VaultEnvironmentProperties.Fashn(FashnTestSupport.API_KEY),
            new VaultEnvironmentProperties.Inferhub(null),
            new VaultEnvironmentProperties.Firebase("demo-ai-fashion-vault"),
            new VaultEnvironmentProperties.Otel("ai-fashion-vault-api", "", "", ""));
    }
}
