package com.aifashionvault.platform.importer;

import java.util.Objects;

import com.aifashionvault.importer.ImportMediaAccess;
import com.aifashionvault.importer.ResolutionReason;
import com.aifashionvault.importer.ResolutionResult;
import com.aifashionvault.importer.ResolutionStatus;
import com.aifashionvault.importer.SourceReference;
import com.aifashionvault.importer.SourceResolver;
import com.aifashionvault.shared.ShareImportId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.platform.objectstore.ObjectStoreExceptions;

/**
 * Terminal resolver for image-bearing imports: a verified upload is claimed to
 * an owner/import-derived private key and the import is READY. Any URL in the
 * source reference is retained only as source metadata; this resolver never
 * performs a URL network fetch.
 */
public final class SharedImageResolver implements SourceResolver {

	private final ImportMediaAccess mediaAccess;
	private final UserId ownerId;
	private final ShareImportId importId;

	public SharedImageResolver(ImportMediaAccess mediaAccess, UserId ownerId, ShareImportId importId) {
		this.mediaAccess = Objects.requireNonNull(mediaAccess, "mediaAccess");
		this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
		this.importId = Objects.requireNonNull(importId, "importId");
	}

	@Override
	public boolean supports(SourceReference source) {
		return source.verifiedUploadId() != null;
	}

	@Override
	public ResolutionResult resolve(SourceReference source) {
		if (!supports(source)) {
			return unsupported();
		}
		ImportMediaAccess.VerifiedImage verified = findVerified(source);
		if (verified == null) {
			return unsupported();
		}
		StorageKey destination = destinationKey(verified);
		ImportMediaAccess.ClaimOutcome outcome = claim(verified, destination);
		if (outcome == ImportMediaAccess.ClaimOutcome.CLAIMED || outcome == ImportMediaAccess.ClaimOutcome.REPLAYED) {
			return new ResolutionResult(
					ResolutionStatus.RESOLVED,
					destination,
					source.url(),
					null,
					null,
					null);
		}
		return unsupported();
	}

	private ImportMediaAccess.VerifiedImage findVerified(SourceReference source) {
		try {
			return mediaAccess.findVerifiedUpload(source.verifiedUploadId()).orElse(null);
		}
		catch (ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
			throw retryable();
		}
	}

	private ImportMediaAccess.ClaimOutcome claim(ImportMediaAccess.VerifiedImage verified, StorageKey destination) {
		try {
			return mediaAccess.claimVerifiedUpload(verified, destination);
		}
		catch (ObjectStoreExceptions.ObjectStoreUnavailableException ex) {
			throw retryable();
		}
	}

	private StorageKey destinationKey(ImportMediaAccess.VerifiedImage verified) {
		String extension = verified.mediaType().endsWith("png") ? "png" : "jpg";
		return new StorageKey("users/" + ownerId.value() + "/imports/" + importId.value() + "." + extension);
	}

	private static ResolutionResult unsupported() {
		return new ResolutionResult(ResolutionStatus.UNSUPPORTED, null, null, null, null, null);
	}

	private static ImporterRetryableException retryable() {
		return new ImporterRetryableException(ResolutionReason.DEPENDENCY_UNAVAILABLE,
				"object store unavailable");
	}
}
