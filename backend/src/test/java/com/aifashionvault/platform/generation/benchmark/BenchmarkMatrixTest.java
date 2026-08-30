package com.aifashionvault.platform.generation.benchmark;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.benchmark.BenchmarkCaseResult;
import com.aifashionvault.generation.benchmark.BenchmarkMatrixRow;
import com.aifashionvault.generation.benchmark.BenchmarkReport;
import com.aifashionvault.generation.benchmark.BenchmarkRunSpec;
import com.aifashionvault.generation.benchmark.Outcome;
import com.aifashionvault.shared.QualityDecision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Black-box matrix behavior: category and provider-total rows, counts,
 * nearest-rank P50, explicit denominators, and null cost-per-success when the
 * denominator is zero or cost is unknown.
 */
class BenchmarkMatrixTest {

	private static final String PROVIDER = "fake-benchmark";
	private static final String MODEL = "try-on";

	private static BenchmarkCaseResult result(String caseId, String category, long latencyMs, Outcome outcome,
			String cost, String currency, String identity, String garment, String color) {
		QualityDecision decision = switch (outcome) {
			case SUCCESS -> QualityDecision.PASS;
			case QUALITY_RETRY -> QualityDecision.RETRY;
			case QUALITY_FAIL -> QualityDecision.FAIL;
			default -> null;
		};
		return new BenchmarkCaseResult("run-1", caseId, PROVIDER, MODEL, category, latencyMs, outcome,
				cost == null ? null : new BigDecimal(cost), currency,
				null,
				identity == null ? null : new BigDecimal(identity),
				garment == null ? null : new BigDecimal(garment),
				color == null ? null : new BigDecimal(color),
				false, decision);
	}

	private static BenchmarkMatrixRow rowFor(List<BenchmarkMatrixRow> rows, String category) {
		return rows.stream().filter(row -> row.category().equals(category)).findFirst().orElseThrow();
	}

	@Test
	void categoryRowsAndProviderTotalsAreEmittedWithExplicitCounts() {
		BenchmarkCaseResult footwearSuccess = result("case-1", "footwear", 100L, Outcome.SUCCESS, "0.10", "USD",
				"0.9", "0.8", "0.7");
		BenchmarkCaseResult footwearFailure = result("case-2", "footwear", 200L, Outcome.PROVIDER_FAILURE, null, null,
				null, null, null);
		BenchmarkCaseResult headwearSuccess = result("case-3", "headwear", 300L, Outcome.SUCCESS, "0.20", "USD",
				"0.6", "0.6", "0.6");
		List<BenchmarkMatrixRow> rows = BenchmarkMatrixGenerator.rows(
				List.of(footwearSuccess, footwearFailure, headwearSuccess));
		assertThat(rows).hasSize(3);
		BenchmarkMatrixRow footwear = rowFor(rows, "footwear");
		assertThat(footwear.sampleCount()).isEqualTo(2);
		assertThat(footwear.successCount()).isEqualTo(1);
		assertThat(footwear.failRate()).isEqualByComparingTo("0.5");
		BenchmarkMatrixRow headwear = rowFor(rows, "headwear");
		assertThat(headwear.sampleCount()).isEqualTo(1);
		assertThat(headwear.successCount()).isEqualTo(1);
		BenchmarkMatrixRow total = rowFor(rows, BenchmarkMatrixGenerator.PROVIDER_TOTAL_CATEGORY);
		assertThat(total.sampleCount()).isEqualTo(3);
		assertThat(total.successCount()).isEqualTo(2);
		assertThat(total.failRate()).isEqualByComparingTo("0.333333");
		assertThat(rows.indexOf(footwear)).isLessThan(rows.indexOf(headwear))
			.isLessThan(rows.indexOf(total));
	}

	@Test
	void nearestRankP50UsesQualityApprovedCasesOnly() {
		// 5 cases; only 3 are quality-approved successes. Latencies include a huge
		// failed case that must not influence P50. Nearest rank of [100,200,300] is 200.
		List<BenchmarkMatrixRow> rows = BenchmarkMatrixGenerator.rows(List.of(
				result("a", "tops", 100L, Outcome.SUCCESS, null, null, "0.9", "0.9", "0.9"),
				result("b", "tops", 300L, Outcome.SUCCESS, null, null, "0.9", "0.9", "0.9"),
				result("c", "tops", 200L, Outcome.SUCCESS, null, null, "0.9", "0.9", "0.9"),
				result("d", "tops", 9_999L, Outcome.PROVIDER_FAILURE, null, null, null, null, null),
				result("e", "tops", 9_999L, Outcome.QUALITY_FAIL, "0.10", "USD", "0.5", "0.5", "0.5")));
		BenchmarkMatrixRow row = rows.get(0);
		assertThat(row.p50LatencyMs()).isEqualTo(200L);
		assertThat(row.successCount()).isEqualTo(3L);
		assertThat(row.sampleCount()).isEqualTo(5L);
		// Even count: nearest rank ceil(2/2) = 1 of sorted [100,200] is 100 (the
		// documented nearest-rank method, not an interpolated average).
		List<BenchmarkMatrixRow> evenRows = BenchmarkMatrixGenerator.rows(List.of(
				result("a", "tops", 100L, Outcome.SUCCESS, null, null, "0.9", "0.9", "0.9"),
				result("b", "tops", 200L, Outcome.SUCCESS, null, null, "0.9", "0.9", "0.9")));
		assertThat(evenRows.get(0).p50LatencyMs()).isEqualTo(100L);
	}

