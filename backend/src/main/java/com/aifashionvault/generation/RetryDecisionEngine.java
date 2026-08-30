package com.aifashionvault.generation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import com.aifashionvault.generation.vton.ProviderFailureCategory;
import com.aifashionvault.shared.TerminalReason;

/**
 * Pure, deterministic retry decision boundary over persisted canonical state
 * plus explicit environment facts (Q2/Q14).
 *
 * <p>The engine performs no I/O, keeps no mutable state, and never draws
 * randomness: the decision instant arrives in {@code context.now()} and the
 * jitter draw in {@code context.jitterFraction()} (Q29). Every decision walks
 * the R4 gate chain in the fixed precedence order; the first failed gate owns
 * the denial. Unconditional R13 terminal verdicts are attributed to the gate
 * whose semantics own the failure family and are evaluated at that gate's
 * position, so earlier gates still win. Reconciliation of already-submitted
 * work ({@link RetryDisposition#WAIT_EXISTING_ATTEMPT}) is exempt from the
 * deadline, spend, eligibility, budget, and backoff gates while still fenced
 * by terminality, cancellation/deletion, and policy.</p>
 */
public final class RetryDecisionEngine {

	public static final String GATE_TERMINALITY = RetryDecision.TERMINALITY_GATE;
	public static final String GATE_CANCELLATION_OR_DELETION = "CANCELLATION_OR_DELETION";
	public static final String GATE_POLICY = "POLICY";
	public static final String GATE_TENANT_OR_INPUT_INTEGRITY = "TENANT_OR_INPUT_INTEGRITY";
	public static final String GATE_ORCHESTRATION_DEADLINE = "ORCHESTRATION_DEADLINE";
	public static final String GATE_SPEND_BREAKER = "SPEND_BREAKER";
	public static final String GATE_PROVIDER_ELIGIBILITY = "PROVIDER_ELIGIBILITY";
	public static final String GATE_ACTION_CEILING = "ACTION_CEILING";
	public static final String GATE_LOGICAL_ATTEMPT_CEILING = "LOGICAL_ATTEMPT_CEILING";
	public static final String GATE_PAID_ATTEMPT_CEILING = "PAID_ATTEMPT_CEILING";
	public static final String GATE_REMAINING_STEP_RESERVATION = "REMAINING_STEP_RESERVATION";
	public static final String GATE_BACKOFF_FIT = "BACKOFF_FIT";

	private enum Route {
		WAIT_EXISTING,
		REPLAY,
		TECHNICAL,
		FALLBACK,
		QUALITY
	}

	/**
	 * Decides one retry outcome deterministically from the supplied context.
	 *
	 * <p>Gate precedence (R4/Q14): terminality; cancellation/user
	 * deletion/relevant media deletion; valid policy/action enabled;
	 * tenant/input/fingerprint integrity; orchestration deadline; hard spend
	 * breaker; provider capability/health; action-specific ceiling;
	 * logical-attempt ceiling; paid-capable-attempt ceiling; remaining-step
	 * reservation; backoff/Retry-After fit. The first failed gate owns the
	 * resulting disposition and reason.</p>
	 *
	 * @param context fully materialized decision input
	 * @return the decision; granted dispositions carry null terminal reason
	 *         and null owning gate (Q2/Q14)
	 */
	public RetryDecision decide(RetryDecisionContext context) {
		if (context == null) {
			throw new IllegalArgumentException("context is required");
		}
		if (context.jobTerminal()) {
			return RetryDecision.terminalityDenial();
		}
		if (context.cancelled()) {
			return RetryDecision.deny(GATE_CANCELLATION_OR_DELETION, TerminalReason.CANCELLED_BY_USER);
		}
		if (context.userDeleted()) {
			return RetryDecision.deny(GATE_CANCELLATION_OR_DELETION, TerminalReason.USER_DELETED);
		}
		if (context.relevantMediaDeleted()) {
			return RetryDecision.deny(GATE_CANCELLATION_OR_DELETION, TerminalReason.INPUT_MEDIA_DELETED);
		}
		if (context.evaluatorRetryRequested()) {
			return decideEvaluatorRetry(context);
		}
		return decideProviderFacing(context);
	}

