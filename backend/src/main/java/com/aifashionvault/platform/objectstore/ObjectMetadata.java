package com.aifashionvault.platform.objectstore;

public record ObjectMetadata(long contentLength, String contentType, String version) {

	public ObjectMetadata {
		if (contentType == null || contentType.isBlank()) {
			throw new IllegalArgumentException("contentType is required");
		}
		if (version == null || version.isBlank()) {
			throw new IllegalArgumentException("version is required");
		}
	}
}
