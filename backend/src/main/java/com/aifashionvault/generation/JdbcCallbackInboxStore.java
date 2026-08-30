package com.aifashionvault.generation;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifashionvault.shared.GenerationAttemptId;

public class JdbcCallbackInboxStore implements CallbackInboxStore {

	private static final String COLUMNS =
		"id, dedupe_hash, generation_attempt_id, candidate_provider_job_id, safe_terminal_hint, received_at";

	private static final RowMapper<CallbackInbox> MAPPER = (rs, rowNum) -> {
		UUID attemptId = rs.getObject("generation_attempt_id", UUID.class);
		return new CallbackInbox(
			rs.getObject("id", UUID.class),
			rs.getString("dedupe_hash"),
			attemptId == null ? null : new GenerationAttemptId(attemptId),
			rs.getString("candidate_provider_job_id"),
			rs.getString("safe_terminal_hint"),
			rs.getTimestamp("received_at").toInstant());
	};

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final Clock clock;

	public JdbcCallbackInboxStore(DataSource dataSource) {
		this(dataSource, Clock.systemUTC());
	}

	public JdbcCallbackInboxStore(DataSource dataSource, Clock clock) {
		if (dataSource == null) {
			throw new IllegalArgumentException("dataSource is required");
		}
		if (clock == null) {
			throw new IllegalArgumentException("clock is required");
		}
		this.jdbc = new JdbcTemplate(dataSource);
		this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		this.clock = clock;
	}

	@Override
	public void register(GenerationAttemptId attemptId, String tokenHash, Instant receivedAt) {
		if (attemptId == null) {
			throw new IllegalArgumentException("attemptId is required");
		}
		if (tokenHash == null || tokenHash.isBlank()) {
			throw new IllegalArgumentException("tokenHash is required");
		}
		if (receivedAt == null) {
			throw new IllegalArgumentException("receivedAt is required");
		}
		jdbc.update(
			"INSERT INTO generation_callback_inbox "
				+ "(id, dedupe_hash, generation_attempt_id, candidate_provider_job_id, safe_terminal_hint, received_at) "
				+ "VALUES (?, ?, ?, NULL, NULL, ?) "
				+ "ON CONFLICT (dedupe_hash) DO NOTHING",
			UUID.randomUUID(),
			tokenHash,
			attemptId.value(),
			Timestamp.from(receivedAt));
	}

	@Override
	public Optional<CallbackInbox> findByDedupeHash(String dedupeHash) {
		if (dedupeHash == null || dedupeHash.isBlank()) {
			throw new IllegalArgumentException("dedupeHash is required");
		}
		List<CallbackInbox> found = jdbc.query(
			"SELECT " + COLUMNS + " FROM generation_callback_inbox WHERE dedupe_hash = ?",
			MAPPER,
			dedupeHash);
		return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
	}

	@Override
	public List<CallbackInbox> findByAttempt(GenerationAttemptId attemptId) {
		if (attemptId == null) {
			throw new IllegalArgumentException("attemptId is required");
		}
		return jdbc.query(
			"SELECT " + COLUMNS + " FROM generation_callback_inbox "
				+ "WHERE generation_attempt_id = ? ORDER BY received_at, id",
			MAPPER,
			attemptId.value());
	}

	@Override
	public boolean accept(String tokenHash, String candidateProviderJobId, String safeTerminalHint, Instant now) {
		if (tokenHash == null || tokenHash.isBlank()) {
			throw new IllegalArgumentException("tokenHash is required");
		}
		if (now == null) {
			now = clock.instant();
		}
		Instant receivedAt = now;
		Boolean accepted = transactions.execute(status -> {
			List<CallbackInbox> found = jdbc.query(
				"SELECT " + COLUMNS + " FROM generation_callback_inbox WHERE dedupe_hash = ?",
				MAPPER,
				tokenHash);
			if (found.isEmpty()) {
				return false;
			}
			CallbackInbox existing = found.getFirst();
			jdbc.update(
				"UPDATE generation_callback_inbox SET "
					+ "candidate_provider_job_id = COALESCE(candidate_provider_job_id, ?), "
					+ "safe_terminal_hint = COALESCE(safe_terminal_hint, ?) "
					+ "WHERE dedupe_hash = ?",
				candidateProviderJobId,
				safeTerminalHint,
				tokenHash);
			if (existing.generationAttemptId() != null) {
				UUID jobId = jdbc.query(
					"SELECT generation_job_id FROM generation_attempt WHERE id = ?",
					rs -> rs.next() ? rs.getObject("generation_job_id", UUID.class) : null,
					existing.generationAttemptId().value());
				if (jobId != null) {
					jdbc.update(
						"INSERT INTO generation_work_outbox "
							+ "(id, generation_job_id, not_before, created_at, publish_state) "
							+ "VALUES (?, ?, ?, ?, ?) "
							+ "ON CONFLICT (id) DO NOTHING",
						existing.id(),
						jobId,
						Timestamp.from(receivedAt),
						Timestamp.from(receivedAt),
						WorkOutbox.PENDING);
				}
			}
			return true;
		});
		return Boolean.TRUE.equals(accepted);
	}
}
