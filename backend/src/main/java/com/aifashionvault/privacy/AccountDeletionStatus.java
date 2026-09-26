package com.aifashionvault.privacy;

import java.time.Instant;
import java.util.UUID;

import com.aifashionvault.shared.UserId;

public record AccountDeletionStatus(UUID workflowId, UserId userId, AccountDeletionStep nextStep,
		AccountDeletionState state, int attemptCount, Instant requestedAt, Instant updatedAt, Instant completedAt,
		String lastErrorCode) {

	public AccountDeletionStatus {
		if (workflowId == null || userId == null || nextStep == null || state == null || requestedAt == null
				|| updatedAt == null) {
			throw new IllegalArgumentException("workflow status fields are required");
		}
		if (attemptCount < 0) {
			throw new IllegalArgumentException("attemptCount must be non-negative");
		}
		if ((state == AccountDeletionState.COMPLETE) != (nextStep == AccountDeletionStep.COMPLETE)
				|| (state == AccountDeletionState.COMPLETE) != (completedAt != null)) {
			throw new IllegalArgumentException("completion fields are inconsistent");
		}
		if (updatedAt.isBefore(requestedAt)
				|| completedAt != null && completedAt.isBefore(updatedAt)) {
			throw new IllegalArgumentException("deletion timestamps are out of order");
		}
	}
}
