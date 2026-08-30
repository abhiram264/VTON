package com.aifashionvault.generation.vton;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public final class AttemptRedeliveryPolicy {
    public static final Duration FIRST_POLL_DELAY = Duration.ofSeconds(3);
    public static final List<Duration> QUEUED_RUNNING_POLL_DELAYS = List.of(
        Duration.ofSeconds(3),
        Duration.ofSeconds(5),
        Duration.ofSeconds(10),
        Duration.ofSeconds(15),
        Duration.ofSeconds(30),
        Duration.ofSeconds(60)
    );
    public static final Duration QUEUED_RUNNING_POLL_CAP = Duration.ofSeconds(60);
    public static final List<Duration> UNAVAILABLE_POLL_DELAYS = List.of(
        Duration.ofSeconds(10),
        Duration.ofSeconds(20),
        Duration.ofSeconds(40),
        Duration.ofSeconds(60)
    );
    public static final Duration UNAVAILABLE_POLL_CAP = Duration.ofSeconds(60);
    public static final List<Duration> REPLAY_SAFE_TRANSMISSION_DELAYS = List.of(
        Duration.ofSeconds(5),
        Duration.ofSeconds(30),
        Duration.ofSeconds(120)
    );
    public static final int MAX_REPLAY_SAFE_TRANSMISSIONS = 3;
    public static final Duration UNSAFE_CALLBACK_RECOVERY_GRACE = Duration.ofMinutes(5);
    public static final Duration PROVIDER_EXECUTION_DEADLINE = Duration.ofMinutes(15);
    public static final Duration JOB_ORCHESTRATION_DEADLINE = Duration.ofMinutes(30);
    public static final double POLL_JITTER_FRACTION = 0.20;
    public static final Duration LOGICAL_RETRY_BASE_DELAY = Duration.ofSeconds(1);
    public static final Duration LOGICAL_RETRY_COMPUTED_CAP = Duration.ofSeconds(30);
    public static final Duration MAX_AUTONOMOUS_RETRY_WAIT = Duration.ofMinutes(5);

    public enum Decision {
        SUBMIT,
        REPLAY_SAME_KEY,
        POLL,
        STOP,
        BLOCK_UNSAFE_REPLAY
    }

    private AttemptRedeliveryPolicy() {
    }

    public static Decision decide(
            boolean terminal,
            Optional<ProviderExecutionId> persistedExecution,
            boolean submissionIndeterminate,
            SubmissionReplaySafety replaySafety) {
        if (persistedExecution == null) {
            throw new IllegalArgumentException("persisted execution optional is required");
        }
        if (replaySafety == null) {
            throw new IllegalArgumentException("submission replay safety is required");
        }
        if (terminal) {
            return Decision.STOP;
        }
        if (persistedExecution.isPresent()) {
            return Decision.POLL;
        }
        if (submissionIndeterminate && replaySafety == SubmissionReplaySafety.SAME_KEY_SAFE) {
            return Decision.REPLAY_SAME_KEY;
        }
        if (submissionIndeterminate) {
            return Decision.BLOCK_UNSAFE_REPLAY;
        }
        return Decision.SUBMIT;
    }

    public static Duration queuedOrRunningPollDelay(int completedPolls) {
        return delayAt(completedPolls, QUEUED_RUNNING_POLL_DELAYS, QUEUED_RUNNING_POLL_CAP, "completed polls");
    }

    public static Duration unavailablePollDelay(int unavailableObservations) {
        return delayAt(unavailableObservations, UNAVAILABLE_POLL_DELAYS, UNAVAILABLE_POLL_CAP, "unavailable observations");
    }

    public static Duration replaySafeRetransmissionDelay(int completedTransmissions) {
        if (completedTransmissions < 0) {
            throw new IllegalArgumentException("completed transmissions must be non-negative");
        }
        if (completedTransmissions >= REPLAY_SAFE_TRANSMISSION_DELAYS.size()) {
            throw new IllegalArgumentException("replay-safe transmission budget exhausted");
        }
        return REPLAY_SAFE_TRANSMISSION_DELAYS.get(completedTransmissions);
    }

    public static boolean replaySafeRetransmissionAllowed(int transmissionCount) {
        if (transmissionCount < 0) {
            throw new IllegalArgumentException("transmission count must be non-negative");
        }
        return transmissionCount < MAX_REPLAY_SAFE_TRANSMISSIONS;
    }

    public static boolean replaySafeRetransmissionAllowed(
            int transmissionCount,
            boolean acceptanceKnown,
            SubmissionReplaySafety capability,
            Instant submissionRecoveryDeadlineAt,
            Instant orchestrationDeadlineAt,
            Instant currentInstant,
            boolean lifecycleActive) {
        if (transmissionCount < 0) {
            throw new IllegalArgumentException("transmission count must be non-negative");
        }
        if (capability == null) {
            throw new IllegalArgumentException("submission replay safety is required");
        }
        if (submissionRecoveryDeadlineAt == null) {
            throw new IllegalArgumentException("submission recovery deadline is required");
        }
        if (orchestrationDeadlineAt == null) {
            throw new IllegalArgumentException("orchestration deadline is required");
        }
        if (currentInstant == null) {
            throw new IllegalArgumentException("current instant is required");
        }
        if (submissionRecoveryDeadlineAt.isAfter(orchestrationDeadlineAt)) {
            throw new IllegalArgumentException("submission recovery deadline must not exceed orchestration deadline");
        }
        return lifecycleActive
            && !acceptanceKnown
            && capability == SubmissionReplaySafety.SAME_KEY_SAFE
            && transmissionCount < MAX_REPLAY_SAFE_TRANSMISSIONS
            && currentInstant.isBefore(submissionRecoveryDeadlineAt)
            && currentInstant.isBefore(orchestrationDeadlineAt);
    }

    public static Duration logicalRetryDelay(int retryOrdinal, double randomFraction) {
        if (retryOrdinal < 1) {
            throw new IllegalArgumentException("retry ordinal must be >= 1");
        }
        if (!Double.isFinite(randomFraction) || randomFraction < 0.0 || randomFraction > 1.0) {
            throw new IllegalArgumentException("random fraction must be finite and between 0 and 1");
        }
        long boundSeconds = retryOrdinal >= 6
            ? LOGICAL_RETRY_COMPUTED_CAP.toSeconds()
            : LOGICAL_RETRY_BASE_DELAY.toSeconds() << (retryOrdinal - 1);
        Duration bound = Duration.ofSeconds(Math.min(boundSeconds, LOGICAL_RETRY_COMPUTED_CAP.toSeconds()));
        long jitterNanos = (long) Math.floor(bound.toNanos() * randomFraction);
        return Duration.ofNanos(Math.min(jitterNanos, MAX_AUTONOMOUS_RETRY_WAIT.toNanos()));
    }

    public static boolean failUnsafeAmbiguityPromptly(boolean callbackRecoveryExists, Duration elapsedSinceOwnership) {
        if (elapsedSinceOwnership == null) {
            throw new IllegalArgumentException("elapsed ownership duration is required");
        }
        if (elapsedSinceOwnership.isNegative()) {
            throw new IllegalArgumentException("elapsed ownership duration must be non-negative");
        }
        if (!callbackRecoveryExists) {
            return true;
        }
        return elapsedSinceOwnership.compareTo(UNSAFE_CALLBACK_RECOVERY_GRACE) >= 0;
    }

    private static Duration delayAt(int index, List<Duration> schedule, Duration cap, String label) {
        if (index < 0) {
            throw new IllegalArgumentException(label + " must be non-negative");
        }
        if (index >= schedule.size()) {
            return cap;
        }
        return schedule.get(index);
    }
}
