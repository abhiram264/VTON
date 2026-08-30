package com.aifashionvault.look;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.StudioSlot;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

public class JdbcLookStore implements LookStore {

	private static final String SELECT_LOOK =
		"SELECT id, user_id, avatar_profile_id, generation_job_id, studio_session_id, "
			+ "storage_key, render_plan, provider, provider_model, quality_status, created_at, deleted_at "
			+ "FROM look ";

	private static final RowMapper<Look> LOOK_MAPPER = (rs, rowNum) -> new Look(
		new LookId(rs.getObject("id", UUID.class)),
		new UserId(rs.getObject("user_id", UUID.class)),
		toAvatarProfileId(rs.getObject("avatar_profile_id", UUID.class)),
		toGenerationJobId(rs.getObject("generation_job_id", UUID.class)),
		toStudioSessionId(rs.getObject("studio_session_id", UUID.class)),
		new StorageKey(rs.getString("storage_key")),
		rs.getString("render_plan"),
		rs.getString("provider"),
		rs.getString("provider_model"),
		QualityDecision.valueOf(rs.getString("quality_status")),
		rs.getTimestamp("created_at").toInstant(),
		List.of());

	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;

	public JdbcLookStore(DataSource dataSource) {
		if (dataSource == null) {
			throw new IllegalArgumentException("dataSource is required");
		}
		this.jdbc = new JdbcTemplate(dataSource);
		this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}

	@Override
	public Look insert(Look look) {
		requirePassLook(look);
		try {
			transactions.execute(status -> {
				insertLook(look);
				insertItems(look);
				return null;
			});
			return withItems(look, loadItems(look.id()));
		}
		catch (DataAccessException ex) {
			if (isUniqueViolation(ex)) {
				return existingLook(look);
			}
			throw ex;
		}
	}

	@Override
	public Optional<Look> insertIfJobActive(Look look) {
		requirePassLook(look);
		try {
			Optional<Look> inserted = transactions.execute(status -> {
				if (!lockActiveGenerationJob(look.generationJobId())) {
					return Optional.empty();
				}
				insertLook(look);
				insertItems(look);
				if (!completeActiveJob(look.generationJobId(), look.createdAt())) {
					status.setRollbackOnly();
					return Optional.empty();
				}
				insertLookReadyEvent(look);
				return Optional.of(withItems(look, loadItems(look.id())));
			});
			return inserted == null ? Optional.empty() : inserted;
		}
		catch (DataAccessException ex) {
			if (isUniqueViolation(ex)) {
				return Optional.of(existingLook(look));
			}
			throw ex;
		}
	}

	@Override
	public Optional<Look> findById(LookId id) {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		return one(jdbc.query(SELECT_LOOK + "WHERE id = ? AND deleted_at IS NULL", LOOK_MAPPER, id.value()))
			.map(this::withLoadedItems);
	}

    @Override
    public Optional<Look> findByGenerationJobId(GenerationJobId generationJobId) {
        if (generationJobId == null) {
            throw new IllegalArgumentException("generationJobId is required");
        }
        return one(jdbc.query(
            SELECT_LOOK + "WHERE generation_job_id = ? AND deleted_at IS NULL",
            LOOK_MAPPER,
            generationJobId.value()))
            .map(this::withLoadedItems);
    }

    @Override
    public Optional<Look> findByGenerationJobIdForReplay(GenerationJobId generationJobId) {
        if (generationJobId == null) {
            throw new IllegalArgumentException("generationJobId is required");
        }
        return one(jdbc.query(
            SELECT_LOOK + "WHERE generation_job_id = ?",
            LOOK_MAPPER,
            generationJobId.value()))
            .map(this::withLoadedItems);
    }

	@Override
	public Optional<Look> findByIdForUser(LookId id, UserId ownerId) {
		requireOwnerLookup(id, ownerId);
		return one(jdbc.query(
			SELECT_LOOK + "WHERE id = ? AND user_id = ? AND deleted_at IS NULL",
			LOOK_MAPPER,
			id.value(),
			ownerId.value()))
			.map(this::withLoadedItems);
	}

