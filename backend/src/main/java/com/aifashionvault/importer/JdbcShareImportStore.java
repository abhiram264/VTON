package com.aifashionvault.importer;
import java.net.URI;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

/**
 * PostgreSQL-arbitrated Share Import persistence. Database uniqueness on
 * (user_id, idempotency_key) is the concurrency arbiter; attempt tokens fence
 * late losers during conditional result publication.
 */
public class JdbcShareImportStore {

	public sealed interface CreateResult permits Created, Replayed, FingerprintConflict {
	}

	public record Created(ShareImport shareImport) implements CreateResult {
	}

	public record Replayed(ShareImport shareImport) implements CreateResult {
	}

	public record FingerprintConflict(ShareImportId importId) implements CreateResult {
	}

	public sealed interface AttemptResult permits AttemptClaimed, AttemptHeld {
	}

	public record AttemptClaimed(ShareImport shareImport) implements AttemptResult {
	}

	public record AttemptHeld(ShareImport shareImport) implements AttemptResult {
	}

	public sealed interface RecoveryResult permits RecoveryClaimed, RecoveryNotRecoverable, ImportMissing {
	}

	public record RecoveryClaimed(ShareImport shareImport) implements RecoveryResult {
	}

	public record RecoveryNotRecoverable(ShareImport shareImport) implements RecoveryResult {
	}

	public record ImportMissing() implements RecoveryResult {
	}

	public sealed interface DeleteResult permits Deleted, AlreadyDeleted, Missing {
	}

	public record Deleted(StorageKey storageKey) implements DeleteResult {
	}

	/** The retained key lets later delete calls retry physical cleanup. */
	public record AlreadyDeleted(StorageKey storageKey) implements DeleteResult {
	}

	public record Missing() implements DeleteResult {
	}

	private static final RowMapper<ShareImport> MAPPER = (rs, rowNum) -> new ShareImport(
		new ShareImportId(rs.getObject("id", java.util.UUID.class)),
		new UserId(rs.getObject("user_id", java.util.UUID.class)),
		rs.getString("idempotency_key"),
		rs.getString("request_fingerprint"),
		ShareImportKind.valueOf(rs.getString("source_kind")),
		sourceUrl(rs),
		storageKey(rs),
		ShareImportState.valueOf(rs.getString("resolver_state")),
		rs.getInt("attempt_count"),
		optionalTimestamp(rs, "attempt_leased_at"),
		rs.getObject("attempt_token", java.util.UUID.class),
		optionalReason(rs),
		rs.getString("source_title"),
		rs.getString("source_site_name"),
		rs.getTimestamp("created_at").toInstant(),
		rs.getTimestamp("updated_at").toInstant());

	private static final String COLUMNS = "id, user_id, idempotency_key, request_fingerprint, source_kind, source_url, "
		+ "storage_key, resolver_state, attempt_count, attempt_leased_at, attempt_token, terminal_reason_code, "
		+ "source_title, source_site_name, created_at, updated_at";

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final Clock clock;

	public JdbcShareImportStore(DataSource dataSource) {
		this(dataSource, Clock.systemUTC());
	}

	public JdbcShareImportStore(DataSource dataSource, Clock clock) {
		Objects.requireNonNull(dataSource, "dataSource");
		Objects.requireNonNull(clock, "clock");
		this.jdbc = new JdbcTemplate(dataSource);
		this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		this.clock = clock;
	}

	public CreateResult create(ImportCommand command) {
		Objects.requireNonNull(command, "command");
		String fingerprint = fingerprint(command);
		ShareImportId id = new ShareImportId(UUID.randomUUID());
		Instant now = clock.instant();
		UUID attemptToken = UUID.randomUUID();
		try {
			transactions.executeWithoutResult(status -> jdbc.update(
				"INSERT INTO share_import (id, user_id, source_kind, source_url, storage_key, resolver_state, "
					+ "idempotency_key, request_fingerprint, attempt_count, attempt_leased_at, attempt_token, "
					+ "created_at, updated_at) "
					+ "VALUES (?, ?, ?, ?, NULL, 'RESOLVING', ?, ?, 1, ?, ?, ?, ?)",
			id.value(), command.ownerId().value(), command.kind().name(),
			sourceUrlText(command.sourceUrl()), command.idempotencyKey(), fingerprint,
			Timestamp.from(now), attemptToken, Timestamp.from(now), Timestamp.from(now)));
		} catch (DataAccessException ex) {
			if (!isUniqueViolation(ex)) {
				throw ex;
			}
			return arbitrateCreate(command).orElseThrow(() -> ex);
		}
		return new Created(rowById(command.ownerId(), id).orElseThrow());
	}

