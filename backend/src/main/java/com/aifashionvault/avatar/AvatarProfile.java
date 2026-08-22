package com.aifashionvault.avatar;

import java.time.Instant;

import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.UserId;

public record AvatarProfile(
	AvatarProfileId id,
	UserId userId,
	AvatarImageId primaryImageId,
	String status,
	Instant createdAt,
	Instant updatedAt) {

	public AvatarProfile {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (status == null || status.isBlank()) {
			throw new IllegalArgumentException("status is required");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
		if (updatedAt == null) {
			throw new IllegalArgumentException("updatedAt is required");
		}
	}
}
