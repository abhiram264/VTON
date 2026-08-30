package com.aifashionvault.platform.web;

public record AvatarUploadRequest(String uploadId) {

	public AvatarUploadRequest {
		if (uploadId == null || uploadId.isBlank()) {
			throw new IllegalArgumentException("uploadId is required");
		}
	}
}
