package com.aifashionvault.platform.generation;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.DoubleSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aifashionvault.admission.GlobalCostCircuitBreaker;
import com.aifashionvault.admission.ProviderExecutionBudgetRequest;
import com.aifashionvault.generation.CallbackInbox;
import com.aifashionvault.generation.CallbackInboxStore;
import com.aifashionvault.generation.GenerationAttempt;
import com.aifashionvault.generation.GenerationCostFact;
import com.aifashionvault.generation.GenerationJob;
import com.aifashionvault.generation.PricingSnapshot;
import com.aifashionvault.generation.GenerationRetryBudget;
import com.aifashionvault.generation.QualityEvaluationOutcome;
import com.aifashionvault.generation.QualityEvaluationRequest;
import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.generation.QualityGate;
import com.aifashionvault.generation.QualityResult;
import com.aifashionvault.generation.RenderPlan;
import com.aifashionvault.generation.RenderStep;
import com.aifashionvault.generation.RetryPolicySnapshot;
import com.aifashionvault.generation.ProviderHealthGate;
import com.aifashionvault.generation.RetryDecision;
import com.aifashionvault.generation.RetryDecisionContext;
import com.aifashionvault.generation.RetryDecisionEngine;
import com.aifashionvault.generation.RetryDisposition;
import com.aifashionvault.generation.RetryBackoff;
import com.aifashionvault.generation.RetryPolicyResolution;
import com.aifashionvault.generation.ProviderTurnOutcome;
import com.aifashionvault.generation.vton.GenerationOutputStore;
import com.aifashionvault.generation.vton.AttemptRedeliveryPolicy;
import com.aifashionvault.generation.vton.GenerationStatus;
import com.aifashionvault.generation.vton.PrivateMediaRef;
import com.aifashionvault.generation.vton.ProviderExecutionId;
import com.aifashionvault.generation.vton.ProviderFailure;
import com.aifashionvault.generation.vton.ProviderFailureCategory;
import com.aifashionvault.generation.vton.ProviderJobId;
import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.generation.vton.ProviderMediaSource;
import com.aifashionvault.generation.vton.ProviderOutputRef;
import com.aifashionvault.generation.vton.ProviderRegistry;
import com.aifashionvault.generation.vton.ProviderUsage;
import com.aifashionvault.generation.vton.StatusQueryResult;
import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.generation.vton.SubmissionReplaySafety;
import com.aifashionvault.generation.vton.VtonMode;
import com.aifashionvault.generation.vton.VtonProductAsset;
import com.aifashionvault.generation.vton.VtonProvider;
import com.aifashionvault.generation.vton.VtonRequest;
import com.aifashionvault.look.Look;
import com.aifashionvault.look.LookItem;
import com.aifashionvault.look.LookStore;
import com.aifashionvault.notification.Notification;
import com.aifashionvault.notification.NotificationStore;
import com.aifashionvault.notification.NotificationEvent;
import com.aifashionvault.notification.NotificationEventStore;
import com.aifashionvault.platform.generation.AuthorizedGenerationMediaResolver.GenerationInputMissingException;
import com.aifashionvault.platform.generation.AuthorizedGenerationMediaResolver.GenerationInputUnavailableException;
import com.aifashionvault.platform.generation.AuthorizedGenerationMediaResolver.ResolvedMedia;
import com.aifashionvault.platform.generation.AuthorizedGenerationMediaResolver.UnauthorizedGenerationMediaException;
import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.GenerationMediaStore;
import com.aifashionvault.platform.objectstore.ImageMediaPolicy;
import com.aifashionvault.platform.objectstore.ImageMediaType;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.ObjectStoreGenerationOutputStore;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.platform.objectstore.StorageKeys;
import com.aifashionvault.platform.queue.GenerationWorkLease;
import com.aifashionvault.platform.queue.GenerationWorkQueue;
import com.aifashionvault.platform.queue.SqsGenerationWorkQueue;
import com.aifashionvault.platform.queue.WorkOutboxStore;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.NotificationId;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.TerminalReason;
import com.aifashionvault.shared.VaultItemId;
import com.aifashionvault.shared.UserId;

public final class GenerationWorkProcessor {

	private static final Logger log = LoggerFactory.getLogger(GenerationWorkProcessor.class);
	private static final Duration VISIBILITY_EXTENSION = Duration.ofSeconds(90);
	private static final Duration HEARTBEAT_INTERVAL =
		Duration.ofSeconds(SqsGenerationWorkQueue.HEARTBEAT_INTERVAL_SECONDS);
	private static final Duration FIRST_POLL_DELAY = AttemptRedeliveryPolicy.FIRST_POLL_DELAY;
	private static final Duration STATUS_UNAVAILABLE_DELAY = Duration.ofSeconds(10);
	private static final Duration PROVIDER_SIGNED_READ_TTL = Duration.ofMinutes(5);
	private static final Duration SUBMISSION_RECOVERY_WINDOW = AttemptRedeliveryPolicy.UNSAFE_CALLBACK_RECOVERY_GRACE;
	private static final String CODE_UNSAFE_REPLAY = "UNSAFE_REPLAY_BLOCKED";
	private static final String CODE_INDETERMINATE = "PROVIDER_INDETERMINATE";
	private static final String CODE_QUALITY_REJECTED = "QUALITY_REJECTED";
	private static final String CODE_SUBJECT_DELETED = "SUBJECT_DELETED";
	private static final String CODE_CANCELLED_BY_USER = "CANCELLED_BY_USER";
	private static final String CODE_USER_DELETED = "USER_DELETED";
	private static final String CODE_SUBMISSION_UNKNOWN = "SUBMISSION_OUTCOME_UNKNOWN";
	private static final String CODE_RECOVERY_DEADLINE = "SUBMISSION_RECOVERY_DEADLINE";
	private static final String CODE_EXECUTION_DEADLINE = "PROVIDER_EXECUTION_DEADLINE";
private static final String CODE_ORCHESTRATION_DEADLINE = TerminalReason.ORCHESTRATION_DEADLINE_EXCEEDED.name();
	private static final String CODE_PROVIDER_OUTPUT_INVALID = "PROVIDER_OUTPUT_INVALID";
	private static final String CODE_PROVIDER_UNAVAILABLE = "PROVIDER_UNAVAILABLE";
	private static final Duration NOTIFICATION_TTL = Duration.ofHours(24);
	private static final int PROVIDER_CAPACITY_SLOTS = 8;
	private static final int MAX_LOGICAL_ATTEMPTS_PER_STEP = 3;


	private final GenerationStore generationStore;
	private final LookStore lookStore;
	private final NotificationStore notificationStore;
	private final GenerationWorkQueue queue;
	private final VtonProvider provider;
	private final ProviderRegistry providerRegistry;
	private final AuthorizedGenerationMediaResolver mediaResolver;
	private final PrivacyApprovedInputTransfer inputTransfer;
	private final QualityGate qualityGate;
	private final GenerationMediaStore mediaStore;
	private final ObjectStore objectStore;
	private final Clock clock;
	private final WorkOutboxStore workOutboxStore;
	private final CallbackInboxStore callbackInboxStore;
	private final GenerationMetrics metrics;
	private final GenerationCapacityLeaseStore capacityLeaseStore;
	private final NotificationEventStore notificationEventStore;
	private final DoubleSupplier randomFraction;
	private final GlobalCostCircuitBreaker costCircuitBreaker;
	private final ProviderHealthGate providerHealthGate;
	private final RetryDecisionEngine retryDecisionEngine = new RetryDecisionEngine();


 	public GenerationWorkProcessor(
 		GenerationStore generationStore,
 		LookStore lookStore,
 		NotificationStore notificationStore,
 		GenerationWorkQueue queue,
 		VtonProvider provider,
 		AuthorizedGenerationMediaResolver mediaResolver,
 		PrivacyApprovedInputTransfer inputTransfer,
 		QualityGate qualityGate,
 		GenerationMediaStore mediaStore,
 		ObjectStore objectStore,
 		Clock clock,
 		ProviderRegistry providerRegistry,
 		WorkOutboxStore workOutboxStore,
 		CallbackInboxStore callbackInboxStore,
 		GenerationMetrics metrics,
 		GenerationCapacityLeaseStore capacityLeaseStore,
 		NotificationEventStore notificationEventStore,
 		DoubleSupplier randomFraction,
 		GlobalCostCircuitBreaker costCircuitBreaker,
 		ProviderHealthGate providerHealthGate
 	) {
 		this.generationStore = require(generationStore, "generationStore");
 		this.lookStore = require(lookStore, "lookStore");
 		this.notificationStore = require(notificationStore, "notificationStore");
 		this.queue = require(queue, "queue");
 		this.provider = require(provider, "provider");
 		this.mediaResolver = require(mediaResolver, "mediaResolver");
 		this.inputTransfer = require(inputTransfer, "inputTransfer");
 		this.qualityGate = require(qualityGate, "qualityGate");
 		this.mediaStore = require(mediaStore, "mediaStore");
 		this.objectStore = require(objectStore, "objectStore");
 		this.clock = require(clock, "clock");
 		this.providerRegistry = providerRegistry;
 		this.workOutboxStore = workOutboxStore;
 		this.callbackInboxStore = callbackInboxStore;
 		this.metrics = metrics;
 		this.capacityLeaseStore = capacityLeaseStore;
 		this.notificationEventStore = notificationEventStore;
 		this.randomFraction = randomFraction == null
 			? () -> ThreadLocalRandom.current().nextDouble()
 			: randomFraction;
 		this.costCircuitBreaker = require(costCircuitBreaker, "costCircuitBreaker");
 		this.providerHealthGate = require(providerHealthGate, "providerHealthGate");
 	}

	public void process(GenerationWorkLease lease) {
		if (lease == null) {
			throw new IllegalArgumentException("lease is required");
		}
		ScheduledExecutorService heartbeat = Executors.newSingleThreadScheduledExecutor(task -> {
			Thread thread = new Thread(task, "generation-work-heartbeat");
			thread.setDaemon(true);
			return thread;
		});
		try {
			extendVisibility(lease);
			heartbeat.scheduleAtFixedRate(
				() -> extendVisibility(lease),
				HEARTBEAT_INTERVAL.toSeconds(),
				HEARTBEAT_INTERVAL.toSeconds(),
				TimeUnit.SECONDS);
			processTurn(lease);
		}
		finally {
			heartbeat.shutdownNow();
		}
	}

