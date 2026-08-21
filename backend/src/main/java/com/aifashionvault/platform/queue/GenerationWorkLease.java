package com.aifashionvault.platform.queue;

import java.util.Objects;
import java.util.UUID;

public record GenerationWorkLease(UUID generationJobId, String receiptHandle) {

	public GenerationWorkLease {
		Objects.requireNonNull(generationJobId, "generationJobId");
		Objects.requireNonNull(receiptHandle, "receiptHandle");
		if (receiptHandle.isBlank()) {
			throw new IllegalArgumentException("receiptHandle");
		}
	}
}
