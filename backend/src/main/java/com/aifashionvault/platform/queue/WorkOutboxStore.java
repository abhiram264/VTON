package com.aifashionvault.platform.queue;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface WorkOutboxStore {

	Optional<Claim> claimNext();

	void markSent(UUID outboxId);

	void markPublishFailed(UUID outboxId, String errorCategory);

	void insertSuccessor(UUID generationJobId, Instant notBefore);
	default boolean insertSuccessorIfActive(UUID generationJobId, Instant notBefore) {
		throw new UnsupportedOperationException("insertSuccessorIfActive");
	}


	record Claim(UUID outboxId, UUID generationJobId, int attemptCount) {

		public Claim {
			Objects.requireNonNull(outboxId, "outboxId");
			Objects.requireNonNull(generationJobId, "generationJobId");
			if (attemptCount < 0) {
				throw new IllegalArgumentException("attemptCount must be >= 0");
			}
		}
	}
}
