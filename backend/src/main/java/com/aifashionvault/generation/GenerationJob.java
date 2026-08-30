package com.aifashionvault.generation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.ClientRequestId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

/**
 * Canonical durable generation workflow state (ADR-004).
 *
 * <p>The trailing {@code retryOfJobId} records optional manual Try Again
 * lineage: a deliberate new job created from a terminal predecessor keeps the
 * predecessor immutable (Q62). It defaults to empty for every compatibility
 * overload.</p>
 */
public record GenerationJob(
	GenerationJobId id,
	UserId userId,
	StudioSessionId studioSessionId,
	String status,
	String requestedMode,
	ClientRequestId clientRequestId,
	Instant createdAt,
	Instant completedAt,
	AvatarImageId avatarImageId,
	List<VaultItemId> productVaultItemIds,
	Instant cancelledAt,
	Instant subjectDeletedAt,
	String requestFingerprint,
	Integer avatarRevision,
	int statusVersion,
	Instant updatedAt,
	String terminalReason,
	int currentStep,
	int totalSteps,
	int renderPlanVersion,
	Instant orchestrationDeadlineAt,
	Instant retryNotBeforeAt,
	RetryPolicySnapshot retryPolicySnapshot,
	Optional<GenerationJobId> retryOfJobId) {

	public static final String QUEUED = "QUEUED";
	public static final String PREFLIGHT = "PREFLIGHT";
	public static final String SUBMITTED = "SUBMITTED";
	public static final String GENERATING = "GENERATING";
	public static final String QUALITY_CHECK = "QUALITY_CHECK";
	public static final String RETRYING = "RETRYING";
	public static final String COMPLETED = "COMPLETED";
	public static final String FAILED = "FAILED";
	public static final String CANCELLED_BY_USER = "CANCELLED_BY_USER";
	public static final String SUBJECT_DELETED = "SUBJECT_DELETED";
	public static final Duration ORCHESTRATION_DEADLINE = Duration.ofMinutes(30);
	public static final Set<String> LIFECYCLE_STATUSES = Set.of(
		QUEUED, PREFLIGHT, SUBMITTED, GENERATING, QUALITY_CHECK, RETRYING, COMPLETED, FAILED);
	public static final Set<String> ACTIVE_STATUSES = Set.of(
		QUEUED, PREFLIGHT, SUBMITTED, GENERATING, QUALITY_CHECK, RETRYING);

	public GenerationJob(
		GenerationJobId id,
		UserId userId,
		StudioSessionId studioSessionId,
		String status,
		String requestedMode,
		ClientRequestId clientRequestId,
		Instant createdAt,
		Instant completedAt,
		AvatarImageId avatarImageId,
		List<VaultItemId> productVaultItemIds,
		Instant cancelledAt,
		Instant subjectDeletedAt) {
		this(
			id,
			userId,
			studioSessionId,
			status,
			requestedMode,
			clientRequestId,
			createdAt,
			defaultCompletedAt(status, completedAt, cancelledAt, subjectDeletedAt, createdAt),
			avatarImageId,
			productVaultItemIds,
			cancelledAt,
			subjectDeletedAt,
			null,
			null,
			0,
			createdAt,
			defaultTerminalReason(cancelledAt, subjectDeletedAt),
			1,
			productVaultItemIds == null || productVaultItemIds.isEmpty() ? 1 : productVaultItemIds.size(),
			1,
			createdAt == null ? null : createdAt.plus(ORCHESTRATION_DEADLINE),
			null,
			null,
			Optional.empty());
	}

	/**
	 * Compatibility overload for the pre-retry persisted job shape.
	 *
	 * <p>Legacy rows and callers do not have durable retry state, so both new
	 * fields intentionally default to {@code null}.</p>
	 */
	public GenerationJob(
		GenerationJobId id,
		UserId userId,
		StudioSessionId studioSessionId,
		String status,
		String requestedMode,
		ClientRequestId clientRequestId,
		Instant createdAt,
		Instant completedAt,
		AvatarImageId avatarImageId,
		List<VaultItemId> productVaultItemIds,
		Instant cancelledAt,
		Instant subjectDeletedAt,
		String requestFingerprint,
		Integer avatarRevision,
		int statusVersion,
		Instant updatedAt,
		String terminalReason,
		int currentStep,
		int totalSteps,
		int renderPlanVersion,
		Instant orchestrationDeadlineAt) {
		this(
			id,
			userId,
			studioSessionId,
			status,
			requestedMode,
			clientRequestId,
			createdAt,
			completedAt,
			avatarImageId,
			productVaultItemIds,
			cancelledAt,
			subjectDeletedAt,
			requestFingerprint,
			avatarRevision,
			statusVersion,
			updatedAt,
			terminalReason,
			currentStep,
			totalSteps,
			renderPlanVersion,
			orchestrationDeadlineAt,
			null,
			null,
			Optional.empty());
	}

	/**
	 * Compatibility overload for the pre-lineage persisted job shape. Manual
	 * Try Again lineage (Q62) defaults to empty.
	 */
	public GenerationJob(
		GenerationJobId id,
		UserId userId,
		StudioSessionId studioSessionId,
		String status,
		String requestedMode,
		ClientRequestId clientRequestId,
		Instant createdAt,
		Instant completedAt,
		AvatarImageId avatarImageId,
		List<VaultItemId> productVaultItemIds,
		Instant cancelledAt,
		Instant subjectDeletedAt,
		String requestFingerprint,
		Integer avatarRevision,
		int statusVersion,
		Instant updatedAt,
		String terminalReason,
		int currentStep,
		int totalSteps,
		int renderPlanVersion,
		Instant orchestrationDeadlineAt,
		Instant retryNotBeforeAt,
		RetryPolicySnapshot retryPolicySnapshot) {
		this(
			id,
			userId,
			studioSessionId,
			status,
			requestedMode,
			clientRequestId,
			createdAt,
			completedAt,
			avatarImageId,
			productVaultItemIds,
			cancelledAt,
			subjectDeletedAt,
			requestFingerprint,
			avatarRevision,
			statusVersion,
			updatedAt,
			terminalReason,
			currentStep,
			totalSteps,
			renderPlanVersion,
			orchestrationDeadlineAt,
			retryNotBeforeAt,
			retryPolicySnapshot,
			Optional.empty());
	}

	public GenerationJob {
		retryOfJobId = retryOfJobId == null ? Optional.empty() : retryOfJobId;
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (status == null || status.isBlank()) {
			throw new IllegalArgumentException("status is required");
		}
		if (!LIFECYCLE_STATUSES.contains(status)) {
			throw new IllegalArgumentException("status must be a canonical lifecycle name");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
		if (productVaultItemIds == null) {
			throw new IllegalArgumentException("productVaultItemIds is required");
		}
		for (VaultItemId productVaultItemId : productVaultItemIds) {
			if (productVaultItemId == null) {
				throw new IllegalArgumentException("productVaultItemIds must not contain null");
			}
		}
		productVaultItemIds = List.copyOf(productVaultItemIds);
		if (updatedAt == null) {
			throw new IllegalArgumentException("updatedAt is required");
		}
		if (orchestrationDeadlineAt == null) {
			throw new IllegalArgumentException("orchestrationDeadlineAt is required");
		}
		if (statusVersion < 0) {
			throw new IllegalArgumentException("statusVersion must be >= 0");
		}
		if (renderPlanVersion < 1) {
			throw new IllegalArgumentException("renderPlanVersion must be >= 1");
		}
		if (totalSteps < 1) {
			throw new IllegalArgumentException("totalSteps must be >= 1");
		}
		if (currentStep < 1 || currentStep > totalSteps) {
			throw new IllegalArgumentException("currentStep must be between 1 and totalSteps");
		}
		boolean terminal = COMPLETED.equals(status) || FAILED.equals(status);
		if (terminal && completedAt == null) {
			throw new IllegalArgumentException("completedAt is required for terminal status");
		}
		if (!terminal && completedAt != null) {
			throw new IllegalArgumentException("completedAt must be null for active status");
		}
		if (cancelledAt != null) {
			if (!FAILED.equals(status)) {
				throw new IllegalArgumentException("cancelledAt requires status FAILED");
			}
			if (subjectDeletedAt == null && !CANCELLED_BY_USER.equals(terminalReason)) {
				throw new IllegalArgumentException("cancelledAt requires terminalReason CANCELLED_BY_USER");
			}
		}
		if (subjectDeletedAt != null) {
			if (!FAILED.equals(status)) {
				throw new IllegalArgumentException("subjectDeletedAt requires status FAILED");
			}
			if (cancelledAt == null && !SUBJECT_DELETED.equals(terminalReason)) {
				throw new IllegalArgumentException("subjectDeletedAt requires terminalReason SUBJECT_DELETED");
			}
		}
		if (FAILED.equals(status) && (terminalReason == null || terminalReason.isBlank())) {
			throw new IllegalArgumentException("terminalReason is required for FAILED");
		}
		if (retryNotBeforeAt != null && !RETRYING.equals(status)) {
			throw new IllegalArgumentException("retryNotBeforeAt requires status RETRYING");
		}
		if (retryNotBeforeAt != null && !retryNotBeforeAt.isBefore(orchestrationDeadlineAt)) {
			throw new IllegalArgumentException("retryNotBeforeAt must be before the orchestration deadline");
		}
		if (retryPolicySnapshot != null) {
			if (retryPolicySnapshot.maxPaidAttempts() < totalSteps) {
				throw new IllegalArgumentException("retryPolicySnapshot must allow one baseline attempt per step");
			}
			long maxAllowedPaidAttempts = (long) totalSteps + RetryPolicySnapshot.ADDITIONAL_LOGICAL_ATTEMPTS;
			if (retryPolicySnapshot.maxPaidAttempts() > maxAllowedPaidAttempts) {
				throw new IllegalArgumentException("retryPolicySnapshot may not raise the total attempt cap");
			}
			if (retryPolicySnapshot.orchestrationDeadline().compareTo(ORCHESTRATION_DEADLINE) > 0) {
				throw new IllegalArgumentException("retryPolicySnapshot may not exceed the orchestration deadline");
			}
		}
		if (cancelledAt != null && subjectDeletedAt != null
			&& !CANCELLED_BY_USER.equals(terminalReason)
			&& !SUBJECT_DELETED.equals(terminalReason)) {
			throw new IllegalArgumentException("terminalReason must remain a cancellation or deletion reason");
		}
	}

	public boolean active() {
		return ACTIVE_STATUSES.contains(status);
	}

	public static GenerationJob rememberIntent(Map<ClientRequestId, GenerationJob> intents, GenerationJob job) {
		if (job == null) {
			throw new IllegalArgumentException("job is required");
		}
		ClientRequestId requestId = job.clientRequestId();
		if (requestId == null) {
			return job;
		}
		if (intents == null) {
			throw new IllegalArgumentException("intents is required");
		}
		GenerationJob existing = intents.putIfAbsent(requestId, job);
		return existing != null ? existing : job;
	}

	private static Instant defaultCompletedAt(
		String status,
		Instant completedAt,
		Instant cancelledAt,
		Instant subjectDeletedAt,
		Instant createdAt) {
		if (completedAt != null) {
			return completedAt;
		}
		if (FAILED.equals(status) || cancelledAt != null || subjectDeletedAt != null) {
			if (cancelledAt != null) {
				return cancelledAt;
			}
			if (subjectDeletedAt != null) {
				return subjectDeletedAt;
			}
			return createdAt;
		}
		if (COMPLETED.equals(status)) {
			return createdAt;
		}
		return null;
	}

	private static String defaultTerminalReason(Instant cancelledAt, Instant subjectDeletedAt) {
		if (cancelledAt != null && subjectDeletedAt == null) {
			return CANCELLED_BY_USER;
		}
		if (subjectDeletedAt != null && cancelledAt == null) {
			return SUBJECT_DELETED;
		}
		if (cancelledAt != null) {
			return CANCELLED_BY_USER;
		}
		return null;
	}
}
