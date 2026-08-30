package com.aifashionvault.platform.generation.benchmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.aifashionvault.generation.benchmark.BenchmarkCaseResult;
import com.aifashionvault.generation.benchmark.BenchmarkMatrixRow;

/**
 * Deterministic aggregate provider matrix from per-case benchmark results.
 *
 * <p>Metric semantics (see {@link BenchmarkMetrics}): fidelity is the mean
 * identity/garment/color fidelity over quality-approved successes; P50 is the
 * nearest-rank median latency over successes with the sample count reported in
 * the row; failRate counts every non-success outcome over all samples;
 * costPerSuccess is total attributable known cost divided by success count and
 * null (never zero) when undefined.
 *
 * <p>Emits one row per (providerKey, modelOrMode, category) in sorted order,
 * followed by a provider total row whose category is
 * {@link #PROVIDER_TOTAL_CATEGORY}, so category weakness cannot hide behind
 * the total. Ordering is a pure function of the result values.
 */
public final class BenchmarkMatrixGenerator {

	public static final String PROVIDER_TOTAL_CATEGORY = "ALL";

	private BenchmarkMatrixGenerator() {
	}

	public static List<BenchmarkMatrixRow> rows(List<BenchmarkCaseResult> results) {
		Map<String, Map<String, Map<String, List<BenchmarkCaseResult>>>> grouped = new TreeMap<>();
		for (BenchmarkCaseResult result : results) {
			grouped.computeIfAbsent(result.providerKey(), ignored -> new TreeMap<>())
				.computeIfAbsent(result.modelOrMode(), ignored -> new TreeMap<>())
				.computeIfAbsent(result.category(), ignored -> new ArrayList<>())
				.add(result);
		}
		List<BenchmarkMatrixRow> rows = new ArrayList<>();
		for (Map.Entry<String, Map<String, Map<String, List<BenchmarkCaseResult>>>> provider : grouped
			.entrySet()) {
			for (Map.Entry<String, Map<String, List<BenchmarkCaseResult>>> model : provider.getValue()
				.entrySet()) {
				List<BenchmarkCaseResult> allCases = new ArrayList<>();
				for (Map.Entry<String, List<BenchmarkCaseResult>> category : model.getValue().entrySet()) {
					allCases.addAll(category.getValue());
					rows.add(row(provider.getKey(), model.getKey(), category.getKey(), category.getValue()));
				}
				rows.add(row(provider.getKey(), model.getKey(), PROVIDER_TOTAL_CATEGORY, allCases));
			}
		}
		return rows;
	}

	private static BenchmarkMatrixRow row(String providerKey, String modelOrMode, String category,
			List<BenchmarkCaseResult> cases) {
		BenchmarkMetrics.Stats stats = BenchmarkMetrics.of(cases);
		return new BenchmarkMatrixRow(providerKey, modelOrMode, category, stats.fidelity(),
				stats.p50LatencyMs(), stats.failRate(), stats.costPerSuccess(), stats.sampleCount(),
				stats.successCount());
	}
}
