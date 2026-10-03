package com.aifashionvault.notification;

import java.time.Duration;
import java.util.Set;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** Privacy-safe, low-cardinality metrics around FCM notification sends. */
public final class NotificationMetrics {

	public static final String SEND_OUTCOME = "notification.send.outcome";
	public static final String SEND_DURATION = "notification.send.duration";

	private static final Set<String> CHANNELS = Set.of("FCM");
	private static final Set<String> OUTCOMES = Set.of(
		"ACCEPTED",
		"RETRY_SCHEDULED",
		"PERMANENT_FAILURE",
		"DEADLINE_EXCEEDED",
		"ATTEMPTS_EXHAUSTED");

	private final MeterRegistry registry;

	public NotificationMetrics(MeterRegistry registry) {
		this.registry = registry;
	}

	public void recordSend(String channel, String outcome, Duration duration) {
		if (channel == null || !CHANNELS.contains(channel)) {
			throw new IllegalArgumentException("channel must be one of " + CHANNELS);
		}
		if (outcome == null || !OUTCOMES.contains(outcome)) {
			throw new IllegalArgumentException("outcome must be one of " + OUTCOMES);
		}
		if (duration == null) {
			throw new IllegalArgumentException("duration is required");
		}
		if (registry == null) {
			return;
		}
		try {
			Counter.builder(SEND_OUTCOME)
				.tags("channel", channel, "outcome", outcome)
				.register(registry)
				.increment();
			Timer.builder(SEND_DURATION)
				.tags("channel", channel, "outcome", outcome)
				.register(registry)
				.record(duration.isNegative() ? Duration.ZERO : duration);
		}
		catch (RuntimeException | Error ignored) {
			// Delivery must not depend on observability availability.
		}
	}
}