	/**
	 * Evaluator infrastructure failures retry evaluation of the same
	 * candidate and never cause regeneration (Q11/R14). Evaluator retry is
	 * not provider work, so the policy, spend, and provider-eligibility
	 * gates do not apply, but the integrity and deadline fences still
	 * outrank it (Q28/Q30/Q31/Q32). Already-submitted work may still be
	 * quality-evaluated under a non-valid policy because no new provider
	 * execution is required (Q5).
	 */
	private RetryDecision decideEvaluatorRetry(RetryDecisionContext context) {
		RetryDecision integrity = gateIntegrity(context);
		if (integrity != null) {
			return integrity;
		}
		if (!context.orchestrationDeadlineAt().isAfter(context.now())) {
			return RetryDecision.deny(GATE_ORCHESTRATION_DEADLINE, TerminalReason.ORCHESTRATION_DEADLINE_EXCEEDED);
		}
		if (context.evaluatorRetriesOnCandidate() >= maxEvaluatorRetries(context.policyResolution())) {
			return RetryDecision.deny(GATE_ACTION_CEILING, TerminalReason.QUALITY_EVALUATOR_UNAVAILABLE);
		}
		return RetryDecision.grant(RetryDisposition.EVALUATOR_RETRY);
	}

	private RetryDecision decideProviderFacing(RetryDecisionContext context) {
		RetryDecision policy = gatePolicy(context);
		if (policy != null) {
			return policy;
		}
		RetryDecision integrity = gateIntegrity(context);
		if (integrity != null) {
			return integrity;
		}
		RetryPolicySnapshot snapshot = context.policyResolution().snapshot();
		if (context.providerFailureCategory() == ProviderFailureCategory.TIMEOUT_NETWORK
			&& context.submissionIndeterminate()
			&& !context.externalExecutionPersisted()
			&& context.recoveryDeadlineExpired()) {
			return RetryDecision.deny(GATE_ORCHESTRATION_DEADLINE, TerminalReason.SUBMISSION_OUTCOME_INDETERMINATE);
		}
		Route route = resolveRoute(context, snapshot);
		if (route == null) {
			return RetryDecision.deny(GATE_ACTION_CEILING, TerminalReason.INTERNAL_INVARIANT_FAILURE);
		}
		if (route == Route.WAIT_EXISTING) {
			return RetryDecision.grant(RetryDisposition.WAIT_EXISTING_ATTEMPT);
		}
		RetryDecision deadline = gateOrchestrationDeadline(context);
		if (deadline != null) {
			return deadline;
		}
		RetryDecision spend = gateSpendBreaker(context);
		if (spend != null) {
			return spend;
		}
		RetryDecision eligibility = gateProviderEligibility(context, snapshot);
		if (eligibility != null) {
			return eligibility;
		}
		RetryDecision actionCeiling = gateActionCeiling(context, snapshot, route);
		if (actionCeiling != null) {
			if (route == Route.TECHNICAL && fallbackAllowed(context, snapshot)) {
				route = Route.FALLBACK;
			} else {
				return actionCeiling;
			}
		}
		RetryDecision budgets = gateBudgets(context, snapshot, route);
		if (budgets != null) {
			return budgets;
		}
		return schedule(context, route);
	}

