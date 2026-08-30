package com.aifashionvault.avatar;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

public class AvatarWorkflow {
	private static final Logger LOGGER = LoggerFactory.getLogger(AvatarWorkflow.class);

	private static final long MAX_STAGING_BYTES = 30L * 1024 * 1024;

	private final JdbcAvatarStore store;
	private final AvatarMediaAccess mediaAccess;
	private final AvatarPreflight preflight;
	private final Clock clock;

	public AvatarWorkflow(JdbcAvatarStore store, AvatarMediaAccess mediaAccess, AvatarPreflight preflight) {
		this(store, mediaAccess, preflight, Clock.systemUTC());
	}

	public AvatarWorkflow(JdbcAvatarStore store, AvatarMediaAccess mediaAccess, AvatarPreflight preflight, Clock clock) {
		if (store == null || mediaAccess == null || preflight == null || clock == null) {
			throw new IllegalArgumentException("store, mediaAccess, preflight, and clock are required");
		}
		this.store = store;
		this.mediaAccess = mediaAccess;
		this.preflight = preflight;
		this.clock = clock;
	}

	public AvatarProfile create(UserId userId, UploadId uploadId) {
		requireUserAndUpload(userId, uploadId);
		Optional<AvatarProfile> replayed = replayCurrentPrimary(userId, uploadId);
		if (replayed.isPresent()) {
			return replayed.get();
		}
		requireNotConsumed(userId, uploadId);
		if (store.findByUser(userId).isPresent()) {
			throw new AvatarException(AvatarProblemCode.AVATAR_CONFLICT);
		}
		PreparedImage prepared = inspectAndClaim(userId, uploadId);
		Optional<AvatarProfile> replayedByKey = replayCurrentPrimary(userId, prepared.destination());
		if (replayedByKey.isPresent()) {
			return replayedByKey.get();
		}
		Instant now = Instant.now(clock);
		AvatarProfileId profileId = new AvatarProfileId(UUID.randomUUID());
		AvatarProfile stored;
		try {
			stored = store.insertFirst(
				new AvatarProfile(profileId, userId, prepared.imageId(), "ACTIVE", 1, now, now),
				image(prepared, profileId, uploadId, now));
		}
		catch (RuntimeException ex) {
			compensatePersistent(prepared.destination());
			throw ex;
		}
		releaseAcceptedStaging(userId, uploadId);
		return requireActiveMedia(stored);
	}

	public AvatarProfile replace(UserId userId, AvatarProfileId profileId, int expectedRevision, UploadId uploadId) {
		requireUserAndUpload(userId, uploadId);
		if (profileId == null) {
			throw new IllegalArgumentException("profileId is required");
		}
		Optional<AvatarProfile> replayed = replayCurrentPrimary(userId, uploadId);
		if (replayed.isPresent()) {
			if (!replayed.get().id().equals(profileId)) {
				throw new AvatarException(AvatarProblemCode.AVATAR_NOT_FOUND);
			}
			return replayed.get();
		}
		requireNotConsumed(userId, uploadId);
		AvatarProfile current = store.findByIdAndUser(profileId, userId)
			.filter(profile -> "ACTIVE".equals(profile.status()))
			.orElseThrow(() -> new AvatarException(AvatarProblemCode.AVATAR_NOT_FOUND));
		if (current.revision() != expectedRevision) {
			throw new AvatarException(AvatarProblemCode.AVATAR_STALE_REVISION);
		}
		PreparedImage prepared = inspectAndClaim(userId, uploadId);
		Optional<AvatarProfile> replayedByKey = replayCurrentPrimary(userId, prepared.destination());
		if (replayedByKey.isPresent()) {
			if (!replayedByKey.get().id().equals(profileId)) {
				throw new AvatarException(AvatarProblemCode.AVATAR_NOT_FOUND);
			}
			return replayedByKey.get();
		}
		AvatarProfile stored;
		try {
			stored = store.replaceImage(
				profileId,
				userId,
				expectedRevision,
				image(prepared, profileId, uploadId, Instant.now(clock)));
		}
		catch (RuntimeException ex) {
			compensatePersistent(prepared.destination());
			throw ex;
		}
		releaseAcceptedStaging(userId, uploadId);
		return requireActiveMedia(stored);
	}

