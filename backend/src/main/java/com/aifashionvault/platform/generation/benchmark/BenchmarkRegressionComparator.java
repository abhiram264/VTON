package com.aifashionvault.platform.generation.benchmark;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.aifashionvault.generation.benchmark.BenchmarkCaseResult;
import com.aifashionvault.generation.benchmark.Outcome;

/**
 * Baseline-vs-candidate regression comparison (Plan §52). Runs are matched
 * case-by-case on (caseId, category, benchmarkVersion) from the same
 * benchmark version; a benchmark-version mismatch is an error, never a
 * comparison. For each matched case the absolute and percentage deltas of
 * fidelity (mean identity/garment/color), latency, and outcome are reported.
 * Category-level and overall dimension deltas come from
 * {@link BenchmarkMatrixGenerator} rows. Missing pairs are surfaced, never
 * dropped. The result carries metrics only — no deployment approval and no
 * automatic blocking decision.
 */
public final class BenchmarkRegressionComparator {

	/** One matched-case comparison; deltas are candidate minus baseline. */
	public record CaseDelta(
			String caseId,
			String category,
			BigDecimal fidelityDelta,
			BigDecimal fidelityDeltaPercent,
			long latencyDeltaMs,
			Outcome baselineOutcome,
			Outcome candidateOutcome) {
	}

	/** Dimension-level delta between two matrix rows (overall or per category). */
	public record DimensionDelta(
			String dimension,
			String category,
			BigDecimal absolute,
			BigDecimal percent) {
	}

	/** Overall + per-category dimension deltas; each overall row is a scalar fact. */
	public record RegressionReport(
			String benchmarkVersion,
			List<CaseDelta> caseDeltas,
			List<DimensionDelta> dimensionDeltas,
			List<String> missingInCandidate,
			List<String> missingInBaseline,
			List<String> notes) {
	}

	private static final int SCALE = BenchmarkMetrics.SCALE;
	private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

	private BenchmarkRegressionComparator() {
	}

	public static RegressionReport compare(List<BenchmarkCaseResult> baseline, List<BenchmarkCaseResult> candidate,
			String baselineBenchmarkVersion, String candidateBenchmarkVersion) {
		if (!baselineBenchmarkVersion.equals(candidateBenchmarkVersion)) {
			throw new IllegalArgumentException(
					"benchmark version mismatch: baseline " + baselineBenchmarkVersion + " vs candidate "
							+ candidateBenchmarkVersion + "; matched comparison requires the same frozen version");
		}
		Map<String, BenchmarkCaseResult> baselineByCase = byCaseId(baseline);
		Map<String, BenchmarkCaseResult> candidateByCase = byCaseId(candidate);
		List<CaseDelta> caseDeltas = new ArrayList<>();
		List<String> missingInCandidate = new ArrayList<>(missing(baselineByCase, candidateByCase));
		List<String> missingInBaseline = new ArrayList<>(missing(candidateByCase, baselineByCase));
		for (Map.Entry<String, BenchmarkCaseResult> entry : baselineByCase.entrySet()) {
			BenchmarkCaseResult candidateResult = candidateByCase.get(entry.getKey());
			if (candidateResult == null) {
				continue;
			}
			BenchmarkCaseResult baselineResult = entry.getValue();
			if (!baselineResult.category().equals(candidateResult.category())) {
				throw new IllegalArgumentException("category mismatch for matched case " + baselineResult.caseId()
						+ ": " + baselineResult.category() + " vs " + candidateResult.category());
			}
			BigDecimal baselineFidelity = fidelity(baselineResult);
			BigDecimal candidateFidelity = fidelity(candidateResult);
			caseDeltas.add(new CaseDelta(
					baselineResult.caseId(),
					baselineResult.category(),
					baselineFidelity == null || candidateFidelity == null ? null
							: candidateFidelity.subtract(baselineFidelity),
					percentDelta(baselineFidelity, candidateFidelity),
					candidateResult.latencyMs() - baselineResult.latencyMs(),
					baselineResult.outcome(),
					candidateResult.outcome()));
		}
		List<DimensionDelta> dimensionDeltas = new ArrayList<>(compareDimensions(baseline, candidate));
		List<String> notes = new ArrayList<>();
		if (!missingInCandidate.isEmpty()) {
			notes.add(missingInCandidate.size() + " case(s) present in baseline but missing in candidate");
		}
		if (!missingInBaseline.isEmpty()) {
			notes.add(missingInBaseline.size() + " case(s) present in candidate but missing in baseline");
		}
		notes.add("deltas are candidate minus baseline; metrics only, no deployment decision");
		return new RegressionReport(baselineBenchmarkVersion, caseDeltas, dimensionDeltas, missingInCandidate,
				missingInBaseline, notes);
	}

