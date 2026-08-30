package com.aifashionvault.generation;

public enum GenerationStatus {
	QUEUED,
	PREFLIGHT,
	SUBMITTED,
	GENERATING,
	QUALITY_CHECK,
	RETRYING,
	COMPLETED,
	FAILED
}
