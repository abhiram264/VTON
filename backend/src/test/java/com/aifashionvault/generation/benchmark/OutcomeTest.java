package com.aifashionvault.generation.benchmark;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OutcomeTest {

	@Test
	void outcomeValuesAreExactlyTheFrozenSevenInFrozenOrder() {
		assertThat(Outcome.values())
			.extracting(Enum::name)
			.containsExactly(
				"SUCCESS",
				"PROVIDER_FAILURE",
				"OUTPUT_INVALID",
				"QUALITY_RETRY",
				"QUALITY_FAIL",
				"EVALUATOR_UNAVAILABLE",
				"FIXTURE_INVALID");
	}

	@Test
	void valueOfResolvesEveryFrozenName() {
		for (Outcome outcome : Outcome.values()) {
			assertThat(Outcome.valueOf(outcome.name())).isEqualTo(outcome);
		}
	}
}
