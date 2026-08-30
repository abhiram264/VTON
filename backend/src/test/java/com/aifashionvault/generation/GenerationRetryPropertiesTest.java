package com.aifashionvault.generation;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.vton.AttemptRedeliveryPolicy;
import com.aifashionvault.generation.vton.SubmissionReplaySafety;
import com.aifashionvault.shared.ClientRequestId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.TerminalReason;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationRetryPropertiesTest {

	private static final int MAX_TOTAL_STEPS = 32;
	private static final int EXPECTED_EXTRA_ATTEMPTS = 2;
	private static final int EXPECTED_MAX_ATTEMPTS_PER_STEP = 3;
	private static final int EXPECTED_MAX_QUALITY_RETRIES_PER_STEP = 1;
	private static final int EXPECTED_MAX_TRANSMISSIONS = 3;
	private static final Instant MODEL_START = Instant.parse("2026-08-22T00:00:00Z");
	private static final GenerationJobId JOB_ID = new GenerationJobId(id("property-job"));
	private static final UserId USER_ID = new UserId(id("property-user"));
	private static final StudioSessionId SESSION_ID = new StudioSessionId(id("property-session"));
	private static final ClientRequestId REQUEST_ID = new ClientRequestId("property-request");
	private static final VaultItemId VAULT_ITEM_ID = new VaultItemId(id("property-item"));

	@Test
	void everyBoundedTwoTokenDistributionHonoursLogicalAndTransmissionCaps() {
		long totalDistributions = 0;

		for (int totalSteps = 1; totalSteps <= MAX_TOTAL_STEPS; totalSteps++) {
			GenerationRetryBudget budget = GenerationRetryBudget.forStepCount(totalSteps);
			assertThat(budget.maxLogicalAttempts())
				.as("totalSteps=%s max logical attempts", totalSteps)
				.isEqualTo(totalSteps + EXPECTED_EXTRA_ATTEMPTS);
			assertThat(budget.maxPaidAttempts())
				.as("totalSteps=%s max paid attempts", totalSteps)
				.isEqualTo(totalSteps + EXPECTED_EXTRA_ATTEMPTS);
			assertThat(budget.allowsLogicalAttempt(totalSteps + EXPECTED_EXTRA_ATTEMPTS - 1))
				.as("one attempt below the cap must be admitted for totalSteps=%s", totalSteps)
				.isTrue();
			assertThat(budget.allowsLogicalAttempt(totalSteps + EXPECTED_EXTRA_ATTEMPTS))
				.as("the cap must be exclusive for totalSteps=%s", totalSteps)
				.isFalse();
			assertThat(budget.allowsQualityRetry(0)).isTrue();
			assertThat(budget.allowsQualityRetry(1)).isFalse();
			assertThat(budget.allowsQualityTriggeredRetry(0, totalSteps + EXPECTED_EXTRA_ATTEMPTS - 1))
				.as("quality retry needs global admission for totalSteps=%s", totalSteps)
				.isTrue();
			assertThat(budget.allowsQualityTriggeredRetry(0, totalSteps + EXPECTED_EXTRA_ATTEMPTS))
				.as("quality retry cannot bypass the global cap for totalSteps=%s", totalSteps)
				.isFalse();
			assertThat(budget.consumesLogicalAttempt(true)).isFalse();
			assertThat(budget.consumesLogicalAttempt(false)).isTrue();

			List<TokenPlacement> placements = tokenPlacements(totalSteps);
			long distributionsForSteps = 0;
			for (TokenPlacement first : placements) {
				for (TokenPlacement second : placements) {
					AttemptLedger ledger = new AttemptLedger(totalSteps, budget);
					ledger.admit(first);
					ledger.admit(second);

					String caseDescription = "totalSteps=" + totalSteps
						+ ", first=" + first + ", second=" + second;
					assertThat(ledger.additionalUsage())
						.as(caseDescription + " additional logical usage")
						.isBetween(0, EXPECTED_EXTRA_ATTEMPTS);
					assertThat(ledger.logicalAttempts())
						.as(caseDescription + " total logical attempts")
						.isLessThanOrEqualTo(budget.maxLogicalAttempts());
					for (int attemptsOnStep : ledger.attemptsByStep()) {
						assertThat(attemptsOnStep)
							.as(caseDescription + " attempts on a step")
							.isLessThanOrEqualTo(EXPECTED_MAX_ATTEMPTS_PER_STEP);
					}
					for (int qualityRetriesOnStep : ledger.qualityRetriesByStep()) {
						assertThat(qualityRetriesOnStep)
							.as(caseDescription + " quality retries on a step")
							.isLessThanOrEqualTo(EXPECTED_MAX_QUALITY_RETRIES_PER_STEP);
					}
					ledger.exerciseReplayTransmissions(caseDescription);
					distributionsForSteps++;
				}
			}
			assertThat(distributionsForSteps)
				.as("all ordered placements of zero, one, or two tokens for totalSteps=%s", totalSteps)
				.isEqualTo((long) placements.size() * placements.size());
			totalDistributions += distributionsForSteps;
		}

		assertThat(totalDistributions).isGreaterThan(0);
	}

	@Test
	void retryFallbackQualityAndQueueRecoveryPreserveRenderPlanIdentity() {
		List<PlanEvent> sequence = List.of(
			PlanEvent.RETRY,
			PlanEvent.FALLBACK,
			PlanEvent.QUALITY,
			PlanEvent.QUEUE_RECOVERY,
			PlanEvent.RETRY,
			PlanEvent.QUALITY,
			PlanEvent.FALLBACK,
			PlanEvent.QUEUE_RECOVERY);

		for (int totalSteps = 1; totalSteps <= MAX_TOTAL_STEPS; totalSteps++) {
			PlanFixture fixture = deterministicPlan(totalSteps);
			RenderPlan plan = fixture.plan();
			PlanSnapshot expected = PlanSnapshot.capture(plan);

			// Both constructors must take defensive copies before recovery begins.
			fixture.sourceSteps().clear();
			for (List<RenderStep.Item> sourceItems : fixture.sourceItems()) {
				sourceItems.clear();
			}
			assertPlanSnapshot(plan, expected, "after source mutation for totalSteps=" + totalSteps);

			for (PlanEvent event : sequence) {
				final int stepCount = totalSteps;
				final PlanEvent appliedEvent = event;
				RenderPlan observed = plan;
				assertThatThrownBy(() -> observed.steps().add(
					new RenderStep(id("illegal-step-" + stepCount + "-" + appliedEvent), stepCount + 1, null, List.of())))
					.as("render plan steps must remain immutable during %s", appliedEvent)
					.isInstanceOf(UnsupportedOperationException.class);
				assertThatThrownBy(() -> observed.steps().get(0).items().add(
					new RenderStep.Item(id("illegal-item-" + stepCount + "-" + appliedEvent), 2, VAULT_ITEM_ID, "illegal", "illegal")))
					.as("render step items must remain immutable during %s", appliedEvent)
					.isInstanceOf(UnsupportedOperationException.class);

				plan = applyPlanEvent(plan, event);
				assertPlanSnapshot(plan, expected,
					"after " + event + " for totalSteps=" + totalSteps);
			}
		}
	}

	@Test
	void exactDeadlinesAndTerminalEventsAreMonotonicAndSuppressLateWork() {
		Instant deadline = MODEL_START.plus(GenerationJob.ORCHESTRATION_DEADLINE);
		assertThat(deadline).isEqualTo(MODEL_START.plusSeconds(30 * 60));

		for (int totalSteps = 1; totalSteps <= MAX_TOTAL_STEPS; totalSteps++) {
			RetryPolicySnapshot policy = RetryPolicySnapshot.forStepCount(totalSteps);
			assertThat(policy.orchestrationDeadline()).isEqualTo(GenerationJob.ORCHESTRATION_DEADLINE);
			assertThat(policy.maxPaidAttempts()).isEqualTo(totalSteps + EXPECTED_EXTRA_ATTEMPTS);
		}

		Instant exactDeadline = deadline;
		assertThat(AttemptRedeliveryPolicy.replaySafeRetransmissionAllowed(
			0,
			false,
			SubmissionReplaySafety.SAME_KEY_SAFE,
			exactDeadline,
			exactDeadline,
			exactDeadline.minusNanos(1),
			true)).isTrue();
		assertThat(AttemptRedeliveryPolicy.replaySafeRetransmissionAllowed(
			0,
			false,
			SubmissionReplaySafety.SAME_KEY_SAFE,
			exactDeadline,
			exactDeadline,
			exactDeadline,
			true)).isFalse();
		assertThat(AttemptRedeliveryPolicy.replaySafeRetransmissionAllowed(
			0,
			false,
			SubmissionReplaySafety.SAME_KEY_SAFE,
			exactDeadline,
			exactDeadline,
			exactDeadline.plusNanos(1),
			true)).isFalse();
		assertThat(AttemptRedeliveryPolicy.decide(
			true, Optional.empty(), false, SubmissionReplaySafety.SAME_KEY_SAFE))
			.isEqualTo(AttemptRedeliveryPolicy.Decision.STOP);

		TemporalModel deadlineModel = TemporalModel.start(3, MODEL_START);
		Instant retryAdmission = deadline.minusSeconds(2);
		assertThat(deadlineModel.apply(new TemporalEvent(TemporalEventKind.RETRY, retryAdmission))).isTrue();
		assertThat(deadlineModel.job().retryNotBeforeAt()).isEqualTo(deadline.minusSeconds(1));
		GenerationJob beforeDeadlineEvent = deadlineModel.job();
		assertThat(deadlineModel.apply(new TemporalEvent(TemporalEventKind.RETRY, exactDeadline))).isFalse();
		assertThat(deadlineModel.job()).isEqualTo(beforeDeadlineEvent);
		assertThat(deadlineModel.apply(new TemporalEvent(TemporalEventKind.RETRY, exactDeadline.plusNanos(1)))).isFalse();
		assertThat(deadlineModel.job()).isEqualTo(beforeDeadlineEvent);
		assertThat(deadlineModel.expire(exactDeadline)).isTrue();
		GenerationJob deadlineTerminal = deadlineModel.job();
		assertThat(deadlineTerminal.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(deadlineTerminal.completedAt()).isEqualTo(exactDeadline);
		assertThat(deadlineTerminal.updatedAt()).isEqualTo(exactDeadline);
		assertThat(deadlineTerminal.orchestrationDeadlineAt()).isEqualTo(exactDeadline);
		assertThat(deadlineTerminal.active()).isFalse();
		assertThat(deadlineModel.apply(new TemporalEvent(
			TemporalEventKind.COMPLETE, exactDeadline.plusSeconds(1)))).isFalse();
		assertThat(deadlineModel.job()).isEqualTo(deadlineTerminal);

		List<List<TemporalEvent>> terminalSequences = List.of(
			List.of(
				new TemporalEvent(TemporalEventKind.RETRY, MODEL_START.plusSeconds(1)),
				new TemporalEvent(TemporalEventKind.CANCEL, MODEL_START.plusSeconds(2)),
				new TemporalEvent(TemporalEventKind.DELETE, MODEL_START.plusSeconds(3)),
				new TemporalEvent(TemporalEventKind.COMPLETE, MODEL_START.plusSeconds(4)),
				new TemporalEvent(TemporalEventKind.RETRY, MODEL_START.plusSeconds(5))),
			List.of(
				new TemporalEvent(TemporalEventKind.RETRY, MODEL_START.plusSeconds(1)),
				new TemporalEvent(TemporalEventKind.DELETE, MODEL_START.plusSeconds(2)),
				new TemporalEvent(TemporalEventKind.CANCEL, MODEL_START.plusSeconds(3)),
				new TemporalEvent(TemporalEventKind.COMPLETE, MODEL_START.plusSeconds(4)),
				new TemporalEvent(TemporalEventKind.RETRY, MODEL_START.plusSeconds(5))),
			List.of(
				new TemporalEvent(TemporalEventKind.RETRY, MODEL_START.plusSeconds(1)),
				new TemporalEvent(TemporalEventKind.COMPLETE, MODEL_START.plusSeconds(2)),
				new TemporalEvent(TemporalEventKind.CANCEL, MODEL_START.plusSeconds(3)),
				new TemporalEvent(TemporalEventKind.DELETE, MODEL_START.plusSeconds(4)),
				new TemporalEvent(TemporalEventKind.RETRY, MODEL_START.plusSeconds(5))));

		for (List<TemporalEvent> sequence : terminalSequences) {
			TemporalModel model = TemporalModel.start(3, MODEL_START);
			assertThat(model.apply(sequence.get(0))).isTrue();
			assertThat(model.job().status()).isEqualTo(GenerationJob.RETRYING);
			int versionBeforeTerminal = model.job().statusVersion();
			Instant deadlineBeforeTerminal = model.job().orchestrationDeadlineAt();

			TemporalEvent terminalEvent = sequence.get(1);
			assertThat(model.apply(terminalEvent)).isTrue();
			GenerationJob terminal = model.job();
			assertThat(terminal.statusVersion()).isEqualTo(versionBeforeTerminal + 1);
			assertThat(terminal.orchestrationDeadlineAt()).isEqualTo(deadlineBeforeTerminal);
			assertThat(terminal.updatedAt()).isEqualTo(terminalEvent.at());
			assertThat(terminal.completedAt()).isEqualTo(terminalEvent.at());
			assertThat(terminal.retryNotBeforeAt()).isNull();
			assertThat(terminal.active()).isFalse();
			assertTerminalFields(terminal, terminalEvent.kind(), terminalEvent.at());

			for (TemporalEvent lateEvent : sequence.subList(2, sequence.size())) {
				assertThat(lateEvent.at()).isAfter(terminal.updatedAt());
				GenerationJob beforeLateEvent = model.job();
				assertThat(model.apply(lateEvent))
					.as("late %s must be suppressed after %s", lateEvent.kind(), terminalEvent.kind())
					.isFalse();
				assertThat(model.job()).isEqualTo(beforeLateEvent);
				assertThat(model.job().status()).isEqualTo(terminal.status());
				assertThat(model.job().statusVersion()).isEqualTo(terminal.statusVersion());
				assertThat(model.job().orchestrationDeadlineAt()).isEqualTo(deadlineBeforeTerminal);
			}
		}
	}

	@Test
	void duplicateDueRetryClaimsCreateAtMostOneSuccessorPerPredecessor() {
		Instant retryDueAt = MODEL_START.plusSeconds(10);
		UUID predecessor = id("predecessor");
		UUID firstSuccessor = id("successor-first");
		RetryClaimModel model = RetryClaimModel.start(3, MODEL_START, predecessor, retryDueAt, 17);

		List<RetryClaim> sequence = List.of(
			new RetryClaim("worker-before-due", predecessor, 17, retryDueAt.minusNanos(1), id("successor-before-due")),
			new RetryClaim("worker-a", predecessor, 17, retryDueAt, firstSuccessor),
			new RetryClaim("worker-b", predecessor, 17, retryDueAt, id("successor-second")),
			new RetryClaim("worker-a-replay", predecessor, 18, retryDueAt.plusSeconds(1), id("successor-replay")),
			new RetryClaim("worker-c-stale", id("other-predecessor"), 18, retryDueAt.plusSeconds(1), id("successor-other")));

		assertThat(model.claim(sequence.get(0))).isFalse();
		assertThat(model.claim(sequence.get(1))).isTrue();
		assertThat(model.claim(sequence.get(2))).isFalse();
		assertThat(model.claim(sequence.get(3))).isFalse();
		assertThat(model.claim(sequence.get(4))).isFalse();

		assertThat(model.successorsByPredecessor()).hasSize(1);
		assertThat(model.successorsByPredecessor()).containsEntry(predecessor, firstSuccessor);
		assertThat(model.job().statusVersion()).isEqualTo(18);
		assertThat(model.job().retryNotBeforeAt()).isNull();
		assertThat(model.job().active()).isTrue();
	}

	private static List<TokenPlacement> tokenPlacements(int totalSteps) {
		List<TokenPlacement> placements = new ArrayList<>();
		placements.add(TokenPlacement.unused());
		for (int stepIndex = 1; stepIndex <= totalSteps; stepIndex++) {
			for (AttemptOutcome outcome : AttemptOutcome.values()) {
				placements.add(new TokenPlacement(stepIndex, outcome));
			}
		}
		return List.copyOf(placements);
	}

	private static RenderPlan applyPlanEvent(RenderPlan plan, PlanEvent event) {
		return switch (event) {
			case RETRY, FALLBACK, QUALITY, QUEUE_RECOVERY -> new RenderPlan(plan.version(), plan.steps());
		};
	}

	private static PlanFixture deterministicPlan(int totalSteps) {
		List<RenderStep> sourceSteps = new ArrayList<>();
		List<List<RenderStep.Item>> sourceItems = new ArrayList<>();
		for (int stepIndex = 1; stepIndex <= totalSteps; stepIndex++) {
			List<RenderStep.Item> items = new ArrayList<>();
			items.add(new RenderStep.Item(
				id("plan-item-" + totalSteps + "-" + stepIndex),
				1,
				VAULT_ITEM_ID,
				"category-" + stepIndex,
				"slot-" + stepIndex));
			sourceItems.add(items);
			sourceSteps.add(new RenderStep(
				id("plan-step-" + totalSteps + "-" + stepIndex),
				stepIndex,
				"capability-" + stepIndex,
				items));
		}
		RenderPlan plan = new RenderPlan(1, sourceSteps);
		return new PlanFixture(plan, sourceSteps, sourceItems);
	}

	private static void assertPlanSnapshot(RenderPlan actual, PlanSnapshot expected, String description) {
		assertThat(actual.version()).as(description + " version").isEqualTo(expected.version());
		assertThat(actual.steps()).as(description + " total steps").hasSize(expected.totalSteps());
		assertThat(actual.steps()).extracting(RenderStep::id)
			.as(description + " step identities")
			.containsExactlyElementsOf(expected.stepIds());
		for (int stepIndex = 0; stepIndex < expected.totalSteps(); stepIndex++) {
			assertThat(actual.steps().get(stepIndex).stepIndex())
				.as(description + " step index")
				.isEqualTo(stepIndex + 1);
			assertThat(actual.steps().get(stepIndex).items()).extracting(RenderStep.Item::id)
				.as(description + " item identities")
				.containsExactlyElementsOf(expected.itemIds().get(stepIndex));
		}
	}

	private static void assertTerminalFields(GenerationJob job, TemporalEventKind kind, Instant at) {
		switch (kind) {
			case CANCEL -> {
				assertThat(job.status()).isEqualTo(GenerationJob.FAILED);
				assertThat(job.terminalReason()).isEqualTo(GenerationJob.CANCELLED_BY_USER);
				assertThat(job.cancelledAt()).isEqualTo(at);
				assertThat(job.subjectDeletedAt()).isNull();
			}
			case DELETE -> {
				assertThat(job.status()).isEqualTo(GenerationJob.FAILED);
				assertThat(job.terminalReason()).isEqualTo(GenerationJob.SUBJECT_DELETED);
				assertThat(job.cancelledAt()).isNull();
				assertThat(job.subjectDeletedAt()).isEqualTo(at);
			}
			case COMPLETE -> {
				assertThat(job.status()).isEqualTo(GenerationJob.COMPLETED);
				assertThat(job.terminalReason()).isNull();
				assertThat(job.cancelledAt()).isNull();
				assertThat(job.subjectDeletedAt()).isNull();
			}
			case RETRY -> throw new AssertionError("retry is not a terminal event");
		}
	}

	private static GenerationJob createJob(
		String status,
		int totalSteps,
		Instant createdAt,
		Instant completedAt,
		Instant cancelledAt,
		Instant subjectDeletedAt,
		int statusVersion,
		Instant updatedAt,
		String terminalReason,
		Instant retryNotBeforeAt) {
		return new GenerationJob(
			JOB_ID,
			USER_ID,
			SESSION_ID,
			status,
			"try-on",
			REQUEST_ID,
			createdAt,
			completedAt,
			null,
			List.of(VAULT_ITEM_ID),
			cancelledAt,
			subjectDeletedAt,
			"property-fingerprint",
			1,
			statusVersion,
			updatedAt,
			terminalReason,
			1,
			totalSteps,
			1,
			createdAt.plus(GenerationJob.ORCHESTRATION_DEADLINE),
			retryNotBeforeAt,
			RetryPolicySnapshot.forStepCount(totalSteps));
	}

	private static UUID id(String name) {
		return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
	}

	private enum AttemptOutcome {
		TECHNICAL,
		FALLBACK,
		QUALITY,
		QUEUE_RECOVERY
	}

	private record TokenPlacement(int stepIndex, AttemptOutcome outcome) {
		private static TokenPlacement unused() {
			return new TokenPlacement(0, null);
		}

		private boolean used() {
			return stepIndex > 0;
		}
	}

	private static final class AttemptLedger {
		private final GenerationRetryBudget budget;
		private final int[] attemptsByStep;
		private final int[] qualityRetriesByStep;
		private int logicalAttempts;
		private int additionalUsage;

		private AttemptLedger(int totalSteps, GenerationRetryBudget budget) {
			this.budget = budget;
			this.attemptsByStep = new int[totalSteps];
			this.qualityRetriesByStep = new int[totalSteps];
			Arrays.fill(this.attemptsByStep, 1);
			this.logicalAttempts = totalSteps;
		}

		private void admit(TokenPlacement placement) {
			if (!placement.used()) {
				return;
			}
			int step = placement.stepIndex() - 1;
			boolean admitted;
			if (placement.outcome() == AttemptOutcome.QUALITY) {
				admitted = budget.allowsQualityTriggeredRetry(
					qualityRetriesByStep[step], logicalAttempts);
				if (qualityRetriesByStep[step] == 0) {
					assertThat(admitted).as("first quality retry should be admitted").isTrue();
				} else {
					assertThat(admitted).as("second quality retry should be rejected").isFalse();
				}
				if (admitted) {
					qualityRetriesByStep[step]++;
				}
			} else {
				admitted = budget.allowsLogicalAttempt(logicalAttempts);
			}
			if (admitted) {
				attemptsByStep[step]++;
				logicalAttempts++;
				additionalUsage++;
			}
		}

		private void exerciseReplayTransmissions(String description) {
			int beforeReplay = logicalAttempts;
			int transmissionCount = 1;
			while (budget.allowsReplaySafeRetransmit(logicalAttempts, transmissionCount)) {
				assertThat(transmissionCount)
					.as(description + " replay transmission count before increment")
					.isLessThanOrEqualTo(EXPECTED_MAX_TRANSMISSIONS - 1);
				assertThat(budget.consumesLogicalAttempt(true)).isFalse();
				transmissionCount++;
			}
			assertThat(transmissionCount)
				.as(description + " transmissions per logical attempt")
				.isEqualTo(EXPECTED_MAX_TRANSMISSIONS);
			assertThat(budget.allowsReplaySafeRetransmit(logicalAttempts, transmissionCount))
				.as(description + " fourth transmission must be rejected")
				.isFalse();
			assertThat(logicalAttempts)
				.as(description + " replay must not consume a logical attempt")
				.isEqualTo(beforeReplay);
			assertThat(budget.consumesLogicalAttempt(false)).isTrue();
		}

		private int[] attemptsByStep() {
			return attemptsByStep;
		}

		private int[] qualityRetriesByStep() {
			return qualityRetriesByStep;
		}

		private int logicalAttempts() {
			return logicalAttempts;
		}

		private int additionalUsage() {
			return additionalUsage;
		}
	}

	private enum PlanEvent {
		RETRY,
		FALLBACK,
		QUALITY,
		QUEUE_RECOVERY
	}

	private record PlanFixture(RenderPlan plan, List<RenderStep> sourceSteps, List<List<RenderStep.Item>> sourceItems) {
	}

	private record PlanSnapshot(int version, int totalSteps, List<UUID> stepIds, List<List<UUID>> itemIds) {
		private static PlanSnapshot capture(RenderPlan plan) {
			List<UUID> stepIds = plan.steps().stream().map(RenderStep::id).toList();
			List<List<UUID>> itemIds = plan.steps().stream()
				.map(step -> step.items().stream().map(RenderStep.Item::id).toList())
				.toList();
			return new PlanSnapshot(plan.version(), plan.steps().size(), stepIds, itemIds);
		}
	}

	private enum TemporalEventKind {
		RETRY,
		CANCEL,
		DELETE,
		COMPLETE
	}

	private record TemporalEvent(TemporalEventKind kind, Instant at) {
	}

	private static final class TemporalModel {
		private GenerationJob job;

		private TemporalModel(GenerationJob job) {
			this.job = job;
		}

		private static TemporalModel start(int totalSteps, Instant createdAt) {
			return new TemporalModel(createJob(
				GenerationJob.QUEUED,
				totalSteps,
				createdAt,
				null,
				null,
				null,
				0,
				createdAt,
				null,
				null));
		}

		private boolean apply(TemporalEvent event) {
			if (!job.active()) {
				return false;
			}
			return switch (event.kind()) {
				case RETRY -> scheduleRetry(event.at());
				case CANCEL -> terminal(event.kind(), event.at());
				case DELETE -> terminal(event.kind(), event.at());
				case COMPLETE -> terminal(event.kind(), event.at());
			};
		}

		private boolean scheduleRetry(Instant now) {
			Instant deadline = job.orchestrationDeadlineAt();
			Instant retryNotBeforeAt = now.plusSeconds(1);
			if (!now.isBefore(deadline) || !retryNotBeforeAt.isBefore(deadline)) {
				return false;
			}
			job = copyJob(
				GenerationJob.RETRYING,
				null,
				null,
				null,
				job.statusVersion() + 1,
				now,
				null,
				retryNotBeforeAt);
			return true;
		}

		private boolean expire(Instant now) {
			if (!job.active() || now.isBefore(job.orchestrationDeadlineAt())) {
				return false;
			}
			job = copyJob(
				GenerationJob.FAILED,
				now,
				null,
				null,
				job.statusVersion() + 1,
				now,
				TerminalReason.ORCHESTRATION_DEADLINE_EXCEEDED.name(),
				null);
			return true;
		}

		private boolean terminal(TemporalEventKind kind, Instant at) {
			if (!job.active()) {
				return false;
			}
			if (kind == TemporalEventKind.COMPLETE) {
				job = copyJob(
					GenerationJob.COMPLETED,
					at,
					null,
					null,
					job.statusVersion() + 1,
					at,
					null,
					null);
			} else if (kind == TemporalEventKind.CANCEL) {
				job = copyJob(
					GenerationJob.FAILED,
					at,
					at,
					null,
					job.statusVersion() + 1,
					at,
					GenerationJob.CANCELLED_BY_USER,
					null);
			} else if (kind == TemporalEventKind.DELETE) {
				job = copyJob(
					GenerationJob.FAILED,
					at,
					null,
					at,
					job.statusVersion() + 1,
					at,
					GenerationJob.SUBJECT_DELETED,
					null);
			} else {
				throw new AssertionError("retry is not terminal");
			}
			return true;
		}

		private GenerationJob copyJob(
			String status,
			Instant completedAt,
			Instant cancelledAt,
			Instant subjectDeletedAt,
			int statusVersion,
			Instant updatedAt,
			String terminalReason,
			Instant retryNotBeforeAt) {
			return createJob(
				status,
				job.totalSteps(),
				job.createdAt(),
				completedAt,
				cancelledAt,
				subjectDeletedAt,
				statusVersion,
				updatedAt,
				terminalReason,
				retryNotBeforeAt);
		}

		private GenerationJob job() {
			return job;
		}
	}

	private record RetryClaim(String worker, UUID predecessor, int expectedVersion, Instant now, UUID successor) {
	}

	private static final class RetryClaimModel {
		private final UUID predecessor;
		private final Map<UUID, UUID> successorsByPredecessor = new HashMap<>();
		private GenerationJob job;

		private RetryClaimModel(GenerationJob job, UUID predecessor) {
			this.job = job;
			this.predecessor = predecessor;
		}

		private static RetryClaimModel start(
			int totalSteps,
			Instant createdAt,
			UUID predecessor,
			Instant retryDueAt,
			int statusVersion) {
			GenerationJob job = createJob(
				GenerationJob.RETRYING,
				totalSteps,
				createdAt,
				null,
				null,
				null,
				statusVersion,
				createdAt,
				null,
				retryDueAt);
			return new RetryClaimModel(job, predecessor);
		}

		private boolean claim(RetryClaim claim) {
			if (!predecessor.equals(claim.predecessor())
				|| !job.active()
				|| !GenerationJob.RETRYING.equals(job.status())
				|| claim.expectedVersion() != job.statusVersion()
				|| job.retryNotBeforeAt() == null
				|| claim.now().isBefore(job.retryNotBeforeAt())
				|| successorsByPredecessor.containsKey(claim.predecessor())) {
				return false;
			}
			successorsByPredecessor.put(claim.predecessor(), claim.successor());
			job = createJob(
				GenerationJob.PREFLIGHT,
				job.totalSteps(),
				job.createdAt(),
				null,
				null,
				null,
				job.statusVersion() + 1,
				claim.now(),
				null,
				null);
			return true;
		}

		private Map<UUID, UUID> successorsByPredecessor() {
			return Map.copyOf(successorsByPredecessor);
		}

		private GenerationJob job() {
			return job;
		}
	}
}
