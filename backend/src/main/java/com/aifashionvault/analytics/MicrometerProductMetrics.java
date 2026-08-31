package com.aifashionvault.analytics;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Best-effort, identity-free product metrics. Counts describe the supplied event
 * stream (at-least-once); without an identity field this boundary cannot dedupe
 * users or attribute events beyond their explicit ordering and bounded context.
 * Percentage numerators and denominators are emitted separately. A denominator
 * is never synthesized, and UTC day/week windows are represented by fixed tags.
 */
public final class MicrometerProductMetrics implements ProductMetrics {
    public static final String SHARE_IMPORT_SUCCESS = "product.share.import.success";
    public static final String SHARE_TRY_ON = "product.share.try_on";
    public static final String FIRST_LOOK = "product.time_to_first_look";
    public static final String GENERATION_SUCCESS = "product.generation.success";
    public static final String QUALITY_REJECTION = "product.generation.quality_rejection";
    public static final String GENERATION_LATENCY = "product.generation.latency";
    public static final String SUCCESS_LOOK_COST = "product.look.cost.success";
    public static final String QUALITY_LOOK_COST = "product.look.cost.quality_approved";
    public static final String VAULT_ITEMS = "product.vault.items";
    public static final String VAULT_REUSE = "product.vault.reuse";
    public static final String STUDIO_USAGE = "product.studio.usage";
    public static final String MULTI_ITEM_USAGE = "product.generation.multi_item";
    public static final String LOOK_SAVE = "product.look.save";
    public static final String LOOK_SHARE = "product.look.share";
    public static final String RETENTION = "product.retention";
    public static final String CORE_WEEKLY = "product.core.quality_approved_looks_per_activated_user_week";
    public static final String NUMERATOR = "numerator";
    public static final String DENOMINATOR = "denominator";

    private final MeterRegistry registry;
    // Identity-free stream boundary: this is only the earliest supplied activation for first-Look/retention timing.
    private final AtomicLong activation = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong firstLook = new AtomicLong(Long.MAX_VALUE);

    public MicrometerProductMetrics(MeterRegistry registry) { this.registry = registry; }

    @Override
    public void record(AnalyticsEventName eventName, ProductMetricContext c) {
        if (registry == null || eventName == null || c == null || c.occurredAt() == null || invalid(c)) return;
        try {
            switch (eventName) {
                case SHARE_RECEIVED, SHARE_RESOLUTION_FAILED -> count(SHARE_IMPORT_SUCCESS, DENOMINATOR);
                case SHARE_RESOLVED -> {
                    count(SHARE_IMPORT_SUCCESS, NUMERATOR);
                    count(SHARE_TRY_ON, DENOMINATOR);
                }
                case ACCOUNT_CREATED -> {
                    // Every delivery is counted: without identity, at-least-once stream counts are intentional.
                    activation.accumulateAndGet(c.occurredAt().toEpochMilli(), Math::min);
                    count(CORE_WEEKLY, DENOMINATOR, week(c.occurredAt()));
                    count(RETENTION, DENOMINATOR, "offset1");
                    count(RETENTION, DENOMINATOR, "offset7");
                    count(RETENTION, DENOMINATOR, "offset30");
                }
                case GENERATION_REQUESTED -> {
                    count(GENERATION_SUCCESS, DENOMINATOR);
                    count(MULTI_ITEM_USAGE, DENOMINATOR);
                    if (c.itemCount() != null && c.itemCount() > 1) count(MULTI_ITEM_USAGE, NUMERATOR);
                    if (isStudio(c.mode())) count(STUDIO_USAGE, NUMERATOR);
                    if (isStudio(c.mode())) count(STUDIO_USAGE, DENOMINATOR);
                    if (isShare(c.mode())) count(SHARE_TRY_ON, NUMERATOR);
                }
                case GENERATION_COMPLETED -> {
                    count(GENERATION_SUCCESS, NUMERATOR);
                    count(QUALITY_REJECTION, DENOMINATOR);
                    count(LOOK_SAVE, DENOMINATOR);
                    count(LOOK_SHARE, DENOMINATOR);
                    recordLatency(c.durationMillis());
                    recordCost(SUCCESS_LOOK_COST, c.cost());
                    if ("PASS".equals(quality(c.qualityDecision()))) {
                        count(CORE_WEEKLY, NUMERATOR, week(c.occurredAt()));
                        count(QUALITY_LOOK_COST, NUMERATOR);
                        recordCost(QUALITY_LOOK_COST, c.cost());
                    }
                }
                case GENERATION_FAILED -> count(GENERATION_SUCCESS, DENOMINATOR);
                case GENERATION_QUALITY_REJECTED -> {
                    count(QUALITY_REJECTION, NUMERATOR);
                    count(QUALITY_REJECTION, DENOMINATOR);
                }
                case VAULT_ITEM_CREATED -> {
                    count(VAULT_ITEMS, NUMERATOR);
                    // No active-user event exists in the frozen taxonomy; denominator is unavailable,
                    // so item creation is never reused as a tautological active-user denominator.
                    count(VAULT_REUSE, DENOMINATOR);
                }
                case VAULT_ITEM_REUSED -> count(VAULT_REUSE, NUMERATOR);
                case STUDIO_STARTED -> count(STUDIO_USAGE, DENOMINATOR);
                case LOOK_SAVED -> count(LOOK_SAVE, NUMERATOR);
                case LOOK_SHARED -> count(LOOK_SHARE, NUMERATOR);
                case LOOK_VIEWED -> recordLookAndRetention(c.occurredAt());
                default -> { }
            }
        } catch (RuntimeException ignored) {
            // Observability is deliberately off the product critical path.
        }
    }

