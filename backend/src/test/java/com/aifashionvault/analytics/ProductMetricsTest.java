package com.aifashionvault.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;

class ProductMetricsTest {
    private static final Instant START = Instant.parse("2026-01-01T23:00:00Z");

    @Test
    void recordsEveryPercentageAsDistinctNumeratorAndDenominator() {
        var registry = new SimpleMeterRegistry();
        ProductMetrics metrics = new MicrometerProductMetrics(registry);
        metrics.record(AnalyticsEventName.SHARE_RECEIVED, context(START, null, 1L, null, null));
        metrics.record(AnalyticsEventName.SHARE_RESOLUTION_FAILED, context(START, null, 1L, null, null));
        metrics.record(AnalyticsEventName.SHARE_RESOLVED, context(START, null, 1L, null, null));
        metrics.record(AnalyticsEventName.GENERATION_REQUESTED, context(START, "SHARE", 1L, null, null));
        metrics.record(AnalyticsEventName.GENERATION_REQUESTED, context(START, "STUDIO", 2L, null, null));
        metrics.record(AnalyticsEventName.GENERATION_FAILED, context(START, null, 1L, null, null));
        metrics.record(AnalyticsEventName.GENERATION_COMPLETED, context(START, null, 1L, "PASS", null));
        metrics.record(AnalyticsEventName.GENERATION_QUALITY_REJECTED, context(START, null, 1L, "REJECT", null));
        metrics.record(AnalyticsEventName.VAULT_ITEM_CREATED, context(START, null, 1L, null, null));
        metrics.record(AnalyticsEventName.VAULT_ITEM_REUSED, context(START, null, 1L, null, null));
        metrics.record(AnalyticsEventName.LOOK_SAVED, context(START, null, 1L, null, null));
        metrics.record(AnalyticsEventName.LOOK_SHARED, context(START, null, 1L, null, null));

        assertParts(registry, MicrometerProductMetrics.SHARE_IMPORT_SUCCESS, 1, 2);
        assertParts(registry, MicrometerProductMetrics.SHARE_TRY_ON, 1, 1);
        assertParts(registry, MicrometerProductMetrics.GENERATION_SUCCESS, 1, 3);
        assertParts(registry, MicrometerProductMetrics.QUALITY_REJECTION, 1, 2);
        assertThat(registry.find(MicrometerProductMetrics.VAULT_ITEMS).tag("part", "denominator").counter()).isNull();
        assertParts(registry, MicrometerProductMetrics.VAULT_REUSE, 1, 1);
        assertParts(registry, MicrometerProductMetrics.STUDIO_USAGE, 1, 1);
        assertParts(registry, MicrometerProductMetrics.MULTI_ITEM_USAGE, 1, 2);
        assertParts(registry, MicrometerProductMetrics.LOOK_SAVE, 1, 1);
        assertParts(registry, MicrometerProductMetrics.LOOK_SHARE, 1, 1);
    }

    @Test
    void zeroDenominatorsStayUnavailable() {
        var registry = new SimpleMeterRegistry();
        ProductMetrics metrics = new MicrometerProductMetrics(registry);
        metrics.record(AnalyticsEventName.LOOK_SAVED, context(START, null, null, null, null));
        assertThat(registry.find(MicrometerProductMetrics.LOOK_SAVE).tag("part", "denominator").counter()).isNull();
        assertThat(registry.find(MicrometerProductMetrics.LOOK_SAVE).tag("part", "numerator").counter().count()).isOne();
    }

    @Test
    void firstLookIsNonNegativeAndRecordedOnlyOnce() {
        var registry = new SimpleMeterRegistry();
        ProductMetrics metrics = new MicrometerProductMetrics(registry);
        metrics.record(AnalyticsEventName.LOOK_VIEWED, context(START.minusSeconds(1), null, null, null, null));
        metrics.record(AnalyticsEventName.ACCOUNT_CREATED, context(START, null, null, null, null));
        metrics.record(AnalyticsEventName.LOOK_VIEWED, context(START.minusSeconds(1), null, null, null, null));
        metrics.record(AnalyticsEventName.LOOK_VIEWED, context(START.plusSeconds(90), null, null, null, null));
        metrics.record(AnalyticsEventName.LOOK_VIEWED, context(START.plusSeconds(180), null, null, null, null));
        assertThat(registry.get(MicrometerProductMetrics.FIRST_LOOK).timer().count()).isOne();
        assertThat(registry.get(MicrometerProductMetrics.FIRST_LOOK).timer().totalTime(TimeUnit.SECONDS)).isEqualTo(90);
    }

