package com.aifashionvault.avatar;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AvatarMediaCleanup {

	private static final Logger log = LoggerFactory.getLogger(AvatarMediaCleanup.class);
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
		for (AvatarImage pending : store.listDeletionPending()) {
			try {
				mediaAccess.deletePersistent(pending.storageKey());
			}
			catch (RuntimeException ex) {
				log.warn(
					"avatar media cleanup failed for item {} profile {}",
					pending.id().value(),
					pending.profileId().value());
				continue;
			}
			store.markReconciled(pending.id());
		}
		reconcilePersistentOrphans();
	}

	private void reconcilePersistentOrphans() {
		List<AvatarMediaAccess.PersistentObject> objects;
		try {
			objects = mediaAccess.listPersistentAvatars();
		}
		catch (RuntimeException ex) {
			log.warn("persistent avatar orphan listing failed");
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
				log.warn("persistent avatar orphan reconciliation failed");
			}
		}
	}
}
