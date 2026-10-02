package com.aifashionvault.notification;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.aifashionvault.shared.UserId;

/**
 * Owner-bound push device registration (C20). A device token identifies one
 * app installation; it is unique across owners so that signing into a second
 * account on the same device moves the token to that account instead of
 * leaving the first account able to receive its notifications.
 */
public record DeviceRegistration(
	UUID id,
	UserId userId,
	String deviceToken,
	String platform,
	Instant createdAt,
	Instant updatedAt,
	Instant lastSeenAt
) {

	public static final String ANDROID = "ANDROID";
	public static final String IOS = "IOS";

	private static final Set<String> PLATFORMS = Set.of(ANDROID, IOS);

	public DeviceRegistration {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (deviceToken == null || deviceToken.isBlank()) {
			throw new IllegalArgumentException("deviceToken is required");
		}
		if (platform == null || !PLATFORMS.contains(platform)) {
			throw new IllegalArgumentException("platform must be ANDROID or IOS");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
		if (updatedAt == null) {
			throw new IllegalArgumentException("updatedAt is required");
		}
		if (lastSeenAt == null) {
			throw new IllegalArgumentException("lastSeenAt is required");
		}
	}

	public static boolean isSupportedPlatform(String platform) {
		return platform != null && PLATFORMS.contains(platform);
	}
}
