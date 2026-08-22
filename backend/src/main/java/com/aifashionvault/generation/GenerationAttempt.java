package com.aifashionvault.generation;

import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.GenerationJobId;

public record GenerationAttempt(GenerationAttemptId id, GenerationJobId generationJobId, String provider, String model, String providerJobId, int attemptNumber, java.time.Instant startedAt, java.time.Instant completedAt, Integer latencyMs, java.math.BigDecimal creditsUsed, java.math.BigDecimal estimatedCost, String currency, String failureCode, String technicalOutcome) {

	public GenerationAttempt {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (generationJobId == null) {
			throw new IllegalArgumentException("generationJobId is required");
		}
		if (provider == null || provider.isBlank()) {
			throw new IllegalArgumentException("provider is required");
		}
		if (attemptNumber < 1) {
			throw new IllegalArgumentException("attemptNumber must be >= 1");
		}
		if (startedAt == null) {
			throw new IllegalArgumentException("startedAt is required");
		}
		if (technicalOutcome != null
			&& !"SUCCESS".equals(technicalOutcome)
			&& !"FAILED".equals(technicalOutcome)) {
			throw new IllegalArgumentException("technicalOutcome must be SUCCESS, FAILED, or null");
		}
	}
}
