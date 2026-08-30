package com.aifashionvault.platform.objectstore;

import java.util.List;
import java.util.Optional;

import com.aifashionvault.avatar.AvatarException;
import com.aifashionvault.avatar.AvatarMediaAccess;
import com.aifashionvault.avatar.AvatarMediaType;
import com.aifashionvault.avatar.AvatarProblemCode;
import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

public final class ObjectStoreAvatarMediaAccess implements AvatarMediaAccess {

	private final ObjectStore objectStore;

	public ObjectStoreAvatarMediaAccess(ObjectStore objectStore) {
		if (objectStore == null) {
			throw new IllegalArgumentException("objectStore is required");
		}
		this.objectStore = objectStore;
	}

	@Override
	public Optional<StagingObject> findAvatarStaging(UserId userId, UploadId uploadId) {
		try {
			ObjectMetadata metadata = objectStore.head(stagingRef(userId, uploadId));
			return Optional.of(new StagingObject(metadata.version(), metadata.contentLength()));
		}
		catch (ObjectStoreExceptions.StagedObjectMissingException ignored) {
			return Optional.empty();
		}
	}

	@Override
	public byte[] readAvatarStaging(UserId userId, UploadId uploadId, String version, int maxBytes) {
		try {
			return objectStore.readBounded(stagingRef(userId, uploadId), version, maxBytes);
		}
		catch (ObjectStoreExceptions.StagedObjectMissingException ignored) {
			throw new AvatarException(AvatarProblemCode.AVATAR_UPLOAD_NOT_CLAIMABLE);
		}
	}

	@Override
	public void deleteAvatarStaging(UserId userId, UploadId uploadId) {
		try {
			objectStore.delete(stagingRef(userId, uploadId));
		}
		catch (ObjectStoreExceptions.StagedObjectMissingException ignored) {
			// missing is success
		}
	}

	@Override
	public StorageKey claimAcceptedImage(
		UserId userId,
		UploadId uploadId,
		AvatarImageId imageId,
		AvatarMediaType mediaType,
		String sourceVersion
	) {
		StorageKey destination = StorageKeys.persistentKey(
			userId,
			StorageBoundary.AVATAR,
			imageId.value(),
			toImageMediaType(mediaType));
		ClaimResult claimed = objectStore.claim(new ClaimCommand(
			userId,
			uploadId,
			destination,
			StorageBoundary.AVATAR,
			imageId.value().toString(),
			sourceVersion));
		if (claimed instanceof ClaimResult.Conflict) {
			throw new AvatarException(AvatarProblemCode.AVATAR_CONFLICT);
		}
		return destination;
	}

	@Override
	public void requirePersistentPresent(StorageKey storageKey) {
		try {
			objectStore.head(persistentRef(storageKey));
		}
		catch (ObjectStoreExceptions.StagedObjectMissingException ignored) {
			throw new AvatarException(AvatarProblemCode.AVATAR_MEDIA_MISSING);
		}
	}

	@Override
	public void deletePersistent(StorageKey storageKey) {
		try {
			objectStore.delete(persistentRef(storageKey));
		}
		catch (ObjectStoreExceptions.StagedObjectMissingException ignored) {
			// missing is success
		}
	}

	@Override
	public List<PersistentObject> listPersistentAvatars() {
		return objectStore.list(StorageBoundary.AVATAR, "users/")
			.stream()
			.map(object -> new PersistentObject(new StorageKey(object.key()), object.lastModified()))
			.toList();
	}

	private static ObjectRef stagingRef(UserId userId, UploadId uploadId) {
		return new ObjectRef(
			StorageBoundary.TEMP,
			StorageKeys.stagingKey(userId, uploadId, UploadPurpose.AVATAR).value());
	}

	private static ObjectRef persistentRef(StorageKey storageKey) {
		return new ObjectRef(StorageBoundary.AVATAR, storageKey.value());
	}

	private static ImageMediaType toImageMediaType(AvatarMediaType mediaType) {
		return switch (mediaType) {
			case JPEG -> ImageMediaType.JPEG;
			case PNG -> ImageMediaType.PNG;
		};
	}
}
