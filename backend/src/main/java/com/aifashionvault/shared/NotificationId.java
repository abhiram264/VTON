package com.aifashionvault.shared;

import java.util.UUID;

public record NotificationId(UUID value) {

	public NotificationId {
		if (value == null) {
			throw new IllegalArgumentException("value is required");
		}
	}
}
