package com.aifashionvault.platform.generation;

import java.time.Instant;

import com.aifashionvault.shared.GenerationAttemptId;

public interface GenerationCapacityLeaseStore {

	boolean tryAcquire(
		String providerKey,
		GenerationAttemptId attemptId,
		Instant now,
		Instant leaseExpiresAt,
		int maxSlots);

	void release(GenerationAttemptId attemptId, Instant releasedAt);
}
