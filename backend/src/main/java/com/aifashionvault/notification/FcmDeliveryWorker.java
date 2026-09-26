package com.aifashionvault.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import com.aifashionvault.notification.FcmClient.FcmSendResult;
import com.aifashionvault.notification.NotificationEventStore.DueDelivery;
import com.aifashionvault.shared.ObservationFacade;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;

public final class FcmDeliveryWorker {

	private final NotificationEventStore store;
	private final FcmClient client;
	private final NotificationDeliveryConfiguration config;
	private final Random random;
	private final NotificationMetrics metrics;
	private final ObservationFacade observations;

	public FcmDeliveryWorker(
		NotificationEventStore store,
		FcmClient client,
		NotificationDeliveryConfiguration config
	) {
		this(store, client, config, ThreadLocalRandom.current());
	}

	public FcmDeliveryWorker(
		NotificationEventStore store,
		FcmClient client,
		NotificationDeliveryConfiguration config,
		Random random
	) {
		this(store, client, config, random, null, null);
	}

	public FcmDeliveryWorker(
		NotificationEventStore store,
		FcmClient client,
		NotificationDeliveryConfiguration config,
		Random random,
		MeterRegistry meterRegistry,
		ObservationRegistry observationRegistry
	) {
		if (store == null) {
			throw new IllegalArgumentException("store is required");
		}
		if (client == null) {
			throw new IllegalArgumentException("client is required");
		}
		if (config == null) {
			throw new IllegalArgumentException("config is required");
		}
		if (random == null) {
			throw new IllegalArgumentException("random is required");
		}
		this.store = store;
		this.client = client;
		this.config = config;
		this.random = random;
		this.metrics = new NotificationMetrics(meterRegistry);
		this.observations = new ObservationFacade(observationRegistry);
	}

	public void enqueue(NotificationEvent event, List<String> deviceIdentities) {
		if (event == null) {
			throw new IllegalArgumentException("event is required");
		}
		boolean inserted = store.insert(event);
		if (deviceIdentities == null || deviceIdentities.isEmpty()) {
			return;
		}
		NotificationEvent durable = inserted
				? event
				: store.findByGenerationAndType(event.generationId(), event.type()).orElseThrow(
						() -> new IllegalStateException("persisted notification event not found"));
		if (!sameImmutablePayload(event, durable)) {
			throw new IllegalStateException("notification event replay payload mismatch");
		}
		for (String deviceIdentity : deviceIdentities) {
			store.ensureDelivery(durable.id(), deviceIdentity);
		}
	}

	public boolean processNext(Instant now) {
		return processNext(now, Instant::now);
	}

	boolean processNext(Instant now, Instant completedAt) {
		return processNext(now, () -> completedAt);
	}

	boolean processNext(Instant now, Supplier<Instant> completionClock) {
		if (now == null) {
			throw new IllegalArgumentException("now is required");
		}
		if (completionClock == null) {
			throw new IllegalArgumentException("completionClock is required");
		}
		DueDelivery due = store.claimDue(now).orElse(null);
		if (due == null) {
			return false;
		}
		NotificationEvent event = due.event();
		DeviceDelivery delivery = due.delivery();
		if (!now.isBefore(event.deadlineAt())) {
			store.markTerminal(
					delivery.id(), due.claimToken(), due.claimEpoch(), delivery.attemptCount(),
					DeviceDelivery.DEADLINE_EXCEEDED, now);
			return true;
		}
		if (delivery.attemptCount() >= config.getMaxAttempts()) {
			store.markTerminal(
					delivery.id(), due.claimToken(), due.claimEpoch(), delivery.attemptCount(),
					DeviceDelivery.ATTEMPTS_EXHAUSTED, now);
			return true;
		}
		long started = System.nanoTime();
		Observation observation = observations.start(ObservationFacade.NOTIFICATION_SEND);
		FcmSendResult result;
		try {
			result = client.send(delivery.deviceIdentity(), event.pushPayload(), now, event.deadlineAt());
		}
		catch (RuntimeException | Error failure) {
			observations.error(observation, failure);
			observations.stop(observation);
			throw failure;
		}
		Instant completedAt = completionClock.get();
		int attempts = delivery.attemptCount() + 1;
		String outcome = sendOutcome(result, attempts, completedAt, event.deadlineAt());
		observations.keyValue(observation, "outcome", outcome);
		observations.stop(observation);
		metrics.recordSend("FCM", outcome, Duration.ofNanos(System.nanoTime() - started));
		if (result.accepted() && completedAt.isBefore(event.deadlineAt())) {
			store.markAccepted(
					delivery.id(), due.claimToken(), due.claimEpoch(), attempts,
					result.providerMessageId(), completedAt);
			return true;
		}
		if (result.retryable()
				&& attempts < config.getMaxAttempts()
				&& completedAt.isBefore(event.deadlineAt())) {
			Duration delay = retryDelay(attempts, result.retryAfter());
			Duration remaining = Duration.between(completedAt, event.deadlineAt());
			store.scheduleRetry(
					delivery.id(), due.claimToken(), due.claimEpoch(), attempts,
					completedAt.plus(delay.compareTo(remaining) < 0 ? delay : remaining));
			return true;
		}
		store.markTerminal(
				delivery.id(), due.claimToken(), due.claimEpoch(), attempts, outcome, completedAt);
		return true;
	}

	private String sendOutcome(FcmSendResult result, int attempts, Instant now, Instant deadlineAt) {
		if (!now.isBefore(deadlineAt)) {
			return DeviceDelivery.DEADLINE_EXCEEDED;
		}
		if (result.accepted()) {
			return "ACCEPTED";
		}
		if (result.retryable() && attempts < config.getMaxAttempts()) {
			return "RETRY_SCHEDULED";
		}
		return result.retryable() ? DeviceDelivery.ATTEMPTS_EXHAUSTED : DeviceDelivery.PERMANENT_FAILURE;
	}

	private static boolean sameImmutablePayload(NotificationEvent expected, NotificationEvent actual) {
		return Objects.equals(expected.userId(), actual.userId())
				&& Objects.equals(expected.generationId(), actual.generationId())
				&& Objects.equals(expected.type(), actual.type())
				&& Objects.equals(expected.lookId(), actual.lookId())
				&& Objects.equals(expected.createdAt(), actual.createdAt())
				&& Objects.equals(expected.deadlineAt(), actual.deadlineAt())
				&& Objects.equals(expected.reason(), actual.reason());
	}

	Duration retryDelay(int attemptCount, Duration retryAfter) {
		Duration delay = retryAfter == null ? exponentialBackoff(attemptCount) : retryAfter;
		Duration minimum = config.minRetry();
		return delay.compareTo(minimum) < 0 ? minimum : delay;
	}

	private Duration exponentialBackoff(int attemptCount) {
		int shift = Math.max(0, Math.min(attemptCount - 1, 8));
		long baseMillis = config.minRetry().toMillis() * (1L << shift);
		long jitterMillis = Math.round(random.nextDouble() * 0.2d * baseMillis);
		return Duration.ofMillis(baseMillis + jitterMillis);
	}

	@Override
	public String toString() {
		return "FcmDeliveryWorker[]";
	}
}
