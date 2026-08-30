package com.aifashionvault.generation;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.aifashionvault.generation.vton.ProviderJobId;
import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

public interface GenerationStore {

	sealed interface CreateAcceptedResult {
		record Created(GenerationJob job) implements CreateAcceptedResult {
		}

		record Replayed(GenerationJob job) implements CreateAcceptedResult {
		}

		record FingerprintConflict(GenerationJob existing) implements CreateAcceptedResult {
		}

		record QuotaRejected(String reason) implements CreateAcceptedResult {
		}
	}

	sealed interface CreateSingleItemResult {
		record Created(GenerationJob job) implements CreateSingleItemResult {}
		record Replayed(GenerationJob job) implements CreateSingleItemResult {}
		record FingerprintConflict(GenerationJob existing) implements CreateSingleItemResult {}
		record ProductUploadAlreadyClaimed() implements CreateSingleItemResult {}
	}

	void insertJob(GenerationJob job);

	Optional<GenerationJob> findJob(GenerationJobId jobId);

	void insertAttempt(GenerationAttempt attempt);

	Optional<GenerationAttempt> findAttempt(GenerationAttemptId attemptId);

	List<GenerationAttempt> listAttempts(GenerationJobId jobId);

	boolean tryAcquireSubmitOwnership(GenerationAttemptId attemptId, Instant ownedAt);

	void recordProviderExecution(GenerationAttemptId attemptId, ProviderJobId providerJobId);

	void markSubmissionIndeterminate(GenerationAttemptId attemptId);

	void saveQualityResult(QualityResult qualityResult);

	void markCancelled(GenerationJobId jobId, Instant cancelledAt);

	void markSubjectDeleted(GenerationJobId jobId, Instant subjectDeletedAt);

	void completeAttempt(GenerationAttempt attempt);

	void markJobCompleted(GenerationJobId jobId, Instant completedAt);

	default void completeTerminal(GenerationAttempt attempt, Instant jobCompletedAt) {
		throw new UnsupportedOperationException("completeTerminal");
	}

	default boolean completeTerminalIfActive(GenerationAttempt attempt, Instant jobCompletedAt) {
		throw new UnsupportedOperationException("completeTerminalIfActive");
	}

	default void scheduleRetry(GenerationAttempt completedAttempt, GenerationAttempt nextAttempt) {
		throw new UnsupportedOperationException("scheduleRetry");
	}

	default boolean scheduleRetryIfActive(GenerationAttempt completedAttempt, GenerationAttempt nextAttempt) {
		throw new UnsupportedOperationException("scheduleRetryIfActive");
	}

	/**
	 * Atomically completes the predecessor attempt and schedules its active job
	 * in {@code RETRYING} with the supplied durable retry-not-before instant.
	 * This operation does not create a successor attempt.
	 *
	 * @return {@code true} when the version-fenced transition commits
	 */
	default boolean scheduleRetryIfActive(GenerationAttempt completedAttempt, Instant retryNotBeforeAt) {
		throw new UnsupportedOperationException("scheduleRetryIfActive");
	}

	/**
	 * Atomically completes the predecessor attempt and schedules its active job
	 * in {@code RETRYING} with the supplied durable retry-not-before instant,
	 * but only when the persisted job {@code statusVersion} equals the expected
	 * version fence. This operation does not create a successor attempt.
	 *
	 * @param expectedStatusVersion the status version observed before the retry decision
	 * @return {@code true} when the version-fenced transition commits
	 */
	default boolean scheduleRetryIfActive(
		GenerationAttempt completedAttempt,
		int expectedStatusVersion,
		Instant retryNotBeforeAt
	) {
		throw new UnsupportedOperationException("scheduleRetryIfActive");
	}

	/**
	 * Atomically claims a due retry at the expected job status version and
	 * creates exactly one successor attempt. A successful claim clears the
	 * retry-not-before value and leaves the job active.
	 *
	 * <p>Jobs admitted under a version 2 retry-policy snapshot additionally
	 * enforce the paid-slot reservation rule (R6/Q16): the proposed attempt is
	 * denied unless the paid-capable slots remaining after it still cover every
	 * mandatory render step that lacks its baseline execution. Version 1 rows
	 * keep their historical behavior unchanged (Q63).</p>
	 *
	 * @return {@code true} when this call creates the successor, or {@code false}
	 *         when the job is stale, no longer due, already claimed, the
	 *         persisted retry-policy row is unreadable or violates the settled
	 *         ceilings (R9/Q5), or the reservation rule denies the proposed attempt
	 */
	default boolean claimDueRetry(
		GenerationJobId jobId,
		int expectedStatusVersion,
		Instant now,
		GenerationAttempt nextAttempt
	) {
		throw new UnsupportedOperationException("claimDueRetry");
	}

	/**
	 * Resolves the fail-closed persisted retry-policy state for a job (R9/Q5).
	 * Missing, empty, malformed, unknown-version, and settled-ceiling-violating
	 * rows resolve to their exact {@link RetryPolicyResolution.State} without
	 * ever substituting current defaults for historical policy.
	 *
	 * @return the resolution, or {@code null} when the job does not exist
	 */
	default RetryPolicyResolution resolveRetryPolicy(GenerationJobId jobId) {
		throw new UnsupportedOperationException("resolveRetryPolicy");
	}