	private void processTurn(GenerationWorkLease lease) {
		GenerationJobId jobId = new GenerationJobId(lease.generationJobId());
		Optional<GenerationJob> found = generationStore.findJob(jobId);
		if (found.isEmpty()) {
			log.info("generation jobId={} code=MISSING_JOB", jobId.value());
			queue.delete(lease);
			return;
		}
		GenerationJob job = found.get();
		if (terminal(job)) {
			queue.delete(lease);
			return;
		}
		Instant now = clock.instant();
		if (!indeterminateRecoveryExpired(jobId, now)
			&& generationStore.failIfDeadlineExceeded(jobId, now)) {
			recordDeadlineExpired(job);
			notifyFailed(job);
			log.info("generation jobId={} code={}", jobId.value(), CODE_ORCHESTRATION_DEADLINE);
			queue.delete(lease);
			return;
		}
		job = generationStore.findJob(jobId).orElse(job);
		if (acknowledgeUnavailableOrTerminal(job, lease)) {
			return;
		}

		GenerationAttempt attempt;
		if (GenerationJob.RETRYING.equals(job.status()) && job.retryNotBeforeAt() != null) {
			if (now.isBefore(job.retryNotBeforeAt())) {
				queue.delete(lease);
				return;
			}
			List<GenerationAttempt> existing = generationStore.listAttempts(jobId);
			int nextNumber = nextAttemptNumber(existing);
			UUID fallbackStepId = existing.isEmpty() ? null : existing.getLast().renderStepId();
			GenerationAttempt next = newAttempt(job, nextNumber, fallbackStepId);
			boolean claimed;
			try {
				claimed = generationStore.claimDueRetry(jobId, job.statusVersion(), now, next);
			}
			catch (UnsupportedOperationException ex) {
				failTerminal(
					job,
					existing.isEmpty() ? next : existing.getLast(),
					lease,
					TerminalReason.INTERNAL_INVARIANT_FAILURE.name(),
					false);
				return;
			}
			if (!claimed) {
				GenerationJob latest = generationStore.findJob(jobId).orElse(job);
				List<GenerationAttempt> latestAttempts = generationStore.listAttempts(jobId);
				TerminalReason policyGateReason = retryPolicyGateReason(latest);
				if (policyGateReason != null) {
					GenerationAttempt refused = latestAttempts.isEmpty() ? next : latestAttempts.getLast();
					recordDisposition(RetryDisposition.NO_RETRY);
					recordExhaustion(policyGateReason);
					failTerminal(
						latest,
						refused,
						lease,
						policyGateReason.name(),
						false,
						GenerationMetrics.TERMINAL_BUDGET_EXHAUSTED);
					return;
				}
				if (retryDueAndExhausted(latest, now, latestAttempts, next.renderStepId())) {
					GenerationAttempt exhausted = latestAttempts.isEmpty() ? next : latestAttempts.getLast();
					recordDisposition(RetryDisposition.NO_RETRY);
					recordExhaustion(TerminalReason.RETRY_BUDGET_EXHAUSTED);
					failTerminal(
						latest,
						exhausted,
						lease,
						TerminalReason.RETRY_BUDGET_EXHAUSTED.name(),
						false,
						GenerationMetrics.TERMINAL_BUDGET_EXHAUSTED);
					return;
				}
				recordSuppression(GenerationMetrics.SUPPRESSION_STALE_WORKER);
				queue.delete(lease);
				return;
			}



			attempt = generationStore.findAttempt(next.id()).orElse(next);
			job = generationStore.findJob(jobId).orElse(job);
		}
		else {
			if (GenerationJob.QUEUED.equals(job.status()) && generationStore.markPreflight(jobId)) {
				recordTransition(job.status(), GenerationJob.PREFLIGHT);
				job = generationStore.findJob(jobId).orElse(job);
				if (acknowledgeUnavailableOrTerminal(job, lease)) {
					return;
				}
			}
			List<GenerationAttempt> attempts = generationStore.listAttempts(jobId);
			if (attempts.isEmpty()) {
				TerminalReason policyGateReason = retryPolicyGateReason(job);
				if (policyGateReason != null) {
					GenerationAttempt candidate = newAttempt(job, 1, null);
					recordDisposition(RetryDisposition.NO_RETRY);
					recordExhaustion(policyGateReason);
					failTerminal(
						job,
						candidate,
						lease,
						policyGateReason.name(),
						false,
						GenerationMetrics.TERMINAL_BUDGET_EXHAUSTED);
					return;
				}
				GenerationAttempt candidate = newAttempt(job, 1, null);
				if (!admitLogicalAttempt(job, attempts, candidate.renderStepId())) {
					failTerminal(
						job,
						candidate,
						lease,
						TerminalReason.RETRY_BUDGET_EXHAUSTED.name(),
						false,
						GenerationMetrics.TERMINAL_BUDGET_EXHAUSTED);
					return;
				}
				attempt = candidate;
				generationStore.insertAttempt(attempt);
				recordLogicalAttempt(false);
			}
			else {
				attempt = attempts.getLast();
				if (attempt.completedAt() != null) {
					if (job.completedAt() != null) {
						ensureNotification(job, attempt);
						queue.delete(lease);
						return;
					}
					if (job.currentStep() < job.totalSteps()) {
						if (completedCurrentStepSuccessfully(job, attempt)) {
							int completedStep = job.currentStep();
							boolean advanced;
							try {
								advanced = generationStore.advanceStepIfActive(
									job.id(),
									job.statusVersion(),
									completedStep);
							}
							catch (UnsupportedOperationException ex) {
								failTerminal(job, attempt, lease, CODE_ORCHESTRATION_DEADLINE, false);
								return;
							}
							GenerationJob progressed = generationStore.findJob(job.id()).orElse(job);
							if (acknowledgeUnavailableOrTerminal(progressed, lease)) {
								return;
							}
							if (!advanced && progressed.currentStep() == completedStep) {
								wakeImmediately(progressed, lease);
								return;
							}
							GenerationAttempt next = ensureNextBaseline(
								progressed,
								generationStore.listAttempts(progressed.id()));
							if (next != null) {
								wakeImmediately(progressed, lease);
								return;
							}
							queue.delete(lease);
							return;
						}
						else {
							GenerationAttempt next = ensureNextBaseline(job, attempts);
							if (next != null) {
								wakeImmediately(job, lease);
								return;
							}
						}
					}
					resumeIncompleteTerminal(job, attempt, lease);
					return;
				}
			}
		}

		if (attemptDeadlineExceeded(job, attempt, lease)) {
			return;
		}
		VtonProvider active = resolveProvider(attempt);
		attempt = confirmCallbackCandidate(job, attempt, active);
		if (attemptDeadlineExceeded(job, attempt, lease)) {
			return;
		}
		if (ownedWithoutExecution(attempt)
			&& active.capabilities().submissionReplaySafety() != SubmissionReplaySafety.SAME_KEY_SAFE) {
			if (callbackRecoveryPending(attempt)) {
				continueLater(job, lease, FIRST_POLL_DELAY);
				return;
			}
			generationStore.markSubmissionIndeterminate(attempt.id());
			continueLater(job, lease, FIRST_POLL_DELAY);
			return;
		}

		AttemptRedeliveryPolicy.Decision decision = AttemptRedeliveryPolicy.decide(
			terminal(job),
			persistedExecution(attempt),
			attempt.submissionIndeterminate(),
			active.capabilities().submissionReplaySafety());
		if (ownedWithoutExecution(attempt) && decision == AttemptRedeliveryPolicy.Decision.SUBMIT) {
			decision = AttemptRedeliveryPolicy.Decision.REPLAY_SAME_KEY;
		}
		log.info(
			"generation jobId={} attemptId={} provider={} decision={}",
			jobId.value(),
			attempt.id().value(),
			active.providerKey().value(),
			decision);

		switch (decision) {
			case STOP -> queue.delete(lease);
			case BLOCK_UNSAFE_REPLAY -> {
				generationStore.markSubmissionIndeterminate(attempt.id());
				continueLater(job, lease, FIRST_POLL_DELAY);
			}
			case POLL -> poll(job, attempt, lease, active);
			case SUBMIT, REPLAY_SAME_KEY -> submitOrReplay(job, attempt, lease, decision, active);
		}
	}

	private void submitOrReplay(
		GenerationJob job,
		GenerationAttempt attempt,
		GenerationWorkLease lease,
		AttemptRedeliveryPolicy.Decision decision,
		VtonProvider active
	) {
		boolean acquired = generationStore.tryAcquireSubmitOwnership(attempt.id(), clock.instant());
		if (!acquired) {
			GenerationJob latestJob = generationStore.findJob(job.id()).orElse(job);
			if (unavailable(latestJob)) {
				discardLeftoverTemp(latestJob);
				log.info("generation jobId={} code=SUBJECT_UNAVAILABLE", latestJob.id().value());
				queue.delete(lease);
				return;
			}
			GenerationAttempt latest = generationStore.findAttempt(attempt.id()).orElse(attempt);
			if (decision != AttemptRedeliveryPolicy.Decision.REPLAY_SAME_KEY || latest.submitOwnedAt() == null) {
				continueLater(latestJob, lease, FIRST_POLL_DELAY);
				return;
			}
			attempt = latest;
			job = latestJob;
		}
		else {
			attempt = generationStore.findAttempt(attempt.id()).orElse(attempt);
			GenerationJob latestJob = generationStore.findJob(job.id()).orElse(job);
			if (unavailable(latestJob)) {
				discardLeftoverTemp(latestJob);
				log.info("generation jobId={} code=SUBJECT_UNAVAILABLE", latestJob.id().value());
				queue.delete(lease);
				return;
			}
			job = latestJob;
		}
		if (attemptDeadlineExceeded(job, attempt, lease)) {
			return;
		}
		attempt = bindPricingIfNeeded(attempt, active);
		if (decision == AttemptRedeliveryPolicy.Decision.SUBMIT
			&& !newExecutionGatesAdmit(job, attempt, lease, active)) {
			return;
		}
		if (decision == AttemptRedeliveryPolicy.Decision.REPLAY_SAME_KEY
			&& !replayAdmission(job, attempt, active)) {
			failTerminal(job, attempt, lease, TerminalReason.SUBMISSION_OUTCOME_INDETERMINATE.name(), true);
			return;
		}
		if (!tryAcquireCapacity(attempt, job, active)) {
			continueLater(job, lease, FIRST_POLL_DELAY);
			return;
		}

		ResolvedMedia media;
		try {
			media = mediaResolver.resolve(job);
		}
		catch (GenerationInputMissingException ex) {
			failTerminal(job, attempt, lease, CODE_PROVIDER_OUTPUT_INVALID, false);
			return;
		}
		catch (GenerationInputUnavailableException ex) {
			failTerminal(job, attempt, lease, CODE_PROVIDER_UNAVAILABLE, false);
			return;
		}
		catch (UnauthorizedGenerationMediaException ex) {
			failTerminal(job, attempt, lease, TerminalReason.INTERNAL_INVARIANT_FAILURE.name(), false);
			return;
		}

		PrivacyApprovedInputTransfer.PreparedInput prepared;
		try {
			prepared = inputTransfer.prepare(
				media.avatar(),
				active.capabilities().inputTransfers(),
				PROVIDER_SIGNED_READ_TTL);
			for (VtonProductAsset product : media.products()) {
				inputTransfer.prepare(
					product.media(),
					active.capabilities().inputTransfers(),
					PROVIDER_SIGNED_READ_TTL);
			}
		}
		catch (RuntimeException ex) {
			failTerminal(job, attempt, lease, TerminalReason.INTERNAL_INVARIANT_FAILURE.name(), false);
			return;
		}
		log.info(
			"generation jobId={} attemptId={} provider={} transfer={}",
			job.id().value(),
			attempt.id().value(),
			active.providerKey().value(),
			prepared.method());

		VtonRequest request = new VtonRequest(
			media.avatar(),
			media.products(),
			Optional.empty(),
			mode(job));
		attempt = recordTransmissionOrFail(job, attempt, lease, decision);
		if (attempt == null) {
			return;
		}
		ProviderTurnOutcome submission;
		try {
			if (!stillActive(job)) {
				queue.delete(lease);
				return;
			}
			submission = active.submitTurn(request, attempt.submissionKey());
		}
		catch (RuntimeException ex) {
			handleIndeterminate(job, attempt, lease, active);
			return;
		}
		if (submission == null) {
			failTerminal(job, attempt, lease, TerminalReason.SUBMISSION_OUTCOME_INDETERMINATE.name(), true);
			return;
		}
		switch (submission) {
			case ProviderTurnOutcome.Accepted accepted -> {
				if (!generationStore.recordProviderExecutionIfActive(attempt.id(), accepted.providerJobId())) {
					continueLater(job, lease, FIRST_POLL_DELAY);
					return;
				}
				if (generationStore.markSubmitted(job.id())) {
					recordTransition(job.status(), GenerationJob.SUBMITTED);
				}
				attempt = withProviderJobId(attempt, accepted.providerJobId().value());
				GenerationAttempt persisted = generationStore.findAttempt(attempt.id()).orElse(attempt);
				attempt = mergePersistedTransmission(persisted, attempt);
				capture(job, attempt, lease, accepted.providerJobId(), active);
			}
			case ProviderTurnOutcome.Rejected rejected -> handleProviderFailure(job, attempt, lease, rejected.failure(), active);
			case ProviderTurnOutcome.Indeterminate ignored -> handleIndeterminate(job, attempt, lease, active);
		}
	}

	private void poll(GenerationJob job, GenerationAttempt attempt, GenerationWorkLease lease, VtonProvider active) {
		if (attemptDeadlineExceeded(job, attempt, lease)) {
			return;
		}
		// Reconciliation-only (Q47): a durable provider execution id proves the
		// submission, so a job redelivered before SUBMITTED is promoted exactly
		// like the accepted path before the quality fence can engage.
		if (generationStore.markSubmitted(job.id())) {
			recordTransition(job.status(), GenerationJob.SUBMITTED);
		}
		ProviderJobId providerJobId = new ProviderJobId(attempt.providerJobId());
		StatusQueryResult result = active.getStatus(providerJobId);
		if (result == null) {
			failTerminal(job, attempt, lease, TerminalReason.SUBMISSION_OUTCOME_INDETERMINATE.name(), true);
			return;
		}
		switch (result) {
			case StatusQueryResult.Observed observed -> {
				switch (observed.status()) {
					case GenerationStatus.Succeeded succeeded -> capture(
						job,
						withUsage(attempt, succeeded.usage()),
						lease,
						providerJobId,
						succeeded.outputs(),
						active);
					case GenerationStatus.Failed failed -> handleProviderFailure(
						job,
						withUsage(attempt, failed.usage()),
						lease,
						failed.failure(),
						active);
					case GenerationStatus.Queued ignored -> continueLater(job, lease, FIRST_POLL_DELAY);
					case GenerationStatus.Running ignored -> {
						if (generationStore.markGenerating(job.id())) {
							recordTransition(job.status(), GenerationJob.GENERATING);
						}
						continueLater(job, lease, FIRST_POLL_DELAY);
					}
				}
			}
			case StatusQueryResult.Unavailable unavailable -> {
				if (retryableFailure(unavailable.failure())) {
					continueLater(job, lease, STATUS_UNAVAILABLE_DELAY);
				}
				else {
					handleProviderFailure(job, attempt, lease, unavailable.failure(), active);
				}
			}
		}
	}

