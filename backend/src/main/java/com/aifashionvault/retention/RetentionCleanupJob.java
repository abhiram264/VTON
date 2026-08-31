package com.aifashionvault.retention;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

public final class RetentionCleanupJob {
    private final RetentionInventory inventory;
    private final RetentionPolicy policy;
    public RetentionCleanupJob(RetentionInventory inventory, RetentionPolicy policy) {
        if (inventory == null || policy == null) throw new IllegalArgumentException("inventory and policy are required");
        this.inventory = inventory; this.policy = policy;
    }
    public RetentionRunReport run() {
        Instant now = policy.now();
        Instant cutoff = policy.cutoff(now);
        var counts = new EnumMap<RetentionClass, Map<RetentionOutcome, Long>>(RetentionClass.class);
        for (var kind : RetentionClass.values()) {
            var c = new EnumMap<RetentionOutcome, Long>(RetentionOutcome.class);
            for (var outcome : RetentionOutcome.values()) c.put(outcome, 0L);
            try {
                var candidates = inventory.list(kind);
                if (candidates == null) throw new IllegalStateException("inventory returned null");
                for (var candidate : candidates) {
                    increment(c, RetentionOutcome.SCANNED);
                    if (candidate == null || candidate.retentionClass() != kind) continue;
                    if (candidate.lastModified() == null) { increment(c, RetentionOutcome.UNKNOWN_AGE); continue; }
                    if (!policy.eligible(candidate, cutoff)) { increment(c, RetentionOutcome.TOO_YOUNG); continue; }
                    try {
                        var result = inventory.delete(candidate);
                        if (result == DeleteOutcome.DELETED || result == DeleteOutcome.ALREADY_ABSENT) increment(c, RetentionOutcome.DELETED);
                        else increment(c, RetentionOutcome.DELETE_FAILED);
                    } catch (RuntimeException ex) { increment(c, RetentionOutcome.DELETE_FAILED); }
                }
            } catch (RuntimeException ex) { increment(c, RetentionOutcome.LIST_FAILED); }
            counts.put(kind, c);
        }
        return new RetentionRunReport(now, cutoff, counts);
    }
    private static void increment(Map<RetentionOutcome, Long> counts, RetentionOutcome outcome) { counts.merge(outcome, 1L, Long::sum); }
}
