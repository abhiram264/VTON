package com.aifashionvault.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
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

import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.platform.web.ShareImportMediaAccess;
import com.aifashionvault.platform.web.ShareImportProblem;
import com.aifashionvault.platform.web.ShareImportProblemCode;
import com.aifashionvault.platform.web.ShareImportService;
import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.shared.UserId;

/**
 * The owner-scoped preview of a resolved share import (C07 clause 2): a
 * time-boxed signed read plus safe source metadata, never an object key.
 *
 * <p>Deliberately NOT a {@code @SpringBootTest} (same reason as
 * {@link ShareImportConfirmIntegrationTest}): the service runs over real
 * migrated PostgreSQL through its production collaborators with only the
 * outbound media seam faked, so no network or S3 is touched.
 */
class ShareImportPreviewIntegrationTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
	private static final Instant EXPIRES_AT = NOW.plus(Duration.ofMinutes(5));
	private static final StorageKey RESOLVED_KEY = new StorageKey("users/11111111/vault/resolved.png");
	private static final UserId OWNER = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final UserId STRANGER = new UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"));

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	private static JdbcTemplate jdbc;
	private static JdbcShareImportStore store;

	@BeforeAll
	static void startDatabaseAndMigrate() {
		POSTGRES.start();
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		jdbc = new JdbcTemplate(dataSource);
		store = new JdbcShareImportStore(dataSource, new FixedClock(NOW));
	}

	@BeforeEach
	void clearTables() {
		jdbc.update("TRUNCATE TABLE share_import, source_reference, vault_item, users CASCADE");
		insertUser(OWNER);
		insertUser(STRANGER);
	}

	@Test
	void ownerSeesASignedPreviewWithSafeSourceMetadata() {
		ShareImportId importId = readyUrlImport(OWNER);
		ShareImportService service = service(new SigningMedia(RESOLVED_KEY));

		var preview = service.preview(OWNER, importId.value().toString());

		assertThat(preview.importId()).isEqualTo(importId.value().toString());
		assertThat(preview.previewUrl()).isEqualTo("https://objects.example.test/signed/resolved.png?sig=abc");
		assertThat(preview.previewExpiresAt()).isEqualTo(EXPIRES_AT);
		assertThat(preview.sourceTitle()).isEqualTo("Rare Rabbit Shirt");
		assertThat(preview.sourceSiteName()).isEqualTo("Shop");
		// No object key leaks through the preview body.
		assertThat(preview.previewUrl()).doesNotContain(RESOLVED_KEY.value());
	}

	@Test
	void previewIsReissuableSoAStaleUrlCanBeRefreshed() {
		ShareImportId importId = readyUrlImport(OWNER);
		ShareImportService service = service(new SigningMedia(RESOLVED_KEY));

		String first = service.preview(OWNER, importId.value().toString()).previewUrl();
		String again = service.preview(OWNER, importId.value().toString()).previewUrl();

		assertThat(again).isEqualTo(first);
	}

	@Test
	void anotherOwnerSeesNoPreviewAndLearnsNothing() {
		ShareImportId importId = readyUrlImport(OWNER);
		ShareImportService service = service(new SigningMedia(RESOLVED_KEY));

		assertThatThrownBy(() -> service.preview(STRANGER, importId.value().toString()))
			.isInstanceOf(ShareImportProblem.class)
			.satisfies(ex -> assertThat(((ShareImportProblem) ex).code())
				.isEqualTo(ShareImportProblemCode.IMPORT_NOT_FOUND));
		// The owner can still preview their own import.
		assertThat(service.preview(OWNER, importId.value().toString()).previewUrl()).isNotBlank();
	}

	@Test
	void anUnresolvedImportHasNoPreview() {
		ShareImportId importId = new ShareImportId(UUID.randomUUID());
		jdbc.update("INSERT INTO share_import (id, user_id, source_kind, source_url, resolver_state, "
				+ "terminal_reason_code, idempotency_key, request_fingerprint, attempt_count, created_at, updated_at) "
				+ "VALUES (?, ?, 'URL', 'https://shop.example.test/item', 'NEEDS_IMAGE', 'URL_RESOLUTION_FAILED', ?, 'f', 1, ?, ?)",
			importId.value(), OWNER.value(), "key-0123456789abcd", java.sql.Timestamp.from(NOW),
			java.sql.Timestamp.from(NOW));
		ShareImportService service = service(new SigningMedia(RESOLVED_KEY));

		assertThatThrownBy(() -> service.preview(OWNER, importId.value().toString()))
			.isInstanceOf(ShareImportProblem.class)
			.satisfies(ex -> assertThat(((ShareImportProblem) ex).code())
				.isEqualTo(ShareImportProblemCode.IMPORT_STATE_CONFLICT));
	}

	@Test
	void aSeamThatCannotSignReportsUnavailableRatherThanFabricatingAccess() {
		ShareImportId importId = readyUrlImport(OWNER);
		// Default seam behaviour: no capability available.
		ShareImportService service = service(new ShareImportMediaAccess() {
			@Override
			public Optional<ImportMediaAccess.VerifiedImage> findVerifiedUpload(UserId ownerId, com.aifashionvault.shared.UploadId uploadId) {
				return Optional.empty();
			}

			@Override
			public ImportMediaAccess.ClaimOutcome claimVerifiedUpload(UserId ownerId,
					ImportMediaAccess.VerifiedImage upload, StorageKey destination) {
				throw new AssertionError("preview must not claim an upload");
			}

			@Override
			public boolean publishFetchedImage(UserId ownerId, java.io.InputStream content, long contentLength,
					StorageKey destination) {
				throw new AssertionError("preview must not publish an image");
			}

			@Override
			public void deleteObject(StorageKey key) {
				throw new AssertionError("preview must not delete objects");
			}
		});

		assertThatThrownBy(() -> service.preview(OWNER, importId.value().toString()))
			.isInstanceOf(ShareImportProblem.class)
			.satisfies(ex -> assertThat(((ShareImportProblem) ex).code())
				.isEqualTo(ShareImportProblemCode.DEPENDENCY_UNAVAILABLE));
	}

	private static ShareImportService service(ShareImportMediaAccess media) {
		return new ShareImportService(store, media, (ownerId, importId, attemptToken) -> {
			throw new AssertionError("preview must not re-resolve a source");
		});
	}

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

	/** Mints a deterministic signed capability for the expected key only. */
	private static final class SigningMedia implements ShareImportMediaAccess {

		private final StorageKey expected;

		private SigningMedia(StorageKey expected) {
			this.expected = expected;
		}

		@Override
		public Optional<PresignedAccess> presignPreview(UserId ownerId, StorageKey key) {
			if (!expected.equals(key)) {
				return Optional.empty();
			}
			return Optional.of(new PresignedAccess(
				new ObjectRef(StorageDomain.VAULT, key.value()), "GET",
				URI.create("https://objects.example.test/signed/resolved.png?sig=abc"),
				Map.of(), EXPIRES_AT));
		}

		@Override
		public Optional<ImportMediaAccess.VerifiedImage> findVerifiedUpload(UserId ownerId,
				com.aifashionvault.shared.UploadId uploadId) {
			return Optional.empty();
		}

		@Override
		public ImportMediaAccess.ClaimOutcome claimVerifiedUpload(UserId ownerId,
				ImportMediaAccess.VerifiedImage upload, StorageKey destination) {
			throw new AssertionError("preview must not claim an upload");
		}

		@Override
		public boolean publishFetchedImage(UserId ownerId, java.io.InputStream content, long contentLength,
				StorageKey destination) {
			throw new AssertionError("preview must not publish an image");
		}

		@Override
		public void deleteObject(StorageKey key) {
			throw new AssertionError("preview must not delete objects");
		}
	}

	private static final class FixedClock extends Clock {

		private final Instant instant;

		private FixedClock(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return instant;
		}
	}
}