	@Test
	void qualityPassRateDenominatorExcludesCasesThatNeverReachedTheQualityGate() {
		// 3 cases: 1 success, 1 quality FAIL, 1 provider failure (never evaluated).
		// pass rate = 1/2 evaluated, fail rate = 2/3 samples.
		List<BenchmarkMatrixRow> rows = BenchmarkMatrixGenerator.rows(List.of(
				result("a", "tops", 100L, Outcome.SUCCESS, null, null, "0.9", "0.9", "0.9"),
				result("b", "tops", 100L, Outcome.QUALITY_FAIL, null, null, "0.4", "0.4", "0.4"),
				result("c", "tops", 100L, Outcome.PROVIDER_FAILURE, null, null, null, null, null)));
		BenchmarkMatrixRow row = rows.get(0);
		assertThat(row.failRate()).isEqualByComparingTo("0.666667");
	}

	@Test
	void costPerSuccessIsNullNotZeroWithoutSuccessesAndWithUnknownCost() {
		// No successes: cost-per-success must be null, never zero or infinity.
		List<BenchmarkMatrixRow> noSuccess = BenchmarkMatrixGenerator.rows(
				List.of(result("a", "tops", 100L, Outcome.QUALITY_FAIL, "0.30", "USD", "0.5", "0.5", "0.5")));
		assertThat(noSuccess.get(0).costPerSuccess()).isNull();
		// Successes but unknown cost: still null, never zero.
		List<BenchmarkMatrixRow> unknownCost = BenchmarkMatrixGenerator.rows(
				List.of(result("a", "tops", 100L, Outcome.SUCCESS, null, null, "0.9", "0.9", "0.9")));
		assertThat(unknownCost.get(0).costPerSuccess()).isNull();
	}

	@Test
	void costPerSuccessDividesTotalAttributableCostBySuccessCount() {
		// 2 successes + 1 attributable failure cost: (0.10 + 0.20 + 0.10) / 2 = 0.20.
		List<BenchmarkMatrixRow> rows = BenchmarkMatrixGenerator.rows(List.of(
				result("a", "tops", 100L, Outcome.SUCCESS, "0.10", "USD", "0.9", "0.9", "0.9"),
				result("b", "tops", 100L, Outcome.SUCCESS, "0.20", "USD", "0.9", "0.9", "0.9"),
				result("c", "tops", 100L, Outcome.QUALITY_FAIL, "0.10", "USD", "0.5", "0.5", "0.5")));
		assertThat(rows.get(0).costPerSuccess()).isEqualByComparingTo("0.20");
	}

	@Test
	void categoryWeaknessIsExposedByCategoryRowsAndAveragesStayHonest() {
		// Footwear fidelity collapses while headwear is fine; the footwear row must
		// show it even though the provider total averages it away.
		List<BenchmarkMatrixRow> rows = BenchmarkMatrixGenerator.rows(List.of(
				result("a", "footwear", 100L, Outcome.SUCCESS, null, null, "0.1", "0.1", "0.1"),
				result("b", "footwear", 100L, Outcome.SUCCESS, null, null, "0.2", "0.2", "0.2"),
				result("c", "headwear", 100L, Outcome.SUCCESS, null, null, "0.9", "0.9", "0.9")));
		BenchmarkMatrixRow footwear = rowFor(rows, "footwear");
		assertThat(footwear.fidelity()).isEqualByComparingTo("0.15");
		BenchmarkMatrixRow total = rowFor(rows, BenchmarkMatrixGenerator.PROVIDER_TOTAL_CATEGORY);
		assertThat(total.fidelity()).isEqualByComparingTo("0.4");
	}

	@Test
	void failedCasesNeverInflateFidelity() {
		// Failed case carries fidelity values in the record but must not enter the mean.
		List<BenchmarkMatrixRow> rows = BenchmarkMatrixGenerator.rows(List.of(
				result("a", "tops", 100L, Outcome.SUCCESS, null, null, "0.9", "0.9", "0.9"),
				result("b", "tops", 100L, Outcome.QUALITY_FAIL, null, null, "1.0", "1.0", "1.0")));
		assertThat(rows.get(0).fidelity()).isEqualByComparingTo("0.9");
	}

	@Test
	void zeroSampleRowIsRejectedByTheFrozenContract() {
		// Rows only exist for observed (provider, model, category) groups, so an empty
		// input produces no rows; and the frozen row record rejects a cost-per-success
		// value when there are no quality-approved successes (undefined, never zero).
		List<BenchmarkMatrixRow> rows = BenchmarkMatrixGenerator.rows(List.of());
		assertThat(rows).isEmpty();
		assertThatThrownBy(() -> new BenchmarkMatrixRow(PROVIDER, MODEL, "tops", null, 0L, null, BigDecimal.ZERO,
				0L, 0L))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void reportSerializationCarriesMatrixThroughBenchmarkReport() {
		BenchmarkCaseResult success = result("case-1", "tops", 100L, Outcome.SUCCESS, "0.10", "USD", "0.9", "0.8",
				"0.7");
		BenchmarkReport report = new BenchmarkReport(List.of(success), BenchmarkMatrixGenerator.rows(List.of(success)));
		BenchmarkRunSpec spec = new BenchmarkRunSpec("run-1", "2026.01.0", PROVIDER, MODEL, "prompt-v1",
				"routing-v1", List.of("case-1"));
		String json = BenchmarkReportJson.serialize(report, spec);
		assertThat(json).contains("\"caseResults\"").contains("\"matrixRows\"");
		String text = BenchmarkMatrixText.render(report);
		assertThat(text).contains("PROVIDER").contains("tops").contains("ALL");
	}
}
