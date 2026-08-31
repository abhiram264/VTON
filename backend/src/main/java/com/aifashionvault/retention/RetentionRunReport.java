package com.aifashionvault.retention;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

public record RetentionRunReport(Instant startedAt, Instant cutoff, Map<RetentionClass, Map<RetentionOutcome, Long>> counts) {
    public RetentionRunReport {
        var outer = new EnumMap<RetentionClass, Map<RetentionOutcome, Long>>(RetentionClass.class);
        for (var c : RetentionClass.values()) {
            var inner = new EnumMap<RetentionOutcome, Long>(RetentionOutcome.class);
            for (var o : RetentionOutcome.values()) inner.put(o, counts.getOrDefault(c, Map.of()).getOrDefault(o, 0L));
            outer.put(c, Map.copyOf(inner));
        }
        counts = Map.copyOf(outer);
    }
}
