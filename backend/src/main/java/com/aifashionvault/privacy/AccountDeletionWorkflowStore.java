package com.aifashionvault.privacy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.aifashionvault.shared.UserId;

public interface AccountDeletionWorkflowStore {

	AccountDeletionStatus request(UserId userId, Instant now);

	Optional<Lease> acquire(UserId userId, Instant now, Instant expiresAt);

	AccountDeletionStatus advance(UUID workflowId, UUID leaseToken, AccountDeletionStep expectedStep,
			AccountDeletionStep nextStep, Instant now);

	AccountDeletionStatus fail(UUID workflowId, UUID leaseToken, AccountDeletionStep expectedStep, String safeErrorCode,
			Instant now);

	AccountDeletionStatus status(UserId userId);

	record Lease(AccountDeletionStatus status, UUID token) {
		public Lease {
			if (status == null || token == null) {
				throw new IllegalArgumentException("lease fields are required");
			}
		}
	}
}
