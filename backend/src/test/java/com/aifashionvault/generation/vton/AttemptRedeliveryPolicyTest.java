package com.aifashionvault.generation.vton;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AttemptRedeliveryPolicyTest {
    private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");
    private static final Instant RECOVERY_DEADLINE = NOW.plusSeconds(30);
    private static final Instant ORCHESTRATION_DEADLINE = NOW.plus(Duration.ofMinutes(30));

    @Test
    void admissionRequiresActiveSafeUnacceptedAttemptBeforeBothDeadlines() {
        for (int transmissionCount = 0; transmissionCount < AttemptRedeliveryPolicy.MAX_REPLAY_SAFE_TRANSMISSIONS; transmissionCount++) {
            assertThat(admission(transmissionCount, false, SubmissionReplaySafety.SAME_KEY_SAFE, NOW, true)).isTrue();
        }

        assertThat(admission(AttemptRedeliveryPolicy.MAX_REPLAY_SAFE_TRANSMISSIONS, false, SubmissionReplaySafety.SAME_KEY_SAFE, NOW, true)).isFalse();
        assertThat(admission(0, true, SubmissionReplaySafety.SAME_KEY_SAFE, NOW, true)).isFalse();
        assertThat(admission(0, false, SubmissionReplaySafety.UNSAFE, NOW, true)).isFalse();
        assertThat(admission(0, false, SubmissionReplaySafety.SAME_KEY_SAFE, NOW, false)).isFalse();
        assertThat(admission(0, false, SubmissionReplaySafety.SAME_KEY_SAFE, RECOVERY_DEADLINE, true)).isFalse();
        assertThat(admission(0, false, SubmissionReplaySafety.SAME_KEY_SAFE, ORCHESTRATION_DEADLINE, true)).isFalse();
        assertThat(admission(0, false, SubmissionReplaySafety.SAME_KEY_SAFE, ORCHESTRATION_DEADLINE.plusNanos(1), true)).isFalse();
    }

    @Test
    void admissionRejectsInvalidInputsAndInconsistentDeadlines() {
        assertThatIllegalArgumentException().isThrownBy(
            () -> admission(-1, false, SubmissionReplaySafety.SAME_KEY_SAFE, NOW, true));
        assertThatIllegalArgumentException().isThrownBy(
            () -> admission(0, false, null, NOW, true));
        assertThatIllegalArgumentException().isThrownBy(
            () -> AttemptRedeliveryPolicy.replaySafeRetransmissionAllowed(
                0, false, SubmissionReplaySafety.SAME_KEY_SAFE, null, ORCHESTRATION_DEADLINE, NOW, true));
        assertThatIllegalArgumentException().isThrownBy(
            () -> AttemptRedeliveryPolicy.replaySafeRetransmissionAllowed(
                0, false, SubmissionReplaySafety.SAME_KEY_SAFE, RECOVERY_DEADLINE, null, NOW, true));
        assertThatIllegalArgumentException().isThrownBy(
            () -> AttemptRedeliveryPolicy.replaySafeRetransmissionAllowed(
                0, false, SubmissionReplaySafety.SAME_KEY_SAFE, RECOVERY_DEADLINE, ORCHESTRATION_DEADLINE, null, true));
        assertThatIllegalArgumentException().isThrownBy(
            () -> AttemptRedeliveryPolicy.replaySafeRetransmissionAllowed(
                0, false, SubmissionReplaySafety.SAME_KEY_SAFE, ORCHESTRATION_DEADLINE.plusNanos(1), ORCHESTRATION_DEADLINE, NOW, true));
    }

    @Test
    void logicalRetryDelayUsesDeterministicFullJitterWithinCappedBounds() {
        assertThat(AttemptRedeliveryPolicy.LOGICAL_RETRY_BASE_DELAY).isEqualTo(Duration.ofSeconds(1));
        assertThat(AttemptRedeliveryPolicy.LOGICAL_RETRY_COMPUTED_CAP).isEqualTo(Duration.ofSeconds(30));
        assertThat(AttemptRedeliveryPolicy.MAX_AUTONOMOUS_RETRY_WAIT).isEqualTo(Duration.ofMinutes(5));

        assertThat(AttemptRedeliveryPolicy.logicalRetryDelay(1, 0.0)).isEqualTo(Duration.ZERO);
        assertThat(AttemptRedeliveryPolicy.logicalRetryDelay(1, 0.5)).isEqualTo(Duration.ofMillis(500));
        assertThat(AttemptRedeliveryPolicy.logicalRetryDelay(2, 1.0)).isEqualTo(Duration.ofSeconds(2));
        assertThat(AttemptRedeliveryPolicy.logicalRetryDelay(6, 0.5)).isEqualTo(Duration.ofSeconds(15));
        assertThat(AttemptRedeliveryPolicy.logicalRetryDelay(Integer.MAX_VALUE, 1.0))
            .isEqualTo(AttemptRedeliveryPolicy.LOGICAL_RETRY_COMPUTED_CAP);

        for (int ordinal = 1; ordinal <= 32; ordinal++) {
            Duration bound = ordinal < 6
                ? Duration.ofSeconds(1L << (ordinal - 1))
                : AttemptRedeliveryPolicy.LOGICAL_RETRY_COMPUTED_CAP;
            Duration delay = AttemptRedeliveryPolicy.logicalRetryDelay(ordinal, 0.37);
            assertThat(delay).isBetween(Duration.ZERO, bound);
            assertThat(delay).isLessThanOrEqualTo(AttemptRedeliveryPolicy.MAX_AUTONOMOUS_RETRY_WAIT);
        }
    }

    @Test
    void logicalRetryDelayRejectsInvalidOrdinalAndRandomFraction() {
        assertThatIllegalArgumentException().isThrownBy(() -> AttemptRedeliveryPolicy.logicalRetryDelay(0, 0.5));
        assertThatIllegalArgumentException().isThrownBy(() -> AttemptRedeliveryPolicy.logicalRetryDelay(1, -0.01));
        assertThatIllegalArgumentException().isThrownBy(() -> AttemptRedeliveryPolicy.logicalRetryDelay(1, 1.01));
        assertThatIllegalArgumentException().isThrownBy(() -> AttemptRedeliveryPolicy.logicalRetryDelay(1, Double.NaN));
        assertThatIllegalArgumentException().isThrownBy(() -> AttemptRedeliveryPolicy.logicalRetryDelay(1, Double.POSITIVE_INFINITY));
    }

    private static boolean admission(
        int transmissionCount,
        boolean acceptanceKnown,
        SubmissionReplaySafety capability,
        Instant now,
        boolean lifecycleActive) {
        return AttemptRedeliveryPolicy.replaySafeRetransmissionAllowed(
            transmissionCount,
            acceptanceKnown,
            capability,
            RECOVERY_DEADLINE,
            ORCHESTRATION_DEADLINE,
            now,
            lifecycleActive);
    }
}