    private void recordLookAndRetention(Instant at) {
        long timestamp = at.toEpochMilli();
        long activationAt = activation.get();
        if (activationAt != Long.MAX_VALUE && timestamp >= activationAt) {
            if (firstLook.compareAndSet(Long.MAX_VALUE, timestamp)) {
                Timer.builder(FIRST_LOOK).register(registry).record(Duration.ofMillis(timestamp - activationAt));
            }
            long days = ChronoUnit.DAYS.between(Instant.ofEpochMilli(activationAt).atZone(ZoneOffset.UTC).toLocalDate(), at.atZone(ZoneOffset.UTC).toLocalDate());
            if (days == 1 || days == 7 || days == 30) count(RETENTION, NUMERATOR, "offset" + days);
        }
    }

    private void recordLatency(Long millis) {
        if (millis != null) Timer.builder(GENERATION_LATENCY).register(registry).record(Duration.ofMillis(millis));
    }

    private void recordCost(String name, BigDecimal cost) {
        if (cost != null) DistributionSummary.builder(name).tag("part", "amount").register(registry).record(cost.doubleValue());
    }

    private boolean invalid(ProductMetricContext c) {
        return c.durationMillis() != null && c.durationMillis() < 0
                || c.itemCount() != null && c.itemCount() < 0
                || c.cost() != null && c.cost().signum() < 0;
    }

    private void count(String name, String part) { count(name, part, "all"); }

    private void count(String name, String part, String window) {
        Counter.builder(name).tag("part", part).tag("window", boundedWindow(window)).register(registry).increment();
    }

    private static String week(Instant at) {
        var date = at.atZone(ZoneOffset.UTC);
        var fields = WeekFields.ISO;
        return String.format(Locale.ROOT, "w%04d-%02d", date.get(fields.weekBasedYear()), date.get(fields.weekOfWeekBasedYear()));
    }

    private static String boundedWindow(String value) {
        return value.matches("w\\d{4}-(?:0[1-9]|[1-4]\\d|5[0-3])") || value.equals("offset1") || value.equals("offset7") || value.equals("offset30") ? value : "all";
    }
    private static String quality(String value) { return bounded(value, "UNKNOWN", "PASS", "REJECT"); }
    private static boolean isStudio(String value) { return "STUDIO".equals(bounded(value, "UNKNOWN", "STUDIO", "DIRECT")); }
    private static boolean isShare(String value) { return "SHARE".equals(bounded(value, "UNKNOWN", "SHARE", "STUDIO", "DIRECT")); }
    private static String bounded(String value, String unknown, String... allowed) {
        if (value == null) return unknown;
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (String candidate : allowed) if (candidate.equals(normalized)) return candidate;
        return unknown;
    }
}
