package com.aifashionvault.platform.objectstore;

import java.net.URI;
import java.time.Instant;

public record PresignedAccess(ObjectRef object, String method, URI uri, Instant expiresAt) {

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
		if (expiresAt == null) {
			throw new IllegalArgumentException("expiry is required");
		}
	}
}
