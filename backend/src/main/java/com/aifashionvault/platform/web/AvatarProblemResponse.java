package com.aifashionvault.platform.web;

import java.util.Set;

public record AvatarProblemResponse(String code) {

	private static final Set<String> CODES = Set.of(
		"AVATAR_UNREADABLE",
		"AVATAR_UNSUPPORTED_FORMAT",
		"AVATAR_TYPE_MISMATCH",
		"AVATAR_RESOLUTION_INSUFFICIENT",
		"AVATAR_BYTES_EXCEEDED",
		"AVATAR_DIMENSIONS_EXCEEDED",
		"AVATAR_PIXELS_EXCEEDED",
		"AVATAR_ASPECT_RATIO",
		"AVATAR_TOO_BLURRED",
		"AVATAR_TOO_DARK",
		"AVATAR_CONFLICT",
		"AVATAR_STALE_REVISION",
		"AVATAR_NOT_FOUND",
		"AVATAR_UPLOAD_NOT_CLAIMABLE",
		"AVATAR_MEDIA_MISSING",
		"AVATAR_PERSON_NOT_SINGLE",
		"AVATAR_FACE_NOT_VISIBLE",
		"AVATAR_BODY_NOT_VISIBLE",
		"AVATAR_PREFLIGHT_UNAVAILABLE");

	public AvatarProblemResponse {
		if (code == null || code.isBlank()) {
			throw new IllegalArgumentException("code is required");
		}
		if (!CODES.contains(code)) {
			throw new IllegalArgumentException("code is not supported");
		}
	}

	@Override
	public String toString() {
		return "AvatarProblemResponse[code=" + code + "]";
	}
}
