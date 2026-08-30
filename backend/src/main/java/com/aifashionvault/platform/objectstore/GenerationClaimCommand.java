package com.aifashionvault.platform.objectstore;

import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

public record GenerationClaimCommand(
	UserId userId,
	StorageKey sourceTempKey,
	StorageKey destinationLookKey,
	String sourceVersion,
	String claimMarker
) {

	public GenerationClaimCommand {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (sourceTempKey == null) {
			throw new IllegalArgumentException("sourceTempKey is required");
		}
		if (destinationLookKey == null) {
			throw new IllegalArgumentException("destinationLookKey is required");
		}
		if (sourceVersion == null || sourceVersion.isBlank()) {
			throw new IllegalArgumentException("sourceVersion is required");
		}
		if (claimMarker == null || claimMarker.isBlank()) {
			throw new IllegalArgumentException("claimMarker is required");
		}
		if (!sourceTempKey.value().startsWith("temp/")) {
			throw new IllegalArgumentException("sourceTempKey must be a TEMP key");
		}
		if (!destinationLookKey.value().contains("/looks/")) {
			throw new IllegalArgumentException("destinationLookKey must be a LOOK key");
		}
	}
}
