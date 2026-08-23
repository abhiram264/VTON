package com.aifashionvault.platform.web;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public record CreateUploadResponse(
	String id,
	String method,
	String url,
	Map<String, String> headers,
	Instant expiresAt
) {

	public CreateUploadResponse {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("id is required");
		}
		if (method == null || method.isBlank()) {
			throw new IllegalArgumentException("method is required");
		}
		if (url == null || url.isBlank()) {
			throw new IllegalArgumentException("url is required");
		}
		if (headers == null) {
			throw new IllegalArgumentException("headers is required");
		}
		if (expiresAt == null) {
			throw new IllegalArgumentException("expiresAt is required");
		}
		headers = Collections.unmodifiableMap(new HashMap<>(headers));
	}

	@Override
	public String toString() {
		return "CreateUploadResponse[id=" + id
			+ ", method=" + method
			+ ", headers=" + headers
			+ ", expiresAt=" + expiresAt
			+ "]";
	}
}
