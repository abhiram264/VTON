package com.aifashionvault.shared;

import java.util.UUID;

public record CollectionId(UUID value) {

	public CollectionId {
		if (value == null) {
			throw new IllegalArgumentException("value is required");
		}
	}
}
