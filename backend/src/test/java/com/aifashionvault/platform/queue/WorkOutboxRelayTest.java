package com.aifashionvault.platform.queue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class WorkOutboxRelayTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-04-02T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
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

	private JdbcWorkOutboxStore store;
	private RecordingWorkQueue queue;
	private GenerationWorkOutboxRelay relay;

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
		jdbc.update(
			"TRUNCATE TABLE generation_work_outbox, generation_job, users CASCADE");
		store = new JdbcWorkOutboxStore(dataSource, CLOCK, ZERO_JITTER);
		queue = new RecordingWorkQueue();
		relay = new GenerationWorkOutboxRelay(store, queue);
	}

	@Test
	void pendingEligibleOutboxIsPublishedAsUuidAndMarkedSent() {
		UUID jobId = insertJob();
		store.insertSuccessor(jobId, NOW);

		relay.publishEligible();

		assertThat(queue.sentBodies()).containsExactly(jobId.toString());
		assertThat(queue.sentBodies().getFirst()).doesNotContain("{").doesNotContain(":");
		assertThat(publishState(jobId)).isEqualTo("SENT");
		assertThat(sentAt(jobId)).isEqualTo(NOW);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_job", Integer.class)).isEqualTo(1);
	}

	@Test
	void sqsFailureLeavesPendingRetryAndDoesNotCreateSecondJob() {
		UUID jobId = insertJob();
		store.insertSuccessor(jobId, NOW);
		queue.failNextSend();

		relay.publishEligible();

		assertThat(queue.sendCount()).isEqualTo(1);
		assertThat(publishState(jobId)).isEqualTo("PENDING");
		assertThat(attemptCount(jobId)).isEqualTo(1);
		assertThat(lastErrorCategory(jobId)).isEqualTo("SQS_PUBLISH");
		assertThat(notBefore(jobId)).isEqualTo(NOW.plusSeconds(1));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_job", Integer.class)).isEqualTo(1);
	}

	@Test
	void crashAfterSqsAcceptBeforeSentMayRepublishWithoutSecondJob() {
		UUID jobId = insertJob();
		store.insertSuccessor(jobId, NOW);
		WorkOutboxStore.Claim claim = store.claimNext().orElseThrow();
		queue.send(claim.generationJobId());
		assertThat(publishState(jobId)).isEqualTo("CLAIMED");

		JdbcWorkOutboxStore recoveredStore = new JdbcWorkOutboxStore(
			dataSource,
			Clock.fixed(NOW.plusSeconds(31), ZoneOffset.UTC),
			ZERO_JITTER);
		GenerationWorkOutboxRelay recovered = new GenerationWorkOutboxRelay(recoveredStore, queue);
		recovered.publishEligible();

		assertThat(queue.sentBodies()).containsExactly(jobId.toString(), jobId.toString());
		assertThat(publishState(jobId)).isEqualTo("SENT");
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_job", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_work_outbox", Integer.class)).isEqualTo(1);
	}

	@Test
	void futureNotBeforeIsNotPublished() {
		UUID jobId = insertJob();
		store.insertSuccessor(jobId, NOW.plusSeconds(10));

		relay.publishEligible();

		assertThat(queue.sendCount()).isZero();
		assertThat(publishState(jobId)).isEqualTo("PENDING");
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

	private String publishState(UUID jobId) {
		return jdbc.queryForObject(
			"SELECT publish_state FROM generation_work_outbox WHERE generation_job_id = ?",
			String.class,
			jobId);
	}

	private Instant sentAt(UUID jobId) {
		Timestamp timestamp = jdbc.queryForObject(
			"SELECT sent_at FROM generation_work_outbox WHERE generation_job_id = ?",
			Timestamp.class,
			jobId);
		return timestamp == null ? null : timestamp.toInstant();
	}

	private int attemptCount(UUID jobId) {
		Integer count = jdbc.queryForObject(
			"SELECT attempt_count FROM generation_work_outbox WHERE generation_job_id = ?",
			Integer.class,
			jobId);
		return count == null ? 0 : count;
	}

	private String lastErrorCategory(UUID jobId) {
		return jdbc.queryForObject(
			"SELECT last_error_category FROM generation_work_outbox WHERE generation_job_id = ?",
			String.class,
			jobId);
	}

	private Instant notBefore(UUID jobId) {
		Timestamp timestamp = jdbc.queryForObject(
			"SELECT not_before FROM generation_work_outbox WHERE generation_job_id = ?",
			Timestamp.class,
			jobId);
		return timestamp == null ? null : timestamp.toInstant();
	}

	private static final class RecordingWorkQueue implements GenerationWorkQueue {

		private final List<String> sentBodies = new ArrayList<>();
		private final AtomicInteger remainingFailures = new AtomicInteger();

		void failNextSend() {
			remainingFailures.incrementAndGet();
		}

		List<String> sentBodies() {
			return List.copyOf(sentBodies);
		}

		int sendCount() {
			return sentBodies.size();
		}

		@Override
		public void send(UUID generationJobId) {
			sentBodies.add(generationJobId.toString());
			if (remainingFailures.getAndUpdate(value -> value > 0 ? value - 1 : 0) > 0) {
				throw new IllegalStateException("SQS unavailable");
			}
		}

		@Override
		public Optional<GenerationWorkLease> receive() {
			return Optional.empty();
		}

		@Override
		public void extendVisibility(GenerationWorkLease lease, Duration extension) {
		}

		@Override
		public void delete(GenerationWorkLease lease) {
		}
	}
}
