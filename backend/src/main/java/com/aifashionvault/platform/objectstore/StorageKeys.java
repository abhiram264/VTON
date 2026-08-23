package com.aifashionvault.platform.objectstore;

import java.util.UUID;

import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

public final class StorageKeys {

	private StorageKeys() {
	}

	public static StorageKey stagingKey(UserId userId, UploadId uploadId) {
		requireNonNull(userId, "userId");
		requireNonNull(uploadId, "uploadId");
		return new StorageKey("temp/" + userId.value() + "/uploads/" + uploadId.value());
	}

	public static StorageKey persistentKey(
		UserId userId,
		StorageBoundary boundary,
		UUID stableId,
		ImageMediaType mediaType
	) {
		requireNonNull(userId, "userId");
		requireNonNull(boundary, "boundary");
		requireNonNull(stableId, "stableId");
		requireNonNull(mediaType, "mediaType");
		String folder = switch (boundary) {
			case AVATAR -> "avatars";
			case VAULT -> "vault";
			case LOOK -> "looks";
			case TEMP -> throw new IllegalArgumentException("persistentKey is not for TEMP");
		};
		return new StorageKey("users/" + userId.value() + "/" + folder + "/" + stableId + extension(mediaType));
	}

	public static StorageKey generationTempKey(
		UserId userId,
		UUID jobId,
		UUID objectId,
		ImageMediaType mediaType
	) {
		requireNonNull(userId, "userId");
		requireNonNull(jobId, "jobId");
		requireNonNull(objectId, "objectId");
		requireNonNull(mediaType, "mediaType");
		return new StorageKey("temp/" + userId.value() + "/" + jobId + "/" + objectId + extension(mediaType));
	}

	private static String extension(ImageMediaType mediaType) {
		return switch (mediaType) {
			case JPEG -> ".jpg";
			case PNG -> ".png";
		};
	}

	private static void requireNonNull(Object value, String name) {
		if (value == null) {
			throw new IllegalArgumentException(name + " is required");
		}
	}
}
