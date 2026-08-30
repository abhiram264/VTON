package com.aifashionvault.platform.objectstore;

import java.time.Instant;

public record StoredObject(String key, Instant lastModified) {

	public StoredObject {
		if (key == null || key.isBlank()) {
			throw new IllegalArgumentException("key is required");
		}
	}
}
