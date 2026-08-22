package com.aifashionvault.generation;

import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.QualityResultId;

public record QualityResult(QualityResultId id, GenerationAttemptId generationAttemptId, QualityDecision decision, java.math.BigDecimal identityPreservation, java.math.BigDecimal garmentFidelity, java.math.BigDecimal colorFidelity, Boolean anatomyValid, Boolean artifactDetected, java.time.Instant createdAt) {

	public QualityResult {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (generationAttemptId == null) {
			throw new IllegalArgumentException("generationAttemptId is required");
		}
		if (decision == null) {
			throw new IllegalArgumentException("decision is required");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
	}
}
