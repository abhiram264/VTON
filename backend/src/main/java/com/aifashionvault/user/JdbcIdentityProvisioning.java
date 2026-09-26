package com.aifashionvault.user;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifashionvault.shared.AccountMediaPurge;
import com.aifashionvault.shared.IdentityMappingId;
import com.aifashionvault.shared.UserId;

public final class JdbcIdentityProvisioning implements IdentityProvisioning {

	private static final RowMapper<User> USER_MAPPER = (rs, rowNum) -> new User(
		new UserId(rs.getObject("id", UUID.class)),
		rs.getTimestamp("created_at").toInstant(),
		rs.getTimestamp("updated_at").toInstant(),
		toInstant(rs.getTimestamp("deleted_at")));

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final Clock clock;

	public JdbcIdentityProvisioning(DataSource dataSource) {
		this(dataSource, Clock.systemUTC());
	}

	public JdbcIdentityProvisioning(DataSource dataSource, Clock clock) {
		this(dataSource, clock, List.of());
	}

	public JdbcIdentityProvisioning(
		DataSource dataSource,
		Clock clock,
		ObjectProvider<AccountMediaPurge> mediaPurges) {
		this(dataSource, clock, orderedPurges(mediaPurges));
	}

	public JdbcIdentityProvisioning(DataSource dataSource, Clock clock, List<AccountMediaPurge> mediaPurges) {
		if (dataSource == null) {
			throw new IllegalArgumentException("dataSource is required");
		}
		if (clock == null) {
			throw new IllegalArgumentException("clock is required");
		}
		if (mediaPurges == null) {
			throw new IllegalArgumentException("mediaPurges is required");
		}
		this.jdbc = new JdbcTemplate(dataSource);
		this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		this.clock = clock;
	}

	@Override
	public IdentityResolution resolve(String issuer, String subject) {
		requireExternalIdentity(issuer, subject);
		IdentityResolution existing = lookup(issuer, subject);
		if (existing != null) {
			return existing;
		}
		try {
			IdentityResolution created = transactions.execute(status -> insertUserAndMapping(issuer, subject));
			if (created == null) {
				throw new IllegalStateException("identity provisioning transaction returned no resolution");
			}
			return created;
		}
		catch (DataAccessException ex) {
			if (!isUniqueViolation(ex)) {
				throw ex;
			}
			IdentityResolution retried = lookup(issuer, subject);
			if (retried == null) {
				throw new IllegalStateException("identity mapping missing after unique conflict", ex);
			}
			return retried;
		}
	}

	@Override
	public User beginDeletion(UserId userId, Instant deletedAt) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (deletedAt == null) {
			throw new IllegalArgumentException("deletedAt is required");
		}
		User user = loadUser(userId);
		if (user == null) {
			throw new IllegalArgumentException("user is required");
		}
		if (user.deletedAt() != null) {
			return user;
		}
		User deleted = UserDeletion.markDeleted(user, deletedAt);
		jdbc.update(
			"UPDATE users SET deleted_at = ?, updated_at = ? WHERE id = ?",
			Timestamp.from(deleted.deletedAt()),
			Timestamp.from(deleted.updatedAt()),
			deleted.id().value());
		return deleted;
	}

	private IdentityResolution insertUserAndMapping(String issuer, String subject) {
		Instant now = Instant.now(clock);
		UserId userId = new UserId(UUID.randomUUID());
		IdentityMappingId mappingId = new IdentityMappingId(UUID.randomUUID());
		Timestamp createdAt = Timestamp.from(now);
		jdbc.update(
			"INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES (?, ?, ?, NULL)",
			userId.value(),
			createdAt,
			createdAt);
		jdbc.update(
			"INSERT INTO identity_mapping (id, user_id, issuer, subject, created_at) VALUES (?, ?, ?, ?, ?)",
			mappingId.value(),
			userId.value(),
			issuer,
			subject,
			createdAt);
		return new IdentityResolution.Active(userId);
	}

	private IdentityResolution lookup(String issuer, String subject) {
		List<User> users = jdbc.query(
			"SELECT u.id, u.created_at, u.updated_at, u.deleted_at "
				+ "FROM identity_mapping m "
				+ "JOIN users u ON u.id = m.user_id "
				+ "WHERE m.issuer = ? AND m.subject = ?",
			USER_MAPPER,
			issuer,
			subject);
		if (users.isEmpty()) {
			return null;
		}
		User user = users.getFirst();
		if (user.deletedAt() != null) {
			return new IdentityResolution.Deleted(user.id());
		}
		return new IdentityResolution.Active(user.id());
	}

	private User loadUser(UserId userId) {
		List<User> users = jdbc.query(
			"SELECT id, created_at, updated_at, deleted_at FROM users WHERE id = ?",
			USER_MAPPER,
			userId.value());
		return users.isEmpty() ? null : users.getFirst();
	}

	private static void requireExternalIdentity(String issuer, String subject) {
		if (issuer == null || issuer.isBlank()) {
			throw new IllegalArgumentException("issuer is required");
		}
		if (subject == null || subject.isBlank()) {
			throw new IllegalArgumentException("subject is required");
		}
	}

	private static boolean isUniqueViolation(DataAccessException ex) {
		if (ex instanceof DuplicateKeyException) {
			return true;
		}
		Throwable cause = ex.getMostSpecificCause();
		return cause instanceof SQLException sql && "23505".equals(sql.getSQLState());
	}

	private static Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	private static List<AccountMediaPurge> orderedPurges(ObjectProvider<AccountMediaPurge> mediaPurges) {
		if (mediaPurges == null) {
			throw new IllegalArgumentException("mediaPurges is required");
		}
		return mediaPurges.orderedStream().toList();
	}
}
