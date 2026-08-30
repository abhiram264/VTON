package com.aifashionvault.normalization;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.TimeZone;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductCorrectionTest {

	private static final Instant NOW = Instant.parse("2026-08-30T12:00:00Z");
	private static final UserId OWNER = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final UserId OTHER = new UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final VaultItemId ITEM = new VaultItemId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
		.withDatabaseName("postgres")
		.withStartupTimeout(Duration.ofMinutes(5));

	private static JdbcTemplate jdbc;
	private static JdbcProductCorrectionStore store;

	@BeforeAll
	static void migrate() {
		POSTGRES.start();
		Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration").load().migrate();
		var dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(dataSource);
		store = new JdbcProductCorrectionStore(dataSource);
	}

	@BeforeEach
	void seedOwnerAndItem() {
		jdbc.update("TRUNCATE TABLE product_correction_event, vault_item, users CASCADE");
		insertUser(OWNER);
		insertUser(OTHER);
		insertItem(ITEM, OWNER);
	}

	@Test
	void appendsAndReadsExactImmutableEventForOwner() {
		CorrectionEvent event = event(UUID.randomUUID());

		assertThat(store.append(event)).isEqualTo(event);
		assertThat(store.find(OWNER, event.id())).contains(event);
		assertThat(store.find(OTHER, event.id())).isEmpty();
	}

	@Test
	void invalidCorrectionAttemptsFailBeforePersistence() {
		UUID id = UUID.randomUUID();
		assertThatThrownBy(() -> new CorrectionEvent(id, OWNER, ITEM, ProductCategory.TOP,
			ProductCategory.BOTTOM, 0.70, NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CorrectionEvent(id, OWNER, ITEM, ProductCategory.TOP,
			ProductCategory.UNKNOWN, 0.5, NOW)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CorrectionEvent(id, OWNER, ITEM, ProductCategory.TOP,
			ProductCategory.TOP, 0.5, NOW)).isInstanceOf(IllegalArgumentException.class);
		for (double invalid : new double[] {-0.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY}) {
			assertThatThrownBy(() -> new CorrectionEvent(id, OWNER, ITEM, ProductCategory.TOP,
				ProductCategory.BOTTOM, invalid, NOW)).isInstanceOf(IllegalArgumentException.class);
		}
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_correction_event", Integer.class)).isZero();
	}

	@Test
	void identicalReplayIsIdempotentButConflictingIdentityIsRejected() {
		CorrectionEvent original = event(UUID.randomUUID());
		store.append(original);

		assertThat(store.append(original)).isEqualTo(original);
		CorrectionEvent conflict = new CorrectionEvent(original.id(), OWNER, ITEM, ProductCategory.TOP,
			ProductCategory.BAG, original.confidence(), original.createdAt());
		assertThatThrownBy(() -> store.append(conflict))
			.isInstanceOf(CorrectionEventIdentityConflictException.class);
		assertThat(store.find(OWNER, original.id())).contains(original);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM product_correction_event", Integer.class)).isOne();
	}

	@Test
	void differentEventIdentityAppendsAnotherObservation() {
		store.append(event(UUID.randomUUID()));
		store.append(event(UUID.randomUUID()));

		assertThat(store.findAll(OWNER)).hasSize(2);
	}

	@Test
	void databaseRejectsCrossOwnerItemPair() {
		CorrectionEvent crossOwner = new CorrectionEvent(UUID.randomUUID(), OTHER, ITEM,
			ProductCategory.TOP, ProductCategory.BOTTOM, 0.5, NOW);

		assertThatThrownBy(() -> store.append(crossOwner)).isInstanceOf(DataIntegrityViolationException.class);
		assertThat(store.findAll(OWNER)).isEmpty();
		assertThat(store.findAll(OTHER)).isEmpty();
	}

	@Test
	void deletionOfItemOrUserCascadesCorrectionEvents() {
		CorrectionEvent itemEvent = event(UUID.randomUUID());
		store.append(itemEvent);
		jdbc.update("DELETE FROM vault_item WHERE id = ?", ITEM.value());
		assertThat(store.find(OWNER, itemEvent.id())).isEmpty();

		VaultItemId secondItem = new VaultItemId(UUID.randomUUID());
		insertItem(secondItem, OWNER);
		CorrectionEvent userEvent = new CorrectionEvent(UUID.randomUUID(), OWNER, secondItem,
			ProductCategory.TOP, ProductCategory.BOTTOM, 0.5, NOW);
		store.append(userEvent);
		jdbc.update("DELETE FROM vault_item WHERE user_id = ?", OWNER.value());
		jdbc.update("DELETE FROM users WHERE id = ?", OWNER.value());
		assertThat(store.find(OWNER, userEvent.id())).isEmpty();
	}

	private CorrectionEvent event(UUID id) {
		return new CorrectionEvent(id, OWNER, ITEM, ProductCategory.TOP, ProductCategory.BOTTOM, 0.5, NOW);
	}

	private void insertUser(UserId userId) {
		Timestamp now = Timestamp.from(NOW);
		jdbc.update("INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES (?, ?, ?, NULL)",
			userId.value(), now, now);
	}

	private void insertItem(VaultItemId itemId, UserId owner) {
		jdbc.update("INSERT INTO vault_item (id, user_id, storage_key, created_at) VALUES (?, ?, ?, ?)",
			itemId.value(), owner.value(), "vault/" + itemId.value(), Timestamp.from(NOW));
	}
}
