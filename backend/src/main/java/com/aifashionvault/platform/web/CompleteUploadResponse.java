package com.aifashionvault.platform.web;

public record CompleteUploadResponse(
	String id,
	String status,
	String mediaType,
	long contentLength,
	int width,
	int height
) {

	public CompleteUploadResponse {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("id is required");
		}
		if (status == null || status.isBlank()) {
			throw new IllegalArgumentException("status is required");
		}
		if (!"READY".equals(status)) {
			throw new IllegalArgumentException("status must be READY");
		}
		if (mediaType == null || mediaType.isBlank()) {
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
	}
}