	/**
	 * Gate 3: valid policy and action enabled (R9/Q5/Q63). Non-valid
	 * resolutions fail closed for every new execution and never import
	 * current defaults; EMPTY is treated as MISSING. Already-submitted work
	 * still reconciles through WAIT_EXISTING_ATTEMPT when no new execution
	 * is needed.
	 */
	private RetryDecision gatePolicy(RetryDecisionContext context) {
		RetryPolicyResolution resolution = context.policyResolution();
		if (resolution.state() == RetryPolicyResolution.State.VALID) {
			RetryPolicySnapshot snapshot = resolution.snapshot();
			if (context.technicalRetryRequested() && !snapshot.technicalRetryEnabled()) {
				return RetryDecision.deny(GATE_POLICY, TerminalReason.RETRY_BUDGET_EXHAUSTED);
			}
			if (context.qualityRegenerationRequested() && !snapshot.qualityRegenerationEnabled()) {
				return RetryDecision.deny(GATE_POLICY, TerminalReason.RETRY_BUDGET_EXHAUSTED);
			}
			return null;
		}
		if (context.externalExecutionPersisted() && !proposesNewExecution(context)) {
			return RetryDecision.grant(RetryDisposition.WAIT_EXISTING_ATTEMPT);
		}
		TerminalReason reason = switch (resolution.state()) {
			case MISSING, EMPTY -> TerminalReason.RETRY_POLICY_MISSING;
			case INVALID -> TerminalReason.RETRY_POLICY_INVALID;
			case UNSUPPORTED_VERSION -> TerminalReason.RETRY_POLICY_UNSUPPORTED_VERSION;
			case VALID -> throw new IllegalStateException("unreachable");
		};
		return RetryDecision.deny(GATE_POLICY, reason);
	}

	/**
	 * Gate 4: tenant/input/fingerprint integrity (Q32/Q57). Cross-tenant
	 * mismatch is a security/invariant terminal failure, a stale input
	 * snapshot can never be retried, and a provider-reported invalid request
	 * proves the input itself is terminal (Q17).
	 */
	private RetryDecision gateIntegrity(RetryDecisionContext context) {
		if (context.tenantMismatch()) {
			return RetryDecision.deny(GATE_TENANT_OR_INPUT_INTEGRITY, TerminalReason.INTERNAL_INVARIANT_FAILURE);
		}
		if (context.inputSnapshotStale()) {
			return RetryDecision.deny(GATE_TENANT_OR_INPUT_INTEGRITY, TerminalReason.INPUT_SNAPSHOT_STALE);
		}
		if (context.providerFailureCategory() == ProviderFailureCategory.INVALID_REQUEST) {
			return RetryDecision.deny(GATE_TENANT_OR_INPUT_INTEGRITY, TerminalReason.INPUT_INVALID);
		}
		return null;
	}

	/**
	 * Gate 5: orchestration deadline is the hard stop for new provider work
	 * and user-facing success (Q28). Reconciliation of already-submitted
	 * work is exempt and may continue for accounting and cleanup (Q47).
	 */
	private RetryDecision gateOrchestrationDeadline(RetryDecisionContext context) {
		if (!context.orchestrationDeadlineAt().isAfter(context.now())) {
			return RetryDecision.deny(GATE_ORCHESTRATION_DEADLINE, TerminalReason.ORCHESTRATION_DEADLINE_EXCEEDED);
		}
		return null;
	}

	/**
	 * Gate 6: global/per-user hard spend breakers outrank every retry
	 * entitlement, including quality regeneration and fallback; only
	 * no-spend reconciliation passes (Q55).
	 */
	private RetryDecision gateSpendBreaker(RetryDecisionContext context) {
		if (context.spendBlocked()) {
			return RetryDecision.deny(GATE_SPEND_BREAKER, TerminalReason.COST_BUDGET_BLOCKED);
		}
		return null;
	}

