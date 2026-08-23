package com.aifashionvault.platform.objectstore;

import java.time.Duration;

import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

public interface ObjectStore {

	UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration);

	ObjectMetadata head(ObjectRef object);

	byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes);

	void delete(ObjectRef object);

	ClaimResult claim(ClaimCommand command);

	void purgeUserObjects(UserId userId);

	void cleanupTemp();

	PresignedAccess presignGet(ObjectRef object, Duration ttl);
}
