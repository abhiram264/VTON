package com.aifashionvault.platform.queue;

import java.util.UUID;

public record GenerationWorkMessage(UUID generationId) {
	public GenerationWorkMessage {
		if (generationId == null) throw new IllegalArgumentException("generationId is required");
	}
}
