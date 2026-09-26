package com.aifashionvault.platform.inferhub;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.generation.vton.ProviderRegistry;
import com.aifashionvault.generation.vton.ProviderRegistryComposition;
import com.aifashionvault.generation.vton.ProviderRoutingConfiguration;
import com.aifashionvault.generation.vton.ProviderRoutingProperties;
import com.aifashionvault.platform.environment.AppEnv;
import com.aifashionvault.platform.environment.VaultEnvironmentProperties;
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

/**
 * t281: pins the FULL provider-selection matrix as named cells (U56's matrix B, as landed
 * by U02's {@link ProviderRegistryComposition}). Four key postures (P1..P4) x two selection
 * states (S0 unset, S1 forced), plus the non-cell invariants.
 *
 * <p>Postures use the SHIPPED present-empty form ({@code vault.*.api-key=} resolves to "")
 * for the unset side: with {@code ${VTON_IMAGE_API_KEY:}} / {@code ${FASHN_API_KEY:}} an
 * unset environment variable yields a PRESENT-EMPTY property, which
 * {@code NonBlankApiKeyCondition} rejects — that is the path that actually ships, not an
 * absent property.
 *
 * <p>Every cell asserts, from the OBSERVED context: (a) the context started, (b)
 * {@link ProviderRegistry} bean presence and, when present, {@code selectedProviderKey()},
 * (c) the create-path admission predicate
 * {@link FashnConfig#anyProviderConfigured(VaultEnvironmentProperties)}.
 */
class InferHubProviderSelectionMatrixTest {
    private static final String INFERHUB_KEY = "test-inferhub-key";
    private static final String FASHN_KEY = "test-fashn-key";

