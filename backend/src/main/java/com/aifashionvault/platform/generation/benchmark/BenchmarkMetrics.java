package com.aifashionvault.platform.generation.benchmark;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.aifashionvault.generation.benchmark.BenchmarkCaseResult;
import com.aifashionvault.generation.benchmark.Outcome;

/**
 * Shared benchmark metric statistics with explicit denominators.
 *
 * <p>Success means quality-approved output (Outcome SUCCESS), never provider
 * HTTP completion. Fidelity is the mean of per-case identity/garment/color
 * averages over successes only — a try-on acceptability measure, not an
 * attractiveness or style score. P50 latency is the nearest-rank median
 * (ascending, 1-based rank ceil(n/2)) over successes. failRate = non-success
 * outcomes / sampleCount; qualityPassRate = successes / cases that reached a
 * quality decision. costPerSuccess = total attributable known cost divided by
 * success count; it is null (never zero) when there are no successes, when no
 * cost is determinable, or when known costs span multiple currencies.
 */
final class BenchmarkMetrics {

	static final int SCALE = 6;
	private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

	record Stats(
			long sampleCount,
			long successCount,
			long qualityEvaluatedCount,
			BigDecimal fidelity,
			long p50LatencyMs,
			BigDecimal failRate,
			BigDecimal qualityPassRate,
			BigDecimal costPerSuccess) {
	}

	private BenchmarkMetrics() {
	}

	static Stats of(List<BenchmarkCaseResult> results) {
		long successCount = 0;
		long evaluatedCount = 0;
		List<BigDecimal> caseFidelities = new ArrayList<>();
		List<Long> latencies = new ArrayList<>();
		BigDecimal totalCost = BigDecimal.ZERO;
		boolean anyCostKnown = false;
		Set<String> currencies = new HashSet<>();
		for (BenchmarkCaseResult result : results) {
			if (result.outcome() == Outcome.SUCCESS) {
				successCount++;
				latencies.add(result.latencyMs());
				if (result.identityFidelity() != null && result.garmentFidelity() != null
						&& result.colorFidelity() != null) {
					caseFidelities.add(result.identityFidelity()
						.add(result.garmentFidelity())
						.add(result.colorFidelity())
						.divide(BigDecimal.valueOf(3), SCALE, ROUNDING));
				}
			}
			if (result.qualityGateResult() != null) {
				evaluatedCount++;
			}
			if (result.cost() != null) {
				anyCostKnown = true;
				totalCost = totalCost.add(result.cost());
				currencies.add(result.currency());
			}
		}
		BigDecimal costPerSuccess = null;
		if (successCount > 0 && anyCostKnown && currencies.size() == 1) {
			costPerSuccess = totalCost.divide(BigDecimal.valueOf(successCount), SCALE, ROUNDING);
		}
		return new Stats(results.size(), successCount, evaluatedCount, mean(caseFidelities),
				nearestRankP50(latencies), divideOrNull(results.size() - successCount, results.size()),
				divideOrNull(successCount, evaluatedCount), costPerSuccess);
	}

	private static BigDecimal mean(List<BigDecimal> values) {
		if (values.isEmpty()) {
			return null;
		}
		BigDecimal total = BigDecimal.ZERO;
		for (BigDecimal value : values) {
			total = total.add(value);
		}
		return total.divide(BigDecimal.valueOf(values.size()), SCALE, ROUNDING);
	}

	/** Nearest-rank P50: ascending sort, 1-based rank ceil(n/2); 0 when there is no sample. */
	static long nearestRankP50(List<Long> latencies) {
		if (latencies.isEmpty()) {
			return 0L;
		}
		List<Long> sorted = new ArrayList<>(latencies);
		sorted.sort(Long::compareTo);
		return sorted.get((sorted.size() + 1) / 2 - 1);
	}

	private static BigDecimal divideOrNull(long numerator, long denominator) {
		if (denominator == 0) {
			return null;
		}
		return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), SCALE, ROUNDING);
	}
}
