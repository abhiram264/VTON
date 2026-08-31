package com.aifashionvault.platform.objectstore;

import java.util.UUID;
import java.util.function.Supplier;

import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

public final class UploadWorkflow {

	private final ObjectStore store;
	private final ImageInspector inspector;
	private final Supplier<UUID> uploadIds;

	public UploadWorkflow(ObjectStore store, ImageInspector inspector) {
		this(store, inspector, UUID::randomUUID);
	}

	public UploadWorkflow(ObjectStore store, ImageInspector inspector, Supplier<UUID> uploadIds) {
		if (store == null || inspector == null || uploadIds == null) {
			throw new IllegalArgumentException("store, inspector, and uploadIds are required");
		}
		this.store = store;
		this.inspector = inspector;
		this.uploadIds = uploadIds;
	}

	public UploadCapability create(UserId userId, MediaDeclaration declaration) {
		return create(userId, declaration, UploadPurpose.GENERIC);
	}

	public UploadCapability create(UserId userId, MediaDeclaration declaration, UploadPurpose purpose) {
		if (userId == null || declaration == null || purpose == null) {
			throw new IllegalArgumentException("userId, declaration, and purpose are required");
		}
		try {
			ImageMediaPolicy.requireDeclaration(declaration.contentType(), declaration.contentLength());
		}
		catch (IllegalArgumentException ex) {
			String message = ex.getMessage();
			if (message != null && message.contains("unsupported")) {
				throw new ObjectStoreExceptions.UnsupportedMediaTypeException(message);
			}
			throw new ObjectStoreExceptions.InvalidUploadDeclarationException(
				message == null || message.isBlank() ? "invalid upload declaration" : message);
		}
		UploadId uploadId = new UploadId(uploadIds.get());
		return store.presignStagingPut(userId, uploadId, declaration, purpose);
	}

	public VerifiedUpload complete(UserId userId, UploadId uploadId) {
		if (userId == null || uploadId == null) {
			throw new IllegalArgumentException("userId and uploadId are required");
		}
		ObjectRef avatar = stagingRef(userId, uploadId, UploadPurpose.AVATAR);
		ObjectRef ref;
		ObjectMetadata metadata;
		try {
			metadata = store.head(avatar);
			ref = avatar;
		}
		catch (ObjectStoreExceptions.StagedObjectMissingException ignored) {
			ref = stagingRef(userId, uploadId, UploadPurpose.GENERIC);
			metadata = store.head(ref);
		}
		ImageMediaType declaredType;
		try {
			String normalized = ImageMediaPolicy.requireDeclaration(metadata.contentType(), metadata.contentLength());
			declaredType = "image/jpeg".equals(normalized) ? ImageMediaType.JPEG : ImageMediaType.PNG;
		}
		catch (IllegalArgumentException ex) {
			deleteQuietly(ref);
			throw new ObjectStoreExceptions.ImageValidationException(
				ex.getMessage() == null || ex.getMessage().isBlank() ? "invalid staged object" : ex.getMessage());
		}
		int maxBytes = boundedReadCap(metadata.contentLength());
		byte[] bytes = store.readBounded(ref, metadata.version(), maxBytes);
		ImageInspector.Result inspected;
		try {
			inspected = inspector.inspect(bytes);
			if (inspected.mediaType() != declaredType) {
				throw new ObjectStoreExceptions.ImageValidationException("image type does not match stored content type");
			}
		}
		catch (ObjectStoreExceptions.ImageValidationException ex) {
			deleteQuietly(ref);
			throw ex;
		}
		return new VerifiedUpload(
			uploadId,
			inspected.mediaType(),
			metadata.contentLength(),
			inspected.width(),
			inspected.height(),
			metadata.version());
	}

	public void abort(UserId userId, UploadId uploadId) {
		if (userId == null || uploadId == null) {
			throw new IllegalArgumentException("userId and uploadId are required");
		}
		try {
			store.delete(stagingRef(userId, uploadId, UploadPurpose.AVATAR));
		}
		catch (ObjectStoreExceptions.StagedObjectMissingException ignored) {
			try {
				store.delete(stagingRef(userId, uploadId, UploadPurpose.GENERIC));
			}
			catch (ObjectStoreExceptions.StagedObjectMissingException ignoredAgain) {
				// missing TEMP object is a successful abort
			}
		}
	}

	private static ObjectRef stagingRef(UserId userId, UploadId uploadId, UploadPurpose purpose) {
		return new ObjectRef(StorageDomain.TEMP, StorageKeys.stagingKey(userId, uploadId, purpose).value());
	}

	private static int boundedReadCap(long contentLength) {
		long cap = Math.min(contentLength, ImageMediaPolicy.MAX_BYTES);
		if (cap > Integer.MAX_VALUE) {
			return Integer.MAX_VALUE;
		}
		return (int) cap;
	}

	private void deleteQuietly(ObjectRef ref) {
		try {
			store.delete(ref);
		}
		catch (RuntimeException ignored) {
			// best-effort cleanup; caller rethrows the validation exception
		}
	}
}
