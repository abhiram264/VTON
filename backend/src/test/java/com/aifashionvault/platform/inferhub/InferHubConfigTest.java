package com.aifashionvault.platform.inferhub;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.generation.vton.ProviderRegistry;
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
        .withUserConfiguration(InferHubConfig.class, FashnConfig.class)
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

    @Test
    void fashnBeansAreUnchangedByTheInferHubConfig() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(FashnMapper.class);
            assertThat(context).hasSingleBean(FashnClient.class);
            assertThat(context).hasSingleBean(FashnVtonProvider.class);
            // FashnConfig's registry still selects FASHN and resolves its own provider.
            assertThat(context).hasSingleBean(ProviderRegistry.class);
            ProviderRegistry registry = context.getBean(ProviderRegistry.class);
            assertThat(registry.selectedProviderKey()).isEqualTo(new ProviderKey("fashn"));
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
