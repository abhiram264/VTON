package com.aifashionvault.avatar;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

public class JdbcAvatarStore {

	private static final String LOCK_PROFILE_FOR_UPDATE =
		"SELECT id, user_id, primary_image_id, status, revision FROM avatar_profile WHERE id = ? AND user_id = ? FOR UPDATE";

	private static final RowMapper<AvatarProfile> PROFILE_MAPPER = (rs, rowNum) -> new AvatarProfile(
		new AvatarProfileId(rs.getObject("id", UUID.class)),
		new UserId(rs.getObject("user_id", UUID.class)),
		toImageId(rs.getObject("primary_image_id", UUID.class)),
		rs.getString("status"),
		rs.getInt("revision"),
		rs.getTimestamp("created_at").toInstant(),
		rs.getTimestamp("updated_at").toInstant());

	private static final RowMapper<AvatarImage> IMAGE_MAPPER = (rs, rowNum) -> new AvatarImage(
		new AvatarImageId(rs.getObject("id", UUID.class)),
		new AvatarProfileId(rs.getObject("profile_id", UUID.class)),
		new StorageKey(rs.getString("storage_key")),
		rs.getObject("width", Integer.class),
		rs.getObject("height", Integer.class),
		rs.getString("pose"),
		rs.getString("quality_status"),
		rs.getTimestamp("created_at").toInstant(),
		toUploadId(rs.getObject("source_upload_id", UUID.class)),
		AvatarImageLifecycle.valueOf(rs.getString("lifecycle_state")),
		warningsFrom(rs),
		rs.getString("preflight_policy_version"));

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;
	private final Clock clock;

	public JdbcAvatarStore(DataSource dataSource) {
		this(dataSource, Clock.systemUTC());
	}

	public JdbcAvatarStore(DataSource dataSource, Clock clock) {
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

	public AvatarProfile insertFirst(AvatarProfile profile, AvatarImage image) {
		requireInsertPair(profile, image);
		try {
			AvatarProfile stored = transactions.execute(status -> insertFirstInTransaction(profile, image));
			if (stored == null) {
				throw new IllegalStateException("avatar insert transaction returned no profile");
			}
			return stored;
		}
		catch (DataAccessException ex) {
			throw translateUnique(ex);
		}
	}

	public Optional<AvatarProfile> findByUser(UserId userId) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		return one(jdbc.query(
			"SELECT id, user_id, primary_image_id, status, revision, created_at, updated_at "
				+ "FROM avatar_profile WHERE user_id = ? AND status = 'ACTIVE'",
			PROFILE_MAPPER,
			userId.value()));
	}

	public Optional<AvatarProfile> findByIdAndUser(AvatarProfileId profileId, UserId userId) {
		if (profileId == null) {
			throw new IllegalArgumentException("profileId is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		return loadProfile(profileId, userId);
	}

	public Optional<AvatarImage> findBySourceUpload(UserId userId, UploadId uploadId) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (uploadId == null) {
			throw new IllegalArgumentException("uploadId is required");
		}
		return one(jdbc.query(
			"SELECT i.id, i.profile_id, i.storage_key, i.width, i.height, i.pose, i.quality_status, "
				+ "i.source_upload_id, i.lifecycle_state, i.warnings, i.preflight_policy_version, i.created_at "
				+ "FROM avatar_image i "
				+ "JOIN avatar_profile p ON p.id = i.profile_id "
				+ "WHERE p.user_id = ? AND i.source_upload_id = ?",
			IMAGE_MAPPER,
			userId.value(),
			uploadId.value()));
	}

	public boolean isUploadConsumed(UserId userId, UploadId uploadId) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (uploadId == null) {
			throw new IllegalArgumentException("uploadId is required");
		}
		Boolean consumed = jdbc.queryForObject(
			"SELECT EXISTS (SELECT 1 FROM avatar_consumed_upload WHERE user_id = ? AND upload_id = ?)",
			Boolean.class,
			userId.value(),
			uploadId.value());
		return Boolean.TRUE.equals(consumed);
	}

