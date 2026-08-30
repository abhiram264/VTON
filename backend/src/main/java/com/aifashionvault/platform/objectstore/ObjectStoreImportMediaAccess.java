package com.aifashionvault.platform.objectstore;

import java.io.InputStream;
import java.util.Optional;

import software.amazon.awssdk.services.s3.model.S3Exception;

import com.aifashionvault.importer.ImportMediaAccess;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

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
		throw new UnsupportedOperationException("findVerifiedUpload requires an owner; use the overload");
	}

	public Optional<VerifiedImage> findVerifiedUpload(UserId ownerId, UploadId uploadId) {
		if (ownerId == null) {
			throw new IllegalArgumentException("ownerId is required");
		}
		if (uploadId == null) {
			throw new IllegalArgumentException("uploadId is required");
		}
		ObjectRef ref = new ObjectRef(StorageBoundary.TEMP, StorageKeys.stagingKey(ownerId, uploadId).value());
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
		throw new UnsupportedOperationException("claimVerifiedUpload requires an owner; use the overload");
	}

	public ClaimOutcome claimVerifiedUpload(UserId ownerId, VerifiedImage upload, StorageKey destination) {
		if (ownerId == null) {
			throw new IllegalArgumentException("ownerId is required");
		}
		if (upload == null || destination == null) {
			throw new IllegalArgumentException("upload and destination are required");
		}
		ObjectRef source = new ObjectRef(
				StorageBoundary.TEMP,
				StorageKeys.stagingKey(ownerId, upload.id(), UploadPurpose.GENERIC).value());
		ObjectRef dest = new ObjectRef(StorageBoundary.VAULT, destination.value());
		ClaimResult result = store.claim(new ClaimCommand(
				ownerId,
				upload.id(),
				destination,
				StorageBoundary.VAULT,
				claimMarker(ownerId, destination),
				upload.version()));
		if (result instanceof ClaimResult.Copied) {
			// Story 38: a claim is one-time. The copy succeeded, so the staging
			// object is consumed regardless of whether its delete succeeds.
			consumeStaging(source);
			return ClaimOutcome.CLAIMED;
		}
		if (result instanceof ClaimResult.Replay) {
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
		if (contentLength < 0) {
			throw new IllegalArgumentException("contentLength must not be negative");
		}
		String normalized = ImageMediaPolicy.requireDeclaration(contentType, Math.max(contentLength, 1L));
		if (writer == null || ownerId == null) {
			throw new IllegalStateException("publishing fetched images requires an owner-scoped adapter");
		}
		ObjectRef dest = new ObjectRef(StorageBoundary.VAULT, destination.value());
		return writer.putIfAbsent(dest, content, contentLength, claimMarker(ownerId, destination), normalized);
	}

	@Override
	public void deleteObject(StorageKey key) {
		if (key == null) {
			throw new IllegalArgumentException("key is required");
		}
		try {
			store.delete(new ObjectRef(StorageBoundary.VAULT, key.value()));
		}
		catch (ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
			// best-effort publish cleanup: never mask the retryable publish failure
		}
		catch (S3Exception ex) {
			// store.delete rethrows service-side S3 failures raw; publish
			// cleanup must stay best-effort and typed
			throw new ObjectStoreExceptions.ObjectStoreUnavailableException("object store is unavailable");
		}
	}

	private static String claimMarker(UserId ownerId, StorageKey destination) {
		return "import:" + ownerId.value() + ":" + destination.value();
	}
}
