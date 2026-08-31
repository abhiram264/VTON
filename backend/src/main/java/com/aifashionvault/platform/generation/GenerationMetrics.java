package com.aifashionvault.platform.generation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import com.aifashionvault.generation.CostAggregate;
import com.aifashionvault.generation.CostQuery;
import com.aifashionvault.generation.GenerationCostFact;
import com.aifashionvault.generation.GenerationCostMetrics;
import com.aifashionvault.generation.GenerationJob;
import com.aifashionvault.generation.PricingSnapshot;
import com.aifashionvault.generation.RetryDisposition;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.TerminalReason;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public final class GenerationMetrics implements GenerationCostMetrics {

	public static final String TRANSITION = "generation.transition";
	public static final String DEADLINE_EXPIRED = "generation.deadline.expired";
	public static final String OUTBOX_LAG = "generation.outbox.lag";
	public static final String AMBIGUITY = "generation.submission.ambiguity";
	public static final String QUALITY = "generation.quality";
	public static final String QUALITY_EVALUATOR_UNAVAILABLE = "generation.quality.evaluator.unavailable";
	public static final String COST = "generation.cost";
	public static final String COST_ATTEMPT = "generation.cost.attempt";
	public static final String ORPHAN_CLEANUP = "generation.orphan.cleanup";
	public static final String LOGICAL_ATTEMPTS = "generation.retry.logical.attempts";
	public static final String ADDITIONAL_BUDGET = "generation.retry.additional.budget";
	public static final String QUALITY_RETRIES = "generation.retry.quality.retries";
	public static final String REPLAY_RETRANSMISSIONS = "generation.retry.replay.retransmissions";
	public static final String PROVIDER_OUTCOMES = "generation.retry.provider.outcomes";
	public static final String FALLBACK = "generation.retry.fallback";
	public static final String RETRY_DELAY = "generation.retry.delay";
	public static final String RETRY_AFTER_REJECTED = "generation.retry.after.rejected";
	public static final String RECOVERY = "generation.retry.recovery";
	public static final String SUPPRESSION = "generation.retry.suppression";
	public static final String ADMISSION_REJECTIONS = "generation.retry.admission.rejections";
	public static final String TERMINAL = "generation.terminal";
	public static final String DISPOSITIONS = "generation.retry.dispositions";
	public static final String EXHAUSTION = "generation.retry.exhaustion";
	public static final String CAS_CONFLICTS = "generation.retry.cas.conflicts";
	public static final String EVALUATOR_RETRIES = "generation.retry.evaluator.retries";
	public static final String STORAGE_UNAVAILABLE = "generation.retry.storage.unavailable";

	public static final String PROVIDER_TIMEOUT = "PROVIDER_TIMEOUT";
	public static final String PROVIDER_RATE_LIMIT = "PROVIDER_RATE_LIMIT";
	public static final String PROVIDER_INTERNAL = "PROVIDER_INTERNAL_ERROR";
	public static final String PROVIDER_NON_RETRYABLE = "PROVIDER_NON_RETRYABLE";
	public static final String PROVIDER_UNKNOWN = "UNKNOWN";

	public static final String RETRY_AFTER_CEILING = "CEILING";
	public static final String RETRY_AFTER_DEADLINE = "DEADLINE";

	public static final String RECOVERY_SUBMISSION = "SUBMISSION";
	public static final String RECOVERY_DEADLINE = "RECOVERY_DEADLINE";
	public static final String EXECUTION_DEADLINE = "EXECUTION_DEADLINE";
	public static final String ORCHESTRATION_DEADLINE = "ORCHESTRATION_DEADLINE";

	public static final String SUPPRESSION_STALE_WORKER = "STALE_WORKER";
	public static final String SUPPRESSION_DUPLICATE_CALLBACK_OR_POLL = "DUPLICATE_CALLBACK_OR_POLL";
	public static final String SUPPRESSION_CANCELLED_OR_DELETED = "CANCELLED_OR_DELETED";

	public static final String ADMISSION_QUOTA = "QUOTA";
	public static final String ADMISSION_CREDIT = "CREDIT";
	public static final String ADMISSION_COST = "COST";
	public static final String ADMISSION_CIRCUIT = "CIRCUIT";

	public static final String TERMINAL_COMPLETED = "COMPLETED";
	public static final String TERMINAL_FAILED = "FAILED";
	public static final String TERMINAL_CANCELLED = "CANCELLED";
	public static final String TERMINAL_SUBJECT_DELETED = "SUBJECT_DELETED";
	public static final String TERMINAL_BUDGET_EXHAUSTED = "BUDGET_EXHAUSTED";
	public static final String TERMINAL_DEADLINE_EXPIRED = "DEADLINE_EXPIRED";
	public static final String TERMINAL_UNKNOWN = "UNKNOWN";

	private final MeterRegistry registry;
	private final Clock clock;
	private final Map<GenerationAttemptId, RecordedCostFact> costFacts = new HashMap<>();
	private final AtomicLong outboxLagMillis = new AtomicLong();

	public GenerationMetrics(MeterRegistry registry) {
		this(registry, Clock.systemUTC());
	}

	public GenerationMetrics(MeterRegistry registry, Clock clock) {
		if (registry == null) {
			throw new IllegalArgumentException("registry is required");
		}
		if (clock == null) {
			throw new IllegalArgumentException("clock is required");
		}
		this.registry = registry;
		this.clock = clock;
		Gauge.builder(OUTBOX_LAG, outboxLagMillis, AtomicLong::doubleValue)
			.baseUnit("milliseconds")
			.register(registry);
	}

	public void recordTransition(String from, String to) {
		requireLifecycle(from, "from");
		requireLifecycle(to, "to");
		Counter.builder(TRANSITION)
			.tag("from", from)
			.tag("to", to)
			.register(registry)
			.increment();
	}

	public void recordDeadlineExpired() {
		Counter.builder(DEADLINE_EXPIRED).register(registry).increment();
	}

	public void recordOutboxLag(Duration lag) {
		if (lag == null || lag.isNegative()) {
			throw new IllegalArgumentException("lag must be non-negative");
		}
		outboxLagMillis.set(lag.toMillis());
	}

	public void recordAmbiguity() {
		Counter.builder(AMBIGUITY).register(registry).increment();
	}

	public void recordQuality(String policyVersion, QualityDecision decision) {
		recordQuality(policyVersion, decision, "none");
	}

	public void recordQuality(String policyVersion, QualityDecision decision, String reasonCode) {
		if (policyVersion == null || policyVersion.isBlank()) {
			throw new IllegalArgumentException("policyVersion is required");
		}
		if (decision == null) {
			throw new IllegalArgumentException("decision is required");
		}
		Counter.builder(QUALITY)
			.tag("policy_version", policyVersion)
			.tag("decision", decision.name())
			.tag("reason_code", requireQualityReasonCode(reasonCode))
			.register(registry)
			.increment();
	}

	public void recordEvaluatorUnavailable(String reasonCode) {
		Counter.builder(QUALITY_EVALUATOR_UNAVAILABLE)
			.tag("reason_code", requireEvaluatorUnavailableReason(reasonCode))
			.register(registry)
			.increment();
	}

	public void recordCostStatus(String costStatus) {
		if (!PricingSnapshot.COST_ESTIMATED.equals(costStatus)
			&& !PricingSnapshot.COST_FINAL.equals(costStatus)
			&& !PricingSnapshot.COST_UNKNOWN.equals(costStatus)) {
			throw new IllegalArgumentException("costStatus must be ESTIMATED, FINAL, or UNKNOWN");
		}
		Counter.builder(COST)
			.tag("status", costStatus)
			.register(registry)
			.increment();
	}

	@Override
	public synchronized void recordAttempt(GenerationCostFact fact) {
		if (fact == null) {
			throw new IllegalArgumentException("fact is required");
		}
		RecordedCostFact existing = costFacts.get(fact.attemptId());
		if (existing != null) {
			if (existing.fact().equals(fact)) {
				return;
			}
			if (!isFinalReconciliation(existing.fact(), fact)) {
				throw new IllegalStateException("conflicting cost fact for attempt " + fact.attemptId().value());
			}
		}
		costFacts.put(fact.attemptId(), new RecordedCostFact(fact, existing == null ? clock.instant() : existing.recordedAt()));
		recordCostFactMetric(fact);
	}

	@Override
	public synchronized CostAggregate aggregate(CostQuery query) {
		if (query == null) {
			throw new IllegalArgumentException("query is required");
		}
		long requests = 0;
		long successes = 0;
		long approvedLooks = 0;
		long activeUsers = 0;
		long retainedUsers = 0;
		long knownCosts = 0;
		boolean unknownCost = false;
		BigDecimal total = BigDecimal.ZERO;
		Set<String> currencies = new HashSet<>();
		for (RecordedCostFact recorded : costFacts.values()) {
			GenerationCostFact fact = recorded.fact();
			if (!matches(query, recorded)) {
				continue;
			}
			requests++;
			if ("SUCCESS".equals(fact.technicalOutcome())) {
				successes++;
			}
			if (fact.qualityApprovedLook()) {
				approvedLooks++;
			}
			if (fact.activeUser()) {
				activeUsers++;
			}
			if (fact.retainedUser()) {
				retainedUsers++;
			}
			if (PricingSnapshot.COST_UNKNOWN.equals(fact.costStatus())) {
				unknownCost = true;
			}
			else {
				knownCosts++;
				total = total.add(fact.cost());
				currencies.add(fact.currency());
			}
		}
		boolean combinable = !unknownCost && currencies.size() <= 1;
		BigDecimal totalCost = combinable ? total : null;
		String currency = combinable
			? query.currency() != null ? query.currency() : currencies.stream().findFirst().orElse(null)
			: null;
		return new CostAggregate(
			totalCost,
			ratio(totalCost, requests),
			ratio(totalCost, successes),
			ratio(totalCost, approvedLooks),
			ratio(totalCost, activeUsers),
			ratio(totalCost, retainedUsers),
			requests,
			successes,
			approvedLooks,
			activeUsers,
			retainedUsers,
			knownCosts,
			currency);
	}

	private static boolean matches(CostQuery query, RecordedCostFact recorded) {
		GenerationCostFact fact = recorded.fact();
		return !recorded.recordedAt().isBefore(query.windowStart())
			&& recorded.recordedAt().isBefore(query.windowEnd())
			&& (query.userId() == null || query.userId().equals(fact.userId()))
			&& (query.providerKey() == null || query.providerKey().equals(fact.providerKey()))
			&& (query.modelRouteAuditIdentity() == null
				|| query.modelRouteAuditIdentity().equals(fact.modelRouteAuditIdentity()))
			&& (query.currency() == null || query.currency().equals(fact.currency()));
	}

	private static BigDecimal ratio(BigDecimal total, long denominator) {
		return total == null || denominator == 0
			? null
			: total.divide(BigDecimal.valueOf(denominator), MathContext.DECIMAL128);
	}

	private static boolean isFinalReconciliation(GenerationCostFact previous, GenerationCostFact replacement) {
		return PricingSnapshot.COST_ESTIMATED.equals(previous.costStatus())
			&& PricingSnapshot.COST_FINAL.equals(replacement.costStatus())
			&& previous.generationId().equals(replacement.generationId())
			&& previous.attemptId().equals(replacement.attemptId())
			&& previous.userId().equals(replacement.userId())
			&& previous.providerKey().equals(replacement.providerKey())
			&& previous.modelRouteAuditIdentity().equals(replacement.modelRouteAuditIdentity())
			&& previous.technicalOutcome().equals(replacement.technicalOutcome())
			&& java.util.Objects.equals(previous.qualityOutcome(), replacement.qualityOutcome())
			&& previous.qualityApprovedLook() == replacement.qualityApprovedLook()
			&& previous.activeUser() == replacement.activeUser()
			&& previous.retainedUser() == replacement.retainedUser()
			&& previous.currency().equals(replacement.currency());
	}

	private void recordCostFactMetric(GenerationCostFact fact) {
		Counter.builder(COST_ATTEMPT)
			.tag("provider", fact.providerKey())
			.tag("model", fact.modelRouteAuditIdentity())
			.tag("technical_outcome", fact.technicalOutcome())
			.tag("quality_outcome", fact.qualityOutcome() == null ? "none" : fact.qualityOutcome())
			.tag("cost_status", fact.costStatus())
			.register(registry)
			.increment();
	}

	private record RecordedCostFact(GenerationCostFact fact, Instant recordedAt) {
	}

	public void recordOrphanCleanup(String kind, int count) {
		if (!"temp".equals(kind) && !"final".equals(kind)) {
			throw new IllegalArgumentException("kind must be temp or final");
		}
		if (count < 0) {
			throw new IllegalArgumentException("count must be >= 0");
		}
		Counter.builder(ORPHAN_CLEANUP)
			.tag("kind", kind)
			.register(registry)
			.increment(count);
	}

	private static void requireLifecycle(String status, String name) {
		if (status == null || !GenerationJob.LIFECYCLE_STATUSES.contains(status)) {
			throw new IllegalArgumentException(name + " must be a canonical lifecycle status");
		}
	}

	public void recordLogicalAttempt(boolean retry) {
		recordCounter(LOGICAL_ATTEMPTS, "retry", Boolean.toString(retry));
	}

	public void recordAdditionalBudget(boolean exhausted) {
		recordCounter(ADDITIONAL_BUDGET, "exhausted", Boolean.toString(exhausted));
	}

	public void recordQualityRetry(boolean exhausted) {
		recordCounter(QUALITY_RETRIES, "exhausted", Boolean.toString(exhausted));
	}

	public void recordReplaySafeRetransmission(boolean capExhausted) {
		recordCounter(REPLAY_RETRANSMISSIONS, "cap_exhausted", Boolean.toString(capExhausted));
	}

	public void recordProviderOutcome(String normalizedCategory) {
		recordCounter(PROVIDER_OUTCOMES, "category", requireProviderOutcome(normalizedCategory));
	}

	public void recordFallback(boolean success) {
		recordCounter(FALLBACK, "success", Boolean.toString(success));
	}

	public void recordRetryDelay(Duration delay) {
		requireNonNegative(delay, "delay");
		Timer.builder(RETRY_DELAY).register(registry).record(delay);
	}

	public void recordRetryAfterRejected(String reason) {
		recordCounter(RETRY_AFTER_REJECTED, "reason", requireRetryAfterReason(reason));
	}

	public void recordRecovery(String kind) {
		recordCounter(RECOVERY, "kind", requireRecoveryKind(kind));
	}

	public void recordSuppression(String kind) {
		recordCounter(SUPPRESSION, "kind", requireSuppressionKind(kind));
	}

	public void recordAdmissionRejected(String kind) {
		recordCounter(ADMISSION_REJECTIONS, "kind", requireAdmissionKind(kind));
	}

	public void recordTerminal(String category) {
		recordCounter(TERMINAL, "category", requireTerminalCategory(category));
	}

	/**
	 * Records one executed retry disposition (Q2/Q67). The label is the
	 * enum name of the disposition; no job, attempt, media, or URL data is
	 * ever carried (Q59).
	 */
	public void recordDisposition(RetryDisposition disposition) {
		if (disposition == null) {
			throw new IllegalArgumentException("disposition is required");
		}
		recordCounter(DISPOSITIONS, "disposition", disposition.name());
	}

	/**
	 * Records one retry exhaustion with its product-owned terminal reason
	 * (Q25/Q67). Only the enum name is emitted.
	 */
	public void recordExhaustionReason(TerminalReason reason) {
		if (reason == null) {
			throw new IllegalArgumentException("reason is required");
		}
		recordCounter(EXHAUSTION, "reason", reason.name());
	}

	/** Records one statusVersion optimistic-concurrency conflict (Q44/Q67). */
	public void recordCasConflict() {
		Counter.builder(CAS_CONFLICTS).register(registry).increment();
	}

	/** Records one evaluator-retry consumption of the same candidate (Q11/Q67). */
	public void recordEvaluatorRetry(boolean exhausted) {
		recordCounter(EVALUATOR_RETRIES, "exhausted", Boolean.toString(exhausted));
	}

	/** Records one terminal private-storage unavailability (Q33/T18/Q67). */
	public void recordStorageUnavailable() {
		Counter.builder(STORAGE_UNAVAILABLE).register(registry).increment();
	}

	private void recordCounter(String name, String tagKey, String tagValue) {
		Counter.builder(name)
			.tag(tagKey, tagValue)
			.register(registry)
			.increment();
	}

	private static String requireQualityReasonCode(String reasonCode) {
		if (reasonCode == null || reasonCode.isBlank()) {
			return "none";
		}
		String normalized = reasonCode.strip();
		if ("none".equalsIgnoreCase(normalized)) {
			return "none";
		}
		normalized = normalized.toUpperCase(Locale.ROOT);
		return switch (normalized) {
			case "IMAGE_INVALID",
				"IMAGE_TOO_SMALL",
				"PERSON_NOT_PRESERVED",
				"BODY_NOT_PRESERVED",
				"GARMENT_FIDELITY_LOW",
				"COLOR_FIDELITY_LOW",
				"ANATOMY_INVALID",
				"IDENTITY_NOT_PRESERVED",
				"PREVIOUS_ITEMS_NOT_PRESERVED",
				"ARTIFACT_DETECTED",
				"INPUT_MEDIA_MISSING" -> normalized;
			default -> throw new IllegalArgumentException("reasonCode must be a canonical quality reason");
		};
	}

	private static String requireEvaluatorUnavailableReason(String reasonCode) {
		if ("EVALUATOR_UNAVAILABLE".equals(reasonCode)
			|| "QUALITY_POLICY_UNSUPPORTED".equals(reasonCode)) {
			return reasonCode;
		}
		throw new IllegalArgumentException("reasonCode must be a canonical evaluator-unavailable reason");
	}

	private static String requireProviderOutcome(String category) {
		if (PROVIDER_TIMEOUT.equals(category)
			|| PROVIDER_RATE_LIMIT.equals(category)
			|| PROVIDER_INTERNAL.equals(category)
			|| PROVIDER_NON_RETRYABLE.equals(category)
			|| PROVIDER_UNKNOWN.equals(category)) {
			return category;
		}
		throw new IllegalArgumentException("normalizedCategory must be a canonical provider outcome");
	}

	private static String requireRetryAfterReason(String reason) {
		if (RETRY_AFTER_CEILING.equals(reason) || RETRY_AFTER_DEADLINE.equals(reason)) {
			return reason;
		}
		throw new IllegalArgumentException("reason must be CEILING or DEADLINE");
	}

	private static String requireRecoveryKind(String kind) {
		if (RECOVERY_SUBMISSION.equals(kind)
			|| RECOVERY_DEADLINE.equals(kind)
			|| EXECUTION_DEADLINE.equals(kind)
			|| ORCHESTRATION_DEADLINE.equals(kind)) {
			return kind;
		}
		throw new IllegalArgumentException(
			"kind must be SUBMISSION, RECOVERY_DEADLINE, EXECUTION_DEADLINE, or ORCHESTRATION_DEADLINE");
	}

	private static String requireSuppressionKind(String kind) {
		if (SUPPRESSION_STALE_WORKER.equals(kind)
			|| SUPPRESSION_DUPLICATE_CALLBACK_OR_POLL.equals(kind)
			|| SUPPRESSION_CANCELLED_OR_DELETED.equals(kind)) {
			return kind;
		}
		throw new IllegalArgumentException("kind must be a canonical suppression category");
	}

	private static String requireAdmissionKind(String kind) {
		if (ADMISSION_QUOTA.equals(kind)
			|| ADMISSION_CREDIT.equals(kind)
			|| ADMISSION_COST.equals(kind)
			|| ADMISSION_CIRCUIT.equals(kind)) {
			return kind;
		}
		throw new IllegalArgumentException("kind must be QUOTA, CREDIT, COST, or CIRCUIT");
	}

	private static String requireTerminalCategory(String category) {
		if (TERMINAL_COMPLETED.equals(category)
			|| TERMINAL_FAILED.equals(category)
			|| TERMINAL_CANCELLED.equals(category)
			|| TERMINAL_SUBJECT_DELETED.equals(category)
			|| TERMINAL_BUDGET_EXHAUSTED.equals(category)
			|| TERMINAL_DEADLINE_EXPIRED.equals(category)
			|| TERMINAL_UNKNOWN.equals(category)) {
			return category;
		}
		throw new IllegalArgumentException("category must be a canonical terminal result");
	}

	private static void requireNonNegative(Duration duration, String name) {
		if (duration == null || duration.isNegative()) {
			throw new IllegalArgumentException(name + " must be non-negative");
		}
	}

}
