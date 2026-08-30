package com.aifashionvault.avatar;

import java.time.Instant;
import java.util.List;

import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UploadId;

public record AvatarImage(
	AvatarImageId id,
	AvatarProfileId profileId,
	StorageKey storageKey,
	Integer width,
	Integer height,
	String pose,
	String qualityStatus,
	Instant createdAt,
	UploadId sourceUploadId,
	AvatarImageLifecycle lifecycleState,
	List<String> warnings,
	String preflightPolicyVersion) {

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
		if (width != null && width <= 0) {
			throw new IllegalArgumentException("width must be positive");
		}
		if (height != null && height <= 0) {
			throw new IllegalArgumentException("height must be positive");
		}
		if (qualityStatus == null || qualityStatus.isBlank()) {
			throw new IllegalArgumentException("qualityStatus is required");
		}
		if (!"ACCEPTED".equals(qualityStatus)) {
			throw new IllegalArgumentException("qualityStatus must be ACCEPTED");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
		if (lifecycleState == null) {
			throw new IllegalArgumentException("lifecycleState is required");
		}
		if (warnings == null) {
			throw new IllegalArgumentException("warnings is required");
		}
		for (String warning : warnings) {
			if (warning == null) {
				throw new IllegalArgumentException("warning is required");
			}
		}
		if (preflightPolicyVersion != null && preflightPolicyVersion.isBlank()) {
			throw new IllegalArgumentException("preflightPolicyVersion must not be blank");
		}
		warnings = List.copyOf(warnings);
	}
}
