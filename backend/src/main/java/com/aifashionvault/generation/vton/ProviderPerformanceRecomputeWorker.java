package com.aifashionvault.generation.vton;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Hourly derived-rollup self-heal (slice-73 D1/D7). Recomputes the trailing
 * three UTC days plus today, so late or corrected {@code generation_attempt}
 * / {@code quality_result} facts are folded into
 * {@code generation_provider_performance_daily} without any write-path hook;
 * the attempt tables stay the append-only source of truth.
 *
 * <p>{@code recompute} is a deterministic whole-day replace, so overlapping
 * windows from consecutive ticks or concurrent instances write identical
 * content. Failures are caught and logged at WARNING with the exception's
 * {@code toString()} only (no user ids, no cost values); the next tick
 * retries the full window. When no store bean exists (no DataSource in the
 * context) the tick is a no-op.</p>
 *
 * <p>Deliberately non-final with a public scheduled method: full application
 * contexts CGLIB-subclass scheduled beans, which cannot subclass a final
 * class or override a method invisible to the subclass package.</p>
 */
public class ProviderPerformanceRecomputeWorker {

	/** Fixed delay between recompute ticks. */
	static final long RECOMPUTE_FIXED_DELAY_SECONDS = 3600L;
	/** Delay before the first recompute tick. */
	static final long RECOMPUTE_INITIAL_DELAY_SECONDS = 300L;
	/** Whole UTC days behind today that every tick recomputes. */
	static final int TRAILING_DAYS = 3;

	private static final Logger log = LoggerFactory.getLogger(ProviderPerformanceRecomputeWorker.class);

	private final ObjectProvider<JdbcProviderPerformanceStore> stores;
	private final Clock clock;

	public ProviderPerformanceRecomputeWorker(ObjectProvider<JdbcProviderPerformanceStore> stores, Clock clock) {
		this.stores = Objects.requireNonNull(stores, "stores");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	@Scheduled(
		initialDelay = RECOMPUTE_INITIAL_DELAY_SECONDS,
		fixedDelay = RECOMPUTE_FIXED_DELAY_SECONDS,
		timeUnit = TimeUnit.SECONDS)
	public void scheduledRecompute() {
		JdbcProviderPerformanceStore store = this.stores.getIfAvailable();
		if (store == null) {
			return;
		}
		try {
			recomputeTrailingWindow(store);
		}
		catch (RuntimeException ex) {
			log.warn("provider performance recompute failed: {}", ex.toString());
		}
	}

	/** Recomputes [startOfDay(now) - 3d, startOfDay(now) + 1d) (UTC). */
	void recomputeTrailingWindow(ProviderPerformanceStore store) {
		LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
		Instant from = today.minusDays(TRAILING_DAYS).atStartOfDay().toInstant(ZoneOffset.UTC);
		Instant to = today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
		store.recompute(from, to);
	}
}
