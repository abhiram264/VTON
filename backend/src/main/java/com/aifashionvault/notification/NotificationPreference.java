package com.aifashionvault.notification;

import java.time.Instant;

import com.aifashionvault.shared.UserId;

/** Owner-scoped push preference (C20). Absent row means the default: push enabled. */
public record NotificationPreference(UserId userId, boolean pushEnabled, Instant updatedAt) {

	public NotificationPreference {
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (updatedAt == null) {
			throw new IllegalArgumentException("updatedAt is required");
		}
	}
}
