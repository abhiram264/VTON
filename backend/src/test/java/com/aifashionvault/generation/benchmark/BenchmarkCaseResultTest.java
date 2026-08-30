package com.aifashionvault.generation.benchmark;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.QualityDecision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BenchmarkCaseResultTest {

	@Test
	void componentShapeMatchesFrozenSeam() {
		assertThat(BenchmarkCaseResult.class.getRecordComponents())
			.extracting(java.lang.reflect.RecordComponent::getName)
			.containsExactly(
				"runId",
				"caseId",
				"providerKey",
				"modelOrMode",
				"category",
				"latencyMs",
				"outcome",
				"cost",
				"currency",
				"humanRating",
				"identityFidelity",
				"garmentFidelity",
				"colorFidelity",
				"artifacts",
				"qualityGateResult");
		assertThat(BenchmarkCaseResult.class.getRecordComponents())
			.extracting(java.lang.reflect.RecordComponent::getType)
			.containsExactly(
				String.class,
				String.class,
				String.class,
				String.class,
				String.class,
				long.class,
				Outcome.class,
				BigDecimal.class,
				String.class,
				BigDecimal.class,
				BigDecimal.class,
				BigDecimal.class,
				BigDecimal.class,
				boolean.class,
				QualityDecision.class);
	}

	@Test
	void constructsFullyPopulatedSuccess() {
		BenchmarkCaseResult result = result(Outcome.SUCCESS, new BigDecimal("0.0100"), "USD",
			new BigDecimal("4"), new BigDecimal("0.98"), new BigDecimal("0.97"), new BigDecimal("0.96"),
			false, QualityDecision.PASS);
		assertThat(result.runId()).isEqualTo("run-1");
		assertThat(result.caseId()).isEqualTo("case-1");
		assertThat(result.latencyMs()).isEqualTo(1500L);
		assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
		assertThat(result.qualityGateResult()).isEqualTo(QualityDecision.PASS);
		assertThat(result.artifacts()).isFalse();
	}

	@Test
	void nullQualityDecisionsAreLegalForNonQualityOutcomes() {
		for (Outcome outcome : new Outcome[] {
				Outcome.PROVIDER_FAILURE, Outcome.OUTPUT_INVALID, Outcome.EVALUATOR_UNAVAILABLE,
				Outcome.FIXTURE_INVALID}) {
			assertThat(result(outcome, null, null, null, null, null, null, false, null).outcome())
				.isEqualTo(outcome);
		}
	}

	@Test
	void blankOrNullIdsFailClosed() {
		String[] fields = {"runId", "caseId", "providerKey", "modelOrMode", "category"};
		for (String field : fields) {
			assertThatThrownBy(() -> result(field, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining(field);
		}
	}

	@Test
	void nullOutcomeFailsClosed() {
		BenchmarkCaseResult base = result(Outcome.SUCCESS, null, null, null, null, null, null, false,
			QualityDecision.PASS);
		assertThatThrownBy(() -> new BenchmarkCaseResult(base.runId(), base.caseId(), base.providerKey(),
			base.modelOrMode(), base.category(), base.latencyMs(), null, base.cost(), base.currency(),
			base.humanRating(), base.identityFidelity(), base.garmentFidelity(), base.colorFidelity(),
			base.artifacts(), base.qualityGateResult()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("outcome");
	}

	@Test
	void negativeLatencyFailsClosed() {
		assertThatThrownBy(() -> result(-1L, Outcome.SUCCESS, QualityDecision.PASS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("latencyMs");
	}

	@Test
	void outcomeQualityDecisionConsistencyFailsClosed() {
		assertThatThrownBy(() -> result(Outcome.SUCCESS, QualityDecision.RETRY))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("inconsistent");
		assertThatThrownBy(() -> result(Outcome.SUCCESS, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("inconsistent");
		assertThatThrownBy(() -> result(Outcome.QUALITY_RETRY, QualityDecision.PASS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("inconsistent");
		assertThatThrownBy(() -> result(Outcome.QUALITY_FAIL, QualityDecision.RETRY))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("inconsistent");
		assertThatThrownBy(() -> result(Outcome.PROVIDER_FAILURE, QualityDecision.FAIL))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("inconsistent");
		assertThatThrownBy(() -> result(Outcome.OUTPUT_INVALID, QualityDecision.PASS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("inconsistent");
		assertThatThrownBy(() -> result(Outcome.EVALUATOR_UNAVAILABLE, QualityDecision.RETRY))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("inconsistent");
		assertThatThrownBy(() -> result(Outcome.FIXTURE_INVALID, QualityDecision.FAIL))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("inconsistent");
	}

	@Test
	void unknownCostIsNullNeverZeroAndCurrencyAccompaniesDeterminableCost() {
		assertThatThrownBy(() -> result(Outcome.SUCCESS, new BigDecimal("0.0100"), null, null, null, null, null,
			false, QualityDecision.PASS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("currency");
		assertThatThrownBy(() -> result(Outcome.SUCCESS, null, "USD", null, null, null, null, false,
			QualityDecision.PASS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("currency");
		assertThat(result(Outcome.SUCCESS, null, null, null, null, null, null, false, QualityDecision.PASS)
			.cost()).isNull();
	}

	@Test
	void negativeCostFailsClosed() {
		assertThatThrownBy(() -> result(Outcome.SUCCESS, new BigDecimal("-0.01"), "USD", null, null, null, null,
			false, QualityDecision.PASS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("cost");
	}

	@Test
	void negativeHumanRatingFailsClosed() {
		assertThatThrownBy(() -> result(Outcome.SUCCESS, null, null, new BigDecimal("-0.01"), null, null, null,
			false, QualityDecision.PASS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("humanRating");
	}

	@Test
	void fidelityValuesAreBoundedToUnitInterval() {
		assertThatThrownBy(() -> result(Outcome.SUCCESS, null, null, null, new BigDecimal("-0.01"), null, null,
			false, QualityDecision.PASS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("identityFidelity");
		assertThatThrownBy(() -> result(Outcome.SUCCESS, null, null, null, new BigDecimal("1.01"), null, null,
			false, QualityDecision.PASS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("identityFidelity");
		assertThatThrownBy(() -> result(Outcome.SUCCESS, null, null, null, null, new BigDecimal("-0.01"), null,
			false, QualityDecision.PASS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("garmentFidelity");
		assertThatThrownBy(() -> result(Outcome.SUCCESS, null, null, null, null, new BigDecimal("1.01"), null,
			false, QualityDecision.PASS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("garmentFidelity");
		assertThatThrownBy(() -> result(Outcome.SUCCESS, null, null, null, null, null, new BigDecimal("-0.01"),
			false, QualityDecision.PASS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("colorFidelity");
		assertThatThrownBy(() -> result(Outcome.SUCCESS, null, null, null, null, null, new BigDecimal("1.01"),
			false, QualityDecision.PASS))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("colorFidelity");
		assertThat(result(Outcome.SUCCESS, null, null, null, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE,
			false, QualityDecision.PASS).identityFidelity()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	private static BenchmarkCaseResult result(Outcome outcome, QualityDecision decision) {
		return new BenchmarkCaseResult("run-1", "case-1", "fashn", "mode-x", "tops", 1500L, outcome, null,
			null, null, null, null, null, false, decision);
	}

	private static BenchmarkCaseResult result(String field, String value) {
		String runId = "runId".equals(field) ? value : "run-1";
		String caseId = "caseId".equals(field) ? value : "case-1";
		String providerKey = "providerKey".equals(field) ? value : "fashn";
		String modelOrMode = "modelOrMode".equals(field) ? value : "mode-x";
		String category = "category".equals(field) ? value : "tops";
		return new BenchmarkCaseResult(runId, caseId, providerKey, modelOrMode, category, 1500L,
			Outcome.SUCCESS, null, null, null, null, null, null, false, QualityDecision.PASS);
	}

	private static BenchmarkCaseResult result(long latencyMs, Outcome outcome, QualityDecision decision) {
		return new BenchmarkCaseResult("run-1", "case-1", "fashn", "mode-x", "tops", latencyMs, outcome, null,
			null, null, null, null, null, false, decision);
	}

	private static BenchmarkCaseResult result(Outcome outcome, BigDecimal cost, String currency,
		BigDecimal humanRating, BigDecimal identityFidelity, BigDecimal garmentFidelity,
		BigDecimal colorFidelity, boolean artifacts, QualityDecision decision) {
		return new BenchmarkCaseResult("run-1", "case-1", "fashn", "mode-x", "tops", 1500L, outcome, cost,
			currency, humanRating, identityFidelity, garmentFidelity, colorFidelity, artifacts, decision);
	}
}
