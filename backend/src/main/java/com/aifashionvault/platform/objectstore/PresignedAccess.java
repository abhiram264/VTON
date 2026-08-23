package com.aifashionvault.platform.objectstore;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public record PresignedAccess(
	ObjectRef object,
	String method,
	URI uri,
	Map<String, String> signedHeaders,
	Instant expiresAt
) {

	public PresignedAccess {
		if (object == null) {
			throw new IllegalArgumentException("object is required");
		}
		if (method == null || method.isBlank()) {
			throw new IllegalArgumentException("method is required");
		}
		if (uri == null) {
			throw new IllegalArgumentException("uri is required");
		}
		if (signedHeaders == null) {
			throw new IllegalArgumentException("signedHeaders is required");
		}
		if (expiresAt == null) {
			throw new IllegalArgumentException("expiry is required");
		}
		signedHeaders = Collections.unmodifiableMap(new HashMap<>(signedHeaders));
	}

	@Override
	public String toString() {
		return "PresignedAccess[object=" + object
			+ ", method=" + method
			+ ", signedHeaders=" + signedHeaders
			+ ", expiresAt=" + expiresAt
			+ "]";
	}
}
