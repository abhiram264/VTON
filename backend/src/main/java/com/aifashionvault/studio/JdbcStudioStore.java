package com.aifashionvault.studio;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.StudioItemId;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.StudioSlot;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

/**
 * Owner-scoped persistence for Studio drafts over the existing V006
 * {@code studio_session}/{@code studio_item} schema.
 *
 * <p>There is deliberately no {@code revision} column: {@code updated_at} is the
 * optimistic-concurrency token, so a stale device that saves carrying an outdated
 * {@code updated_at} is rejected ({@link SaveStatus#STALE}) instead of silently
 * overwriting a newer draft.</p>
 *
 * <p>Every referenced record is re-checked against the owner on write: an avatar
 * profile or vault item that belongs to another user is refused before any row is
 * mutated. Slot-conflict and duplicate-garment rules are already enforced by the
 * {@link StudioSession} value type, so a conflicting draft cannot reach this store.</p>
 *
 * <p><b>Canonical item order.</b> {@code studio_item} has no global ordering column
 * (its unique key is {@code (studio_session_id, slot, position)}, so {@code position} is
 * a position <em>within a slot</em>). Reads therefore return items in the deterministic
 * canonical order {@code slot ASC, position ASC, id ASC}, which is <em>not</em>
 * necessarily the caller's insertion order. Callers that need a specific slot sequence
 * must order by slot themselves rather than relying on the persisted list order.</p>
 */
public final class JdbcStudioStore {

	public enum SaveStatus { SAVED, STALE, NOT_FOUND }

	public record SaveResult(SaveStatus status, StudioSession session) {
		public SaveResult {
			if (status == null) {
				throw new IllegalArgumentException("status is required");
			}
			if ((status == SaveStatus.SAVED) != (session != null)) {
				throw new IllegalArgumentException("only a saved result carries a session");
			}
		}

		public boolean saved() {
			return status == SaveStatus.SAVED;
		}
	}

	private static final String SESSION_COLUMNS = "id, user_id, avatar_profile_id, created_at, updated_at";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final RowMapper<StudioSession> sessions = (rs, row) -> {
		StudioSessionId id = new StudioSessionId(rs.getObject("id", UUID.class));
		return new StudioSession(
			id,
			new UserId(rs.getObject("user_id", UUID.class)),
			avatar(rs.getObject("avatar_profile_id", UUID.class)),
			rs.getTimestamp("created_at").toInstant(),
			rs.getTimestamp("updated_at").toInstant(),
			items(id));
	};

	public JdbcStudioStore(DataSource dataSource) {
		if (dataSource == null) {
			throw new IllegalArgumentException("dataSource is required");
		}
		this.jdbc = new JdbcTemplate(dataSource);
		this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}

	/** Inserts a brand-new draft and returns it as persisted. */
	public StudioSession create(StudioSession session) {
		if (session == null) {
			throw new IllegalArgumentException("session is required");
		}
		validateOwnedReferences(session);
		return transactions.execute(status -> {
			jdbc.update("INSERT INTO studio_session(id,user_id,avatar_profile_id,created_at,updated_at) VALUES(?,?,?,?,?)",
				session.id().value(), session.userId().value(), avatar(session.avatarProfileId()),
				timestamp(session.createdAt()), timestamp(session.updatedAt()));
			insertItems(session);
			return findForUser(session.id(), session.userId()).orElseThrow();
		});
	}

	/** Owner-scoped read; another user's session id resolves to empty. */
	public Optional<StudioSession> findForUser(StudioSessionId sessionId, UserId userId) {
		if (sessionId == null) {
			throw new IllegalArgumentException("sessionId is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		return jdbc.query("SELECT " + SESSION_COLUMNS + " FROM studio_session WHERE id=? AND user_id=?",
			sessions, sessionId.value(), userId.value()).stream().findFirst();
	}

	/** The draft to restore when the app reopens: the owner's most recently updated session. */
	public Optional<StudioSession> findLatestForUser(UserId userId) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		return jdbc.query("SELECT " + SESSION_COLUMNS + " FROM studio_session WHERE user_id=? ORDER BY updated_at DESC, id DESC LIMIT 1",
			sessions, userId.value()).stream().findFirst();
	}

