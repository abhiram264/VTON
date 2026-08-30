package com.aifashionvault.platform.generation;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifashionvault.shared.GenerationAttemptId;

public final class JdbcGenerationCapacityLeaseStore implements GenerationCapacityLeaseStore {

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;

	public JdbcGenerationCapacityLeaseStore(DataSource dataSource) {
		if (dataSource == null) {
			throw new IllegalArgumentException("dataSource is required");
		}
		this.jdbc = new JdbcTemplate(dataSource);
		this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}

	@Override
	public boolean tryAcquire(
		String providerKey,
		GenerationAttemptId attemptId,
		Instant now,
		Instant leaseExpiresAt,
		int maxSlots
	) {
		if (providerKey == null || providerKey.isBlank()) {
			throw new IllegalArgumentException("providerKey is required");
		}
		if (attemptId == null) {
			throw new IllegalArgumentException("attemptId is required");
		}
		if (now == null) {
			throw new IllegalArgumentException("now is required");
		}
		if (leaseExpiresAt == null || !leaseExpiresAt.isAfter(now)) {
			throw new IllegalArgumentException("leaseExpiresAt must be after now");
		}
		if (maxSlots < 1) {
			throw new IllegalArgumentException("maxSlots must be >= 1");
		}
		Boolean acquired = transactions.execute(status -> {
			jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", providerKey);
			jdbc.update(
				"UPDATE generation_provider_capacity_slot SET released_at = ? "
					+ "WHERE provider_key = ? AND released_at IS NULL AND lease_expires_at <= ?",
				Timestamp.from(now),
				providerKey,
				Timestamp.from(now));
			Integer existing = jdbc.queryForObject(
				"SELECT COUNT(*) FROM generation_provider_capacity_slot "
					+ "WHERE generation_attempt_id = ? AND released_at IS NULL AND lease_expires_at > ?",
				Integer.class,
				attemptId.value(),
				Timestamp.from(now));
			if (existing != null && existing > 0) {
				return true;
			}
			Integer active = jdbc.queryForObject(
				"SELECT COUNT(*) FROM generation_provider_capacity_slot "
					+ "WHERE provider_key = ? AND released_at IS NULL AND lease_expires_at > ?",
				Integer.class,
				providerKey,
				Timestamp.from(now));
			if (active != null && active >= maxSlots) {
				return false;
			}
			try {
				jdbc.update(
					"INSERT INTO generation_provider_capacity_slot "
						+ "(id, provider_key, generation_attempt_id, leased_at, lease_expires_at, released_at) "
						+ "VALUES (?, ?, ?, ?, ?, NULL)",
					UUID.randomUUID(),
					providerKey,
					attemptId.value(),
					Timestamp.from(now),
					Timestamp.from(leaseExpiresAt));
				return true;
			}
			catch (DuplicateKeyException ignored) {
				Integer owned = jdbc.queryForObject(
					"SELECT COUNT(*) FROM generation_provider_capacity_slot "
						+ "WHERE generation_attempt_id = ? AND provider_key = ? AND released_at IS NULL",
					Integer.class,
					attemptId.value(),
					providerKey);
				return owned != null && owned > 0;
			}
		});
		return Boolean.TRUE.equals(acquired);
	}

	@Override
	public void release(GenerationAttemptId attemptId, Instant releasedAt) {
		if (attemptId == null) {
			throw new IllegalArgumentException("attemptId is required");
		}
		if (releasedAt == null) {
			throw new IllegalArgumentException("releasedAt is required");
		}
		jdbc.update(
			"UPDATE generation_provider_capacity_slot SET released_at = ? "
				+ "WHERE generation_attempt_id = ? AND released_at IS NULL",
			Timestamp.from(releasedAt),
			attemptId.value());
	}
}
