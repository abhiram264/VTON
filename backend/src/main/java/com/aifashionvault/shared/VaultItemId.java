package com.aifashionvault.shared;

import java.util.UUID;

public record VaultItemId(UUID value) {

	public VaultItemId {
		if (value == null) {
			throw new IllegalArgumentException("value is required");
		}
	}
}
