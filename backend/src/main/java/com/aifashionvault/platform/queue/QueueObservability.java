package com.aifashionvault.platform.queue;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;

public final class QueueObservability {

	static final String QUEUE_DEPTH = "queue.depth";
	static final String QUEUE_OLDEST_AGE = "queue.oldest_age";

	private static final Logger log = LoggerFactory.getLogger(QueueObservability.class);

	private final JdbcWorkOutboxStore store;
	private final Clock clock;
	private final AtomicLong depth = new AtomicLong();
	private final AtomicLong oldestAgeSeconds = new AtomicLong();

	public QueueObservability(JdbcWorkOutboxStore store, MeterRegistry registry, Clock clock) {
		this.store = Objects.requireNonNull(store, "store");
		Objects.requireNonNull(registry, "registry");
		this.clock = Objects.requireNonNull(clock, "clock");
		Gauge.builder(QUEUE_DEPTH, depth, AtomicLong::doubleValue)
			.description("Durable generation outbox rows not yet sent")
			.register(registry);
		TimeGauge.builder(QUEUE_OLDEST_AGE, oldestAgeSeconds, TimeUnit.SECONDS, AtomicLong::doubleValue)
			.description("Age of the oldest unsent generation outbox row")
			.register(registry);
	}

	@Scheduled(fixedDelay = 5000)
	void refresh() {
		try {
			int unsent = store.countUnsent();
			long ageSeconds = store.oldestUnsentCreatedAt()
				.map(oldest -> Math.max(0L, Duration.between(oldest, clock.instant()).toSeconds()))
				.orElse(0L);
			depth.set(unsent);
			oldestAgeSeconds.set(ageSeconds);
		}
		catch (RuntimeException ex) {
			log.info("queue observability refresh failed, keeping previous values: {}", ex.toString());
		}
	}
}
