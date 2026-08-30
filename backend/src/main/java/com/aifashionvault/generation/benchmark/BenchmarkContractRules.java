package com.aifashionvault.generation.benchmark;

import com.aifashionvault.shared.QualityDecision;

import java.math.BigDecimal;

final class BenchmarkContractRules {

	private BenchmarkContractRules() {
	}

	static String requireNonBlank(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " is required");
		}
		return value;
	}

	static void requireNonNegative(BigDecimal value, String field) {
		if (value != null && value.signum() < 0) {
			throw new IllegalArgumentException(field + " must not be negative");
		}
	}

	static void requireUnitInterval(BigDecimal value, String field) {
		if (value != null && (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0)) {
			throw new IllegalArgumentException(field + " must be within [0,1]");
		}
	}

	static void requireQualityDecisionConsistency(Outcome outcome, QualityDecision qualityGateResult) {
		QualityDecision expected = switch (outcome) {
			case SUCCESS -> QualityDecision.PASS;
			case QUALITY_RETRY -> QualityDecision.RETRY;
			case QUALITY_FAIL -> QualityDecision.FAIL;
			case PROVIDER_FAILURE, OUTPUT_INVALID, EVALUATOR_UNAVAILABLE, FIXTURE_INVALID -> null;
		};
		if (qualityGateResult != expected) {
			throw new IllegalArgumentException(
				"outcome " + outcome + " is inconsistent with quality decision " + qualityGateResult);
		}
	}
}
