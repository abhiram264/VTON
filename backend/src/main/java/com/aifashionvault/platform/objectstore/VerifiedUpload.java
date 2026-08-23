package com.aifashionvault.platform.objectstore;

import com.aifashionvault.shared.UploadId;

public record VerifiedUpload(
	UploadId id,
	ImageMediaType mediaType,
	long contentLength,
	int width,
	int height,
	String version
) {

	public VerifiedUpload {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (mediaType == null) {
			throw new IllegalArgumentException("mediaType is required");
		}
		if (contentLength <= 0) {
			throw new IllegalArgumentException("contentLength must be positive");
		}
		if (width <= 0) {
			throw new IllegalArgumentException("width must be positive");
		}
		if (height <= 0) {
			throw new IllegalArgumentException("height must be positive");
		}
		if (version == null || version.isBlank()) {
			throw new IllegalArgumentException("version is required");
		}
	}
}
