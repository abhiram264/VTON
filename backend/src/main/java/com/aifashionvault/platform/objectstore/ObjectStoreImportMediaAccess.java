package com.aifashionvault.platform.objectstore;

import java.io.InputStream;
import java.util.Optional;


import com.aifashionvault.importer.ImportMediaAccess;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.StorageDomain;

/**
 * Importer media adapter over the existing private object store: claims a
 * verified GENERIC staging upload to an owner/import-derived private key,
 * conditionally creates objects from fetched bytes, and removes owned objects.
 */
public final class ObjectStoreImportMediaAccess implements ImportMediaAccess {

	private final ObjectStore store;
	private final ImageInspector inspector;
	private final ImportObjectWriter writer;
	private final UserId ownerId;

	public ObjectStoreImportMediaAccess(ObjectStore store, ImageInspector inspector) {
		this(store, inspector, null, null);
	}

	public ObjectStoreImportMediaAccess(ObjectStore store, ImageInspector inspector, ImportObjectWriter writer,
			UserId ownerId) {
		this.store = store;
		this.inspector = inspector;
		this.writer = writer;
		this.ownerId = ownerId;
	}

	@Override
	public Optional<VerifiedImage> findVerifiedUpload(UploadId uploadId) {
		if (uploadId == null) {
			throw new IllegalArgumentException("uploadId is required");
		}
		if (this.ownerId == null) {
			throw new IllegalArgumentException("ownerId is required");
		}
		return findVerifiedUpload(this.ownerId, uploadId);
	}

	public Optional<VerifiedImage> findVerifiedUpload(UserId ownerId, UploadId uploadId) {
		if (ownerId == null) {
			throw new IllegalArgumentException("ownerId is required");
		}
		if (uploadId == null) {
			throw new IllegalArgumentException("uploadId is required");
		}
		ObjectRef ref = new ObjectRef(StorageDomain.TEMP, StorageKeys.stagingKey(ownerId, uploadId).value());
		final ObjectMetadata metadata;
		try {
			metadata = store.head(ref);
		}
		catch (ObjectStoreExceptions.StagedObjectMissingException ex) {
			return Optional.empty();
		}
		String normalized;
		try {
			normalized = ImageMediaPolicy.requireDeclaration(metadata.contentType(), metadata.contentLength());
		}
		catch (IllegalArgumentException ex) {
			return Optional.empty();
		}
		byte[] bytes = store.readBounded(ref, metadata.version(), (int) Math.min(metadata.contentLength(),
				ImageMediaPolicy.MAX_BYTES));
		ImageMediaType inspected;
		try {
			inspected = inspector.inspect(bytes).mediaType();
		}
		catch (ObjectStoreExceptions.ImageValidationException ex) {
			return Optional.empty();
		}
		boolean matches = ("image/jpeg".equals(normalized) && inspected == ImageMediaType.JPEG)
				|| ("image/png".equals(normalized) && inspected == ImageMediaType.PNG);
		if (!matches) {
			return Optional.empty();
		}
		return Optional.of(new VerifiedImage(
				uploadId,
				normalized,
				metadata.contentLength(),
				metadata.version()));
	}

	@Override
	public ClaimOutcome claimVerifiedUpload(VerifiedImage upload, StorageKey destination) {
		if (this.ownerId == null) {
			throw new IllegalArgumentException("ownerId is required");
		}
		return claimVerifiedUpload(this.ownerId, upload, destination);
	}

	public ClaimOutcome claimVerifiedUpload(UserId ownerId, VerifiedImage upload, StorageKey destination) {
		if (ownerId == null) {
			throw new IllegalArgumentException("ownerId is required");
		}
		if (upload == null || destination == null) {
			throw new IllegalArgumentException("upload and destination are required");
		}
		ObjectRef source = new ObjectRef(
				StorageDomain.TEMP,
				StorageKeys.stagingKey(ownerId, upload.id(), UploadPurpose.GENERIC).value());
		ObjectRef dest = new ObjectRef(StorageDomain.VAULT, destination.value());
		ClaimResult result = store.claim(new ClaimCommand(
				ownerId,
				upload.id(),
				destination,
				StorageDomain.VAULT,
				claimMarker(ownerId, destination),
				upload.version()));
		if (result instanceof ClaimResult.Copied) {
			// Story 38: a claim is one-time. The copy succeeded, so the staging
			// object is consumed regardless of whether its delete succeeds.
			consumeStaging(source);
			return ClaimOutcome.CLAIMED;
		}
		if (result instanceof ClaimResult.Replay) {
			// M03 follow-up: consuming staging on verified replay prevents
			// orphaned staging objects when the destination was already claimed.
			consumeStaging(source);
			return ClaimOutcome.REPLAYED;
		}
		return ClaimOutcome.CONFLICT;
	}

	private void consumeStaging(ObjectRef source) {
		try {
			store.delete(source);
		}
		catch (RuntimeException ignored) {
			// the claim itself succeeded; a leftover staging object is reaped
			// by temp expiry — best-effort, like ImportObjectWriter.deleteQuietly
		}
	}

	@Override
	public boolean publishFetchedImage(InputStream content, long contentLength, StorageKey destination,
			String contentType) {
		if (content == null) {
			throw new IllegalArgumentException("content is required");
		}
		if (destination == null) {
			throw new IllegalArgumentException("destination is required");
		}
		if (contentLength <= 0) {
			throw new IllegalArgumentException("contentLength must be positive");
		}
		String normalized = ImageMediaPolicy.requireDeclaration(contentType, contentLength);
		if (writer == null || ownerId == null) {
			throw new IllegalStateException("publishing fetched images requires an owner-scoped adapter");
		}
		ObjectRef dest = new ObjectRef(StorageDomain.VAULT, destination.value());
		return writer.putIfAbsent(dest, content, contentLength, claimMarker(ownerId, destination), normalized);
	}

	@Override
	public void deleteObject(StorageKey key) {
		if (key == null) {
			throw new IllegalArgumentException("key is required");
		}
		ObjectRef target = new ObjectRef(StorageDomain.VAULT, key.value());
		try {
			store.delete(target);
		}
		catch (ObjectStoreExceptions.StagedObjectMissingException ex) {
			// Already absent is an idempotent delete success.
		}
		catch (ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
			// Re-throw typed provider failure so callers (e.g. M03 account media purge)
			// observe the failure and retain retryable records.
			throw ex;
		}
		catch (RuntimeException ex) {
			throw new ObjectStoreExceptions.ObjectStoreUnavailableException(
					"object store is unavailable during delete");
		}
	}
	private static String claimMarker(UserId ownerId, StorageKey destination) {
		return "import:" + ownerId.value() + ":" + destination.value();
	}
}