	@Override
	public List<Look> listForUser(UserId ownerId) {
		if (ownerId == null) {
			throw new IllegalArgumentException("ownerId is required");
		}
		return jdbc.query(
			SELECT_LOOK + "WHERE user_id = ? AND deleted_at IS NULL ORDER BY created_at DESC, id DESC",
			LOOK_MAPPER,
			ownerId.value()).stream().map(this::withLoadedItems).toList();
	}

	/**
	 * Returns an owner-scoped row even after logical deletion so repeated delete
	 * requests can retry physical object cleanup without making the Look readable.
	 */
	public Optional<Look> findByIdForCleanup(LookId id, UserId ownerId) {
		requireOwnerLookup(id, ownerId);
		return one(jdbc.query(
			SELECT_LOOK + "WHERE id = ? AND user_id = ?",
			LOOK_MAPPER,
			id.value(),
			ownerId.value()))
			.map(this::withLoadedItems);
	}

	@Override
	public boolean logicallyDeleteForUser(LookId id, UserId ownerId) {
		requireOwnerLookup(id, ownerId);
		return jdbc.update(
			"UPDATE look SET deleted_at = COALESCE(deleted_at, CURRENT_TIMESTAMP) WHERE id = ? AND user_id = ?",
			id.value(),
			ownerId.value()) == 1;
	}

	private void requirePassLook(Look look) {
		if (look == null) {
			throw new IllegalArgumentException("look is required");
		}
		rejectHttpStorageKey(look.storageKey());
		if (look.qualityStatus() != QualityDecision.PASS) {
			throw new IllegalArgumentException("qualityStatus must be PASS");
		}
	}

	private boolean lockActiveGenerationJob(GenerationJobId generationJobId) {
		if (generationJobId == null) {
			return false;
		}
		List<Boolean> active = jdbc.query(
			"SELECT cancelled_at, subject_deleted_at, completed_at FROM generation_job WHERE id = ? FOR UPDATE",
			(rs, rowNum) -> rs.getTimestamp("cancelled_at") == null
				&& rs.getTimestamp("subject_deleted_at") == null
				&& rs.getTimestamp("completed_at") == null,
			generationJobId.value());
		return !active.isEmpty() && Boolean.TRUE.equals(active.getFirst());
	}
	private boolean completeActiveJob(GenerationJobId generationJobId, Instant completedAt) {
		if (generationJobId == null || completedAt == null) {
			return false;
		}
		return jdbc.update(
			"UPDATE generation_job SET status = 'COMPLETED', completed_at = ?, updated_at = ?, "
				+ "status_version = status_version + 1 "
				+ "WHERE id = ? AND completed_at IS NULL AND cancelled_at IS NULL AND subject_deleted_at IS NULL "
				+ "AND status IN ('QUEUED','PREFLIGHT','SUBMITTED','GENERATING','QUALITY_CHECK','RETRYING')",
			Timestamp.from(completedAt),
			Timestamp.from(completedAt),
			generationJobId.value()) == 1;
	}

	private void insertLookReadyEvent(Look look) {
		if (look.generationJobId() == null) {
			return;
		}
		jdbc.update(
			"INSERT INTO generation_notification_event "
				+ "(id, user_id, generation_job_id, type, look_id, created_at, deadline_at) "
				+ "VALUES (?, ?, ?, 'LOOK_READY', ?, ?, ?) "
				+ "ON CONFLICT (generation_job_id, type) DO NOTHING",
			UUID.randomUUID(),
			look.userId().value(),
			look.generationJobId().value(),
			look.id().value(),
			Timestamp.from(look.createdAt()),
			Timestamp.from(look.createdAt().plusSeconds(24L * 60L * 60L)));
	}

