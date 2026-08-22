package com.aifashionvault.shared;

import java.util.UUID;

public record GenerationJobId(UUID value) {

	public GenerationJobId {
		if (value == null) {
			throw new IllegalArgumentException("value is required");
		}
	}
}
