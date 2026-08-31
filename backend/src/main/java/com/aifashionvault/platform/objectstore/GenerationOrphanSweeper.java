package com.aifashionvault.platform.objectstore;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import com.aifashionvault.shared.StorageDomain;

public final class GenerationOrphanSweeper {

	public static final Duration DEFAULT_SAFE_AGE = Duration.ofHours(24);

	@FunctionalInterface
	public interface ReferencedObjects {

		boolean referenced(ObjectRef object);
	}

	private final ObjectStore objects;
	private final ReferencedObjects references;
	private final Clock clock;
	private final Duration safeAge;

	public GenerationOrphanSweeper(ObjectStore objects, ReferencedObjects references, Clock clock, Duration safeAge) {
		if (objects == null) {
			throw new IllegalArgumentException("objects is required");
		}
		if (references == null) {
			throw new IllegalArgumentException("references is required");
		}
		if (clock == null) {
			throw new IllegalArgumentException("clock is required");
		}
		if (safeAge == null || safeAge.isNegative() || safeAge.isZero()) {
			throw new IllegalArgumentException("safeAge must be positive");
		}
		this.objects = objects;
		this.references = references;
		this.clock = clock;
		this.safeAge = safeAge;
	}

	public int sweep() {
		int deleted = 0;
		deleted += sweepBoundary(StorageDomain.TEMP);
		deleted += sweepBoundary(StorageDomain.TEMP);
		deleted += sweepBoundary(StorageDomain.LOOK);
		return deleted;
	}

	private int sweepBoundary(StorageDomain boundary) {
		List<StoredObject> listed = objects.list(boundary, "");
		Instant cutoff = clock.instant().minus(safeAge);
		int deleted = 0;
		for (StoredObject stored : listed) {
			if (stored.lastModified() == null || stored.lastModified().isAfter(cutoff)) {
				continue;
			}
			ObjectRef ref = new ObjectRef(boundary, stored.key());
			if (references.referenced(ref)) {
				continue;
			}
			try {
				objects.delete(ref);
				deleted++;
			}
			catch (RuntimeException ignored) {
				// A later sweep retries this object without blocking other cleanup.
			}
		}
		return deleted;
	}
}