    @Test
    void latencyAndCostsUseOnlyValidCompletedOutcomes() {
        var registry = new SimpleMeterRegistry();
        ProductMetrics metrics = new MicrometerProductMetrics(registry);
        metrics.record(AnalyticsEventName.GENERATION_REQUESTED, context(START, null, 1L, null, null));
        metrics.record(AnalyticsEventName.GENERATION_FAILED, context(START, null, 1L, null, null));
        metrics.record(AnalyticsEventName.GENERATION_COMPLETED, context(START, null, 1L, "PASS", new BigDecimal("2.50"), 0L));
        metrics.record(AnalyticsEventName.GENERATION_COMPLETED, context(START, null, 1L, "REJECT", new BigDecimal("1.25"), 950L));
        assertThat(registry.get(MicrometerProductMetrics.GENERATION_LATENCY).timer().count()).isEqualTo(2);
        assertThat(registry.get(MicrometerProductMetrics.SUCCESS_LOOK_COST).summary().count()).isEqualTo(2);
        assertThat(registry.get(MicrometerProductMetrics.QUALITY_LOOK_COST).summary().count()).isOne();
    }

    @Test
    void retentionUsesExactUtcDayOffsetsAndCoreUsesBoundedUtcWeek() {
        var registry = new SimpleMeterRegistry();
        ProductMetrics metrics = new MicrometerProductMetrics(registry);
        metrics.record(AnalyticsEventName.ACCOUNT_CREATED, context(START, null, null, null, null));
        metrics.record(AnalyticsEventName.LOOK_VIEWED, context(Instant.parse("2026-01-02T00:01:00Z"), null, null, null, null));
        metrics.record(AnalyticsEventName.LOOK_VIEWED, context(START.plusSeconds(2 * 86400), null, null, null, null));
        metrics.record(AnalyticsEventName.LOOK_VIEWED, context(START.plusSeconds(7 * 86400), null, null, null, null));
        metrics.record(AnalyticsEventName.LOOK_VIEWED, context(START.plusSeconds(30 * 86400), null, null, null, null));
        metrics.record(AnalyticsEventName.GENERATION_COMPLETED, context(START, null, 1L, "PASS", null));
        metrics.record(AnalyticsEventName.GENERATION_COMPLETED, context(START.plusSeconds(7 * 86400), null, 1L, "PASS", null));

        assertThat(counter(registry, MicrometerProductMetrics.RETENTION, "numerator", "offset1")).isOne();
        assertThat(counter(registry, MicrometerProductMetrics.RETENTION, "numerator", "offset7")).isOne();
        assertThat(counter(registry, MicrometerProductMetrics.RETENTION, "numerator", "offset30")).isOne();
        assertThat(registry.find(MicrometerProductMetrics.RETENTION).tag("window", "offset2").counter()).isNull();
        assertThat(registry.find(MicrometerProductMetrics.CORE_WEEKLY).tag("part", "denominator").counters()).hasSize(1);
        assertThat(registry.find(MicrometerProductMetrics.CORE_WEEKLY).tag("part", "numerator").counters()).hasSize(2);
    }

    @Test
    void repeatedActivationsCountEachUtcWeekAndYear() {
        var registry = new SimpleMeterRegistry();
        ProductMetrics metrics = new MicrometerProductMetrics(registry);
        metrics.record(AnalyticsEventName.ACCOUNT_CREATED, context(Instant.parse("2025-01-01T00:00:00Z"), null, null, null, null));
        metrics.record(AnalyticsEventName.ACCOUNT_CREATED, context(Instant.parse("2026-01-01T00:00:00Z"), null, null, null, null));

        assertThat(registry.find(MicrometerProductMetrics.CORE_WEEKLY).tag("part", "denominator").counters())
                .extracting(counter -> counter.getId().getTag("window"))
                .containsExactlyInAnyOrder("w2025-01", "w2026-01");
        assertThat(registry.find(MicrometerProductMetrics.RETENTION).tag("part", "denominator").counters())
                .allSatisfy(counter -> assertThat(counter.count()).isEqualTo(2));
    }

    @Test
    void vaultItemsDoesNotInventActiveUserDenominator() {
        var registry = new SimpleMeterRegistry();
        ProductMetrics metrics = new MicrometerProductMetrics(registry);
        metrics.record(AnalyticsEventName.VAULT_ITEM_CREATED, context(START, null, 1L, null, null));
        metrics.record(AnalyticsEventName.VAULT_ITEM_CREATED, context(START, null, 1L, null, null));

        assertThat(counter(registry, MicrometerProductMetrics.VAULT_ITEMS, "numerator", "all")).isEqualTo(2);
        assertThat(registry.find(MicrometerProductMetrics.VAULT_ITEMS).tag("part", "denominator").counter()).isNull();
    }

