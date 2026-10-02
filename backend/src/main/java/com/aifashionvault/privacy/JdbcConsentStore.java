package com.aifashionvault.privacy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifashionvault.shared.ConsentId;
import com.aifashionvault.shared.UserId;

public final class JdbcConsentStore implements ConsentStore {

	private static final String COLUMNS = "SELECT id,user_id,purpose,version,granted_at,revoked_at FROM consent WHERE user_id=?";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;

	public JdbcConsentStore(DataSource dataSource) {
		if (dataSource == null) {
			throw new IllegalArgumentException("dataSource is required");
		}
		this.jdbc = new JdbcTemplate(dataSource);
		this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}

	@Override
	public List<Consent> list(UserId ownerId) {
		return jdbc.query(COLUMNS + " ORDER BY granted_at ASC, id ASC",
			(rs, row) -> consent(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
				rs.getString("purpose"), rs.getString("version"), rs.getTimestamp("granted_at").toInstant(),
				rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant()),
			ownerId.value());
	}

	@Override
	public Optional<Consent> active(UserId ownerId, ConsentPurpose purpose) {
		return jdbc.query(COLUMNS + " AND purpose=? AND revoked_at IS NULL ORDER BY granted_at DESC, id DESC LIMIT 1",
			(rs, row) -> consent(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
				rs.getString("purpose"), rs.getString("version"), rs.getTimestamp("granted_at").toInstant(), null),
			ownerId.value(), purpose.wireName()).stream().findFirst();
	}

	@Override
	public Consent grant(UserId ownerId, ConsentId consentId, ConsentPurpose purpose, String version, Instant now) {
		return transactions.execute(status -> {
			Optional<Consent> current = active(ownerId, purpose);
			if (current.isPresent()) {
				if (current.get().version().equals(version)) {
					return current.get();
				}
				jdbc.update("UPDATE consent SET revoked_at=? WHERE id=?", timestamp(now), current.get().id().value());
			}
			jdbc.update("INSERT INTO consent(id,user_id,purpose,version,granted_at,revoked_at) VALUES(?,?,?,?,?,NULL)",
				consentId.value(), ownerId.value(), purpose.wireName(), version, timestamp(now));
			return new Consent(consentId, ownerId, purpose.wireName(), version, now, null);
		});
	}

	@Override
	public boolean withdraw(UserId ownerId, ConsentPurpose purpose, Instant now) {
		return jdbc.update("UPDATE consent SET revoked_at=? WHERE user_id=? AND purpose=? AND revoked_at IS NULL",
			timestamp(now), ownerId.value(), purpose.wireName()) == 1;
	}

	private static Consent consent(UUID id, UUID userId, String purpose, String version, Instant grantedAt, Instant revokedAt) {
		return new Consent(new ConsentId(id), new UserId(userId), purpose, version, grantedAt, revokedAt);
	}

	private static Timestamp timestamp(Instant instant) {
		return Timestamp.from(instant);
	}
}
