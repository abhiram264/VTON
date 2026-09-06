package com.aifashionvault.generation.vton;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Registry decorator and context wiring for slice-74 U3.
 *
 * <p>Group A: POJO decorator semantics without Spring — delegation of {@code resolve} (direct,
 * alias, unknown), delegation of {@code providerKeys}, routed {@code selectedProviderKey} with
 * exactly one emitted route, fail-open to the bootstrap key on engine failure, resilience to a
 * broken metrics adapter, and byte-for-byte passthrough (OFF mode, AUTO single-provider) through
 * the real engine.
 *
 * <p>Group B: {@link ApplicationContextRunner} wiring with plain fakes — no Postgres, no
 * LocalStack, no network. The test registry configuration is registered BEFORE the
 * configuration under test so its recording {@link ProviderRoutingMetrics} bean is visible to
 * the {@code @ConditionalOnMissingBean} no-op condition.
 */
class ProviderRouterRegistryTest {

    private static final Instant AS_OF = Instant.parse("2026-09-01T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(AS_OF, ZoneOffset.UTC);
    private static final ProviderKey ALPHA = new ProviderKey("alpha");
    private static final ProviderKey BRAVO = new ProviderKey("bravo");
    private static final ProviderKey ALIAS = new ProviderKey("alpha-alias");
    private static final ProviderKey UNKNOWN = new ProviderKey("missing");

    private static final Comparator<ProviderPerformanceRow> SNAPSHOT_ORDER = Comparator
        .comparing(ProviderPerformanceRow::providerKey)
        .thenComparing(ProviderPerformanceRow::category)
        .thenComparing(ProviderPerformanceRow::modelOrMode)
        .thenComparing(ProviderPerformanceRow::currency, Comparator.nullsFirst(Comparator.naturalOrder()));

    // ------------------------------------------------------------------
    // Group A: POJO decorator semantics (no Spring)
    // ------------------------------------------------------------------

    @Test
    void constructorRejectsNullCollaborators() {
        ProviderRegistry base = twoProviderRegistry();
        ProviderRouter router = ScriptableRouter.returning(routeTo(BRAVO));
        RecordingMetrics metrics = new RecordingMetrics();
        ProviderRoutingProperties properties = new ProviderRoutingProperties();
        assertThatIllegalArgumentException().isThrownBy(() -> new RoutingProviderRegistry(null, router, metrics, properties));
        assertThatIllegalArgumentException().isThrownBy(() -> new RoutingProviderRegistry(base, null, metrics, properties));
        assertThatIllegalArgumentException().isThrownBy(() -> new RoutingProviderRegistry(base, router, null, properties));
        assertThatIllegalArgumentException().isThrownBy(() -> new RoutingProviderRegistry(base, router, metrics, null));
    }

    @Test
    void resolveDelegatesDirectKeysUnchanged() {
        RoutingProviderRegistry decorator = decorator(ScriptableRouter.returning(routeTo(BRAVO)), new RecordingMetrics());
        assertThat(decorator.resolve(ALPHA).providerKey()).isEqualTo(ALPHA);
        assertThat(decorator.resolve(BRAVO).providerKey()).isEqualTo(BRAVO);
    }

    @Test
    void resolveDelegatesAliasKeysUnchanged() {
        RoutingProviderRegistry decorator = decorator(ScriptableRouter.returning(routeTo(BRAVO)), new RecordingMetrics());
        assertThat(decorator.resolve(ALIAS).providerKey()).isEqualTo(ALPHA);
    }

    @Test
    void resolvePropagatesUnknownProviderExceptionUnchanged() {
        RoutingProviderRegistry decorator = decorator(ScriptableRouter.returning(routeTo(BRAVO)), new RecordingMetrics());
        assertThatThrownBy(() -> decorator.resolve(UNKNOWN))
            .isInstanceOf(UnknownProviderException.class)
            .hasMessageContaining("missing");
    }

    @Test
    void providerKeysDelegates() {
        ProviderRegistry base = twoProviderRegistry();
        RoutingProviderRegistry decorator = new RoutingProviderRegistry(base,
            ScriptableRouter.returning(routeTo(BRAVO)), new RecordingMetrics(), new ProviderRoutingProperties());
        assertThat(decorator.providerKeys()).containsExactlyElementsOf(base.providerKeys());
    }

    @Test
    void selectedProviderKeyReturnsRouterRouteAndEmitsExactlyThatRoute() {
        ProviderRoute scripted = routeTo(BRAVO);
        RecordingMetrics metrics = new RecordingMetrics();
        RoutingProviderRegistry decorator = decorator(ScriptableRouter.returning(scripted), metrics);

        assertThat(decorator.selectedProviderKey()).isEqualTo(BRAVO);
        assertThat(metrics.routes).containsExactly(scripted);
    }

    @Test
    void routerFailureFailsOpenToBootstrapKeyWithDefaultedEvidenceUnavailable() {
        RecordingMetrics metrics = new RecordingMetrics();
        RoutingProviderRegistry decorator = decorator(ScriptableRouter.failing(new IllegalStateException("store exploded")), metrics);

        assertThat(decorator.selectedProviderKey()).isEqualTo(ALPHA);
        assertThat(metrics.routes).hasSize(1);
        ProviderRoute fallback = metrics.routes.get(0);
        assertThat(fallback.providerKey()).isEqualTo(ALPHA);
        assertThat(fallback.modelOrMode()).isNull();
        assertThat(fallback.routingPolicyVersion()).isEqualTo("routing-v1");
        assertThat(fallback.objective()).isEqualTo(RoutingObjective.QUALITY);
        assertThat(fallback.outcome()).isEqualTo(RouteOutcome.DEFAULTED_EVIDENCE_UNAVAILABLE);
        assertThat(fallback.candidateCount()).isEqualTo(2);
        assertThat(fallback.axisScores()).isEmpty();
    }

    @Test
    void brokenMetricsAdapterDoesNotBreakSelection() {
        RoutingProviderRegistry decorator = decorator(ScriptableRouter.returning(routeTo(BRAVO)), new ThrowingMetrics());
        assertThat(decorator.selectedProviderKey()).isEqualTo(BRAVO);
    }

    @Test
    void offModePassesThroughByteForByteViaRealEngine() {
        ProviderRoutingProperties properties = new ProviderRoutingProperties();
        properties.setMode("off");
        CategoryAwareProviderRouter engine = new CategoryAwareProviderRouter(twoProviderRegistry(), null, null,
            new NoopProviderRoutingConstraints(), properties, FIXED_CLOCK);
        RecordingMetrics metrics = new RecordingMetrics();
        RoutingProviderRegistry decorator = new RoutingProviderRegistry(twoProviderRegistry(), engine, metrics, properties);

        assertThat(decorator.selectedProviderKey()).isEqualTo(ALPHA);
        assertThat(metrics.routes).hasSize(1);
        ProviderRoute route = metrics.routes.get(0);
        assertThat(route.providerKey()).isEqualTo(ALPHA);
        assertThat(route.outcome()).isEqualTo(RouteOutcome.PASSTHROUGH_DISABLED);
        assertThat(route.modelOrMode()).isNull();
        assertThat(route.axisScores()).isEmpty();
        assertThat(route.candidateCount()).isEqualTo(2);
        assertThat(route.objective()).isEqualTo(RoutingObjective.QUALITY);
        assertThat(route.routingPolicyVersion()).isEqualTo("routing-v1");
    }

    @Test
    void autoSingleProviderPassesThroughByteForByteViaRealEngine() {
        ProviderRegistry single = new DefaultProviderRegistry(List.of(new FakeVtonProvider(ALPHA)), ALPHA, Map.of());
        ProviderRoutingProperties properties = new ProviderRoutingProperties();
        CategoryAwareProviderRouter engine = new CategoryAwareProviderRouter(single, null, null,
            new NoopProviderRoutingConstraints(), properties, FIXED_CLOCK);
        RecordingMetrics metrics = new RecordingMetrics();
        RoutingProviderRegistry decorator = new RoutingProviderRegistry(single, engine, metrics, properties);

        assertThat(decorator.selectedProviderKey()).isEqualTo(ALPHA);
        assertThat(metrics.routes).hasSize(1);
        ProviderRoute route = metrics.routes.get(0);
        assertThat(route.outcome()).isEqualTo(RouteOutcome.PASSTHROUGH_SINGLE_PROVIDER);
        assertThat(route.providerKey()).isEqualTo(ALPHA);
        assertThat(route.modelOrMode()).isNull();
        assertThat(route.axisScores()).isEmpty();
        assertThat(route.candidateCount()).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // Group B: ApplicationContextRunner wiring (plain fakes, no host services)
    // ------------------------------------------------------------------

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(TestProviderRegistryConfig.class, ProviderRoutingConfiguration.class);

    @Test
    void wrapsRegistryInPlaceAndDefaultsWhenEvidenceStoreIsAbsent() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBeanNamesForType(ProviderRegistry.class)).hasSize(1);
            ProviderRegistry registry = context.getBean(ProviderRegistry.class);
            assertThat(registry).isInstanceOf(RoutingProviderRegistry.class);
            assertThat(registry.resolve(ALPHA).providerKey()).isEqualTo(ALPHA);
            assertThat(registry.selectedProviderKey()).isEqualTo(ALPHA);
            RecordingMetrics metrics = context.getBean(RecordingMetrics.class);
            assertThat(metrics.routes).hasSize(1);
            assertThat(metrics.routes.get(0).outcome()).isEqualTo(RouteOutcome.DEFAULTED_INSUFFICIENT_EVIDENCE);
            assertThat(metrics.routes.get(0).providerKey()).isEqualTo(ALPHA);
        });
    }

    @Test
    void routesToBetterProviderWhenEvidenceIsSufficient() {
        ProviderPerformanceSnapshot snapshot = snapshotOf(
            row("alpha", "m-alpha", "tops", bd("0.80"), bd("0.85"), 100L, bd("0.10"), bd("1.00"), 40, 30),
            row("bravo", "m-bravo", "tops", bd("0.95"), bd("0.90"), 120L, bd("0.05"), bd("1.10"), 40, 32));
        runner.withBean(ProviderPerformanceStore.class, () -> new FixedStore(snapshot)).run(context -> {
            assertThat(context).hasNotFailed();
            ProviderRegistry registry = context.getBean(ProviderRegistry.class);
            assertThat(registry.selectedProviderKey()).isEqualTo(BRAVO);
            RecordingMetrics metrics = context.getBean(RecordingMetrics.class);
            assertThat(metrics.routes).hasSize(1);
            assertThat(metrics.routes.get(0).outcome()).isEqualTo(RouteOutcome.SELECTED);
            assertThat(metrics.routes.get(0).providerKey()).isEqualTo(BRAVO);
        });
    }

    @Test
    void offModeDisablesRoutingInContext() {
        runner.withPropertyValues("vault.generation.routing.mode=off").run(context -> {
            assertThat(context).hasNotFailed();
            ProviderRegistry registry = context.getBean(ProviderRegistry.class);
            assertThat(registry.selectedProviderKey()).isEqualTo(ALPHA);
            RecordingMetrics metrics = context.getBean(RecordingMetrics.class);
            assertThat(metrics.routes).hasSize(1);
            ProviderRoute route = metrics.routes.get(0);
            assertThat(route.outcome()).isEqualTo(RouteOutcome.PASSTHROUGH_DISABLED);
            assertThat(route.providerKey()).isEqualTo(ALPHA);
            assertThat(route.modelOrMode()).isNull();
            assertThat(route.axisScores()).isEmpty();
        });
    }

    @Test
    void forcedProviderPinOverridesEvidenceOrdering() {
        runner.withPropertyValues("vault.generation.routing.forced-provider=bravo").run(context -> {
            assertThat(context).hasNotFailed();
            ProviderRegistry registry = context.getBean(ProviderRegistry.class);
            assertThat(registry.selectedProviderKey()).isEqualTo(BRAVO);
            RecordingMetrics metrics = context.getBean(RecordingMetrics.class);
            assertThat(metrics.routes).hasSize(1);
            assertThat(metrics.routes.get(0).outcome()).isEqualTo(RouteOutcome.DEFAULTED_FORCED);
            assertThat(metrics.routes.get(0).providerKey()).isEqualTo(BRAVO);
        });
    }

    @Test
    void malformedForcedProviderFailsContextBindingFast() {
        runner.withPropertyValues("vault.generation.routing.forced-provider=BAD KEY!").run(context -> {
            assertThat(context).hasFailed();
            assertThat(causeChain(context.getStartupFailure()))
                .anyMatch(IllegalArgumentException.class::isInstance);
        });
    }

    // ------------------------------------------------------------------
    // Test configuration and fakes
    // ------------------------------------------------------------------

    @Configuration(proxyBeanMethods = false)
    static class TestProviderRegistryConfig {

        @Bean
        ProviderRegistry twoProviderRegistry() {
            FakeVtonProvider alpha = new FakeVtonProvider(ALPHA);
            FakeVtonProvider bravo = new FakeVtonProvider(BRAVO);
            return new DefaultProviderRegistry(List.of(alpha, bravo), ALPHA, Map.of());
        }

        @Bean
        RecordingMetrics recordingRoutingMetrics() {
            return new RecordingMetrics();
        }
    }

    private static RoutingProviderRegistry decorator(ProviderRouter router, ProviderRoutingMetrics metrics) {
        return new RoutingProviderRegistry(twoProviderRegistry(), router, metrics, new ProviderRoutingProperties());
    }

    private static DefaultProviderRegistry twoProviderRegistry() {
        return new DefaultProviderRegistry(
            List.of(new FakeVtonProvider(ALPHA), new FakeVtonProvider(BRAVO)), ALPHA, Map.of(ALIAS, ALPHA));
    }

    private static ProviderRoute routeTo(ProviderKey key) {
        return new ProviderRoute(key, "m-model", "routing-v1", RoutingObjective.QUALITY, RouteOutcome.SELECTED, 2, Map.of());
    }

    private static List<Throwable> causeChain(Throwable failure) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable current = failure; current != null && !chain.contains(current); current = current.getCause()) {
            chain.add(current);
        }
        return chain;
    }

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static ProviderPerformanceSnapshot snapshotOf(ProviderPerformanceRow... rows) {
        List<ProviderPerformanceRow> sorted = Arrays.stream(rows).sorted(SNAPSHOT_ORDER).toList();
        return new ProviderPerformanceSnapshot(sorted, AS_OF);
    }

    private static ProviderPerformanceRow row(
        String provider, String model, String category,
        BigDecimal fidelity, BigDecimal qualityPassRate, Long meanLatencyMs,
        BigDecimal failRate, BigDecimal costPerSuccess,
        long sampleCount, long qualityApprovedCount) {
        return new ProviderPerformanceRow(provider, model, category, fidelity, qualityPassRate, meanLatencyMs,
            failRate, costPerSuccess, "USD", sampleCount, sampleCount, qualityApprovedCount);
    }

    private static final class ScriptableRouter implements ProviderRouter {
        private final ProviderRoute result;
        private final RuntimeException failure;

        private ScriptableRouter(ProviderRoute result, RuntimeException failure) {
            this.result = result;
            this.failure = failure;
        }

        private static ScriptableRouter returning(ProviderRoute route) {
            return new ScriptableRouter(route, null);
        }

        private static ScriptableRouter failing(RuntimeException failure) {
            return new ScriptableRouter(null, failure);
        }

        @Override public ProviderRoute route(RoutingRequest request) {
            if (failure != null) throw failure;
            return result;
        }
    }

    private static final class RecordingMetrics implements ProviderRoutingMetrics {
        private final List<ProviderRoute> routes = new ArrayList<>();

        @Override public void routeSelected(ProviderRoute route) {
            routes.add(route);
        }
    }

    private static final class ThrowingMetrics implements ProviderRoutingMetrics {
        @Override public void routeSelected(ProviderRoute route) {
            throw new IllegalStateException("metrics adapter down");
        }
    }

    private static final class FixedStore implements ProviderPerformanceStore {
        private final ProviderPerformanceSnapshot snapshot;

        private FixedStore(ProviderPerformanceSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override public ProviderPerformanceSnapshot snapshot(ProviderPerformanceQuery query) { return snapshot; }
        @Override public void recompute(Instant fromInclusive, Instant toExclusive) { }
    }

    private static final class FakeVtonProvider implements VtonProvider {
        private final ProviderKey key;
        private final ProviderCapabilities capabilities;

        private FakeVtonProvider(ProviderKey key) {
            this.key = key;
            this.capabilities = new ProviderCapabilities(
                Map.of(new VtonMode("try-on"), new ModeCapabilities(Set.of(new ItemCategory("tops")), 1, false, InputSizeLimit.unknown())),
                Set.of(InputTransferMethod.BOUNDED_INLINE_DATA),
                Set.of(OutputDeliveryMethod.BOUNDED_INLINE_DATA),
                SubmissionReplaySafety.SAME_KEY_SAFE);
        }

        @Override public ProviderKey providerKey() { return key; }
        @Override public ProviderCapabilities capabilities() { return capabilities; }
        @Override public GenerationSubmission submit(VtonRequest request, SubmissionKey submissionKey) {
            throw new UnsupportedOperationException("routing tests never submit");
        }
        @Override public StatusQueryResult getStatus(ProviderJobId providerJobId) {
            throw new UnsupportedOperationException("routing tests never poll");
        }
        @Override public ProviderMediaSource openOutput(ProviderOutputRef outputRef) {
            throw new UnsupportedOperationException("routing tests never read output");
        }
    }
}
