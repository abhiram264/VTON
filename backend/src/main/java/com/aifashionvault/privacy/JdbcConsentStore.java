package com.aifashionvault.privacy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import com.aifashionvault.shared.ConsentId;
import com.aifashionvault.shared.UserId;

/**
 * JDBC consent store over the existing V010 {@code consent} table. No schema
 * change: the table already carries purpose, version, granted_at and
 * revoked_at, and every query is scoped by {@code user_id} so one owner can
 * never read or revoke another owner's grant.
 */
public class JdbcConsentStore implements ConsentStore {

	private static final String COLUMNS =
		"id, user_id, purpose, version, granted_at, revoked_at";

	private static final RowMapper<Consent> MAPPER = (rs, rowNum) -> new Consent(
		new ConsentId(rs.getObject("id", UUID.class)),
		new UserId(rs.getObject("user_id", UUID.class)),
		rs.getString("purpose"),
		rs.getString("version"),
		toInstant(rs, "granted_at"),
		toInstant(rs, "revoked_at"));

	private final JdbcTemplate jdbc;

	public JdbcConsentStore(DataSource dataSource) {
		if (dataSource == null) {
			throw new IllegalArgumentException("dataSource is required");
		}
		this.jdbc = new JdbcTemplate(dataSource);
	}

	@Override
	public Consent insert(Consent consent) {
		if (consent == null) {
			throw new IllegalArgumentException("consent is required");
		}
		jdbc.update(
			"INSERT INTO consent(id, user_id, purpose, version, granted_at, revoked_at) VALUES (?, ?, ?, ?, ?, ?)",
			consent.id().value(),
			consent.userId().value(),
			consent.purpose(),
			consent.version(),
			Timestamp.from(consent.grantedAt()),
			consent.revokedAt() == null ? null : Timestamp.from(consent.revokedAt()));
		return consent;
	}

	@Override
	public Optional<Consent> active(UserId userId, String purpose) {
		if (userId == null || purpose == null) {
			return Optional.empty();
		}
		return jdbc.query(
				"SELECT " + COLUMNS + " FROM consent WHERE user_id = ? AND purpose = ? AND revoked_at IS NULL "
					+ "ORDER BY granted_at DESC, id DESC LIMIT 1",
				MAPPER,
				userId.value(),
				purpose)
			.stream()
			.findFirst();
	}

	@Override
	public List<Consent> list(UserId userId) {
		if (userId == null) {
			return List.of();
		}
		return jdbc.query(
			"SELECT " + COLUMNS + " FROM consent WHERE user_id = ? ORDER BY granted_at ASC, id ASC",
			MAPPER,
			userId.value());
	}

	@Override
	public int revoke(UserId userId, String purpose, Instant revokedAt) {
		if (userId == null || purpose == null || revokedAt == null) {
			throw new IllegalArgumentException("userId, purpose and revokedAt are required");
		}
		return jdbc.update(
			"UPDATE consent SET revoked_at = ? WHERE user_id = ? AND purpose = ? AND revoked_at IS NULL",
			Timestamp.from(revokedAt),
			userId.value(),
			purpose);
	}

	private static Instant toInstant(ResultSet rs, String column) throws SQLException {
		Timestamp value = rs.getTimestamp(column);
		return value == null ? null : value.toInstant();
	}
}