	/**
	 * Gate 7: provider capability/health (Q56) plus the R13 terminal
	 * verdicts whose semantics live at the provider boundary: content
	 * policy, provider auth, protocol violations, unknown failures, and
	 * quota/capability exhaustion without an approved fallback. An open
	 * health circuit or a missing capability blocks new executions but
	 * never reconciliation, and never consumes counters.
	 */
	private RetryDecision gateProviderEligibility(RetryDecisionContext context, RetryPolicySnapshot snapshot) {
		if (!context.providerHealthy()) {
			return RetryDecision.deny(GATE_PROVIDER_ELIGIBILITY, TerminalReason.PROVIDER_UNAVAILABLE);
		}
		if (!context.providerCapable()) {
			TerminalReason reason = context.providerFailureCategory() == ProviderFailureCategory.UNSUPPORTED_CAPABILITY
				? TerminalReason.UNSUPPORTED_ITEM
				: TerminalReason.NO_ELIGIBLE_PROVIDER;
			return RetryDecision.deny(GATE_PROVIDER_ELIGIBILITY, reason);
		}
		ProviderFailureCategory category = context.providerFailureCategory();
		if (category == null || context.qualityRegenerationRequested()) {
			return null;
		}
		switch (category) {
			case CONTENT_POLICY_REJECTION:
				return RetryDecision.deny(GATE_PROVIDER_ELIGIBILITY, TerminalReason.CONTENT_POLICY_REJECTED);
			case AUTH_CONFIGURATION:
				return RetryDecision.deny(GATE_PROVIDER_ELIGIBILITY, TerminalReason.PROVIDER_AUTH_CONFIGURATION);
			case PROTOCOL_VIOLATION:
				return RetryDecision.deny(GATE_PROVIDER_ELIGIBILITY, TerminalReason.PROVIDER_PROTOCOL_ERROR);
			case UNKNOWN_PROVIDER_FAILURE:
				return RetryDecision.deny(GATE_PROVIDER_ELIGIBILITY, TerminalReason.PROVIDER_UNKNOWN_FAILURE);
			case QUOTA_BILLING:
				if (!fallbackAllowed(context, snapshot)) {
					return RetryDecision.deny(GATE_PROVIDER_ELIGIBILITY, TerminalReason.PROVIDER_QUOTA_EXHAUSTED);
				}
				return null;
			case UNSUPPORTED_CAPABILITY:
				if (!fallbackAllowed(context, snapshot)) {
					return RetryDecision.deny(GATE_PROVIDER_ELIGIBILITY, TerminalReason.UNSUPPORTED_ITEM);
				}
				return null;
			default:
				return null;
		}
	}

	/**
	 * Gate 8: action-specific ceilings (Q9/Q10/Q15). A technically exhausted
	 * job may still fall back when the frozen routing context approves it
	 * (R13); quality regeneration is bounded per render step and never
	 * consumes the technical counter (Q10); bounded remote-not-found
	 * reconciliation terminates once exhausted without proof the original
	 * cannot exist (Q24).
	 */
	private RetryDecision gateActionCeiling(RetryDecisionContext context, RetryPolicySnapshot snapshot, Route route) {
		if (context.providerFailureCategory() == ProviderFailureCategory.REMOTE_EXECUTION_NOT_FOUND
			&& context.remoteNotFoundLocalReconcileDone()
			&& !context.remoteProvenCannotExist()) {
			return RetryDecision.deny(GATE_ACTION_CEILING, TerminalReason.REMOTE_EXECUTION_NOT_FOUND);
		}
		if (route == Route.QUALITY
			&& context.qualityRetriesUsedOnStep() >= snapshot.maxQualityRetriesPerStep()) {
			return RetryDecision.deny(GATE_ACTION_CEILING, TerminalReason.QUALITY_RETRY_EXHAUSTED);
		}
		if (route == Route.TECHNICAL
			&& context.technicalRetriesUsed() >= maxTechnicalRetries(snapshot)) {
			return RetryDecision.deny(GATE_ACTION_CEILING, TerminalReason.RETRY_BUDGET_EXHAUSTED);
		}
		return null;
	}

