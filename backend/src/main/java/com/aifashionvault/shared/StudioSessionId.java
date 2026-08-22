package com.aifashionvault.shared;

import java.util.UUID;

public record StudioSessionId(UUID value) {

	public StudioSessionId {
		if (value == null) {
			throw new IllegalArgumentException("value is required");
		}
	}
}