	public Optional<AvatarProfile> get(UserId userId, AvatarProfileId profileId) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (profileId == null) {
			throw new IllegalArgumentException("profileId is required");
		}
		Optional<AvatarProfile> found = store.findByIdAndUser(profileId, userId);
		if (found.isEmpty() || "DELETING".equals(found.get().status())) {
			return Optional.empty();
		}
		return Optional.of(requireActiveMedia(found.get()));
	}

	public List<AvatarProfile> list(UserId userId) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		return store.findByUser(userId)
			.map(this::requireActiveMedia)
			.map(List::of)
			.orElseGet(List::of);
	}

	public void delete(UserId userId, AvatarProfileId profileId, Integer expectedRevision) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (profileId == null) {
			throw new IllegalArgumentException("profileId is required");
		}
		store.logicalDelete(profileId, userId, expectedRevision);
	}

	public Optional<AvatarImage> currentImage(AvatarProfile profile) {
		if (profile == null || profile.primaryImageId() == null) {
			return Optional.empty();
		}
		return store.findImage(profile.userId(), profile.primaryImageId());
	}

	private Optional<AvatarProfile> replayCurrentPrimary(UserId userId, UploadId uploadId) {
		return replayIfCurrentPrimary(userId, store.findBySourceUpload(userId, uploadId));
	}

	private Optional<AvatarProfile> replayCurrentPrimary(UserId userId, StorageKey storageKey) {
		return replayIfCurrentPrimary(userId, store.findByStorageKey(userId, storageKey));
	}

	private Optional<AvatarProfile> replayIfCurrentPrimary(UserId userId, Optional<AvatarImage> existing) {
		if (existing.isEmpty()) {
			return Optional.empty();
		}
		Optional<AvatarProfile> active = store.findByUser(userId);
		if (active.isPresent() && existing.get().id().equals(active.get().primaryImageId())) {
			return Optional.of(requireActiveMedia(active.get()));
		}
		throw new AvatarException(AvatarProblemCode.AVATAR_UPLOAD_NOT_CLAIMABLE);
	}

	private void requireNotConsumed(UserId userId, UploadId uploadId) {
		if (store.isUploadConsumed(userId, uploadId)) {
			throw new AvatarException(AvatarProblemCode.AVATAR_UPLOAD_NOT_CLAIMABLE);
		}
	}

	private void compensatePersistent(StorageKey storageKey) {
		try {
			mediaAccess.deletePersistent(storageKey);
		}
		catch (RuntimeException ignored) {
			LOGGER.warn("Avatar media compensation failed; orphan reconciliation will retry");
		}
	}

	private void releaseAcceptedStaging(UserId userId, UploadId uploadId) {
		try {
			mediaAccess.deleteAvatarStaging(userId, uploadId);
		}
		catch (RuntimeException ignored) {
			LOGGER.warn("Accepted avatar staging release failed; temporary cleanup will retry");
		}
	}

	private PreparedImage inspectAndClaim(UserId userId, UploadId uploadId) {
		AvatarMediaAccess.StagingObject staging = mediaAccess.findAvatarStaging(userId, uploadId)
			.orElseThrow(() -> new AvatarException(AvatarProblemCode.AVATAR_UPLOAD_NOT_CLAIMABLE));
		byte[] bytes = mediaAccess.readAvatarStaging(
			userId,
			uploadId,
			staging.version(),
			boundedReadCap(staging.contentLength()));
		AvatarPreflightResult inspected;
		try {
			inspected = preflight.inspect(bytes);
		}
		catch (AvatarException ex) {
			if (ex.code() != AvatarProblemCode.AVATAR_PREFLIGHT_UNAVAILABLE) {
				try {
					mediaAccess.deleteAvatarStaging(userId, uploadId);
				}
				catch (RuntimeException ignored) {
					// best-effort TEMP release; caller rethrows the deterministic rejection
				}
			}
			throw ex;
		}
		AvatarImageId imageId = new AvatarImageId(UUID.randomUUID());
		StorageKey destination = mediaAccess.claimAcceptedImage(
			userId,
			uploadId,
			imageId,
			inspected.mediaType(),
			staging.version());
		return new PreparedImage(imageId, destination, inspected);
	}

	private AvatarProfile requireActiveMedia(AvatarProfile profile) {
		if (profile.primaryImageId() == null) {
			throw new AvatarException(AvatarProblemCode.AVATAR_MEDIA_MISSING);
		}
		AvatarImage image = store.findImage(profile.userId(), profile.primaryImageId())
			.orElseThrow(() -> new AvatarException(AvatarProblemCode.AVATAR_MEDIA_MISSING));
		mediaAccess.requirePersistentPresent(image.storageKey());
		return profile;
	}

	private static AvatarImage image(PreparedImage prepared, AvatarProfileId profileId, UploadId uploadId, Instant now) {
		return new AvatarImage(
			prepared.imageId(),
			profileId,
			prepared.destination(),
			prepared.inspected().width(),
			prepared.inspected().height(),
			null,
			"ACCEPTED",
			now,
			uploadId,
			AvatarImageLifecycle.ACTIVE,
			prepared.inspected().warnings(),
			prepared.inspected().policyVersion());
	}

	private static void requireUserAndUpload(UserId userId, UploadId uploadId) {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (uploadId == null) {
			throw new IllegalArgumentException("uploadId is required");
		}
	}

	private static int boundedReadCap(long contentLength) {
		long cap = Math.min(contentLength, MAX_STAGING_BYTES);
		if (cap > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		if (cap <= 0) {
			return (int) MAX_STAGING_BYTES;
		}
		return (int) cap;
	}

	private record PreparedImage(
		AvatarImageId imageId,
		StorageKey destination,
		AvatarPreflightResult inspected
	) {
	}
}