	private static Map<String, BenchmarkCaseResult> byCaseId(List<BenchmarkCaseResult> results) {
		Map<String, BenchmarkCaseResult> byCaseId = new TreeMap<>();
		for (BenchmarkCaseResult result : results) {
			if (byCaseId.putIfAbsent(result.caseId(), result) != null) {
				throw new IllegalArgumentException("duplicate caseId: " + result.caseId());
			}
		}
		return byCaseId;
	}

	private static List<String> missing(Map<String, BenchmarkCaseResult> from, Map<String, BenchmarkCaseResult> notIn) {
		List<String> missing = new ArrayList<>();
		for (String caseId : from.keySet()) {
			if (!notIn.containsKey(caseId)) {
				missing.add(caseId);
			}
		}
		return missing;
	}

	private static BigDecimal fidelity(BenchmarkCaseResult result) {
		if (result.identityFidelity() == null || result.garmentFidelity() == null || result.colorFidelity() == null) {
			return null;
		}
		return result.identityFidelity()
			.add(result.garmentFidelity())
			.add(result.colorFidelity())
			.divide(BigDecimal.valueOf(3), SCALE, ROUNDING);
	}

	private static BigDecimal percentDelta(BigDecimal baselineValue, BigDecimal candidateValue) {
		if (baselineValue == null || candidateValue == null || baselineValue.signum() == 0) {
			return null;
		}
		return candidateValue.subtract(baselineValue)
			.divide(baselineValue, SCALE + 2, ROUNDING)
			.multiply(BigDecimal.valueOf(100))
			.setScale(SCALE, ROUNDING);
	}

	private static List<DimensionDelta> compareDimensions(List<BenchmarkCaseResult> baseline,
			List<BenchmarkCaseResult> candidate) {
		Map<String, List<BenchmarkCaseResult>> baselineGroups = groupByCategory(baseline);
		Map<String, List<BenchmarkCaseResult>> candidateGroups = groupByCategory(candidate);
		Set<String> categories = new HashSet<>(baselineGroups.keySet());
		categories.addAll(candidateGroups.keySet());
		List<DimensionDelta> deltas = new ArrayList<>();
		List<String> ordered = new ArrayList<>(categories);
		ordered.sort(String::compareTo);
		ordered.add(0, BenchmarkMatrixGenerator.PROVIDER_TOTAL_CATEGORY);
		List<BenchmarkCaseResult> baselineAll = new ArrayList<>(baseline);
		List<BenchmarkCaseResult> candidateAll = new ArrayList<>(candidate);
		for (String category : ordered) {
			BenchmarkMetrics.Stats baselineStats = BenchmarkMetrics.of(
					BenchmarkMatrixGenerator.PROVIDER_TOTAL_CATEGORY.equals(category) ? baselineAll
							: baselineGroups.getOrDefault(category, List.of()));
			BenchmarkMetrics.Stats candidateStats = BenchmarkMetrics.of(
					BenchmarkMatrixGenerator.PROVIDER_TOTAL_CATEGORY.equals(category) ? candidateAll
							: candidateGroups.getOrDefault(category, List.of()));
			deltas.add(delta("fidelity", category, baselineStats.fidelity(), candidateStats.fidelity()));
			deltas.add(delta("p50LatencyMs", category,
					baselineStats.p50LatencyMs() == 0 && baselineStats.successCount() == 0 ? null
							: BigDecimal.valueOf(baselineStats.p50LatencyMs()),
					candidateStats.p50LatencyMs() == 0 && candidateStats.successCount() == 0 ? null
							: BigDecimal.valueOf(candidateStats.p50LatencyMs())));
			deltas.add(delta("failRate", category, baselineStats.failRate(), candidateStats.failRate()));
			deltas.add(delta("qualityPassRate", category, baselineStats.qualityPassRate(),
					candidateStats.qualityPassRate()));
			deltas.add(delta("costPerSuccess", category, baselineStats.costPerSuccess(),
					candidateStats.costPerSuccess()));
		}
		return deltas;
	}

	private static Map<String, List<BenchmarkCaseResult>> groupByCategory(List<BenchmarkCaseResult> results) {
		Map<String, List<BenchmarkCaseResult>> grouped = new TreeMap<>();
		for (BenchmarkCaseResult result : results) {
			grouped.computeIfAbsent(result.category(), ignored -> new ArrayList<>()).add(result);
		}
		return grouped;
	}

	private static DimensionDelta delta(String dimension, String category, BigDecimal baselineValue,
			BigDecimal candidateValue) {
		if (baselineValue == null && candidateValue == null) {
			return new DimensionDelta(dimension, category, null, null);
		}
		if (baselineValue == null || candidateValue == null) {
			// One side undefined (e.g. cost-per-success with no successes): absolute change
			// undefined-null -> value / value -> undefined-null, surfaced rather than zero.
			return new DimensionDelta(dimension, category, null, null);
		}
		return new DimensionDelta(dimension, category, candidateValue.subtract(baselineValue),
				percentDelta(baselineValue, candidateValue));
	}
}
