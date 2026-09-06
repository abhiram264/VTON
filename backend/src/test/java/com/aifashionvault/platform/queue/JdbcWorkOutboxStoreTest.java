package com.aifashionvault.platform.queue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcWorkOutboxStoreTest {

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
	}

	@Test
	void insertSuccessorPersistsPendingNotBefore() {
		UUID jobId = insertJob();
		Instant notBefore = NOW.plusSeconds(45);

		store.insertSuccessor(jobId, notBefore);

		List<OutboxRow> rows = loadRows(jobId);
		assertThat(rows).hasSize(1);
		assertThat(rows.getFirst().publishState()).isEqualTo("PENDING");
		assertThat(rows.getFirst().notBefore()).isEqualTo(notBefore);
		assertThat(rows.getFirst().attemptCount()).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_job", Integer.class)).isEqualTo(1);
	}

	@Test
	void claimSkipsFutureNotBefore() {
		UUID jobId = insertJob();
		store.insertSuccessor(jobId, NOW.plusSeconds(30));

		assertThat(store.claimNext()).isEmpty();
		assertThat(loadRows(jobId).getFirst().publishState()).isEqualTo("PENDING");
	}

	@Test
	void claimLocksEligiblePendingRow() {
		UUID jobId = insertJob();
		store.insertSuccessor(jobId, NOW);

		WorkOutboxStore.Claim claim = store.claimNext().orElseThrow();

		assertThat(claim.generationJobId()).isEqualTo(jobId);
		OutboxRow row = loadRows(jobId).getFirst();
		assertThat(row.publishState()).isEqualTo("CLAIMED");
		assertThat(row.claimedAt()).isEqualTo(NOW);
		assertThat(row.claimedUntil()).isEqualTo(NOW.plusSeconds(30));
		assertThat(store.claimNext()).isEmpty();
	}

	@Test
	void concurrentClaimsTakeDistinctRowsWithSkipLocked() throws Exception {
		UUID firstJob = insertJob();
		UUID secondJob = insertJob();
		store.insertSuccessor(firstJob, NOW);
		store.insertSuccessor(secondJob, NOW);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		JdbcWorkOutboxStore firstStore = new JdbcWorkOutboxStore(dataSource, CLOCK, ZERO_JITTER);
		JdbcWorkOutboxStore secondStore = new JdbcWorkOutboxStore(dataSource, CLOCK, ZERO_JITTER);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Optional<WorkOutboxStore.Claim>> first = pool.submit(() -> claimAtBarrier(firstStore, ready, start));
			Future<Optional<WorkOutboxStore.Claim>> second = pool.submit(() -> claimAtBarrier(secondStore, ready, start));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			Optional<WorkOutboxStore.Claim> claimedA = first.get(10, TimeUnit.SECONDS);
			Optional<WorkOutboxStore.Claim> claimedB = second.get(10, TimeUnit.SECONDS);

			assertThat(claimedA).isPresent();
			assertThat(claimedB).isPresent();
			assertThat(claimedA.get().generationJobId()).isNotEqualTo(claimedB.get().generationJobId());
			assertThat(store.claimNext()).isEmpty();
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void expiredClaimIsRecoverable() {
		UUID jobId = insertJob();
		store.insertSuccessor(jobId, NOW);
		WorkOutboxStore.Claim first = store.claimNext().orElseThrow();
		assertThat(store.claimNext()).isEmpty();

		JdbcWorkOutboxStore later = new JdbcWorkOutboxStore(
			dataSource,
			Clock.fixed(NOW.plusSeconds(31), ZoneOffset.UTC),
			ZERO_JITTER);

		WorkOutboxStore.Claim recovered = later.claimNext().orElseThrow();
		assertThat(recovered.outboxId()).isEqualTo(first.outboxId());
		assertThat(recovered.generationJobId()).isEqualTo(jobId);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_job", Integer.class)).isEqualTo(1);
	}

	@Test
	void markSentLeavesRowSentAndUnclaimable() {
		UUID jobId = insertJob();
		store.insertSuccessor(jobId, NOW);
		WorkOutboxStore.Claim claim = store.claimNext().orElseThrow();

		store.markSent(claim.outboxId());

		OutboxRow row = loadRows(jobId).getFirst();
		assertThat(row.publishState()).isEqualTo("SENT");
		assertThat(row.sentAt()).isEqualTo(NOW);
		assertThat(store.claimNext()).isEmpty();
	}

	@Test
	void publishFailureSchedulesRetryWithoutSecondJob() {
		UUID jobId = insertJob();
		store.insertSuccessor(jobId, NOW);
		WorkOutboxStore.Claim claim = store.claimNext().orElseThrow();

		store.markPublishFailed(claim.outboxId(), "SQS_PUBLISH");

		OutboxRow row = loadRows(jobId).getFirst();
		assertThat(row.publishState()).isEqualTo("PENDING");
		assertThat(row.attemptCount()).isEqualTo(1);
		assertThat(row.lastErrorCategory()).isEqualTo("SQS_PUBLISH");
		assertThat(row.notBefore()).isEqualTo(NOW.plusSeconds(1));
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_job", Integer.class)).isEqualTo(1);
	}

	@Test
	void publishFailureUsesCappedBackoff() {
		UUID jobId = insertJob();
		store.insertSuccessor(jobId, NOW);
		WorkOutboxStore.Claim claim = store.claimNext().orElseThrow();
		long[] expectedSeconds = {1, 2, 4, 8, 16, 32, 60};
		for (int i = 0; i < expectedSeconds.length; i++) {
			store.markPublishFailed(claim.outboxId(), "SQS_PUBLISH");
			OutboxRow row = loadRows(jobId).getFirst();
			assertThat(row.attemptCount()).isEqualTo(i + 1);
			assertThat(row.notBefore()).isEqualTo(NOW.plusSeconds(expectedSeconds[i]));
			assertThat(row.publishState()).isEqualTo("PENDING");
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_job", Integer.class)).isEqualTo(1);
	}

	@Test
	void unsentReadsAreEmptyWhenOutboxIsEmpty() {
		assertThat(store.countUnsent()).isZero();
		assertThat(store.oldestUnsentCreatedAt()).isEmpty();
	}

	@Test
	void unsentReadsCountBacklogAndIgnoreSentRows() {
		UUID sentJob = insertJob();
		UUID backoffJob = insertJob();
		UUID oldestJob = insertJob();
		store.insertSuccessor(sentJob, NOW);
		WorkOutboxStore.Claim sentClaim = store.claimNext().orElseThrow();
		store.markSent(sentClaim.outboxId());
		store.insertSuccessor(backoffJob, NOW);
		WorkOutboxStore.Claim backoffClaim = store.claimNext().orElseThrow();
		store.markPublishFailed(backoffClaim.outboxId(), "SQS_PUBLISH");
		store.insertSuccessor(oldestJob, NOW);
		Instant oldestCreatedAt = NOW.minus(Duration.ofMillis(40_123));
		jdbc.update(
			"UPDATE generation_work_outbox SET created_at = ? WHERE generation_job_id = ?",
			Timestamp.from(oldestCreatedAt),
			oldestJob);

		assertThat(store.countUnsent()).isEqualTo(2);
		assertThat(store.oldestUnsentCreatedAt()).contains(oldestCreatedAt);
	}

	private static Optional<WorkOutboxStore.Claim> claimAtBarrier(
		WorkOutboxStore workOutboxStore,
		CountDownLatch ready,
		CountDownLatch start
	) throws Exception {
		ready.countDown();
		if (!start.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("start");
		}
		return workOutboxStore.claimNext();
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

	private List<OutboxRow> loadRows(UUID jobId) {
		return jdbc.query(
			"SELECT id, generation_job_id, not_before, publish_state, attempt_count, last_error_category, "
				+ "sent_at, claimed_at, claimed_until "
				+ "FROM generation_work_outbox WHERE generation_job_id = ? ORDER BY created_at, id",
			(rs, rowNum) -> new OutboxRow(
				rs.getObject("id", UUID.class),
				rs.getObject("generation_job_id", UUID.class),
				rs.getTimestamp("not_before").toInstant(),
				rs.getString("publish_state"),
				rs.getInt("attempt_count"),
				rs.getString("last_error_category"),
				toInstant(rs.getTimestamp("sent_at")),
				toInstant(rs.getTimestamp("claimed_at")),
				toInstant(rs.getTimestamp("claimed_until"))),
			jobId);
	}

	private static Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	private record OutboxRow(
		UUID id,
		UUID generationJobId,
		Instant notBefore,
		String publishState,
		int attemptCount,
		String lastErrorCategory,
		Instant sentAt,
		Instant claimedAt,
		Instant claimedUntil
	) {
	}
}
