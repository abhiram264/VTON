package com.aifashionvault.platform.web;

import java.io.InputStream;
import java.util.Optional;

import com.aifashionvault.importer.ImportMediaAccess;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

/**
 * Owner-scoped media seam for the share-import API. Production wraps the
 * platform object-store adapter's OWNER-SCOPED overloads
 * (findVerifiedUpload/claimVerifiedUpload); the unscoped port methods are
 * never called.
 */
public interface ShareImportMediaAccess {

	Optional<ImportMediaAccess.VerifiedImage> findVerifiedUpload(UserId ownerId, UploadId uploadId);

	ImportMediaAccess.ClaimOutcome claimVerifiedUpload(
		UserId ownerId,
		ImportMediaAccess.VerifiedImage upload,
		StorageKey destination);

	boolean publishFetchedImage(UserId ownerId, InputStream content, long contentLength, StorageKey destination);

	void deleteObject(StorageKey key);

	/**
	 * Short-lived, owner-scoped read capability for a resolved import object so
	 * the client can preview the imported image before saving it. The returned
	 * capability is a time-boxed signed read (never an object key or a
	 * permanent URL) and is re-issuable on demand. A seam that cannot mint one
	 * returns empty rather than fabricating access, so the API answers
	 * unavailable instead of leaking a preview it cannot authorize.
	 */
	default Optional<PresignedAccess> presignPreview(UserId ownerId, StorageKey key) {
		return Optional.empty();
	}
}
