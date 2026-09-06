package com.aifashionvault.notification;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationMetricsTest {

	private static final String CANARY_DEVICE_TOKEN = "device-token-canary-9f8e7d6c5b4a";
	private static final String CANARY_EVENT_ID = "event_0192c6f0-canary-id";
	private static final String CANARY_PROVIDER_MESSAGE_ID = "projects/test-project/messages/canary-msg";

	@Test
	void recordSendCreatesExactlyOneOutcomeCounterAndDurationTimerWithFrozenTags() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();

		new NotificationMetrics(registry).recordSend("FCM", "ACCEPTED", Duration.ofMillis(120));

		Counter counter = registry.get(NotificationMetrics.SEND_OUTCOME)
			.tag("channel", "FCM")
			.tag("outcome", "ACCEPTED")
			.counter();
		assertThat(counter.count()).isEqualTo(1.0);
		assertThat(counter.getId().getTags())
			.containsExactlyInAnyOrder(Tag.of("channel", "FCM"), Tag.of("outcome", "ACCEPTED"));
		Timer timer = registry.get(NotificationMetrics.SEND_DURATION)
			.tag("channel", "FCM")
			.tag("outcome", "ACCEPTED")
			.timer();
		assertThat(timer.count()).isEqualTo(1);
		assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isEqualTo(120.0);
		assertThat(timer.getId().getTags())
			.containsExactlyInAnyOrder(Tag.of("channel", "FCM"), Tag.of("outcome", "ACCEPTED"));
		assertThat(registry.getMeters()).hasSize(2);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"ACCEPTED", "RETRY_SCHEDULED", "PERMANENT_FAILURE", "DEADLINE_EXCEEDED", "ATTEMPTS_EXHAUSTED" })
	void everyFrozenOutcomeIsAccepted(String outcome) {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();

		new NotificationMetrics(registry).recordSend("FCM", outcome, Duration.ofMillis(5));

		assertThat(registry.get(NotificationMetrics.SEND_OUTCOME)
			.tag("channel", "FCM")
			.tag("outcome", outcome)
			.counter()
			.count()).isEqualTo(1.0);
		assertThat(registry.get(NotificationMetrics.SEND_DURATION)
			.tag("channel", "FCM")
			.tag("outcome", outcome)
			.timer()
			.count()).isEqualTo(1);
	}

	@Test
	void unknownOutcomeThrowsBeforeAnyMeterIsCreated() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		NotificationMetrics metrics = new NotificationMetrics(registry);

		assertThatThrownBy(() -> metrics.recordSend("FCM", "DELIVERED", Duration.ofMillis(5)))
			.isInstanceOf(IllegalArgumentException.class);

		assertThat(registry.getMeters()).isEmpty();
	}

	@Test
	void unknownChannelThrowsBeforeAnyMeterIsCreated() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		NotificationMetrics metrics = new NotificationMetrics(registry);

		assertThatThrownBy(() -> metrics.recordSend("APNS", "ACCEPTED", Duration.ofMillis(5)))
			.isInstanceOf(IllegalArgumentException.class);

		assertThat(registry.getMeters()).isEmpty();
	}

	@Test
	void nullArgumentsThrowBeforeAnyMeterIsCreated() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		NotificationMetrics metrics = new NotificationMetrics(registry);

		assertThatThrownBy(() -> metrics.recordSend(null, "ACCEPTED", Duration.ofMillis(5)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordSend("FCM", null, Duration.ofMillis(5)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordSend("FCM", "ACCEPTED", null))
			.isInstanceOf(IllegalArgumentException.class);

		assertThat(registry.getMeters()).isEmpty();
	}

	@Test
	void negativeDurationClampsToZeroLikeResolverDuration() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();

		new NotificationMetrics(registry).recordSend("FCM", "PERMANENT_FAILURE", Duration.ofMillis(-50));

		Timer timer = registry.get(NotificationMetrics.SEND_DURATION)
			.tag("channel", "FCM")
			.tag("outcome", "PERMANENT_FAILURE")
			.timer();
		assertThat(timer.count()).isEqualTo(1);
		assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isZero();
	}

	@Test
	void nullRegistryStillValidatesOutcomesWithoutRecording() {
		NotificationMetrics metrics = new NotificationMetrics(null);

		assertThatCode(() -> metrics.recordSend("FCM", "ACCEPTED", Duration.ofMillis(5)))
			.doesNotThrowAnyException();
		assertThatThrownBy(() -> metrics.recordSend("FCM", "BOGUS", Duration.ofMillis(5)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void registryFailureDoesNotPropagate() {
		SimpleMeterRegistry throwing = new SimpleMeterRegistry();
		throwing.config().meterFilter(new MeterFilter() {
			@Override
			public Meter.Id map(Meter.Id id) {
				throw new IllegalStateException("registry unavailable");
			}
		});

		assertThatCode(() -> new NotificationMetrics(throwing).recordSend("FCM", "ACCEPTED", Duration.ofMillis(5)))
			.doesNotThrowAnyException();
	}

	@Test
	void canariesCannotReachMeterNamesOrTags() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		NotificationMetrics metrics = new NotificationMetrics(registry);

		assertThatThrownBy(() -> metrics.recordSend(CANARY_DEVICE_TOKEN, "ACCEPTED", Duration.ofMillis(1)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> metrics.recordSend("FCM", CANARY_EVENT_ID, Duration.ofMillis(1)))
			.isInstanceOf(IllegalArgumentException.class);

		metrics.recordSend("FCM", "ACCEPTED", Duration.ofMillis(1));

		assertThat(registry.getMeters()).isNotEmpty();
		registry.getMeters().forEach(meter -> {
			String exported = meter.getId().getName() + meter.getId().getTags().toString();
			assertThat(exported)
				.doesNotContain(CANARY_DEVICE_TOKEN, CANARY_EVENT_ID, CANARY_PROVIDER_MESSAGE_ID);
		});
	}
}
