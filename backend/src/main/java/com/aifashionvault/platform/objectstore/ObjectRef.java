package com.aifashionvault.platform.objectstore;

import java.util.Locale;

public record ObjectRef(StorageBoundary boundary, String objectKey) {

	public ObjectRef {
		if (boundary == null) {
			throw new IllegalArgumentException("storage boundary is required");
		}
		if (objectKey == null || objectKey.isBlank()) {
			throw new IllegalArgumentException("object key is required");
		}
		String stripped = objectKey.strip();
		String lower = stripped.toLowerCase(Locale.ROOT);
		if (lower.startsWith("http://") || lower.startsWith("https://")) {
			throw new IllegalArgumentException("object key must not be a URL");
		}
		objectKey = stripped;
	}
}
