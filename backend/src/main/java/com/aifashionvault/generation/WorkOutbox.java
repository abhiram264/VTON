package com.aifashionvault.generation;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.aifashionvault.shared.GenerationJobId;

public record WorkOutbox(
	UUID id,
	GenerationJobId generationJobId,
	Instant notBefore,
	Instant createdAt,
	String publishState,
	int attemptCount,
	String lastErrorCategory,
	Instant sentAt,
	Instant claimedAt,
	Instant claimedUntil) {

	public static final String PENDING = "PENDING";
	public static final String CLAIMED = "CLAIMED";
	public static final String SENT = "SENT";
	private static final Set<String> PUBLISH_STATES = Set.of(PENDING, CLAIMED, SENT);

	public WorkOutbox {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (generationJobId == null) {
			throw new IllegalArgumentException("generationJobId is required");
		}
		if (notBefore == null) {
			throw new IllegalArgumentException("notBefore is required");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
		if (publishState == null || !PUBLISH_STATES.contains(publishState)) {
			throw new IllegalArgumentException("publishState must be PENDING, CLAIMED, or SENT");
		}
		if (attemptCount < 0) {
			throw new IllegalArgumentException("attemptCount must be >= 0");
		}
	}
}
