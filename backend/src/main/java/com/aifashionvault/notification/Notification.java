package com.aifashionvault.notification;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.NotificationId;
import com.aifashionvault.shared.TerminalReason;
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
		"base64_image",
		"providerJobId",
		"provider_job_id",
		"storageKey",
		"storage_key",
		"objectKey",
		"object_key",
		"signedUrl",
		"signed_url",
		"imageUrl"
	);

	private static final Set<String> LOOK_READY_KEYS = Set.of("lookId");
	private static final Set<String> GENERATION_FAILED_KEYS = Set.of("generationId");
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
		if ("LOOK_READY".equals(type) && !payload.keySet().equals(LOOK_READY_KEYS)) {
			throw new IllegalArgumentException("LOOK_READY payload must contain only lookId");
		}
		if ("GENERATION_FAILED".equals(type)
			&& (!GENERATION_FAILED_KEYS.containsAll(payload.keySet()) || !payload.containsKey("generationId"))) {
			throw new IllegalArgumentException("GENERATION_FAILED payload must contain only generationId");
		}
		payload = Collections.unmodifiableMap(new HashMap<>(payload));
	}

	public static Notification lookReady(NotificationId id, UserId userId, LookId lookId, Instant createdAt) {
		if (lookId == null) {
			throw new IllegalArgumentException("lookId is required");
		}
		return new Notification(id, userId, "LOOK_READY", Map.of("lookId", lookId.value().toString()), null, createdAt);
	}

	/** Terminal failure notification; terminal reason remains internal and is never exposed in payload. */
	public static Notification generationFailed(
		NotificationId id,
		UserId userId,
		GenerationJobId generationJobId,
		String terminalReason,
		Instant createdAt
	) {
		if (generationJobId == null) {
			throw new IllegalArgumentException("generationJobId is required");
		}
		if (terminalReason != null && !terminalReason.isBlank()) {
			TerminalReason.valueOf(terminalReason);
		}
		return new Notification(id, userId, "GENERATION_FAILED", Map.of("generationId", generationJobId.value().toString()), null, createdAt);
	}

	/**
	 * Compatibility factory without a reason; delegates with a null reason so
	 * the payload still carries the opaque {@code generationId} (Q61/R12).
	 */
	public static Notification generationFailed(NotificationId id, UserId userId, GenerationJobId generationJobId, Instant createdAt) {
		return generationFailed(id, userId, generationJobId, null, createdAt);
	}

	private static boolean isHttpUrl(String value) {
		if (value == null) {
			return false;
		}
		String lower = value.toLowerCase(Locale.ROOT);
		return lower.startsWith("http://") || lower.startsWith("https://");
	}
}
