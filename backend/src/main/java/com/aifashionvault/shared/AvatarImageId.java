package com.aifashionvault.shared;

import java.util.UUID;

public record AvatarImageId(UUID value) {

	public AvatarImageId {
		if (value == null) {
			throw new IllegalArgumentException("value is required");
		}
	}
}
