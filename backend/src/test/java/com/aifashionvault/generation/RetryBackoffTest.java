package com.aifashionvault.generation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Deterministic timing math for full-jitter backoff (Q26), Retry-After
 * combination (Q27), and bounded scheduling (Q28). Fixed instants only; no
 * sleep, no wall clock.
 */
class RetryBackoffTest {

	private static final Instant NOW = Instant.parse("2026-08-28T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final Duration FIVE_MINUTES = Duration.ofMinutes(5);
	private static final Instant DEADLINE = NOW.plus(Duration.ofMinutes(30));

	@Test
	void fullJitterBoundariesFollowTheLocalCapSequence() {
		// localCap = min(30s, 1s * 2^(n-1)) for n = 1..7
		Duration[] localCaps = {
			Duration.ofSeconds(1),
			Duration.ofSeconds(2),
			Duration.ofSeconds(4),
			Duration.ofSeconds(8),
			Duration.ofSeconds(16),
			Duration.ofSeconds(30),
			Duration.ofSeconds(30)
		};
		for (int ordinal = 1; ordinal <= localCaps.length; ordinal++) {
			Duration localCap = localCaps[ordinal - 1];

			assertThat(RetryBackoff.computedJitterDelay(ordinal, 0.0))
				.as("ordinal %d fraction 0", ordinal)
				.isZero();
			assertThat(RetryBackoff.computedJitterDelay(ordinal, 1.0))
				.as("ordinal %d fraction 1", ordinal)
				.isEqualTo(localCap);
			assertThat(RetryBackoff.computedJitterDelay(ordinal, 0.5))
				.as("ordinal %d fraction 0.5", ordinal)
				.isEqualTo(Duration.ofMillis(localCap.toMillis() / 2));
		}
	}

	@Test
	void computedJitterDelayRejectsBadOrdinalsAndFractions() {
		assertThatIllegalArgumentException().isThrownBy(() -> RetryBackoff.computedJitterDelay(0, 0.5));
		assertThatIllegalArgumentException().isThrownBy(() -> RetryBackoff.computedJitterDelay(1, -0.1));
		assertThatIllegalArgumentException().isThrownBy(() -> RetryBackoff.computedJitterDelay(1, 1.1));
		assertThatIllegalArgumentException().isThrownBy(() -> RetryBackoff.computedJitterDelay(1, Double.NaN));
	}

	@Test
	void effectiveDelayTakesTheMaximumOfComputedAndRetryAfter() {
		Duration computed = Duration.ofSeconds(5);

		assertThat(RetryBackoff.effectiveDelay(computed, Duration.ofSeconds(120)))
			.isEqualTo(Duration.ofSeconds(120));
		assertThat(RetryBackoff.effectiveDelay(computed, Duration.ofSeconds(2)))
			.isEqualTo(computed);
		assertThat(RetryBackoff.effectiveDelay(computed, null))
			.isEqualTo(computed);
		assertThat(RetryBackoff.effectiveDelay(computed, Duration.ofSeconds(-1)))
			.as("negative Retry-After falls back to the computed delay")
			.isEqualTo(computed);
	}

	@Test
	void effectiveDelayRejectsNullOrNegativeComputedDelay() {
		assertThatIllegalArgumentException().isThrownBy(() -> RetryBackoff.effectiveDelay(null, null));
		assertThatIllegalArgumentException().isThrownBy(
			() -> RetryBackoff.effectiveDelay(Duration.ofSeconds(-1), null));
	}

	@Test
	void scheduleAtClampsDelayToTheFiveMinutePerWaitCeiling() {
		Instant scheduled = RetryBackoff.scheduleAt(
			CLOCK, Duration.ofMinutes(10), DEADLINE, FIVE_MINUTES);

		assertThat(scheduled).isEqualTo(NOW.plus(FIVE_MINUTES));
	}

	@Test
	void scheduleAtHonorsAShorterMaxAutonomousWait() {
		Instant scheduled = RetryBackoff.scheduleAt(
			CLOCK, Duration.ofMinutes(10), DEADLINE, Duration.ofSeconds(45));

		assertThat(scheduled).isEqualTo(NOW.plusSeconds(45));
	}

	@Test
	void scheduleAtReturnsNullWhenDelayCannotFitBeforeTheDeadline() {
		Instant deadline = NOW.plus(Duration.ofSeconds(30));

		Instant scheduled = RetryBackoff.scheduleAt(CLOCK, Duration.ofSeconds(60), deadline, FIVE_MINUTES);

		assertThat(scheduled).isNull();
	}

	@Test
	void scheduleAtReturnsNullWhenTheDeadlineHasAlreadyPassed() {
		assertThat(RetryBackoff.scheduleAt(CLOCK, Duration.ofSeconds(1), NOW, FIVE_MINUTES)).isNull();
		assertThat(RetryBackoff.scheduleAt(CLOCK, Duration.ofSeconds(1), NOW.minusSeconds(1), FIVE_MINUTES))
			.isNull();
	}

	@Test
	void scheduleAtReturnsTheExactInstantForAnUnclampedDelay() {
		Instant scheduled = RetryBackoff.scheduleAt(
			CLOCK, Duration.ofSeconds(90), DEADLINE, FIVE_MINUTES);

		assertThat(scheduled).isEqualTo(NOW.plusSeconds(90));
	}

	@Test
	void scheduleAtRejectsInvalidArguments() {
		assertThatIllegalArgumentException().isThrownBy(
			() -> RetryBackoff.scheduleAt(null, Duration.ZERO, DEADLINE, FIVE_MINUTES));
		assertThatIllegalArgumentException().isThrownBy(
			() -> RetryBackoff.scheduleAt(CLOCK, null, DEADLINE, FIVE_MINUTES));
		assertThatIllegalArgumentException().isThrownBy(
			() -> RetryBackoff.scheduleAt(CLOCK, Duration.ofSeconds(-1), DEADLINE, FIVE_MINUTES));
		assertThatIllegalArgumentException().isThrownBy(
			() -> RetryBackoff.scheduleAt(CLOCK, Duration.ZERO, null, FIVE_MINUTES));
		assertThatIllegalArgumentException().isThrownBy(
			() -> RetryBackoff.scheduleAt(CLOCK, Duration.ZERO, DEADLINE, null));
		assertThatIllegalArgumentException().isThrownBy(
			() -> RetryBackoff.scheduleAt(CLOCK, Duration.ZERO, DEADLINE, FIVE_MINUTES.plusSeconds(1)));
	}
}
