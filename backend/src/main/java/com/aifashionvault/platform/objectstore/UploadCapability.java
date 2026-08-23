package com.aifashionvault.platform.objectstore;

import java.net.URI;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.aifashionvault.shared.UploadId;

public record UploadCapability(
	UploadId id,
	String method,
	URI uri,
	Map<String, String> signedHeaders,
	Instant expiresAt
) {

	public UploadCapability {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
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
			throw new IllegalArgumentException("expiresAt is required");
		}
		signedHeaders = Collections.unmodifiableMap(new HashMap<>(signedHeaders));
	}

	@Override
	public String toString() {
		return "UploadCapability[id=" + id
			+ ", method=" + method
			+ ", signedHeaders=" + signedHeaders
			+ ", expiresAt=" + expiresAt
			+ "]";
	}
}
