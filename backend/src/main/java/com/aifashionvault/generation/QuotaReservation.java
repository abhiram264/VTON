package com.aifashionvault.generation;

import java.time.Instant;
import java.util.UUID;

import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.UserId;

public record QuotaReservation(
	UUID id,
	UserId userId,
	GenerationJobId generationJobId,
	Instant reservedAt,
	Instant releasedAt) {

	public QuotaReservation {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (generationJobId == null) {
			throw new IllegalArgumentException("generationJobId is required");
		}
		if (reservedAt == null) {
			throw new IllegalArgumentException("reservedAt is required");
		}
	}

	public boolean active() {
		return releasedAt == null;
	}
}
