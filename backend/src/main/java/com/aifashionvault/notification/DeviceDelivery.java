package com.aifashionvault.notification;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record DeviceDelivery(
	UUID id,
	UUID eventId,
	String deviceIdentity,
	String status,
	int attemptCount,
	Instant nextAttemptAt,
	String providerMessageId,
	String terminalResult
) {

	public static final String PENDING = "PENDING";
	public static final String ACCEPTED = "ACCEPTED";
	public static final String FAILED = "FAILED";

	public static final String ACCEPTED_FOR_DELIVERY = "ACCEPTED_FOR_DELIVERY";
	public static final String ATTEMPTS_EXHAUSTED = "ATTEMPTS_EXHAUSTED";
	public static final String DEADLINE_EXCEEDED = "DEADLINE_EXCEEDED";
	public static final String PERMANENT_FAILURE = "PERMANENT_FAILURE";

	private static final Set<String> STATUSES = Set.of(PENDING, ACCEPTED, FAILED);

	public DeviceDelivery {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (eventId == null) {
			throw new IllegalArgumentException("eventId is required");
		}
		if (deviceIdentity == null || deviceIdentity.isBlank()) {
			throw new IllegalArgumentException("deviceIdentity is required");
		}
		if (status == null || !STATUSES.contains(status)) {
			throw new IllegalArgumentException("status must be PENDING, ACCEPTED, or FAILED");
		}
		if (attemptCount < 0) {
			throw new IllegalArgumentException("attemptCount must be >= 0");
		}
	}

	public boolean acceptedForDelivery() {
		return ACCEPTED.equals(status) && ACCEPTED_FOR_DELIVERY.equals(terminalResult);
	}
}
