package com.aifashionvault.platform.objectstore;

import java.time.Duration;
import java.util.List;

import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

public interface ObjectStore {

	UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration);

	default UploadCapability presignStagingPut(
		UserId userId,
		UploadId uploadId,
		MediaDeclaration declaration,
		UploadPurpose purpose
	) {
		return presignStagingPut(userId, uploadId, declaration);
	}

	ObjectMetadata head(ObjectRef object);

	byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes);

	void delete(ObjectRef object);

	default List<StoredObject> list(StorageBoundary boundary, String prefix) {
		return List.of();
	}

	ClaimResult claim(ClaimCommand command);

	void purgeUserObjects(UserId userId);

	void cleanupTemp();

	PresignedAccess presignGet(ObjectRef object, Duration ttl);
}
