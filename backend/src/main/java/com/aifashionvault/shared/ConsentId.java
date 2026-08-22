package com.aifashionvault.shared;

import java.util.UUID;

public record ConsentId(UUID value) {

	public ConsentId {
		if (value == null) {
			throw new IllegalArgumentException("value is required");
		}
	}
}
