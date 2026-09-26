package com.aifashionvault.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
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
import com.aifashionvault.platform.web.ShareImportProblem;
import com.aifashionvault.platform.web.ShareImportRequest;
import com.aifashionvault.platform.web.ShareImportService;
import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

/**
 * A replayed create must revalidate staging before it resolves (F05, PR-07).
 *
 * <p>Deliberately NOT a {@code @SpringBootTest}: at the pinned base no
 * {@code @SpringBootTest} context can load (GenerationMediaStore
 * {@code NoUniqueBeanDefinitionException}, plus test-side
 * {@code BeanDefinitionOverrideException}). The production
 * {@link JdbcShareImportStore} and {@link ShareImportService} run over real
 * migrated PostgreSQL; only the outbound media seam is faked, with a fake that
 * models the real consumption rule — a claimed upload is gone afterwards.
 */
class ShareImportReplayStagingIntegrationTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
	private static final String KEY = "key-0123456789abcd";
	private static final UserId OWNER = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final UserId STRANGER = new UserId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final UploadId UPLOAD = new UploadId(UUID.fromString("33333333-3333-3333-3333-333333333333"));

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	private static JdbcTemplate jdbc;
	private static JdbcShareImportStore store;
	private static StagingMedia media;

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
		media = new StagingMedia();
	}

	@BeforeEach
	void clearTables() {
		jdbc.update("TRUNCATE TABLE share_import, source_reference, vault_item, users CASCADE");
		insertUser(OWNER);
		insertUser(STRANGER);
		media.reset();
	}

	/**
	 * The defect: a first IMAGE create claims and consumes its staging upload;
	 * replaying the identical request must then be refused, not answered from the
	 * stored row. Before the fix, arbitration returned the replayed row and the
	 * claimability check never ran, so this replay resolved normally.
	 */
	@Test
	void replayOfImageCreateIsRefusedOnceStagingIsConsumed() {
		ShareImportService service = new ShareImportService(store, media, new PassthroughChains());
		ShareImportRequest request = new ShareImportRequest("IMAGE", null, UPLOAD.value().toString());

		// First create: staging is claimable, the import resolves, staging is consumed.
		var first = service.create(OWNER, KEY, request);
		assertThat(first.status()).isEqualTo(201);
		assertThat(media.isConsumed(UPLOAD)).isTrue();
		ShareImportId importId = shareImportId();

		// Replay: the staging upload is gone, so the replay must be refused.
		assertThatThrownBy(() -> service.create(OWNER, KEY, request))
			.isInstanceOf(ShareImportProblem.class)
			.satisfies(thrown -> assertThat(((ShareImportProblem) thrown).code())
				.isEqualTo(com.aifashionvault.platform.web.ShareImportProblemCode.UPLOAD_NOT_CLAIMABLE));
		// The stored import is untouched by the refused replay: still one row,
		// still READY, and no second attempt was spent.
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM share_import", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT resolver_state FROM share_import WHERE id = ?",
			String.class, importId.value())).isEqualTo("READY");
		assertThat(jdbc.queryForObject("SELECT attempt_count FROM share_import WHERE id = ?",
			Integer.class, importId.value())).isEqualTo(1);
	}

	/**
	 * A replay whose staging is still claimable is a legitimate retry and must
	 * keep working: the fix revalidates, it does not forbid replay outright.
	 */
	@Test
	void replayWithStillClaimableStagingIsServed() {
		ShareImportService service = new ShareImportService(store, media, new PassthroughChains());
		ShareImportRequest request = new ShareImportRequest("IMAGE", null, UPLOAD.value().toString());
		insertResolvingImport();

		var replay = service.create(OWNER, KEY, request);

		assertThat(replay.status()).isIn(200, 202);
		assertThat(replay.body().status()).isEqualTo("READY");
	}

	/**
	 * The fingerprint conflict verdict is unchanged: it is decided from the
	 * stored request alone and is never re-labelled by this request's media.
	 * Here the key is reused for a DIFFERENT request whose upload is also
	 * unclaimable — the answer stays IDEMPOTENCY_KEY_REUSED.
	 */
	@Test
	void fingerprintConflictStaysKeyReusedEvenWithUnclaimableStaging() {
		ShareImportService service = new ShareImportService(store, media, new PassthroughChains());
		insertResolvingImport();
		media.consume(UPLOAD);
		ShareImportRequest differentUpload = new ShareImportRequest("IMAGE", null,
			new UploadId(UUID.fromString("44444444-4444-4444-4444-444444444444")).value().toString());

		assertThatThrownBy(() -> service.create(OWNER, KEY, differentUpload))
			.isInstanceOf(ShareImportProblem.class)
			.satisfies(thrown -> assertThat(((ShareImportProblem) thrown).code())
				.isEqualTo(com.aifashionvault.platform.web.ShareImportProblemCode.IDEMPOTENCY_KEY_REUSED));
	}

	private static ShareImportId shareImportId() {
		return new ShareImportId((UUID) jdbc.queryForObject("SELECT id FROM share_import", UUID.class));
	}

	/**
	 * A RESOLVING IMAGE import under {@link #KEY} whose fingerprint matches an
	 * IMAGE create carrying {@link #UPLOAD}, so arbitration replays it.
	 */
	private static void insertResolvingImport() {
		jdbc.update("INSERT INTO share_import (id, user_id, source_kind, storage_key, resolver_state, "
				+ "idempotency_key, request_fingerprint, attempt_count, created_at, updated_at) "
				+ "VALUES (?, ?, 'IMAGE', NULL, 'RESOLVING', ?, ?, 1, ?, ?)",
			UUID.randomUUID(), OWNER.value(), KEY, "IMAGE|null|" + UPLOAD.value(),
			Timestamp.from(NOW), Timestamp.from(NOW));
	}

	private static void insertUser(UserId user) {
		jdbc.update("INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES (?, ?, ?, NULL)",
			user.value(), Timestamp.from(NOW), Timestamp.from(NOW));
	}

	/**
	 * Staging that is really consumable: the first claim removes the upload, so
	 * a later {@code findVerifiedUpload} is empty exactly as production is.
	 */
	private static final class StagingMedia implements ShareImportMediaAccess {

		private final java.util.Set<UploadId> live = new java.util.HashSet<>();
		private final java.util.Set<UploadId> consumed = new java.util.HashSet<>();

		void reset() {
			live.clear();
			consumed.clear();
			// A fresh staging upload exists before the first request, exactly as
			// the upload-completion path leaves one behind.
			live.add(UPLOAD);
		}

		boolean isConsumed(UploadId uploadId) {
			return consumed.contains(uploadId);
		}

		void consume(UploadId uploadId) {
			live.remove(uploadId);
			consumed.add(uploadId);
		}

		@Override
		public Optional<ImportMediaAccess.VerifiedImage> findVerifiedUpload(UserId ownerId, UploadId uploadId) {
			return live.contains(uploadId)
				? Optional.of(new ImportMediaAccess.VerifiedImage(uploadId, "image/png", 32L, "v1"))
				: Optional.empty();
		}

		@Override
		public ImportMediaAccess.ClaimOutcome claimVerifiedUpload(UserId ownerId,
				ImportMediaAccess.VerifiedImage upload, StorageKey destination) {
			consume(upload.id());
			return ImportMediaAccess.ClaimOutcome.CLAIMED;
		}

		@Override
		public boolean publishFetchedImage(UserId ownerId, InputStream content, long contentLength,
				StorageKey destination) {
			return true;
		}

		@Override
		public void deleteObject(StorageKey key) {
		}
	}

	/**
	 * Resolves an IMAGE import by consuming its staging upload, as production
	 * does, so a later claimability check sees the media as gone.
	 */
	private static final class PassthroughChains implements com.aifashionvault.platform.web.ShareImportResolverChain {

		@Override
		public ImportResolver chain(UserId ownerId, ShareImportId importId, String attemptToken) {
			return new ImportResolver(List.of(new SourceResolver() {

				@Override
				public boolean supports(SourceReference source) {
					return source.verifiedUploadId() != null;
				}

				@Override
				public ResolutionResult resolve(SourceReference source) {
					media.consume(source.verifiedUploadId());
					return new ResolutionResult(ResolutionStatus.RESOLVED,
						new StorageKey("users/11111111-1111-1111-1111-111111111111/vault/import.png"),
						null, "Title", "Site", null);
				}
			}));
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