	private void capture(
		GenerationJob job,
		GenerationAttempt attempt,
		GenerationWorkLease lease,
		ProviderJobId providerJobId,
		VtonProvider active
	) {
		if (attemptDeadlineExceeded(job, attempt, lease)) {
			return;
		}
		StatusQueryResult result = active.getStatus(providerJobId);
		if (result == null) {
			failTerminal(job, attempt, lease, TerminalReason.SUBMISSION_OUTCOME_INDETERMINATE.name(), true);
			return;
		}
		if (result instanceof StatusQueryResult.Unavailable unavailable) {
			if (retryableFailure(unavailable.failure())) {
				continueLater(job, lease, STATUS_UNAVAILABLE_DELAY);
			}
			else {
				handleProviderFailure(job, attempt, lease, unavailable.failure(), active);
			}
			return;
		}
		if (result instanceof StatusQueryResult.Observed observed
			&& observed.status() instanceof GenerationStatus.Succeeded succeeded) {
			capture(job, withUsage(attempt, succeeded.usage()), lease, providerJobId, succeeded.outputs(), active);
			return;
		}
		if (result instanceof StatusQueryResult.Observed observed
			&& observed.status() instanceof GenerationStatus.Failed failed) {
			handleProviderFailure(job, withUsage(attempt, failed.usage()), lease, failed.failure(), active);
			return;
		}
		if (result instanceof StatusQueryResult.Observed observed
			&& observed.status() instanceof GenerationStatus.Running) {
			if (generationStore.markGenerating(job.id())) {
				recordTransition(job.status(), GenerationJob.GENERATING);
			}
		}
		continueLater(job, lease, FIRST_POLL_DELAY);
	}

	private void capture(
		GenerationJob job,
		GenerationAttempt attempt,
		GenerationWorkLease lease,
		ProviderJobId providerJobId,
		List<ProviderOutputRef> outputs,
		VtonProvider active
	) {
        if (!stillActive(job)) {
            GenerationJob latest = generationStore.findJob(job.id()).orElse(job);
            if (unavailable(latest)) {
                GenerationAttempt finished = finishedAttempt(attempt, "SUCCESS", TerminalReason.USER_DELETED.name(), attempt.tempStorageKey(), null);
                completeAttemptAfterSubjectDeletion(latest, finished);
                releaseCapacity(attempt);
            }
            queue.delete(lease);
            return;
        }
		if (outputs == null || outputs.isEmpty()) {
			failTerminal(job, attempt, lease, TerminalReason.PROVIDER_PROTOCOL_ERROR.name(), false);
			return;
		}
		ObjectStoreGenerationOutputStore outputStore = null;
		PrivateMediaRef temp;
		ObjectMetadata metadata;
		byte[] bytes;
		try (ProviderMediaSource source = active.openOutput(outputs.getFirst())) {
			ImageMediaType mediaType = mediaType(source.mediaType());
			outputStore = new ObjectStoreGenerationOutputStore(
				mediaStore,
				objectStore,
				job.userId(),
				job.id().value(),
				attempt.id().value(),
				mediaType);
			temp = outputStore.writePrivateTemp(source);
			ObjectRef tempRef = new ObjectRef(StorageDomain.TEMP, temp.storageKey().value());
			metadata = objectStore.head(tempRef);
			bytes = objectStore.readBounded(
				tempRef,
				metadata.version(),
				boundedReadCap());
		}
		catch (GenerationOutputStore.StorageUnavailableException ex) {
			if (outputStore != null) {
				outputStore.discard();
			}
			recordStorageUnavailable();
			failTerminal(
				job,
				attempt,
				lease,
				TerminalReason.STORAGE_UNAVAILABLE.name(),
				false,
				GenerationMetrics.TERMINAL_FAILED);
			return;
		}
		catch (RuntimeException | IOException ex) {
			if (outputStore != null) {
				outputStore.discard();
			}
			failTerminal(job, attempt, lease, TerminalReason.PROVIDER_PROTOCOL_ERROR.name(), false);
			return;
		}
		if (invalidOutput(bytes)) {
			outputStore.discard();
			failTerminal(job, attempt, lease, TerminalReason.PROVIDER_PROTOCOL_ERROR.name(), false);
			return;
		}
		GenerationJob latestForQuality = generationStore.findJob(job.id()).orElse(job);
		if (unavailable(latestForQuality) || latestForQuality.completedAt() != null) {
			outputStore.discard();
			completeDeleted(latestForQuality, attempt, lease, temp, outputs.getFirst());
			return;
		}
		Optional<QualityResult> stored = generationStore.findQualityResult(attempt.id());
		if (stored.isPresent()) {
			QualityResult replayed = stored.get();
			recordQuality(replayed);
			switch (replayed.decision()) {
				case PASS -> completePass(job, attempt, lease, outputStore, metadata, temp, outputs.getFirst(), active);
				case RETRY -> completeQualityRetry(job, attempt, lease, outputStore, temp, outputs.getFirst(), active);
				case FAIL -> completeQualityFail(job, attempt, lease, outputStore, temp, outputs.getFirst(), active);
			}
			return;
		}
		if (generationStore.markSubmitted(job.id())) {
			recordTransition(latestForQuality.status(), GenerationJob.SUBMITTED);
		}
		generationStore.markGenerating(job.id());
		GenerationJob latestJob = generationStore.findJob(job.id()).orElse(latestForQuality);
		boolean qualityCheck;
		try {
			qualityCheck = generationStore.markQualityCheckIfActive(job.id(), latestJob.statusVersion());
		}
		catch (UnsupportedOperationException ex) {
			outputStore.discard();
			failTerminal(job, attempt, lease, TerminalReason.INTERNAL_INVARIANT_FAILURE.name(), false);
			return;
		}
		if (!qualityCheck) {
			outputStore.discard();
			queue.delete(lease);
			return;
		}
		if (!GenerationJob.QUALITY_CHECK.equals(latestJob.status())) {
			recordTransition(latestJob.status(), GenerationJob.QUALITY_CHECK);
		}
        if (!stillActive(job)) {
            outputStore.discard();
            completeDeleted(generationStore.findJob(job.id()).orElse(latestJob), attempt, lease, temp, outputs.getFirst());
            return;
        }
		QualityEvaluationRequest request = qualityEvaluationRequest(job, attempt, bytes);
		QualityEvaluationOutcome outcome = qualityGate.evaluate(request);
		log.info(
			"generation jobId={} attemptId={} policy={} outcome={}",
			job.id().value(),
			attempt.id().value(),
			request.boundPolicyVersion(),
			outcome instanceof QualityEvaluationOutcome.Completed completed
				? completed.result().decision()
				: "UNAVAILABLE:"
					+ ((QualityEvaluationOutcome.Unavailable) outcome).reasonCode());
		switch (outcome) {
			case QualityEvaluationOutcome.Completed completed -> {
                if (!stillActive(job)) {
                    outputStore.discard();
                    String deletionReason = completed.result().decision() == QualityDecision.PASS
                        ? TerminalReason.USER_DELETED.name()
                        : CODE_QUALITY_REJECTED;
                    completeDeleted(
                        generationStore.findJob(job.id()).orElse(job),
                        attempt,
                        lease,
                        temp,
                        outputs.getFirst(),
                        deletionReason);
                    return;
                }
				QualityResult quality = completed.result();
				recordQuality(quality);
				generationStore.saveQualityResult(quality);
				switch (quality.decision()) {
					case PASS -> completePass(job, attempt, lease, outputStore, metadata, temp, outputs.getFirst(), active);
					case RETRY -> completeQualityRetry(job, attempt, lease, outputStore, temp, outputs.getFirst(), active);
					case FAIL -> completeQualityFail(job, attempt, lease, outputStore, temp, outputs.getFirst(), active);
				}
			}
			case QualityEvaluationOutcome.Unavailable unavailable -> {
				recordEvaluatorUnavailable(unavailable.reasonCode());
				evaluatorRetryOrTerminal(
					job,
					attempt,
					lease,
					outputStore,
					metadata,
					temp,
					request,
					outputs.getFirst(),
					active);
			}
		}
	}

	private void completePass(
		GenerationJob job,
		GenerationAttempt attempt,
		GenerationWorkLease lease,
		ObjectStoreGenerationOutputStore outputStore,
		ObjectMetadata metadata,
		PrivateMediaRef temp,
		ProviderOutputRef outputRef,
		VtonProvider active
	) {
		GenerationJob latest = generationStore.findJob(job.id()).orElse(job);
		if (unavailable(latest) || latest.completedAt() != null) {
			outputStore.discard();
			completeDeleted(latest, attempt, lease, temp, outputRef);
			return;
		}
		if (latest.currentStep() < latest.totalSteps()) {
			outputStore.discard();
			GenerationAttempt finished = finishedAttempt(
				attempt,
				"SUCCESS",
				null,
				temp.storageKey(),
				outputRef);
			generationStore.completeAttempt(finished);
			boolean advanced;
			try {
				advanced = generationStore.advanceStepIfActive(
					latest.id(),
					latest.statusVersion(),
					latest.currentStep());
			}
			catch (UnsupportedOperationException ex) {
				failTerminal(latest, finished, lease, CODE_ORCHESTRATION_DEADLINE, false);
				return;
			}
			recordCost(latest, finished);
			releaseCapacity(attempt);
			if (advanced) {
				GenerationJob progressed = generationStore.findJob(latest.id()).orElse(latest);
				ensureNextBaseline(progressed, generationStore.listAttempts(progressed.id()));
				wakeImmediately(progressed, lease);
			}
			else {
				GenerationJob current = generationStore.findJob(latest.id()).orElse(latest);
				if (unavailable(current)) {
					completeAttemptAfterSubjectDeletion(current, finished);
				}
				else {
					recordSuppression(GenerationMetrics.SUPPRESSION_STALE_WORKER);
				}
				queue.delete(lease);
			}
			return;
		}
		Optional<Look> existing = lookStore.findByGenerationJobId(job.id());
		if (existing.isPresent()) {
			outputStore.discard();
			GenerationAttempt finished = finishedAttempt(
				attempt,
				"SUCCESS",
				null,
				temp.storageKey(),
				outputRef);
			if (generationStore.completeTerminalIfActive(finished, clock.instant())) {
				recordTerminalCategory(GenerationMetrics.TERMINAL_COMPLETED);
				recordCost(job, finished);
				releaseCapacity(attempt);
				notifyReady(job, existing.get());
			}

			queue.delete(lease);
			return;
		}
        if (!stillActive(job)) {
            outputStore.discard();
            GenerationJob current = generationStore.findJob(job.id()).orElse(job);
            if (unavailable(current)) {
                completeDeleted(current, attempt, lease, temp, outputRef);
                return;
            }
            queue.delete(lease);
            return;
        }
		ClaimResult claimed = outputStore.promote(metadata.version());
		if (claimed instanceof ClaimResult.Conflict) {
			outputStore.discard();
			failTerminal(job, attempt, lease, TerminalReason.PROVIDER_PROTOCOL_ERROR.name(), false);
			return;
		}
		latest = generationStore.findJob(job.id()).orElse(job);
		StorageKey lookKey = StorageKeys.persistentKey(
			job.userId(),
			StorageDomain.LOOK,
			attempt.id().value(),
			mediaTypeFromTemp(temp.storageKey()));
		if (unavailable(latest) || latest.completedAt() != null) {
			objectStore.delete(new ObjectRef(StorageDomain.LOOK, lookKey.value()));
			outputStore.discard();
			completeDeleted(latest, attempt, lease, temp, outputRef);
			return;
		}
		LookId lookId = new LookId(UUID.randomUUID());
		Look look = new Look(
			lookId,
			job.userId(),
			null,
			job.id(),
			job.studioSessionId(),
			lookKey,
			job.requestedMode(),
			attempt.provider(),
			attempt.model(),
			QualityDecision.PASS,
			clock.instant(),
			List.of());
		Optional<Look> stored = lookStore.insertIfJobActive(look);
		if (stored.isEmpty()) {
			objectStore.delete(new ObjectRef(StorageDomain.LOOK, lookKey.value()));
			outputStore.discard();
			completeDeleted(generationStore.findJob(job.id()).orElse(latest), attempt, lease, temp, outputRef);
			return;
		}
		outputStore.discard();
		GenerationAttempt finished = finishedAttempt(attempt, "SUCCESS", null, temp.storageKey(), outputRef);
		boolean won = generationStore.completeTerminalIfActive(finished, clock.instant());
		if (won) {
			recordTerminalCategory(GenerationMetrics.TERMINAL_COMPLETED);
		}
		if (!won) {
			generationStore.completeAttempt(finished);
		}
		notifyReady(job, stored.get());
		recordCost(job, finished);
		releaseCapacity(attempt);

		log.info(
			"generation jobId={} attemptId={} provider={} code=LOOK_READY count={}",
			job.id().value(),
			attempt.id().value(),
			active.providerKey().value(),
			1);
		queue.delete(lease);
	}