	/**
	 * Gates 9-11: logical-attempt ceiling, paid-capable-attempt ceiling, and
	 * the reservation of paid capacity for every mandatory render step still
	 * lacking its baseline execution (Q7/Q8/Q16). Replay-safe
	 * retransmissions consume none of these budgets (Q12).
	 */
	private RetryDecision gateBudgets(RetryDecisionContext context, RetryPolicySnapshot snapshot, Route route) {
		if (route == Route.REPLAY) {
			return null;
		}
		int maxLogicalAttempts = context.totalSteps() + snapshot.additionalLogicalAttempts();
		if (context.logicalAttemptsUsed() >= maxLogicalAttempts) {
			return RetryDecision.deny(GATE_LOGICAL_ATTEMPT_CEILING, TerminalReason.RETRY_BUDGET_EXHAUSTED);
		}
		if (context.paidAttemptsUsed() >= snapshot.maxPaidAttempts()) {
			return RetryDecision.deny(GATE_PAID_ATTEMPT_CEILING, TerminalReason.RETRY_BUDGET_EXHAUSTED);
		}
		if (!GenerationRetryBudget.remainingPaidSlotsCoverBaselines(
			snapshot.maxPaidAttempts(),
			context.paidAttemptsUsed(),
			context.baselinesStillRequiringExecution())) {
			return RetryDecision.deny(GATE_REMAINING_STEP_RESERVATION, TerminalReason.RETRY_BUDGET_EXHAUSTED);
		}
		return null;
	}

	/**
	 * Gate 12 and scheduling: full-jitter backoff with provider Retry-After
	 * respected as a minimum (Q26/Q27). Quality regeneration and replay are
	 * immediate; a persisted retryNotBeforeAt is returned verbatim and never
	 * rerolled (Q46).
	 */
	private RetryDecision schedule(RetryDecisionContext context, Route route) {
		RetryDisposition disposition = switch (route) {
			case WAIT_EXISTING -> RetryDisposition.WAIT_EXISTING_ATTEMPT;
			case REPLAY -> RetryDisposition.REPLAY_SAME_ATTEMPT;
			case TECHNICAL -> RetryDisposition.NEW_TECHNICAL_ATTEMPT;
			case FALLBACK -> RetryDisposition.FALLBACK_NEW_ATTEMPT;
			case QUALITY -> RetryDisposition.QUALITY_REGENERATE;
		};
		if (route == Route.QUALITY || route == Route.REPLAY) {
			return RetryDecision.grant(disposition);
		}
		if (context.persistedRetryNotBeforeAt() != null) {
			return RetryDecision.grantAt(disposition, context.persistedRetryNotBeforeAt());
		}
		int retryOrdinal = context.technicalRetriesUsed() + 1;
		Duration computed = RetryBackoff.computedJitterDelay(retryOrdinal, context.jitterFraction());
		Duration effective = RetryBackoff.effectiveDelay(computed, context.retryAfterMinimum());
		Clock clock = Clock.fixed(context.now(), ZoneOffset.UTC);
		Instant scheduled = RetryBackoff.scheduleAt(
			clock,
			effective,
			context.orchestrationDeadlineAt(),
			context.maxAutonomousWait());
		if (scheduled == null) {
			return RetryDecision.deny(GATE_BACKOFF_FIT, backoffFitReason(context));
		}
		if (scheduled.isAfter(context.now())) {
			return RetryDecision.grantAt(disposition, scheduled);
		}
		return RetryDecision.grant(disposition);
	}

	/**
	 * Resolves the repetition mechanism implied by the normalized failure
	 * category and the requested action (R13), or null when no action is
	 * authorized.
	 */
	private Route resolveRoute(RetryDecisionContext context, RetryPolicySnapshot snapshot) {
		if (context.qualityRegenerationRequested()) {
			return Route.QUALITY;
		}
		ProviderFailureCategory category = context.providerFailureCategory();
		if (category == null) {
			return null;
		}
		switch (category) {
			case RATE_CAPACITY:
			case TEMPORARY_UNAVAILABLE:
				return context.technicalRetryRequested() ? Route.TECHNICAL : null;
			case UNSUPPORTED_CAPABILITY:
			case QUOTA_BILLING:
				if (fallbackAllowed(context, snapshot)) {
					return Route.FALLBACK;
				}
				return context.technicalRetryRequested() ? Route.TECHNICAL : null;
			case TIMEOUT_NETWORK:
				return resolveTimeoutRoute(context);
			case REMOTE_EXECUTION_NOT_FOUND:
				return resolveRemoteNotFoundRoute(context);
			default:
				// Terminal categories are denied at their owning gates above;
				// the route only needs to keep flowing through the chain.
				return Route.TECHNICAL;
		}
	}

