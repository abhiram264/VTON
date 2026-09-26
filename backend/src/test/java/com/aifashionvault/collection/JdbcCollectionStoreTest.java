package com.aifashionvault.collection;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.TimeZone;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.shared.CollectionId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JdbcCollectionStoreTest {
	static { TimeZone.setDefault(TimeZone.getTimeZone("UTC")); }
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18").withDatabaseName("postgres").withStartupTimeout(Duration.ofMinutes(5));
	private static final UserId OWNER = userId("11111111-1111-1111-1111-111111111111");
	private static final UserId OTHER = userId("22222222-2222-2222-2222-222222222222");
	private static final Instant T0 = Instant.parse("2026-08-30T10:00:00Z");
	private JdbcTemplate jdbc;
	private JdbcCollectionStore store;

	@BeforeAll static void database() { POSTGRES.start(); }
	@BeforeEach void reset() {
		var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		org.flywaydb.core.Flyway.configure().dataSource(source).load().migrate();
		jdbc = new JdbcTemplate(source);
		jdbc.update("TRUNCATE collection, vault_item, users CASCADE");
		insertUser(OWNER); insertUser(OTHER); store = new JdbcCollectionStore(source);
	}

	@Test void ownerScopedCrudOrderingAndTimestamps() {
		CollectionId lower = collectionId("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		CollectionId higher = collectionId("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
		store.create(OWNER, higher, "Higher", T0); store.create(OWNER, lower, "Lower", T0);
		store.create(OTHER, collectionId("cccccccc-cccc-cccc-cccc-cccccccccccc"), "Private", T0);
		assertThat(store.list(OWNER)).extracting(Collection::id).containsExactly(lower, higher);
		Collection renamed = store.rename(OWNER, lower, "Renamed", T0.plusSeconds(1)).orElseThrow();
		assertThat(renamed.name()).isEqualTo("Renamed"); assertThat(renamed.createdAt()).isEqualTo(T0); assertThat(renamed.updatedAt()).isEqualTo(T0.plusSeconds(1));
		assertThat(store.find(OTHER, lower)).isEmpty(); assertThat(store.delete(OTHER, lower)).isFalse(); assertThat(store.delete(OWNER, lower)).isTrue();
	}

	@Test void membershipsAreOwnerCheckedManyToManyOrderedAndReplaySafe() {
		CollectionId first = collectionId("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"), second = collectionId("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
		VaultItemId high = insertItem("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee", OWNER), low = insertItem("dddddddd-dddd-dddd-dddd-dddddddddddd", OWNER);
		VaultItemId foreign = insertItem("ffffffff-ffff-ffff-ffff-ffffffffffff", OTHER);
		store.create(OWNER, first, "One", T0); store.create(OWNER, second, "Two", T0);
		assertThat(store.addItem(OWNER, first, foreign, T0.plusSeconds(1)).outcome()).isEqualTo(MembershipOutcome.VAULT_ITEM_NOT_FOUND);
		assertThat(store.addItem(OWNER, first, high, T0.plusSeconds(2)).outcome()).isEqualTo(MembershipOutcome.CHANGED);
		MembershipResult replay = store.addItem(OWNER, first, high, T0.plusSeconds(9));
		store.addItem(OWNER, first, low, T0.plusSeconds(2)); store.addItem(OWNER, second, high, T0.plusSeconds(3));
		assertThat(replay.outcome()).isEqualTo(MembershipOutcome.UNCHANGED); assertThat(replay.collection().updatedAt()).isEqualTo(T0.plusSeconds(2));
		assertThat(store.find(OWNER, first).orElseThrow().items()).extracting(CollectionItem::vaultItemId).containsExactly(low, high);
		assertThat(store.find(OWNER, second).orElseThrow().items()).hasSize(1);
		assertThat(store.removeItem(OWNER, first, high, T0.plusSeconds(10)).outcome()).isEqualTo(MembershipOutcome.CHANGED);
		MembershipResult absent = store.removeItem(OWNER, first, high, T0.plusSeconds(11));
		assertThat(absent.outcome()).isEqualTo(MembershipOutcome.UNCHANGED); assertThat(absent.collection().updatedAt()).isEqualTo(T0.plusSeconds(10));
	}
	@Test void compositeOwnerConstraintRejectsCrossOwnerMembership() {
		CollectionId collection = collectionId("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		VaultItemId foreign = insertItem("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee", OTHER);
		store.create(OWNER, collection, "One", T0);

		assertThatThrownBy(() -> jdbc.update(
			"INSERT INTO collection_item(collection_id, vault_item_id, user_id, created_at) VALUES (?, ?, ?, ?)",
			collection.value(), foreign.value(), OWNER.value(), Timestamp.from(T0)))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	@Test void deletingCollectionCascadesMembershipButNotVaultItem() {
		CollectionId collection = collectionId("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"); VaultItemId item = insertItem("dddddddd-dddd-dddd-dddd-dddddddddddd", OWNER);
		store.create(OWNER, collection, "One", T0); store.addItem(OWNER, collection, item, T0.plusSeconds(1)); store.delete(OWNER, collection);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM collection_item", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM vault_item WHERE id=?", Integer.class, item.value())).isOne();
	}

	private void insertUser(UserId id) { jdbc.update("INSERT INTO users(id,created_at,updated_at) VALUES(?,?,?)", id.value(), Timestamp.from(T0), Timestamp.from(T0)); }
	private VaultItemId insertItem(String value, UserId owner) { VaultItemId id = new VaultItemId(UUID.fromString(value)); jdbc.update("INSERT INTO vault_item(id,user_id,storage_key,created_at) VALUES(?,?,?,?)", id.value(), owner.value(), "vault/" + id.value(), Timestamp.from(T0)); return id; }
	private static CollectionId collectionId(String value) { return new CollectionId(UUID.fromString(value)); }
	private static UserId userId(String value) { return new UserId(UUID.fromString(value)); }
}
