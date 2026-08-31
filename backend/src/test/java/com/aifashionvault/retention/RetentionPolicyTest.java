package com.aifashionvault.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RetentionPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final RetentionPolicy POLICY = new RetentionPolicy(Duration.ofHours(24), Clock.fixed(NOW, ZoneOffset.UTC));
    @Test void cutoffIsInclusiveAndClockDeterministic() {
        assertThat(POLICY.cutoff(NOW)).isEqualTo(Instant.parse("2026-08-31T00:00:00Z"));
        assertThat(POLICY.eligible(new RetentionCandidate(RetentionClass.TEMPORARY_GENERATION_INPUT, "x", POLICY.cutoff(NOW)), POLICY.cutoff(NOW))).isTrue();
        assertThat(POLICY.eligible(new RetentionCandidate(RetentionClass.FAILED_INTERMEDIATE, "x", POLICY.cutoff(NOW).plusNanos(1)), POLICY.cutoff(NOW))).isFalse();
        assertThat(POLICY.eligible(new RetentionCandidate(RetentionClass.IMPORT_STAGING, "x", NOW.plusSeconds(1)), POLICY.cutoff(NOW))).isFalse();
    }
    @Test void unknownAgeIsNeverEligible() {
        assertThat(POLICY.eligible(new RetentionCandidate(RetentionClass.IMPORT_STAGING, "opaque", null), POLICY.cutoff(NOW))).isFalse();
    }
    @Test void onlyPositiveAgesAreAccepted() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RetentionPolicy(Duration.ZERO, Clock.systemUTC()));
        assertThatIllegalArgumentException().isThrownBy(() -> new RetentionPolicy(Duration.ofSeconds(-1), Clock.systemUTC()));
    }
    @Test void classIsClosedToTheThreeTemporaryValues() { assertThat(RetentionClass.values()).containsExactly(RetentionClass.TEMPORARY_GENERATION_INPUT, RetentionClass.FAILED_INTERMEDIATE, RetentionClass.IMPORT_STAGING); }
}
