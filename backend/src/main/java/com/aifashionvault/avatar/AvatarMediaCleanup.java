package com.aifashionvault.avatar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class AvatarMediaCleanup {

	private static final System.Logger LOG = System.getLogger(AvatarMediaCleanup.class.getName());
	private static final Duration PERSISTENT_ORPHAN_DELETE_AGE = Duration.ofHours(20);

	private final JdbcAvatarStore store;
	private final AvatarMediaAccess mediaAccess;
	private final Clock clock;

	public AvatarMediaCleanup(JdbcAvatarStore store, AvatarMediaAccess mediaAccess) {
		this(store, mediaAccess, Clock.systemUTC());
	}

	public AvatarMediaCleanup(JdbcAvatarStore store, AvatarMediaAccess mediaAccess, Clock clock) {
		if (store == null) {
			throw new IllegalArgumentException("store is required");
		}
		if (mediaAccess == null) {
			throw new IllegalArgumentException("mediaAccess is required");
		}
		if (clock == null) {
			throw new IllegalArgumentException("clock is required");
		}
		this.store = store;
		this.mediaAccess = mediaAccess;
		this.clock = clock;
	}

	public void reconcile() {
		for (AvatarImage image : store.listDeletionPending()) {
			try {
				mediaAccess.deletePersistent(image.storageKey());
			}
			catch (RuntimeException ex) {
				LOG.log(
					System.Logger.Level.WARNING,
					"avatar media cleanup failed for image {0} profile {1}",
					image.id().value(),
					image.profileId().value());
				continue;
			}
			store.markReconciled(image.id());
		}
		reconcilePersistentOrphans();
	}

	private void reconcilePersistentOrphans() {
		List<AvatarMediaAccess.PersistentObject> objects;
		try {
			objects = mediaAccess.listPersistentAvatars();
		}
		catch (RuntimeException ex) {
			LOG.log(System.Logger.Level.WARNING, "persistent avatar orphan listing failed");
			return;
		}

		Instant oldestEligible = clock.instant().minus(PERSISTENT_ORPHAN_DELETE_AGE);
		for (AvatarMediaAccess.PersistentObject object : objects) {
			Instant lastModified = object.lastModified();
			if (lastModified == null || lastModified.isAfter(oldestEligible)) {
				continue;
			}
			try {
				if (store.findByStorageKey(object.key()).isEmpty()) {
					mediaAccess.deletePersistent(object.key());
				}
			}
			catch (RuntimeException ex) {
				LOG.log(System.Logger.Level.WARNING, "persistent avatar orphan reconciliation failed");
			}
		}
	}
}
