package com.aifashionvault.avatar;

import java.time.Instant;

import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.StorageKey;

public record AvatarImage(
	AvatarImageId id,
	AvatarProfileId profileId,
	StorageKey storageKey,
	Integer width,
	Integer height,
	String pose,
	String qualityStatus,
	Instant createdAt) {

	public AvatarImage {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (profileId == null) {
			throw new IllegalArgumentException("profileId is required");
		}
		if (storageKey == null) {
			throw new IllegalArgumentException("storageKey is required");
		}
		if (qualityStatus == null || qualityStatus.isBlank()) {
			throw new IllegalArgumentException("qualityStatus is required");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
	}
}
