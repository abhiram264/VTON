package com.aifashionvault.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

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
		if (deviceIdentities == null || deviceIdentities.isEmpty()) {
			throw new IllegalArgumentException("deviceIdentities is required");
		}
		store.insert(event);
		for (String deviceIdentity : deviceIdentities) {
			store.ensureDelivery(event.id(), deviceIdentity);
		}
	}

	public boolean processNext(Instant now) {
		if (now == null) {
			throw new IllegalArgumentException("now is required");
		}
		DueDelivery due = store.claimDue(now).orElse(null);
		if (due == null) {
			return false;
		}
		NotificationEvent event = due.event();
		DeviceDelivery delivery = due.delivery();
		if (!now.isBefore(event.deadlineAt())) {
			store.markTerminal(delivery.id(), delivery.attemptCount(), DeviceDelivery.DEADLINE_EXCEEDED, now);
			return true;
		}
		if (delivery.attemptCount() >= config.getMaxAttempts()) {
			store.markTerminal(delivery.id(), delivery.attemptCount(), DeviceDelivery.ATTEMPTS_EXHAUSTED, now);
			return true;
		}
		long started = System.nanoTime();
		Observation observation = observations.start(ObservationFacade.NOTIFICATION_SEND);
		FcmSendResult result;
		try {
			result = client.send(delivery.deviceIdentity(), event.pushPayload(), now);
		}
		catch (RuntimeException | Error failure) {
			observations.error(observation, failure);
			observations.stop(observation);
			throw failure;
		}
		int attempts = delivery.attemptCount() + 1;
		String outcome = sendOutcome(result, attempts, now, event.deadlineAt());
		observations.keyValue(observation, "outcome", outcome);
		observations.stop(observation);
		metrics.recordSend("FCM", outcome, Duration.ofNanos(System.nanoTime() - started));
		if (result.accepted()) {
			store.markAccepted(delivery.id(), attempts, result.providerMessageId(), now);
			return true;
		}
		if (result.retryable() && attempts < config.getMaxAttempts() && now.isBefore(event.deadlineAt())) {
			store.scheduleRetry(delivery.id(), attempts, now.plus(retryDelay(attempts, result.retryAfter())));
			return true;
		}
		store.markTerminal(delivery.id(), attempts, outcome, now);
		return true;
	}

	private String sendOutcome(FcmSendResult result, int attempts, Instant now, Instant deadlineAt) {
		if (result.accepted()) {
			return "ACCEPTED";
		}
		if (result.retryable() && attempts < config.getMaxAttempts() && now.isBefore(deadlineAt)) {
			return "RETRY_SCHEDULED";
		}
		return terminalFailure(result, now, deadlineAt);
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

	private static String terminalFailure(FcmSendResult result, Instant now, Instant deadlineAt) {
		if (!result.retryable()) {
			return DeviceDelivery.PERMANENT_FAILURE;
		}
		if (!now.isBefore(deadlineAt)) {
			return DeviceDelivery.DEADLINE_EXCEEDED;
		}
		return DeviceDelivery.ATTEMPTS_EXHAUSTED;
	}

	@Override
	public String toString() {
		return "FcmDeliveryWorker[]";
	}
}
