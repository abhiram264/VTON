package com.aifashionvault.platform.objectstore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;

class GenerationOrphanSweeperTest {

	private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final Duration SAFE_AGE = Duration.ofHours(1);
	private static final String ORPHAN_TEMP_KEY = "temp/user/job/orphan.jpg";
	private static final String LOOK_KEY = "users/user/looks/look-1.jpg";
	private static final String YOUNG_TEMP_KEY = "temp/user/job/young.jpg";

	@Test
	void unreferencedObjectAfterSafeAgeIsDeletedAndReferencedLookIsKept() {
		FakeObjectStore objects = new FakeObjectStore();
		ObjectRef orphanTemp = new ObjectRef(StorageBoundary.TEMP, ORPHAN_TEMP_KEY);
		ObjectRef referencedLook = new ObjectRef(StorageBoundary.LOOK, LOOK_KEY);
		ObjectRef youngTemp = new ObjectRef(StorageBoundary.TEMP, YOUNG_TEMP_KEY);
		objects.put(orphanTemp, NOW.minus(Duration.ofHours(2)));
		objects.put(referencedLook, NOW.minus(Duration.ofHours(3)));
		objects.put(youngTemp, NOW.minus(Duration.ofMinutes(10)));

		GenerationOrphanSweeper sweeper = new GenerationOrphanSweeper(
			objects,
			ref -> LOOK_KEY.equals(ref.objectKey()),
			CLOCK,
			SAFE_AGE);

		int deleted = sweeper.sweep();

		assertThat(deleted).isEqualTo(1);
		assertThat(objects.contains(orphanTemp)).isFalse();
		assertThat(objects.contains(referencedLook)).isTrue();
		assertThat(objects.contains(youngTemp)).isTrue();
		assertThat(objects.deleted()).containsExactly(orphanTemp);
	}

	@Test
	void objectExistenceDoesNotGrantVisibilityAndUnreferencedFinalIsSwept() {
		FakeObjectStore objects = new FakeObjectStore();
		ObjectRef orphanLook = new ObjectRef(StorageBoundary.LOOK, "users/user/looks/uncommitted.jpg");
		objects.put(orphanLook, NOW.minus(Duration.ofHours(5)));

		GenerationOrphanSweeper sweeper = new GenerationOrphanSweeper(
			objects,
			ref -> false,
			CLOCK,
			SAFE_AGE);

		assertThat(sweeper.sweep()).isEqualTo(1);
		assertThat(objects.contains(orphanLook)).isFalse();
		assertThat(objects.deleted()).containsExactly(orphanLook);
	}

	private static final class FakeObjectStore implements ObjectStore {

		private final Map<ObjectRef, StoredObject> stored = new LinkedHashMap<>();
		private final List<ObjectRef> deleted = new ArrayList<>();

		void put(ObjectRef ref, Instant lastModified) {
			stored.put(ref, new StoredObject(ref.objectKey(), lastModified));
		}

		boolean contains(ObjectRef ref) {
			return stored.containsKey(ref);
		}

		List<ObjectRef> deleted() {
			return List.copyOf(deleted);
		}

		@Override
		public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration) {
			throw new UnsupportedOperationException();
		}

		@Override
		public ObjectMetadata head(ObjectRef object) {
			throw new UnsupportedOperationException();
		}

		@Override
		public byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void delete(ObjectRef object) {
			stored.remove(object);
			deleted.add(object);
		}

		@Override
		public List<StoredObject> list(StorageBoundary boundary, String prefix) {
			List<StoredObject> matches = new ArrayList<>();
			for (Map.Entry<ObjectRef, StoredObject> entry : stored.entrySet()) {
				if (entry.getKey().boundary() != boundary) {
					continue;
				}
				if (prefix != null && !prefix.isBlank() && !entry.getKey().objectKey().startsWith(prefix)) {
					continue;
				}
				matches.add(entry.getValue());
			}
			return matches;
		}

		@Override
		public ClaimResult claim(ClaimCommand command) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void purgeUserObjects(UserId userId) {
			throw new UnsupportedOperationException();
		}

		@Override
		public void cleanupTemp() {
			throw new UnsupportedOperationException();
		}

		@Override
		public PresignedAccess presignGet(ObjectRef object, Duration ttl) {
			throw new UnsupportedOperationException();
		}
	}
}
