package com.aifashionvault.admission;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.shared.UserId;

/**
 * C19 acceptance: "simultaneous requests cannot overspend the approved limit;
 * rejected/duplicate/failed requests account correctly".
 *
 * <p>The limiter serializes through {@code SELECT ... FOR UPDATE} inside one
 * transaction, but nothing proved that before. These tests run real concurrent
 * callers against real PostgreSQL, because the property under test is the
 * database's row-lock behaviour under contention — a mocked DataSource would
 * assert nothing about the thing that can actually break.</p>
 *
 * <p>Failure here is a real defect (overspend, lost update, or a negative
 * counter), not a flaky test: the assertions are on the invariant, never on
 * which particular caller won.</p>
 */
class JdbcGenerationRateLimiterConcurrencyTest {

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres")
		.withStartupTimeout(Duration.ofMinutes(5));
	private static final TimeZone ORIGINAL_TIME_ZONE = TimeZone.getDefault();
	private static final UserId USER = new UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final Instant REQUESTED_AT = Instant.parse("2026-08-31T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(REQUESTED_AT, ZoneOffset.UTC);
	private static final String DAY = "2026-08-31";

	private JdbcTemplate jdbc;

	@BeforeAll
	static void database() {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		POSTGRES.start();
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();
	}

	@AfterAll
	static void restoreDefaultTimeZone() {
		TimeZone.setDefault(ORIGINAL_TIME_ZONE);
	}

	@BeforeEach
	void reset() {
		jdbc = new JdbcTemplate(new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
		jdbc.update("TRUNCATE generation_admission_reservation, generation_admission_counter");
	}

	@Test
	void concurrentReservationsCannotExceedTheDailyLimit() throws Exception {
		int limit = GenerationRiskTier.ORDINARY.dailyLimit();
		int attempts = limit * 6;
		// One shared bucket key set per caller would serialize on the user row, so
		// every caller gets its own device/IP: the ONLY shared bucket is the user's,
		// which is exactly the one the limit is asserted against.
		List<GenerationRateLimiter.Reservation> reservations = runConcurrently(attempts, index ->
			limiter().reserve(request("device-race-" + index, "198.51.100." + (index % 250 + 1))));

		long allowed = reservations.stream().filter(r -> r.decision().allowed()).count();

		assertThat(allowed)
			.as("more reservations were granted than the tier's daily limit")
			.isEqualTo(limit);
		assertThat(counter("user:" + USER.value()))
			.as("the durable user counter must equal the granted reservations")
			.isEqualTo(limit);
		// Every granted reservation is durably recorded once; every refusal recorded none.
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_admission_reservation", Integer.class))
			.isEqualTo(limit * 3);
	}

	@Test
	void concurrentReleasesRefundEachReservationExactlyOnce() throws Exception {
		int limit = GenerationRiskTier.ORDINARY.dailyLimit();
		JdbcGenerationRateLimiter limiter = limiter();
		List<GenerationRateLimiter.Reservation> granted = IntStream.range(0, limit)
			.mapToObj(index -> limiter.reserve(request("device-refund-" + index, "203.0.113." + (index + 1))))
			.toList();
		assertThat(granted).allSatisfy(reservation -> assertThat(reservation.decision().allowed()).isTrue());
		assertThat(counter("user:" + USER.value())).isEqualTo(limit);

		// Release every reservation twice, concurrently: a refund must be idempotent
		// and must never push the counter below zero.
		runConcurrently(limit * 2, index -> {
			limiter.release(granted.get(index % limit));
			return null;
		});

		assertThat(counter("user:" + USER.value()))
			.as("double release must not refund twice")
			.isZero();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_admission_reservation WHERE released_at IS NULL", Integer.class))
			.isZero();
	}

	@Test
	void concurrentCommitAndReleaseOfTheSameReservationSettleOnce() throws Exception {
		JdbcGenerationRateLimiter limiter = limiter();
		GenerationRateLimiter.Reservation reservation = limiter.reserve(request("device-settle", "203.0.113.9"));
		assertThat(reservation.decision().allowed()).isTrue();

		// A commit and a release racing on the same token: whichever lands first wins
		// the row, and the counter ends at 1 (committed) or 0 (released) — never 2,
		// never negative.
		runConcurrently(2, index -> {
			if (index == 0) {
				limiter.commit(reservation);
			}
			else {
				limiter.release(reservation);
			}
			return null;
		});

		long remaining = counter("user:" + USER.value());
		assertThat(remaining).isBetween(0L, 1L);
	}

	private JdbcGenerationRateLimiter limiter() {
		return new JdbcGenerationRateLimiter(jdbc.getDataSource(), CLOCK);
	}

	private GenerationAdmissionRequest request(String device, String ip) {
		return new GenerationAdmissionRequest(USER, device, ip, GenerationRiskTier.ORDINARY, REQUESTED_AT);
	}

	private long counter(String bucketKey) {
		Long value = jdbc.queryForObject(
			"SELECT count FROM generation_admission_counter WHERE bucket_key = ? AND bucket_day = ?",
			Long.class, bucketKey, java.sql.Date.valueOf(DAY));
		return value == null ? 0L : value;
	}

	/** Runs every task on its own thread, released together, and returns the results. */
	private static <T> List<T> runConcurrently(int tasks, IndexedTask<T> task) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(Math.min(tasks, 16));
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Callable<T>> calls = IntStream.range(0, tasks)
				.mapToObj(index -> (Callable<T>) () -> {
					start.await();
					return task.run(index);
				})
				.toList();
			List<Future<T>> futures = calls.stream().map(pool::submit).toList();
			start.countDown();
			java.util.ArrayList<T> results = new java.util.ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(60, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

	@FunctionalInterface
	private interface IndexedTask<T> {
		T run(int index) throws Exception;
	}
}
