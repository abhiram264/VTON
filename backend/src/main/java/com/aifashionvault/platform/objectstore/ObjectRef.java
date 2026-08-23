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
		if (lower.startsWith("data:")) {
			throw new IllegalArgumentException("object key must not be a data URI");
		}
		if (lower.contains("x-amz-signature") || lower.contains("x-amz-algorithm")) {
			throw new IllegalArgumentException("object key must not be a signed URL");
		}
		if (isBase64ImagePayload(stripped)) {
			throw new IllegalArgumentException("object key must not be a base64 image payload");
		}
		objectKey = stripped;
	}

	private static boolean isBase64ImagePayload(String candidate) {
		String compact = candidate.replace(" ", "").replace("\n", "").replace("\r", "").replace("\t", "");
		String compactLower = compact.toLowerCase(Locale.ROOT);
		return compactLower.startsWith("/9j/")
			|| compactLower.startsWith("ivborw0kggo")
			|| compactLower.startsWith("r0lgod")
			|| compactLower.startsWith("uklgr");
	}
}