	private void completeQualityRetry(
		GenerationJob job,
		GenerationAttempt attempt,
		GenerationWorkLease lease,
		ObjectStoreGenerationOutputStore outputStore,
		PrivateMediaRef temp,
		ProviderOutputRef outputRef,
		VtonProvider active
	) {
		outputStore.discard();
		GenerationAttempt finished = finishedAttempt(
			attempt,
			"SUCCESS",
			CODE_QUALITY_REJECTED,
			temp.storageKey(),
			outputRef);
		RetryDecision decision = retryDecisionEngine.decide(
			decisionContext(
				job,
				attempt,
				null,
				false,
				true,
				false,
				active));
		recordDisposition(decision.disposition());
		if (decision.disposition() == RetryDisposition.NO_RETRY) {
			TerminalReason reason = decision.terminalReason();
			if (reason == null) {
				persistSuppressedCompletion(generationStore.findJob(job.id()).orElse(job), finished);
				recordCost(job, finished);
				releaseCapacity(attempt);
				queue.delete(lease);
				return;
			}
			recordQualityRetry(true);
			recordExhaustion(reason);
			failTerminal(
				job,
				finished,
				lease,
				reason.name(),
				false,
				terminalCategoryForReason(reason));
			return;
		}
		applyEngineSchedule(job, finished, lease, null, decision);
	}

	private void completeQualityFail(
		GenerationJob job,
		GenerationAttempt attempt,
		GenerationWorkLease lease,
		ObjectStoreGenerationOutputStore outputStore,
		PrivateMediaRef temp,
		ProviderOutputRef outputRef,
		VtonProvider active
	) {
		outputStore.discard();
		GenerationAttempt finished = finishedAttempt(attempt, "SUCCESS", CODE_QUALITY_REJECTED, temp.storageKey(), outputRef);
		if (generationStore.completeTerminalIfActive(finished, clock.instant())) {
			recordTerminalCategory(GenerationMetrics.TERMINAL_FAILED);
			recordCost(job, finished);
			releaseCapacity(attempt);
			notifyFailed(job);
		}

		else if (completeAttemptAfterSubjectDeletion(job, finished)) {
			releaseCapacity(attempt);
		}
		queue.delete(lease);
	}

	/**
	 * Evaluator infrastructure failures re-evaluate the SAME private
	 * candidate at most once via the engine's EVALUATOR_RETRY disposition
	 * (Q11/R14): no new provider execution is ever authorized by an
	 * evaluator outage. A second unavailability terminates
	 * QUALITY_EVALUATOR_UNAVAILABLE without persisting a quality_result
	 * row. The attempt's evaluatorRetryCount tracks the consumption.
	 */
	private void evaluatorRetryOrTerminal(
		GenerationJob job,
		GenerationAttempt attempt,
		GenerationWorkLease lease,
		ObjectStoreGenerationOutputStore outputStore,
		ObjectMetadata metadata,
		PrivateMediaRef temp,
		QualityEvaluationRequest request,
		ProviderOutputRef outputRef,
		VtonProvider active
	) {
		RetryDecision decision = retryDecisionEngine.decide(
			decisionContext(
				job,
				attempt,
				null,
				false,
				false,
				true,
				active));
		recordDisposition(decision.disposition());
		if (decision.disposition() != RetryDisposition.EVALUATOR_RETRY) {
			outputStore.discard();
			TerminalReason reason = decision.terminalReason() != null
				? decision.terminalReason()
				: TerminalReason.QUALITY_EVALUATOR_UNAVAILABLE;
			recordEvaluatorRetry(true);
			recordExhaustion(reason);
			failTerminal(
				job,
				attempt,
				lease,
				reason.name(),
				false,
				GenerationMetrics.TERMINAL_FAILED);
			return;
		}
		recordEvaluatorRetry(false);
		GenerationAttempt bumped = withEvaluatorRetryCount(attempt, attempt.evaluatorRetryCount() + 1);
		if (!stillActive(job)) {
			outputStore.discard();
			queue.delete(lease);
			return;
		}
		QualityEvaluationOutcome retry = qualityGate.evaluate(request);
		switch (retry) {
			case QualityEvaluationOutcome.Completed completed -> {
				QualityResult quality = completed.result();
				recordQuality(quality);
				generationStore.saveQualityResult(quality);
				switch (quality.decision()) {
					case PASS -> completePass(job, bumped, lease, outputStore, metadata, temp, outputRef, active);
					case RETRY -> completeQualityRetry(job, bumped, lease, outputStore, temp, outputRef, active);
					case FAIL -> completeQualityFail(job, bumped, lease, outputStore, temp, outputRef, active);
				}
			}
			case QualityEvaluationOutcome.Unavailable unavailable -> {
				recordEvaluatorUnavailable(unavailable.reasonCode());
				outputStore.discard();
				recordEvaluatorRetry(true);
				recordExhaustion(TerminalReason.QUALITY_EVALUATOR_UNAVAILABLE);
				failTerminal(
					job,
					bumped,
					lease,
					TerminalReason.QUALITY_EVALUATOR_UNAVAILABLE.name(),
					false,
					GenerationMetrics.TERMINAL_FAILED);
			}
		}
	}

	/**
	 * Assembles the evaluation request from owner-scoped storage only (F16):
	 * the candidate is the already-read private TEMP copy; subject and garment
	 * bytes come from the job-authorized media references. Queue payloads and
	 * provider bytes are never used as references, and any reference load
	 * failure degrades to a null reference rather than crashing the worker
	 * turn; the bound policy decides how missing references are judged.
	 */
	private QualityEvaluationRequest qualityEvaluationRequest(
		GenerationJob job,
		GenerationAttempt attempt,
		byte[] candidateBytes
	) {
		byte[] subjectBytes = null;
		byte[] garmentBytes = null;
		try {
			ResolvedMedia media = mediaResolver.resolve(job);
			subjectBytes = loadAuthorizedBytes(media.avatar());
			int productIndex = Math.min(job.currentStep() - 1, media.products().size() - 1);
			if (productIndex >= 0) {
				garmentBytes = loadAuthorizedBytes(media.products().get(productIndex).media());
			}
		}
		catch (RuntimeException ex) {
			subjectBytes = null;
			garmentBytes = null;
		}
		int totalSteps = Math.max(1, job.totalSteps());
		int currentStep = Math.min(Math.max(1, job.currentStep()), totalSteps);
		return new QualityEvaluationRequest(
			attempt.id(),
			candidateBytes,
			subjectBytes,
			garmentBytes,
			null,
			QualityGate.boundPolicyVersion(attempt.qualityPolicyVersion()),
			new QualityEvaluationRequest.RenderStepContext(
				currentStep,
				totalSteps,
				currentStep >= totalSteps));
	}

	private byte[] loadAuthorizedBytes(PrivateMediaRef media) {
		if (media == null) {
			return null;
		}
		String key = media.storageKey().value();
		StorageDomain boundary;
		if (key.startsWith("temp/")) {
			boundary = StorageDomain.TEMP;
		}
		else if (key.contains("/avatars/")) {
			boundary = StorageDomain.AVATAR;
		}
		else if (key.contains("/vault/")) {
			boundary = StorageDomain.VAULT;
		}
		else if (key.contains("/generation-inputs/")) {
			boundary = StorageDomain.TEMP;
		}
		else if (key.contains("/looks/")) {
			boundary = StorageDomain.LOOK;
		}
		else {
			return null;
		}
		try {
			ObjectRef ref = new ObjectRef(boundary, key);
			ObjectMetadata metadata = objectStore.head(ref);
			return objectStore.readBounded(ref, metadata.version(), boundedReadCap());
		}
		catch (RuntimeException ex) {
			return null;
		}
	}


	private void handleProviderFailure(
		GenerationJob job,
		GenerationAttempt attempt,
		GenerationWorkLease lease,
		ProviderFailure failure,
		VtonProvider active
	) {
		if (failure == null) {
			// Rejected-with-null-failure violates the provider contract (D/Q25).
			failTerminal(job, attempt, lease, TerminalReason.PROVIDER_PROTOCOL_ERROR.name(), false);
			return;
		}
		recordProviderOutcome(failure);
		GenerationAttempt finished = finishedAttempt(
			attempt,
			"FAILED",
			failure.safeCode(),
			attempt.tempStorageKey(),
			attempt.providerOutputRef());
		RetryDecision decision = retryDecisionEngine.decide(
			decisionContext(
				job,
				attempt,
				failure,
				true,
				false,
				false,
				active));
		recordDisposition(decision.disposition());
		if (decision.disposition() == RetryDisposition.NO_RETRY) {
			TerminalReason reason = decision.terminalReason();
			if (reason == null) {
				persistSuppressedCompletion(generationStore.findJob(job.id()).orElse(job), finished);
				recordCost(job, finished);
				releaseCapacity(attempt);
				queue.delete(lease);
				return;
			}
			recordExhaustion(reason);
			failTerminal(
				job,
				finished,
				lease,
				reason.name(),
				false,
				terminalCategoryForReason(reason));
			return;
		}
		if (decision.disposition() == RetryDisposition.WAIT_EXISTING_ATTEMPT
			|| decision.disposition() == RetryDisposition.REPLAY_SAME_ATTEMPT) {
			persistSuppressedCompletion(generationStore.findJob(job.id()).orElse(job), finished);
			recordCost(job, finished);
			releaseCapacity(attempt);
			if (decision.disposition() == RetryDisposition.REPLAY_SAME_ATTEMPT) {
				wakeImmediately(job, lease);
			}
			else {
				continueLater(job, lease, FIRST_POLL_DELAY);
			}
			return;
		}
		applyEngineSchedule(job, finished, lease, failure, decision);
	}

	/**
	 * Executes the granted engine disposition (Q2): persists the finished
	 * attempt, schedules the durable retry time via the fenced store seam
	 * (Q34/Q46), and enqueues the wake-up only after the retry commits
	 * (Q38). CAS loss consumes no budget and yields after one reload (Q44).
	 */
	private void applyEngineSchedule(
		GenerationJob job,
		GenerationAttempt finished,
		GenerationWorkLease lease,
		ProviderFailure failure,
		RetryDecision decision
	) {
		GenerationJob latest = generationStore.findJob(job.id()).orElse(job);
		if (terminal(latest) || unavailable(latest)) {
			persistSuppressedCompletion(latest, finished);
			releaseCapacity(finished);
			queue.delete(lease);
			return;
		}
		Instant retryAt = decision.retryNotBeforeAt() != null
			? decision.retryNotBeforeAt()
			: clock.instant();
		boolean scheduled;
		try {
			scheduled = generationStore.scheduleRetryIfActive(finished, latest.statusVersion(), retryAt);
		}
		catch (UnsupportedOperationException ex) {
			failTerminal(latest, finished, lease, TerminalReason.INTERNAL_INVARIANT_FAILURE.name(), false);
			return;
		}
		if (!scheduled) {
			GenerationJob reloaded = generationStore.findJob(job.id()).orElse(latest);
			recordCasConflict();
			persistSuppressedCompletion(reloaded, finished);
			releaseCapacity(finished);
			recordSuppression(
				unavailable(reloaded)
					? GenerationMetrics.SUPPRESSION_CANCELLED_OR_DELETED
					: GenerationMetrics.SUPPRESSION_STALE_WORKER);
			queue.delete(lease);
			return;
		}
		recordLogicalAttempt(true);
		recordAdditionalBudget(false);
		recordCost(job, finished);
		log.info(
			"generation jobId={} attemptId={} code={} retryNotBeforeAt={}",
			job.id().value(),
			finished.id().value(),
			failure == null ? "RETRY" : failure.safeCode(),
			retryAt);
		wakeRetry(job, lease, retryAt);
	}

	private void handleIndeterminate(
		GenerationJob job,
		GenerationAttempt attempt,
		GenerationWorkLease lease,
		VtonProvider active
	) {
		generationStore.markSubmissionIndeterminate(attempt.id());
		recordAmbiguity();
		attempt = generationStore.findAttempt(attempt.id()).orElse(attempt);
		if (active.capabilities().submissionReplaySafety() == SubmissionReplaySafety.SAME_KEY_SAFE) {
			if (!replayAdmission(job, attempt, active)) {
				failTerminal(
					job,
					attempt,
					lease,
					TerminalReason.SUBMISSION_OUTCOME_INDETERMINATE.name(),
					true);
				return;
			}
			log.info(
				"generation jobId={} attemptId={} provider={} code=INDETERMINATE_REPLAY_SAFE",
				job.id().value(),
				attempt.id().value(),
				active.providerKey().value());
			continueLater(
				job,
				lease,
				AttemptRedeliveryPolicy.replaySafeRetransmissionDelay(attempt.transmissionCount()));
			return;
		}
		// Q23/R23: An ambiguous POST outcome without a provider execution ID
		// forbids any new provider execution for unsafe adapters. The attempt
		// enters bounded indeterminate recovery (mark + wait/reconcile); only
		// the submission recovery deadline terminates this as
		// SUBMISSION_OUTCOME_INDETERMINATE.
		log.info(
			"generation jobId={} attemptId={} provider={} code=INDETERMINATE_RECOVERY_WAIT",
			job.id().value(),
			attempt.id().value(),
			active.providerKey().value());
		continueLater(job, lease, FIRST_POLL_DELAY);
	}

