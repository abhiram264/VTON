package com.aifashionvault.shared;

import java.util.Locale;

public record StorageKey(String value) {

	public StorageKey {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("storage key is required");
		}
		String stripped = value.strip();
		String lower = stripped.toLowerCase(Locale.ROOT);
		if (stripped.contains("://")) {
			throw new IllegalArgumentException("storage key must not be a URI");
		}
		if (lower.startsWith("data:")) {
			throw new IllegalArgumentException("storage key must not be a data URI");
		}
		if (lower.contains("x-amz-signature") || lower.contains("x-amz-algorithm")) {
			throw new IllegalArgumentException("storage key must not be a signed URL");
		}
		if (isBase64ImagePayload(stripped)) {
			throw new IllegalArgumentException("storage key must not be a base64 image payload");
		}
		value = stripped;
	}

	private static boolean isBase64ImagePayload(String candidate) {
		String compact = candidate.replace(" ", "").replace("\n", "").replace("\r", "").replace("\t", "");
		String lower = compact.toLowerCase(Locale.ROOT);
		return lower.startsWith("/9j/")
			|| lower.startsWith("ivborw0kggo")
			|| lower.startsWith("r0lgod")
			|| lower.startsWith("uklgr");
	}
}
