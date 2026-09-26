package com.aifashionvault.platform.objectstore;

import java.util.UUID;
import java.util.function.Supplier;

import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.StorageDomain;

public final class UploadWorkflow {

	private final ObjectStore store;
	private final ImageInspector inspector;
	private final MalwareScanner malwareScanner;
	private final Supplier<UUID> uploadIds;

	/**
	 * Test-only factory. The permissive scanner it installs must never be reachable from a
	 * production call site, so it is confined to this explicitly named entry point instead of
	 * a public constructor. Production wiring must pass a real {@link MalwareScanner}.
	 */
	public static UploadWorkflow forTesting(ObjectStore store, ImageInspector inspector) {
		return forTesting(store, inspector, UUID::randomUUID);
	}

	public static UploadWorkflow forTesting(ObjectStore store, ImageInspector inspector, Supplier<UUID> uploadIds) {
		return new UploadWorkflow(store, inspector, alwaysClean(), uploadIds);
	}

	private static MalwareScanner alwaysClean() {
		return (bytes, type) -> new MalwareScanResult(MalwareVerdict.CLEAN, "clean");
	}

	public UploadWorkflow(ObjectStore store, ImageInspector inspector, MalwareScanner malwareScanner) {
		this(store, inspector, malwareScanner, UUID::randomUUID);
	}

	public UploadWorkflow(
		ObjectStore store,
		ImageInspector inspector,
		MalwareScanner malwareScanner,
		Supplier<UUID> uploadIds
	) {
		if (store == null || inspector == null || malwareScanner == null || uploadIds == null) {
			throw new IllegalArgumentException("store, inspector, malwareScanner, and uploadIds are required");
		}
		this.store = store;
		this.inspector = inspector;
		this.malwareScanner = malwareScanner;
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
		String normalizedType;
		ImageMediaType declaredType;
		try {
			normalizedType = ImageMediaPolicy.requireDeclaration(metadata.contentType(), metadata.contentLength());
			declaredType = "image/jpeg".equals(normalizedType) ? ImageMediaType.JPEG : ImageMediaType.PNG;
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
			if (bytes.length != metadata.contentLength()) {
				throw new ObjectStoreExceptions.ImageValidationException("staged object length does not match metadata");
			}
			inspected = inspector.inspect(bytes);
			if (inspected.mediaType() != declaredType) {
				throw new ObjectStoreExceptions.ImageValidationException("image type does not match stored content type");
			}
			MalwareScanResult scan = malwareScanner.scan(bytes, normalizedType);
			if (scan == null || scan.verdict() != MalwareVerdict.CLEAN) {
				throw new ObjectStoreExceptions.ImageValidationException("image failed malware inspection");
			}
		}
		catch (ObjectStoreExceptions.ImageValidationException ex) {
			deleteQuietly(ref);
			throw ex;
		}
		catch (RuntimeException ex) {
			deleteQuietly(ref);
			throw new ObjectStoreExceptions.ImageValidationException("malware inspection unavailable");
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
		deleteStagingQuietly(stagingRef(userId, uploadId, UploadPurpose.AVATAR));
		deleteStagingQuietly(stagingRef(userId, uploadId, UploadPurpose.GENERIC));
	}

	private void deleteStagingQuietly(ObjectRef ref) {
		try {
			store.delete(ref);
		}
		catch (ObjectStoreExceptions.StagedObjectMissingException ignored) {
			// missing TEMP object is a successful abort
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