	/**
	 * Builds the fully materialized, deterministic engine context for one
	 * retry decision (Q2/Q14). Canonical state is reloaded from the store on
	 * every call (Q40); the hard spend breaker (Q55) and the provider-health
	 * gate (Q56) are materialized here as the environment inputs the engine
	 * consults for new-execution routes.
	 */
	private RetryDecisionContext decisionContext(
		GenerationJob job,
		GenerationAttempt attempt,
		ProviderFailure failure,
		boolean technicalRetryRequested,
		boolean qualityRegenerationRequested,
		boolean evaluatorRetryRequested,
		VtonProvider active
	) {
		GenerationJob latest = generationStore.findJob(job.id()).orElse(job);
		List<GenerationAttempt> attempts = generationStore.listAttempts(job.id());
		int totalSteps = Math.max(1, latest.totalSteps());
		int currentStep = Math.min(Math.max(1, latest.currentStep()), totalSteps);
		Instant orchestrationDeadlineAt = latest.orchestrationDeadlineAt() == null
			? latest.createdAt().plus(GenerationJob.ORCHESTRATION_DEADLINE)
			: latest.orchestrationDeadlineAt();
		boolean timeoutWithExecution = failure != null
			&& failure.category() == ProviderFailureCategory.TIMEOUT_NETWORK
			&& attempt.hasDurableProviderJobId();
		return RetryDecisionContext.builder()
			.jobTerminal(terminal(latest))
			.cancelled(latest.cancelledAt() != null)
			.userDeleted(false)
			.relevantMediaDeleted(latest.subjectDeletedAt() != null)
			.policyResolution(resolvePolicyResolution(latest))
			.totalSteps(totalSteps)
			.currentStep(currentStep)
			.technicalRetriesUsed(additionalUsage(attempts))
			.logicalAttemptsUsed(attempts.size())
			.paidAttemptsUsed(attempts.size())
			.qualityRetriesUsedOnStep(qualityRetriesUsed(attempts, attempt.renderStepId()))
			.evaluatorRetriesOnCandidate(attempt.evaluatorRetryCount())
			.baselinesStillRequiringExecution(baselinesStillRequiring(latest, attempts))
			.maxAutonomousWait(policyMaxAutonomousWait(latest))
			.orchestrationDeadlineAt(orchestrationDeadlineAt)
 			.spendBlocked(false)
			.providerHealthy(providerHealthGate.allowsNewExecution(active.providerKey()))
			.providerCapable(providerCapable(active, latest))
			.technicalRetryRequested(technicalRetryRequested)
			.qualityRegenerationRequested(qualityRegenerationRequested)
			.evaluatorRetryRequested(evaluatorRetryRequested)
			.fallbackCapableProviderAvailable(false)
			.now(clock.instant())
			.jitterFraction(randomFraction.getAsDouble())
			.persistedRetryNotBeforeAt(latest.retryNotBeforeAt())
			.retryAfterMinimum(retryAfterMinimum(failure))
			.retryAfterResolvedFrom(retryAfterMinimum(failure) == null ? null : "provider")
			.providerFailureCategory(failure == null ? null : failure.category())
			.provenNotSubmitted(false)
			.externalExecutionPersisted(!timeoutWithExecution && attempt.hasDurableProviderJobId())
			.providerConfirmedTimeout(timeoutWithExecution)
			.submissionIndeterminate(attempt.submissionIndeterminate())
			.recoveryDeadlineExpired(attempt.submissionRecoveryDeadlineAt() != null
				&& !clock.instant().isBefore(attempt.submissionRecoveryDeadlineAt()))
			.replaySafeEvidence(active.capabilities().submissionReplaySafety()
				== SubmissionReplaySafety.SAME_KEY_SAFE)
			.remoteNotFoundLocalReconcileDone(false)
			.remoteProvenCannotExist(false)
			.build();
	}

	/**
	 * Resolves the fail-closed policy state for a job without substituting
	 * current defaults (Q5/R9). The store's resolution is canonical so
	 * malformed and unknown-version rows keep their exact state end to end;
	 * jobs that resolve nothing fail closed as MISSING.
	 */
	private RetryPolicyResolution resolvePolicyResolution(GenerationJob job) {
		try {
			RetryPolicyResolution resolution = generationStore.resolveRetryPolicy(job.id());
			if (resolution != null) {
				return resolution;
			}
		}
		catch (UnsupportedOperationException ignored) {
			// Store seams without the resolution seam fall through to the
			// snapshot carried on the job below.
		}
		RetryPolicySnapshot snapshot = job.retryPolicySnapshot();
		if (snapshot == null) {
			return new RetryPolicyResolution(RetryPolicyResolution.State.MISSING, null);
		}
		return new RetryPolicyResolution(RetryPolicyResolution.State.VALID, snapshot);
	}

	private Duration retryAfterMinimum(ProviderFailure failure) {
		if (failure == null || failure.retryAfterAt() == null) {
			return null;
		}
		Instant now = clock.instant();
		if (!failure.retryAfterAt().isAfter(now)) {
			return null;
		}
		return Duration.between(now, failure.retryAfterAt());
	}

	private Duration policyMaxAutonomousWait(GenerationJob job) {
		RetryPolicySnapshot policy = job.retryPolicySnapshot();
		Duration wait = policy == null
			? RetryBackoff.MAX_AUTONOMOUS_WAIT
			: policy.maxAutonomousWait();
		return wait.compareTo(RetryBackoff.MAX_AUTONOMOUS_WAIT) <= 0
			? wait
			: RetryBackoff.MAX_AUTONOMOUS_WAIT;
	}

	private boolean providerCapable(VtonProvider active, GenerationJob job) {
		return active.capabilities().forMode(mode(job)).isPresent();
	}

	private static int additionalUsage(List<GenerationAttempt> attempts) {
		long distinctSteps = attempts.stream()
			.map(GenerationAttempt::renderStepId)
			.filter(value -> value != null)
			.distinct()
			.count();
		long baseline = distinctSteps > 0 ? distinctSteps : attempts.size();
		return (int) Math.max(0L, attempts.size() - baseline);
	}

	private static int baselinesStillRequiring(GenerationJob job, List<GenerationAttempt> attempts) {
		int totalSteps = Math.max(1, job.totalSteps());
		long attemptedSteps = attempts.stream()
			.map(GenerationAttempt::renderStepId)
			.filter(value -> value != null)
			.distinct()
			.count();
		if (attemptedSteps == 0) {
			// Render-plan-less attempts all target the implicit current step,
			// which is covered once any attempt exists (Q16).
			return attempts.isEmpty() ? totalSteps : Math.max(0, totalSteps - 1);
		}
		int covered = (int) Math.min(totalSteps, attemptedSteps);
		return Math.max(0, totalSteps - covered);
	}

	/**
	 * Admission gate for new provider executions (Q14). The policy-validity
	 * gate (R9/Q5) sits before the hard spend breaker (Q55) and the
	 * provider-health gate (Q56): a missing, malformed, or unknown-version
	 * persisted policy row refuses spend before any spend-gate side effect.
	 * These gates block new executions only, never reconciliation of
	 * already-submitted attempts.
	 */
	private boolean newExecutionGatesAdmit(
		GenerationJob job,
		GenerationAttempt attempt,
		GenerationWorkLease lease,
		VtonProvider active
	) {
		TerminalReason policyGateReason = retryPolicyGateReason(job);
		if (policyGateReason != null) {
			recordDisposition(RetryDisposition.NO_RETRY);
			recordExhaustion(policyGateReason);
			failTerminal(
				job,
				attempt,
				lease,
				policyGateReason.name(),
				false,
				GenerationMetrics.TERMINAL_BUDGET_EXHAUSTED);
			return false;
		}
		if (costCircuitBlocks(job, attempt, active)) {
			recordDisposition(RetryDisposition.NO_RETRY);
			recordExhaustion(TerminalReason.COST_BUDGET_BLOCKED);
			failTerminal(
				job,
				attempt,
				lease,
				TerminalReason.COST_BUDGET_BLOCKED.name(),
				false,
				GenerationMetrics.TERMINAL_FAILED);
			return false;
		}
		if (!providerHealthGate.allowsNewExecution(active.providerKey())) {
			recordDisposition(RetryDisposition.NO_RETRY);
			recordExhaustion(TerminalReason.PROVIDER_UNAVAILABLE);
			failTerminal(
				job,
				attempt,
				lease,
				TerminalReason.PROVIDER_UNAVAILABLE.name(),
				false,
				GenerationMetrics.TERMINAL_FAILED);
			return false;
		}
		return true;
	}

 	private boolean costCircuitBlocks(GenerationJob job, GenerationAttempt attempt, VtonProvider active) {
 		return !costCircuitBreaker.authorize(new ProviderExecutionBudgetRequest(
 			job.userId(),
 			active.providerKey().value(),
 			attempt.model(),
 			attempt.estimatedCostCeiling(),
 			attempt.currency(),
 			clock.instant())).allowed();
 	}

	/**
	 * Maps a non-valid persisted retry-policy row to its fail-closed terminal
	 * reason (R9/Q5), with EMPTY collapsing to MISSING exactly like the
	 * engine's policy gate. Returns {@code null} when the persisted row is
	 * VALID. The resolution is canonical to the store seam so INVALID and
	 * UNSUPPORTED_VERSION rows keep their exact reason end to end; store seams
	 * without the resolution default degrade to the snapshot carried on the
	 * job rather than silently admitting.
	 */
	private TerminalReason retryPolicyGateReason(GenerationJob job) {
		RetryPolicyResolution resolution = resolvePolicyResolution(job);
		if (resolution.state() == RetryPolicyResolution.State.VALID) {
			return null;
		}
		return switch (resolution.state()) {
			case MISSING, EMPTY -> TerminalReason.RETRY_POLICY_MISSING;
			case INVALID -> TerminalReason.RETRY_POLICY_INVALID;
			case UNSUPPORTED_VERSION -> TerminalReason.RETRY_POLICY_UNSUPPORTED_VERSION;
			case VALID -> throw new IllegalStateException("unreachable");
		};
	}

	private static String terminalCategoryForReason(TerminalReason reason) {
		return switch (reason) {
			case CANCELLED_BY_USER -> GenerationMetrics.TERMINAL_CANCELLED;
			case USER_DELETED, INPUT_MEDIA_DELETED, INPUT_MEDIA_MISSING -> GenerationMetrics.TERMINAL_SUBJECT_DELETED;
			case SUBMISSION_OUTCOME_INDETERMINATE, PROVIDER_UNKNOWN_FAILURE -> GenerationMetrics.TERMINAL_UNKNOWN;
			case ORCHESTRATION_DEADLINE_EXCEEDED -> GenerationMetrics.TERMINAL_DEADLINE_EXPIRED;
			case RETRY_BUDGET_EXHAUSTED, QUALITY_RETRY_EXHAUSTED, QUALITY_EVALUATOR_UNAVAILABLE,
				COST_BUDGET_BLOCKED, RETRY_POLICY_MISSING, RETRY_POLICY_INVALID,
				RETRY_POLICY_UNSUPPORTED_VERSION -> GenerationMetrics.TERMINAL_BUDGET_EXHAUSTED;
			default -> GenerationMetrics.TERMINAL_FAILED;
		};
	}

	private GenerationAttempt withEvaluatorRetryCount(GenerationAttempt attempt, int evaluatorRetryCount) {
		return new GenerationAttempt(
			attempt.id(),
			attempt.generationJobId(),
			attempt.provider(),
			attempt.model(),
			attempt.providerJobId(),
			attempt.attemptNumber(),
			attempt.startedAt(),
			attempt.completedAt(),
			attempt.latencyMs(),
			attempt.creditsUsed(),
			attempt.estimatedCost(),
			attempt.currency(),
			attempt.failureCode(),
			attempt.technicalOutcome(),
			attempt.submissionKey(),
			attempt.submitOwnedAt(),
			attempt.submissionIndeterminate(),
			attempt.tempStorageKey(),
			attempt.providerOutputRef(),
			attempt.renderStepId(),
			attempt.transmissionCount(),
			attempt.providerAcceptedAt(),
			attempt.executionDeadlineAt(),
			attempt.submissionRecoveryDeadlineAt(),
			attempt.qualityPolicyVersion(),
			attempt.pricingCatalogVersion(),
			attempt.estimatedUnits(),
			attempt.unitPrice(),
			attempt.estimatedCostCeiling(),
			attempt.actualCost(),
			attempt.costStatus(),
			attempt.actualBilledUnits(),
			attempt.pricingObservedAt(),
			attempt.billingUnit(),
			attempt.errorMappingContractVersion(),
			evaluatorRetryCount);
	}

	private void recordDisposition(RetryDisposition disposition) {
		if (metrics != null) {
			metrics.recordDisposition(disposition);
		}
	}

	private void recordExhaustion(TerminalReason reason) {
		if (metrics != null) {
			metrics.recordExhaustionReason(reason);
		}
	}

	private void recordCasConflict() {
		if (metrics != null) {
			metrics.recordCasConflict();
		}
	}

	private void recordEvaluatorRetry(boolean exhausted) {
		if (metrics != null) {
			metrics.recordEvaluatorRetry(exhausted);
		}
	}

	private void recordStorageUnavailable() {
		if (metrics != null) {
			metrics.recordStorageUnavailable();
		}
	}