	private Route resolveTimeoutRoute(RetryDecisionContext context) {
		if (context.externalExecutionPersisted()) {
			return Route.WAIT_EXISTING;
		}
		if (context.provenNotSubmitted() && context.replaySafeEvidence()) {
			return Route.REPLAY;
		}
		if (context.providerConfirmedTimeout() && context.technicalRetryRequested()) {
			return Route.TECHNICAL;
		}
		return Route.WAIT_EXISTING;
	}

	private Route resolveRemoteNotFoundRoute(RetryDecisionContext context) {
		if (!context.remoteNotFoundLocalReconcileDone()) {
			return Route.WAIT_EXISTING;
		}
		if (context.remoteProvenCannotExist() && context.technicalRetryRequested()) {
			return Route.TECHNICAL;
		}
		// Reconciliation exhausted without proof the original cannot exist:
		// flow to the ACTION_CEILING denial in gateActionCeiling.
		return Route.TECHNICAL;
	}

	private boolean fallbackAllowed(RetryDecisionContext context, RetryPolicySnapshot snapshot) {
		return snapshot != null
			&& snapshot.fallbackEnabled()
			&& context.fallbackCapableProviderAvailable();
	}

	/**
	 * True when the requested action performs a distinct provider execution
	 * (Q2/Q7): an explicit technical or quality request, or a fallback route
	 * for the quota/capability categories when a capable provider is
	 * available in the frozen routing context.
	 */
	private boolean proposesNewExecution(RetryDecisionContext context) {
		ProviderFailureCategory category = context.providerFailureCategory();
		boolean fallbackIntent = (category == ProviderFailureCategory.UNSUPPORTED_CAPABILITY
			|| category == ProviderFailureCategory.QUOTA_BILLING)
			&& context.fallbackCapableProviderAvailable();
		return context.technicalRetryRequested()
			|| context.qualityRegenerationRequested()
			|| fallbackIntent;
	}

	private TerminalReason backoffFitReason(RetryDecisionContext context) {
		ProviderFailureCategory category = context.providerFailureCategory();
		if (category == ProviderFailureCategory.RATE_CAPACITY) {
			return TerminalReason.PROVIDER_RATE_LIMIT_EXHAUSTED;
		}
		if (category == ProviderFailureCategory.TIMEOUT_NETWORK) {
			return TerminalReason.PROVIDER_TIMEOUT_EXHAUSTED;
		}
		return TerminalReason.PROVIDER_UNAVAILABLE;
	}

	private int maxTechnicalRetries(RetryPolicySnapshot snapshot) {
		if (snapshot.maxTechnicalRetries() != null) {
			return snapshot.maxTechnicalRetries();
		}
		return GenerationRetryBudget.MAX_TECHNICAL_RETRIES_PER_JOB;
	}

	/**
	 * Version-faithful evaluator budget: v2 snapshots carry the settled
	 * limit; v1 snapshots never gained the evaluator-retry capability
	 * retroactively and therefore allow zero (Q63); a non-valid resolution
	 * falls back to the settled constant because already-submitted work may
	 * still be quality-evaluated without new provider executions (Q5).
	 */
	private int maxEvaluatorRetries(RetryPolicyResolution resolution) {
		if (resolution.state() != RetryPolicyResolution.State.VALID) {
			return GenerationRetryBudget.MAX_EVALUATOR_RETRIES_PER_CANDIDATE;
		}
		RetryPolicySnapshot snapshot = resolution.snapshot();
		if (snapshot.maxEvaluatorRetriesPerCandidate() != null) {
			return snapshot.maxEvaluatorRetriesPerCandidate();
		}
		return 0;
	}
}
