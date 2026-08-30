package com.aifashionvault.platform.web;

import java.time.Instant;
import java.util.List;

public record AvatarResourceResponse(
	String id,
	String imageId,
	int revision,
	int width,
	int height,
	String mediaType,
	String displayUrl,
	Instant displayExpiresAt,
	List<String> warnings
) {

	public AvatarResourceResponse {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("id is required");
		}
		if (imageId == null || imageId.isBlank()) {
			throw new IllegalArgumentException("imageId is required");
		}
		if (revision < 1) {
			throw new IllegalArgumentException("revision must be >= 1");
		}
		if (width <= 0) {
			throw new IllegalArgumentException("width must be positive");
		}
		if (height <= 0) {
			throw new IllegalArgumentException("height must be positive");
		}
		if (!"image/jpeg".equals(mediaType) && !"image/png".equals(mediaType)) {
			throw new IllegalArgumentException("mediaType must be image/jpeg or image/png");
		}
		if (displayUrl == null || displayUrl.isBlank()) {
			throw new IllegalArgumentException("displayUrl is required");
		}
		if (displayExpiresAt == null) {
			throw new IllegalArgumentException("displayExpiresAt is required");
		}
		if (warnings == null) {
			throw new IllegalArgumentException("warnings is required");
		}
		warnings = List.copyOf(warnings);
	}

	@Override
	public String toString() {
		return "AvatarResourceResponse[id=" + id
			+ ", imageId=" + imageId
			+ ", revision=" + revision
			+ ", width=" + width
			+ ", height=" + height
			+ ", mediaType=" + mediaType
			+ ", displayExpiresAt=" + displayExpiresAt
			+ ", warnings=" + warnings
			+ "]";
	}
}
