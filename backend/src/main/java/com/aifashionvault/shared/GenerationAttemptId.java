package com.aifashionvault.shared;

import java.util.UUID;

public record GenerationAttemptId(UUID value) {

	public GenerationAttemptId {
		if (value == null) {
			throw new IllegalArgumentException("value is required");
		}
	}
}
