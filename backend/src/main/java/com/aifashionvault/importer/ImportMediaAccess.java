package com.aifashionvault.importer;

import java.io.InputStream;
import java.util.Objects;
import java.util.Optional;

import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;

/**
 * Application-owned media seam for the importer. Implemented by the platform
 * object-store adapter; domain code never touches storage SDK types.
 */
public interface ImportMediaAccess {

	Optional<VerifiedImage> findVerifiedUpload(UploadId uploadId);

	ClaimOutcome claimVerifiedUpload(VerifiedImage upload, StorageKey destination);

	/**
	 * Conditionally creates a private object from bounded fetched image bytes.
	 * The contentType is the inspected, normalized image type (image/png or
	 * image/jpeg) and becomes the stored object's content type.
	 * Returns false when an object already exists at the destination key.
	 */
	boolean publishFetchedImage(InputStream content, long contentLength, StorageKey destination, String contentType);

	/** Best-effort removal of one owned private object. */
	void deleteObject(StorageKey key);

	enum ClaimOutcome { CLAIMED, REPLAYED, CONFLICT }

	record VerifiedImage(UploadId id, String mediaType, long contentLength, String version) {

		public VerifiedImage {
			Objects.requireNonNull(id, "id");
			if (mediaType == null || mediaType.isBlank()) {
				throw new IllegalArgumentException("mediaType is required");
			}
			if (contentLength <= 0) {
				throw new IllegalArgumentException("contentLength must be positive");
			}
			if (version == null || version.isBlank()) {
				throw new IllegalArgumentException("version is required");
			}
		}
	}
}
