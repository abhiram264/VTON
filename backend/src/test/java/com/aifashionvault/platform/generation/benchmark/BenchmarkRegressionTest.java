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
 * Baseline-vs-candidate regression comparison: matched-case deltas with
 * absolute and percentage changes, missing-pair surfacing, and
 * benchmark-version mismatch failing closed. Also proves serialized JSON and
 * text contain no forbidden markers and no unbounded provider/media payload,
 * and that no deployment approval field is emitted.
 */
class BenchmarkRegressionTest {

	private static final String VERSION = "2026.01.0";
	private static final String PROVIDER = "fake-benchmark";
	private static final String MODEL = "try-on";

	private static BenchmarkCaseResult result(String runId, String caseId, String category, long latencyMs,
			Outcome outcome, String cost, String identity, String garment, String color) {
		QualityDecision decision = switch (outcome) {
			case SUCCESS -> QualityDecision.PASS;
			case QUALITY_RETRY -> QualityDecision.RETRY;
			case QUALITY_FAIL -> QualityDecision.FAIL;
			default -> null;
		};
		return new BenchmarkCaseResult(runId, caseId, PROVIDER, MODEL, category, latencyMs, outcome,
				cost == null ? null : new BigDecimal(cost), cost == null ? null : "USD", null,
				identity == null ? null : new BigDecimal(identity),
				garment == null ? null : new BigDecimal(garment),
				color == null ? null : new BigDecimal(color),
				false, decision);
	}

	private static BenchmarkRegressionComparator.DimensionDelta dimension(
			List<BenchmarkRegressionComparator.DimensionDelta> deltas, String dimension, String category) {
		return deltas.stream()
			.filter(delta -> delta.dimension().equals(dimension) && delta.category().equals(category))
			.findFirst()
			.orElseThrow();
	}

	@Test
	void changedValuesProduceAbsoluteAndPercentageDeltas() {
		BenchmarkCaseResult baseline = result("baseline", "case-1", "tops", 100L, Outcome.SUCCESS, "0.10", "0.9",
				"0.9", "0.9");
		// fidelity 0.6 vs 0.9 = -0.3 absolute = -33.333...% ; latency +50ms.
		BenchmarkCaseResult candidate = result("candidate", "case-1", "tops", 150L, Outcome.SUCCESS, "0.20", "0.5",
				"0.6", "0.7");
		BenchmarkRegressionComparator.RegressionReport report = BenchmarkRegressionComparator.compare(
				List.of(baseline), List.of(candidate), VERSION, VERSION);
		assertThat(report.caseDeltas()).hasSize(1);
		BenchmarkRegressionComparator.CaseDelta delta = report.caseDeltas().get(0);
		assertThat(delta.fidelityDelta()).isEqualByComparingTo("-0.3");
		assertThat(delta.fidelityDeltaPercent()).isEqualByComparingTo("-33.333333");
		assertThat(delta.latencyDeltaMs()).isEqualTo(50L);
		assertThat(delta.baselineOutcome()).isEqualTo(Outcome.SUCCESS);
		assertThat(delta.candidateOutcome()).isEqualTo(Outcome.SUCCESS);
	}

	@Test
	void dimensionDeltasCoverAllRequiredDimensionsOverallAndPerCategory() {
		BenchmarkCaseResult baseline = result("baseline", "case-1", "tops", 100L, Outcome.SUCCESS, "0.10", "0.9",
				"0.9", "0.9");
		BenchmarkCaseResult candidate = result("candidate", "case-1", "tops", 200L, Outcome.QUALITY_FAIL, "0.10",
				"0.5", "0.5", "0.5");
		BenchmarkRegressionComparator.RegressionReport report = BenchmarkRegressionComparator.compare(
				List.of(baseline), List.of(candidate), VERSION, VERSION);
		List<BenchmarkRegressionComparator.DimensionDelta> deltas = report.dimensionDeltas();
		// Required comparison dimensions exist for both overall and the category.
		for (String dimension : List.of("fidelity", "p50LatencyMs", "failRate", "qualityPassRate",
				"costPerSuccess")) {
			assertThat(dimension(deltas, dimension, BenchmarkMatrixGenerator.PROVIDER_TOTAL_CATEGORY)).isNotNull();
			assertThat(dimension(deltas, dimension, "tops")).isNotNull();
		}
		// Quality pass rate regressed 1 -> 0 (absolute -1, percentage -100).
		assertThat(dimension(deltas, "qualityPassRate", BenchmarkMatrixGenerator.PROVIDER_TOTAL_CATEGORY).absolute())
			.isEqualByComparingTo("-1");
		assertThat(dimension(deltas, "failRate", BenchmarkMatrixGenerator.PROVIDER_TOTAL_CATEGORY).absolute())
			.isEqualByComparingTo("1");
	}

