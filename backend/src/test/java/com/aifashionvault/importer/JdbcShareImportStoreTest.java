package com.aifashionvault.importer;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcShareImportStoreTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");
	private static final Timestamp NOW_TS = Timestamp.from(NOW);
	private static final UUID USER_UUID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID UPLOAD_UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
	private static final UserId USER = new UserId(USER_UUID);
	private static final UploadId UPLOAD = new UploadId(UPLOAD_UUID);
	private static final URI SOURCE_URL = URI.create("https://example.test/item");
	private static final StorageKey RESOLVED_KEY = new StorageKey("imports/private/resolved-object");

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	private static JdbcTemplate jdbc;
	private static JdbcShareImportStore store;
	private static MutableClock clock;

	@BeforeAll
	static void startDatabaseAndMigrate() {
		POSTGRES.start();
		Flyway.configure()
			.dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
			.locations("classpath:db/migration")
			.load()
			.migrate();
		jdbc = new JdbcTemplate(new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword()));
		clock = new MutableClock(NOW);
		store = new JdbcShareImportStore(new DriverManagerDataSource(
			POSTGRES.getJdbcUrl(),
			POSTGRES.getUsername(),
			POSTGRES.getPassword()), clock);
	}

	@BeforeEach
	void clearTables() {
		jdbc.update("TRUNCATE TABLE share_import, users CASCADE");
		jdbc.update("INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES (?, ?, ?, NULL)",
			USER.value(), NOW_TS, NOW_TS);
		clock.set(NOW);
	}

	@Test
	void createInsertsResolvingImportWithAttemptOne() {
		var result = store.create(command());

		assertThat(result).isInstanceOf(JdbcShareImportStore.Created.class);
		var created = ((JdbcShareImportStore.Created) result).shareImport();
		assertThat(created.state()).isEqualTo(ShareImportState.RESOLVING);
		assertThat(created.attemptCount()).isEqualTo(1);
		assertThat(created.attemptToken()).isNotNull();
		assertThat(created.attemptLeasedAt()).isEqualTo(NOW);
		assertThat(created.sourceUrl()).isEqualTo(SOURCE_URL);
	}

	@Test
	void replaySameCommandReturnsOriginalImport() {
		store.create(command());

		var result = store.create(command());

		assertThat(result).isInstanceOf(JdbcShareImportStore.Replayed.class);
		var replayed = ((JdbcShareImportStore.Replayed) result).shareImport();
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM share_import", Integer.class)).isEqualTo(1);
		assertThat(replayed.state()).isEqualTo(ShareImportState.RESOLVING);
	}

	@Test
	void sameKeyDifferentFingerprintConflicts() {
		store.create(command());

		ImportCommand different = new ImportCommand(USER, fixedKey(), ShareImportKind.IMAGE_URL,
			SOURCE_URL, UPLOAD);
		var result = store.create(different);

		assertThat(result).isInstanceOf(JdbcShareImportStore.FingerprintConflict.class);
	}

	@Test
	void concurrentSameKeyCreatesYieldOneImport() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		java.util.Queue<JdbcShareImportStore.CreateResult> results =
			new java.util.concurrent.ConcurrentLinkedQueue<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Runnable worker = () -> {
			ready.countDown();
			try {
				start.await(10, TimeUnit.SECONDS);
			results.add(store.create(command()));
			} catch (Exception ex) {
				failure.set(ex);
			}
		};
		pool.submit(worker);
		pool.submit(worker);
		assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
		start.countDown();
		pool.shutdown();
		assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		pool.shutdownNow();

		assertThat(failure.get()).isNull();
		assertThat(results).hasSize(2);
		assertThat(results.stream().filter(result -> result instanceof JdbcShareImportStore.Created)).hasSize(1);
		assertThat(results.stream().filter(result -> result instanceof JdbcShareImportStore.Replayed)).hasSize(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM share_import", Integer.class)).isEqualTo(1);
	}

	@Test
	void attemptHeldWhileLeaseIsFresh() {
		var created = createdImport();

		var result = store.beginAttempt(USER, created.id());

		assertThat(result).isInstanceOf(JdbcShareImportStore.AttemptHeld.class);
		assertThat(((JdbcShareImportStore.AttemptHeld) result).shareImport().attemptCount()).isEqualTo(1);
	}

	@Test
	void attemptReclaimAfterLeaseExpiry() {
		var created = createdImport();
		clock.advance(ImportResolverLimits.RESOLUTION_LEASE.plusSeconds(1));

		var result = store.beginAttempt(USER, created.id());

		assertThat(result).isInstanceOf(JdbcShareImportStore.AttemptClaimed.class);
		var claimed = ((JdbcShareImportStore.AttemptClaimed) result).shareImport();
		assertThat(claimed.attemptCount()).isEqualTo(2);
		assertThat(claimed.attemptToken()).isNotEqualTo(created.attemptToken());
	}

	@Test
	void publishWithLiveTokenMovesToReady() {
		var created = createdImport();
		UUID liveToken = created.attemptToken();

		boolean published = store.publish(created.id(), liveToken,
			new ResolutionResult(ResolutionStatus.RESOLVED, RESOLVED_KEY, null, null, null, null));

		assertThat(published).isTrue();
		var after = store.find(USER, created.id()).orElseThrow();
		assertThat(after.state()).isEqualTo(ShareImportState.READY);
		assertThat(after.storageKey()).isEqualTo(RESOLVED_KEY);
	}

	@Test
	void publishWithStaleTokenIsIgnored() {
		var created = createdImport();
		UUID staleToken = created.attemptToken();
		clock.advance(ImportResolverLimits.RESOLUTION_LEASE.plusSeconds(1));
		var reclaimed = ((JdbcShareImportStore.AttemptClaimed) store.beginAttempt(USER, created.id())).shareImport();

		boolean stale = store.publish(created.id(), staleToken,
			new ResolutionResult(ResolutionStatus.RESOLVED, RESOLVED_KEY, null, null, null, null));
		boolean fresh = store.publish(created.id(), reclaimed.attemptToken(),
			new ResolutionResult(ResolutionStatus.RESOLVED, RESOLVED_KEY, null, null, null, null));

		assertThat(stale).isFalse();
		assertThat(fresh).isTrue();
	}

	@Test
	void recoveryOnlyFromNeedsImage() {
		var needsImage = importInState(ShareImportState.NEEDS_IMAGE, ResolutionReason.URL_RESOLUTION_FAILED);

		var result = store.beginRecovery(USER, needsImage.id());

		assertThat(result).isInstanceOf(JdbcShareImportStore.RecoveryClaimed.class);
		var claimed = ((JdbcShareImportStore.RecoveryClaimed) result).shareImport();
		assertThat(claimed.state()).isEqualTo(ShareImportState.RESOLVING);
		assertThat(claimed.attemptToken()).isNotNull();

		var ready = importInState(ShareImportState.READY, null);
		var readyResult = store.beginRecovery(USER, ready.id());
		assertThat(readyResult).isInstanceOf(JdbcShareImportStore.RecoveryNotRecoverable.class);
		assertThat(((JdbcShareImportStore.RecoveryNotRecoverable) readyResult).shareImport().state())
			.isEqualTo(ShareImportState.READY);
	}

	@Test
	void publishRecoveryBindsKeyAndUploadWhenReady() {
		var needsImage = importInState(ShareImportState.NEEDS_IMAGE, ResolutionReason.URL_RESOLUTION_FAILED);
		var claimed = ((JdbcShareImportStore.RecoveryClaimed) store.beginRecovery(USER, needsImage.id())).shareImport();
		assertThat(store.recoveryBinding(USER, needsImage.id())).isEmpty();

		boolean published = store.publishRecovery(claimed.id(), claimed.attemptToken(),
			new ResolutionResult(ResolutionStatus.RESOLVED, RESOLVED_KEY, null, null, null, null),
			"recovery-key-000001", UPLOAD);

		assertThat(published).isTrue();
		var binding = store.recoveryBinding(USER, needsImage.id()).orElseThrow();
		assertThat(binding.recoveryKey()).isEqualTo("recovery-key-000001");
		assertThat(binding.uploadId()).isEqualTo(UPLOAD);
	}

	@Test
	void retryableRecoveryPublishDoesNotBind() {
		var needsImage = importInState(ShareImportState.NEEDS_IMAGE, ResolutionReason.URL_RESOLUTION_FAILED);
		var claimed = ((JdbcShareImportStore.RecoveryClaimed) store.beginRecovery(USER, needsImage.id())).shareImport();

		boolean published = store.publishRecovery(claimed.id(), claimed.attemptToken(),
			new ResolutionResult(ResolutionStatus.RETRYABLE_FAILURE, null, null, null, null,
				ResolutionReason.DEPENDENCY_UNAVAILABLE),
			"recovery-key-000001", UPLOAD);

		assertThat(published).isTrue();
		assertThat(store.recoveryBinding(USER, needsImage.id())).isEmpty();
	}

	@Test
	void deleteIsOwnerScopedAndIdempotent() {
		var created = createdImport();
		var otherOwner = new UserId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
		jdbc.update("INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES (?, ?, ?, NULL)",
			otherOwner.value(), NOW_TS, NOW_TS);

		var wrongOwner = store.delete(otherOwner, created.id());
		assertThat(wrongOwner).isInstanceOf(JdbcShareImportStore.Missing.class);

		var first = store.delete(USER, created.id());
		assertThat(first).isInstanceOf(JdbcShareImportStore.Deleted.class);
		assertThat(((JdbcShareImportStore.Deleted) first).storageKey()).isNull();

		var second = store.delete(USER, created.id());
		assertThat(second).isInstanceOf(JdbcShareImportStore.AlreadyDeleted.class);
		assertThat(store.find(USER, created.id())).isEmpty();
	}
	@Test
	void imageImportRoundTripsWithoutSourceUrl() {
		var created = ((JdbcShareImportStore.Created) store.create(
			new ImportCommand(USER, fixedKey(), ShareImportKind.IMAGE, null, UPLOAD))).shareImport();

		var reloaded = store.find(USER, created.id()).orElseThrow();

		assertThat(reloaded.sourceUrl()).isNull();
		assertThat(reloaded.kind()).isEqualTo(ShareImportKind.IMAGE);
	}

	@Test
	void secondDeleteSucceedsAfterTerminalReasonWasSet() {
		var failed = importInState(ShareImportState.FAILED_TERMINAL, ResolutionReason.URL_RESOLUTION_FAILED);
		assertThat(store.delete(USER, failed.id())).isInstanceOf(JdbcShareImportStore.Deleted.class);

		var second = store.delete(USER, failed.id());

		assertThat(second).isInstanceOf(JdbcShareImportStore.AlreadyDeleted.class);
	}
	@Test
	void deleteCarriesStorageKeyForCleanup() {
		var ready = importInState(ShareImportState.READY, null);

		var result = store.delete(USER, ready.id());

		assertThat(result).isInstanceOf(JdbcShareImportStore.Deleted.class);
		assertThat(((JdbcShareImportStore.Deleted) result).storageKey()).isEqualTo(RESOLVED_KEY);
	}

	@Test
	void storageKeysIncludeDeletedRowsForRetrySafeCleanup() {
		var ready = importInState(ShareImportState.READY, null);
		store.delete(USER, ready.id());

		List<StorageKey> keys = store.storageKeysFor(USER);

		assertThat(keys).containsExactly(RESOLVED_KEY);
	}

	@Test
	void purgeHidesEveryOwnedImport() {
		createdImport();
		importInState(ShareImportState.READY, null);

		store.purgeForUser(USER);

		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM share_import WHERE user_id = ? AND resolver_state = 'DELETED'",
			Integer.class, USER.value())).isEqualTo(2);
		assertThat(store.storageKeysFor(USER)).hasSize(1);
	}

	@Test
	void findExcludesDeletedImports() {
		var created = createdImport();
		store.delete(USER, created.id());

		assertThat(store.find(USER, created.id())).isEmpty();
	}

	@Test
	void publishRejectsUnsupportedResult() {
		var created = createdImport();

		org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.publish(created.id(), UUID.randomUUID(),
			new ResolutionResult(ResolutionStatus.UNSUPPORTED, null, null, null, null, null)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private ImportCommand command() {
		return new ImportCommand(USER, fixedKey(), ShareImportKind.URL, SOURCE_URL, null);
	}

	private static String fixedKey() {
		return "import-key-0123456789abc";
	}

	private final java.util.concurrent.atomic.AtomicInteger keySequence =
		new java.util.concurrent.atomic.AtomicInteger();

	private ShareImport createdImport() {
		String key = "import-key-" + String.format("%013d", keySequence.incrementAndGet());
		return ((JdbcShareImportStore.Created) store.create(
			new ImportCommand(USER, key, ShareImportKind.URL, SOURCE_URL, null))).shareImport();
	}

	private ShareImport importInState(ShareImportState state, ResolutionReason reason) {
		var created = createdImport();
		jdbc.update(
			"UPDATE share_import SET resolver_state = ?, terminal_reason_code = ?, storage_key = ?, "
				+ "attempt_token = NULL, attempt_leased_at = NULL, updated_at = ? WHERE id = ?",
			state.name(), reason == null ? null : reason.name(),
			state == ShareImportState.READY ? RESOLVED_KEY.value() : null, NOW_TS, created.id().value());
		return store.find(USER, created.id()).orElseThrow();
	}

	private static final class MutableClock extends Clock {

		private Instant instant;

		MutableClock(Instant instant) {
			this.instant = instant;
		}

		void set(Instant instant) {
			this.instant = instant;
		}

		void advance(Duration duration) {
			this.instant = this.instant.plus(duration);
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
