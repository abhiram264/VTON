package com.aifashionvault.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.TimeZone;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.platform.web.ShareImportMediaAccess;
import com.aifashionvault.platform.web.ShareImportService;
import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

/**
 * Confirms a resolved share import becomes an owned Vault item (PR-08, F04).
 *
 * <p>Deliberately NOT a {@code @SpringBootTest}: at the pinned base no
 * {@code @SpringBootTest} context can load (GenerationMediaStore
 * {@code NoUniqueBeanDefinitionException}, plus test-side
 * {@code BeanDefinitionOverrideException}), so the HTTP layer is exercised
 * through its real collaborators — the production {@link JdbcShareImportStore}
 * and {@link ShareImportService} over real migrated PostgreSQL — with only the
 * outbound media seam faked. No network, no S3, no Spring context.
 */
class ShareImportConfirmIntegrationTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
	private static final StorageKey RESOLVED_KEY = new StorageKey("users/11111111/vault/resolved.png");
	private static final ShareImportResolverChainPassthrough CHAINS = new ShareImportResolverChainPassthrough();

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	private static JdbcTemplate jdbc;
	private static JdbcShareImportStore store;
	private static ShareImportService service;

	@BeforeAll
	static void startDatabaseAndMigrate() {
		POSTGRES.start();
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();
		DataSourceHolder.dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(DataSourceHolder.dataSource);
		store = new JdbcShareImportStore(DataSourceHolder.dataSource, new FixedClock(NOW));
		service = new ShareImportService(store, NoMedia.INSTANCE, CHAINS);
	}

	@BeforeEach
	void clearTables() {
		jdbc.update("TRUNCATE TABLE share_import, source_reference, vault_item, users CASCADE");
		insertUser(OWNER);
		insertUser(STRANGER);
	}

	@Test
	void confirmedImportBecomesAnOwnedVaultItem() {
		ShareImportId importId = readyUrlImport(OWNER);

		var confirmed = service.confirm(OWNER, importId.value().toString());

		assertThat(confirmed.id()).isNotBlank();
		assertThat(confirmed.sourceReferenceId()).isEqualTo(importId.value().toString());
		// The item belongs to the confirming owner and points at the object the
		// resolver already published, reusing it instead of re-uploading.
		assertThat(jdbc.queryForObject("SELECT user_id FROM vault_item WHERE id = ?",
			UUID.class, UUID.fromString(confirmed.id()))).isEqualTo(OWNER.value());
		assertThat(jdbc.queryForObject("SELECT storage_key FROM vault_item WHERE id = ?",
			String.class, UUID.fromString(confirmed.id()))).isEqualTo(RESOLVED_KEY.value());
		// Provenance survives confirmation as a real source_reference row.
		assertThat(jdbc.queryForObject("SELECT url FROM source_reference WHERE id = ?",
			String.class, importId.value())).isEqualTo("https://shop.example.test/item");
	}

	@Test
	void anotherOwnerIsRefusedAndNothingIsSavedForThem() {
		ShareImportId importId = readyUrlImport(OWNER);

		assertThatThrownBy(() -> service.confirm(STRANGER, importId.value().toString()))
			.isInstanceOf(com.aifashionvault.platform.web.ShareImportProblem.class);
		// The stranger learned nothing and saved nothing; the owner can still
		// confirm their own import afterwards.
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vault_item WHERE user_id = ?",
			Integer.class, STRANGER.value())).isZero();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vault_item WHERE user_id = ?",
			Integer.class, OWNER.value())).isZero();
		assertThat(service.confirm(OWNER, importId.value().toString()).id()).isNotBlank();
	}

	@Test
	void repeatConfirmationReturnsTheSameSavedItem() {
		ShareImportId importId = readyUrlImport(OWNER);

		String first = service.confirm(OWNER, importId.value().toString()).id();
		String again = service.confirm(OWNER, importId.value().toString()).id();

		assertThat(again).isEqualTo(first);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vault_item", Integer.class)).isEqualTo(1);
	}

	@Test
	void unresolvedImportIsNotConfirmable() {
		ShareImportId importId = new ShareImportId(UUID.randomUUID());
		jdbc.update("INSERT INTO share_import (id, user_id, source_kind, source_url, resolver_state, "
				+ "idempotency_key, request_fingerprint, attempt_count, created_at, updated_at) "
				+ "VALUES (?, ?, 'URL', 'https://shop.example.test/item', 'NEEDS_IMAGE', ?, 'f', 1, ?, ?)",
			importId.value(), OWNER.value(), "key-0123456789abcd", java.sql.Timestamp.from(NOW),
			java.sql.Timestamp.from(NOW));
		jdbc.update("UPDATE share_import SET terminal_reason_code = 'URL_RESOLUTION_FAILED' WHERE id = ?",
			importId.value());

		assertThatThrownBy(() -> service.confirm(OWNER, importId.value().toString()))
			.isInstanceOf(com.aifashionvault.platform.web.ShareImportProblem.class);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM vault_item", Integer.class)).isZero();
	}

	private static final UserId OWNER = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final UserId STRANGER = new UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"));

	private static ShareImportId readyUrlImport(UserId owner) {
		ShareImportId id = new ShareImportId(UUID.randomUUID());
		jdbc.update("INSERT INTO share_import (id, user_id, source_kind, source_url, storage_key, resolver_state, "
				+ "idempotency_key, request_fingerprint, attempt_count, source_title, source_site_name, created_at, updated_at) "
				+ "VALUES (?, ?, 'URL', 'https://shop.example.test/item', ?, 'READY', ?, 'f', 1, 'Rare Rabbit Shirt', 'Shop', ?, ?)",
			id.value(), owner.value(), RESOLVED_KEY.value(), "key-0123456789abcd",
			java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
		return id;
	}

	private static void insertUser(UserId user) {
		jdbc.update("INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES (?, ?, ?, NULL)",
			user.value(), java.sql.Timestamp.from(NOW), java.sql.Timestamp.from(NOW));
	}

	/** Media seam is never touched by confirmation: the object already exists. */
	private enum NoMedia implements ShareImportMediaAccess {

		INSTANCE;

		@Override
		public Optional<ImportMediaAccess.VerifiedImage> findVerifiedUpload(UserId ownerId, UploadId uploadId) {
			return Optional.empty();
		}

		@Override
		public ImportMediaAccess.ClaimOutcome claimVerifiedUpload(UserId ownerId,
				ImportMediaAccess.VerifiedImage upload, StorageKey destination) {
			throw new AssertionError("confirmation must not re-claim a staging upload");
		}

		@Override
		public boolean publishFetchedImage(UserId ownerId, InputStream content, long contentLength, StorageKey destination) {
			throw new AssertionError("confirmation must not publish a fetched image");
		}

		@Override
		public void deleteObject(StorageKey key) {
			throw new AssertionError("confirmation must not delete objects");
		}
	}

	private static final class ShareImportResolverChainPassthrough
			implements com.aifashionvault.platform.web.ShareImportResolverChain {

		@Override
		public ImportResolver chain(UserId ownerId, ShareImportId importId, String attemptToken) {
			throw new AssertionError("confirmation must not re-resolve a source");
		}
	}

	private static final class FixedClock extends java.time.Clock {

		private final Instant instant;

		private FixedClock(Instant instant) {
			this.instant = instant;
		}

		@Override
		public java.time.ZoneId getZone() {
			return java.time.ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}

	private static final class DataSourceHolder {

		private static DriverManagerDataSource dataSource;
	}
}