	private void failTerminal(
		GenerationJob job,
		GenerationAttempt attempt,
		GenerationWorkLease lease,
		String failureCode,
		boolean indeterminate
	) {
		failTerminal(job, attempt, lease, failureCode, indeterminate, null);
	}

	private void failTerminal(
		GenerationJob job,
		GenerationAttempt attempt,
		GenerationWorkLease lease,
		String failureCode,
		boolean indeterminate,
		String terminalCategory
	) {
		if (indeterminate) {
			generationStore.markSubmissionIndeterminate(attempt.id());
			recordAmbiguity();
			attempt = generationStore.findAttempt(attempt.id()).orElse(attempt);
			if (attempt.costStatus() == null || PricingSnapshot.COST_ESTIMATED.equals(attempt.costStatus())) {
				attempt = attempt.markCostUnknown();
			}
		}
		GenerationAttempt finished = finishedAttempt(
			attempt,
			"FAILED",
			failureCode,
			attempt.tempStorageKey(),
			attempt.providerOutputRef());
		boolean won = generationStore.completeTerminalIfActive(finished, clock.instant());
		if (won) {
			GenerationJob latest = generationStore.findJob(job.id()).orElse(job);
			recordTerminalCategory(
				terminalCategory == null
					? terminalCategoryFor(latest, failureCode, indeterminate)
					: terminalCategory);
			recordCost(job, finished);
			releaseCapacity(attempt);
			notifyFailed(job);
		}
		else if (completeAttemptAfterSubjectDeletion(job, finished)) {
			releaseCapacity(attempt);
		}
		else if (lookStore.findByGenerationJobId(job.id()).isEmpty()) {
			releaseCapacity(attempt);
			notifyFailed(job);
		}
		log.info(
			"generation jobId={} attemptId={} provider={} code={}",
			job.id().value(),
			attempt.id().value(),
			resolveProvider(attempt).providerKey().value(),
			failureCode);
		queue.delete(lease);
	}


	private void resumeIncompleteTerminal(GenerationJob job, GenerationAttempt attempt, GenerationWorkLease lease) {
		boolean won = generationStore.completeTerminalIfActive(attempt, clock.instant());
		if (won) {
			GenerationJob latest = generationStore.findJob(job.id()).orElse(job);
			recordTerminalCategory(terminalCategoryFor(latest, attempt.failureCode(), false));
		}
		ensureNotification(job, attempt);
		queue.delete(lease);
	}

    private void completeDeleted(
        GenerationJob job,
        GenerationAttempt attempt,
        GenerationWorkLease lease,
        PrivateMediaRef temp,
        ProviderOutputRef outputRef
    ) {
        completeDeleted(job, attempt, lease, temp, outputRef, TerminalReason.USER_DELETED.name());
    }

    private void completeDeleted(
        GenerationJob job,
        GenerationAttempt attempt,
        GenerationWorkLease lease,
        PrivateMediaRef temp,
        ProviderOutputRef outputRef,
        String failureCode
    ) {
        GenerationAttempt finished = finishedAttempt(
            attempt,
            "SUCCESS",
            failureCode,
            temp.storageKey(),
            outputRef);
        boolean won = generationStore.completeTerminalIfActive(finished, clock.instant());
        if (won) {
            recordTerminalCategory(GenerationMetrics.TERMINAL_SUBJECT_DELETED);
        }
        if (!won) {
            completeAttemptAfterSubjectDeletion(job, finished);
        }
        releaseCapacity(attempt);
        log.info("generation jobId={} attemptId={} code={}", job.id().value(), attempt.id().value(), failureCode);
        queue.delete(lease);
    }


	private boolean completeAttemptAfterSubjectDeletion(GenerationJob job, GenerationAttempt finished) {
		GenerationJob latest = generationStore.findJob(job.id()).orElse(job);
		if (latest.subjectDeletedAt() == null && !CODE_SUBJECT_DELETED.equals(latest.terminalReason())) {
			return false;
		}
		generationStore.completeAttempt(finished);
		return true;
	}

	private void continueLater(GenerationJob job, GenerationWorkLease lease, Duration delay) {
		if (workOutboxStore == null) {
			return;
		}
		workOutboxStore.insertSuccessorIfActive(job.id().value(), clock.instant().plus(delay));
		queue.delete(lease);
	}

	private void extendVisibility(GenerationWorkLease lease) {
		try {
			queue.extendVisibility(lease, VISIBILITY_EXTENSION);
		}
		catch (RuntimeException ex) {
			log.info("generation jobId={} code=VISIBILITY_EXTENSION_FAILED", lease.generationJobId());
		}
	}

	private boolean acknowledgeUnavailableOrTerminal(GenerationJob job, GenerationWorkLease lease) {
		if (terminal(job)) {
			queue.delete(lease);
			return true;
		}
		if (unavailable(job)) {
			discardLeftoverTemp(job);
			log.info("generation jobId={} code=SUBJECT_UNAVAILABLE", job.id().value());
			queue.delete(lease);
			return true;
		}
		return false;
	}

	private static boolean terminal(GenerationJob job) {
		return job.completedAt() != null
			|| GenerationJob.COMPLETED.equals(job.status())
			|| GenerationJob.FAILED.equals(job.status());
	}


	private void ensureNotification(GenerationJob job, GenerationAttempt attempt) {
		Optional<Look> look = lookStore.findByGenerationJobId(job.id());
		if (look.isPresent()) {
			notifyReady(job, look.get());
			return;
		}
		notifyFailed(job);
	}

	private GenerationRetryBudget budgetFor(GenerationJob job) {
		RetryPolicySnapshot policy = job.retryPolicySnapshot();
		if (policy == null) {
			// Fail closed (R9/Q5): no readable policy row authorizes no spend.
			return null;
		}
		long derivedMax = (long) job.totalSteps() + RetryPolicySnapshot.ADDITIONAL_LOGICAL_ATTEMPTS;
		int maxPaidAttempts = (int) Math.min(derivedMax, policy.maxPaidAttempts());
		return new GenerationRetryBudget(maxPaidAttempts);
	}

	private boolean admitLogicalAttempt(GenerationJob job, int logicalAttemptsUsed) {
		GenerationRetryBudget budget = budgetFor(job);
		boolean admitted = budget != null && budget.allowsLogicalAttempt(logicalAttemptsUsed);
		if (!admitted) {
			recordAdditionalBudget(true);
		}
		return admitted;
	}

	private boolean admitLogicalAttempt(
		GenerationJob job,
		List<GenerationAttempt> attempts,
		UUID renderStepId
	) {
		if (!admitLogicalAttempt(job, attempts.size())) {
			return false;
		}
		long attemptsForStep = attempts.stream()
			.filter(existing -> sameRenderStep(existing.renderStepId(), renderStepId))
			.count();
		return attemptsForStep < MAX_LOGICAL_ATTEMPTS_PER_STEP;
	}

	private static boolean sameRenderStep(UUID left, UUID right) {
		return left == null ? right == null : left.equals(right);
	}

	private boolean completedCurrentStepSuccessfully(GenerationJob job, GenerationAttempt attempt) {
		if (!"SUCCESS".equals(attempt.technicalOutcome()) || attempt.failureCode() != null) {
			return false;
		}
		Optional<RenderPlan> plan = renderPlan(job.id());
		if (plan.isEmpty() || job.currentStep() < 1 || job.currentStep() > plan.get().steps().size()) {
			return false;
		}
		return plan.get().steps().get(job.currentStep() - 1).id().equals(attempt.renderStepId());
	}
	private boolean retryDueAndExhausted(
		GenerationJob job,
		Instant now,
		List<GenerationAttempt> attempts,
		UUID renderStepId
	) {
		return GenerationJob.RETRYING.equals(job.status())
			&& job.retryNotBeforeAt() != null
			&& !now.isBefore(job.retryNotBeforeAt())
			&& !admitLogicalAttempt(job, attempts, renderStepId);
	}


	private static int nextAttemptNumber(List<GenerationAttempt> attempts) {
		int highest = 0;
		for (GenerationAttempt attempt : attempts) {
			highest = Math.max(highest, attempt.attemptNumber());
		}
		if (highest == Integer.MAX_VALUE) {
			throw new IllegalStateException("attempt number exhausted");
		}
		return highest + 1;
	}

	private GenerationAttempt ensureNextBaseline(GenerationJob job, List<GenerationAttempt> attempts) {
		if (terminal(job) || unavailable(job) || job.currentStep() > job.totalSteps()) {
			return null;
		}
		Optional<RenderPlan> plan = renderPlan(job.id());
		UUID stepId = plan
			.filter(value -> job.currentStep() <= value.steps().size())
			.map(value -> value.steps().get(job.currentStep() - 1).id())
			.orElse(null);
		if (stepId != null) {
			Optional<GenerationAttempt> open = attempts.stream()
				.filter(existing -> stepId.equals(existing.renderStepId()))
				.filter(existing -> existing.completedAt() == null)
				.findFirst();
			if (open.isPresent()) {
				return open.get();
			}
		}
		if (stepId == null
			&& !attempts.isEmpty()
			&& attempts.getLast().completedAt() == null) {
			return attempts.getLast();
		}
		if (!admitLogicalAttempt(job, attempts, stepId)) {
			return null;
		}
		GenerationAttempt next = newAttempt(job, nextAttemptNumber(attempts), stepId);
		generationStore.insertAttempt(next);
		recordLogicalAttempt(false);
		return next;
	}

	private Optional<RenderPlan> renderPlan(GenerationJobId jobId) {
		try {
			Optional<RenderPlan> plan = generationStore.findRenderPlan(jobId);
			return plan == null ? Optional.empty() : plan;
		}
		catch (UnsupportedOperationException ex) {
			return Optional.empty();
		}
	}

	/**
	 * Q23 owns the terminal write when the submission recovery deadline
	 * coincides with the orchestration deadline: newAttempt clamps
	 * submissionRecoveryDeadlineAt to the earlier of the two, so once an open
	 * indeterminate attempt's recovery deadline has expired the tick must reach
	 * attemptDeadlineExceeded and terminate as SUBMISSION_OUTCOME_INDETERMINATE
	 * instead of being overtaken by the job-level ORCHESTRATION_DEADLINE_EXCEEDED
	 * short-circuit (Q28). Returns false for an absent or already-completed
	 * attempt, which cannot reach attemptDeadlineExceeded, so the orchestration
	 * hard stop still applies to those.
	 */
	private boolean indeterminateRecoveryExpired(GenerationJobId jobId, Instant now) {
		List<GenerationAttempt> attempts = generationStore.listAttempts(jobId);
		if (attempts.isEmpty()) {
			return false;
		}
		GenerationAttempt latest = attempts.getLast();
		return latest.completedAt() == null
			&& latest.submissionIndeterminate()
			&& (latest.submissionRecoveryDeadlineAt() == null
				|| !now.isBefore(latest.submissionRecoveryDeadlineAt()));
	}

	private boolean attemptDeadlineExceeded(
		GenerationJob job,
		GenerationAttempt attempt,
		GenerationWorkLease lease
	) {
		Instant now = clock.instant();
		if (attempt.submissionIndeterminate()
			&& (attempt.submissionRecoveryDeadlineAt() == null
				|| !now.isBefore(attempt.submissionRecoveryDeadlineAt()))) {
			recordRecovery(GenerationMetrics.RECOVERY_DEADLINE);
			failTerminal(
				job,
				attempt,
				lease,
				TerminalReason.SUBMISSION_OUTCOME_INDETERMINATE.name(),
				true,
				GenerationMetrics.TERMINAL_UNKNOWN);
			return true;
		}
		if (!attempt.submissionIndeterminate()
			&& attempt.hasDurableProviderJobId()
			&& attempt.executionDeadlineAt() != null
			&& !now.isBefore(attempt.executionDeadlineAt())) {
			recordRecovery(GenerationMetrics.EXECUTION_DEADLINE);
			failTerminal(
				job,
				attempt,
				lease,
				TerminalReason.PROVIDER_TIMEOUT_EXHAUSTED.name(),
				false,
				GenerationMetrics.TERMINAL_FAILED);
			return true;
		}
		return false;
	}


	private boolean replayAdmission(GenerationJob job, GenerationAttempt attempt, VtonProvider active) {
		if (attempt.submissionRecoveryDeadlineAt() == null
			|| job.orchestrationDeadlineAt() == null
			|| !job.active()
			|| unavailable(job)
			|| terminal(job)) {
			return false;
		}
		RetryPolicySnapshot policy = job.retryPolicySnapshot();
		if (policy == null) {
			// Fail closed (R9/Q5): a same-key retransmission whose persisted
			// policy row is unreadable is denied; callers mark the outcome
			// indeterminate through the existing ambiguity handling.
			recordReplaySafeRetransmission(true);
			return false;
		}
		if (attempt.transmissionCount() >= policy.maxTransmissionsPerAttempt()) {
			recordReplaySafeRetransmission(true);
			return false;
		}
		boolean allowed = AttemptRedeliveryPolicy.replaySafeRetransmissionAllowed(
			attempt.transmissionCount(),
			attempt.providerAcceptedAt() != null || attempt.hasDurableProviderJobId(),
			active.capabilities().submissionReplaySafety(),
			attempt.submissionRecoveryDeadlineAt(),
			job.orchestrationDeadlineAt(),
			clock.instant(),
			true);
		if (!allowed) {
			recordReplaySafeRetransmission(
				attempt.transmissionCount() >= policy.maxTransmissionsPerAttempt());
		}
		return allowed;
	}

