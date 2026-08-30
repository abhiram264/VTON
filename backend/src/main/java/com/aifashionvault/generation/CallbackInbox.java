package com.aifashionvault.generation;

import java.time.Instant;
import java.util.UUID;

import com.aifashionvault.shared.GenerationAttemptId;

public record CallbackInbox(
	UUID id,
	String dedupeHash,
	GenerationAttemptId generationAttemptId,
	String candidateProviderJobId,
	String safeTerminalHint,
	Instant receivedAt) {

	public CallbackInbox {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (dedupeHash == null || dedupeHash.isBlank()) {
			throw new IllegalArgumentException("dedupeHash is required");
		}
		if (receivedAt == null) {
			throw new IllegalArgumentException("receivedAt is required");
		}
	}
}
