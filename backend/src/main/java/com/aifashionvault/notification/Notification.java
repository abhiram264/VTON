package com.aifashionvault.notification;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.aifashionvault.shared.NotificationId;
import com.aifashionvault.shared.UserId;

public record Notification(
	NotificationId id,
	UserId userId,
	String type,
	Map<String, String> payload,
	Instant readAt,
	Instant createdAt
) {

	private static final Set<String> FORBIDDEN_KEYS = Set.of(
		"avatar_url",
		"signed_s3_url",
		"image_url",
		"base64_image"
	);

	public Notification {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (type == null || type.isBlank()) {
			throw new IllegalArgumentException("type is required");
		}
		if (payload == null) {
			throw new IllegalArgumentException("payload is required");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
		for (Map.Entry<String, String> entry : payload.entrySet()) {
			if (FORBIDDEN_KEYS.contains(entry.getKey())) {
				throw new IllegalArgumentException("payload must not contain " + entry.getKey());
			}
			if (isHttpUrl(entry.getValue())) {
				throw new IllegalArgumentException("payload must not contain an http(s) URL");
			}
		}
		payload = Collections.unmodifiableMap(new HashMap<>(payload));
	}

	private static boolean isHttpUrl(String value) {
		if (value == null) {
			return false;
		}
		String lower = value.toLowerCase(Locale.ROOT);
		return lower.startsWith("http://") || lower.startsWith("https://");
	}
}
