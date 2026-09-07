package com.aifashionvault.platform.security;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

public final class AuthMetrics {

	public static final String AUTH_SUCCESS = "auth.success";
	public static final String AUTH_FAILURE = "auth.failure";

	public static final String REASON_MISSING = "MISSING";
	public static final String REASON_MALFORMED = "MALFORMED";
	public static final String REASON_REJECTED = "REJECTED";

	private final MeterRegistry registry;

	public AuthMetrics(MeterRegistry registry) {
		if (registry == null) {
			throw new IllegalArgumentException("registry is required");
		}
		this.registry = registry;
	}

	public void recordAuthSuccess() {
		try {
			Counter.builder(AUTH_SUCCESS)
				.register(registry)
				.increment();
		}
		catch (RuntimeException ignored) {
			// Observability must never fail authentication (slice-53 §37).
		}
	}

	public void recordAuthFailure(String reason) {
		String validReason = requireReason(reason);
		try {
			Counter.builder(AUTH_FAILURE)
				.tag("reason", validReason)
				.register(registry)
				.increment();
		}
		catch (RuntimeException ignored) {
			// Observability must never fail authentication (slice-53 §37).
		}
	}

	private static String requireReason(String reason) {
		if (REASON_MISSING.equals(reason)
			|| REASON_MALFORMED.equals(reason)
			|| REASON_REJECTED.equals(reason)) {
			return reason;
		}
		throw new IllegalArgumentException("reason must be MISSING, MALFORMED, or REJECTED");
	}
}