	/**
	 * Resolves an existing create request without touching staging media.
	 * Empty means the caller may validate media and proceed to {@link #create}.
	 */
	public Optional<CreateResult> arbitrateCreate(ImportCommand command) {
		Objects.requireNonNull(command, "command");
		String fingerprint = fingerprint(command);
		Optional<ShareImport> existing = rowByOwnerAndKey(command.ownerId(), command.idempotencyKey());
		if (existing.isEmpty()) {
			return Optional.empty();
		}
		ShareImport row = existing.get();
		CreateResult result = fingerprint.equals(row.requestFingerprint())
			? new Replayed(row)
			: new FingerprintConflict(row.id());
		return Optional.of(result);
	}

	public Optional<ShareImport> find(UserId ownerId, ShareImportId id) {
		Objects.requireNonNull(ownerId, "ownerId");
		Objects.requireNonNull(id, "id");
		return jdbc.query(
			"SELECT " + COLUMNS + " FROM share_import WHERE id = ? AND user_id = ? AND resolver_state <> 'DELETED'",
			MAPPER, id.value(), ownerId.value()).stream().findFirst();
	}

	public AttemptResult beginAttempt(UserId ownerId, ShareImportId id) {
		Objects.requireNonNull(ownerId, "ownerId");
		Objects.requireNonNull(id, "id");
		Instant now = clock.instant();
		Instant leaseExpiry = now.minus(ImportResolverLimits.RESOLUTION_LEASE);
		UUID attemptToken = UUID.randomUUID();
		int updated = jdbc.update(
			"UPDATE share_import SET attempt_count = attempt_count + 1, attempt_leased_at = ?, attempt_token = ?, "
				+ "updated_at = ? "
				+ "WHERE id = ? AND user_id = ? AND resolver_state IN ('RESOLVING', 'FAILED_RETRYABLE') "
				+ "AND (attempt_token IS NULL OR attempt_leased_at IS NULL OR attempt_leased_at < ?)",
			Timestamp.from(now), attemptToken, Timestamp.from(now), id.value(), ownerId.value(), Timestamp.from(leaseExpiry));
		ShareImport current = rowById(ownerId, id).orElse(null);
		if (updated == 1) {
			return new AttemptClaimed(current);
		}
		if (current == null) {
			throw new ShareImportMissingException(id);
		}
		return new AttemptHeld(current);
	}

