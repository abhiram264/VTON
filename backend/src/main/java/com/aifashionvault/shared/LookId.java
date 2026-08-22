package com.aifashionvault.shared;

import java.util.UUID;

public record LookId(UUID value) {

	public LookId {
		if (value == null) {
			throw new IllegalArgumentException("value is required");
		}
	}
}