	/**
	 * Lists persisted active jobs whose durable retry-not-before value is due.
	 */
	default List<GenerationJob> listDueRetrying(Instant now) {
		throw new UnsupportedOperationException("listDueRetrying");
	}

	/**
	 * Atomically records one provider transmission for an attempt. The
	 * transmission count is incremented only while it is below the hard total
	 * of three and provider acceptance is not yet known.
	 *
	 * @return {@code true} when the transmission count is incremented
	 */
	default boolean tryRecordTransmission(GenerationAttemptId attemptId, Instant now) {
		throw new UnsupportedOperationException("tryRecordTransmission");
	}

	/**
	 * Atomically advances an active job's current step when its status version
	 * and completed step match the persisted progression.
	 *
	 * @return {@code true} when the fenced progression commits
	 */
	default boolean advanceStepIfActive(
		GenerationJobId jobId,
		int expectedStatusVersion,
		int completedStep
	) {
		throw new UnsupportedOperationException("advanceStepIfActive");
	}


	default boolean recordProviderExecutionIfActive(GenerationAttemptId attemptId, ProviderJobId providerJobId) {
		throw new UnsupportedOperationException("recordProviderExecutionIfActive");
	}

	default void markSubjectDeletedForAvatar(UserId userId, AvatarImageId avatarImageId, Instant when) {
		throw new UnsupportedOperationException("markSubjectDeletedForAvatar");
	}

	default void markAllSubjectDeleted(UserId userId, Instant when) {
		throw new UnsupportedOperationException("markAllSubjectDeleted");
	}

	default List<GenerationJob> listJobsByUser(UserId userId) {
		throw new UnsupportedOperationException("listJobsByUser");
	}

	default CreateAcceptedResult createAccepted(GenerationJob job) {
		throw new UnsupportedOperationException("createAccepted");
	}

	default CreateSingleItemResult createSingleItem(
		CreateSingleItemGenerationCommand command,
		FrozenSingleItemIntent intent,
		GenerationJob job
	) {
		throw new UnsupportedOperationException("createSingleItem");
	}

	default Optional<FrozenSingleItemIntent> findFrozenSingleItemIntent(GenerationJobId generationId) {
		throw new UnsupportedOperationException("findFrozenSingleItemIntent");
	}

	default Optional<UploadId> findProductSourceUploadId(GenerationJobId generationId, UserId ownerId) {
		throw new UnsupportedOperationException("findProductSourceUploadId");
	}

	default Optional<GenerationJob> findJobForUser(GenerationJobId jobId, UserId userId) {
		throw new UnsupportedOperationException("findJobForUser");
	}

	default Optional<RenderPlan> findRenderPlan(GenerationJobId jobId) {
		throw new UnsupportedOperationException("findRenderPlan");
	}

	default List<WorkOutbox> listWorkOutbox(GenerationJobId jobId) {
		throw new UnsupportedOperationException("listWorkOutbox");
	}

	default Optional<QuotaReservation> findQuotaReservation(GenerationJobId jobId) {
		throw new UnsupportedOperationException("findQuotaReservation");
	}

	default boolean cancelIfActive(GenerationJobId jobId, Instant cancelledAt) {
		throw new UnsupportedOperationException("cancelIfActive");
	}

	default boolean markSubmitted(GenerationJobId jobId) {
		throw new UnsupportedOperationException("markSubmitted");
	}

	default boolean markGenerating(GenerationJobId jobId) {
		throw new UnsupportedOperationException("markGenerating");
	}

	default boolean markPreflight(GenerationJobId jobId) {
		throw new UnsupportedOperationException("markPreflight");
	}

	/**
	 * Finds the persisted completed quality result of an attempt for
	 * redelivery replay. A missing row means the attempt was never evaluated
	 * and is never treated as {@code FAIL}.
	 *
	 * @return the persisted completed quality result, or empty when absent
	 */
	default Optional<QualityResult> findQualityResult(GenerationAttemptId attemptId) {
		throw new UnsupportedOperationException("findQualityResult");
	}

	/**
	 * Atomically advances an active job from {@code GENERATING} into
	 * {@code QUALITY_CHECK} when its persisted status version matches the
	 * expected fence. A replay that observes {@code QUALITY_CHECK} already
	 * persisted at the expected fence without a terminal marker is idempotent.
	 *
	 * @return {@code true} when the fenced transition commits or already holds
	 */
	default boolean markQualityCheckIfActive(GenerationJobId jobId, int expectedStatusVersion) {
		throw new UnsupportedOperationException("markQualityCheckIfActive");
	}

	default boolean failIfDeadlineExceeded(GenerationJobId jobId, Instant now) {
		throw new UnsupportedOperationException("failIfDeadlineExceeded");
	}
	default void deleteUserLinkedGenerationData(UserId userId) {
		throw new UnsupportedOperationException("deleteUserLinkedGenerationData");
	}
}
