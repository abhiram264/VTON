package com.aifashionvault.platform.generation.benchmark;

import com.aifashionvault.generation.benchmark.BenchmarkReport;

/**
 * Human-readable rendering of a benchmark matrix. Scalar metrics only; no
 * media, URLs, credentials, or provider bodies. A trailing legend documents
 * the metric semantics so a printed matrix cannot be misread as an
 * attractiveness score or a deployment decision.
 */
public final class BenchmarkMatrixText {

	private BenchmarkMatrixText() {
	}

	public static String render(BenchmarkReport report) {
		StringBuilder text = new StringBuilder();
		text.append(String.format("%-18s %-10s %-12s %8s %10s %9s %12s %7s %8s%n",
				"PROVIDER", "MODEL", "CATEGORY", "FIDELITY", "P50-MS", "FAIL-RATE", "COST/SUCC", "SAMPLES",
				"SUCCESSES"));
		for (var row : report.matrixRows()) {
			text.append(String.format("%-18s %-10s %-12s %8s %10d %9s %12s %7d %8d%n",
					row.providerKey(),
					row.modelOrMode(),
					row.category(),
					row.fidelity() == null ? "n/a" : row.fidelity().toPlainString(),
					row.p50LatencyMs(),
					row.failRate() == null ? "n/a" : row.failRate().toPlainString(),
					row.costPerSuccess() == null ? "undefined" : row.costPerSuccess().toPlainString(),
					row.sampleCount(),
					row.successCount()));
		}
		text.append("fidelity: mean identity/garment/color over quality-approved successes; ")
			.append("p50: nearest-rank median latency over successes (sample count = successes); ")
			.append("cost-per-success: undefined when there are no quality-approved successes or no determinable cost")
			.append(System.lineSeparator());
		return text.toString();
	}
}
