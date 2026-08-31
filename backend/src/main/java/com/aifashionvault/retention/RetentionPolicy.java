package com.aifashionvault.retention;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

public final class RetentionPolicy {
    public static final Duration DEFAULT_MAXIMUM_AGE = Duration.ofHours(24);
    private final Duration maximumAge;
    private final Clock clock;

    public RetentionPolicy() { this(DEFAULT_MAXIMUM_AGE, Clock.systemUTC()); }
    public RetentionPolicy(Duration maximumAge, Clock clock) {
        if (maximumAge == null || maximumAge.isZero() || maximumAge.isNegative()) throw new IllegalArgumentException("maximumAge must be positive");
        this.maximumAge = maximumAge;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }
    public Duration maximumAge() { return maximumAge; }
    public Instant now() { return clock.instant(); }
    public Instant cutoff(Instant now) {
        if (now == null) throw new IllegalArgumentException("now is required");
        return now.minus(maximumAge);
    }
    public boolean eligible(RetentionCandidate candidate, Instant cutoff) {
        if (candidate == null || cutoff == null || candidate.lastModified() == null) return false;
        return !candidate.lastModified().isAfter(cutoff);
    }
}
