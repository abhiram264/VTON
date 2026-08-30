package com.aifashionvault.avatar;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
class JdbcAvatarStoreTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-04-02T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final String PREFLIGHT_POLICY_VERSION = "policy-model-bundle-v7";
	private static final String REPLACE_LOCK_SQL =
		"SELECT id, user_id, primary_image_id, status, revision FROM avatar_profile WHERE id = ? AND user_id = ? FOR UPDATE";

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbc;

	private JdbcAvatarStore store;

	@DynamicPropertySource
	static void registerDatasource(DynamicPropertyRegistry registry) {
		POSTGRES.start();
		registry.add("vault.db.url", POSTGRES::getJdbcUrl);
		registry.add("vault.db.username", POSTGRES::getUsername);
		registry.add("vault.db.password", POSTGRES::getPassword);
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@BeforeEach
	void setUp() {
		jdbc.update("TRUNCATE TABLE avatar_image, avatar_profile, users CASCADE");
		store = new JdbcAvatarStore(dataSource, CLOCK);
	}

	@Test
	void insertFirstPersistsActiveProfileAndPrimaryImage() {
		UserId userId = insertUser();
		AvatarProfileId profileId = new AvatarProfileId(UUID.randomUUID());
		AvatarImageId imageId = new AvatarImageId(UUID.randomUUID());
		UploadId uploadId = new UploadId(UUID.randomUUID());
		AvatarImage created = image(imageId, profileId, userId, uploadId, List.of("borderline-blur"));

		AvatarProfile stored = store.insertFirst(
			profile(profileId, userId, imageId, "ACTIVE", 1),
			created);

		assertThat(stored.id()).isEqualTo(profileId);
		assertThat(stored.userId()).isEqualTo(userId);
		assertThat(stored.primaryImageId()).isEqualTo(imageId);
		assertThat(stored.status()).isEqualTo("ACTIVE");
		assertThat(stored.revision()).isEqualTo(1);
		assertThat(store.findByUser(userId)).contains(stored);
		assertThat(store.findByIdAndUser(profileId, userId)).contains(stored);
		assertThat(jdbc.queryForObject(
			"SELECT primary_image_id FROM avatar_profile WHERE id = ?",
			UUID.class,
			profileId.value())).isEqualTo(imageId.value());

		AvatarImage loaded = store.findImage(userId, imageId).orElseThrow();
		assertThat(loaded.profileId()).isEqualTo(profileId);
		assertThat(loaded.sourceUploadId()).isEqualTo(uploadId);
		assertThat(loaded.lifecycleState()).isEqualTo(AvatarImageLifecycle.ACTIVE);
		assertThat(loaded.qualityStatus()).isEqualTo("ACCEPTED");
		assertThat(loaded.pose()).isNull();
		assertThat(loaded.warnings()).containsExactly("borderline-blur");
		assertThat(loaded.storageKey()).isEqualTo(created.storageKey());
		assertThat(loaded.preflightPolicyVersion()).isEqualTo(PREFLIGHT_POLICY_VERSION);
		assertThat(store.findBySourceUpload(userId, uploadId)).contains(loaded);
		assertThat(store.findByStorageKey(userId, created.storageKey())).contains(loaded);
		assertThat(store.listImagesForUser(userId)).containsExactly(loaded);
		assertThat(store.isUploadConsumed(userId, uploadId)).isTrue();
		assertThat(jdbc.queryForObject(
			"SELECT consumed_at FROM avatar_consumed_upload WHERE user_id = ? AND upload_id = ?",
			Instant.class,
			userId.value(),
			uploadId.value())).isEqualTo(created.createdAt());
	}

	@Test
	void secondInsertFirstForSameUserThrowsConflictAndLeavesOneProfile() {
		UserId userId = insertUser();
		AvatarProfile first = insertActive(userId);

		AvatarProfileId otherProfileId = new AvatarProfileId(UUID.randomUUID());
		AvatarImageId otherImageId = new AvatarImageId(UUID.randomUUID());
		assertAvatarException(
			() -> store.insertFirst(
				profile(otherProfileId, userId, otherImageId, "ACTIVE", 1),
				image(otherImageId, otherProfileId, userId, new UploadId(UUID.randomUUID()), List.of())),
			AvatarProblemCode.AVATAR_CONFLICT);

		assertThat(count("avatar_profile")).isEqualTo(1);
		assertThat(store.findByUser(userId).orElseThrow().id()).isEqualTo(first.id());
		assertThat(store.findByUser(userId).orElseThrow().primaryImageId()).isEqualTo(first.primaryImageId());
	}

	@Test
	void consumedUploadInsertRollsBackProfileAndImageWhenTombstoneAlreadyExists() {
		UserId userId = insertUser();
		UploadId uploadId = new UploadId(UUID.randomUUID());
		jdbc.update(
			"INSERT INTO avatar_consumed_upload (user_id, upload_id, consumed_at) VALUES (?, ?, ?)",
			userId.value(),
			uploadId.value(),
			Timestamp.from(NOW));
		AvatarProfileId profileId = new AvatarProfileId(UUID.randomUUID());
		AvatarImageId imageId = new AvatarImageId(UUID.randomUUID());

		assertAvatarException(
			() -> store.insertFirst(
				profile(profileId, userId, imageId, "ACTIVE", 1),
				image(imageId, profileId, userId, uploadId, List.of())),
			AvatarProblemCode.AVATAR_CONFLICT);

		assertThat(store.findByUser(userId)).isEmpty();
		assertThat(store.findImage(userId, imageId)).isEmpty();
		assertThat(store.isUploadConsumed(userId, uploadId)).isTrue();
		assertThat(count("avatar_profile")).isZero();
		assertThat(count("avatar_image")).isZero();
		assertThat(count("avatar_consumed_upload")).isEqualTo(1);
	}

	@Test
	void consumedUploadQueryIsScopedToUser() {
		UserId owner = insertUser();
		UserId stranger = insertUser();
		UploadId uploadId = new UploadId(UUID.randomUUID());
		AvatarProfileId profileId = new AvatarProfileId(UUID.randomUUID());
		AvatarImageId imageId = new AvatarImageId(UUID.randomUUID());
		store.insertFirst(
			profile(profileId, owner, imageId, "ACTIVE", 1),
			image(imageId, profileId, owner, uploadId, List.of()));

		assertThat(store.isUploadConsumed(owner, uploadId)).isTrue();
		assertThat(store.isUploadConsumed(stranger, uploadId)).isFalse();
	}

	@Test
	void duplicateSourceUploadIdThrowsConflict() {
		UserId firstUser = insertUser();
		UserId secondUser = insertUser();
		UploadId uploadId = new UploadId(UUID.randomUUID());
		AvatarProfileId firstProfileId = new AvatarProfileId(UUID.randomUUID());
		AvatarImageId firstImageId = new AvatarImageId(UUID.randomUUID());
		store.insertFirst(
			profile(firstProfileId, firstUser, firstImageId, "ACTIVE", 1),
			image(firstImageId, firstProfileId, firstUser, uploadId, List.of()));

		AvatarProfileId secondProfileId = new AvatarProfileId(UUID.randomUUID());
		AvatarImageId secondImageId = new AvatarImageId(UUID.randomUUID());
		assertAvatarException(
			() -> store.insertFirst(
				profile(secondProfileId, secondUser, secondImageId, "ACTIVE", 1),
				image(secondImageId, secondProfileId, secondUser, uploadId, List.of())),
			AvatarProblemCode.AVATAR_CONFLICT);

		assertThat(store.findByUser(secondUser)).isEmpty();
		assertThat(count("avatar_profile")).isEqualTo(1);
		assertThat(store.findBySourceUpload(secondUser, uploadId)).isEmpty();
		assertThat(store.findBySourceUpload(firstUser, uploadId)).isPresent();
	}

	@Test
	void duplicateStorageKeyThrowsConflict() {
		UserId firstUser = insertUser();
		UserId secondUser = insertUser();
		StorageKey sharedKey = new StorageKey("users/shared/avatars/same-object.jpg");
		AvatarProfileId firstProfileId = new AvatarProfileId(UUID.randomUUID());
		AvatarImageId firstImageId = new AvatarImageId(UUID.randomUUID());
		store.insertFirst(
			profile(firstProfileId, firstUser, firstImageId, "ACTIVE", 1),
			image(firstImageId, firstProfileId, sharedKey, new UploadId(UUID.randomUUID()), List.of()));

		AvatarProfileId secondProfileId = new AvatarProfileId(UUID.randomUUID());
		AvatarImageId secondImageId = new AvatarImageId(UUID.randomUUID());
		assertAvatarException(
			() -> store.insertFirst(
				profile(secondProfileId, secondUser, secondImageId, "ACTIVE", 1),
				image(secondImageId, secondProfileId, sharedKey, new UploadId(UUID.randomUUID()), List.of())),
			AvatarProblemCode.AVATAR_CONFLICT);

		assertThat(store.findByUser(secondUser)).isEmpty();
		assertThat(count("avatar_profile")).isEqualTo(1);
		assertThat(store.findByStorageKey(firstUser, sharedKey)).isPresent();
		assertThat(store.findByStorageKey(secondUser, sharedKey)).isEmpty();
	}

	@Test
	void replaceImageWithStaleRevisionThrowsAndDoesNotChangePrimary() {
		UserId userId = insertUser();
		AvatarProfile existing = insertActive(userId);
		AvatarImageId previousPrimary = existing.primaryImageId();
		AvatarImageId replacementId = new AvatarImageId(UUID.randomUUID());

		assertAvatarException(
			() -> store.replaceImage(
				existing.id(),
				userId,
				existing.revision() - 1,
				image(replacementId, existing.id(), userId, new UploadId(UUID.randomUUID()), List.of())),
			AvatarProblemCode.AVATAR_STALE_REVISION);

		AvatarProfile reloaded = store.findByUser(userId).orElseThrow();
		assertThat(reloaded.primaryImageId()).isEqualTo(previousPrimary);
		assertThat(reloaded.revision()).isEqualTo(existing.revision());
		assertThat(store.findImage(userId, replacementId)).isEmpty();
		assertThat(store.listDeletionPending()).isEmpty();
	}

	@Test
	void replaceImageRetiresPreviousToDeletionPendingAndIncrementsRevision() {
		UserId userId = insertUser();
		AvatarProfile existing = insertActive(userId);
		AvatarImageId previousPrimary = existing.primaryImageId();
		AvatarImageId replacementId = new AvatarImageId(UUID.randomUUID());
		UploadId replacementUpload = new UploadId(UUID.randomUUID());

		AvatarProfile replaced = store.replaceImage(
			existing.id(),
			userId,
			existing.revision(),
			image(replacementId, existing.id(), userId, replacementUpload, List.of()));

		assertThat(replaced.id()).isEqualTo(existing.id());
		assertThat(replaced.primaryImageId()).isEqualTo(replacementId);
		assertThat(replaced.revision()).isEqualTo(existing.revision() + 1);
		assertThat(replaced.status()).isEqualTo("ACTIVE");
		assertThat(replaced.updatedAt()).isEqualTo(NOW);
		assertThat(store.findImage(userId, previousPrimary).orElseThrow().lifecycleState())
			.isEqualTo(AvatarImageLifecycle.DELETION_PENDING);
		assertThat(store.findImage(userId, replacementId).orElseThrow().lifecycleState())
			.isEqualTo(AvatarImageLifecycle.ACTIVE);
		assertThat(store.listDeletionPending())
			.extracting(AvatarImage::id)
			.containsExactly(previousPrimary);
		assertThat(store.listImagesForUser(userId)).hasSize(2);
		assertThat(store.isUploadConsumed(userId, replacementUpload)).isTrue();
	}

	@Test
	void replaceRollsBackCandidateAndProfileMutationWhenTombstoneInsertFails() {
		UserId userId = insertUser();
		AvatarProfile existing = insertActive(userId);
		UploadId replacementUpload = new UploadId(UUID.randomUUID());
		AvatarImageId replacementId = new AvatarImageId(UUID.randomUUID());
		jdbc.update(
			"INSERT INTO avatar_consumed_upload (user_id, upload_id, consumed_at) VALUES (?, ?, ?)",
			userId.value(),
			replacementUpload.value(),
			Timestamp.from(NOW));

		assertAvatarException(
			() -> store.replaceImage(
				existing.id(),
				userId,
				existing.revision(),
				image(replacementId, existing.id(), userId, replacementUpload, List.of())),
			AvatarProblemCode.AVATAR_CONFLICT);

		AvatarProfile reloaded = store.findByUser(userId).orElseThrow();
		assertThat(reloaded.primaryImageId()).isEqualTo(existing.primaryImageId());
		assertThat(reloaded.revision()).isEqualTo(existing.revision());
		assertThat(store.findImage(userId, replacementId)).isEmpty();
		assertThat(store.findImage(userId, existing.primaryImageId()).orElseThrow().lifecycleState())
			.isEqualTo(AvatarImageLifecycle.ACTIVE);
		assertThat(store.isUploadConsumed(userId, replacementUpload)).isTrue();
		assertThat(count("avatar_consumed_upload")).isEqualTo(2);
	}

	@Test
	void replaceImageDuplicateSourceUploadIdThrowsConflict() {
		UserId userId = insertUser();
		UploadId uploadId = new UploadId(UUID.randomUUID());
		AvatarProfileId profileId = new AvatarProfileId(UUID.randomUUID());
		AvatarImageId imageId = new AvatarImageId(UUID.randomUUID());
		AvatarProfile existing = store.insertFirst(
			profile(profileId, userId, imageId, "ACTIVE", 1),
			image(imageId, profileId, userId, uploadId, List.of()));

		assertAvatarException(
			() -> store.replaceImage(
				existing.id(),
				userId,
				existing.revision(),
				image(new AvatarImageId(UUID.randomUUID()), existing.id(), userId, uploadId, List.of())),
			AvatarProblemCode.AVATAR_CONFLICT);

		assertThat(store.findByUser(userId).orElseThrow().primaryImageId()).isEqualTo(imageId);
	}

	@Test
	void replaceImageOnDeletingOrForeignProfileThrowsNotFound() {
		UserId owner = insertUser();
		UserId stranger = insertUser();
		AvatarProfile existing = insertActive(owner);
		store.logicalDelete(existing.id(), owner, existing.revision());
		AvatarImage replacement = image(
			new AvatarImageId(UUID.randomUUID()),
			existing.id(),
			owner,
			new UploadId(UUID.randomUUID()),
			List.of());

		assertAvatarException(
			() -> store.replaceImage(existing.id(), owner, existing.revision() + 1, replacement),
			AvatarProblemCode.AVATAR_NOT_FOUND);
		assertAvatarException(
			() -> store.replaceImage(existing.id(), stranger, 1, replacement),
			AvatarProblemCode.AVATAR_NOT_FOUND);
		AvatarProfileId missingId = new AvatarProfileId(UUID.randomUUID());
		assertAvatarException(
			() -> store.replaceImage(
				missingId,
				owner,
				1,
				image(new AvatarImageId(UUID.randomUUID()), missingId, owner, new UploadId(UUID.randomUUID()), List.of())),
			AvatarProblemCode.AVATAR_NOT_FOUND);
		assertThat(store.findByUser(owner)).isEmpty();
		assertThat(store.findByIdAndUser(existing.id(), owner).orElseThrow().status()).isEqualTo("DELETING");
	}

	@Test
	void replaceImageLocksProfileForUpdateByIdAndUser() throws Exception {
		String source = Files.readString(Path.of("src/main/java/com/aifashionvault/avatar/JdbcAvatarStore.java"));
		assertThat(source).contains(REPLACE_LOCK_SQL);
	}

	@Test
	void replaceImageSerializesConcurrentReplacementsOnSameRevision() throws Exception {
		UserId userId = insertUser();
		AvatarProfile existing = insertActive(userId);
		AvatarImage first = image(
			new AvatarImageId(UUID.randomUUID()),
			existing.id(),
			userId,
			new UploadId(UUID.randomUUID()),
			List.of());
		AvatarImage second = image(
			new AvatarImageId(UUID.randomUUID()),
			existing.id(),
			userId,
			new UploadId(UUID.randomUUID()),
			List.of());

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		AtomicReference<Object> firstResult = new AtomicReference<>();
		AtomicReference<Object> secondResult = new AtomicReference<>();
		try {
			pool.submit(() -> runReplace(existing, userId, first, ready, start, firstResult));
			pool.submit(() -> runReplace(existing, userId, second, ready, start, secondResult));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}

		List<Object> results = List.of(firstResult.get(), secondResult.get());
		long successes = results.stream().filter(AvatarProfile.class::isInstance).count();
		long stale = results.stream()
			.filter(AvatarException.class::isInstance)
			.map(AvatarException.class::cast)
			.filter(ex -> ex.code() == AvatarProblemCode.AVATAR_STALE_REVISION)
			.count();
		assertThat(successes).isEqualTo(1);
		assertThat(stale).isEqualTo(1);

		AvatarProfile winner = results.stream()
			.filter(AvatarProfile.class::isInstance)
			.map(AvatarProfile.class::cast)
			.findFirst()
			.orElseThrow();
		AvatarImageId winnerImageId = winner.primaryImageId();
		AvatarImageId loserImageId = winnerImageId.equals(first.id()) ? second.id() : first.id();
		AvatarProfile reloaded = store.findByUser(userId).orElseThrow();
		assertThat(reloaded.revision()).isEqualTo(existing.revision() + 1);
		assertThat(reloaded.primaryImageId()).isEqualTo(winnerImageId);
		assertThat(store.findImage(userId, existing.primaryImageId()).orElseThrow().lifecycleState())
			.isEqualTo(AvatarImageLifecycle.DELETION_PENDING);
		assertThat(store.findImage(userId, winnerImageId).orElseThrow().lifecycleState())
			.isEqualTo(AvatarImageLifecycle.ACTIVE);
		assertThat(store.findImage(userId, loserImageId)).isEmpty();
		assertThat(count("avatar_image")).isEqualTo(2);
	}

	@Test
	void logicalDeleteThenFindByUserIsEmptyAndRepeatDeleteIsIdempotent() {
		UserId userId = insertUser();
		UserId otherUser = insertUser();
		AvatarProfile existing = insertActive(userId);
		AvatarProfile other = insertActive(otherUser);
		AvatarImageId primary = existing.primaryImageId();
		StorageKey storageKey = store.findImage(userId, primary).orElseThrow().storageKey();

		AvatarProfile deleted = store.logicalDelete(existing.id(), userId, existing.revision());

		assertThat(deleted.status()).isEqualTo("DELETING");
		assertThat(deleted.primaryImageId()).isNull();
		assertThat(deleted.revision()).isEqualTo(existing.revision() + 1);
		assertThat(deleted.updatedAt()).isEqualTo(NOW);
		assertThat(store.findByUser(userId)).isEmpty();
		assertThat(store.findByIdAndUser(existing.id(), userId)).contains(deleted);
		AvatarImage pending = store.findImage(userId, primary).orElseThrow();
		assertThat(pending.lifecycleState()).isEqualTo(AvatarImageLifecycle.DELETION_PENDING);
		assertThat(pending.storageKey()).isEqualTo(storageKey);
		assertThat(jdbc.queryForObject(
			"SELECT storage_key FROM avatar_image WHERE id = ?",
			String.class,
			primary.value())).isEqualTo(storageKey.value());
		assertThat(jdbc.queryForObject(
			"SELECT primary_image_id FROM avatar_profile WHERE id = ?",
			UUID.class,
			existing.id().value())).isNull();

		AvatarProfile repeated = store.logicalDelete(existing.id(), userId, 99);

		assertThat(repeated).isEqualTo(deleted);
		assertThat(store.findByIdAndUser(existing.id(), userId)).contains(deleted);
		assertThat(store.findByUser(otherUser)).contains(other);
		assertThat(store.findByIdAndUser(other.id(), otherUser).orElseThrow().status()).isEqualTo("ACTIVE");
		assertThat(count("avatar_profile")).isEqualTo(2);
	}

	@Test
	void logicalDeleteWithoutRevisionUnconditionallyTransitionsActiveProfile() {
		UserId userId = insertUser();
		AvatarProfile existing = insertActive(userId);

		AvatarProfile deleted = store.logicalDelete(existing.id(), userId, null);

		assertThat(deleted.status()).isEqualTo("DELETING");
		assertThat(deleted.primaryImageId()).isNull();
		assertThat(deleted.revision()).isEqualTo(existing.revision() + 1);
		assertThat(store.findImage(userId, existing.primaryImageId()).orElseThrow().lifecycleState())
			.isEqualTo(AvatarImageLifecycle.DELETION_PENDING);
	}

	@Test
	void logicalDeleteStaleRevisionThrowsAndMissingOrForeignThrowsNotFound() {
		UserId owner = insertUser();
		UserId stranger = insertUser();
		AvatarProfile existing = insertActive(owner);

		assertAvatarException(
			() -> store.logicalDelete(existing.id(), owner, existing.revision() - 1),
			AvatarProblemCode.AVATAR_STALE_REVISION);
		assertThat(store.findByUser(owner).orElseThrow().status()).isEqualTo("ACTIVE");
		assertAvatarException(
			() -> store.logicalDelete(existing.id(), stranger, existing.revision()),
			AvatarProblemCode.AVATAR_NOT_FOUND);
		assertAvatarException(
			() -> store.logicalDelete(new AvatarProfileId(UUID.randomUUID()), owner, 1),
			AvatarProblemCode.AVATAR_NOT_FOUND);
	}

	@Test
	void insertFirstWhileActiveOrDeletingThrowsConflictAndDoesNotReactivate() {
		UserId userId = insertUser();
		AvatarProfile original = insertActive(userId);
		AvatarProfileId activeConflictId = new AvatarProfileId(UUID.randomUUID());
		AvatarImageId activeConflictImageId = new AvatarImageId(UUID.randomUUID());
		assertAvatarException(
			() -> store.insertFirst(
				profile(activeConflictId, userId, activeConflictImageId, "ACTIVE", 1),
				image(activeConflictImageId, activeConflictId, userId, new UploadId(UUID.randomUUID()), List.of())),
			AvatarProblemCode.AVATAR_CONFLICT);

		store.logicalDelete(original.id(), userId, original.revision());

		AvatarProfileId requestedId = new AvatarProfileId(UUID.randomUUID());
		AvatarImageId newImageId = new AvatarImageId(UUID.randomUUID());
		assertAvatarException(
			() -> store.insertFirst(
				profile(requestedId, userId, newImageId, "ACTIVE", 1),
				image(newImageId, requestedId, userId, new UploadId(UUID.randomUUID()), List.of())),
			AvatarProblemCode.AVATAR_CONFLICT);

		assertThat(store.findByUser(userId)).isEmpty();
		assertThat(store.findByIdAndUser(original.id(), userId).orElseThrow().status()).isEqualTo("DELETING");
		assertThat(store.findByIdAndUser(original.id(), userId).orElseThrow().id()).isEqualTo(original.id());
		assertThat(count("avatar_profile")).isEqualTo(1);
		assertThat(store.findImage(userId, newImageId)).isEmpty();
	}

	@Test
	void findImageAndFindByStorageKeyRequireMatchingUser() {
		UserId owner = insertUser();
		UserId stranger = insertUser();
		AvatarProfile existing = insertActive(owner);
		AvatarImageId imageId = existing.primaryImageId();
		StorageKey key = store.findImage(owner, imageId).orElseThrow().storageKey();

		assertThat(store.findImage(owner, imageId)).isPresent();
		assertThat(store.findImage(stranger, imageId)).isEmpty();
		assertThat(store.findByStorageKey(owner, key)).isPresent();
		assertThat(store.findByStorageKey(stranger, key)).isEmpty();
	}

	@Test
	void markReconciledDeletesPendingRowAndIsIdempotent() {
		UserId userId = insertUser();
		AvatarProfile existing = insertActive(userId);
		AvatarImageId retired = existing.primaryImageId();
		store.replaceImage(
			existing.id(),
			userId,
			existing.revision(),
			image(new AvatarImageId(UUID.randomUUID()), existing.id(), userId, new UploadId(UUID.randomUUID()), List.of()));

		store.markReconciled(retired);
		store.markReconciled(retired);
		store.markReconciled(new AvatarImageId(UUID.randomUUID()));

		assertThat(store.findImage(userId, retired)).isEmpty();
		assertThat(store.listDeletionPending()).isEmpty();
		assertThat(store.findByUser(userId)).isPresent();
		assertThat(count("avatar_profile")).isEqualTo(1);
	}

	@Test
	void markReconciledDoesNotDeleteActiveImage() {
		UserId userId = insertUser();
		AvatarProfile existing = insertActive(userId);

		store.markReconciled(existing.primaryImageId());

		assertThat(store.findImage(userId, existing.primaryImageId())).isPresent();
		assertThat(store.findByUser(userId).orElseThrow().primaryImageId()).isEqualTo(existing.primaryImageId());
	}

	@Test
	void markReconciledRemovesLastPendingImageThenDeletesDeletingProfile() {
		UserId userId = insertUser();
		AvatarProfile existing = insertActive(userId);
		AvatarImageId first = existing.primaryImageId();
		AvatarImageId second = new AvatarImageId(UUID.randomUUID());
		AvatarProfile replaced = store.replaceImage(
			existing.id(),
			userId,
			existing.revision(),
			image(second, existing.id(), userId, new UploadId(UUID.randomUUID()), List.of()));
		store.logicalDelete(replaced.id(), userId, replaced.revision());

		assertThat(store.listDeletionPending()).hasSize(2);
		assertThat(store.findByIdAndUser(existing.id(), userId).orElseThrow().status()).isEqualTo("DELETING");

		store.markReconciled(first);

		assertThat(store.findImage(userId, first)).isEmpty();
		assertThat(store.findByIdAndUser(existing.id(), userId)).isPresent();
		assertThat(count("avatar_image")).isEqualTo(1);
		assertThat(count("avatar_profile")).isEqualTo(1);

		store.markReconciled(second);

		assertThat(store.findImage(userId, second)).isEmpty();
		assertThat(store.findByIdAndUser(existing.id(), userId)).isEmpty();
		assertThat(store.findByUser(userId)).isEmpty();
		assertThat(count("avatar_image")).isEqualTo(0);
		assertThat(count("avatar_profile")).isEqualTo(0);

		AvatarProfile created = insertActive(userId);
		assertThat(created.id()).isNotEqualTo(existing.id());
		assertThat(created.status()).isEqualTo("ACTIVE");
	}

	@Test
	void consumedUploadSurvivesReconciliationAndCascadesOnlyWithUserDeletion() {
		UserId userId = insertUser();
		UploadId uploadId = new UploadId(UUID.randomUUID());
		AvatarProfileId profileId = new AvatarProfileId(UUID.randomUUID());
		AvatarImageId imageId = new AvatarImageId(UUID.randomUUID());
		AvatarProfile created = store.insertFirst(
			profile(profileId, userId, imageId, "ACTIVE", 1),
			image(imageId, profileId, userId, uploadId, List.of()));
		store.logicalDelete(created.id(), userId, created.revision());

		store.markReconciled(imageId);

		assertThat(store.findByIdAndUser(profileId, userId)).isEmpty();
		assertThat(store.isUploadConsumed(userId, uploadId)).isTrue();
		assertThat(count("avatar_consumed_upload")).isEqualTo(1);

		jdbc.update("DELETE FROM users WHERE id = ?", userId.value());

		assertThat(count("avatar_consumed_upload")).isZero();
	}

	@Test
	void findBySourceUploadIsScopedToOwningUserAndIncludesPendingLifecycle() {
		UserId owner = insertUser();
		UserId stranger = insertUser();
		UploadId uploadId = new UploadId(UUID.randomUUID());
		AvatarProfileId profileId = new AvatarProfileId(UUID.randomUUID());
		AvatarImageId imageId = new AvatarImageId(UUID.randomUUID());
		store.insertFirst(
			profile(profileId, owner, imageId, "ACTIVE", 1),
			image(imageId, profileId, owner, uploadId, List.of()));
		store.logicalDelete(profileId, owner, 1);

		assertThat(store.findBySourceUpload(owner, uploadId).orElseThrow().id()).isEqualTo(imageId);
		assertThat(store.findBySourceUpload(stranger, uploadId)).isEmpty();
	}

	@Test
	void avatarExceptionMessageIsOnlyTheCodeName() {
		UserId userId = insertUser();
		insertActive(userId);
		AvatarProfileId profileId = new AvatarProfileId(UUID.randomUUID());
		AvatarImageId imageId = new AvatarImageId(UUID.randomUUID());

		assertThatThrownBy(() -> store.insertFirst(
			profile(profileId, userId, imageId, "ACTIVE", 1),
			image(imageId, profileId, userId, new UploadId(UUID.randomUUID()), List.of())))
			.isInstanceOf(AvatarException.class)
			.hasMessage("AVATAR_CONFLICT")
			.satisfies(thrown -> assertThat(thrown.getMessage())
				.doesNotContain("storage")
				.doesNotContain("users/")
				.doesNotContain("duplicate")
				.doesNotContain("23505"));
	}

	private void runReplace(
		AvatarProfile existing,
		UserId userId,
		AvatarImage replacement,
		CountDownLatch ready,
		CountDownLatch start,
		AtomicReference<Object> result) {
		ready.countDown();
		try {
			if (!start.await(10, TimeUnit.SECONDS)) {
				result.set(new IllegalStateException("replace start latch timed out"));
				return;
			}
			result.set(store.replaceImage(existing.id(), userId, existing.revision(), replacement));
		}
		catch (Throwable thrown) {
			result.set(thrown);
		}
	}

	private AvatarProfile insertActive(UserId userId) {
		AvatarProfileId profileId = new AvatarProfileId(UUID.randomUUID());
		AvatarImageId imageId = new AvatarImageId(UUID.randomUUID());
		return store.insertFirst(
			profile(profileId, userId, imageId, "ACTIVE", 1),
			image(imageId, profileId, userId, new UploadId(UUID.randomUUID()), List.of()));
	}

	private UserId insertUser() {
		UserId userId = new UserId(UUID.randomUUID());
		jdbc.update(
			"INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES (?, ?, ?, NULL)",
			userId.value(),
			Timestamp.from(NOW),
			Timestamp.from(NOW));
		return userId;
	}

	private int count(String table) {
		Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
		assertThat(count).isNotNull();
		return count;
	}

	private static AvatarProfile profile(
		AvatarProfileId profileId,
		UserId userId,
		AvatarImageId primaryImageId,
		String status,
		int revision) {
		return new AvatarProfile(profileId, userId, primaryImageId, status, revision, NOW, NOW);
	}

	private static AvatarImage image(
		AvatarImageId imageId,
		AvatarProfileId profileId,
		UserId userId,
		UploadId uploadId,
		List<String> warnings) {
		return image(
			imageId,
			profileId,
			new StorageKey("users/" + userId.value() + "/avatars/" + imageId.value() + ".jpg"),
			uploadId,
			warnings);
	}

	private static AvatarImage image(
		AvatarImageId imageId,
		AvatarProfileId profileId,
		StorageKey storageKey,
		UploadId uploadId,
		List<String> warnings) {
		return new AvatarImage(
			imageId,
			profileId,
			storageKey,
			768,
			1024,
			null,
			"ACCEPTED",
			NOW,
			uploadId,
			AvatarImageLifecycle.ACTIVE,
			warnings,
			PREFLIGHT_POLICY_VERSION);
	}

	private static void assertAvatarException(Runnable action, AvatarProblemCode code) {
		assertThatThrownBy(action::run)
			.isInstanceOf(AvatarException.class)
			.satisfies(thrown -> {
				AvatarException exception = (AvatarException) thrown;
				assertThat(exception.code()).isEqualTo(code);
				assertThat(exception.getMessage()).isEqualTo(code.name());
			});
	}
}
