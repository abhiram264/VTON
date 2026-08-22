package com.aifashionvault.shared;

import java.util.UUID;

public record AvatarProfileId(UUID value) {

	public AvatarProfileId {
		if (value == null) {
			throw new IllegalArgumentException("value is required");
		}
	}
}
