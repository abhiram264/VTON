package com.aifashionvault.avatar;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.platform.objectstore.ObjectStoreAvatarMediaAccess;
import com.aifashionvault.platform.objectstore.ClaimCommand;
import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.MediaDeclaration;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.platform.objectstore.StorageBoundary;
import com.aifashionvault.platform.objectstore.UploadCapability;
import com.aifashionvault.platform.objectstore.UploadPurpose;
import com.aifashionvault.shared.AccountMediaPurge;
import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
class AvatarAccountMediaPurgeTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-04-02T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

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
	private FakeObjectStore objectStore;
	private AvatarAccountMediaPurge purge;

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
		objectStore = new FakeObjectStore();
		purge = new AvatarAccountMediaPurge(store, new ObjectStoreAvatarMediaAccess(objectStore));
	}

	@Test
	void implementsAccountMediaPurge() {
		assertThat(purge).isInstanceOf(AccountMediaPurge.class);
	}

	@Test
	void purgeForUserLogicallyDeletesBeforeObjectDeletesThenRemovesRows() {
		UserId userId = insertUser();
		AvatarProfile existing = insertActive(userId);
		AvatarImageId retired = existing.primaryImageId();
		AvatarImageId current = new AvatarImageId(UUID.randomUUID());
		store.replaceImage(
			existing.id(),
			userId,
			existing.revision(),
			image(current, existing.id(), userId, new UploadId(UUID.randomUUID())));
		String retiredKey = store.findImage(userId, retired).orElseThrow().storageKey().value();
		String currentKey = store.findImage(userId, current).orElseThrow().storageKey().value();
		List<String> statusAtObjectDelete = new ArrayList<>();
		objectStore.onDelete(() -> statusAtObjectDelete.add(
			store.findByIdAndUser(existing.id(), userId).map(AvatarProfile::status).orElse("MISSING")));

		purge.purgeForUser(userId);

		assertThat(statusAtObjectDelete).containsOnly("DELETING").hasSize(2);
		assertThat(objectStore.deleted()).containsExactlyInAnyOrder(
			new ObjectRef(StorageBoundary.AVATAR, retiredKey),
			new ObjectRef(StorageBoundary.AVATAR, currentKey));
		assertThat(objectStore.purgeUserObjectsCalls()).isZero();
		assertThat(store.findByUser(userId)).isEmpty();
		assertThat(store.findByIdAndUser(existing.id(), userId)).isEmpty();
		assertThat(store.listImagesForUser(userId)).isEmpty();
		assertThat(store.listDeletionPending()).isEmpty();
		assertThat(store.findImage(userId, retired)).isEmpty();
		assertThat(store.findImage(userId, current)).isEmpty();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM avatar_image WHERE profile_id = ?",
			Integer.class,
			existing.id().value())).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM avatar_profile WHERE id = ?",
			Integer.class,
			existing.id().value())).isZero();

		purge.purgeForUser(userId);

		assertThat(objectStore.deleted()).hasSize(2);
		assertThat(store.findByUser(userId)).isEmpty();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM avatar_profile WHERE user_id = ?",
			Integer.class,
			userId.value())).isZero();
	}

	@Test
	void stagedObjectMissingIsTreatedAsSuccessfulDelete() {
		UserId userId = insertUser();
		AvatarProfile existing = insertActive(userId);
		objectStore.failDelete(new ObjectStoreExceptions.StagedObjectMissingException("already gone"));

		purge.purgeForUser(userId);

		assertThat(store.findByUser(userId)).isEmpty();
		assertThat(store.findByIdAndUser(existing.id(), userId)).isEmpty();
		assertThat(store.listImagesForUser(userId)).isEmpty();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM avatar_profile WHERE user_id = ?",
			Integer.class,
			userId.value())).isZero();
	}

	@Test
	void objectStoreDeleteFailureThrowsAndLeavesDeletingCoordinates() {
		UserId userId = insertUser();
		AvatarProfile existing = insertActive(userId);
		AvatarImageId imageId = existing.primaryImageId();
		StorageKey storageKey = store.findImage(userId, imageId).orElseThrow().storageKey();
		objectStore.failDelete(new ObjectStoreExceptions.ObjectStoreUnavailableException("object store is unavailable"));

		assertThatThrownBy(() -> purge.purgeForUser(userId))
			.isInstanceOf(ObjectStoreExceptions.ObjectStoreUnavailableException.class);

		assertThat(objectStore.purgeUserObjectsCalls()).isZero();
		assertThat(objectStore.deleted()).isEmpty();
		assertThat(store.findByUser(userId)).isEmpty();
		AvatarProfile deleting = store.findByIdAndUser(existing.id(), userId).orElseThrow();
		assertThat(deleting.status()).isEqualTo("DELETING");
		assertThat(deleting.primaryImageId()).isNull();
		AvatarImage pending = store.findImage(userId, imageId).orElseThrow();
		assertThat(pending.lifecycleState()).isEqualTo(AvatarImageLifecycle.DELETION_PENDING);
		assertThat(pending.storageKey()).isEqualTo(storageKey);
		assertThat(store.listImagesForUser(userId)).hasSize(1);
		assertThat(store.listDeletionPending()).extracting(AvatarImage::id).containsExactly(imageId);
		assertThat(jdbc.queryForObject(
			"SELECT storage_key FROM avatar_image WHERE id = ?",
			String.class,
			imageId.value())).isEqualTo(storageKey.value());

		objectStore.failDelete(null);
		purge.purgeForUser(userId);

		assertThat(store.findByUser(userId)).isEmpty();
		assertThat(store.findByIdAndUser(existing.id(), userId)).isEmpty();
		assertThat(store.listImagesForUser(userId)).isEmpty();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM avatar_profile WHERE id = ?",
			Integer.class,
			existing.id().value())).isZero();
	}

	@Test
	void sourceDoesNotImportUserPackageOrCallPurgeUserObjects() throws Exception {
		String source = Files.readString(avatarAccountMediaPurgeSource(), StandardCharsets.UTF_8);

		assertThat(source)
			.doesNotContain("com.aifashionvault.user")
			.doesNotContain("purgeUserObjects")
			.doesNotContain("LocalStack")
			.doesNotContain("org.springframework.web")
			.doesNotContain("org.springframework.http");
	}

	private AvatarProfile insertActive(UserId userId) {
		AvatarProfileId profileId = new AvatarProfileId(UUID.randomUUID());
		AvatarImageId imageId = new AvatarImageId(UUID.randomUUID());
		return store.insertFirst(
			profile(profileId, userId, imageId),
			image(imageId, profileId, userId, new UploadId(UUID.randomUUID())));
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

	private static AvatarProfile profile(AvatarProfileId profileId, UserId userId, AvatarImageId primaryImageId) {
		return new AvatarProfile(profileId, userId, primaryImageId, "ACTIVE", 1, NOW, NOW);
	}

	private static AvatarImage image(
		AvatarImageId imageId,
		AvatarProfileId profileId,
		UserId userId,
		UploadId uploadId) {
		return new AvatarImage(
			imageId,
			profileId,
			new StorageKey("users/" + userId.value() + "/avatars/" + imageId.value() + ".jpg"),
			768,
			1024,
			null,
			"ACCEPTED",
			NOW,
			uploadId,
			AvatarImageLifecycle.ACTIVE,
			List.of(),
			null);
	}

	private static Path avatarAccountMediaPurgeSource() {
		Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
		Path current = cwd;
		while (current != null) {
			Path direct = current.resolve(Path.of(
				"src",
				"main",
				"java",
				"com",
				"aifashionvault",
				"avatar",
				"AvatarAccountMediaPurge.java"));
			if (Files.isRegularFile(direct)) {
				return direct;
			}
			Path underBackend = current.resolve("backend").resolve(Path.of(
				"src",
				"main",
				"java",
				"com",
				"aifashionvault",
				"avatar",
				"AvatarAccountMediaPurge.java"));
			if (Files.isRegularFile(underBackend)) {
				return underBackend;
			}
			current = current.getParent();
		}
		throw new IllegalStateException("Cannot locate AvatarAccountMediaPurge.java from " + cwd);
	}

	private static final class FakeObjectStore implements ObjectStore {

		private final List<ObjectRef> deleted = new ArrayList<>();
		private RuntimeException deleteFailure;
		private int purgeUserObjectsCalls;
		private Runnable beforeDelete = () -> {
		};

		void failDelete(RuntimeException failure) {
			this.deleteFailure = failure;
		}

		void onDelete(Runnable action) {
			this.beforeDelete = action == null ? () -> {
			} : action;
		}

		List<ObjectRef> deleted() {
			return List.copyOf(deleted);
		}

		int purgeUserObjectsCalls() {
			return purgeUserObjectsCalls;
		}

		@Override
		public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration) {
			throw new AssertionError("presignStagingPut must not be called");
		}

		@Override
		public UploadCapability presignStagingPut(
			UserId userId,
			UploadId uploadId,
			MediaDeclaration declaration,
			UploadPurpose purpose) {
			throw new AssertionError("presignStagingPut must not be called");
		}

		@Override
		public ObjectMetadata head(ObjectRef object) {
			throw new AssertionError("head must not be called");
		}

		@Override
		public byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes) {
			throw new AssertionError("readBounded must not be called");
		}

		@Override
		public void delete(ObjectRef object) {
			beforeDelete.run();
			if (deleteFailure != null) {
				throw deleteFailure;
			}
			deleted.add(object);
		}

		@Override
		public ClaimResult claim(ClaimCommand command) {
			throw new AssertionError("claim must not be called");
		}

		@Override
		public void purgeUserObjects(UserId userId) {
			purgeUserObjectsCalls++;
			throw new AssertionError("purgeUserObjects must not be called");
		}

		@Override
		public void cleanupTemp() {
			throw new AssertionError("cleanupTemp must not be called");
		}

		@Override
		public PresignedAccess presignGet(ObjectRef object, Duration ttl) {
			throw new AssertionError("presignGet must not be called");
		}
	}
}
