package com.aifashionvault.shared;

import java.util.UUID;

public record IdentityMappingId(UUID value) {

	public IdentityMappingId {
		if (value == null) {
			throw new IllegalArgumentException("value is required");
		}
	}
}
