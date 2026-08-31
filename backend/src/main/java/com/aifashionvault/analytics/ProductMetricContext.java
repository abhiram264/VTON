package com.aifashionvault.analytics;

import java.math.BigDecimal;
import java.time.Instant;

public record ProductMetricContext(
        Instant occurredAt,
        String outcome,
        String mode,
        String qualityDecision,
        String provider,
        String model,
        Long durationMillis,
        Long itemCount,
        BigDecimal cost) {
}