	Optional<AvatarImage> findImage(AvatarImageId imageId) {
		if (imageId == null) {
			throw new IllegalArgumentException("imageId is required");
		}
		return one(jdbc.query(
			"SELECT id, profile_id, storage_key, width, height, pose, quality_status, "
				+ "source_upload_id, lifecycle_state, warnings, preflight_policy_version, created_at "
				+ "FROM avatar_image WHERE id = ?",
			IMAGE_MAPPER,
			imageId.value()));
	}

	public Optional<AvatarImage> findImage(UserId userId, AvatarImageId imageId) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (imageId == null) {
			throw new IllegalArgumentException("imageId is required");
		}
		return one(jdbc.query(
			"SELECT i.id, i.profile_id, i.storage_key, i.width, i.height, i.pose, i.quality_status, "
				+ "i.source_upload_id, i.lifecycle_state, i.warnings, i.preflight_policy_version, i.created_at "
				+ "FROM avatar_image i "
				+ "JOIN avatar_profile p ON p.id = i.profile_id "
				+ "WHERE p.user_id = ? AND i.id = ?",
			IMAGE_MAPPER,
			userId.value(),
			imageId.value()));
	}

	public Optional<AvatarImage> findByStorageKey(UserId userId, StorageKey storageKey) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (storageKey == null) {
			throw new IllegalArgumentException("storageKey is required");
		}
		return one(jdbc.query(
			"SELECT i.id, i.profile_id, i.storage_key, i.width, i.height, i.pose, i.quality_status, "
				+ "i.source_upload_id, i.lifecycle_state, i.warnings, i.preflight_policy_version, i.created_at "
				+ "FROM avatar_image i "
				+ "JOIN avatar_profile p ON p.id = i.profile_id "
				+ "WHERE p.user_id = ? AND i.storage_key = ?",
			IMAGE_MAPPER,
			userId.value(),
			storageKey.value()));
	}

	public Optional<AvatarImage> findByStorageKey(StorageKey storageKey) {
		if (storageKey == null) {
			throw new IllegalArgumentException("storageKey is required");
		}
		return one(jdbc.query(
			"SELECT id, profile_id, storage_key, width, height, pose, quality_status, "
				+ "source_upload_id, lifecycle_state, warnings, preflight_policy_version, created_at "
				+ "FROM avatar_image WHERE storage_key = ?",
			IMAGE_MAPPER,
			storageKey.value()));
	}

	public List<AvatarImage> listImagesForUser(UserId userId) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		return jdbc.query(
			"SELECT i.id, i.profile_id, i.storage_key, i.width, i.height, i.pose, i.quality_status, "
				+ "i.source_upload_id, i.lifecycle_state, i.warnings, i.preflight_policy_version, i.created_at "
				+ "FROM avatar_image i "
				+ "JOIN avatar_profile p ON p.id = i.profile_id "
				+ "WHERE p.user_id = ? "
				+ "ORDER BY i.created_at, i.id",
			IMAGE_MAPPER,
			userId.value());
	}

	public AvatarProfile replaceImage(
		AvatarProfileId profileId,
		UserId userId,
		int expectedRevision,
		AvatarImage newImage) {
		if (profileId == null) {
			throw new IllegalArgumentException("profileId is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (newImage == null) {
			throw new IllegalArgumentException("newImage is required");
		}
		if (!profileId.equals(newImage.profileId())) {
			throw new IllegalArgumentException("image.profileId must equal profileId");
		}
		if (newImage.lifecycleState() != AvatarImageLifecycle.ACTIVE) {
			throw new IllegalArgumentException("image.lifecycleState must be ACTIVE");
		}
		try {
			AvatarProfile stored = transactions.execute(status -> replaceImageInTransaction(
				profileId,
				userId,
				expectedRevision,
				newImage));
			if (stored == null) {
				throw new IllegalStateException("avatar replace transaction returned no profile");
			}
			return stored;
		}
		catch (DataAccessException ex) {
			throw translateUnique(ex);
		}
	}

	public AvatarProfile logicalDelete(AvatarProfileId profileId, UserId userId, Integer expectedRevision) {
		if (profileId == null) {
			throw new IllegalArgumentException("profileId is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		AvatarProfile existing = loadProfile(profileId, userId)
			.orElseThrow(() -> new AvatarException(AvatarProblemCode.AVATAR_NOT_FOUND));
		if ("DELETING".equals(existing.status())) {
			return existing;
		}
		AvatarProfile stored = transactions.execute(status -> logicalDeleteInTransaction(
			profileId,
			userId,
			expectedRevision));
		if (stored == null) {
			throw new IllegalStateException("avatar delete transaction returned no profile");
		}
		return stored;
	}

	public List<AvatarImage> listDeletionPending() {
		return jdbc.query(
			"SELECT id, profile_id, storage_key, width, height, pose, quality_status, "
				+ "source_upload_id, lifecycle_state, warnings, preflight_policy_version, created_at "
				+ "FROM avatar_image WHERE lifecycle_state = ?",
			IMAGE_MAPPER,
			AvatarImageLifecycle.DELETION_PENDING.name());
	}

	public void markReconciled(AvatarImageId imageId) {
		if (imageId == null) {
			throw new IllegalArgumentException("imageId is required");
		}
		transactions.execute(status -> {
			UUID profileId = jdbc.query(
				"SELECT profile_id FROM avatar_image WHERE id = ? AND lifecycle_state = ?",
				rs -> rs.next() ? rs.getObject("profile_id", UUID.class) : null,
				imageId.value(),
				AvatarImageLifecycle.DELETION_PENDING.name());
			if (profileId == null) {
				return null;
			}
			jdbc.query(
				"SELECT id FROM avatar_profile WHERE id = ? FOR UPDATE",
				rs -> {
					while (rs.next()) {
						// lock owning profile before mutating child rows
					}
					return null;
				},
				profileId);
			jdbc.update(
				"DELETE FROM avatar_image WHERE id = ? AND lifecycle_state = ?",
				imageId.value(),
				AvatarImageLifecycle.DELETION_PENDING.name());
			jdbc.update(
				"DELETE FROM avatar_profile p WHERE p.id = ? AND p.status = 'DELETING' "
					+ "AND NOT EXISTS (SELECT 1 FROM avatar_image i WHERE i.profile_id = p.id)",
				profileId);
			return null;
		});
	}

	private AvatarProfile insertFirstInTransaction(AvatarProfile profile, AvatarImage image) {
		if (loadProfileByUser(profile.userId()).isPresent()) {
			throw new AvatarException(AvatarProblemCode.AVATAR_CONFLICT);
		}
		jdbc.update(
			"INSERT INTO avatar_profile "
				+ "(id, user_id, primary_image_id, status, revision, created_at, updated_at) "
				+ "VALUES (?, ?, NULL, ?, ?, ?, ?)",
			profile.id().value(),
			profile.userId().value(),
			"ACTIVE",
			profile.revision(),
			Timestamp.from(profile.createdAt()),
			Timestamp.from(profile.updatedAt()));
		insertImage(image);
		insertConsumedUpload(profile.userId(), image);
		jdbc.update(
			"UPDATE avatar_profile SET primary_image_id = ? WHERE id = ?",
			image.id().value(),
			profile.id().value());
		return loadProfile(profile.id(), profile.userId())
			.orElseThrow(() -> new IllegalStateException("avatar profile missing after insert"));
	}

	private AvatarProfile replaceImageInTransaction(
		AvatarProfileId profileId,
		UserId userId,
		int expectedRevision,
		AvatarImage newImage) {
		List<UUID> locked = jdbc.query(
			LOCK_PROFILE_FOR_UPDATE,
			(rs, rowNum) -> rs.getObject("id", UUID.class),
			profileId.value(),
			userId.value());
		if (locked.isEmpty()) {
			throw new AvatarException(AvatarProblemCode.AVATAR_NOT_FOUND);
		}
		AvatarProfile current = loadProfile(profileId, userId)
			.orElseThrow(() -> new AvatarException(AvatarProblemCode.AVATAR_NOT_FOUND));
		if ("DELETING".equals(current.status())) {
			throw new AvatarException(AvatarProblemCode.AVATAR_NOT_FOUND);
		}
		if (current.revision() != expectedRevision) {
			throw new AvatarException(AvatarProblemCode.AVATAR_STALE_REVISION);
		}
		insertImage(newImage);
		insertConsumedUpload(userId, newImage);
		int updated = jdbc.update(
			"UPDATE avatar_profile "
				+ "SET primary_image_id = ?, revision = revision + 1, updated_at = ? "
				+ "WHERE id = ? AND user_id = ? AND revision = ? AND status = 'ACTIVE'",
			newImage.id().value(),
			Timestamp.from(Instant.now(clock)),
			profileId.value(),
			userId.value(),
			expectedRevision);
		if (updated == 0) {
			throw new AvatarException(AvatarProblemCode.AVATAR_STALE_REVISION);
		}
		retireOtherActiveImages(profileId, newImage.id());
		return loadProfile(profileId, userId)
			.orElseThrow(() -> new IllegalStateException("avatar profile missing after replace"));
	}

	private AvatarProfile logicalDeleteInTransaction(
		AvatarProfileId profileId,
		UserId userId,
		Integer expectedRevision) {
		int updated;
		if (expectedRevision == null) {
			updated = jdbc.update(
				"UPDATE avatar_profile "
					+ "SET status = 'DELETING', primary_image_id = NULL, revision = revision + 1, updated_at = ? "
					+ "WHERE id = ? AND user_id = ? AND status = 'ACTIVE'",
				Timestamp.from(Instant.now(clock)),
				profileId.value(),
				userId.value());
		}
		else {
			updated = jdbc.update(
				"UPDATE avatar_profile "
					+ "SET status = 'DELETING', primary_image_id = NULL, revision = revision + 1, updated_at = ? "
					+ "WHERE id = ? AND user_id = ? AND revision = ? AND status = 'ACTIVE'",
				Timestamp.from(Instant.now(clock)),
				profileId.value(),
				userId.value(),
				expectedRevision);
		}
		if (updated == 0) {
			AvatarProfile current = loadProfile(profileId, userId)
				.orElseThrow(() -> new AvatarException(AvatarProblemCode.AVATAR_NOT_FOUND));
			if ("DELETING".equals(current.status())) {
				return current;
			}
			if (expectedRevision != null) {
				throw new AvatarException(AvatarProblemCode.AVATAR_STALE_REVISION);
			}
			throw new AvatarException(AvatarProblemCode.AVATAR_NOT_FOUND);
		}
		jdbc.update(
			"UPDATE avatar_image SET lifecycle_state = ? WHERE profile_id = ? AND lifecycle_state = ?",
			AvatarImageLifecycle.DELETION_PENDING.name(),
			profileId.value(),
			AvatarImageLifecycle.ACTIVE.name());
		return loadProfile(profileId, userId)
			.orElseThrow(() -> new IllegalStateException("avatar profile missing after delete"));
	}

	private void insertImage(AvatarImage image) {
		jdbc.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO avatar_image "
					+ "(id, profile_id, storage_key, width, height, pose, quality_status, "
					+ "source_upload_id, lifecycle_state, warnings, preflight_policy_version, created_at) "
					+ "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
			statement.setObject(1, image.id().value());
			statement.setObject(2, image.profileId().value());
			statement.setString(3, image.storageKey().value());
			statement.setObject(4, image.width());
			statement.setObject(5, image.height());
			statement.setString(6, image.pose());
			statement.setString(7, image.qualityStatus());
			statement.setObject(8, image.sourceUploadId() == null ? null : image.sourceUploadId().value());
			statement.setString(9, image.lifecycleState().name());
			statement.setArray(10, connection.createArrayOf("text", image.warnings().toArray(String[]::new)));
			statement.setString(11, image.preflightPolicyVersion());
			statement.setTimestamp(12, Timestamp.from(image.createdAt()));
			return statement;
		});
	}

	private void insertConsumedUpload(UserId userId, AvatarImage image) {
		if (image.sourceUploadId() == null) {
			throw new IllegalArgumentException("image.sourceUploadId is required");
		}
		jdbc.update(
			"INSERT INTO avatar_consumed_upload (user_id, upload_id, consumed_at) VALUES (?, ?, ?)",
			userId.value(),
			image.sourceUploadId().value(),
			Timestamp.from(image.createdAt()));
	}

	private void retireOtherActiveImages(AvatarProfileId profileId, AvatarImageId keepImageId) {
		jdbc.update(
			"UPDATE avatar_image SET lifecycle_state = ? "
				+ "WHERE profile_id = ? AND id <> ? AND lifecycle_state = ?",
			AvatarImageLifecycle.DELETION_PENDING.name(),
			profileId.value(),
			keepImageId.value(),
			AvatarImageLifecycle.ACTIVE.name());
	}

	private Optional<AvatarProfile> loadProfile(AvatarProfileId profileId, UserId userId) {
		return one(jdbc.query(
			"SELECT id, user_id, primary_image_id, status, revision, created_at, updated_at "
				+ "FROM avatar_profile WHERE id = ? AND user_id = ?",
			PROFILE_MAPPER,
			profileId.value(),
			userId.value()));
	}

	private Optional<AvatarProfile> loadProfileByUser(UserId userId) {
		return one(jdbc.query(
			"SELECT id, user_id, primary_image_id, status, revision, created_at, updated_at "
				+ "FROM avatar_profile WHERE user_id = ?",
			PROFILE_MAPPER,
			userId.value()));
	}

	private static void requireInsertPair(AvatarProfile profile, AvatarImage image) {
		if (profile == null) {
			throw new IllegalArgumentException("profile is required");
		}
		if (image == null) {
			throw new IllegalArgumentException("image is required");
		}
		if (!profile.id().equals(image.profileId())) {
			throw new IllegalArgumentException("image.profileId must equal profile.id");
		}
		if (!"ACTIVE".equals(profile.status())) {
			throw new IllegalArgumentException("profile.status must be ACTIVE");
		}
		if (image.lifecycleState() != AvatarImageLifecycle.ACTIVE) {
			throw new IllegalArgumentException("image.lifecycleState must be ACTIVE");
		}
	}

	private static RuntimeException translateUnique(DataAccessException ex) {
		if (isUniqueViolation(ex)) {
			return new AvatarException(AvatarProblemCode.AVATAR_CONFLICT);
		}
		return ex;
	}

	private static boolean isUniqueViolation(DataAccessException ex) {
		if (ex instanceof DuplicateKeyException) {
			return true;
		}
		Throwable cause = ex.getMostSpecificCause();
		return cause instanceof SQLException sql && "23505".equals(sql.getSQLState());
	}

	private static AvatarImageId toImageId(UUID value) {
		return value == null ? null : new AvatarImageId(value);
	}

	private static UploadId toUploadId(UUID value) {
		return value == null ? null : new UploadId(value);
	}

	private static List<String> warningsFrom(ResultSet rs) throws SQLException {
		Array array = rs.getArray("warnings");
		if (array == null) {
			return List.of();
		}
		Object raw = array.getArray();
		if (raw instanceof String[] values) {
			return List.of(values);
		}
		if (raw instanceof Object[] values) {
			String[] copied = new String[values.length];
			for (int i = 0; i < values.length; i++) {
				copied[i] = String.valueOf(values[i]);
			}
			return List.of(copied);
		}
		return List.of();
	}

	private static <T> Optional<T> one(List<T> values) {
		return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
	}
}
