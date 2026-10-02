package com.aifashionvault.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class MetricsAnalyticsEventPublisherTest {

	private static final Instant OCCURRED_AT = Instant.parse("2026-03-04T05:06:07Z");

	private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
	private final AnalyticsEventPublisher publisher =
		new MetricsAnalyticsEventPublisher(new MicrometerProductMetrics(registry));

	@Test
	void aDeletionRequestReachesTheDeletionRequestMeter() {
		publisher.publish(event(AnalyticsEventName.DELETION_REQUESTED, Map.of()));

		assertThat(numerator(MicrometerProductMetrics.DELETION_REQUEST)).isEqualTo(1.0);
	}

	@Test
	void theDocumentedPropertyKeysCarryTheContextIntoTheMetrics() {
		// Every mapped key is exercised at once so a rename in the adapter cannot
		// pass unnoticed: only the fully populated event produces the PASS branch.
		publisher.publish(event(AnalyticsEventName.GENERATION_COMPLETED, Map.of(
			AnalyticsEventPublisher.OUTCOME, text("OK"),
			AnalyticsEventPublisher.MODE, text("STUDIO"),
			AnalyticsEventPublisher.QUALITY_DECISION, text("PASS"),
			AnalyticsEventPublisher.PROVIDER, text("inferhub"),
			AnalyticsEventPublisher.MODEL, text("model-x"),
			AnalyticsEventPublisher.DURATION_MS, number(1234L),
			AnalyticsEventPublisher.ITEM_COUNT, number(3L),
			AnalyticsEventPublisher.COST, decimal(2.5))));

		assertThat(numerator(MicrometerProductMetrics.GENERATION_SUCCESS)).isEqualTo(1.0);
		assertThat(numerator(MicrometerProductMetrics.QUALITY_LOOK_COST)).isEqualTo(1.0);
		assertThat(registry.get(MicrometerProductMetrics.GENERATION_LATENCY).timer().count()).isEqualTo(1L);
		assertThat(registry.get(MicrometerProductMetrics.SUCCESS_LOOK_COST).summary().totalAmount()).isEqualTo(2.5);
	}

	@Test
	void anEventWithoutATimestampIsDroppedRatherThanMisdatingIt() {
		// occurred_at is the one required property: the metrics boundary needs a
		// time, and stamping it with the adapter's own clock would silently
		// misdate an event that arrived late.
		publisher.publish(new AnalyticsEventPublisher.Event(
			AnalyticsEventName.DELETION_REQUESTED, Map.of()));

		assertThat(registry.find(MicrometerProductMetrics.DELETION_REQUEST).counter()).isNull();
	}

	@Test
	void aWronglyTypedPropertyIsIgnoredInsteadOfFailingTheEvent() {
		// mode arrives as a number: the deletion count must still be recorded, so
		// one producer bug degrades a field rather than losing the event.
		publisher.publish(event(AnalyticsEventName.DELETION_REQUESTED, Map.of(
			AnalyticsEventPublisher.MODE, number(7L),
			AnalyticsEventPublisher.COST, text("not-a-cost"))));

		assertThat(numerator(MicrometerProductMetrics.DELETION_REQUEST)).isEqualTo(1.0);
	}

	@Test
	void anUnsafePayloadCannotBeConstructedAtAll() {
		// The redaction boundary is the Event constructor, so a producer cannot
		// hand this adapter a photo, token or signed URL in the first place.
		assertThatIllegalArgumentException().isThrownBy(() -> event(
			AnalyticsEventName.DELETION_REQUESTED,
			Map.of("signed_url", text("https://example.invalid/x?signature=abc"))));
		assertThat(registry.find(MicrometerProductMetrics.DELETION_REQUEST).counter()).isNull();
	}

	private AnalyticsEventPublisher.Event event(
		AnalyticsEventName name,
		Map<String, AnalyticsEventPublisher.PropertyValue> extra
	) {
		Map<String, AnalyticsEventPublisher.PropertyValue> properties = new LinkedHashMap<>();
		properties.put(AnalyticsEventPublisher.OCCURRED_AT, number(OCCURRED_AT.toEpochMilli()));
		properties.putAll(extra);
		return new AnalyticsEventPublisher.Event(name, properties);
	}

	private static AnalyticsEventPublisher.TextValue text(String value) {
		return new AnalyticsEventPublisher.TextValue(value);
	}

	private static AnalyticsEventPublisher.LongValue number(long value) {
		return new AnalyticsEventPublisher.LongValue(value);
	}

	private static AnalyticsEventPublisher.DoubleValue decimal(double value) {
		return new AnalyticsEventPublisher.DoubleValue(value);
	}

	private double numerator(String meter) {
		return registry.get(meter)
			.tag("part", MicrometerProductMetrics.NUMERATOR)
			.tag("window", "all")
			.counter()
			.count();
	}
}
