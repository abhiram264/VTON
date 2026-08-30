package com.aifashionvault.generation;

public enum CoarseGenerationPhase {
	QUEUED,
	PREPARING,
	GENERATING,
	CHECKING_QUALITY,
	READY,
	FAILED;

	public static CoarseGenerationPhase fromDurable(String durableStatus, String terminalReason) {
		if (durableStatus == null || durableStatus.isBlank()) {
			throw new IllegalArgumentException("durableStatus must not be blank");
		}
		return switch (durableStatus) {
			case "QUEUED" -> QUEUED;
			case "PREFLIGHT" -> PREPARING;
			case "SUBMITTED", "GENERATING", "RETRYING" -> GENERATING;
			case "QUALITY_CHECK" -> CHECKING_QUALITY;
			case "COMPLETED" -> READY;
			case "FAILED" -> FAILED;
			default -> throw new IllegalArgumentException("Unknown durableStatus: " + durableStatus);
		};
	}
}
