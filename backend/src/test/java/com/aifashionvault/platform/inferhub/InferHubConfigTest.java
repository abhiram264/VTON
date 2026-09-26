package com.aifashionvault.platform.inferhub;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.generation.vton.ProviderRegistry;
import com.aifashionvault.generation.vton.ProviderRegistryComposition;
import com.aifashionvault.platform.environment.AppEnv;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;
import com.aifashionvault.platform.fashn.FashnClient;
import com.aifashionvault.platform.fashn.FashnMapper;
import com.aifashionvault.platform.fashn.FashnVtonProvider;
import com.aifashionvault.platform.fashn.FashnConfig;
import com.aifashionvault.platform.objectstore.ClaimCommand;
import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.MediaDeclaration;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.platform.objectstore.UploadCapability;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;

class InferHubConfigTest {
    private static final String INFERHUB_KEY = "test-inferhub-key";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(InferHubConfig.class, FashnConfig.class, ProviderRegistryComposition.class)
        .withPropertyValues(
            "vault.inferhub.api-key=" + INFERHUB_KEY,
            "vault.fashn.api-key=test-fashn-key")
        .withBean(ObjectStore.class, NoopObjectStore::new)
        .withBean(VaultEnvironmentProperties.class, InferHubConfigTest::properties);

    @Test
    void registersInferHubBeansWhenApiKeyIsPresent() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(InferHubMapper.class);
            assertThat(context).hasSingleBean(InferHubClient.class);
            assertThat(context).hasSingleBean(InferHubVtonProvider.class);
            assertThat(context.getBean(InferHubVtonProvider.class).providerKey())
                .isEqualTo(new ProviderKey("inferhub"));
        });
    }

    @Test
    void withoutAnApiKeyNoAdapterBeanExistsButTheMapperIsUngated() {
        new ApplicationContextRunner()
            .withUserConfiguration(InferHubConfig.class)
            .withBean(ObjectStore.class, NoopObjectStore::new)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).doesNotHaveBean(InferHubVtonProvider.class);
                assertThat(context).doesNotHaveBean(InferHubClient.class);
                // The mapper is NOT gated: it is pure translation logic and safe to construct.
                assertThat(context).hasSingleBean(InferHubMapper.class);
            });
    }

    /**
     * t220 mandated cell: the `${VTON_IMAGE_API_KEY:}`/`${FASHN_API_KEY:}` defaults turn an
     * unset environment variable into a PRESENT-EMPTY property, which the old
     * {@code @ConditionalOnProperty} matched — half-creating beans whose client builders then
     * threw. {@code @ConditionalOnNonBlankApiKey} must reject present-empty exactly like
     * absent: context starts, no provider beans, no registry, mappers ungated.
     */
    @Test
    void presentEmptyKeysMeanNoProviderBeansAndNoRegistry() {
        new ApplicationContextRunner()
            .withUserConfiguration(InferHubConfig.class, FashnConfig.class, ProviderRegistryComposition.class)
            .withPropertyValues("vault.inferhub.api-key=", "vault.fashn.api-key=")
            .withBean(ObjectStore.class, NoopObjectStore::new)
            .withBean(VaultEnvironmentProperties.class, InferHubConfigTest::properties)
            .run(context -> {
                assertThat(context).hasNotFailed();
                // FactoryBean products resolvable by type; a null product is a NullBean.
                assertThat(context.getBeanProvider(ProviderRegistry.class).getIfAvailable()).isNull();
                assertThat(context).doesNotHaveBean(InferHubVtonProvider.class);
                assertThat(context).doesNotHaveBean(FashnVtonProvider.class);
                assertThat(context).doesNotHaveBean(InferHubClient.class);
                assertThat(context).doesNotHaveBean(FashnClient.class);
                assertThat(context).hasSingleBean(InferHubMapper.class);
                assertThat(context).hasSingleBean(FashnMapper.class);
            });
    }

    /**
     * t220 mandated cell: the composed registry exists with the InferHub provider selected
     * when InferHub is the only configured provider.
     */
    @Test
    void inferHubOnlySetupSelectsInferHub() {
        new ApplicationContextRunner()
            .withUserConfiguration(InferHubConfig.class, FashnConfig.class, ProviderRegistryComposition.class)
            .withPropertyValues("vault.inferhub.api-key=" + INFERHUB_KEY)
            .withBean(ObjectStore.class, NoopObjectStore::new)
            .withBean(VaultEnvironmentProperties.class, InferHubConfigTest::properties)
            .run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasSingleBean(ProviderRegistry.class);
                ProviderRegistry registry = context.getBean(ProviderRegistry.class);
                assertThat(registry.selectedProviderKey()).isEqualTo(new ProviderKey("inferhub"));
                assertThat(registry.resolve(new ProviderKey("inferhub")))
                    .isSameAs(context.getBean(InferHubVtonProvider.class));
                // FASHN is absent (blank key), so the registry is InferHub-only.
                assertThat(registry.providerKeys()).containsExactly(new ProviderKey("inferhub"));
            });
    }

    /**
     * t220 mandated cell: both keys set — BOTH providers registered, and the selection is
     * deterministic (forced-provider unset, no inferhub/fashn pin overrides, inferhub wins
     * by the frozen order) and NOT dependent on bean construction order. The two runs flip
     * the registration order of the user configurations to prove order-independence.
     */
    @Test
    void bothKeysSetRegistersBothAndSelectsDeterministically() {
        for (boolean inferHubFirst : new boolean[] {true, false}) {
            Class<?>[] configurationOrder = inferHubFirst
                ? new Class<?>[] {InferHubConfig.class, FashnConfig.class, ProviderRegistryComposition.class}
                : new Class<?>[] {FashnConfig.class, InferHubConfig.class, ProviderRegistryComposition.class};
            new ApplicationContextRunner()
                .withUserConfiguration(configurationOrder)
                .withPropertyValues(
                    "vault.inferhub.api-key=" + INFERHUB_KEY,
                    "vault.fashn.api-key=test-fashn-key")
                .withBean(ObjectStore.class, NoopObjectStore::new)
                .withBean(VaultEnvironmentProperties.class, InferHubConfigTest::properties)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ProviderRegistry.class);
                    ProviderRegistry registry = context.getBean(ProviderRegistry.class);
                    assertThat(registry.providerKeys())
                        .containsExactlyInAnyOrder(new ProviderKey("inferhub"), new ProviderKey("fashn"));
                    assertThat(registry.selectedProviderKey()).isEqualTo(new ProviderKey("inferhub"));
                    assertThat(registry.resolve(new ProviderKey("fashn")))
                        .isSameAs(context.getBean(FashnVtonProvider.class));
                });
        }
    }

    /**
     * t220 mandated cell: the provider-agnostic admission predicate replaces the
     * FASHN-specific one at the create path, so an InferHub-only setup must be admitted.
     */
    @Test
    void anyProviderConfiguredIsTrueForInferHubOnlyAndFalseWhenBothBlank() {
        assertThat(FashnConfig.anyProviderConfigured(properties())).isTrue();
        assertThat(FashnConfig.anyProviderConfigured(new VaultEnvironmentProperties(
            AppEnv.development, null, null, null, null,
            new VaultEnvironmentProperties.Fashn("   "),
            new VaultEnvironmentProperties.Inferhub(null), null, null))).isFalse();
        assertThat(FashnConfig.anyProviderConfigured(null)).isFalse();
        // FASHN-only keeps working through the same predicate.
        assertThat(FashnConfig.anyProviderConfigured(new VaultEnvironmentProperties(
            AppEnv.development, null, null, null, null,
            new VaultEnvironmentProperties.Fashn("test-fashn-key"),
            new VaultEnvironmentProperties.Inferhub(""), null, null))).isTrue();
    }

    @Test
    void fashnBeansAreUnchangedByTheInferHubConfig() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FashnMapper.class);
            assertThat(context).hasSingleBean(FashnClient.class);
            assertThat(context).hasSingleBean(FashnVtonProvider.class);
            // The composed registry selects inferhub with both keys set (deterministic order)
            // and still resolves the FASHN provider.
            assertThat(context).hasSingleBean(ProviderRegistry.class);
            ProviderRegistry registry = context.getBean(ProviderRegistry.class);
            assertThat(registry.selectedProviderKey()).isEqualTo(new ProviderKey("inferhub"));
            assertThat(registry.resolve(new ProviderKey("fashn")))
                .isSameAs(context.getBean(FashnVtonProvider.class));
        });
    }

    private static VaultEnvironmentProperties properties() {
        return new VaultEnvironmentProperties(
            AppEnv.development,
            new VaultEnvironmentProperties.Db("jdbc:postgresql://127.0.0.1:5432/postgres", "postgres", "test"),
            new VaultEnvironmentProperties.Aws("us-east-1", "http://127.0.0.1:4566", "http://127.0.0.1:4566", "test", "test"),
            new VaultEnvironmentProperties.Sqs("http://127.0.0.1:4566/000000000000/q"),
            new VaultEnvironmentProperties.S3("avatar", "vault", "look", "temp"),
            new VaultEnvironmentProperties.Fashn("test-fashn-key"),
            new VaultEnvironmentProperties.Inferhub(INFERHUB_KEY),
            new VaultEnvironmentProperties.Firebase("demo-ai-fashion-vault"),
            new VaultEnvironmentProperties.Otel("ai-fashion-vault-api", "", "", ""));
    }

    /**
     * Nothing in these context tests touches the store; every method fails loudly
     * so an unexpected call is visible instead of silently answered.
     */
    static final class NoopObjectStore implements ObjectStore {
        @Override
        public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ObjectMetadata head(ObjectRef object) {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void delete(ObjectRef object) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ClaimResult claim(ClaimCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void purgeUserObjects(UserId userId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void cleanupTemp() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PresignedAccess presignGet(ObjectRef object, Duration ttl) {
            throw new UnsupportedOperationException();
        }
    }
}
