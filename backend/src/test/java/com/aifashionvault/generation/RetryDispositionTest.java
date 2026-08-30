package com.aifashionvault.generation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RetryDispositionTest {

	@Test
	void valuesMatchTheFrozenRetryTaxonomyInDeclarationOrder() {
		assertThat(RetryDisposition.values())
			.extracting(Enum::name)
			.containsExactly(
				"NO_RETRY",
				"REPLAY_SAME_ATTEMPT",
				"WAIT_EXISTING_ATTEMPT",
				"NEW_TECHNICAL_ATTEMPT",
				"QUALITY_REGENERATE",
				"EVALUATOR_RETRY",
				"FALLBACK_NEW_ATTEMPT");
	}
}