	private GenerationAttempt recordTransmissionOrFail(
		GenerationJob job,
		GenerationAttempt attempt,
		GenerationWorkLease lease,
		AttemptRedeliveryPolicy.Decision decision
	) {
		boolean recorded;
		try {
			recorded = generationStore.tryRecordTransmission(attempt.id(), clock.instant());
		}
		catch (UnsupportedOperationException ex) {
			recorded = false;
		}
		if (!recorded) {
			if (decision == AttemptRedeliveryPolicy.Decision.REPLAY_SAME_KEY) {
				recordReplaySafeRetransmission(true);
			}
			failTerminal(job, attempt, lease, TerminalReason.SUBMISSION_OUTCOME_INDETERMINATE.name(), true);
			return null;
		}
		GenerationAttempt persisted = generationStore.findAttempt(attempt.id()).orElse(attempt);
		attempt = mergePersistedTransmission(persisted, attempt);
		if (decision == AttemptRedeliveryPolicy.Decision.REPLAY_SAME_KEY) {
			recordReplaySafeRetransmission(false);
		}
		return attempt;
	}

	private int qualityRetriesUsed(List<GenerationAttempt> attempts, UUID renderStepId) {
		return (int) attempts.stream()
			.filter(attempt -> CODE_QUALITY_REJECTED.equals(attempt.failureCode()))
			.filter(attempt -> renderStepId == null
				? attempt.renderStepId() == null
				: renderStepId.equals(attempt.renderStepId()))
			.count();
	}

	private boolean retryableFailure(ProviderFailure failure) {
		if (failure == null || !failure.retryable()) {
			return false;
		}
		return switch (failure.category()) {
			case TIMEOUT_NETWORK, RATE_CAPACITY, TEMPORARY_UNAVAILABLE -> true;
			default -> false;
		};
	}

	private static String terminalCategoryFor(
		GenerationJob job,
		String failureCode,
		boolean indeterminate
	) {
		if (job != null && (job.cancelledAt() != null
			|| GenerationJob.CANCELLED_BY_USER.equals(job.terminalReason()))) {
			return GenerationMetrics.TERMINAL_CANCELLED;
		}
		if (job != null && (job.subjectDeletedAt() != null
			|| GenerationJob.SUBJECT_DELETED.equals(job.terminalReason())
			|| CODE_SUBJECT_DELETED.equals(failureCode))) {
			return GenerationMetrics.TERMINAL_SUBJECT_DELETED;
		}
		if (indeterminate
			|| CODE_SUBMISSION_UNKNOWN.equals(failureCode)
			|| CODE_INDETERMINATE.equals(failureCode)
			|| CODE_UNSAFE_REPLAY.equals(failureCode)) {
			return GenerationMetrics.TERMINAL_UNKNOWN;
		}
		if (CODE_EXECUTION_DEADLINE.equals(failureCode)
			|| CODE_RECOVERY_DEADLINE.equals(failureCode)
			|| CODE_ORCHESTRATION_DEADLINE.equals(failureCode)) {
			return GenerationMetrics.TERMINAL_DEADLINE_EXPIRED;
		}
		return GenerationMetrics.TERMINAL_FAILED;
	}


	private void recordProviderOutcome(ProviderFailure failure) {
		if (metrics == null || failure == null) {
			return;
		}
		metrics.recordProviderOutcome(switch (failure.category()) {
			case TIMEOUT_NETWORK -> GenerationMetrics.PROVIDER_TIMEOUT;
			case RATE_CAPACITY -> GenerationMetrics.PROVIDER_RATE_LIMIT;
			case TEMPORARY_UNAVAILABLE -> GenerationMetrics.PROVIDER_INTERNAL;
			case INVALID_REQUEST, UNSUPPORTED_CAPABILITY, CONTENT_POLICY_REJECTION, AUTH_CONFIGURATION,
				QUOTA_BILLING, REMOTE_EXECUTION_NOT_FOUND, PROTOCOL_VIOLATION -> GenerationMetrics.PROVIDER_NON_RETRYABLE;
			case UNKNOWN_PROVIDER_FAILURE -> GenerationMetrics.PROVIDER_UNKNOWN;
		});
	}

	private void recordReplaySafeRetransmission(boolean capExhausted) {
		if (metrics != null) {
			metrics.recordReplaySafeRetransmission(capExhausted);
		}
	}

	private void recordRecovery(String kind) {
		if (metrics != null) {
			metrics.recordRecovery(kind);
		}
	}

	private void recordSuppression(String kind) {
		if (metrics != null) {
			metrics.recordSuppression(kind);
		}
	}

	private void recordAdditionalBudget(boolean exhausted) {
		if (metrics != null) {
			metrics.recordAdditionalBudget(exhausted);
		}
	}

	private void recordLogicalAttempt(boolean retry) {
		if (metrics != null) {
			metrics.recordLogicalAttempt(retry);
		}
	}

	private void recordQualityRetry(boolean exhausted) {
		if (metrics != null) {
			metrics.recordQualityRetry(exhausted);
		}
	}

	private void persistSuppressedCompletion(GenerationJob job, GenerationAttempt finished) {
		if (finished == null) {
			return;
		}
		if (terminal(job) || unavailable(job)) {
			generationStore.completeAttempt(finished);
		}
	}

	private void wakeRetry(GenerationJob job, GenerationWorkLease lease, Instant retryAt) {
		if (workOutboxStore != null) {
			workOutboxStore.insertSuccessorIfActive(job.id().value(), retryAt);
		}
		queue.delete(lease);
	}

	private void wakeImmediately(GenerationJob job, GenerationWorkLease lease) {
		if (workOutboxStore != null) {
			workOutboxStore.insertSuccessorIfActive(job.id().value(), clock.instant());
		}
		queue.delete(lease);
	}
	private void notifyReady(GenerationJob job, Look look) {
		GenerationJob latest = generationStore.findJob(job.id()).orElse(job);
		if (unavailable(latest) || !GenerationJob.COMPLETED.equals(latest.status())) {
			return;
		}
		Instant now = clock.instant();
		notificationStore.insert(Notification.lookReady(
			new NotificationId(UUID.randomUUID()), latest.userId(), look.id(), now));
		insertNotificationEvent(NotificationEvent.lookReady(
			UUID.randomUUID(), latest.userId(), latest.id(), look.id(), now, now.plus(NOTIFICATION_TTL)));
		recordTransition(job.status(), GenerationJob.COMPLETED);
	}

	/**
	 * Emits the terminal failure notification with the opaque generation id
	 * plus the coarse product-safe terminal reason when one exists
	 * (Q61/R12). Deletion, cancellation, and USER_DELETED terminations
	 * never push (Q31). Terminal idempotency stays with the
	 * UNIQUE(generation_job_id, type) event insert.
	 */
	private void notifyFailed(GenerationJob job) {
		GenerationJob latest = generationStore.findJob(job.id()).orElse(job);
		if (suppressedFailureNotification(latest)) {
			return;
		}
		Instant now = clock.instant();
		notificationStore.insert(Notification.generationFailed(
			new NotificationId(UUID.randomUUID()), latest.userId(), latest.id(), now));
		insertNotificationEvent(NotificationEvent.generationFailed(
			UUID.randomUUID(), latest.userId(), latest.id(), now, now.plus(NOTIFICATION_TTL)));
		recordTransition(latest.status(), GenerationJob.FAILED);
	}

	private static boolean suppressedFailureNotification(GenerationJob job) {
		return job.cancelledAt() != null
			|| job.subjectDeletedAt() != null
			|| CODE_CANCELLED_BY_USER.equals(job.terminalReason())
			|| CODE_SUBJECT_DELETED.equals(job.terminalReason())
			|| CODE_USER_DELETED.equals(job.terminalReason());
	}

	/**
	 * Coerces the persisted terminal reason into the notification-safe
	 * vocabulary (Q61/R12): only {@link TerminalReason} enum names reach the
	 * payload; legacy and provider-shaped strings are omitted so the payload
	 * degrades to the opaque generationId instead of leaking non-product
	 * vocabulary.
	 */
	private static String notificationReason(GenerationJob job) {
		String terminalReason = job.terminalReason();
		if (terminalReason == null || terminalReason.isBlank()) {
			return null;
		}
		try {
			return TerminalReason.valueOf(terminalReason).name();
		}
		catch (IllegalArgumentException ignored) {
			return null;
		}
	}

	private GenerationAttempt confirmCallbackCandidate(
		GenerationJob job,
		GenerationAttempt attempt,
		VtonProvider active
	) {
		if (callbackInboxStore == null || attempt.hasDurableProviderJobId()) {
			return attempt;
		}
		for (CallbackInbox inbox : callbackInboxStore.findByAttempt(attempt.id())) {
			String candidate = inbox.candidateProviderJobId();
			if (candidate == null || candidate.isBlank()) {
				continue;
			}
			StatusQueryResult result = active.getStatus(new ProviderJobId(candidate));
			if (!(result instanceof StatusQueryResult.Observed)) {
				continue;
			}
			if (generationStore.recordProviderExecutionIfActive(attempt.id(), new ProviderJobId(candidate))) {
				generationStore.markSubmitted(job.id());
				return generationStore.findAttempt(attempt.id()).orElse(attempt);
			}
		}
		return attempt;
	}

	private boolean callbackRecoveryPending(GenerationAttempt attempt) {
		if (callbackInboxStore == null || attempt.submitOwnedAt() == null) {
			return false;
		}
		boolean hasCandidate = false;
		for (CallbackInbox inbox : callbackInboxStore.findByAttempt(attempt.id())) {
			if (inbox.candidateProviderJobId() != null && !inbox.candidateProviderJobId().isBlank()) {
				hasCandidate = true;
				break;
			}
		}
		if (!hasCandidate) {
			return false;
		}
		Duration elapsed = Duration.between(attempt.submitOwnedAt(), clock.instant());
		return !AttemptRedeliveryPolicy.failUnsafeAmbiguityPromptly(true, elapsed);
	}

	private GenerationAttempt finishedAttempt(
		GenerationAttempt attempt,
		String technicalOutcome,
		String failureCode,
		StorageKey tempStorageKey,
		ProviderOutputRef outputRef
	) {
		Instant now = clock.instant();
		int latency = (int) Math.max(0L, now.toEpochMilli() - attempt.startedAt().toEpochMilli());
		return new GenerationAttempt(
			attempt.id(),
			attempt.generationJobId(),
			attempt.provider(),
			attempt.model(),
			attempt.providerJobId(),
			attempt.attemptNumber(),
			attempt.startedAt(),
			now,
			latency,
			attempt.creditsUsed(),
			attempt.estimatedCost(),
			attempt.currency(),
			failureCode,
			technicalOutcome,
			attempt.submissionKey(),
			attempt.submitOwnedAt(),
			attempt.submissionIndeterminate(),
			tempStorageKey,
			outputRef,
			attempt.renderStepId(),
			attempt.transmissionCount(),
			attempt.providerAcceptedAt(),
			attempt.executionDeadlineAt(),
			attempt.submissionRecoveryDeadlineAt(),
			attempt.qualityPolicyVersion() == null
				? QualityGate.BASELINE_POLICY_VERSION
				: attempt.qualityPolicyVersion(),
			attempt.pricingCatalogVersion(),
			attempt.estimatedUnits(),
			attempt.unitPrice(),
			attempt.estimatedCostCeiling(),
			attempt.actualCost(),
			attempt.costStatus(),
			attempt.actualBilledUnits(),
			attempt.pricingObservedAt(),
			attempt.billingUnit(),
			attempt.errorMappingContractVersion(),
			attempt.evaluatorRetryCount());
	}

	private GenerationAttempt newAttempt(GenerationJob job, int attemptNumber) {
		return newAttempt(job, attemptNumber, null);
	}

	private GenerationAttempt newAttempt(GenerationJob job, int attemptNumber, UUID legacyRenderStepId) {
		GenerationAttemptId id = new GenerationAttemptId(UUID.randomUUID());
		String providerName = providerRegistry != null
			? providerRegistry.selectedProviderKey().value()
			: provider.providerKey().value();
		Instant startedAt = clock.instant();
		Instant orchestrationDeadline = job.orchestrationDeadlineAt();
		if (orchestrationDeadline == null) {
			orchestrationDeadline = startedAt.plus(GenerationJob.ORCHESTRATION_DEADLINE);
		}
		Instant executionDeadline = min(
			orchestrationDeadline,
			startedAt.plus(AttemptRedeliveryPolicy.PROVIDER_EXECUTION_DEADLINE));
		Instant recoveryDeadline = min(
			orchestrationDeadline,
			startedAt.plus(SUBMISSION_RECOVERY_WINDOW));
		UUID renderStepId = renderPlan(job.id())
			.filter(plan -> job.currentStep() <= plan.steps().size())
			.map(plan -> plan.steps().get(job.currentStep() - 1).id())
			.orElse(legacyRenderStepId);
		GenerationAttempt created = new GenerationAttempt(
			id,
			job.id(),
			providerName,
			"default",
			null,
			attemptNumber,
			startedAt,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			SubmissionKey.forAttempt(id),
			null,
			false,
			null,
			null,
			renderStepId,
			0,
			null,
			executionDeadline,
			recoveryDeadline,
			QualityGate.BASELINE_POLICY_VERSION,
			null,
			null,
			null,
			null,
			null,
			null);
		return bindPricingIfNeeded(created, resolveProvider(created));
	}