    @Test
    void p1S0_bothKeysNoPin_registryPresentWinnerInferhub() {
        matrix(FASHN_KEY, INFERHUB_KEY, null).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ProviderRegistry.class);
            assertThat(context.getBean(ProviderRegistry.class).selectedProviderKey())
                .isEqualTo(new ProviderKey("inferhub"));
            assertThat(FashnConfig.anyProviderConfigured(context.getBean(VaultEnvironmentProperties.class)))
                .isTrue();
        });
    }

    @Test
    void p1S1_bothKeysPinFashn_winnerFashn() {
        matrix(FASHN_KEY, INFERHUB_KEY, "fashn").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ProviderRegistry.class);
            assertThat(context.getBean(ProviderRegistry.class).selectedProviderKey())
                .isEqualTo(new ProviderKey("fashn"));
            assertThat(FashnConfig.anyProviderConfigured(context.getBean(VaultEnvironmentProperties.class)))
                .isTrue();
        });
    }

    /**
     * P1S1 companion: with BOTH keys set both registered keys are pinnable, so
     * pin=inferhub must keep the winner at inferhub.
     */
    @Test
    void p1S1_bothKeysPinInferhub_winnerInferhub() {
        matrix(FASHN_KEY, INFERHUB_KEY, "inferhub").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ProviderRegistry.class);
            assertThat(context.getBean(ProviderRegistry.class).selectedProviderKey())
                .isEqualTo(new ProviderKey("inferhub"));
            assertThat(FashnConfig.anyProviderConfigured(context.getBean(VaultEnvironmentProperties.class)))
                .isTrue();
        });
    }

    @Test
    void p2S0_fashnOnlyNoPin_registryPresentWinnerFashn() {
        matrix(FASHN_KEY, "", null).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ProviderRegistry.class);
            assertThat(context.getBean(ProviderRegistry.class).selectedProviderKey())
                .isEqualTo(new ProviderKey("fashn"));
            assertThat(FashnConfig.anyProviderConfigured(context.getBean(VaultEnvironmentProperties.class)))
                .isTrue();
        });
    }

    /**
     * A pin naming an UNREGISTERED provider is silently ignored: no error, no boot
     * failure, and the registered provider still wins.
     */
    @Test
    void p2S1_fashnOnlyPinUnregisteredInferhub_silentlyIgnoredWinnerFashn() {
        matrix(FASHN_KEY, "", "inferhub").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ProviderRegistry.class);
            assertThat(context.getBean(ProviderRegistry.class).selectedProviderKey())
                .isEqualTo(new ProviderKey("fashn"));
            assertThat(FashnConfig.anyProviderConfigured(context.getBean(VaultEnvironmentProperties.class)))
                .isTrue();
        });
    }

    @Test
    void p3S0_inferhubOnlyNoPin_registryPresentWinnerInferhub() {
        matrix("", INFERHUB_KEY, null).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ProviderRegistry.class);
            assertThat(context.getBean(ProviderRegistry.class).selectedProviderKey())
                .isEqualTo(new ProviderKey("inferhub"));
            assertThat(FashnConfig.anyProviderConfigured(context.getBean(VaultEnvironmentProperties.class)))
                .isTrue();
        });
    }

    @Test
    void p3S1_inferhubOnlyPinUnregisteredFashn_silentlyIgnoredWinnerInferhub() {
        matrix("", INFERHUB_KEY, "fashn").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ProviderRegistry.class);
            assertThat(context.getBean(ProviderRegistry.class).selectedProviderKey())
                .isEqualTo(new ProviderKey("inferhub"));
            assertThat(FashnConfig.anyProviderConfigured(context.getBean(VaultEnvironmentProperties.class)))
                .isTrue();
        });
    }

    /**
     * The decisive P4 cell: NEITHER key (both present-empty) — the context STARTS, no
     * {@link ProviderRegistry} bean exists at all (honest-unavailable), and the
     * create-path admission predicate is FALSE.
     */
    @Test
    void p4S0_neitherKey_contextStarts_registryAbsent_predicateFalse() {
        matrix("", "", null).run(context -> {
            assertThat(context).hasNotFailed();
            // FactoryBean product of a null registry is a NullBean: resolvable by type, no bean.
            assertThat(context.getBeanProvider(ProviderRegistry.class).getIfAvailable()).isNull();
            assertThat(FashnConfig.anyProviderConfigured(context.getBean(VaultEnvironmentProperties.class)))
                .isFalse();
        });
    }

    /**
     * P4S1: a pin with no registered provider is inert — the context still starts and the
     * registry is still absent.
     */
    @Test
    void p4S1_neitherKeyWithPin_contextStarts_registryAbsent_pinInert() {
        matrix("", "", "inferhub").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanProvider(ProviderRegistry.class).getIfAvailable()).isNull();
            assertThat(FashnConfig.anyProviderConfigured(context.getBean(VaultEnvironmentProperties.class)))
                .isFalse();
        });
    }

    /**
     * Non-cell invariant (U56 cell A7): a MALFORMED forced-provider value fails
     * binding/startup — the pre-existing {@code ProviderRoutingProperties} setter
     * validation, provider-independent. Uses the real
     * {@link ProviderRoutingConfiguration} so the value goes through actual property
     * binding, in the both-keys posture.
     */
    @Test
    void invariant_malformedForcedProvider_failsStartup() {
        new ApplicationContextRunner()
            .withUserConfiguration(InferHubConfig.class, FashnConfig.class,
                ProviderRegistryComposition.class, ProviderRoutingConfiguration.class)
            .withPropertyValues(
                "vault.fashn.api-key=" + FASHN_KEY,
                "vault.inferhub.api-key=" + INFERHUB_KEY,
                "vault.generation.routing.forced-provider=Try On!")
            .withBean(ObjectStore.class, NoopObjectStore::new)
            .withBean(VaultEnvironmentProperties.class, () -> properties(FASHN_KEY, INFERHUB_KEY))
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("forcedProvider must be a valid provider key");
            });
    }

    /**
     * Non-cell invariant: with BOTH keys set and NO pin the winner is inferhub by the
     * composition's EXPLICIT deterministic choice — flipping the user-configuration
     * registration order must not move it.
     */
    @Test
    void invariant_bothKeysNoPin_winnerIsExplicitNotBeanOrder() {
        for (boolean inferHubFirst : new boolean[] {true, false}) {
            Class<?>[] configurationOrder = inferHubFirst
                ? new Class<?>[] {InferHubConfig.class, FashnConfig.class, ProviderRegistryComposition.class}
                : new Class<?>[] {FashnConfig.class, InferHubConfig.class, ProviderRegistryComposition.class};
            new ApplicationContextRunner()
                .withUserConfiguration(configurationOrder)
                .withPropertyValues(
                    "vault.fashn.api-key=" + FASHN_KEY,
                    "vault.inferhub.api-key=" + INFERHUB_KEY)
                .withBean(ObjectStore.class, NoopObjectStore::new)
                .withBean(VaultEnvironmentProperties.class, () -> properties(FASHN_KEY, INFERHUB_KEY))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ProviderRegistry.class);
                    assertThat(context.getBean(ProviderRegistry.class).selectedProviderKey())
                        .isEqualTo(new ProviderKey("inferhub"));
                });
        }
    }

    /**
     * Non-cell invariant: the P4 startup assertion is {@code hasNotFailed()} explicitly, so
     * a fail-loud regression from the trailing-colon/blank-key interaction (a placeholder
     * that fails to resolve, or a blank key whose client builder throws) is caught rather
     * than assumed away. Uses the whitespace-blank form of both keys as well as
     * present-empty.
     */
    @Test
    void invariant_neitherKeyWhitespaceBlank_startsClean() {
        matrix("   ", "   ", null).run(context -> assertThat(context).hasNotFailed());
    }

    /**
     * One runner shape for the whole matrix: explicit properties (present-empty "" is the
     * shipped unset form), the same fixture trio the sibling config tests use, and an
     * optional forced-provider pin registered as a plain bean (no yaml binding, so the
     * routing BeanPostProcessor stays out of these composition-seam cells).
     */
    private ApplicationContextRunner matrix(String fashnProperty, String inferhubProperty, String forcedProvider) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(InferHubConfig.class, FashnConfig.class, ProviderRegistryComposition.class)
            .withPropertyValues(
                "vault.fashn.api-key=" + fashnProperty,
                "vault.inferhub.api-key=" + inferhubProperty)
            .withBean(ObjectStore.class, NoopObjectStore::new)
            .withBean(VaultEnvironmentProperties.class, () -> properties(fashnProperty, inferhubProperty));
        if (forcedProvider == null) {
            return runner;
        }
        return runner.withBean(ProviderRoutingProperties.class, () -> {
            ProviderRoutingProperties properties = new ProviderRoutingProperties();
            properties.setForcedProvider(forcedProvider);
            return properties;
        });
    }

    private static VaultEnvironmentProperties properties(String fashnKey, String inferhubKey) {
        return new VaultEnvironmentProperties(
            AppEnv.development,
            new VaultEnvironmentProperties.Db("jdbc:postgresql://127.0.0.1:5432/postgres", "postgres", "test"),
            new VaultEnvironmentProperties.Aws("us-east-1", "http://127.0.0.1:4566", "http://127.0.0.1:4566", "test", "test"),
            new VaultEnvironmentProperties.Sqs("http://127.0.0.1:4566/000000000000/q"),
            new VaultEnvironmentProperties.S3("avatar", "vault", "look", "temp"),
            new VaultEnvironmentProperties.Fashn(fashnKey),
            new VaultEnvironmentProperties.Inferhub(inferhubKey),
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
