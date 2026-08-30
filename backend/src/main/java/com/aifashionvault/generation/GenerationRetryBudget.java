package com.aifashionvault.generation;

public record GenerationRetryBudget(int maxPaidAttempts) {

	public static final int ADDITIONAL_LOGICAL_ATTEMPTS = 2;
	public static final int DEFAULT_MAX_PAID_ATTEMPTS = 3;
	public static final int MAX_QUALITY_RETRIES_PER_STEP = 1;
	public static final int MAX_REPLAY_SAFE_TRANSMISSIONS = 3;
	public static final int MAX_TECHNICAL_RETRIES_PER_JOB = 2;
	public static final int MAX_EVALUATOR_RETRIES_PER_CANDIDATE = 1;

	public GenerationRetryBudget {
		if (maxPaidAttempts < 1) {
			throw new IllegalArgumentException("maxPaidAttempts must be >= 1");
		}
	}

	public static GenerationRetryBudget forStepCount(int renderStepCount) {
		if (renderStepCount < 1) {
			throw new IllegalArgumentException("renderStepCount must be >= 1");
		}
		return new GenerationRetryBudget(renderStepCount + ADDITIONAL_LOGICAL_ATTEMPTS);
	}

	public int maxLogicalAttempts() {
		return maxPaidAttempts;
	}

	public boolean consumesLogicalAttempt(boolean replaySafeRetransmit) {
		return !replaySafeRetransmit;
	}

	public boolean allowsReplaySafeRetransmit(int logicalAttemptsUsed, int transmissionCount) {
		if (logicalAttemptsUsed < 0) {
			throw new IllegalArgumentException("logicalAttemptsUsed must be >= 0");
		}
		if (logicalAttemptsUsed > maxPaidAttempts) {
			throw new IllegalArgumentException("logicalAttemptsUsed must be <= maxPaidAttempts");
		}
		if (transmissionCount < 0) {
			throw new IllegalArgumentException("transmissionCount must be >= 0");
		}
		return transmissionCount < MAX_REPLAY_SAFE_TRANSMISSIONS;
	}

	public boolean allowsLogicalAttempt(int logicalAttemptsUsed) {
		if (logicalAttemptsUsed < 0) {
			throw new IllegalArgumentException("logicalAttemptsUsed must be >= 0");
		}
		return logicalAttemptsUsed < maxPaidAttempts;
	}

	public boolean allowsQualityRetry(int qualityRetriesUsedOnStep) {
		if (qualityRetriesUsedOnStep < 0) {
			throw new IllegalArgumentException("qualityRetriesUsedOnStep must be >= 0");
		}
		return qualityRetriesUsedOnStep < MAX_QUALITY_RETRIES_PER_STEP;
	}

	public boolean allowsQualityTriggeredRetry(int qualityRetriesUsedOnStep, int logicalAttemptsUsed) {
		return allowsQualityRetry(qualityRetriesUsedOnStep) && allowsLogicalAttempt(logicalAttemptsUsed);
	}

	/**
	 * Resolves the maximum logical attempts for a job with the given total
	 * mandatory render steps: one baseline per step plus the settled
	 * additional logical attempts.
	 */
	public static int maxLogicalAttemptsFor(int totalSteps) {
		if (totalSteps < 1) {
			throw new IllegalArgumentException("totalSteps must be >= 1");
		}
		return totalSteps + ADDITIONAL_LOGICAL_ATTEMPTS;
	}

	/**
	 * Determines whether the remaining paid-capable slots still cover every
	 * mandatory baseline before a proposed additional execution may proceed.
	 *
	 * <p>The proposed execution consumes one paid slot, so the slots
	 * remaining after it are {@code maxPaidAttempts - paidAttemptsUsed - 1}.
	 * {@code baselinesStillRequiringExecution} counts mandatory render steps
	 * that still lack their baseline execution AFTER the proposed execution;
	 * the caller subtracts one when the proposed execution is itself such a
	 * baseline. With {@code maxPaidAttempts=3}, a one-step job may spend two
	 * extra paid attempts, a two-step job at most one extra while the second
	 * baseline remains, and a three-step job zero extras while all three
	 * baselines remain.</p>
	 */
	public static boolean remainingPaidSlotsCoverBaselines(
		int maxPaidAttempts,
		int paidAttemptsUsed,
		int baselinesStillRequiringExecution) {
		if (maxPaidAttempts < 0) {
			throw new IllegalArgumentException("maxPaidAttempts must be >= 0");
		}
		if (paidAttemptsUsed < 0) {
			throw new IllegalArgumentException("paidAttemptsUsed must be >= 0");
		}
		if (baselinesStillRequiringExecution < 0) {
			throw new IllegalArgumentException("baselinesStillRequiringExecution must be >= 0");
		}
		return maxPaidAttempts - paidAttemptsUsed - 1 >= baselinesStillRequiringExecution;
	}
}
