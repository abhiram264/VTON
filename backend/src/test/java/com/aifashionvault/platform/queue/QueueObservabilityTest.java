package com.aifashionvault.platform.queue;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class QueueObservabilityTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-04-02T12:00:00Z");
	private static final Duration OLDEST_AGE = Duration.ofMillis(40_123);
	private static final Random ZERO_JITTER = new Random() {
		@Override
		public double nextDouble() {
			return 0.0d;
		}
	};

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	private static DriverManagerDataSource dataSource;
	private static JdbcTemplate jdbc;

	private SimpleMeterRegistry registry;
	private SettableClock clock;

	@BeforeAll
	static void startDatabaseAndMigrate() {
		POSTGRES.start();
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();
		dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword());
		jdbc = new JdbcTemplate(dataSource);
	}

	@BeforeEach
	void setUp() {
		jdbc.update("TRUNCATE TABLE generation_work_outbox, generation_job, users CASCADE");
		registry = new SimpleMeterRegistry();
		clock = new SettableClock(NOW);
	}

	@Test
	void registersGaugesAtZeroBeforeAnyRefresh() {
		new QueueObservability(store(), registry, clock);

		assertThat(gauge(QueueObservability.QUEUE_DEPTH)).isEqualTo(0.0d);
		assertThat(timeGauge(QueueObservability.QUEUE_OLDEST_AGE).baseTimeUnit()).isEqualTo(TimeUnit.SECONDS);
		assertThat(timeGauge(QueueObservability.QUEUE_OLDEST_AGE).value()).isEqualTo(0.0d);
	}

	@Test
	void refreshPublishesDepthAndOldestAgeInSeconds() {
		insertSentRow(NOW.minusSeconds(120));
		insertUnsentRow(NOW);
		insertUnsentRow(NOW.minus(OLDEST_AGE));
		QueueObservability observability = new QueueObservability(store(), registry, clock);

		observability.refresh();

		assertThat(gauge(QueueObservability.QUEUE_DEPTH)).isEqualTo(2.0d);
		assertThat(timeGauge(QueueObservability.QUEUE_OLDEST_AGE).value(TimeUnit.SECONDS)).isEqualTo(40.0d);
	}

	@Test
	void emptyOutboxReadsZeroDepthAndAge() {
		QueueObservability observability = new QueueObservability(store(), registry, clock);

		observability.refresh();

		assertThat(gauge(QueueObservability.QUEUE_DEPTH)).isEqualTo(0.0d);
		assertThat(timeGauge(QueueObservability.QUEUE_OLDEST_AGE).value(TimeUnit.SECONDS)).isEqualTo(0.0d);
	}

	@Test
	void advancingClockWithoutRefreshKeepsLastSample() {
		insertUnsentRow(NOW.minus(OLDEST_AGE));
		QueueObservability observability = new QueueObservability(store(), registry, clock);
		observability.refresh();
		assertThat(timeGauge(QueueObservability.QUEUE_OLDEST_AGE).value(TimeUnit.SECONDS)).isEqualTo(40.0d);

		clock.advance(Duration.ofSeconds(30));

		assertThat(timeGauge(QueueObservability.QUEUE_OLDEST_AGE).value(TimeUnit.SECONDS)).isEqualTo(40.0d);

		observability.refresh();

		assertThat(timeGauge(QueueObservability.QUEUE_OLDEST_AGE).value(TimeUnit.SECONDS))
			.isEqualTo(70.0d);
	}

	@Test
	void futureCreatedAtClampsAgeToZero() {
		insertUnsentRow(NOW.plusSeconds(5));
		QueueObservability observability = new QueueObservability(store(), registry, clock);

		observability.refresh();

		assertThat(gauge(QueueObservability.QUEUE_DEPTH)).isEqualTo(1.0d);
		assertThat(timeGauge(QueueObservability.QUEUE_OLDEST_AGE).value(TimeUnit.SECONDS)).isEqualTo(0.0d);
	}

	@Test
	void storeFailureRetainsPreviousValuesAndThrowsNothing() {
		insertUnsentRow(NOW.minus(OLDEST_AGE));
		FailingDataSource flaky = new FailingDataSource(dataSource);
		QueueObservability observability =
			new QueueObservability(new JdbcWorkOutboxStore(flaky, clock, ZERO_JITTER), registry, clock);
		observability.refresh();
		assertThat(gauge(QueueObservability.QUEUE_DEPTH)).isEqualTo(1.0d);

		flaky.fail();

		assertThatCode(observability::refresh).doesNotThrowAnyException();
		assertThat(gauge(QueueObservability.QUEUE_DEPTH)).isEqualTo(1.0d);
		assertThat(timeGauge(QueueObservability.QUEUE_OLDEST_AGE).value(TimeUnit.SECONDS)).isEqualTo(40.0d);
	}

	private JdbcWorkOutboxStore store() {
		return new JdbcWorkOutboxStore(dataSource, clock, ZERO_JITTER);
	}

	private double gauge(String name) {
		return registry.get(name).gauge().value();
	}

	private TimeGauge timeGauge(String name) {
		return registry.get(name).timeGauge();
	}

	private void insertSentRow(Instant createdAt) {
		JdbcWorkOutboxStore inserting = store();
		UUID jobId = insertJob();
		inserting.insertSuccessor(jobId, NOW);
		WorkOutboxStore.Claim claim = inserting.claimNext().orElseThrow();
		inserting.markSent(claim.outboxId());
		jdbc.update(
			"UPDATE generation_work_outbox SET created_at = ? WHERE id = ?",
			Timestamp.from(createdAt),
			claim.outboxId());
	}

	private void insertUnsentRow(Instant createdAt) {
		UUID jobId = insertJob();
		store().insertSuccessor(jobId, NOW);
		jdbc.update(
			"UPDATE generation_work_outbox SET created_at = ? WHERE generation_job_id = ?",
			Timestamp.from(createdAt),
			jobId);
	}
	private UUID insertJob() {
		UUID userId = UUID.randomUUID();
		UUID jobId = UUID.randomUUID();
		jdbc.update(
			"INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES (?, ?, ?, NULL)",
			userId,
			Timestamp.from(NOW),
			Timestamp.from(NOW));
		jdbc.update(
			"INSERT INTO generation_job (id, user_id, status, created_at) VALUES (?, ?, 'QUEUED', ?)",
			jobId,
			userId,
			Timestamp.from(NOW));
		return jobId;
	}

	private static final class SettableClock extends Clock {

		private Instant now;

		SettableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration by) {
			now = now.plus(by);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}
	}

	private static final class FailingDataSource implements DataSource {

		private final DataSource delegate;
		private volatile boolean failing;

		FailingDataSource(DataSource delegate) {
			this.delegate = delegate;
		}

		void fail() {
			failing = true;
		}

		@Override
		public Connection getConnection() throws SQLException {
			if (failing) {
				throw new SQLException("simulated connection failure");
			}
			return delegate.getConnection();
		}

		@Override
		public Connection getConnection(String username, String password) throws SQLException {
			if (failing) {
				throw new SQLException("simulated connection failure");
			}
			return delegate.getConnection(username, password);
		}

		@Override
		public PrintWriter getLogWriter() throws SQLException {
			return delegate.getLogWriter();
		}

		@Override
		public void setLogWriter(PrintWriter out) throws SQLException {
			delegate.setLogWriter(out);
		}

		@Override
		public void setLoginTimeout(int seconds) throws SQLException {
			delegate.setLoginTimeout(seconds);
		}

		@Override
		public int getLoginTimeout() throws SQLException {
			return delegate.getLoginTimeout();
		}

		@Override
		public Logger getParentLogger() throws SQLFeatureNotSupportedException {
			return delegate.getParentLogger();
		}

		@Override
		public <T> T unwrap(Class<T> iface) throws SQLException {
			return delegate.unwrap(iface);
		}

		@Override
		public boolean isWrapperFor(Class<?> iface) throws SQLException {
			return delegate.isWrapperFor(iface);
		}
	}
}
