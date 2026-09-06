package com.aifashionvault.generation.vton;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.ProviderHealthGate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Deterministic matrix for the category-aware routing engine: eligibility gates, min-max
 * normalization, sufficiency, cheapest-tier constraint, scoped/global aggregation, forced pin,
 * tie-breaks, and thread safety. Plain fakes, fixed clock, no Spring context, no host
 * services.
 */
class CategoryAwareProviderRouterTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-09-01T00:00:00Z"), ZoneOffset.UTC);
    private static final VtonMode TRY_ON = new VtonMode("try-on");
    private static final VtonMode VIDEO = new VtonMode("video");
    private static final ItemCategory ETHNIC = new ItemCategory("ethnic");
    private static final ItemCategory TOPS = new ItemCategory("tops");
    private static final ItemCategory SHOES = new ItemCategory("shoes");
    private static final BigDecimal LOW_FAILURE = new BigDecimal("0.10");

    private static final Comparator<ProviderPerformanceRow> SNAPSHOT_ORDER = Comparator
        .comparing(ProviderPerformanceRow::providerKey)
        .thenComparing(ProviderPerformanceRow::category)
        .thenComparing(ProviderPerformanceRow::modelOrMode)
        .thenComparing(ProviderPerformanceRow::currency, Comparator.nullsFirst(Comparator.naturalOrder()));

    // ------------------------------------------------------------------
    // Scoped quality routing (spec test 1)
    // ------------------------------------------------------------------

    @Test
    void scopedQualityRoutesToHigherFidelityProvider() {
        DefaultProviderRegistry registry = registry(provider("alpha"), provider("bravo"));
        ProviderPerformanceSnapshot snapshot = snapshotOf(
            row("alpha", "m-alpha", "ethnic", bd("0.80"), bd("0.85"), 100L, LOW_FAILURE, bd("1.00"), 40, 30),
            row("bravo", "m-bravo", "ethnic", bd("0.95"), bd("0.90"), 120L, bd("0.05"), bd("1.10"), 40, 32));
        CategoryAwareProviderRouter engine = engine(registry, new FixedStore(snapshot));

        ProviderRoute route = engine.route(new RoutingRequest(TRY_ON, List.of(ETHNIC), RoutingObjective.QUALITY));

        assertThat(route.providerKey().value()).isEqualTo("bravo");
        assertThat(route.modelOrMode()).isEqualTo("m-bravo");
        assertThat(route.outcome()).isEqualTo(RouteOutcome.SELECTED);
        assertThat(route.objective()).isEqualTo(RoutingObjective.QUALITY);
        assertThat(route.routingPolicyVersion()).isEqualTo("routing-v1");
        assertThat(route.candidateCount()).isEqualTo(2);
        assertThat(route.axisScores())
            .containsEntry("quality", bd("1.000000"))
            .containsEntry("cost", bd("0.000000"))
            .containsEntry("latency", bd("0.000000"));
    }

    // ------------------------------------------------------------------
    // Mode gates (spec tests 3, 10, 12 passthrough facets)
    // ------------------------------------------------------------------

    @Test
    void offModeBypassesRoutingEvenWithFullEvidence() {
        DefaultProviderRegistry registry = registry(provider("alpha"), provider("bravo"));
        ProviderPerformanceSnapshot snapshot = snapshotOf(
            row("alpha", "m-alpha", "ethnic", bd("0.80"), bd("0.85"), 100L, LOW_FAILURE, bd("1.00"), 40, 30),
            row("bravo", "m-bravo", "ethnic", bd("0.95"), bd("0.90"), 120L, bd("0.05"), bd("1.10"), 40, 32));
        ProviderRoutingProperties properties = new ProviderRoutingProperties();
        properties.setMode("off");
        CategoryAwareProviderRouter engine = engine(registry, new FixedStore(snapshot), properties);

        ProviderRoute route = engine.route(new RoutingRequest(null, List.of(), RoutingObjective.QUALITY));

        assertThat(route.providerKey().value()).isEqualTo("alpha");
        assertThat(route.outcome()).isEqualTo(RouteOutcome.PASSTHROUGH_DISABLED);
        assertThat(route.modelOrMode()).isNull();
        assertThat(route.axisScores()).isEmpty();
    }

    @Test
    void autoModePassesThroughWithSingleProvider() {
        DefaultProviderRegistry registry = registry(provider("alpha"));
        ProviderPerformanceSnapshot snapshot = snapshotOf(
            row("alpha", "m-alpha", "ethnic", bd("0.80"), bd("0.85"), 100L, LOW_FAILURE, bd("1.00"), 40, 30));
        CategoryAwareProviderRouter engine = engine(registry, new FixedStore(snapshot));

        ProviderRoute route = engine.route(new RoutingRequest(null, List.of(), RoutingObjective.QUALITY));

        assertThat(route.providerKey().value()).isEqualTo("alpha");
        assertThat(route.outcome()).isEqualTo(RouteOutcome.PASSTHROUGH_SINGLE_PROVIDER);
        assertThat(route.candidateCount()).isEqualTo(1);
    }

    @Test
    void onModeRoutesEvenWithSingleCandidate() {
        DefaultProviderRegistry registry = registry(provider("alpha"));
        ProviderPerformanceSnapshot snapshot = snapshotOf(
            row("alpha", "m-alpha", "ethnic", bd("0.80"), bd("0.85"), 100L, LOW_FAILURE, bd("1.00"), 40, 30));
        ProviderRoutingProperties properties = new ProviderRoutingProperties();
        properties.setMode("on");
        CategoryAwareProviderRouter engine = engine(registry, new FixedStore(snapshot), properties);

        ProviderRoute route = engine.route(new RoutingRequest(null, List.of(), RoutingObjective.QUALITY));

        assertThat(route.providerKey().value()).isEqualTo("alpha");
        assertThat(route.outcome()).isEqualTo(RouteOutcome.SELECTED);
        assertThat(route.modelOrMode()).isEqualTo("m-alpha");
    }

    // ------------------------------------------------------------------
    // Reliability gate (spec test 4)
    // ------------------------------------------------------------------

    @Test
    void highFailureRateProviderIsExcluded() {
        DefaultProviderRegistry registry = registry(provider("alpha"), provider("bravo"));
        ProviderPerformanceSnapshot snapshot = snapshotOf(
            row("alpha", "m-alpha", "ethnic", bd("0.70"), bd("0.80"), 100L, bd("0.20"), bd("1.00"), 40, 30),
            row("bravo", "m-bravo", "ethnic", bd("0.98"), bd("0.90"), 120L, bd("0.60"), bd("1.10"), 40, 32));
        CategoryAwareProviderRouter engine = engine(registry, new FixedStore(snapshot));

        ProviderRoute route = engine.route(new RoutingRequest(TRY_ON, List.of(ETHNIC), RoutingObjective.QUALITY));

        assertThat(route.providerKey().value()).isEqualTo("alpha");
        assertThat(route.outcome()).isEqualTo(RouteOutcome.SELECTED);
    }

    // ------------------------------------------------------------------
    // Sufficiency and no-eligible defaults (spec tests 5, 6)
    // ------------------------------------------------------------------

    @Test
    void insufficientEvidenceDefaultsToBootstrap() {
        DefaultProviderRegistry registry = registry(provider("alpha"), provider("bravo"));
        ProviderPerformanceSnapshot snapshot = snapshotOf(
            row("alpha", "m-alpha", "ethnic", bd("0.80"), bd("0.85"), 100L, LOW_FAILURE, bd("1.00"), 20, 15),
            row("bravo", "m-bravo", "ethnic", bd("0.95"), bd("0.90"), 120L, bd("0.05"), bd("1.10"), 20, 16));
        CategoryAwareProviderRouter engine = engine(registry, new FixedStore(snapshot));

        ProviderRoute route = engine.route(new RoutingRequest(null, List.of(), RoutingObjective.QUALITY));

        assertThat(route.providerKey().value()).isEqualTo("alpha");
        assertThat(route.outcome()).isEqualTo(RouteOutcome.DEFAULTED_INSUFFICIENT_EVIDENCE);
        assertThat(route.modelOrMode()).isNull();
        assertThat(route.axisScores()).isEmpty();
    }

    @Test
    void healthGateBlockingAllCandidatesDefaultsWithoutThrowing() {
        DefaultProviderRegistry registry = registry(provider("alpha"), provider("bravo"));
        ProviderPerformanceSnapshot snapshot = snapshotOf(
            row("alpha", "m-alpha", "ethnic", bd("0.80"), bd("0.85"), 100L, LOW_FAILURE, bd("1.00"), 40, 30),
            row("bravo", "m-bravo", "ethnic", bd("0.95"), bd("0.90"), 120L, bd("0.05"), bd("1.10"), 40, 32));
        CategoryAwareProviderRouter engine = engine(registry, new FixedStore(snapshot), new FixedHealthGate(Set.of()));

        ProviderRoute route = engine.route(new RoutingRequest(TRY_ON, List.of(ETHNIC), RoutingObjective.QUALITY));

        assertThat(route.providerKey().value()).isEqualTo("alpha");
        assertThat(route.outcome()).isEqualTo(RouteOutcome.DEFAULTED_NO_ELIGIBLE);
        assertThat(route.axisScores()).isEmpty();
    }

    @Test
    void storeFailuresPropagateFromTheEngine() {
        DefaultProviderRegistry registry = registry(provider("alpha"), provider("bravo"));
        CategoryAwareProviderRouter engine = engine(registry, new ThrowingStore());

        assertThatThrownBy(() -> engine.route(new RoutingRequest(null, List.of(), RoutingObjective.QUALITY)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("evidence unavailable");
    }

    // ------------------------------------------------------------------
    // Forced pin (spec test 8)
    // ------------------------------------------------------------------

    @Test
    void registeredForcedProviderWinsWithDefaultedForced() {
        DefaultProviderRegistry registry = registry(provider("alpha"), provider("bravo"));
        ProviderRoutingProperties properties = new ProviderRoutingProperties();
        properties.setForcedProvider("bravo");
        CategoryAwareProviderRouter engine = engine(registry, new FixedStore(snapshotOf()), properties);

        ProviderRoute route = engine.route(new RoutingRequest(null, List.of(), RoutingObjective.QUALITY));

        assertThat(route.providerKey().value()).isEqualTo("bravo");
        assertThat(route.outcome()).isEqualTo(RouteOutcome.DEFAULTED_FORCED);
        assertThat(route.modelOrMode()).isNull();
        assertThat(route.axisScores()).isEmpty();
    }

    @Test
    void unresolvableForcedProviderFallsThroughToNormalSelection() {
        DefaultProviderRegistry registry = registry(provider("alpha"), provider("bravo"));
        ProviderPerformanceSnapshot snapshot = snapshotOf(
            row("alpha", "m-alpha", "ethnic", bd("0.80"), bd("0.85"), 100L, LOW_FAILURE, bd("1.00"), 40, 30),
            row("bravo", "m-bravo", "ethnic", bd("0.95"), bd("0.90"), 120L, bd("0.05"), bd("1.10"), 40, 32));
        ProviderRoutingProperties properties = new ProviderRoutingProperties();
        properties.setForcedProvider("ghost");
        CategoryAwareProviderRouter engine = engine(registry, new FixedStore(snapshot), properties);

        ProviderRoute route = engine.route(new RoutingRequest(TRY_ON, List.of(ETHNIC), RoutingObjective.QUALITY));

        assertThat(route.providerKey().value()).isEqualTo("bravo");
        assertThat(route.modelOrMode()).isEqualTo("m-bravo");
        assertThat(route.outcome()).isEqualTo(RouteOutcome.FORCED_IGNORED_UNREGISTERED);
    }

    @Test
    void unresolvableForcedProviderKeepsDefaultedOutcomeWhenRoutingDefaults() {
        DefaultProviderRegistry registry = registry(provider("alpha"), provider("bravo"));
        ProviderPerformanceSnapshot snapshot = snapshotOf(
            row("alpha", "m-alpha", "ethnic", bd("0.80"), bd("0.85"), 100L, LOW_FAILURE, bd("1.00"), 20, 15),
            row("bravo", "m-bravo", "ethnic", bd("0.95"), bd("0.90"), 120L, bd("0.05"), bd("1.10"), 20, 16));
        ProviderRoutingProperties properties = new ProviderRoutingProperties();
        properties.setForcedProvider("ghost");
        CategoryAwareProviderRouter engine = engine(registry, new FixedStore(snapshot), properties);

        ProviderRoute route = engine.route(new RoutingRequest(null, List.of(), RoutingObjective.QUALITY));

        assertThat(route.providerKey().value()).isEqualTo("alpha");
        assertThat(route.outcome()).isEqualTo(RouteOutcome.DEFAULTED_INSUFFICIENT_EVIDENCE);
    }

    // ------------------------------------------------------------------
    // Cheapest-tier constraint (spec test 9)
    // ------------------------------------------------------------------

    @Test
    void cheapestTierConstraintExcludesExpensiveAndUncostedProviders() {
        DefaultProviderRegistry registry = registry(provider("alpha"), provider("bravo"), provider("charlie"));
        ProviderPerformanceSnapshot snapshot = snapshotOf(
            row("alpha", "m-alpha", "tops", bd("0.80"), bd("0.85"), 100L, LOW_FAILURE, bd("1.00"), 40, 30),
            row("bravo", "m-bravo", "tops", bd("0.75"), bd("0.80"), 120L, LOW_FAILURE, bd("0.50"), 40, 28),
            row("charlie", "m-charlie", "tops", bd("0.95"), bd("0.90"), 90L, LOW_FAILURE, null, 40, 0));
        CategoryAwareProviderRouter engine = engine(registry, new FixedStore(snapshot), new FixedConstraints(true));

        ProviderRoute route = engine.route(new RoutingRequest(null, List.of(), RoutingObjective.COST));

        assertThat(route.providerKey().value()).isEqualTo("bravo");
        assertThat(route.outcome()).isEqualTo(RouteOutcome.SELECTED);
        assertThat(route.candidateCount()).isEqualTo(3);
    }

    // ------------------------------------------------------------------
    // Capability gate (spec test 10)
    // ------------------------------------------------------------------

    @Test
    void unsupportedModeExcludesProvider() {
        DefaultProviderRegistry registry = registry(provider("alpha", TRY_ON), provider("bravo", TRY_ON, VIDEO));
        ProviderPerformanceSnapshot snapshot = snapshotOf(
            row("alpha", "m-alpha", "ethnic", bd("0.95"), bd("0.90"), 100L, LOW_FAILURE, bd("1.00"), 40, 30),
            row("bravo", "m-bravo", "ethnic", bd("0.80"), bd("0.85"), 120L, LOW_FAILURE, bd("1.10"), 40, 32));
        CategoryAwareProviderRouter engine = engine(registry, new FixedStore(snapshot));

        ProviderRoute route = engine.route(new RoutingRequest(VIDEO, List.of(ETHNIC), RoutingObjective.QUALITY));

        assertThat(route.providerKey().value()).isEqualTo("bravo");
        assertThat(route.outcome()).isEqualTo(RouteOutcome.SELECTED);
    }

    // ------------------------------------------------------------------
    // Multi-category protection (spec tests 11, 12)
    // ------------------------------------------------------------------

    @Test
    void providerMissingRequestedCategoryEvidenceIsExcluded() {
        DefaultProviderRegistry registry = registry(provider("alpha"), provider("bravo"));
        ProviderPerformanceSnapshot snapshot = snapshotOf(
            row("alpha", "m-alpha-shoes", "shoes", bd("0.70"), bd("0.75"), 200L, LOW_FAILURE, bd("1.20"), 40, 30),
            row("alpha", "m-alpha-tops", "tops", bd("0.80"), bd("0.85"), 100L, LOW_FAILURE, bd("1.00"), 40, 30),
            row("bravo", "m-bravo-tops", "tops", bd("0.95"), bd("0.90"), 120L, bd("0.05"), bd("1.10"), 40, 32));
        CategoryAwareProviderRouter engine = engine(registry, new FixedStore(snapshot));

        ProviderRoute route = engine.route(
            new RoutingRequest(TRY_ON, List.of(TOPS, SHOES), RoutingObjective.QUALITY));

        assertThat(route.providerKey().value()).isEqualTo("alpha");
        assertThat(route.modelOrMode()).isEqualTo("m-alpha-tops");
        assertThat(route.outcome()).isEqualTo(RouteOutcome.SELECTED);
    }

    @Test
    void multiCategoryJobDefaultsWhenNoProviderCoversEveryCategory() {
        DefaultProviderRegistry registry = registry(provider("alpha"), provider("bravo"));
        ProviderPerformanceSnapshot snapshot = snapshotOf(
            row("alpha", "m-alpha-tops", "tops", bd("0.80"), bd("0.85"), 100L, LOW_FAILURE, bd("1.00"), 40, 30),
            row("bravo", "m-bravo-shoes", "shoes", bd("0.95"), bd("0.90"), 120L, bd("0.05"), bd("1.10"), 40, 32));
        CategoryAwareProviderRouter engine = engine(registry, new FixedStore(snapshot));

        ProviderRoute route = engine.route(
            new RoutingRequest(TRY_ON, List.of(TOPS, SHOES), RoutingObjective.QUALITY));

        assertThat(route.providerKey().value()).isEqualTo("alpha");
        assertThat(route.outcome()).isEqualTo(RouteOutcome.DEFAULTED_NO_ELIGIBLE);
    }

    @Test
    void worstCategoryMinimumBeatsAverageWinner() {
        DefaultProviderRegistry registry = registry(provider("alpha"), provider("bravo"));
        ProviderPerformanceSnapshot snapshot = snapshotOf(
            row("alpha", "m-alpha-shoes", "shoes", bd("0.40"), bd("0.45"), 500L, LOW_FAILURE, bd("2.00"), 40, 30),
            row("alpha", "m-alpha-tops", "tops", bd("0.90"), bd("0.92"), 100L, LOW_FAILURE, bd("1.00"), 40, 30),
            row("bravo", "m-bravo-shoes", "shoes", bd("0.50"), bd("0.55"), 250L, LOW_FAILURE, bd("1.00"), 40, 30),
            row("bravo", "m-bravo-tops", "tops", bd("0.50"), bd("0.52"), 80L, LOW_FAILURE, bd("0.90"), 40, 28));
        CategoryAwareProviderRouter engine = engine(registry, new FixedStore(snapshot));

        ProviderRoute route = engine.route(
            new RoutingRequest(TRY_ON, List.of(TOPS, SHOES), RoutingObjective.QUALITY));

        assertThat(route.providerKey().value()).isEqualTo("bravo");
        assertThat(route.modelOrMode()).isEqualTo("m-bravo-tops");
        assertThat(route.outcome()).isEqualTo(RouteOutcome.SELECTED);
    }

    // ------------------------------------------------------------------
    // Tie-break determinism (spec test 13)
    // ------------------------------------------------------------------

    @Test
    void tiesResolveDeterministicallyBySamplesThenKey() {
        ProviderPerformanceSnapshot higherBravoSamples = snapshotOf(
            row("alpha", "m-alpha", "ethnic", null, bd("0.90"), null, LOW_FAILURE, null, 40, 30),
            row("bravo", "m-bravo", "ethnic", null, bd("0.90"), null, LOW_FAILURE, null, 60, 45));
        ProviderPerformanceSnapshot equalSamples = snapshotOf(
            row("alpha", "m-alpha", "ethnic", null, bd("0.90"), null, LOW_FAILURE, null, 40, 30),
            row("bravo", "m-bravo", "ethnic", null, bd("0.90"), null, LOW_FAILURE, null, 40, 30));
        RoutingRequest request = new RoutingRequest(TRY_ON, List.of(ETHNIC), RoutingObjective.QUALITY);

        ProviderRoute bySamples = engine(registry(provider("alpha"), provider("bravo")), new FixedStore(higherBravoSamples)).route(request);
        assertThat(bySamples.providerKey().value()).isEqualTo("bravo");

        List<ProviderRoute> repeated = new ArrayList<>();
        CategoryAwareProviderRouter byKey = engine(registry(provider("alpha"), provider("bravo")), new FixedStore(equalSamples));
        for (int i = 0; i < 3; i++) {
            repeated.add(byKey.route(request));
        }
        assertThat(repeated.get(0).providerKey().value()).isEqualTo("alpha");
        assertThat(repeated).hasSize(3).allSatisfy(route -> assertThat(route).isEqualTo(repeated.get(0)));
    }

    // ------------------------------------------------------------------
    // Quality fallback and secondary neutrality (spec test 14)
    // ------------------------------------------------------------------

    @Test
    void qualityFallsBackToQualityPassRateAndNeutralSecondarySurvives() {
        DefaultProviderRegistry registry = registry(provider("alpha"), provider("bravo"), provider("charlie"));
        ProviderPerformanceSnapshot snapshot = snapshotOf(
            row("alpha", "m-alpha", "ethnic", null, bd("0.90"), null, LOW_FAILURE, bd("0.50"), 40, 30),
            row("bravo", "m-bravo", "ethnic", null, bd("0.70"), null, LOW_FAILURE, bd("0.80"), 40, 28),
            row("charlie", "m-charlie", "ethnic", null, null, 100L, LOW_FAILURE, bd("0.40"), 40, 26));

        ProviderRoute quality = engine(registry, new FixedStore(snapshot))
            .route(new RoutingRequest(TRY_ON, List.of(ETHNIC), RoutingObjective.QUALITY));
        assertThat(quality.providerKey().value()).isEqualTo("alpha");
        assertThat(quality.outcome()).isEqualTo(RouteOutcome.SELECTED);

        ProviderRoute cost = engine(registry, new FixedStore(snapshot))
            .route(new RoutingRequest(TRY_ON, List.of(ETHNIC), RoutingObjective.COST));
        assertThat(cost.providerKey().value()).isEqualTo("charlie");
        assertThat(cost.axisScores().get("quality")).isEqualTo(bd("0.500000"));
    }

    // ------------------------------------------------------------------
    // Concurrency (spec test 15)
    // ------------------------------------------------------------------

    @Test
    void routeIsDeterministicUnderConcurrentLoad() throws Exception {
        DefaultProviderRegistry registry = registry(provider("alpha"), provider("bravo"));
        ProviderPerformanceSnapshot snapshot = snapshotOf(
            row("alpha", "m-alpha", "ethnic", bd("0.80"), bd("0.85"), 100L, LOW_FAILURE, bd("1.00"), 40, 30),
            row("bravo", "m-bravo", "ethnic", bd("0.95"), bd("0.90"), 120L, bd("0.05"), bd("1.10"), 40, 32));
        CategoryAwareProviderRouter engine = engine(registry, new FixedStore(snapshot));
        RoutingRequest request = new RoutingRequest(TRY_ON, List.of(ETHNIC), RoutingObjective.QUALITY);

        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<ProviderRoute>> futures = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                futures.add(pool.submit(() -> engine.route(request)));
            }
            ProviderRoute first = futures.get(0).get();
            for (Future<ProviderRoute> future : futures) {
                assertThat(future.get()).isEqualTo(first);
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    // ------------------------------------------------------------------
    // Axis score bounds and normalization direction (spec test 18)
    // ------------------------------------------------------------------

    @Test
    void lowerCostAndLowerLatencyScoreHigher() {
        DefaultProviderRegistry registry = registry(provider("alpha"), provider("bravo"));
        CategoryAwareProviderRouter engine = engine(registry, new FixedStore(snapshotOf(
            row("alpha", "m-alpha-lat", "tops", bd("0.80"), bd("0.85"), 100L, LOW_FAILURE, null, 40, 30),
            row("bravo", "m-bravo-lat", "tops", bd("0.80"), bd("0.85"), 300L, LOW_FAILURE, null, 40, 30))));

        ProviderRoute latency = engine.route(new RoutingRequest(TRY_ON, List.of(TOPS), RoutingObjective.LATENCY));
        assertThat(latency.providerKey().value()).isEqualTo("alpha");
        assertThat(latency.axisScores().get("latency")).isEqualTo(bd("1.000000"));

        CategoryAwareProviderRouter costEngine = engine(registry, new FixedStore(snapshotOf(
            row("alpha", "m-alpha-cost", "tops", bd("0.80"), bd("0.85"), null, LOW_FAILURE, bd("0.50"), 40, 30),
            row("bravo", "m-bravo-cost", "tops", bd("0.80"), bd("0.85"), null, LOW_FAILURE, bd("1.50"), 40, 30))));

        ProviderRoute cost = costEngine.route(new RoutingRequest(TRY_ON, List.of(TOPS), RoutingObjective.COST));
        assertThat(cost.providerKey().value()).isEqualTo("alpha");
        assertThat(cost.axisScores().get("cost")).isEqualTo(bd("1.000000"));
    }

    @Test
    void globalRequestAggregatesBySampleWeightedMeanWithBoundedAxisScores() {
        DefaultProviderRegistry registry = registry(provider("alpha"), provider("bravo"));
        ProviderPerformanceSnapshot snapshot = snapshotOf(
            row("alpha", "m-alpha-shoes", "shoes", bd("0.50"), bd("0.55"), 400L, LOW_FAILURE, bd("2.00"), 36, 30),
            row("alpha", "m-alpha-tops", "tops", bd("0.90"), bd("0.92"), 100L, LOW_FAILURE, bd("1.00"), 36, 30),
            row("bravo", "m-bravo-tops", "tops", bd("0.80"), bd("0.84"), 150L, LOW_FAILURE, bd("1.20"), 72, 60));
        CategoryAwareProviderRouter engine = engine(registry, new FixedStore(snapshot));

        ProviderRoute route = engine.route(new RoutingRequest(null, List.of(), RoutingObjective.QUALITY));

        assertThat(route.providerKey().value()).isEqualTo("alpha");
        assertThat(route.outcome()).isEqualTo(RouteOutcome.SELECTED);
        assertThat(route.axisScores()).containsOnlyKeys("quality", "cost", "latency");
        assertThat(route.axisScores().values())
            .allSatisfy(score -> {
                assertThat(score.scale()).isEqualTo(6);
                assertThat(score).isBetween(bd("0.000000"), bd("1.000000"));
            });
        assertThat(route.axisScores().get("quality")).isEqualTo(bd("1.000000"));
    }

    @Test
    void missingStoreBehavesAsEmptySnapshot() {
        DefaultProviderRegistry registry = registry(provider("alpha"), provider("bravo"));
        CategoryAwareProviderRouter engine = engine(registry, null);

        ProviderRoute route = engine.route(new RoutingRequest(null, List.of(), RoutingObjective.QUALITY));

        assertThat(route.providerKey().value()).isEqualTo("alpha");
        assertThat(route.outcome()).isEqualTo(RouteOutcome.DEFAULTED_INSUFFICIENT_EVIDENCE);
    }

    // ------------------------------------------------------------------
    // Registry candidate seam and metrics port
    // ------------------------------------------------------------------

    @Test
    void providerKeysSeamExposesRegisteredKeysAndDefaultExposesSelection() {
        DefaultProviderRegistry registry = registry(provider("alpha"), provider("bravo"), provider("charlie"));
        assertThat(registry.providerKeys())
            .containsExactlyInAnyOrder(new ProviderKey("alpha"), new ProviderKey("bravo"), new ProviderKey("charlie"));

        ProviderRegistry singleKey = new ProviderRegistry() {
            @Override public VtonProvider resolve(ProviderKey providerKey) { throw new UnknownProviderException(providerKey); }
            @Override public ProviderKey selectedProviderKey() { return new ProviderKey("alpha"); }
        };
        assertThat(singleKey.providerKeys()).containsExactly(new ProviderKey("alpha"));
    }

    @Test
    void passthroughRoutesAreObservableThroughTheMetricsPort() {
        DefaultProviderRegistry registry = registry(provider("alpha"));
        CategoryAwareProviderRouter engine = engine(registry, new FixedStore(snapshotOf()));
        RecordingMetrics metrics = new RecordingMetrics();

        metrics.routeSelected(engine.route(new RoutingRequest(null, List.of(), RoutingObjective.QUALITY)));

        assertThat(metrics.routes).hasSize(1);
        assertThat(metrics.routes.get(0).outcome()).isEqualTo(RouteOutcome.PASSTHROUGH_SINGLE_PROVIDER);
        assertThat(new NoopProviderRoutingConstraints().restrictToCheapestTier()).isFalse();
        new NoopProviderRoutingMetrics().routeSelected(metrics.routes.get(0));
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static BigDecimal bd(String value) {
        return new BigDecimal(value);
    }

    private static FakeVtonProvider provider(String key, VtonMode... modes) {
        return new FakeVtonProvider(key, modes.length == 0 ? new VtonMode[] { TRY_ON } : modes);
    }

    private static DefaultProviderRegistry registry(FakeVtonProvider... providers) {
        return new DefaultProviderRegistry(List.of(providers), providers[0].providerKey(), Map.of());
    }

    private static CategoryAwareProviderRouter engine(DefaultProviderRegistry registry, ProviderPerformanceStore store) {
        return engine(registry, store, new ProviderRoutingProperties());
    }

    private static CategoryAwareProviderRouter engine(DefaultProviderRegistry registry, ProviderPerformanceStore store, ProviderHealthGate healthGate) {
        return new CategoryAwareProviderRouter(registry, store, healthGate,
            new NoopProviderRoutingConstraints(), new ProviderRoutingProperties(), FIXED_CLOCK);
    }

    private static CategoryAwareProviderRouter engine(DefaultProviderRegistry registry, ProviderPerformanceStore store, ProviderRoutingProperties properties) {
        return new CategoryAwareProviderRouter(registry, store, null,
            new NoopProviderRoutingConstraints(), properties, FIXED_CLOCK);
    }

    private static CategoryAwareProviderRouter engine(DefaultProviderRegistry registry, ProviderPerformanceStore store, ProviderRoutingConstraints constraints) {
        return new CategoryAwareProviderRouter(registry, store, null,
            constraints, new ProviderRoutingProperties(), FIXED_CLOCK);
    }

    private static ProviderPerformanceSnapshot snapshotOf(ProviderPerformanceRow... rows) {
        List<ProviderPerformanceRow> sorted = Arrays.stream(rows).sorted(SNAPSHOT_ORDER).toList();
        return new ProviderPerformanceSnapshot(sorted, FIXED_CLOCK.instant());
    }

    private static ProviderPerformanceRow row(
        String provider, String model, String category,
        BigDecimal fidelity, BigDecimal qualityPassRate, Long meanLatencyMs,
        BigDecimal failRate, BigDecimal costPerSuccess,
        long sampleCount, long qualityApprovedCount) {
        return new ProviderPerformanceRow(provider, model, category, fidelity, qualityPassRate, meanLatencyMs,
            failRate, costPerSuccess, "USD", sampleCount, sampleCount, qualityApprovedCount);
    }

    private static final class FakeVtonProvider implements VtonProvider {
        private final ProviderKey key;
        private final ProviderCapabilities capabilities;

        private FakeVtonProvider(String key, VtonMode... modes) {
            this.key = new ProviderKey(key);
            Map<VtonMode, ModeCapabilities> modeMap = new LinkedHashMap<>();
            for (VtonMode mode : modes) {
                modeMap.put(mode, new ModeCapabilities(Set.of(ETHNIC, TOPS, SHOES), 1, false, InputSizeLimit.unknown()));
            }
            this.capabilities = new ProviderCapabilities(modeMap,
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

    private static final class FixedStore implements ProviderPerformanceStore {
        private final ProviderPerformanceSnapshot snapshot;

        private FixedStore(ProviderPerformanceSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override public ProviderPerformanceSnapshot snapshot(ProviderPerformanceQuery query) { return snapshot; }
        @Override public void recompute(Instant fromInclusive, Instant toExclusive) { }
    }

    private static final class ThrowingStore implements ProviderPerformanceStore {
        @Override public ProviderPerformanceSnapshot snapshot(ProviderPerformanceQuery query) {
            throw new IllegalStateException("evidence unavailable");
        }
        @Override public void recompute(Instant fromInclusive, Instant toExclusive) { }
    }

    private static final class FixedHealthGate implements ProviderHealthGate {
        private final Set<ProviderKey> allowed;

        private FixedHealthGate(Set<ProviderKey> allowed) {
            this.allowed = allowed;
        }

        @Override public boolean allowsNewExecution(ProviderKey provider) {
            return allowed.contains(provider);
        }
    }

    private static final class FixedConstraints implements ProviderRoutingConstraints {
        private final boolean restrict;

        private FixedConstraints(boolean restrict) {
            this.restrict = restrict;
        }

        @Override public boolean restrictToCheapestTier() {
            return restrict;
        }
    }

    private static final class RecordingMetrics implements ProviderRoutingMetrics {
        private final List<ProviderRoute> routes = new ArrayList<>();

        @Override public void routeSelected(ProviderRoute route) {
            routes.add(route);
        }
    }
}
