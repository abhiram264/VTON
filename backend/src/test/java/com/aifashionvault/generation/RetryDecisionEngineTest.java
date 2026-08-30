package com.aifashionvault.generation;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.vton.ProviderFailureCategory;
import com.aifashionvault.shared.TerminalReason;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Exhaustive deterministic matrix for the retry decision engine: R4 gate
 * precedence, the R13 provider-failure matrix, budget ceilings, multi-step
 * reservation, backoff/Retry-After math, and fail-closed policy handling.
 * Fixed instants and fixed jitter fractions only; no sleep, no wall clock.
 */
class RetryDecisionEngineTest {

	private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");
	private static final RetryPolicyResolution MISSING_POLICY =
		new RetryPolicyResolution(RetryPolicyResolution.State.MISSING, null);
	private static final RetryPolicyResolution EMPTY_POLICY =
		new RetryPolicyResolution(RetryPolicyResolution.State.EMPTY, null);
	private static final RetryPolicyResolution INVALID_POLICY =
		new RetryPolicyResolution(RetryPolicyResolution.State.INVALID, null);
	private static final RetryPolicyResolution UNSUPPORTED_POLICY =
		new RetryPolicyResolution(RetryPolicyResolution.State.UNSUPPORTED_VERSION, null);

	private final RetryDecisionEngine engine = new RetryDecisionEngine();

	private RetryDecision decide(RetryDecisionContext.Builder builder) {
		return engine.decide(builder.build());
	}

	private static RetryDecisionContext.Builder technicalContext(ProviderFailureCategory category) {
		return RetryDecisionContext.builder()
			.providerFailureCategory(category)
			.technicalRetryRequested(true);
	}

	private static RetryPolicyResolution validV2Policy(boolean fallbackEnabled) {
		return new RetryPolicyResolution(
			RetryPolicyResolution.State.VALID,
			RetryPolicySnapshot.forStepCountV2(1, true, fallbackEnabled, true));
	}

	// ------------------------------------------------------------------
	// Terminality and cancellation/deletion/integrity fences (R4 gates 1-4)
	// ------------------------------------------------------------------

