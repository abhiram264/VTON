package com.aifashionvault.shared;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QualityDecisionTest {

	@Test
	void valuesAreExactlyPassRetryFail() {
		assertThat(QualityDecision.values()).hasSize(3);
		assertThat(QualityDecision.values())
			.extracting(Enum::name)
			.containsExactly("PASS", "RETRY", "FAIL");
	}
}
