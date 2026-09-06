package com.aifashionvault.generation.vton;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import com.aifashionvault.generation.ProviderHealthGate;

/**
 * Pure, deterministic, category-aware provider routing engine (slice-74).
 *
 * <p>The decision is a pure function of (registry candidates, evidence snapshot, health gate,
 * constraints, properties, clock, request): identical inputs produce identical
 * {@link ProviderRoute}s, including BigDecimal scales. All per-call state is local; iteration
 * order is pinned to registration/insertion order via linked collections, never hash order.
 *
 * <p>Decision order: mode gate (OFF passthrough, AUTO single-provider passthrough, ON always
 * routes) → forced pin → eligibility (health, mode capability, reliability) → evidence
 * grouping and sufficiency → per-category min-max scoring against the category objective →
 * optional cheapest-tier constraint → aggregation (scoped: worst-category minimum over every
 * requested category; global: sample-count-weighted mean over sufficient categories) →
 * deterministic selection (composite, then total samples, then provider key, then model).
 *
 * <p>Scoring axes: quality = representative row fidelity, else qualityPassRate; cost =
 * costPerSuccess (null is unscoreable, never zero); latency = meanLatencyMs. A candidate
 * unscoreable on a category's primary axis is excluded from that category; missing secondary
 * axes score neutral 0.5, degenerate axes (one survivor or all equal) score 1.0. Normalized
 * scores use scale 6, HALF_UP, clamped to [0,1].
 *
 * <p>Forced pin contract: a resolvable pin returns {@link RouteOutcome#DEFAULTED_FORCED}
 * bypassing all evidence evaluation. An unresolvable pin is ignored observably: normal routing
 * proceeds and, when it selects, the outcome is reported as
 * {@link RouteOutcome#FORCED_IGNORED_UNREGISTERED} (the pin was ignored; a normal selection
 * happened); when normal routing defaults, the defaulted outcome is kept.
 *
 * <p>Failure behavior: a null store behaves as an empty snapshot; a null health gate allows
 * every provider. A {@link RuntimeException} from the store propagates to the caller — the
 * registry decorator owns catch-and-fail-open. Every default fails open to
 * {@link ProviderRegistry#selectedProviderKey()} with empty axis scores.
 */
public final class CategoryAwareProviderRouter implements ProviderRouter {

    private static final int SCORE_SCALE = 6;
    private static final String CELL_SEPARATOR = "\u0000";
    private static final String QUALITY_AXIS = "quality";
    private static final String COST_AXIS = "cost";
    private static final String LATENCY_AXIS = "latency";
    private static final BigDecimal ZERO_SCORE = BigDecimal.ZERO.setScale(SCORE_SCALE, RoundingMode.UNNECESSARY);
    private static final BigDecimal NEUTRAL = new BigDecimal("0.500000");
    private static final BigDecimal ONE = BigDecimal.ONE;
    private static final BigDecimal FULL_SCORE = ONE.setScale(SCORE_SCALE, RoundingMode.UNNECESSARY);
    private static final BigDecimal[] QUALITY_WEIGHTS = { new BigDecimal("0.6"), new BigDecimal("0.2"), new BigDecimal("0.2") };
    private static final BigDecimal[] COST_WEIGHTS = { new BigDecimal("0.2"), new BigDecimal("0.6"), new BigDecimal("0.2") };
    private static final BigDecimal[] LATENCY_WEIGHTS = { new BigDecimal("0.2"), new BigDecimal("0.2"), new BigDecimal("0.6") };