	@Test
	void missingPairsAreSurfacedNotDropped() {
		BenchmarkCaseResult baselineOnly = result("baseline", "case-baseline-only", "tops", 100L, Outcome.SUCCESS,
				"0.10", "0.9", "0.9", "0.9");
		BenchmarkCaseResult both = result("baseline", "case-both", "tops", 100L, Outcome.SUCCESS, "0.10", "0.9", "0.9",
				"0.9");
		BenchmarkCaseResult candidateBoth = result("candidate", "case-both", "tops", 120L, Outcome.SUCCESS, "0.10",
				"0.9", "0.9", "0.9");
		BenchmarkCaseResult candidateOnly = result("candidate", "case-candidate-only", "tops", 100L, Outcome.SUCCESS,
				"0.10", "0.9", "0.9", "0.9");
		BenchmarkRegressionComparator.RegressionReport report = BenchmarkRegressionComparator.compare(
				List.of(baselineOnly, both), List.of(candidateBoth, candidateOnly), VERSION, VERSION);
		assertThat(report.missingInCandidate()).containsExactly("case-baseline-only");
		assertThat(report.missingInBaseline()).containsExactly("case-candidate-only");
		assertThat(report.caseDeltas()).hasSize(1);
		assertThat(report.caseDeltas().get(0).caseId()).isEqualTo("case-both");
		assertThat(report.notes()).anyMatch(note -> note.contains("missing in candidate"));
	}

	@Test
	void benchmarkVersionMismatchFailsClosedInsteadOfComparing() {
		BenchmarkCaseResult baseline = result("baseline", "case-1", "tops", 100L, Outcome.SUCCESS, "0.10", "0.9",
				"0.9", "0.9");
		BenchmarkCaseResult candidate = result("candidate", "case-1", "tops", 100L, Outcome.SUCCESS, "0.10", "0.9",
				"0.9", "0.9");
		assertThatThrownBy(() -> BenchmarkRegressionComparator.compare(List.of(baseline), List.of(candidate), VERSION,
				"2026.02.0"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("benchmark version mismatch");
	}

	@Test
	void serializedOutputsContainNoForbiddenMarkersOrPayloads() {
		BenchmarkCaseResult success = result("run-1", "case-1", "tops", 100L, Outcome.SUCCESS, "0.10", "0.9", "0.8",
				"0.7");
		BenchmarkCaseResult failure = result("run-1", "case-2", "tops", 100L, Outcome.PROVIDER_FAILURE, null, null,
				null, null);
		BenchmarkReport report = new BenchmarkReport(List.of(success, failure),
				BenchmarkMatrixGenerator.rows(List.of(success, failure)));
		BenchmarkRunSpec spec = new BenchmarkRunSpec("run-1", VERSION, PROVIDER, MODEL, "prompt-v1", "routing-v1",
				List.of("case-1", "case-2"));
		String json = BenchmarkReportJson.serialize(report, spec);
		String text = BenchmarkMatrixText.render(report);
		BenchmarkRegressionComparator.RegressionReport regression = BenchmarkRegressionComparator.compare(
				List.of(success), List.of(success), VERSION, VERSION);
		String regressionJson = BenchmarkReportJson.serialize(regression);
		for (String output : List.of(json, text, regressionJson)) {
			String lower = output.toLowerCase();
			// Media/URL/base64 markers.
			assertThat(lower).doesNotContain("http://").doesNotContain("https://").doesNotContain("base64")
				.doesNotContain("/9j/").doesNotContain("data:image");
			// Prompt/exception/provider body leakage markers.
			assertThat(lower).doesNotContain("deadbeef").doesNotContain("stack trace").doesNotContain("exception")
				.doesNotContain("prompt body");
			// Forbidden scoring fields: assert JSON keys / column labels, not prose that
			// legitimately documents semantics ("quality-approved successes").
			assertThat(lower).doesNotContain("attractiveness").doesNotContain("beauty").doesNotContain("hotness")
				.doesNotContain("stylescore").doesNotContain("gender").doesNotContain("ethnicity");
			assertThat(json).doesNotContain("\"age\"");
			// No deployment decision automation fields.
			assertThat(json).doesNotContain("\"approved\"").doesNotContain("\"deploymentdecision\"")
				.doesNotContain("\"blocked\"");
			// No credentials or user ids.
			assertThat(lower).doesNotContain("password").doesNotContain("secret").doesNotContain("token")
				.doesNotContain("api-key").doesNotContain("credential");
		}
		assertThat(json.length()).isLessThan(4_096);
		assertThat(text.length()).isLessThan(4_096);
	}

	@Test
	void regressionReportCarriesNoDeploymentApprovalField() {
		BenchmarkCaseResult baseline = result("baseline", "case-1", "tops", 100L, Outcome.SUCCESS, "0.10", "0.9",
				"0.9", "0.9");
		BenchmarkCaseResult candidate = result("candidate", "case-1", "tops", 100L, Outcome.SUCCESS, "0.10", "0.9",
				"0.9", "0.9");
		BenchmarkRegressionComparator.RegressionReport report = BenchmarkRegressionComparator.compare(
				List.of(baseline), List.of(candidate), VERSION, VERSION);
		assertThat(report.notes()).noneMatch(note -> note.toLowerCase().contains("approve"));
	}

	@Test
	void matrixRowsRoundTripThroughTheFrozenReportShape() {
		BenchmarkCaseResult success = result("run-1", "case-1", "tops", 100L, Outcome.SUCCESS, "0.10", "0.9", "0.8",
				"0.7");
		List<BenchmarkMatrixRow> rows = BenchmarkMatrixGenerator.rows(List.of(success));
		BenchmarkReport report = new BenchmarkReport(List.of(success), rows);
		assertThat(report.matrixRows()).isEqualTo(rows);
	}
}