	private static Instant min(Instant left, Instant right) {
		return left.isBefore(right) ? left : right;
	}

	private void discardLeftoverTemp(GenerationJob job) {
		for (GenerationAttempt attempt : generationStore.listAttempts(job.id())) {
			if (attempt.tempStorageKey() != null) {
				objectStore.delete(new ObjectRef(StorageDomain.TEMP, attempt.tempStorageKey().value()));
				continue;
			}
			for (ImageMediaType type : ImageMediaType.values()) {
				StorageKey key = StorageKeys.generationTempKey(
					job.userId(),
					job.id().value(),
					attempt.id().value(),
					type);
				objectStore.delete(new ObjectRef(StorageDomain.TEMP, key.value()));
			}
		}
	}

	private VtonProvider resolveProvider(GenerationAttempt attempt) {
		if (providerRegistry != null) {
			return providerRegistry.resolve(new ProviderKey(attempt.provider()));
		}
		return provider;
	}

	private Optional<ProviderExecutionId> persistedExecution(GenerationAttempt attempt) {
		if (attempt.providerJobId() == null || attempt.providerJobId().isBlank()) {
			return Optional.empty();
		}
		return Optional.of(new ProviderExecutionId(new ProviderKey(attempt.provider()), new ProviderJobId(attempt.providerJobId())));
	}

	private static GenerationAttempt mergePersistedTransmission(
		GenerationAttempt persisted,
		GenerationAttempt candidate
	) {
		return new GenerationAttempt(
			persisted.id(),
			persisted.generationJobId(),
			persisted.provider(),
			persisted.model(),
			persisted.providerJobId(),
			persisted.attemptNumber(),
			persisted.startedAt(),
			persisted.completedAt(),
			persisted.latencyMs(),
			candidate.creditsUsed(),
			candidate.estimatedCost(),
			candidate.currency(),
			candidate.failureCode(),
			candidate.technicalOutcome(),
			persisted.submissionKey(),
			persisted.submitOwnedAt(),
			persisted.submissionIndeterminate(),
			persisted.tempStorageKey(),
			persisted.providerOutputRef(),
			persisted.renderStepId(),
			persisted.transmissionCount(),
			persisted.providerAcceptedAt(),
			persisted.executionDeadlineAt(),
			persisted.submissionRecoveryDeadlineAt(),
			candidate.qualityPolicyVersion(),
			candidate.pricingCatalogVersion(),
			candidate.estimatedUnits(),
			candidate.unitPrice(),
			candidate.estimatedCostCeiling(),
			candidate.actualCost(),
			candidate.costStatus(),
			candidate.actualBilledUnits(),
			candidate.pricingObservedAt(),
			candidate.billingUnit());
	}

	private static GenerationAttempt withUsage(GenerationAttempt attempt, Optional<ProviderUsage> usage) {
		if (usage == null || usage.isEmpty()) {
			return attempt;
		}
		ProviderUsage mapped = usage.get();
		String currency = attempt.currency() != null ? attempt.currency() : mapped.currency();
		return new GenerationAttempt(
			attempt.id(),
			attempt.generationJobId(),
			attempt.provider(),
			attempt.model(),
			attempt.providerJobId(),
			attempt.attemptNumber(),
			attempt.startedAt(),
			attempt.completedAt(),
			attempt.latencyMs(),
			mapped.creditsUsed(),
			attempt.estimatedCost(),
			currency,
			attempt.failureCode(),
			attempt.technicalOutcome(),
			attempt.submissionKey(),
			attempt.submitOwnedAt(),
			attempt.submissionIndeterminate(),
			attempt.tempStorageKey(),
			attempt.providerOutputRef(),
			attempt.renderStepId(),
			attempt.transmissionCount(),
			attempt.providerAcceptedAt(),
			attempt.executionDeadlineAt(),
			attempt.submissionRecoveryDeadlineAt(),
			attempt.qualityPolicyVersion(),
			attempt.pricingCatalogVersion(),
			attempt.estimatedUnits(),
			attempt.unitPrice(),
			attempt.estimatedCostCeiling(),
			mapped.estimatedCost(),
			PricingSnapshot.COST_FINAL,
			mapped.creditsUsed(),
			attempt.pricingObservedAt(),
			attempt.billingUnit());
	}

	private static boolean ownedWithoutExecution(GenerationAttempt attempt) {
		return attempt.submitOwnedAt() != null
			&& (attempt.providerJobId() == null || attempt.providerJobId().isBlank())
			&& !attempt.submissionIndeterminate();
	}

	private static boolean unavailable(GenerationJob job) {
		return job.cancelledAt() != null || job.subjectDeletedAt() != null;
	}

	private boolean stillActive(GenerationJob observed) {
		GenerationJob latest = generationStore.findJob(observed.id()).orElse(observed);
		return !terminal(latest) && !unavailable(latest);
	}

	private static VtonMode mode(GenerationJob job) {
		String requested = job.requestedMode() == null || job.requestedMode().isBlank() ? "try-on" : job.requestedMode();
		return new VtonMode(requested);
	}


	private static ImageMediaType mediaType(String value) {
		if (value != null && value.toLowerCase(Locale.ROOT).contains("png")) {
			return ImageMediaType.PNG;
		}
		return ImageMediaType.JPEG;
	}

	private static ImageMediaType mediaTypeFromTemp(StorageKey key) {
		return key.value().endsWith(".png") ? ImageMediaType.PNG : ImageMediaType.JPEG;
	}

	private static int boundedReadCap() {
		return (int) Math.min(ImageMediaPolicy.MAX_BYTES, Integer.MAX_VALUE);
	}

	private static boolean invalidOutput(byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			return true;
		}
		if (bytes.length > ImageMediaPolicy.MAX_BYTES) {
			return true;
		}
		String text = new String(bytes, StandardCharsets.UTF_8).strip().toLowerCase(Locale.ROOT);
		if (text.startsWith("http://") || text.startsWith("https://")) {
			return true;
		}
		return !hasImageMagic(bytes);
	}

	private static boolean hasImageMagic(byte[] bytes) {
		if (bytes.length >= 3 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF) {
			return true;
		}
		return bytes.length >= 8
			&& bytes[0] == (byte) 0x89
			&& bytes[1] == 0x50
			&& bytes[2] == 0x4E
			&& bytes[3] == 0x47
			&& bytes[4] == 0x0D
			&& bytes[5] == 0x0A
			&& bytes[6] == 0x1A
			&& bytes[7] == 0x0A;
	}

	private GenerationAttempt bindPricingIfNeeded(GenerationAttempt attempt, VtonProvider active) {
		if (attempt.costStatus() != null || attempt.pricingCatalogVersion() != null) {
			return attempt;
		}
		String model = attempt.model() == null || attempt.model().isBlank() ? "default" : attempt.model();
		String currency = attempt.currency() == null || attempt.currency().isBlank() ? "USD" : attempt.currency();
		PricingSnapshot snapshot = new PricingSnapshot(
			active.providerKey().value(),
			model,
			"mvp-2026-08",
			clock.instant(),
			"generation",
			BigDecimal.ONE,
			new BigDecimal("0.10"),
			currency,
			new BigDecimal("0.10"));
		return attempt.bindPricing(snapshot);
	}

	private static GenerationAttempt withProviderJobId(GenerationAttempt attempt, String providerJobId) {
		if (attempt.hasDurableProviderJobId() || providerJobId == null || providerJobId.isBlank()) {
			return attempt;
		}
		return new GenerationAttempt(
			attempt.id(),
			attempt.generationJobId(),
			attempt.provider(),
			attempt.model(),
			providerJobId,
			attempt.attemptNumber(),
			attempt.startedAt(),
			attempt.completedAt(),
			attempt.latencyMs(),
			attempt.creditsUsed(),
			attempt.estimatedCost(),
			attempt.currency(),
			attempt.failureCode(),
			attempt.technicalOutcome(),
			attempt.submissionKey(),
			attempt.submitOwnedAt(),
			attempt.submissionIndeterminate(),
			attempt.tempStorageKey(),
			attempt.providerOutputRef(),
			attempt.renderStepId(),
			attempt.transmissionCount(),
			attempt.providerAcceptedAt(),
			attempt.executionDeadlineAt(),
			attempt.submissionRecoveryDeadlineAt(),
			attempt.qualityPolicyVersion(),
			attempt.pricingCatalogVersion(),
			attempt.estimatedUnits(),
			attempt.unitPrice(),
			attempt.estimatedCostCeiling(),
			attempt.actualCost(),
			attempt.costStatus(),
			attempt.actualBilledUnits(),
			attempt.pricingObservedAt(),
			attempt.billingUnit());
	}

	private boolean tryAcquireCapacity(GenerationAttempt attempt, GenerationJob job, VtonProvider active) {
		if (capacityLeaseStore == null) {
			return true;
		}
		Instant now = clock.instant();
		Instant expiresAt = attempt.executionDeadlineAt();
		if (expiresAt == null || !expiresAt.isAfter(now)) {
			expiresAt = job.orchestrationDeadlineAt();
		}
		if (expiresAt == null || !expiresAt.isAfter(now)) {
			expiresAt = now.plus(GenerationJob.ORCHESTRATION_DEADLINE);
		}
		try {
			return capacityLeaseStore.tryAcquire(
				active.providerKey().value(),
				attempt.id(),
				now,
				expiresAt,
				PROVIDER_CAPACITY_SLOTS);
		}
		catch (RuntimeException ex) {
			log.info("generation jobId={} attemptId={} code=CAPACITY_LEASE_UNAVAILABLE", job.id().value(), attempt.id().value());
			return false;
		}
	}

	private void releaseCapacity(GenerationAttempt attempt) {
		if (capacityLeaseStore == null) {
			return;
		}
		capacityLeaseStore.release(attempt.id(), clock.instant());
	}

	private void insertNotificationEvent(NotificationEvent event) {
		if (notificationEventStore == null) {
			return;
		}
		notificationEventStore.insert(event);
	}

	private void recordTransition(String from, String to) {
		if (metrics == null) {
			return;
		}
		metrics.recordTransition(from, to);
	}

	private void recordDeadlineExpired(GenerationJob job) {
		if (metrics == null) {
			return;
		}
		metrics.recordDeadlineExpired();
		metrics.recordTerminal(GenerationMetrics.TERMINAL_DEADLINE_EXPIRED);
		recordTransition(job.status(), GenerationJob.FAILED);
	}

	private void recordTerminalCategory(String category) {
		if (metrics != null) {
			metrics.recordTerminal(category);
		}
	}

	private void recordAmbiguity() {
		if (metrics != null) {
			metrics.recordAmbiguity();
		}
	}

	private void recordQuality(QualityResult quality) {
		if (metrics == null) {
			return;
		}
		metrics.recordQuality(quality.qualityPolicyVersion(), quality.decision(), quality.reasonCode());
	}

	private void recordEvaluatorUnavailable(String reasonCode) {
		if (metrics == null) {
			return;
		}
		metrics.recordEvaluatorUnavailable(reasonCode);
	}

	private void recordCost(GenerationJob job, GenerationAttempt attempt) {
		if (metrics == null) {
			return;
		}
		String costStatus = attempt.costStatus();
		BigDecimal cost = PricingSnapshot.COST_FINAL.equals(costStatus)
			? attempt.actualCost()
			: attempt.estimatedCost();
		if (costStatus == null || PricingSnapshot.COST_UNKNOWN.equals(costStatus) || cost == null) {
			costStatus = PricingSnapshot.COST_UNKNOWN;
			cost = null;
		}
		Optional<QualityResult> quality = generationStore.findQualityResult(attempt.id());
		String qualityOutcome = quality.map(result -> result.decision().name()).orElse(null);
		boolean qualityApprovedLook = "PASS".equals(qualityOutcome)
			&& job.currentStep() >= job.totalSteps();
		metrics.recordCostStatus(costStatus);
		metrics.recordAttempt(new GenerationCostFact(
			job.id(),
			attempt.id(),
			job.userId(),
			attempt.provider(),
			attempt.model(),
			attempt.technicalOutcome(),
			qualityOutcome,
			attempt.creditsUsed(),
			attempt.actualBilledUnits(),
			cost,
			attempt.currency(),
			costStatus,
			qualityApprovedLook,
			true,
			false));
	}

	private static <T> T require(T value, String name) {
		if (value == null) {
			throw new IllegalArgumentException(name + " is required");
		}
		return value;
	}
}
