package com.aifashionvault.generation;

public sealed interface QualityEvaluationOutcome
	permits QualityEvaluationOutcome.Completed, QualityEvaluationOutcome.Unavailable {

	record Completed(QualityResult result) implements QualityEvaluationOutcome {

		public Completed {
			if (result == null) {
				throw new IllegalArgumentException("result is required");
			}
		}
	}

	record Unavailable(String reasonCode) implements QualityEvaluationOutcome {

		public Unavailable {
			if (reasonCode == null || reasonCode.isBlank()) {
				throw new IllegalArgumentException("reasonCode is required");
			}
		}
	}
}
