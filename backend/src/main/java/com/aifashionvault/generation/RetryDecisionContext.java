package com.aifashionvault.generation;

import java.time.Duration;
import java.time.Instant;

import com.aifashionvault.generation.vton.ProviderFailureCategory;

/**
 * Immutable, fully materialized input for one deterministic retry decision.
 *
 * <p>The context carries only persisted canonical state plus explicit
 * environment facts: the decision instant, the injected jitter fraction,
 * spend/provider-health flags, and the normalized provider failure category
 * (Q2/Q29). Nothing here performs I/O or holds clocks of its own; the pure
 * engine reads it and produces a {@link RetryDecision} (Q14).</p>
 *
 * <p>{@code baselinesStillRequiringExecution} counts mandatory render steps
 * lacking their baseline execution AFTER the proposed execution; the caller
 * subtracts one when the proposed execution is itself such a baseline
 * (Q16).</p>
 */
public record RetryDecisionContext(
	boolean jobTerminal,
	boolean cancelled,
	boolean userDeleted,
	boolean relevantMediaDeleted,
	boolean inputSnapshotStale,
	boolean tenantMismatch,
	RetryPolicyResolution policyResolution,
	int totalSteps,
	int currentStep,
	int technicalRetriesUsed,
	int logicalAttemptsUsed,
	int paidAttemptsUsed,
	int qualityRetriesUsedOnStep,
	int evaluatorRetriesOnCandidate,
	int baselinesStillRequiringExecution,
	Duration maxAutonomousWait,
	Instant orchestrationDeadlineAt,
	boolean spendBlocked,
	boolean providerHealthy,
	boolean providerCapable,
	boolean technicalRetryRequested,
	boolean qualityRegenerationRequested,
	boolean evaluatorRetryRequested,
	boolean fallbackCapableProviderAvailable,
	Instant now,
	double jitterFraction,
	Instant persistedRetryNotBeforeAt,
	Duration retryAfterMinimum,
	String retryAfterResolvedFrom,
	ProviderFailureCategory providerFailureCategory,
	boolean provenNotSubmitted,
	boolean externalExecutionPersisted,
	boolean providerConfirmedTimeout,
	boolean submissionIndeterminate,
	boolean recoveryDeadlineExpired,
	boolean replaySafeEvidence,
	boolean remoteNotFoundLocalReconcileDone,
	boolean remoteProvenCannotExist) {

	/**
	 * Validates every field fail-closed.
	 *
	 * @throws IllegalArgumentException when any required value is missing or a
	 *         counter/step/fraction is outside its settled range (Q15)
	 */
	public RetryDecisionContext {
		if (policyResolution == null) {
			throw new IllegalArgumentException("policyResolution is required");
		}
		if (totalSteps < 1) {
			throw new IllegalArgumentException("totalSteps must be >= 1");
		}
		if (currentStep < 1 || currentStep > totalSteps) {
			throw new IllegalArgumentException("currentStep must be within the render plan");
		}
		if (technicalRetriesUsed < 0) {
			throw new IllegalArgumentException("technicalRetriesUsed must be >= 0");
		}
		if (logicalAttemptsUsed < 0) {
			throw new IllegalArgumentException("logicalAttemptsUsed must be >= 0");
		}
		if (paidAttemptsUsed < 0) {
			throw new IllegalArgumentException("paidAttemptsUsed must be >= 0");
		}
		if (qualityRetriesUsedOnStep < 0) {
			throw new IllegalArgumentException("qualityRetriesUsedOnStep must be >= 0");
		}
		if (evaluatorRetriesOnCandidate < 0) {
			throw new IllegalArgumentException("evaluatorRetriesOnCandidate must be >= 0");
		}
		if (baselinesStillRequiringExecution < 0) {
			throw new IllegalArgumentException("baselinesStillRequiringExecution must be >= 0");
		}
		if (maxAutonomousWait == null) {
			throw new IllegalArgumentException("maxAutonomousWait is required");
		}
		if (maxAutonomousWait.isNegative()) {
			throw new IllegalArgumentException("maxAutonomousWait must not be negative");
		}
		if (orchestrationDeadlineAt == null) {
			throw new IllegalArgumentException("orchestrationDeadlineAt is required");
		}
		if (now == null) {
			throw new IllegalArgumentException("now is required");
		}
		if (!Double.isFinite(jitterFraction) || jitterFraction < 0.0 || jitterFraction > 1.0) {
			throw new IllegalArgumentException("jitterFraction must be finite and between 0 and 1");
		}
		if (retryAfterMinimum != null && retryAfterMinimum.isNegative()) {
			throw new IllegalArgumentException("retryAfterMinimum must not be negative");
		}
	}

	/**
	 * Starts a builder with a settled, fully enabled-by-default v2 world:
	 * active single-step job, valid v2 policy, healthy and capable provider,
	 * no fences, no usage, fixed decision instant.
	 */
	public static Builder builder() {
		return new Builder();
	}

	/**
	 * Mutable convenience builder for the immutable record. Defaults match
	 * the settled V1/V2 commitments so tests state only what varies.
	 */
	public static final class Builder {

		private static final Instant DEFAULT_NOW = Instant.parse("2026-08-28T12:00:00Z");

		private boolean jobTerminal;
		private boolean cancelled;
		private boolean userDeleted;
		private boolean relevantMediaDeleted;
		private boolean inputSnapshotStale;
		private boolean tenantMismatch;
		private RetryPolicyResolution policyResolution = new RetryPolicyResolution(
			RetryPolicyResolution.State.VALID,
			RetryPolicySnapshot.forStepCountV2(1));
		private int totalSteps = 1;
		private int currentStep = 1;
		private int technicalRetriesUsed;
		private int logicalAttemptsUsed;
		private int paidAttemptsUsed;
		private int qualityRetriesUsedOnStep;
		private int evaluatorRetriesOnCandidate;
		private int baselinesStillRequiringExecution;
		private Duration maxAutonomousWait = RetryBackoff.MAX_AUTONOMOUS_WAIT;
		private Instant orchestrationDeadlineAt = DEFAULT_NOW.plus(Duration.ofMinutes(30));
		private boolean spendBlocked;
		private boolean providerHealthy = true;
		private boolean providerCapable = true;
		private boolean technicalRetryRequested;
		private boolean qualityRegenerationRequested;
		private boolean evaluatorRetryRequested;
		private boolean fallbackCapableProviderAvailable;
		private Instant now = DEFAULT_NOW;
		private double jitterFraction = 0.5;
		private Instant persistedRetryNotBeforeAt;
		private Duration retryAfterMinimum;
		private String retryAfterResolvedFrom;
		private ProviderFailureCategory providerFailureCategory;
		private boolean provenNotSubmitted;
		private boolean externalExecutionPersisted;
		private boolean providerConfirmedTimeout;
		private boolean submissionIndeterminate;
		private boolean recoveryDeadlineExpired;
		private boolean replaySafeEvidence;
		private boolean remoteNotFoundLocalReconcileDone;
		private boolean remoteProvenCannotExist;

		private Builder() {
		}

		public Builder jobTerminal(boolean value) {
			this.jobTerminal = value;
			return this;
		}

		public Builder cancelled(boolean value) {
			this.cancelled = value;
			return this;
		}

		public Builder userDeleted(boolean value) {
			this.userDeleted = value;
			return this;
		}

		public Builder relevantMediaDeleted(boolean value) {
			this.relevantMediaDeleted = value;
			return this;
		}

		public Builder inputSnapshotStale(boolean value) {
			this.inputSnapshotStale = value;
			return this;
		}

		public Builder tenantMismatch(boolean value) {
			this.tenantMismatch = value;
			return this;
		}

		public Builder policyResolution(RetryPolicyResolution value) {
			this.policyResolution = value;
			return this;
		}

		public Builder totalSteps(int value) {
			this.totalSteps = value;
			return this;
		}

		public Builder currentStep(int value) {
			this.currentStep = value;
			return this;
		}

		public Builder technicalRetriesUsed(int value) {
			this.technicalRetriesUsed = value;
			return this;
		}

		public Builder logicalAttemptsUsed(int value) {
			this.logicalAttemptsUsed = value;
			return this;
		}

		public Builder paidAttemptsUsed(int value) {
			this.paidAttemptsUsed = value;
			return this;
		}

		public Builder qualityRetriesUsedOnStep(int value) {
			this.qualityRetriesUsedOnStep = value;
			return this;
		}

		public Builder evaluatorRetriesOnCandidate(int value) {
			this.evaluatorRetriesOnCandidate = value;
			return this;
		}

		public Builder baselinesStillRequiringExecution(int value) {
			this.baselinesStillRequiringExecution = value;
			return this;
		}

		public Builder maxAutonomousWait(Duration value) {
			this.maxAutonomousWait = value;
			return this;
		}

		public Builder orchestrationDeadlineAt(Instant value) {
			this.orchestrationDeadlineAt = value;
			return this;
		}

		public Builder spendBlocked(boolean value) {
			this.spendBlocked = value;
			return this;
		}

		public Builder providerHealthy(boolean value) {
			this.providerHealthy = value;
			return this;
		}

		public Builder providerCapable(boolean value) {
			this.providerCapable = value;
			return this;
		}

		public Builder technicalRetryRequested(boolean value) {
			this.technicalRetryRequested = value;
			return this;
		}

		public Builder qualityRegenerationRequested(boolean value) {
			this.qualityRegenerationRequested = value;
			return this;
		}

		public Builder evaluatorRetryRequested(boolean value) {
			this.evaluatorRetryRequested = value;
			return this;
		}

		public Builder fallbackCapableProviderAvailable(boolean value) {
			this.fallbackCapableProviderAvailable = value;
			return this;
		}

		public Builder now(Instant value) {
			this.now = value;
			return this;
		}

		public Builder jitterFraction(double value) {
			this.jitterFraction = value;
			return this;
		}

		public Builder persistedRetryNotBeforeAt(Instant value) {
			this.persistedRetryNotBeforeAt = value;
			return this;
		}

		public Builder retryAfterMinimum(Duration value) {
			this.retryAfterMinimum = value;
			return this;
		}

		public Builder retryAfterResolvedFrom(String value) {
			this.retryAfterResolvedFrom = value;
			return this;
		}

		public Builder providerFailureCategory(ProviderFailureCategory value) {
			this.providerFailureCategory = value;
			return this;
		}

		public Builder provenNotSubmitted(boolean value) {
			this.provenNotSubmitted = value;
			return this;
		}

		public Builder externalExecutionPersisted(boolean value) {
			this.externalExecutionPersisted = value;
			return this;
		}

		public Builder providerConfirmedTimeout(boolean value) {
			this.providerConfirmedTimeout = value;
			return this;
		}

		public Builder submissionIndeterminate(boolean value) {
			this.submissionIndeterminate = value;
			return this;
		}

		public Builder recoveryDeadlineExpired(boolean value) {
			this.recoveryDeadlineExpired = value;
			return this;
		}

		public Builder replaySafeEvidence(boolean value) {
			this.replaySafeEvidence = value;
			return this;
		}

		public Builder remoteNotFoundLocalReconcileDone(boolean value) {
			this.remoteNotFoundLocalReconcileDone = value;
			return this;
		}

		public Builder remoteProvenCannotExist(boolean value) {
			this.remoteProvenCannotExist = value;
			return this;
		}

		public RetryDecisionContext build() {
			return new RetryDecisionContext(
				jobTerminal,
				cancelled,
				userDeleted,
				relevantMediaDeleted,
				inputSnapshotStale,
				tenantMismatch,
				policyResolution,
				totalSteps,
				currentStep,
				technicalRetriesUsed,
				logicalAttemptsUsed,
				paidAttemptsUsed,
				qualityRetriesUsedOnStep,
				evaluatorRetriesOnCandidate,
				baselinesStillRequiringExecution,
				maxAutonomousWait,
				orchestrationDeadlineAt,
				spendBlocked,
				providerHealthy,
				providerCapable,
				technicalRetryRequested,
				qualityRegenerationRequested,
				evaluatorRetryRequested,
				fallbackCapableProviderAvailable,
				now,
				jitterFraction,
				persistedRetryNotBeforeAt,
				retryAfterMinimum,
				retryAfterResolvedFrom,
				providerFailureCategory,
				provenNotSubmitted,
				externalExecutionPersisted,
				providerConfirmedTimeout,
				submissionIndeterminate,
				recoveryDeadlineExpired,
				replaySafeEvidence,
				remoteNotFoundLocalReconcileDone,
				remoteProvenCannotExist);
		}
	}
}
