package com.aifashionvault.platform.objectstore;

import java.util.UUID;

import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

public final class StorageKeys {

	private StorageKeys() {
	}

	public static StorageKey stagingKey(UserId userId, UploadId uploadId) {
		return stagingKey(userId, uploadId, UploadPurpose.GENERIC);
	}

	public static StorageKey stagingKey(UserId userId, UploadId uploadId, UploadPurpose purpose) {
		requireNonNull(userId, "userId");
		requireNonNull(uploadId, "uploadId");
		requireNonNull(purpose, "purpose");
		String folder = switch (purpose) {
			case GENERIC -> "uploads";
			case AVATAR -> "avatars";
		};
		return new StorageKey("temp/" + userId.value() + "/" + folder + "/" + uploadId.value());
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
			case GENERATION_INPUT, TEMP -> throw new IllegalArgumentException("persistentKey is not for " + boundary);
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

	public static StorageKey generationInputKey(UserId userId, UUID generationId, UUID mediaAssetId) {
		requireNonNull(userId, "userId");
		requireNonNull(generationId, "generationId");
		requireNonNull(mediaAssetId, "mediaAssetId");
		return new StorageKey("users/" + userId.value() + "/generation-inputs/" + generationId + "/" + mediaAssetId);
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
