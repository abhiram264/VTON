package com.aifashionvault.platform.web;

import java.io.InputStream;
import java.util.Optional;

import com.aifashionvault.importer.ImportMediaAccess;
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
}
