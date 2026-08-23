package com.aifashionvault.shared;

import java.util.UUID;

public record UploadId(UUID value) {

	public UploadId {
		if (value == null) {
			throw new IllegalArgumentException("value is required");
		}
	}
}
