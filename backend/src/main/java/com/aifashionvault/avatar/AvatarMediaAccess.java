package com.aifashionvault.avatar;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

public interface AvatarMediaAccess {

	record StagingObject(String version, long contentLength) {

		public StagingObject {
			if (version == null || version.isBlank()) {
				throw new IllegalArgumentException("version is required");
			}
		}
	}

	record PersistentObject(StorageKey key, Instant lastModified) {}

	Optional<StagingObject> findAvatarStaging(UserId userId, UploadId uploadId);

	byte[] readAvatarStaging(UserId userId, UploadId uploadId, String version, int maxBytes);

	void deleteAvatarStaging(UserId userId, UploadId uploadId);

	StorageKey claimAcceptedImage(
		UserId userId,
		UploadId uploadId,
		AvatarImageId imageId,
		AvatarMediaType mediaType,
		String sourceVersion);

	void requirePersistentPresent(StorageKey storageKey);

	void deletePersistent(StorageKey storageKey);

	default List<PersistentObject> listPersistentAvatars() {
		return List.of();
	}
}
