package com.aifashionvault.platform.objectstore;

import java.time.Duration;
import java.util.List;

import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.StorageDomain;

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

	/**
	 * Lists the stored objects under a boundary. Implementations that cannot
	 * enumerate MUST throw rather than return an empty list.
	 *
	 * <p>{@code GenerationOrphanSweeper} decides what to delete from this list: an
	 * implementation that silently returns nothing would report a healthy
	 * zero-delete sweep while reaping nothing, so the failure would be invisible.
	 * Throwing keeps that failure loud.</p>
	 */
	default List<StoredObject> list(StorageDomain boundary, String prefix) {
		throw new UnsupportedOperationException(
			getClass().getName() + " cannot list " + boundary + " (prefix='" + prefix + "')");
	}

	ClaimResult claim(ClaimCommand command);

	void purgeUserObjects(UserId userId);

	void cleanupTemp();

	PresignedAccess presignGet(ObjectRef object, Duration ttl);
}