    /**
     * Deterministic representative-row order: best qualityPassRate first (nulls last), then
     * lowest failRate, lowest costPerSuccess (nulls last), lowest meanLatencyMs (nulls last),
     * then lexicographically smallest modelOrMode.
     */
    private static final Comparator<ProviderPerformanceRow> REPRESENTATIVE_ORDER = Comparator
        .comparing(ProviderPerformanceRow::qualityPassRate, Comparator.nullsLast(Comparator.reverseOrder()))
        .thenComparing(ProviderPerformanceRow::failRate)
        .thenComparing(ProviderPerformanceRow::costPerSuccess, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(ProviderPerformanceRow::meanLatencyMs, Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(ProviderPerformanceRow::modelOrMode);

    private final ProviderRegistry registry;
    private final ProviderPerformanceStore store;
    private final ProviderHealthGate healthGate;
    private final ProviderRoutingConstraints constraints;
    private final ProviderRoutingProperties properties;
    private final Clock clock;

    /**
     * {@code store} and {@code healthGate} may be null: absent evidence (empty snapshot) and
     * allow-all health respectively. The remaining collaborators are required.
     */
    public CategoryAwareProviderRouter(
        ProviderRegistry registry,
        ProviderPerformanceStore store,
        ProviderHealthGate healthGate,
        ProviderRoutingConstraints constraints,
        ProviderRoutingProperties properties,
        Clock clock) {
        if (registry == null) throw new IllegalArgumentException("registry is required");
        if (constraints == null) throw new IllegalArgumentException("constraints is required");
        if (properties == null) throw new IllegalArgumentException("properties is required");
        if (clock == null) throw new IllegalArgumentException("clock is required");
        this.registry = registry;
        this.store = store;
        this.healthGate = healthGate;
        this.constraints = constraints;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public ProviderRoute route(RoutingRequest request) {
        if (request == null) throw new IllegalArgumentException("request is required");
        List<ProviderKey> candidates = List.copyOf(registry.providerKeys());
        int candidateCount = candidates.size();

        switch (properties.mode()) {
            case OFF -> {
                return auditRoute(request, candidateCount, RouteOutcome.PASSTHROUGH_DISABLED);
            }
            case AUTO -> {
                if (candidateCount <= 1) {
                    return auditRoute(request, candidateCount, RouteOutcome.PASSTHROUGH_SINGLE_PROVIDER);
                }
            }
            case ON -> { /* always proceed to routing */ }
        }

        Optional<ProviderKey> pin = properties.forcedProvider();
        boolean forcedIgnored = false;
        if (pin.isPresent()) {
            if (candidates.contains(pin.get())) {
                return new ProviderRoute(pin.get(), null, properties.policyVersion(), request.objective(),
                    RouteOutcome.DEFAULTED_FORCED, candidateCount, Map.of());
            }
            forcedIgnored = true;
        }

        Instant now = clock.instant();
        ProviderPerformanceSnapshot snapshot = store == null
            ? new ProviderPerformanceSnapshot(List.of(), now)
            : store.snapshot(new ProviderPerformanceQuery(now.minus(properties.window()), now, null, null, properties.getCurrency()));

        Evidence evidence = groupEvidence(candidates, snapshot);

        boolean scoped = !request.categories().isEmpty();
        List<String> effectiveCategories;
        if (scoped) {
            effectiveCategories = request.categories().stream().map(ItemCategory::value).toList();
            if (!evidence.hasAnySufficientCell(effectiveCategories)) {
                return auditRoute(request, candidateCount, RouteOutcome.DEFAULTED_INSUFFICIENT_EVIDENCE);
            }
        } else {
            effectiveCategories = evidence.sufficientCategoryUnion();
            if (effectiveCategories.isEmpty()) {
                return auditRoute(request, candidateCount, RouteOutcome.DEFAULTED_INSUFFICIENT_EVIDENCE);
            }
        }

        Set<String> eligible = eligibleCandidates(candidates, request, evidence);

        Map<String, LinkedHashMap<ProviderKey, CategoryScore>> scoredByCategory = new LinkedHashMap<>();
        for (String category : effectiveCategories) {
            RoutingObjective objective = scoped ? request.objective() : properties.objectiveFor(new ItemCategory(category));
            scoredByCategory.put(category, scoreCategory(candidates, eligible, evidence, category, objective));
        }

        if (constraints.restrictToCheapestTier()) {
            for (LinkedHashMap<ProviderKey, CategoryScore> pool : scoredByCategory.values()) {
                restrictToCheapestTier(pool);
            }
        }

        RouteOutcome outcome = forcedIgnored ? RouteOutcome.FORCED_IGNORED_UNREGISTERED : RouteOutcome.SELECTED;
        if (scoped) {
            return aggregateScoped(request, candidates, candidateCount, effectiveCategories, scoredByCategory, evidence, outcome);
        }
        return aggregateGlobal(request, candidateCount, effectiveCategories, scoredByCategory, evidence, outcome);
    }

    // ------------------------------------------------------------------
    // Eligibility
    // ------------------------------------------------------------------

    private Set<String> eligibleCandidates(
        List<ProviderKey> candidates,
        RoutingRequest request,
        Evidence evidence) {
        Set<String> eligible = new LinkedHashSet<>();
        for (ProviderKey key : candidates) {
            if (healthGate != null && !healthGate.allowsNewExecution(key)) continue;
            if (request.mode() != null && registry.resolve(key).capabilities().forMode(request.mode()).isEmpty()) continue;
            ProviderPerformanceRow representative = evidence.providerRepresentatives.get(key.value());
            if (representative == null || representative.failRate().compareTo(properties.maxFailRate()) > 0) continue;
            eligible.add(key.value());
        }
        return eligible;
    }

    // ------------------------------------------------------------------
    // Per-category scoring
    // ------------------------------------------------------------------

    private LinkedHashMap<ProviderKey, CategoryScore> scoreCategory(
        List<ProviderKey> candidates,
        Set<String> eligible,
        Evidence evidence,
        String category,
        RoutingObjective objective) {
        LinkedHashMap<ProviderKey, ProviderPerformanceRow> pool = new LinkedHashMap<>();
        for (ProviderKey key : candidates) {
            if (!eligible.contains(key.value())) continue;
            String cell = key.value() + CELL_SEPARATOR + category;
            ProviderPerformanceRow representative = evidence.cellRepresentatives.get(cell);
            if (representative == null || !evidence.isSufficient(cell)) continue;
            if (axisValue(representative, objective) == null) continue;
            pool.put(key, representative);
        }

        List<ProviderPerformanceRow> poolRows = new ArrayList<>(pool.values());
        BigDecimal[] qualityRange = range(poolRows, CategoryAwareProviderRouter::qualityValue);
        BigDecimal[] costRange = range(poolRows, ProviderPerformanceRow::costPerSuccess);
        BigDecimal[] latencyRange = range(poolRows, CategoryAwareProviderRouter::latencyValue);
        BigDecimal[] weights = weights(objective);

        LinkedHashMap<ProviderKey, CategoryScore> scored = new LinkedHashMap<>();
        pool.forEach((key, representative) -> {
            BigDecimal quality = axisScore(qualityValue(representative), qualityRange, true);
            BigDecimal cost = axisScore(representative.costPerSuccess(), costRange, false);
            BigDecimal latency = axisScore(latencyValue(representative), latencyRange, false);
            BigDecimal composite = weights[0].multiply(quality)
                .add(weights[1].multiply(cost))
                .add(weights[2].multiply(latency));
            scored.put(key, new CategoryScore(representative, Map.of(QUALITY_AXIS, quality, COST_AXIS, cost, LATENCY_AXIS, latency), composite));
        });
        return scored;
    }

    private void restrictToCheapestTier(LinkedHashMap<ProviderKey, CategoryScore> pool) {
        pool.values().removeIf(scored -> scored.row().costPerSuccess() == null);
        BigDecimal minCost = null;
        for (CategoryScore scored : pool.values()) {
            BigDecimal cost = scored.row().costPerSuccess();
            if (minCost == null || cost.compareTo(minCost) < 0) minCost = cost;
        }
        if (minCost == null) return;
        BigDecimal ceiling = minCost.multiply(properties.cheapestTierTolerance());
        pool.values().removeIf(scored -> scored.row().costPerSuccess().compareTo(ceiling) > 0);
    }

    // ------------------------------------------------------------------
    // Aggregation
    // ------------------------------------------------------------------

    private ProviderRoute aggregateScoped(
        RoutingRequest request,
        List<ProviderKey> candidates,
        int candidateCount,
        List<String> effectiveCategories,
        Map<String, LinkedHashMap<ProviderKey, CategoryScore>> scoredByCategory,
        Evidence evidence,
        RouteOutcome selectionOutcome) {
        ProviderKey winner = null;
        BigDecimal bestTotal = null;
        long bestSamples = 0L;
        String worstCategory = null;
        for (ProviderKey key : candidates) {
            BigDecimal total = null;
            String worst = null;
            long samples = 0L;
            boolean complete = true;
            for (String category : effectiveCategories) {
                CategoryScore scored = scoredByCategory.get(category).get(key);
                if (scored == null) {
                    complete = false;
                    break;
                }
                samples += evidence.sampleTotals.get(key.value() + CELL_SEPARATOR + category);
                if (total == null || scored.composite().compareTo(total) < 0) {
                    total = scored.composite();
                    worst = category;
                }
            }
            if (!complete) continue;
            String model = evidence.cellRepresentatives.get(key.value() + CELL_SEPARATOR + worst).modelOrMode();
            if (winner == null || better(key, total, samples, model, winner, bestTotal, bestSamples,
                evidence.cellRepresentatives.get(winner.value() + CELL_SEPARATOR + worstCategory).modelOrMode())) {
                winner = key;
                bestTotal = total;
                bestSamples = samples;
                worstCategory = worst;
            }
        }
        if (winner == null) {
            return auditRoute(request, candidateCount, RouteOutcome.DEFAULTED_NO_ELIGIBLE);
        }
        return new ProviderRoute(winner,
            evidence.cellRepresentatives.get(winner.value() + CELL_SEPARATOR + worstCategory).modelOrMode(),
            properties.policyVersion(), request.objective(), selectionOutcome, candidateCount,
            scoredByCategory.get(worstCategory).get(winner).axisScores());
    }

    private ProviderRoute aggregateGlobal(
        RoutingRequest request,
        int candidateCount,
        List<String> effectiveCategories,
        Map<String, LinkedHashMap<ProviderKey, CategoryScore>> scoredByCategory,
        Evidence evidence,
        RouteOutcome selectionOutcome) {
        LinkedHashMap<ProviderKey, GlobalTally> tallies = new LinkedHashMap<>();
        for (String category : effectiveCategories) {
            for (Map.Entry<ProviderKey, CategoryScore> entry : scoredByCategory.get(category).entrySet()) {
                String cell = entry.getKey().value() + CELL_SEPARATOR + category;
                long samples = evidence.sampleTotals.get(cell);
                BigDecimal sampleWeight = BigDecimal.valueOf(samples);
                GlobalTally tally = tallies.computeIfAbsent(entry.getKey(), key -> new GlobalTally());
                tally.samples += samples;
                tally.compositeSum = tally.compositeSum.add(entry.getValue().composite().multiply(sampleWeight));
                entry.getValue().axisScores().forEach((axis, score) ->
                    tally.axisSums.merge(axis, score.multiply(sampleWeight), BigDecimal::add));
                if (tally.definingCategory == null) tally.definingCategory = category;
            }
        }
        if (tallies.isEmpty()) {
            return auditRoute(request, candidateCount, RouteOutcome.DEFAULTED_NO_ELIGIBLE);
        }
        ProviderKey winner = null;
        BigDecimal bestTotal = null;
        long bestSamples = 0L;
        String bestModel = null;
        Map<String, BigDecimal> bestAxisScores = null;
        for (Map.Entry<ProviderKey, GlobalTally> entry : tallies.entrySet()) {
            GlobalTally tally = entry.getValue();
            BigDecimal divisor = BigDecimal.valueOf(tally.samples);
            BigDecimal total = clamp(tally.compositeSum.divide(divisor, SCORE_SCALE, RoundingMode.HALF_UP));
            Map<String, BigDecimal> axisScores = new LinkedHashMap<>();
            for (String axis : List.of(QUALITY_AXIS, COST_AXIS, LATENCY_AXIS)) {
                axisScores.put(axis, clamp(tally.axisSums.get(axis).divide(divisor, SCORE_SCALE, RoundingMode.HALF_UP)));
            }
            String model = evidence.cellRepresentatives.get(entry.getKey().value() + CELL_SEPARATOR + tally.definingCategory).modelOrMode();
            if (winner == null || better(entry.getKey(), total, tally.samples, model, winner, bestTotal, bestSamples, bestModel)) {
                winner = entry.getKey();
                bestTotal = total;
                bestSamples = tally.samples;
                bestModel = model;
                bestAxisScores = axisScores;
            }
        }
        return new ProviderRoute(winner, bestModel, properties.policyVersion(), request.objective(),
            selectionOutcome, candidateCount, bestAxisScores);
    }

    /** Selection order: higher composite, then higher total samples, then smaller provider key, then smaller model. */
    private static boolean better(
        ProviderKey keyA, BigDecimal totalA, long samplesA, String modelA,
        ProviderKey keyB, BigDecimal totalB, long samplesB, String modelB) {
        int byTotal = totalA.compareTo(totalB);
        if (byTotal != 0) return byTotal > 0;
        int bySamples = Long.compare(samplesA, samplesB);
        if (bySamples != 0) return bySamples > 0;
        int byKey = keyA.value().compareTo(keyB.value());
        if (byKey != 0) return byKey < 0;
        return modelA.compareTo(modelB) < 0;
    }

    // ------------------------------------------------------------------
    // Evidence grouping
    // ------------------------------------------------------------------

    private Evidence groupEvidence(List<ProviderKey> candidates, ProviderPerformanceSnapshot snapshot) {
        Set<String> candidateValues = new LinkedHashSet<>();
        for (ProviderKey key : candidates) {
            candidateValues.add(key.value());
        }
        Evidence evidence = new Evidence();
        Map<String, List<ProviderPerformanceRow>> cells = new LinkedHashMap<>();
        for (ProviderPerformanceRow row : snapshot.rows()) {
            if (!candidateValues.contains(row.providerKey())) continue;
            cells.computeIfAbsent(row.providerKey() + CELL_SEPARATOR + row.category(), key -> new ArrayList<>()).add(row);
            ProviderPerformanceRow current = evidence.providerRepresentatives.get(row.providerKey());
            if (current == null || REPRESENTATIVE_ORDER.compare(row, current) < 0) {
                evidence.providerRepresentatives.put(row.providerKey(), row);
            }
        }
        cells.forEach((cell, rows) -> {
            long samples = 0L;
            for (ProviderPerformanceRow row : rows) samples += row.sampleCount();
            evidence.sampleTotals.put(cell, samples);
            evidence.cellRepresentatives.put(cell, rows.stream().min(REPRESENTATIVE_ORDER).orElseThrow());
        });
        for (Map.Entry<String, Long> entry : evidence.sampleTotals.entrySet()) {
            if (entry.getValue() >= properties.minSampleCount()) {
                evidence.sufficientCells.add(entry.getKey());
                evidence.sufficientCategories.add(entry.getKey().substring(entry.getKey().indexOf(CELL_SEPARATOR) + 1));
            }
        }
        return evidence;
    }

    private final class Evidence {
        private final Map<String, Long> sampleTotals = new LinkedHashMap<>();
        private final Map<String, ProviderPerformanceRow> cellRepresentatives = new LinkedHashMap<>();
        private final Map<String, ProviderPerformanceRow> providerRepresentatives = new LinkedHashMap<>();
        private final Set<String> sufficientCells = new LinkedHashSet<>();
        private final Set<String> sufficientCategories = new LinkedHashSet<>();

        private boolean isSufficient(String cell) {
            return sufficientCells.contains(cell);
        }

        private boolean hasAnySufficientCell(List<String> categories) {
            for (String cell : sufficientCells) {
                if (categories.contains(cell.substring(cell.indexOf(CELL_SEPARATOR) + 1))) return true;
            }
            return false;
        }

        private List<String> sufficientCategoryUnion() {
            return List.copyOf(sufficientCategories);
        }
    }

    // ------------------------------------------------------------------
    // Scoring primitives
    // ------------------------------------------------------------------

    private static BigDecimal axisValue(ProviderPerformanceRow row, RoutingObjective objective) {
        return switch (objective) {
            case QUALITY -> qualityValue(row);
            case COST -> row.costPerSuccess();
            case LATENCY -> latencyValue(row);
        };
    }

    private static BigDecimal qualityValue(ProviderPerformanceRow row) {
        return row.fidelity() != null ? row.fidelity() : row.qualityPassRate();
    }

    private static BigDecimal latencyValue(ProviderPerformanceRow row) {
        return row.meanLatencyMs() == null ? null : BigDecimal.valueOf(row.meanLatencyMs());
    }

    private static BigDecimal[] weights(RoutingObjective objective) {
        return switch (objective) {
            case QUALITY -> QUALITY_WEIGHTS;
            case COST -> COST_WEIGHTS;
            case LATENCY -> LATENCY_WEIGHTS;
        };
    }

    private static BigDecimal[] range(List<ProviderPerformanceRow> rows, Function<ProviderPerformanceRow, BigDecimal> axis) {
        BigDecimal min = null;
        BigDecimal max = null;
        for (ProviderPerformanceRow row : rows) {
            BigDecimal value = axis.apply(row);
            if (value == null) continue;
            if (min == null || value.compareTo(min) < 0) min = value;
            if (max == null || value.compareTo(max) > 0) max = value;
        }
        return min == null ? null : new BigDecimal[] { min, max };
    }

    /**
     * Min-max normalization: missing secondary value → neutral 0.5; degenerate range (single
     * survivor or all equal) → 1.0; otherwise (higher-is-better) or inverted (lower-is-better)
     * normalization, scale 6 HALF_UP, clamped to [0,1].
     */
    private static BigDecimal axisScore(BigDecimal raw, BigDecimal[] range, boolean higherIsBetter) {
        if (raw == null) return NEUTRAL;
        if (range == null || range[0].compareTo(range[1]) == 0) return FULL_SCORE;
        BigDecimal span = range[1].subtract(range[0]);
        BigDecimal offset = higherIsBetter ? raw.subtract(range[0]) : range[1].subtract(raw);
        return clamp(offset.divide(span, SCORE_SCALE, RoundingMode.HALF_UP));
    }

    private static BigDecimal clamp(BigDecimal score) {
        if (score.compareTo(ZERO_SCORE) < 0) return ZERO_SCORE;
        if (score.compareTo(FULL_SCORE) > 0) return FULL_SCORE;
        return score;
    }

    /** Defaulting route: bootstrap key, no evidence, empty axis scores. */
    private ProviderRoute auditRoute(RoutingRequest request, int candidateCount, RouteOutcome outcome) {
        return new ProviderRoute(registry.selectedProviderKey(), null, properties.policyVersion(),
            request.objective(), outcome, candidateCount, Map.of());
    }

    private record CategoryScore(ProviderPerformanceRow row, Map<String, BigDecimal> axisScores, BigDecimal composite) { }

    private static final class GlobalTally {
        private long samples;
        private BigDecimal compositeSum = BigDecimal.ZERO;
        private final Map<String, BigDecimal> axisSums = new LinkedHashMap<>();
        private String definingCategory;
    }
}
