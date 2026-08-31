package com.aifashionvault.platform.objectstore;

import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;

public record ClaimCommand(
	UserId userId,
	UploadId uploadId,
	StorageKey destination,
	StorageDomain destinationBoundary,
	String claimMarker,
	String sourceVersion
) {

	public ClaimCommand {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (uploadId == null) {
			throw new IllegalArgumentException("uploadId is required");
		}
		if (destination == null) {
			throw new IllegalArgumentException("destination is required");
		}
		if (destinationBoundary == null) {
			throw new IllegalArgumentException("destinationBoundary is required");
		}
		if (claimMarker == null || claimMarker.isBlank()) {
			throw new IllegalArgumentException("claimMarker is required");
		}
		if (sourceVersion == null || sourceVersion.isBlank()) {
			throw new IllegalArgumentException("sourceVersion is required");
		}
	}
}
