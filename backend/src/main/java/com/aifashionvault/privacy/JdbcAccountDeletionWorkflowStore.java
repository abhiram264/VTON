package com.aifashionvault.privacy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.aifashionvault.shared.UserId;

/**
 * Durable {@link AccountDeletionWorkflowStore} over the V021
 * {@code account_deletion_workflow} table, so the retryable deletion workflow
 * ({@link DefaultAccountDeletionWorkflow}) can persist a checkpoint, hand out a
 * lease and resume after a failure or a worker restart.
 *
 * <p>Every mutation is a compare-and-set keyed on the workflow id, the held lease
 * token, the expected step and a still-live lease. A stale or foreign worker (its
 * lease expired and was taken over) changes nothing and is told so; it can never
 * overwrite a newer checkpoint. The lease is released when the workflow completes
 * or fails, so a failed step stays resumable.
 */
public final class JdbcAccountDeletionWorkflowStore implements AccountDeletionWorkflowStore {

	private static final String COLUMNS =
		"SELECT id, user_id, next_step, status, requested_at, updated_at, completed_at, attempt_count, last_error_code "
			+ "FROM account_deletion_workflow";

	private static final RowMapper<AccountDeletionStatus> MAPPER = (rs, row) -> new AccountDeletionStatus(
		rs.getObject("id", UUID.class),
		new UserId(rs.getObject("user_id", UUID.class)),
		AccountDeletionStep.valueOf(rs.getString("next_step")),
		AccountDeletionState.valueOf(rs.getString("status")),
		rs.getInt("attempt_count"),
		rs.getTimestamp("requested_at").toInstant(),
		rs.getTimestamp("updated_at").toInstant(),
		rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
		rs.getString("last_error_code"));

	private final JdbcTemplate jdbc;

	public JdbcAccountDeletionWorkflowStore(DataSource dataSource) {
		if (dataSource == null) {
			throw new IllegalArgumentException("dataSource is required");
		}
		this.jdbc = new JdbcTemplate(dataSource);
	}

	@Override
	public AccountDeletionStatus request(UserId userId, Instant now) {
		require(userId, "userId");
		require(now, "now");
		// Idempotent: the UNIQUE(user_id) constraint plus ON CONFLICT DO NOTHING
		// make concurrent first requests converge on a single workflow row.
		jdbc.update(
			"INSERT INTO account_deletion_workflow "
				+ "(id, user_id, next_step, status, requested_at, updated_at, completed_at, lease_token, lease_expires_at, attempt_count, last_error_code) "
				+ "VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, NULL, 0, NULL) ON CONFLICT (user_id) DO NOTHING",
			UUID.randomUUID(),
			userId.value(),
			AccountDeletionStep.REQUESTED.name(),
			AccountDeletionState.REQUESTED.name(),
			Timestamp.from(now),
			Timestamp.from(now));
		return status(userId);
	}

	@Override
	public Optional<Lease> acquire(UserId userId, Instant now, Instant expiresAt) {
		require(userId, "userId");
		require(now, "now");
		require(expiresAt, "expiresAt");
		if (!expiresAt.isAfter(now)) {
			throw new IllegalArgumentException("lease must expire after acquisition");
		}
		UUID token = UUID.randomUUID();
		int updated = jdbc.update(
			"UPDATE account_deletion_workflow SET lease_token = ?, lease_expires_at = ? "
				+ "WHERE user_id = ? AND status <> ? AND (lease_token IS NULL OR lease_expires_at <= ?)",
			token,
			Timestamp.from(expiresAt),
			userId.value(),
			AccountDeletionState.COMPLETE.name(),
			Timestamp.from(now));
		if (updated == 0) {
			return Optional.empty();
		}
		return Optional.of(new Lease(status(userId), token));
	}

	@Override
	public AccountDeletionStatus advance(UUID workflowId, UUID leaseToken, AccountDeletionStep expectedStep,
			AccountDeletionStep nextStep, Instant now) {
		require(workflowId, "workflowId");
		require(leaseToken, "leaseToken");
		require(expectedStep, "expectedStep");
		require(nextStep, "nextStep");
		require(now, "now");
		if (nextStep != successor(expectedStep)) {
			throw new IllegalStateException("unexpected deletion successor");
		}
		boolean complete = nextStep == AccountDeletionStep.COMPLETE;
		String sql = "UPDATE account_deletion_workflow SET next_step = ?, status = ?, updated_at = ?"
			+ (complete ? ", completed_at = ?, lease_token = NULL, lease_expires_at = NULL" : "")
			+ " WHERE id = ? AND next_step = ? AND lease_token = ? AND lease_expires_at > ?";
		List<Object> arguments = new ArrayList<>();
		arguments.add(nextStep.name());
		arguments.add(complete ? AccountDeletionState.COMPLETE.name() : AccountDeletionState.RUNNING.name());
		arguments.add(Timestamp.from(now));
		if (complete) {
			arguments.add(Timestamp.from(now));
		}
		arguments.add(workflowId);
		arguments.add(expectedStep.name());
		arguments.add(leaseToken);
		arguments.add(Timestamp.from(now));
		int updated = jdbc.update(sql, arguments.toArray());
		if (updated != 1) {
			throw new IllegalStateException("stale deletion checkpoint");
		}
		return byWorkflowId(workflowId);
	}

	@Override
	public AccountDeletionStatus fail(UUID workflowId, UUID leaseToken, AccountDeletionStep expectedStep,
			String safeErrorCode, Instant now) {
		require(workflowId, "workflowId");
		require(leaseToken, "leaseToken");
		require(expectedStep, "expectedStep");
		require(now, "now");
		int updated = jdbc.update(
			"UPDATE account_deletion_workflow SET status = ?, updated_at = ?, attempt_count = attempt_count + 1, "
				+ "last_error_code = ?, lease_token = NULL, lease_expires_at = NULL "
				+ "WHERE id = ? AND next_step = ? AND lease_token = ? AND lease_expires_at > ?",
			AccountDeletionState.RETRYABLE_FAILURE.name(),
			Timestamp.from(now),
			safeErrorCode,
			workflowId,
			expectedStep.name(),
			leaseToken,
			Timestamp.from(now));
		if (updated != 1) {
			throw new IllegalStateException("stale deletion checkpoint");
		}
		return byWorkflowId(workflowId);
	}

	@Override
	public AccountDeletionStatus status(UserId userId) {
		require(userId, "userId");
		List<AccountDeletionStatus> rows = jdbc.query(COLUMNS + " WHERE user_id = ?", MAPPER, userId.value());
		if (rows.isEmpty()) {
			throw new IllegalStateException("deletion workflow is not requested");
		}
		return rows.getFirst();
	}

	private AccountDeletionStatus byWorkflowId(UUID workflowId) {
		List<AccountDeletionStatus> rows = jdbc.query(COLUMNS + " WHERE id = ?", MAPPER, workflowId);
		if (rows.isEmpty()) {
			throw new IllegalStateException("deletion workflow is missing");
		}
		return rows.getFirst();
	}

	private static AccountDeletionStep successor(AccountDeletionStep step) {
		AccountDeletionStep[] steps = AccountDeletionStep.values();
		if (step == AccountDeletionStep.COMPLETE) {
			throw new IllegalStateException("workflow is complete");
		}
		return steps[step.ordinal() + 1];
	}

	private static <T> T require(T value, String name) {
		if (value == null) {
			throw new IllegalArgumentException(name + " is required");
		}
		return value;
	}
}
