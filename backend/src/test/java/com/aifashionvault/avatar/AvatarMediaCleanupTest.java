package com.aifashionvault.avatar;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

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
import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AvatarMediaCleanupTest {

	private static final Instant NOW = Instant.parse("2026-04-02T12:00:00Z");

	@Test
	void pendingImageObjectDeletedThenMarkReconciled() {
		JdbcAvatarStore store = mock(JdbcAvatarStore.class);
		FakeObjectStore objectStore = new FakeObjectStore();
		AvatarImage image = pendingImage();
		when(store.listDeletionPending()).thenReturn(List.of(image));

		new AvatarMediaCleanup(store, new ObjectStoreAvatarMediaAccess(objectStore)).reconcile();

		assertThat(objectStore.deleted()).containsExactly(ref(image));
		verify(store).markReconciled(image.id());
	}

	@Test
	void alreadyAbsentObjectStillMarkReconciled() {
		JdbcAvatarStore store = mock(JdbcAvatarStore.class);
		FakeObjectStore objectStore = new FakeObjectStore();
		AvatarImage image = pendingImage();
		when(store.listDeletionPending()).thenReturn(List.of(image));
		objectStore.failDelete(new ObjectStoreExceptions.StagedObjectMissingException("already gone"));

		new AvatarMediaCleanup(store, new ObjectStoreAvatarMediaAccess(objectStore)).reconcile();

		verify(store).markReconciled(image.id());
	}

	@Test
	void deleteFailureLeavesRowPending() {
		JdbcAvatarStore store = mock(JdbcAvatarStore.class);
		FakeObjectStore objectStore = new FakeObjectStore();
		AvatarImage image = pendingImage();
		when(store.listDeletionPending()).thenReturn(List.of(image));
		objectStore.failDelete(new ObjectStoreExceptions.ObjectStoreUnavailableException("object store is unavailable"));

		new AvatarMediaCleanup(store, new ObjectStoreAvatarMediaAccess(objectStore)).reconcile();

		verify(store, never()).markReconciled(image.id());
	}

	@Test
	void deleteFailureSkipsThatRowAndContinuesOthers() {
		JdbcAvatarStore store = mock(JdbcAvatarStore.class);
		FakeObjectStore objectStore = new FakeObjectStore();
		AvatarImage failing = pendingImage();
		AvatarImage succeeding = pendingImage();
		when(store.listDeletionPending()).thenReturn(List.of(failing, succeeding));
		objectStore.failDelete(
			ref(failing),
			new ObjectStoreExceptions.ObjectStoreUnavailableException("object store is unavailable"));

		new AvatarMediaCleanup(store, new ObjectStoreAvatarMediaAccess(objectStore)).reconcile();

		assertThat(objectStore.deleted()).containsExactly(ref(succeeding));
		verify(store, never()).markReconciled(failing.id());
		verify(store).markReconciled(succeeding.id());
	}

	@Test
	void persistentOrphanCleanupHonorsTwentyHourBoundaryAndOwnership() {
		JdbcAvatarStore store = mock(JdbcAvatarStore.class);
		AvatarMediaAccess mediaAccess = mock(AvatarMediaAccess.class);
		Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
		StorageKey exactlyBefore = storageKey("exactly-before");
		StorageKey exactlyAt = storageKey("exactly-at");
		StorageKey exactlyAfter = storageKey("exactly-after");
		StorageKey owned = storageKey("owned");
		StorageKey timestampUnknown = storageKey("timestamp-unknown");
		when(mediaAccess.listPersistentAvatars()).thenReturn(List.of(
			new AvatarMediaAccess.PersistentObject(exactlyBefore, NOW.minus(Duration.ofHours(20)).plusNanos(1)),
			new AvatarMediaAccess.PersistentObject(exactlyAt, NOW.minus(Duration.ofHours(20))),
			new AvatarMediaAccess.PersistentObject(exactlyAfter, NOW.minus(Duration.ofHours(20)).minusNanos(1)),
			new AvatarMediaAccess.PersistentObject(owned, NOW.minus(Duration.ofHours(21))),
			new AvatarMediaAccess.PersistentObject(timestampUnknown, null)));
		when(store.findByStorageKey(exactlyAt)).thenReturn(Optional.empty());
		when(store.findByStorageKey(exactlyAfter)).thenReturn(Optional.empty());
		when(store.findByStorageKey(owned)).thenReturn(Optional.of(image(owned, AvatarImageLifecycle.ACTIVE)));

		new AvatarMediaCleanup(store, mediaAccess, clock).reconcile();

		verify(mediaAccess, never()).deletePersistent(exactlyBefore);
		verify(mediaAccess).deletePersistent(exactlyAt);
		verify(mediaAccess).deletePersistent(exactlyAfter);
		verify(mediaAccess, never()).deletePersistent(owned);
		verify(mediaAccess, never()).deletePersistent(timestampUnknown);
		verify(store, never()).findByStorageKey(exactlyBefore);
		verify(store, never()).findByStorageKey(timestampUnknown);
		verify(store, never()).markReconciled(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void persistentOrphanDeleteFailurePreservesDatabaseAndContinues() {
		JdbcAvatarStore store = mock(JdbcAvatarStore.class);
		AvatarMediaAccess mediaAccess = mock(AvatarMediaAccess.class);
		StorageKey failing = storageKey("delete-fails");
		StorageKey succeeding = storageKey("delete-succeeds");
		when(mediaAccess.listPersistentAvatars()).thenReturn(List.of(
			new AvatarMediaAccess.PersistentObject(failing, NOW.minus(Duration.ofHours(21))),
			new AvatarMediaAccess.PersistentObject(succeeding, NOW.minus(Duration.ofHours(21)))));
		when(store.findByStorageKey(failing)).thenReturn(Optional.empty());
		when(store.findByStorageKey(succeeding)).thenReturn(Optional.empty());
		org.mockito.Mockito.doThrow(new RuntimeException("object store is unavailable"))
			.when(mediaAccess)
			.deletePersistent(failing);

		assertThatCode(() -> new AvatarMediaCleanup(
			store,
			mediaAccess,
			Clock.fixed(NOW, ZoneOffset.UTC)).reconcile()).doesNotThrowAnyException();

		verify(mediaAccess).deletePersistent(failing);
		verify(mediaAccess).deletePersistent(succeeding);
		verify(store, never()).markReconciled(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void persistentListingFailureDoesNotAffectCompletedPendingReconciliation() {
		JdbcAvatarStore store = mock(JdbcAvatarStore.class);
		AvatarMediaAccess mediaAccess = mock(AvatarMediaAccess.class);
		AvatarImage pending = pendingImage();
		when(store.listDeletionPending()).thenReturn(List.of(pending));
		when(mediaAccess.listPersistentAvatars()).thenThrow(new RuntimeException("listing unavailable"));

		assertThatCode(() -> new AvatarMediaCleanup(
			store,
			mediaAccess,
			Clock.fixed(NOW, ZoneOffset.UTC)).reconcile()).doesNotThrowAnyException();

		verify(mediaAccess).deletePersistent(pending.storageKey());
		verify(store).markReconciled(pending.id());
		verify(store, never()).findByStorageKey(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void ownerLookupFailurePreservesObjectAndContinues() {
		JdbcAvatarStore store = mock(JdbcAvatarStore.class);
		AvatarMediaAccess mediaAccess = mock(AvatarMediaAccess.class);
		StorageKey lookupFails = storageKey("lookup-fails");
		StorageKey unowned = storageKey("unowned");
		when(mediaAccess.listPersistentAvatars()).thenReturn(List.of(
			new AvatarMediaAccess.PersistentObject(lookupFails, NOW.minus(Duration.ofHours(21))),
			new AvatarMediaAccess.PersistentObject(unowned, NOW.minus(Duration.ofHours(21)))));
		when(store.findByStorageKey(lookupFails)).thenThrow(new RuntimeException("database unavailable"));
		when(store.findByStorageKey(unowned)).thenReturn(Optional.empty());

		assertThatCode(() -> new AvatarMediaCleanup(
			store,
			mediaAccess,
			Clock.fixed(NOW, ZoneOffset.UTC)).reconcile()).doesNotThrowAnyException();

		verify(mediaAccess, never()).deletePersistent(lookupFails);
		verify(mediaAccess).deletePersistent(unowned);
	}

	@Test
	void configurationWiresCleanupAndHourlyScheduleWithoutEnableScheduling() {
		Configuration configuration = AvatarCleanupConfiguration.class.getAnnotation(Configuration.class);
		assertThat(configuration).isNotNull();
		assertThat(configuration.proxyBeanMethods()).isFalse();
		assertThat(AvatarCleanupConfiguration.class.getAnnotation(EnableScheduling.class)).isNull();

		List<Method> beans = beanMethods(AvatarCleanupConfiguration.class);
		assertThat(beans)
			.extracting(Method::getReturnType)
			.contains(AvatarMediaCleanup.class);

		List<Method> scheduled = scheduledMethods(AvatarCleanupConfiguration.class);
		assertThat(scheduled).isNotEmpty();
		for (Method method : scheduled) {
			Scheduled annotation = method.getAnnotation(Scheduled.class);
			assertThat(delay(annotation.initialDelay(), annotation.initialDelayString(), annotation.timeUnit()))
				.isGreaterThanOrEqualTo(Duration.ofHours(1));
			Duration repeat = delay(annotation.fixedDelay(), annotation.fixedDelayString(), annotation.timeUnit());
			if (repeat.isZero() || repeat.isNegative()) {
				repeat = delay(annotation.fixedRate(), annotation.fixedRateString(), annotation.timeUnit());
			}
			assertThat(repeat).isGreaterThanOrEqualTo(Duration.ofHours(1));
		}
	}

	@Test
	void scheduledReconcileIsolatesCleanupExceptions() {
		AvatarMediaCleanup cleanup = mock(AvatarMediaCleanup.class);
		org.mockito.Mockito.doThrow(new RuntimeException("connection refused")).when(cleanup).reconcile();
		AvatarCleanupConfiguration.ScheduledCleanup scheduled = new AvatarCleanupConfiguration.ScheduledCleanup(cleanup);

		assertThatCode(scheduled::reconcile).doesNotThrowAnyException();
		org.mockito.Mockito.verify(cleanup).reconcile();
	}

	@Test
	void productionSourcesDoNotReferenceGenerationQueueSqsOrRedis() throws Exception {
		for (String fileName : List.of("AvatarMediaCleanup.java", "AvatarCleanupConfiguration.java")) {
			String source = Files.readString(avatarSource(fileName), StandardCharsets.UTF_8);
			assertThat(source)
				.doesNotContain("GenerationWorkQueue")
				.doesNotContain("SqsGenerationWorkQueue")
				.doesNotContain("software.amazon.awssdk.services.sqs")
				.doesNotContain("SQS")
				.doesNotContain("Sqs")
				.doesNotContain("Redis")
				.doesNotContain("redis")
				.doesNotContain("Jedis")
				.doesNotContain("Redisson")
				.doesNotContain("lettuce");
		}
	}

	private static AvatarImage pendingImage() {
		return image(storageKey(UUID.randomUUID().toString()), AvatarImageLifecycle.DELETION_PENDING);
	}

	private static AvatarImage image(StorageKey storageKey, AvatarImageLifecycle lifecycle) {
		return new AvatarImage(
			new AvatarImageId(UUID.randomUUID()),
			new AvatarProfileId(UUID.randomUUID()),
			storageKey,
			768,
			1024,
			null,
			"ACCEPTED",
			NOW,
			new UploadId(UUID.randomUUID()),
			lifecycle,
			List.of(),
			null);
	}

	private static StorageKey storageKey(String name) {
		return new StorageKey("users/" + UUID.randomUUID() + "/avatars/" + name + ".jpg");
	}

	private static ObjectRef ref(AvatarImage image) {
		return new ObjectRef(StorageBoundary.AVATAR, image.storageKey().value());
	}

	private static List<Method> beanMethods(Class<?> type) {
		List<Method> methods = new ArrayList<>();
		for (Method method : type.getDeclaredMethods()) {
			if (method.isAnnotationPresent(Bean.class)) {
				methods.add(method);
			}
		}
		return methods;
	}

	private static List<Method> scheduledMethods(Class<?> type) {
		List<Method> methods = new ArrayList<>();
		for (Method method : type.getDeclaredMethods()) {
			if (method.isAnnotationPresent(Scheduled.class)) {
				methods.add(method);
			}
		}
		for (Class<?> nested : type.getDeclaredClasses()) {
			methods.addAll(scheduledMethods(nested));
		}
		return methods;
	}

	private static Duration delay(long amount, String amountString, TimeUnit unit) {
		if (amount >= 0) {
			return Duration.of(amount, unit.toChronoUnit());
		}
		if (amountString != null && !amountString.isBlank()) {
			if (amountString.startsWith("P")) {
				return Duration.parse(amountString);
			}
			return Duration.of(Long.parseLong(amountString), unit.toChronoUnit());
		}
		return Duration.ofMillis(-1);
	}

	private static Path avatarSource(String fileName) {
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
				fileName));
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
				fileName));
			if (Files.isRegularFile(underBackend)) {
				return underBackend;
			}
			current = current.getParent();
		}
		throw new IllegalStateException("Cannot locate " + fileName + " from " + cwd);
	}

	private static final class FakeObjectStore implements ObjectStore {

		private final List<ObjectRef> deleted = new ArrayList<>();
		private RuntimeException deleteFailure;
		private ObjectRef failingObject;
		private RuntimeException failingObjectError;

		void failDelete(RuntimeException failure) {
			this.deleteFailure = failure;
		}

		void failDelete(ObjectRef object, RuntimeException failure) {
			this.failingObject = object;
			this.failingObjectError = failure;
		}

		List<ObjectRef> deleted() {
			return List.copyOf(deleted);
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
			if (deleteFailure != null) {
				throw deleteFailure;
			}
			if (failingObject != null && failingObject.equals(object) && failingObjectError != null) {
				throw failingObjectError;
			}
			deleted.add(object);
		}

		@Override
		public ClaimResult claim(ClaimCommand command) {
			throw new AssertionError("claim must not be called");
		}

		@Override
		public void purgeUserObjects(UserId userId) {
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
