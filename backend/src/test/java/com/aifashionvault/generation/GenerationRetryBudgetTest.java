package com.aifashionvault.generation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationRetryBudgetTest {

	@Test
	void maxLogicalAttemptsIsBaseStepsPlusTwo() {
		assertThat(GenerationRetryBudget.ADDITIONAL_LOGICAL_ATTEMPTS).isEqualTo(2);
		assertThat(GenerationRetryBudget.forStepCount(1).maxLogicalAttempts()).isEqualTo(3);
		assertThat(GenerationRetryBudget.forStepCount(1).maxPaidAttempts()).isEqualTo(3);
		assertThat(GenerationRetryBudget.forStepCount(2).maxLogicalAttempts()).isEqualTo(4);
		assertThat(GenerationRetryBudget.forStepCount(3).maxLogicalAttempts()).isEqualTo(5);
		assertThat(GenerationRetryBudget.DEFAULT_MAX_PAID_ATTEMPTS).isEqualTo(3);
	}

	@Test
	void singleItemJobAllowsThreeLogicalAttempts() {
		GenerationRetryBudget budget = GenerationRetryBudget.forStepCount(1);

		assertThat(budget.allowsLogicalAttempt(0)).isTrue();
		assertThat(budget.allowsLogicalAttempt(1)).isTrue();
		assertThat(budget.allowsLogicalAttempt(2)).isTrue();
		assertThat(budget.allowsLogicalAttempt(3)).isFalse();
	}

	@Test
	void replaySafeRetransmitDoesNotConsumeLogicalAttempt() {
		GenerationRetryBudget budget = GenerationRetryBudget.forStepCount(1);

		assertThat(budget.consumesLogicalAttempt(true)).isFalse();
		assertThat(budget.consumesLogicalAttempt(false)).isTrue();
		assertThat(budget.allowsLogicalAttempt(3)).isFalse();
		assertThat(budget.allowsReplaySafeRetransmit(3, 1)).isTrue();
	}

	@Test
	void replaySafeRetransmitStopsAtThreeTotalTransmissions() {
		GenerationRetryBudget budget = GenerationRetryBudget.forStepCount(1);

		assertThat(GenerationRetryBudget.MAX_REPLAY_SAFE_TRANSMISSIONS).isEqualTo(3);
		assertThat(budget.allowsReplaySafeRetransmit(3, 1)).isTrue();
		assertThat(budget.allowsReplaySafeRetransmit(3, 2)).isTrue();
		assertThat(budget.allowsReplaySafeRetransmit(3, 3)).isFalse();
	}

	@Test
	void replaySafeRetransmitRejectsUsageBeyondPaidBudget() {
		GenerationRetryBudget budget = GenerationRetryBudget.forStepCount(1);

		assertThat(budget.allowsReplaySafeRetransmit(3, 1)).isTrue();
		assertThat(budget.allowsReplaySafeRetransmit(3, 2)).isTrue();
		assertThatThrownBy(() -> budget.allowsReplaySafeRetransmit(4, 1))
			.isInstanceOf(IllegalArgumentException.class);
	}


	@Test
	void atMostOneQualityTriggeredRetryPerStep() {
		GenerationRetryBudget budget = GenerationRetryBudget.forStepCount(1);

		assertThat(GenerationRetryBudget.MAX_QUALITY_RETRIES_PER_STEP).isEqualTo(1);
		assertThat(budget.allowsQualityRetry(0)).isTrue();
		assertThat(budget.allowsQualityRetry(1)).isFalse();
		assertThat(budget.allowsQualityTriggeredRetry(0, 1)).isTrue();
		assertThat(budget.allowsQualityTriggeredRetry(1, 1)).isFalse();
		assertThat(budget.allowsQualityTriggeredRetry(0, 3)).isFalse();
	}

	@Test
	void rejectsNonPositiveStepCountAndNegativeUsage() {
		assertThatThrownBy(() -> GenerationRetryBudget.forStepCount(0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationRetryBudget(0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> GenerationRetryBudget.forStepCount(1).allowsLogicalAttempt(-1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> GenerationRetryBudget.forStepCount(1).allowsQualityRetry(-1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> GenerationRetryBudget.forStepCount(1).allowsReplaySafeRetransmit(-1, 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> GenerationRetryBudget.forStepCount(1).allowsReplaySafeRetransmit(0, -1))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void maxLogicalAttemptsForAddsTwoToEveryStepCount() {
		assertThat(GenerationRetryBudget.maxLogicalAttemptsFor(1)).isEqualTo(3);
		assertThat(GenerationRetryBudget.maxLogicalAttemptsFor(2)).isEqualTo(4);
		assertThat(GenerationRetryBudget.maxLogicalAttemptsFor(3)).isEqualTo(5);

		assertThatThrownBy(() -> GenerationRetryBudget.maxLogicalAttemptsFor(0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> GenerationRetryBudget.maxLogicalAttemptsFor(-1))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void v2CeilingsAreFixedPerJobAndPerCandidate() {
		assertThat(GenerationRetryBudget.MAX_TECHNICAL_RETRIES_PER_JOB).isEqualTo(2);
		assertThat(GenerationRetryBudget.MAX_EVALUATOR_RETRIES_PER_CANDIDATE).isEqualTo(1);
	}

	@Test
	void oneStepJobMaySpendTwoExtraPaidAttempts() {
		// maxPaidAttempts=3 and one mandatory step whose baseline still requires
		// execution: an extra attempt may be admitted twice before the last paid
		// slot must be reserved for the baseline.
		assertThat(GenerationRetryBudget.remainingPaidSlotsCoverBaselines(3, 0, 1)).isTrue();
		assertThat(GenerationRetryBudget.remainingPaidSlotsCoverBaselines(3, 1, 1)).isTrue();
		assertThat(GenerationRetryBudget.remainingPaidSlotsCoverBaselines(3, 2, 1)).isFalse();

		// Proposing the baseline itself leaves no baseline outstanding.
		assertThat(GenerationRetryBudget.remainingPaidSlotsCoverBaselines(3, 0, 0)).isTrue();
		assertThat(GenerationRetryBudget.remainingPaidSlotsCoverBaselines(3, 2, 0)).isTrue();
		assertThat(GenerationRetryBudget.remainingPaidSlotsCoverBaselines(3, 3, 0)).isFalse();
	}

	@Test
	void twoStepJobAllowsAtMostOneExtraWhileSecondBaselineRemains() {
		// The first baseline is executed (one paid slot used) while the second
		// baseline still requires execution: exactly one extra attempt may be
		// admitted, then the remaining slots must be reserved.
		assertThat(GenerationRetryBudget.remainingPaidSlotsCoverBaselines(3, 1, 1)).isTrue();
		assertThat(GenerationRetryBudget.remainingPaidSlotsCoverBaselines(3, 2, 1)).isFalse();

		// Proposing the second baseline itself leaves no baseline outstanding.
		assertThat(GenerationRetryBudget.remainingPaidSlotsCoverBaselines(3, 1, 0)).isTrue();
		assertThat(GenerationRetryBudget.remainingPaidSlotsCoverBaselines(3, 2, 0)).isTrue();
		assertThat(GenerationRetryBudget.remainingPaidSlotsCoverBaselines(3, 3, 0)).isFalse();
	}

	@Test
	void threeStepJobHasNoSparePaidAttemptWhileAllBaselinesRemain() {
		// All three baselines still require execution: no extra paid attempt may
		// be admitted, while each baseline itself remains admissible in turn.
		assertThat(GenerationRetryBudget.remainingPaidSlotsCoverBaselines(3, 0, 3)).isFalse();
		assertThat(GenerationRetryBudget.remainingPaidSlotsCoverBaselines(3, 0, 2)).isTrue();
		assertThat(GenerationRetryBudget.remainingPaidSlotsCoverBaselines(3, 1, 1)).isTrue();
		assertThat(GenerationRetryBudget.remainingPaidSlotsCoverBaselines(3, 2, 0)).isTrue();

		// After all three baselines executed, nothing remains for retries.
		assertThat(GenerationRetryBudget.remainingPaidSlotsCoverBaselines(3, 3, 0)).isFalse();
	}

	@Test
	void reservationRejectsNegativeArguments() {
		assertThatThrownBy(() -> GenerationRetryBudget.remainingPaidSlotsCoverBaselines(-1, 0, 0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> GenerationRetryBudget.remainingPaidSlotsCoverBaselines(3, -1, 0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> GenerationRetryBudget.remainingPaidSlotsCoverBaselines(3, 0, -1))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
