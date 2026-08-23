package com.aifashionvault.platform.objectstore;

public record MediaDeclaration(String contentType, long contentLength) {

	public MediaDeclaration {
		if (contentType == null || contentType.isBlank()) {
			throw new IllegalArgumentException("contentType is required");
		}
	}
}
