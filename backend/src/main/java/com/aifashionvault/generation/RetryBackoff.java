package com.aifashionvault.generation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Deterministic timing math for capped exponential full jitter backoff (Q26),
 * provider Retry-After handling (Q27), and bounded scheduling inside the
 * five-minute maximum autonomous wait and the orchestration deadline (Q28).
 *
 * <p>Everything here is pure: the retry ordinal and the injected jitter
 * fraction fully determine the locally computed delay; a {@link Clock} and
 * the supplied bounds fully determine the scheduled instant. No wall-clock
 * sleeping or randomness ever participates.</p>
 */
public final class RetryBackoff {

	/** Full-jitter backoff base (Q26). */
	public static final Duration BACKOFF_BASE = Duration.ofSeconds(1);
	/** Local cap on the jitter-computed component (Q26). */
	public static final Duration COMPUTED_BACKOFF_CAP = Duration.ofSeconds(30);
	/** Per-wait ceiling for any autonomous delay; never extends the orchestration deadline (Q27/Q28). */
	public static final Duration MAX_AUTONOMOUS_WAIT = Duration.ofMinutes(5);

	private RetryBackoff() {
	}

	/**
	 * Computes the full-jitter delay for retry ordinal {@code n} starting at
	 * one (Q26): localCap = min(30s, 1s * 2^(n-1)); the delay is uniform in
	 * [0, localCap] and is selected deterministically as
	 * {@code floor(localCap * fraction)}.
	 *
	 * @param retryOrdinal one-based ordinal of the retry being scheduled
	 * @param randomFraction deterministic jitter draw in [0, 1]
	 * @return the locally computed delay, never exceeding the 30-second cap
	 */
	public static Duration computedJitterDelay(int retryOrdinal, double randomFraction) {
		if (retryOrdinal < 1) {
			throw new IllegalArgumentException("retryOrdinal must be >= 1");
		}
		if (!Double.isFinite(randomFraction) || randomFraction < 0.0 || randomFraction > 1.0) {
			throw new IllegalArgumentException("random fraction must be finite and between 0 and 1");
		}
		long localCapSeconds = retryOrdinal >= 6
			? COMPUTED_BACKOFF_CAP.toSeconds()
			: BACKOFF_BASE.toSeconds() << (retryOrdinal - 1);
		Duration localCap = Duration.ofSeconds(Math.min(localCapSeconds, COMPUTED_BACKOFF_CAP.toSeconds()));
		long jitterNanos = (long) Math.floor(localCap.toNanos() * randomFraction);
		return Duration.ofNanos(Math.min(jitterNanos, localCap.toNanos()));
	}

	/**
	 * Combines the locally computed jitter delay with a valid provider
	 * Retry-After minimum (Q27): {@code effectiveDelay =
	 * max(computedJitterDelay, retryAfterMinimum)}.
	 *
	 * <p>The 30-second cap applies only to the locally computed component;
	 * the provider minimum may exceed it. Invalid, negative, or past
	 * Retry-After values fall back to the computed delay and therefore must
	 * be represented as a null {@code retryAfterMinimum} by the caller.</p>
	 *
	 * @param computedJitter delay produced by {@link #computedJitterDelay(int, double)}
	 * @param retryAfterMinimum parsed provider minimum wait, or null when absent or invalid
	 * @return the effective delay before applying the per-wait and deadline bounds
	 */
	public static Duration effectiveDelay(Duration computedJitter, Duration retryAfterMinimum) {
		if (computedJitter == null) {
			throw new IllegalArgumentException("computedJitter is required");
		}
		if (computedJitter.isNegative()) {
			throw new IllegalArgumentException("computedJitter must not be negative");
		}
		if (retryAfterMinimum == null || retryAfterMinimum.isNegative()) {
			return computedJitter;
		}
		return retryAfterMinimum.compareTo(computedJitter) > 0 ? retryAfterMinimum : computedJitter;
	}

	/**
	 * Schedules the next wake-up for an effective delay (Q27/Q28).
	 *
	 * <p>The delay is clamped to the five-minute per-wait ceiling, which
	 * never extends the orchestration deadline. When the resulting instant
	 * would reach past {@code orchestrationDeadlineAt}, no legal schedule
	 * exists and null is returned; the caller then owns the resulting
	 * BACKOFF_FIT denial. An {@code orchestrationDeadlineAt} that is already
	 * at or before the current instant likewise yields null.</p>
	 *
	 * @param clock injected clock providing the current instant
	 * @param delay effective delay, never negative
	 * @param orchestrationDeadlineAt hard stop for new provider work (Q28)
	 * @param maxAutonomousWait per-wait ceiling, never exceeding five minutes
	 * @return the absolute instant to resume, or null when no schedule fits
	 */
	public static Instant scheduleAt(
		Clock clock,
		Duration delay,
		Instant orchestrationDeadlineAt,
		Duration maxAutonomousWait) {
		if (clock == null) {
			throw new IllegalArgumentException("clock is required");
		}
		if (delay == null) {
			throw new IllegalArgumentException("delay is required");
		}
		if (delay.isNegative()) {
			throw new IllegalArgumentException("delay must not be negative");
		}
		if (orchestrationDeadlineAt == null) {
			throw new IllegalArgumentException("orchestrationDeadlineAt is required");
		}
		if (maxAutonomousWait == null) {
			throw new IllegalArgumentException("maxAutonomousWait is required");
		}
		if (maxAutonomousWait.isNegative()) {
			throw new IllegalArgumentException("maxAutonomousWait must not be negative");
		}
		if (maxAutonomousWait.compareTo(MAX_AUTONOMOUS_WAIT) > 0) {
			throw new IllegalArgumentException("maxAutonomousWait exceeds the five-minute ceiling");
		}
		Instant now = clock.instant();
		if (!orchestrationDeadlineAt.isAfter(now)) {
			return null;
		}
		Duration clamped = delay.compareTo(maxAutonomousWait) > 0 ? maxAutonomousWait : delay;
		Instant scheduled = now.plus(clamped);
		return scheduled.isAfter(orchestrationDeadlineAt) ? null : scheduled;
	}
}