	/**
	 * Updates the draft only when {@code expectedUpdatedAt} still matches the stored
	 * token. Returns {@link SaveStatus#STALE} (leaving the row untouched) when a newer
	 * revision won, and {@link SaveStatus#NOT_FOUND} for an unknown or foreign session.
	 */
	public SaveResult save(StudioSession next, Instant expectedUpdatedAt) {
		if (next == null) {
			throw new IllegalArgumentException("session is required");
		}
		if (expectedUpdatedAt == null) {
			throw new IllegalArgumentException("expectedUpdatedAt is required");
		}
		validateOwnedReferences(next);
		return transactions.execute(status -> {
			Optional<Instant> current = jdbc.query(
				"SELECT updated_at FROM studio_session WHERE id=? AND user_id=? FOR UPDATE",
				(rs, row) -> rs.getTimestamp("updated_at").toInstant(),
				next.id().value(), next.userId().value()).stream().findFirst();
			if (current.isEmpty()) {
				return new SaveResult(SaveStatus.NOT_FOUND, null);
			}
			if (!current.get().equals(expectedUpdatedAt)) {
				return new SaveResult(SaveStatus.STALE, null);
			}
			jdbc.update("UPDATE studio_session SET avatar_profile_id=?, updated_at=? WHERE id=? AND user_id=?",
				avatar(next.avatarProfileId()), timestamp(next.updatedAt()), next.id().value(), next.userId().value());
			jdbc.update("DELETE FROM studio_item WHERE studio_session_id=?", next.id().value());
			insertItems(next);
			return new SaveResult(SaveStatus.SAVED, findForUser(next.id(), next.userId()).orElseThrow());
		});
	}

	private void insertItems(StudioSession session) {
		for (StudioItem item : session.items()) {
			jdbc.update("INSERT INTO studio_item(id,studio_session_id,slot,vault_item_id,position,created_at) VALUES(?,?,?,?,?,?)",
				item.id().value(), session.id().value(), item.slot().name(), item.vaultItemId().value(),
				item.position(), timestamp(item.createdAt()));
		}
	}

	private List<StudioItem> items(StudioSessionId sessionId) {
		return jdbc.query("SELECT id, slot, vault_item_id, position, created_at FROM studio_item WHERE studio_session_id=? ORDER BY slot ASC, position ASC, id ASC",
			(rs, row) -> new StudioItem(
				new StudioItemId(rs.getObject("id", UUID.class)),
				sessionId,
				StudioSlot.valueOf(rs.getString("slot")),
				new VaultItemId(rs.getObject("vault_item_id", UUID.class)),
				rs.getInt("position"),
				rs.getTimestamp("created_at").toInstant()),
			sessionId.value());
	}

	private void validateOwnedReferences(StudioSession session) {
		if (session.avatarProfileId() != null && !exists(
			"SELECT EXISTS(SELECT 1 FROM avatar_profile WHERE id=? AND user_id=?)",
			session.avatarProfileId().value(), session.userId().value())) {
			throw new IllegalArgumentException("studio session avatar is not owned by the session user");
		}
		for (StudioItem item : session.items()) {
			if (!exists("SELECT EXISTS(SELECT 1 FROM vault_item WHERE id=? AND user_id=?)",
				item.vaultItemId().value(), session.userId().value())) {
				throw new IllegalArgumentException("studio item vault item is not owned by the session user");
			}
		}
	}

	private boolean exists(String sql, Object... arguments) {
		return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, arguments));
	}

	private static UUID avatar(AvatarProfileId id) {
		return id == null ? null : id.value();
	}

	private static AvatarProfileId avatar(UUID id) {
		return id == null ? null : new AvatarProfileId(id);
	}

	private static Timestamp timestamp(Instant value) {
		return Timestamp.from(value);
	}
}