	public boolean publish(ShareImportId id, UUID attemptToken, ResolutionResult result) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(attemptToken, "attemptToken");
		Objects.requireNonNull(result, "result");
		if (result.status() == ResolutionStatus.UNSUPPORTED) {
			throw new IllegalArgumentException("UNSUPPORTED is not a publishable result");
		}
		ShareImportState state = switch (result.status()) {
			case RESOLVED -> ShareImportState.READY;
			case NEEDS_IMAGE -> ShareImportState.NEEDS_IMAGE;
			case RETRYABLE_FAILURE -> ShareImportState.FAILED_RETRYABLE;
			case TERMINAL_FAILURE -> ShareImportState.FAILED_TERMINAL;
			case UNSUPPORTED -> throw new IllegalArgumentException("UNSUPPORTED is not a publishable result");
		};
		Instant now = clock.instant();
		int updated = jdbc.update(
			"UPDATE share_import SET resolver_state = ?, storage_key = ?, source_title = ?, source_site_name = ?, "
				+ "terminal_reason_code = ?, attempt_token = NULL, attempt_leased_at = NULL, updated_at = ? "
				+ "WHERE id = ? AND attempt_token = ?",
			state.name(),
			result.storageKey() == null ? null : result.storageKey().value(),
			result.title(), result.siteName(),
			result.reason() == null ? null : result.reason().name(),
			Timestamp.from(now), id.value(), attemptToken);
		return updated == 1;
	}

	public RecoveryResult beginRecovery(UserId ownerId, ShareImportId id) {
		Objects.requireNonNull(ownerId, "ownerId");
		Objects.requireNonNull(id, "id");
		Instant now = clock.instant();
		UUID attemptToken = UUID.randomUUID();
		int updated = jdbc.update(
			"UPDATE share_import SET resolver_state = 'RESOLVING', attempt_count = attempt_count + 1, "
				+ "attempt_leased_at = ?, attempt_token = ?, terminal_reason_code = NULL, updated_at = ? "
				+ "WHERE id = ? AND user_id = ? AND resolver_state IN ('NEEDS_IMAGE', 'FAILED_RETRYABLE')",
			Timestamp.from(now), attemptToken, Timestamp.from(now), id.value(), ownerId.value());
		ShareImport current = rowById(ownerId, id).orElse(null);
		if (updated == 1) {
			return new RecoveryClaimed(current);
		}
		if (current == null) {
			return new ImportMissing();
		}
		return new RecoveryNotRecoverable(current);
	}

	/**
	 * Publishes a recovery attempt and, exactly when the import becomes
	 * READY, atomically binds the recovery idempotency key and the claimed
	 * upload on the same row. Non-READY outcomes publish without binding so
	 * a retryable recovery failure can be retried with a fresh upload.
	 */
	public boolean publishRecovery(ShareImportId id, UUID attemptToken, ResolutionResult result,
			String recoveryKey, UploadId uploadId) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(attemptToken, "attemptToken");
		Objects.requireNonNull(result, "result");
		Objects.requireNonNull(recoveryKey, "recoveryKey");
		Objects.requireNonNull(uploadId, "uploadId");
		if (result.status() != ResolutionStatus.RESOLVED) {
			return publish(id, attemptToken, result);
		}
		Instant now = clock.instant();
		int updated = jdbc.update(
			"UPDATE share_import SET resolver_state = 'READY', storage_key = ?, source_title = ?, "
				+ "source_site_name = ?, terminal_reason_code = NULL, attempt_token = NULL, "
				+ "attempt_leased_at = NULL, recovery_key = ?, recovery_upload_id = ?, updated_at = ? "
				+ "WHERE id = ? AND attempt_token = ?",
			result.storageKey() == null ? null : result.storageKey().value(),
			result.title(), result.siteName(),
			recoveryKey, uploadId.value(), Timestamp.from(now), id.value(), attemptToken);
		return updated == 1;
	}

	/** Binding persisted when a recovery attempt published READY: key + claimed upload. */
	public record RecoveryBinding(String recoveryKey, UploadId uploadId) {
	}

	public Optional<RecoveryBinding> recoveryBinding(UserId ownerId, ShareImportId id) {
		Objects.requireNonNull(ownerId, "ownerId");
		Objects.requireNonNull(id, "id");
		return jdbc.query(
			"SELECT recovery_key, recovery_upload_id FROM share_import "
				+ "WHERE id = ? AND user_id = ? AND recovery_key IS NOT NULL AND recovery_upload_id IS NOT NULL",
			(rs, rowNum) -> new RecoveryBinding(rs.getString("recovery_key"),
				new UploadId(rs.getObject("recovery_upload_id", java.util.UUID.class))),
			id.value(), ownerId.value()).stream().findFirst();
	}

	/**
	 * Confirms an owner-owned READY import into the persistent Vault. The
	 * resolved object is already owned by this user at {@code storage_key}, so
	 * confirmation reuses it — no second upload, no download round-trip, and
	 * no original upload id, which a URL import never had (PRD PR-08).
	 * <p>
	 * Identity is derived from (user, import), so a repeated or concurrent
	 * confirmation converges on the same saved item instead of creating two,
	 * and the import's source provenance is preserved through the
	 * {@code source_reference} row. The write is owner-scoped and transactional;
	 * a foreign import never resolves here and no item is inserted.
	 *
	 * @return the confirmed Vault item, or empty when the import is not
	 * owner-visible, is not READY, or has no resolved object.
	 */
	public Optional<ConfirmedItem> confirm(UserId ownerId, ShareImportId id) {
		Objects.requireNonNull(ownerId, "ownerId");
		Objects.requireNonNull(id, "id");
		ShareImport current = find(ownerId, id).orElse(null);
		if (current == null || current.state() != ShareImportState.READY || current.storageKey() == null) {
			return Optional.empty();
		}
		UUID itemId = confirmedItemId(ownerId, id);
		Instant now = clock.instant();
		transactions.executeWithoutResult(status -> {
			// The source row is the vault_item FK target; ON CONFLICT keeps a
			// repeat confirmation idempotent without losing provenance.
			jdbc.update(
				"INSERT INTO source_reference (id, url, retailer, raw_metadata, created_at) VALUES (?, ?, ?, ?::jsonb, ?) "
					+ "ON CONFLICT (id) DO NOTHING",
				id.value(), current.sourceUrl() == null ? null : current.sourceUrl().toString(),
				current.sourceSiteName(), sourceMetadata(current), Timestamp.from(now));
			jdbc.update(
				"INSERT INTO vault_item (id, user_id, storage_key, source_reference_id, name, created_at) "
					+ "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING",
				itemId, ownerId.value(), current.storageKey().value(), id.value(),
				current.sourceTitle(), Timestamp.from(now));
		});
		return confirmedItem(ownerId, itemId);
	}

	private Optional<ConfirmedItem> confirmedItem(UserId ownerId, UUID itemId) {
		return jdbc.query(
			"SELECT v.id, v.source_reference_id, v.created_at FROM vault_item v WHERE v.id = ? AND v.user_id = ?",
			(rs, rowNum) -> new ConfirmedItem(rs.getObject("id", UUID.class),
				rs.getObject("source_reference_id", UUID.class), rs.getTimestamp("created_at").toInstant()),
			itemId, ownerId.value()).stream().findFirst();
	}

	/**
	 * Stable saved-item identity for one (owner, import) pair: repeating or
	 * racing a confirmation can never mint a second item for the same import.
	 */
	private static UUID confirmedItemId(UserId ownerId, ShareImportId id) {
		return UUID.nameUUIDFromBytes(
			("share-import:" + ownerId.value() + ":" + id.value()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
	}

	private static String sourceMetadata(ShareImport current) {
		if (current.sourceSiteName() == null && current.sourceTitle() == null) {
			return null;
		}
		return "{\"siteName\":" + jsonText(current.sourceSiteName()) + ",\"title\":" + jsonText(current.sourceTitle()) + "}";
	}

	private static String jsonText(String value) {
		return value == null ? "null" : "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
	}

	/** Saved-item identity returned by a successful confirmation. */
	public record ConfirmedItem(UUID itemId, UUID sourceReferenceId, java.time.Instant createdAt) {
	}

	public DeleteResult delete(UserId ownerId, ShareImportId id) {
		Objects.requireNonNull(ownerId, "ownerId");
		Objects.requireNonNull(id, "id");
		Instant now = clock.instant();
		List<StorageKey> deleted = jdbc.query(
			"UPDATE share_import SET resolver_state = 'DELETED', attempt_token = NULL, attempt_leased_at = NULL, "
				+ "terminal_reason_code = NULL, updated_at = ? "
				+ "WHERE id = ? AND user_id = ? AND resolver_state <> 'DELETED' RETURNING storage_key",
			(rs, rowNum) -> storageKey(rs),
			Timestamp.from(now), id.value(), ownerId.value());
		if (!deleted.isEmpty()) {
			return new Deleted(deleted.getFirst());
		}
		ShareImport current = rowById(ownerId, id).orElse(null);
		if (current == null) {
			return new Missing();
		}
		if (current.state() == ShareImportState.DELETED) {
			return new AlreadyDeleted(current.storageKey());
		}
		throw new IllegalStateException("share import delete was not applied: " + id.value());
	}

	/**
	 * Clears the storage key of an already-DELETED row once its object removal
	 * has SUCCEEDED, so a later DELETE reads no key and does not repeat the
	 * removal. A row whose key is still set is a cleanup-FAILED row, which a
	 * later delete call retries. The clear is owner-scoped and state-fenced.
	 */
	public void markCleanedUp(UserId ownerId, ShareImportId id) {
		Objects.requireNonNull(ownerId, "ownerId");
		Objects.requireNonNull(id, "id");
		jdbc.update(
			"UPDATE share_import SET storage_key = NULL, updated_at = ? "
				+ "WHERE id = ? AND user_id = ? AND resolver_state = 'DELETED'",
			Timestamp.from(clock.instant()), id.value(), ownerId.value());
	}

	public List<StorageKey> storageKeysFor(UserId ownerId) {
		Objects.requireNonNull(ownerId, "ownerId");
		return jdbc.queryForList(
			"SELECT storage_key FROM share_import WHERE user_id = ? AND storage_key IS NOT NULL",
			String.class, ownerId.value()).stream().map(StorageKey::new).toList();
	}

	public void purgeForUser(UserId ownerId) {
		Objects.requireNonNull(ownerId, "ownerId");
		jdbc.update(
			"UPDATE share_import SET resolver_state = 'DELETED', attempt_token = NULL, attempt_leased_at = NULL, "
				+ "terminal_reason_code = NULL, updated_at = ? WHERE user_id = ? AND resolver_state <> 'DELETED'",
			Timestamp.from(clock.instant()), ownerId.value());
	}

	private Optional<ShareImport> rowById(UserId ownerId, ShareImportId id) {
		return jdbc.query(
			"SELECT " + COLUMNS + " FROM share_import WHERE id = ? AND user_id = ?",
			MAPPER, id.value(), ownerId.value()).stream().findFirst();
	}

	private Optional<ShareImport> rowByOwnerAndKey(UserId ownerId, String idempotencyKey) {
		return jdbc.query(
			"SELECT " + COLUMNS + " FROM share_import WHERE user_id = ? AND idempotency_key = ?",
			MAPPER, ownerId.value(), idempotencyKey).stream().findFirst();
	}

	private static String fingerprint(ImportCommand command) {
		return command.kind() + "|" + sourceUrlText(command.sourceUrl()) + "|"
			+ (command.uploadId() == null ? "" : command.uploadId().value());
	}

	private static String sourceUrlText(URI sourceUrl) {
		return sourceUrl == null ? null : sourceUrl.toString();
	}

	private static URI sourceUrl(java.sql.ResultSet rs) throws SQLException {
		String value = rs.getString("source_url");
		return value == null ? null : java.net.URI.create(value);
	}

	private static StorageKey storageKey(java.sql.ResultSet rs) throws SQLException {
		String value = rs.getString("storage_key");
		return value == null ? null : new StorageKey(value);
	}

	private static Instant optionalTimestamp(java.sql.ResultSet rs, String column) throws SQLException {
		Timestamp timestamp = rs.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}

	private static ResolutionReason optionalReason(java.sql.ResultSet rs) throws SQLException {
		String value = rs.getString("terminal_reason_code");
		return value == null ? null : ResolutionReason.valueOf(value);
	}

	private static boolean isUniqueViolation(DataAccessException ex) {
		if (ex instanceof DuplicateKeyException) {
			return true;
		}
		Throwable cause = ex.getMostSpecificCause();
		return cause instanceof SQLException sql && "23505".equals(sql.getSQLState());
	}

	public static final class ShareImportMissingException extends IllegalStateException {

		private static final long serialVersionUID = 1L;

		ShareImportMissingException(ShareImportId id) {
			super("share import not found: " + id.value());
		}
	}
}
