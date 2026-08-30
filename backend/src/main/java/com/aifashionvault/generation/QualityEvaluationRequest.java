package com.aifashionvault.generation;

import com.aifashionvault.shared.GenerationAttemptId;

public record QualityEvaluationRequest(
	GenerationAttemptId attemptId,
	byte[] candidateBytes,
	byte[] subjectBytes,
	byte[] garmentBytes,
	byte[] previousAcceptedBytes,
	String boundPolicyVersion,
	RenderStepContext renderStep) {

	public QualityEvaluationRequest {
		if (attemptId == null) {
			throw new IllegalArgumentException("attemptId is required");
		}
	}

	public record RenderStepContext(int stepOrdinal, int totalSteps, boolean finalUserVisible) {

		public RenderStepContext {
			if (stepOrdinal < 1) {
				throw new IllegalArgumentException("stepOrdinal must be >= 1");
			}
			if (totalSteps < 1) {
				throw new IllegalArgumentException("totalSteps must be >= 1");
			}
			if (stepOrdinal > totalSteps) {
				throw new IllegalArgumentException("stepOrdinal must be <= totalSteps");
			}
		}
	}
}
