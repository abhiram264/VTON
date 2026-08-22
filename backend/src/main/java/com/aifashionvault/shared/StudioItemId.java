package com.aifashionvault.shared;

import java.util.UUID;

public record StudioItemId(UUID value) {

	public StudioItemId {
		if (value == null) {
			throw new IllegalArgumentException("value is required");
		}
	}
}
