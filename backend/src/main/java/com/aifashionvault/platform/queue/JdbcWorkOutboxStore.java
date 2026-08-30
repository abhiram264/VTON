package com.aifashionvault.platform.queue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifashionvault.generation.GenerationJob;

public final class JdbcWorkOutboxStore implements WorkOutboxStore {

	static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
	private static final long[] RETRY_SECONDS = {1L, 2L, 4L, 8L, 16L, 32L};
	private static final String ACTIVE_STATUSES_SQL =
		"'" + GenerationJob.QUEUED + "','"
			+ GenerationJob.PREFLIGHT + "','"
			+ GenerationJob.SUBMITTED + "','"
			+ GenerationJob.GENERATING + "','"
			+ GenerationJob.QUALITY_CHECK + "','"
			+ GenerationJob.RETRYING + "'";

	private static final long RETRY_CAP_SECONDS = 60L;

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final Clock clock;
	private final Random random;

	public JdbcWorkOutboxStore(DataSource dataSource) {
		this(dataSource, Clock.systemUTC());
	}

	public JdbcWorkOutboxStore(DataSource dataSource, Clock clock) {
		this(dataSource, clock, ThreadLocalRandom.current());
	}

	public JdbcWorkOutboxStore(DataSource dataSource, Clock clock, Random random) {
		Objects.requireNonNull(dataSource, "dataSource");
		this.jdbc = new JdbcTemplate(dataSource);
		this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		this.clock = Objects.requireNonNull(clock, "clock");
		this.random = Objects.requireNonNull(random, "random");
	}

	@Override
	public Optional<Claim> claimNext() {
		Instant now = clock.instant();
		return transactions.execute(status -> {
			List<Claim> rows = jdbc.query(
				"SELECT id, generation_job_id, attempt_count "
					+ "FROM generation_work_outbox "
					+ "WHERE (publish_state = 'PENDING' AND not_before <= ?) "
					+ "OR (publish_state = 'CLAIMED' AND claimed_until IS NOT NULL AND claimed_until <= ?) "
					+ "ORDER BY not_before, created_at, id "
					+ "FOR UPDATE SKIP LOCKED "
					+ "LIMIT 1",
				(rs, rowNum) -> new Claim(
					rs.getObject("id", UUID.class),
					rs.getObject("generation_job_id", UUID.class),
					rs.getInt("attempt_count")),
				Timestamp.from(now),
				Timestamp.from(now));
			if (rows.isEmpty()) {
				return Optional.empty();
			}
			Claim claim = rows.getFirst();
			jdbc.update(
				"UPDATE generation_work_outbox "
					+ "SET publish_state = 'CLAIMED', claimed_at = ?, claimed_until = ? "
					+ "WHERE id = ?",
				Timestamp.from(now),
				Timestamp.from(now.plus(CLAIM_LEASE)),
				claim.outboxId());
			return Optional.of(claim);
		});
	}

	@Override
	public void markSent(UUID outboxId) {
		Objects.requireNonNull(outboxId, "outboxId");
		jdbc.update(
			"UPDATE generation_work_outbox SET publish_state = 'SENT', sent_at = ? "
				+ "WHERE id = ? AND publish_state <> 'SENT'",
			Timestamp.from(clock.instant()),
			outboxId);
	}

	@Override
	public void markPublishFailed(UUID outboxId, String errorCategory) {
		Objects.requireNonNull(outboxId, "outboxId");
		Objects.requireNonNull(errorCategory, "errorCategory");
		Instant now = clock.instant();
		transactions.execute(status -> {
			Integer current = jdbc.queryForObject(
				"SELECT attempt_count FROM generation_work_outbox WHERE id = ? FOR UPDATE",
				Integer.class,
				outboxId);
			int nextAttempt = (current == null ? 0 : current) + 1;
			jdbc.update(
				"UPDATE generation_work_outbox "
					+ "SET publish_state = 'PENDING', attempt_count = ?, last_error_category = ?, not_before = ? "
					+ "WHERE id = ?",
				nextAttempt,
				errorCategory,
				Timestamp.from(now.plus(retryDelay(nextAttempt))),
				outboxId);
			return null;
		});
	}

	@Override
	public void insertSuccessor(UUID generationJobId, Instant notBefore) {
		Objects.requireNonNull(generationJobId, "generationJobId");
		Objects.requireNonNull(notBefore, "notBefore");
		jdbc.update(
			"INSERT INTO generation_work_outbox "
				+ "(id, generation_job_id, not_before, created_at, publish_state) "
				+ "VALUES (?, ?, ?, ?, 'PENDING')",
			UUID.randomUUID(),
			generationJobId,
			Timestamp.from(notBefore),
			Timestamp.from(clock.instant()));
	}
	@Override
	public boolean insertSuccessorIfActive(UUID generationJobId, Instant notBefore) {
		Objects.requireNonNull(generationJobId, "generationJobId");
		Objects.requireNonNull(notBefore, "notBefore");
		Instant createdAt = clock.instant();
		Boolean inserted = transactions.execute(status -> {
			List<UUID> active = jdbc.query(
				"SELECT id FROM generation_job WHERE id = ? AND status IN (" + ACTIVE_STATUSES_SQL + ") "
					+ "AND completed_at IS NULL AND cancelled_at IS NULL AND subject_deleted_at IS NULL "
					+ "FOR UPDATE",
				(rs, rowNum) -> rs.getObject(1, UUID.class),
				generationJobId);
			if (active.isEmpty()) {
				return false;
			}
			jdbc.update(
				"INSERT INTO generation_work_outbox "
					+ "(id, generation_job_id, not_before, created_at, publish_state) "
					+ "VALUES (?, ?, ?, ?, 'PENDING')",
				UUID.randomUUID(),
				generationJobId,
				Timestamp.from(notBefore),
				Timestamp.from(createdAt));
			return true;
		});
		return Boolean.TRUE.equals(inserted);
	}


	Duration retryDelay(int attemptCount) {
		long baseSeconds = attemptCount <= 0 || attemptCount > RETRY_SECONDS.length
			? RETRY_CAP_SECONDS
			: RETRY_SECONDS[attemptCount - 1];
		long jitterMillis = Math.round(random.nextDouble() * 0.2d * baseSeconds * 1000.0d);
		return Duration.ofMillis(baseSeconds * 1000L + jitterMillis);
	}
}
