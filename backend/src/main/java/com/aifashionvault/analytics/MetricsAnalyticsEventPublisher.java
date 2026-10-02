package com.aifashionvault.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Connects the two halves of the analytics feature: a validated
 * {@link AnalyticsEventPublisher.Event} (whose key/value shape is the frozen,
 * redaction-enforcing boundary) and the identity-free {@link ProductMetrics}
 * stream. Without this adapter the taxonomy had no implementation and the
 * metrics had no caller, so a "recorded" lifecycle event could not exist.
 *
 * <p>Property key contract — the adapter is the single place that decides how
 * an event's properties become a {@link ProductMetricContext}, so call sites do
 * not each invent their own mapping:
 * <ul>
 *   <li>{@code occurred_at} — {@link AnalyticsEventPublisher.LongValue} epoch
 *       milliseconds. <strong>Required</strong>: the metrics boundary needs a
 *       timestamp, so an event without one is dropped rather than stamped with
 *       the adapter's own clock, which would silently misdate it.</li>
 *   <li>{@code outcome}, {@code mode}, {@code quality_decision}, {@code provider},
 *       {@code model} — {@link AnalyticsEventPublisher.TextValue}.</li>
 *   <li>{@code duration_ms}, {@code item_count} — {@link AnalyticsEventPublisher.LongValue}.</li>
 *   <li>{@code cost} — {@link AnalyticsEventPublisher.DoubleValue}.</li>
 * </ul>
 * An unrecognised key is ignored; a key of the wrong type is ignored too, so a
 * producer bug degrades one field instead of losing the whole event.
 *
 * <p>Publishing never throws: observability is deliberately off the product
 * critical path (the same posture {@link MicrometerProductMetrics} takes), so a
 * metrics failure can never fail the request that emitted it.
 */
public final class MetricsAnalyticsEventPublisher implements AnalyticsEventPublisher {

	private final ProductMetrics metrics;

	public MetricsAnalyticsEventPublisher(ProductMetrics metrics) {
		this.metrics = Objects.requireNonNull(metrics, "metrics is required");
	}

	@Override
	public void publish(Event event) {
		if (event == null) {
			return;
		}
		Long occurredAt = longValue(event.properties(), OCCURRED_AT);
		if (occurredAt == null) {
			return;
		}
		ProductMetricContext context = new ProductMetricContext(
			Instant.ofEpochMilli(occurredAt),
			textValue(event.properties(), OUTCOME),
			textValue(event.properties(), MODE),
			textValue(event.properties(), QUALITY_DECISION),
			textValue(event.properties(), PROVIDER),
			textValue(event.properties(), MODEL),
			longValue(event.properties(), DURATION_MS),
			longValue(event.properties(), ITEM_COUNT),
			decimalValue(event.properties(), COST));
		try {
			this.metrics.record(event.name(), context);
		}
		catch (RuntimeException ignored) {
			// Off the critical path: a metrics failure must not fail the caller.
		}
	}

	private static String textValue(Map<String, PropertyValue> properties, String key) {
		PropertyValue value = properties.get(key);
		return value instanceof TextValue text ? text.value() : null;
	}

	private static Long longValue(Map<String, PropertyValue> properties, String key) {
		PropertyValue value = properties.get(key);
		return value instanceof LongValue number ? number.value() : null;
	}

	private static BigDecimal decimalValue(Map<String, PropertyValue> properties, String key) {
		PropertyValue value = properties.get(key);
		return value instanceof DoubleValue number ? BigDecimal.valueOf(number.value()) : null;
	}
}