	@Test
	void terminalJobAlwaysDeniesWithoutOverwritingTheTerminalReason() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.jobTerminal(true));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.NO_RETRY);
		assertThat(decision.terminalReason()).isNull();
		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_TERMINALITY);
		assertThat(decision.retryNotBeforeAt()).isNull();
	}

	@Test
	void terminalityOutranksCancellation() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.jobTerminal(true)
			.cancelled(true));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_TERMINALITY);
		assertThat(decision.terminalReason()).isNull();
	}

	@Test
	void cancellationBeatsTechnicalEntitlement() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.cancelled(true));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.NO_RETRY);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.CANCELLED_BY_USER);
		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_CANCELLATION_OR_DELETION);
	}

	@Test
	void userDeletionBeatsQualityEntitlement() {
		RetryDecision decision = decide(RetryDecisionContext.builder()
			.qualityRegenerationRequested(true)
			.userDeleted(true));

		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.USER_DELETED);
		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_CANCELLATION_OR_DELETION);
	}

	@Test
	void mediaDeletionBeatsFallbackEntitlement() {
		RetryDecision decision = decide(RetryDecisionContext.builder()
			.policyResolution(validV2Policy(true))
			.providerFailureCategory(ProviderFailureCategory.QUOTA_BILLING)
			.fallbackCapableProviderAvailable(true)
			.relevantMediaDeleted(true));

		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.INPUT_MEDIA_DELETED);
		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_CANCELLATION_OR_DELETION);
	}

	@Test
	void cancellationOutranksTheOrchestrationDeadline() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.cancelled(true)
			.orchestrationDeadlineAt(NOW.minusSeconds(1)));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_CANCELLATION_OR_DELETION);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.CANCELLED_BY_USER);
	}

	@Test
	void tenantMismatchOutranksTheOrchestrationDeadline() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.tenantMismatch(true)
			.orchestrationDeadlineAt(NOW.minusSeconds(1)));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_TENANT_OR_INPUT_INTEGRITY);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.INTERNAL_INVARIANT_FAILURE);
	}

	@Test
	void staleInputSnapshotTerminatesBeforeAnyCounterGate() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.RATE_CAPACITY)
			.inputSnapshotStale(true));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_TENANT_OR_INPUT_INTEGRITY);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.INPUT_SNAPSHOT_STALE);
	}

	// ------------------------------------------------------------------
	// Policy fail-closed matrix (R9/Q5)
	// ------------------------------------------------------------------

	@Test
	void missingPolicyDeniesNewTechnicalWork() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.policyResolution(MISSING_POLICY));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_POLICY);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.RETRY_POLICY_MISSING);
	}

	@Test
	void emptyPolicyIsTreatedAsMissing() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.policyResolution(EMPTY_POLICY));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_POLICY);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.RETRY_POLICY_MISSING);
	}

	@Test
	void invalidPolicyDeniesNewTechnicalWork() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.policyResolution(INVALID_POLICY));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_POLICY);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.RETRY_POLICY_INVALID);
	}

	@Test
	void unsupportedPolicyVersionDeniesNewTechnicalWork() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.policyResolution(UNSUPPORTED_POLICY));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_POLICY);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.RETRY_POLICY_UNSUPPORTED_VERSION);
	}

	@Test
	void invalidPolicyDeniesQualityRegenerationAndFallback() {
		RetryDecision quality = decide(RetryDecisionContext.builder()
			.policyResolution(INVALID_POLICY)
			.qualityRegenerationRequested(true));
		RetryDecision fallback = decide(RetryDecisionContext.builder()
			.policyResolution(INVALID_POLICY)
			.providerFailureCategory(ProviderFailureCategory.QUOTA_BILLING)
			.fallbackCapableProviderAvailable(true));

		assertThat(quality.terminalReason()).isEqualTo(TerminalReason.RETRY_POLICY_INVALID);
		assertThat(quality.owningGate()).isEqualTo(RetryDecisionEngine.GATE_POLICY);
		assertThat(fallback.terminalReason()).isEqualTo(TerminalReason.RETRY_POLICY_INVALID);
		assertThat(fallback.owningGate()).isEqualTo(RetryDecisionEngine.GATE_POLICY);
	}

	@Test
	void everyNonValidPolicyStillGrantsReconciliationOfSubmittedWork() {
		for (RetryPolicyResolution resolution : new RetryPolicyResolution[] {
			MISSING_POLICY, EMPTY_POLICY, INVALID_POLICY, UNSUPPORTED_POLICY }) {
			RetryDecision decision = decide(RetryDecisionContext.builder()
				.policyResolution(resolution)
				.providerFailureCategory(ProviderFailureCategory.TIMEOUT_NETWORK)
				.submissionIndeterminate(true)
				.externalExecutionPersisted(true));

			assertThat(decision.disposition())
				.as("policy state %s", resolution.state())
				.isEqualTo(RetryDisposition.WAIT_EXISTING_ATTEMPT);
			assertThat(decision.owningGate()).isNull();
			assertThat(decision.terminalReason()).isNull();
		}
	}

	@Test
	void policyGateDeniesDisabledTechnicalAction() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.policyResolution(new RetryPolicyResolution(
				RetryPolicyResolution.State.VALID,
				RetryPolicySnapshot.forStepCountV2(1, false, false, true))));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_POLICY);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.RETRY_BUDGET_EXHAUSTED);
	}

	@Test
	void policyGateDeniesDisabledQualityAction() {
		RetryDecision decision = decide(RetryDecisionContext.builder()
			.policyResolution(new RetryPolicyResolution(
				RetryPolicyResolution.State.VALID,
				RetryPolicySnapshot.forStepCountV2(1, true, false, false)))
			.qualityRegenerationRequested(true));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_POLICY);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.RETRY_BUDGET_EXHAUSTED);
	}

	// ------------------------------------------------------------------
	// Evaluator retry (R14/Q11)
	// ------------------------------------------------------------------

	@Test
	void evaluatorInfrastructureFailureRetriesTheSameCandidateOnce() {
		RetryDecision decision = decide(RetryDecisionContext.builder()
			.evaluatorRetryRequested(true));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.EVALUATOR_RETRY);
		assertThat(decision.retryNotBeforeAt()).isNull();
		assertThat(decision.owningGate()).isNull();
	}

	@Test
	void evaluatorRetryIsExhaustedAfterOneAttempt() {
		RetryDecision decision = decide(RetryDecisionContext.builder()
			.evaluatorRetryRequested(true)
			.evaluatorRetriesOnCandidate(1));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.NO_RETRY);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.QUALITY_EVALUATOR_UNAVAILABLE);
		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_ACTION_CEILING);
	}

	@Test
	void evaluatorFailureNeverBecomesQualityRegeneration() {
		RetryDecision granted = decide(RetryDecisionContext.builder()
			.evaluatorRetryRequested(true));
		RetryDecision exhausted = decide(RetryDecisionContext.builder()
			.evaluatorRetryRequested(true)
			.evaluatorRetriesOnCandidate(1));

		assertThat(granted.disposition()).isNotEqualTo(RetryDisposition.QUALITY_REGENERATE);
		assertThat(exhausted.disposition()).isNotEqualTo(RetryDisposition.QUALITY_REGENERATE);
	}

	@Test
	void evaluatorRetryRespectsTheOrchestrationDeadline() {
		RetryDecision decision = decide(RetryDecisionContext.builder()
			.evaluatorRetryRequested(true)
			.orchestrationDeadlineAt(NOW));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_ORCHESTRATION_DEADLINE);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.ORCHESTRATION_DEADLINE_EXCEEDED);
	}

	@Test
	void cancellationOutranksEvaluatorEntitlement() {
		RetryDecision decision = decide(RetryDecisionContext.builder()
			.evaluatorRetryRequested(true)
			.cancelled(true));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_CANCELLATION_OR_DELETION);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.CANCELLED_BY_USER);
	}

	@Test
	void evaluatorRetrySurvivesANonValidPolicyWithoutNewProviderWork() {
		RetryDecision decision = decide(RetryDecisionContext.builder()
			.policyResolution(INVALID_POLICY)
			.evaluatorRetryRequested(true));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.EVALUATOR_RETRY);
	}

	@Test
	void v1PolicyNeverGainsEvaluatorRetryRetroactively() {
		RetryDecision decision = decide(RetryDecisionContext.builder()
			.policyResolution(new RetryPolicyResolution(
				RetryPolicyResolution.State.VALID,
				RetryPolicySnapshot.forStepCount(1)))
			.evaluatorRetryRequested(true));

		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.QUALITY_EVALUATOR_UNAVAILABLE);
		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_ACTION_CEILING);
	}

	// ------------------------------------------------------------------
	// Provider failure category matrix (R13)
	// ------------------------------------------------------------------

	@Test
	void invalidRequestNeverRetries() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.INVALID_REQUEST));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.NO_RETRY);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.INPUT_INVALID);
		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_TENANT_OR_INPUT_INTEGRITY);
	}

	@Test
	void unsupportedCapabilityWithoutApprovedFallbackTerminates() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.UNSUPPORTED_CAPABILITY));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.NO_RETRY);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.UNSUPPORTED_ITEM);
		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_PROVIDER_ELIGIBILITY);
	}

	@Test
	void unsupportedCapabilityWithApprovedFallbackCreatesFallbackAttempt() {
		RetryDecision decision = decide(RetryDecisionContext.builder()
			.policyResolution(validV2Policy(true))
			.providerFailureCategory(ProviderFailureCategory.UNSUPPORTED_CAPABILITY)
			.fallbackCapableProviderAvailable(true));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.FALLBACK_NEW_ATTEMPT);
		assertThat(decision.owningGate()).isNull();
	}

	@Test
	void contentPolicyRejectionTerminatesWithoutProviderShopping() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.CONTENT_POLICY_REJECTION));

		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.CONTENT_POLICY_REJECTED);
		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_PROVIDER_ELIGIBILITY);
	}

	@Test
	void authConfigurationTerminatesWithoutAutomaticRetry() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.AUTH_CONFIGURATION));

		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.PROVIDER_AUTH_CONFIGURATION);
		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_PROVIDER_ELIGIBILITY);
	}

	@Test
	void quotaBillingWithoutApprovedFallbackTerminates() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.QUOTA_BILLING));

		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.PROVIDER_QUOTA_EXHAUSTED);
		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_PROVIDER_ELIGIBILITY);
	}

	@Test
	void quotaBillingWithApprovedFallbackCreatesFallbackAttempt() {
		RetryDecision decision = decide(RetryDecisionContext.builder()
			.policyResolution(validV2Policy(true))
			.providerFailureCategory(ProviderFailureCategory.QUOTA_BILLING)
			.fallbackCapableProviderAvailable(true));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.FALLBACK_NEW_ATTEMPT);
	}

	@Test
	void rateCapacitySchedulesADelayedTechnicalAttemptHonoringRetryAfter() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.RATE_CAPACITY)
			.retryAfterMinimum(Duration.ofSeconds(120))
			.retryAfterResolvedFrom("delay-seconds")
			.jitterFraction(0.5));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.NEW_TECHNICAL_ATTEMPT);
		assertThat(decision.retryNotBeforeAt()).isEqualTo(NOW.plusSeconds(120));
		assertThat(decision.owningGate()).isNull();
	}

	@Test
	void temporaryUnavailableGrantsAJitteredTechnicalAttempt() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.jitterFraction(0.5));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.NEW_TECHNICAL_ATTEMPT);
		assertThat(decision.retryNotBeforeAt()).isEqualTo(NOW.plusMillis(500));
	}

	@Test
	void timeoutProvenNotSubmittedReplaysTheSameAttemptImmediately() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TIMEOUT_NETWORK)
			.provenNotSubmitted(true)
			.replaySafeEvidence(true));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.REPLAY_SAME_ATTEMPT);
		assertThat(decision.retryNotBeforeAt()).isNull();
	}

	@Test
	void timeoutWithPersistedExternalExecutionReconcilesInsteadOfResubmitting() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TIMEOUT_NETWORK)
			.externalExecutionPersisted(true));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.WAIT_EXISTING_ATTEMPT);
		assertThat(decision.retryNotBeforeAt()).isNull();
	}

	@Test
	void timeoutConfirmedByProviderGrantsNewTechnicalAttempt() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TIMEOUT_NETWORK)
			.providerConfirmedTimeout(true)
			.jitterFraction(0.5));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.NEW_TECHNICAL_ATTEMPT);
		assertThat(decision.retryNotBeforeAt()).isEqualTo(NOW.plusMillis(500));
	}

	@Test
	void timeoutWithAmbiguousSubmissionWaitsWithoutNewExecution() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TIMEOUT_NETWORK)
			.submissionIndeterminate(true));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.WAIT_EXISTING_ATTEMPT);
		assertThat(decision.owningGate()).isNull();
	}

	@Test
	void timeoutAmbiguityPastTheRecoveryDeadlineIsSubmissionIndeterminate() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TIMEOUT_NETWORK)
			.submissionIndeterminate(true)
			.recoveryDeadlineExpired(true));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.NO_RETRY);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.SUBMISSION_OUTCOME_INDETERMINATE);
		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_ORCHESTRATION_DEADLINE);
	}

	@Test
	void remoteNotFoundReconcilesLocallyBeforeAnyNewExecution() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.REMOTE_EXECUTION_NOT_FOUND));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.WAIT_EXISTING_ATTEMPT);
	}

	@Test
	void remoteNotFoundProvenImpossibleGrantsNewTechnicalAttempt() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.REMOTE_EXECUTION_NOT_FOUND)
			.remoteNotFoundLocalReconcileDone(true)
			.remoteProvenCannotExist(true));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.NEW_TECHNICAL_ATTEMPT);
	}

	@Test
	void remoteNotFoundExhaustedWithoutProofTerminatesAtTheActionCeiling() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.REMOTE_EXECUTION_NOT_FOUND)
			.remoteNotFoundLocalReconcileDone(true));

		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.REMOTE_EXECUTION_NOT_FOUND);
		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_ACTION_CEILING);
	}

	@Test
	void protocolViolationTerminatesAndRaisesProviderHealthConcern() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.PROTOCOL_VIOLATION));

		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.PROVIDER_PROTOCOL_ERROR);
		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_PROVIDER_ELIGIBILITY);
	}

	@Test
	void unknownProviderFailureFailsClosed() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE));

		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.PROVIDER_UNKNOWN_FAILURE);
		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_PROVIDER_ELIGIBILITY);
	}

	@Test
	void everyProviderFailureCategoryYieldsItsSettledDeterministicDecision() {
		for (ProviderFailureCategory category : ProviderFailureCategory.values()) {
			RetryDecision decision = decide(technicalContext(category));

			switch (category) {
				case INVALID_REQUEST:
					assertThat(decision.disposition()).isEqualTo(RetryDisposition.NO_RETRY);
					assertThat(decision.terminalReason()).isEqualTo(TerminalReason.INPUT_INVALID);
					break;
				case UNSUPPORTED_CAPABILITY:
					assertThat(decision.disposition()).isEqualTo(RetryDisposition.NO_RETRY);
					assertThat(decision.terminalReason()).isEqualTo(TerminalReason.UNSUPPORTED_ITEM);
					break;
				case CONTENT_POLICY_REJECTION:
					assertThat(decision.disposition()).isEqualTo(RetryDisposition.NO_RETRY);
					assertThat(decision.terminalReason()).isEqualTo(TerminalReason.CONTENT_POLICY_REJECTED);
					break;
				case AUTH_CONFIGURATION:
					assertThat(decision.disposition()).isEqualTo(RetryDisposition.NO_RETRY);
					assertThat(decision.terminalReason()).isEqualTo(TerminalReason.PROVIDER_AUTH_CONFIGURATION);
					break;
				case QUOTA_BILLING:
					assertThat(decision.disposition()).isEqualTo(RetryDisposition.NO_RETRY);
					assertThat(decision.terminalReason()).isEqualTo(TerminalReason.PROVIDER_QUOTA_EXHAUSTED);
					break;
				case RATE_CAPACITY:
					assertThat(decision.disposition()).isEqualTo(RetryDisposition.NEW_TECHNICAL_ATTEMPT);
					assertThat(decision.retryNotBeforeAt()).isEqualTo(NOW.plusMillis(500));
					break;
				case TEMPORARY_UNAVAILABLE:
					assertThat(decision.disposition()).isEqualTo(RetryDisposition.NEW_TECHNICAL_ATTEMPT);
					assertThat(decision.retryNotBeforeAt()).isEqualTo(NOW.plusMillis(500));
					break;
				case TIMEOUT_NETWORK:
					assertThat(decision.disposition()).isEqualTo(RetryDisposition.WAIT_EXISTING_ATTEMPT);
					assertThat(decision.retryNotBeforeAt()).isNull();
					break;
				case REMOTE_EXECUTION_NOT_FOUND:
					assertThat(decision.disposition()).isEqualTo(RetryDisposition.WAIT_EXISTING_ATTEMPT);
					assertThat(decision.retryNotBeforeAt()).isNull();
					break;
				case PROTOCOL_VIOLATION:
					assertThat(decision.disposition()).isEqualTo(RetryDisposition.NO_RETRY);
					assertThat(decision.terminalReason()).isEqualTo(TerminalReason.PROVIDER_PROTOCOL_ERROR);
					break;
				case UNKNOWN_PROVIDER_FAILURE:
					assertThat(decision.disposition()).isEqualTo(RetryDisposition.NO_RETRY);
					assertThat(decision.terminalReason()).isEqualTo(TerminalReason.PROVIDER_UNKNOWN_FAILURE);
					break;
			}
		}
	}

	// ------------------------------------------------------------------
	// Spend breaker (R4 gate 6/Q55)
	// ------------------------------------------------------------------

	@Test
	void spendBreakerBeatsTechnicalEntitlement() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.spendBlocked(true));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_SPEND_BREAKER);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.COST_BUDGET_BLOCKED);
	}

	@Test
	void spendBreakerBeatsQualityEntitlement() {
		RetryDecision decision = decide(RetryDecisionContext.builder()
			.qualityRegenerationRequested(true)
			.spendBlocked(true));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_SPEND_BREAKER);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.COST_BUDGET_BLOCKED);
	}

	@Test
	void spendBreakerBeatsFallbackEntitlementAndProviderHealth() {
		RetryDecision fallback = decide(RetryDecisionContext.builder()
			.policyResolution(validV2Policy(true))
			.providerFailureCategory(ProviderFailureCategory.QUOTA_BILLING)
			.fallbackCapableProviderAvailable(true)
			.spendBlocked(true));
		RetryDecision unhealthy = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.spendBlocked(true)
			.providerHealthy(false));

		assertThat(fallback.owningGate()).isEqualTo(RetryDecisionEngine.GATE_SPEND_BREAKER);
		assertThat(unhealthy.owningGate()).isEqualTo(RetryDecisionEngine.GATE_SPEND_BREAKER);
		assertThat(unhealthy.terminalReason()).isEqualTo(TerminalReason.COST_BUDGET_BLOCKED);
	}

	@Test
	void spendBreakerAllowsReconciliationOfSubmittedWork() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TIMEOUT_NETWORK)
			.externalExecutionPersisted(true)
			.spendBlocked(true));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.WAIT_EXISTING_ATTEMPT);
	}

	// ------------------------------------------------------------------
	// Provider capability/health (R4 gate 7/Q56)
	// ------------------------------------------------------------------

	@Test
	void providerHealthBlocksNewExecutionsButNotReconciliation() {
		RetryDecision denied = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.providerHealthy(false));
		RetryDecision reconciled = decide(technicalContext(ProviderFailureCategory.TIMEOUT_NETWORK)
			.externalExecutionPersisted(true)
			.providerHealthy(false));

		assertThat(denied.owningGate()).isEqualTo(RetryDecisionEngine.GATE_PROVIDER_ELIGIBILITY);
		assertThat(denied.terminalReason()).isEqualTo(TerminalReason.PROVIDER_UNAVAILABLE);
		assertThat(reconciled.disposition()).isEqualTo(RetryDisposition.WAIT_EXISTING_ATTEMPT);
	}

	@Test
	void providerCapabilityDeniesNewExecutionsWithNoEligibleProvider() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.providerCapable(false));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_PROVIDER_ELIGIBILITY);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.NO_ELIGIBLE_PROVIDER);
	}

	@Test
	void providerCapabilityDeniesUnsupportedItemForCapabilityFailures() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.UNSUPPORTED_CAPABILITY)
			.providerCapable(false));

		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.UNSUPPORTED_ITEM);
		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_PROVIDER_ELIGIBILITY);
	}

	// ------------------------------------------------------------------
	// Action ceilings (R4 gate 8/R5)
	// ------------------------------------------------------------------

	@Test
	void technicalCeilingDeniesTheThirdTechnicalRetry() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.technicalRetriesUsed(2));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_ACTION_CEILING);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.RETRY_BUDGET_EXHAUSTED);
	}

	@Test
	void technicalExhaustionEscapesToApprovedFallback() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.policyResolution(validV2Policy(true))
			.fallbackCapableProviderAvailable(true)
			.technicalRetriesUsed(2));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.FALLBACK_NEW_ATTEMPT);
		assertThat(decision.owningGate()).isNull();
	}

	@Test
	void qualityCeilingDeniesTheSecondRegenerationOnAStep() {
		RetryDecision decision = decide(RetryDecisionContext.builder()
			.qualityRegenerationRequested(true)
			.qualityRetriesUsedOnStep(1));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_ACTION_CEILING);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.QUALITY_RETRY_EXHAUSTED);
	}

	@Test
	void qualityRegenerationIgnoresTheTechnicalRetryCounter() {
		RetryDecision decision = decide(RetryDecisionContext.builder()
			.qualityRegenerationRequested(true)
			.technicalRetriesUsed(2));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.QUALITY_REGENERATE);
		assertThat(decision.retryNotBeforeAt()).isNull();
	}

	// ------------------------------------------------------------------
	// Budget ceilings and multi-step reservation (R4 gates 9-11/R6/Q16)
	// ------------------------------------------------------------------

	@Test
	void logicalCeilingDeniesAttemptsBeyondStepsPlusTwo() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.totalSteps(1)
			.logicalAttemptsUsed(3));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_LOGICAL_ATTEMPT_CEILING);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.RETRY_BUDGET_EXHAUSTED);
	}

	@Test
	void paidCeilingDeniesAttemptsBeyondThree() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.paidAttemptsUsed(3));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_PAID_ATTEMPT_CEILING);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.RETRY_BUDGET_EXHAUSTED);
	}

	@Test
	void logicalCeilingIsEvaluatedBeforeThePaidCeiling() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.totalSteps(1)
			.logicalAttemptsUsed(3)
			.paidAttemptsUsed(3));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_LOGICAL_ATTEMPT_CEILING);
	}

	@Test
	void paidCeilingIsEvaluatedBeforeStepReservation() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.paidAttemptsUsed(3)
			.baselinesStillRequiringExecution(0));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_PAID_ATTEMPT_CEILING);
	}

	@Test
	void reservationDeniesARetryThatStarvesARemainingBaseline() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.paidAttemptsUsed(2)
			.baselinesStillRequiringExecution(1));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_REMAINING_STEP_RESERVATION);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.RETRY_BUDGET_EXHAUSTED);
	}

	@Test
	void oneStepJobMaySpendTwoExtraPaidAttempts() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.totalSteps(1)
			.paidAttemptsUsed(2)
			.baselinesStillRequiringExecution(0));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.NEW_TECHNICAL_ATTEMPT);
	}

	@Test
	void twoStepJobAllowsAtMostOneExtraWhileTheSecondBaselineRemains() {
		RetryDecision granted = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.totalSteps(2)
			.paidAttemptsUsed(1)
			.baselinesStillRequiringExecution(1));
		RetryDecision denied = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.totalSteps(2)
			.paidAttemptsUsed(2)
			.baselinesStillRequiringExecution(1));

		assertThat(granted.disposition()).isEqualTo(RetryDisposition.NEW_TECHNICAL_ATTEMPT);
		assertThat(denied.owningGate()).isEqualTo(RetryDecisionEngine.GATE_REMAINING_STEP_RESERVATION);
	}

	@Test
	void threeStepJobHasNoSparePaidAttemptWhileAllBaselinesRemain() {
		RetryDecision denied = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.totalSteps(3)
			.paidAttemptsUsed(2)
			.baselinesStillRequiringExecution(1));
		RetryDecision granted = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.totalSteps(3)
			.paidAttemptsUsed(2)
			.baselinesStillRequiringExecution(0));

		assertThat(denied.owningGate()).isEqualTo(RetryDecisionEngine.GATE_REMAINING_STEP_RESERVATION);
		assertThat(granted.disposition()).isEqualTo(RetryDisposition.NEW_TECHNICAL_ATTEMPT);
	}

	@Test
	void reservationAlsoConstrainsFallback() {
		RetryDecision decision = decide(RetryDecisionContext.builder()
			.policyResolution(validV2Policy(true))
			.providerFailureCategory(ProviderFailureCategory.QUOTA_BILLING)
			.fallbackCapableProviderAvailable(true)
			.paidAttemptsUsed(2)
			.baselinesStillRequiringExecution(1));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_REMAINING_STEP_RESERVATION);
	}

	// ------------------------------------------------------------------
	// Backoff, Retry-After, and deadline fit (R4 gate 12/R7/R8/Q26-Q28)
	// ------------------------------------------------------------------

	@Test
	void persistedRetryNotBeforeIsNeverRerolled() {
		Instant persisted = NOW.plusSeconds(77);
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.RATE_CAPACITY)
			.retryAfterMinimum(Duration.ofSeconds(120))
			.jitterFraction(1.0)
			.persistedRetryNotBeforeAt(persisted));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.NEW_TECHNICAL_ATTEMPT);
		assertThat(decision.retryNotBeforeAt()).isEqualTo(persisted);
	}

	@Test
	void retryAfterLargerThanComputedDelayWins() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.RATE_CAPACITY)
			.retryAfterMinimum(Duration.ofSeconds(250))
			.retryAfterResolvedFrom("delay-seconds")
			.jitterFraction(0.5));

		assertThat(decision.retryNotBeforeAt()).isEqualTo(NOW.plusSeconds(250));
	}

	@Test
	void computedDelayLargerThanRetryAfterWins() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.RATE_CAPACITY)
			.retryAfterMinimum(Duration.ofMillis(100))
			.retryAfterResolvedFrom("http-date")
			.jitterFraction(1.0));

		assertThat(decision.retryNotBeforeAt()).isEqualTo(NOW.plusSeconds(1));
	}

	@Test
	void retryAfterIsClampedToTheFiveMinutePerWaitCeiling() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.RATE_CAPACITY)
			.retryAfterMinimum(Duration.ofMinutes(10))
			.retryAfterResolvedFrom("delay-seconds"));

		assertThat(decision.retryNotBeforeAt()).isEqualTo(NOW.plus(RetryBackoff.MAX_AUTONOMOUS_WAIT));
	}

	@Test
	void rateLimitWaitThatCannotFitBeforeTheDeadlineDeniesAtBackoffFit() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.RATE_CAPACITY)
			.retryAfterMinimum(Duration.ofSeconds(120))
			.retryAfterResolvedFrom("delay-seconds")
			.orchestrationDeadlineAt(NOW.plusSeconds(60)));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_BACKOFF_FIT);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.PROVIDER_RATE_LIMIT_EXHAUSTED);
	}

	@Test
	void unavailableWaitThatCannotFitBeforeTheDeadlineDeniesAtBackoffFit() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.technicalRetriesUsed(1)
			.jitterFraction(1.0)
			.orchestrationDeadlineAt(NOW.plusSeconds(1)));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_BACKOFF_FIT);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.PROVIDER_UNAVAILABLE);
	}

	@Test
	void timeoutWaitThatCannotFitBeforeTheDeadlineDeniesWithTimeoutExhausted() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TIMEOUT_NETWORK)
			.providerConfirmedTimeout(true)
			.technicalRetriesUsed(1)
			.jitterFraction(1.0)
			.orchestrationDeadlineAt(NOW.plusSeconds(1)));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_BACKOFF_FIT);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.PROVIDER_TIMEOUT_EXHAUSTED);
	}

	@Test
	void orchestrationDeadlineDeniesNewTechnicalWork() {
		RetryDecision atDeadline = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.orchestrationDeadlineAt(NOW));
		RetryDecision pastDeadline = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE)
			.orchestrationDeadlineAt(NOW.minusSeconds(1)));

		assertThat(atDeadline.owningGate()).isEqualTo(RetryDecisionEngine.GATE_ORCHESTRATION_DEADLINE);
		assertThat(atDeadline.terminalReason()).isEqualTo(TerminalReason.ORCHESTRATION_DEADLINE_EXCEEDED);
		assertThat(pastDeadline.owningGate()).isEqualTo(RetryDecisionEngine.GATE_ORCHESTRATION_DEADLINE);
	}

	@Test
	void orchestrationDeadlineOutranksTheQualityActionCeiling() {
		RetryDecision decision = decide(RetryDecisionContext.builder()
			.qualityRegenerationRequested(true)
			.qualityRetriesUsedOnStep(1)
			.orchestrationDeadlineAt(NOW.minusSeconds(1)));

		assertThat(decision.owningGate()).isEqualTo(RetryDecisionEngine.GATE_ORCHESTRATION_DEADLINE);
		assertThat(decision.terminalReason()).isEqualTo(TerminalReason.ORCHESTRATION_DEADLINE_EXCEEDED);
	}

	@Test
	void orchestrationDeadlineExemptsReconciliationOfSubmittedWork() {
		RetryDecision decision = decide(technicalContext(ProviderFailureCategory.TIMEOUT_NETWORK)
			.externalExecutionPersisted(true)
			.orchestrationDeadlineAt(NOW.minusSeconds(1)));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.WAIT_EXISTING_ATTEMPT);
	}

	@Test
	void qualityRegenerationIsImmediateEvenWhenRetryAfterIsPresent() {
		RetryDecision decision = decide(RetryDecisionContext.builder()
			.qualityRegenerationRequested(true)
			.retryAfterMinimum(Duration.ofMinutes(5))
			.retryAfterResolvedFrom("delay-seconds"));

		assertThat(decision.disposition()).isEqualTo(RetryDisposition.QUALITY_REGENERATE);
		assertThat(decision.retryNotBeforeAt()).isNull();
		assertThat(decision.owningGate()).isNull();
	}

	@Test
	void grantedDecisionsCarryNoGateAndDenialsAlwaysCarryOne() {
		RetryDecision granted = decide(technicalContext(ProviderFailureCategory.TEMPORARY_UNAVAILABLE));
		RetryDecision denied = decide(technicalContext(ProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE));

		assertThat(granted.owningGate()).isNull();
		assertThat(granted.terminalReason()).isNull();
		assertThat(denied.owningGate()).isNotNull();
		assertThat(denied.terminalReason()).isNotNull();
	}

	// ------------------------------------------------------------------
	// Input validation
	// ------------------------------------------------------------------

	@Test
	void engineRejectsANullContext() {
		assertThatIllegalArgumentException().isThrownBy(() -> engine.decide(null));
	}

	@Test
	void contextRejectsIllegalCounterStepAndJitterValues() {
		assertThatIllegalArgumentException().isThrownBy(() -> RetryDecisionContext.builder()
			.paidAttemptsUsed(-1)
			.build());
		assertThatIllegalArgumentException().isThrownBy(() -> RetryDecisionContext.builder()
			.jitterFraction(1.5)
			.build());
		assertThatIllegalArgumentException().isThrownBy(() -> RetryDecisionContext.builder()
			.totalSteps(2)
			.currentStep(3)
			.build());
		assertThatIllegalArgumentException().isThrownBy(() -> RetryDecisionContext.builder()
			.policyResolution(null)
			.build());
		assertThatIllegalArgumentException().isThrownBy(() -> RetryDecisionContext.builder()
			.retryAfterMinimum(Duration.ofSeconds(-1))
			.build());
	}
}