    @Test
    void unsafeLabelsAreNeverExportedAndInvalidValuesCreateNoMeters() {
        var registry = new SimpleMeterRegistry();
        ProductMetrics metrics = new MicrometerProductMetrics(registry);
        String secret = "https://private.example/item?token=user-123";
        metrics.record(AnalyticsEventName.GENERATION_REQUESTED,
                new ProductMetricContext(START, secret, secret, secret, secret, secret, 1L, 1L, BigDecimal.ONE));
        metrics.record(AnalyticsEventName.GENERATION_COMPLETED,
                new ProductMetricContext(START, null, null, null, null, null, -1L, null, null));
        assertThat(registry.getMeters()).allSatisfy(meter -> {
            assertThat(meter.getId().getTags()).extracting(tag -> tag.getKey()).containsOnly("part", "window");
            assertThat(meter.getId().getTags()).extracting(tag -> tag.getValue()).noneMatch(secret::equals);
        });
        assertThat(registry.find(MicrometerProductMetrics.GENERATION_LATENCY).meter()).isNull();
    }

    @Test
    void nullAndThrowingRegistriesNeverPropagate() {
        ProductMetrics absent = new MicrometerProductMetrics(null);
        assertThatCode(() -> absent.record(AnalyticsEventName.SHARE_RECEIVED, context(START, null, null, null, null))).doesNotThrowAnyException();
        var throwing = new SimpleMeterRegistry();
        throwing.config().meterFilter(new MeterFilter() {
            @Override public Meter.Id map(Meter.Id id) {
                throw new IllegalStateException("registry unavailable");
            }
        });
        ProductMetrics isolated = new MicrometerProductMetrics(throwing);
        assertThatCode(() -> isolated.record(AnalyticsEventName.SHARE_RECEIVED, context(START, null, null, null, null))).doesNotThrowAnyException();
        assertThatCode(() -> isolated.record(null, null)).doesNotThrowAnyException();
    }

    @Test
    void deletionLifecycleRecordsRequestCompletionAndCost() {
        var registry = new SimpleMeterRegistry();
        ProductMetrics metrics = new MicrometerProductMetrics(registry);
        metrics.record(AnalyticsEventName.DELETION_REQUESTED, context(START, null, null, null, null));
        metrics.record(AnalyticsEventName.DELETION_COMPLETED, context(START, null, null, null, new BigDecimal("0.42")));

        assertThat(counter(registry, MicrometerProductMetrics.DELETION_REQUEST, "numerator", "all")).isOne();
        assertThat(counter(registry, MicrometerProductMetrics.DELETION_COMPLETION, "numerator", "all")).isOne();
        assertThat(registry.get(MicrometerProductMetrics.DELETION_COST).summary().count()).isOne();
        assertThat(registry.get(MicrometerProductMetrics.DELETION_COST).summary().totalAmount()).isEqualTo(0.42d);
    }

    @Test
    void deletionCompletionWithoutCostStillCountsButRecordsNoCost() {
        var registry = new SimpleMeterRegistry();
        ProductMetrics metrics = new MicrometerProductMetrics(registry);
        metrics.record(AnalyticsEventName.DELETION_COMPLETED, context(START, null, null, null, null));

        assertThat(counter(registry, MicrometerProductMetrics.DELETION_COMPLETION, "numerator", "all")).isOne();
        assertThat(registry.find(MicrometerProductMetrics.DELETION_COST).meter()).isNull();
        assertThat(registry.find(MicrometerProductMetrics.DELETION_REQUEST).meter()).isNull();
    }

    private static ProductMetricContext context(Instant at, String mode, Long items, String quality, BigDecimal cost) {
        return context(at, mode, items, quality, cost, 100L);
    }

    private static ProductMetricContext context(Instant at, String mode, Long items, String quality, BigDecimal cost, Long duration) {
        return new ProductMetricContext(at, "SUCCESS", mode, quality, "APPROVED", "MODEL", duration, items, cost);
    }

    private static void assertParts(SimpleMeterRegistry registry, String name, double numerator, double denominator) {
        assertThat(counter(registry, name, "numerator", "all")).isEqualTo(numerator);
        assertThat(counter(registry, name, "denominator", "all")).isEqualTo(denominator);
    }

    private static double counter(SimpleMeterRegistry registry, String name, String part, String window) {
        return registry.get(name).tag("part", part).tag("window", window).counter().count();
    }
}
