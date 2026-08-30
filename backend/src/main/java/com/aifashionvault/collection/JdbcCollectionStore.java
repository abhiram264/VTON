package com.aifashionvault.collection;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.aifashionvault.shared.CollectionId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

public final class JdbcCollectionStore implements CollectionStore {
	private static final String COLLECTIONS = "SELECT id,user_id,name,created_at,updated_at FROM collection WHERE user_id=?";
	private final JdbcTemplate jdbc;
	private final TransactionTemplate transactions;

	public JdbcCollectionStore(DataSource dataSource) {
		if (dataSource == null) throw new IllegalArgumentException("dataSource is required");
		jdbc = new JdbcTemplate(dataSource);
		transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}

	@Override
	public Collection create(UserId ownerId, CollectionId collectionId, String name, Instant now) {
		jdbc.update("INSERT INTO collection(id,user_id,name,created_at,updated_at) VALUES(?,?,?,?,?)",
			collectionId.value(), ownerId.value(), name, timestamp(now), timestamp(now));
		return find(ownerId, collectionId).orElseThrow();
	}

	@Override
	public List<Collection> list(UserId ownerId) {
		return jdbc.query(COLLECTIONS + " ORDER BY created_at ASC,id ASC", (rs, row) -> collection(
			rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("name"),
			rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), ownerId.value());
	}

	@Override
	public Optional<Collection> find(UserId ownerId, CollectionId collectionId) {
		return jdbc.query(COLLECTIONS + " AND id=?", (rs, row) -> collection(
			rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("name"),
			rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()),
			ownerId.value(), collectionId.value()).stream().findFirst();
	}

	@Override
	public Optional<Collection> rename(UserId ownerId, CollectionId collectionId, String name, Instant now) {
		jdbc.update("UPDATE collection SET name=?,updated_at=? WHERE id=? AND user_id=?",
			name, timestamp(now), collectionId.value(), ownerId.value());
		return find(ownerId, collectionId);
	}

	@Override
	public boolean delete(UserId ownerId, CollectionId collectionId) {
		return jdbc.update("DELETE FROM collection WHERE id=? AND user_id=?", collectionId.value(), ownerId.value()) == 1;
	}

	@Override
	public MembershipResult addItem(UserId ownerId, CollectionId collectionId, VaultItemId vaultItemId, Instant now) {
		return transactions.execute(status -> {
			Optional<Collection> existing = locked(ownerId, collectionId);
			if (existing.isEmpty()) return result(MembershipOutcome.COLLECTION_NOT_FOUND, null);
			if (!exists("SELECT EXISTS(SELECT 1 FROM vault_item WHERE id=? AND user_id=?)", vaultItemId.value(), ownerId.value()))
				return result(MembershipOutcome.VAULT_ITEM_NOT_FOUND, null);
			try {
				int inserted = jdbc.update("INSERT INTO collection_item(collection_id,vault_item_id,created_at) VALUES(?,?,?) ON CONFLICT DO NOTHING",
					collectionId.value(), vaultItemId.value(), timestamp(now));
				if (inserted == 1) jdbc.update("UPDATE collection SET updated_at=? WHERE id=? AND user_id=?", timestamp(now), collectionId.value(), ownerId.value());
				return result(inserted == 1 ? MembershipOutcome.CHANGED : MembershipOutcome.UNCHANGED, find(ownerId, collectionId).orElseThrow());
			}
			catch (DuplicateKeyException ignored) {
				return result(MembershipOutcome.UNCHANGED, find(ownerId, collectionId).orElseThrow());
			}
		});
	}

	@Override
	public MembershipResult removeItem(UserId ownerId, CollectionId collectionId, VaultItemId vaultItemId, Instant now) {
		return transactions.execute(status -> {
			Optional<Collection> existing = locked(ownerId, collectionId);
			if (existing.isEmpty()) return result(MembershipOutcome.COLLECTION_NOT_FOUND, null);
			int deleted = jdbc.update("DELETE FROM collection_item WHERE collection_id=? AND vault_item_id=?", collectionId.value(), vaultItemId.value());
			if (deleted == 1) jdbc.update("UPDATE collection SET updated_at=? WHERE id=? AND user_id=?", timestamp(now), collectionId.value(), ownerId.value());
			return result(deleted == 1 ? MembershipOutcome.CHANGED : MembershipOutcome.UNCHANGED, find(ownerId, collectionId).orElseThrow());
		});
	}

	private Optional<Collection> locked(UserId ownerId, CollectionId id) {
		return jdbc.query(COLLECTIONS + " AND id=? FOR UPDATE", (rs, row) -> collection(
			rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("name"),
			rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()), ownerId.value(), id.value()).stream().findFirst();
	}

	private Collection collection(UUID id, UUID owner, String name, Instant created, Instant updated) {
		CollectionId collectionId = new CollectionId(id);
		List<CollectionItem> items = jdbc.query("SELECT vault_item_id,created_at FROM collection_item WHERE collection_id=? ORDER BY created_at ASC,vault_item_id ASC",
			(rs, row) -> new CollectionItem(collectionId, new VaultItemId(rs.getObject("vault_item_id", UUID.class)), rs.getTimestamp("created_at").toInstant()), id);
		return new Collection(collectionId, new UserId(owner), name, created, updated, items);
	}

	private boolean exists(String sql, Object... arguments) { return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, arguments)); }
	private static Timestamp timestamp(Instant value) { return Timestamp.from(value); }
	private static MembershipResult result(MembershipOutcome outcome, Collection collection) { return new MembershipResult(outcome, collection); }
}