	private void insertLook(Look look) {
		jdbc.update(connection -> {
			PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO look "
					+ "(id, user_id, avatar_profile_id, generation_job_id, studio_session_id, "
					+ "storage_key, render_plan, provider, provider_model, quality_status, created_at) "
					+ "VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)");
			statement.setObject(1, look.id().value());
			statement.setObject(2, look.userId().value());
			statement.setObject(3, look.avatarProfileId() == null ? null : look.avatarProfileId().value());
			statement.setObject(4, look.generationJobId() == null ? null : look.generationJobId().value());
			statement.setObject(5, look.studioSessionId() == null ? null : look.studioSessionId().value());
			statement.setString(6, look.storageKey().value());
			statement.setString(7, look.renderPlan());
			statement.setString(8, look.provider());
			statement.setString(9, look.providerModel());
			statement.setString(10, look.qualityStatus().name());
			statement.setTimestamp(11, Timestamp.from(look.createdAt()));
			return statement;
		});
	}

	private void insertItems(Look look) {
		for (LookItem item : look.items()) {
			jdbc.update(
				"INSERT INTO look_item (look_id, vault_item_id, slot) VALUES (?, ?, ?)",
				look.id().value(),
				item.vaultItemId().value(),
				item.slot() == null ? null : item.slot().name());
		}
	}

    private Look existingLook(Look look) {
        if (look.generationJobId() != null) {
            Optional<Look> byJob = findByGenerationJobIdForReplay(look.generationJobId());
            if (byJob.isPresent()) {
                return byJob.get();
            }
        }
        return findById(look.id()).orElseThrow(() -> new IllegalStateException("look missing after unique conflict"));
    }

	private Look withLoadedItems(Look look) {
		return withItems(look, loadItems(look.id()));
	}

	private List<LookItem> loadItems(LookId lookId) {
		return jdbc.query(
			"SELECT look_id, vault_item_id, slot FROM look_item WHERE look_id = ? ORDER BY vault_item_id",
			(rs, rowNum) -> new LookItem(
				new LookId(rs.getObject("look_id", UUID.class)),
				new VaultItemId(rs.getObject("vault_item_id", UUID.class)),
				toSlot(rs.getString("slot"))),
			lookId.value());
	}

	private static void rejectHttpStorageKey(StorageKey storageKey) {
		String value = storageKey.value();
		String lower = value.toLowerCase(Locale.ROOT);
		if (lower.startsWith("http://") || lower.startsWith("https://")) {
			throw new IllegalArgumentException("storage key must not be a URL");
		}
	}

	private static void requireOwnerLookup(LookId id, UserId ownerId) {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (ownerId == null) {
			throw new IllegalArgumentException("ownerId is required");
		}
	}

	private static Look withItems(Look look, List<LookItem> items) {
		return new Look(
			look.id(),
			look.userId(),
			look.avatarProfileId(),
			look.generationJobId(),
			look.studioSessionId(),
			look.storageKey(),
			look.renderPlan(),
			look.provider(),
			look.providerModel(),
			look.qualityStatus(),
			look.createdAt(),
			items);
	}

	private static AvatarProfileId toAvatarProfileId(UUID value) {
		return value == null ? null : new AvatarProfileId(value);
	}

	private static GenerationJobId toGenerationJobId(UUID value) {
		return value == null ? null : new GenerationJobId(value);
	}

	private static StudioSessionId toStudioSessionId(UUID value) {
		return value == null ? null : new StudioSessionId(value);
	}

	private static StudioSlot toSlot(String value) {
		return value == null ? null : StudioSlot.valueOf(value);
	}

	private static boolean isUniqueViolation(DataAccessException ex) {
		if (ex instanceof DuplicateKeyException) {
			return true;
		}
		Throwable cause = ex.getMostSpecificCause();
		return cause instanceof SQLException sql && "23505".equals(sql.getSQLState());
	}

	private static <T> Optional<T> one(List<T> values) {
		return values.isEmpty() ? Optional.empty() : Optional.of(values.getFirst());
	}
}
