package com.aifashionvault.notification;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.TerminalReason;
import com.aifashionvault.shared.UserId;

/**
 * Durable terminal notification event driving push delivery (Q61/R12).
 *
 * <p>The trailing {@code reason} carries the optional coarse product-safe
 * terminal reason for failure events: a {@link TerminalReason} enum name or
 * absent. It never carries provider vocabulary, URLs, or media locators;
 * push payloads stay product-safe end to end.</p>
 */
public record NotificationEvent(
	UUID id,
	UserId userId,
	GenerationJobId generationId,
	String type,
	LookId lookId,
	Instant createdAt,
	Instant deadlineAt,
	String reason
) {

	public static final String LOOK_READY = "LOOK_READY";
	public static final String GENERATION_FAILED = "GENERATION_FAILED";

	private static final Set<String> TYPES = Set.of(LOOK_READY, GENERATION_FAILED);

	public NotificationEvent {
		if (id == null) {
			throw new IllegalArgumentException("id is required");
		}
		if (userId == null) {
			throw new IllegalArgumentException("userId is required");
		}
		if (generationId == null) {
			throw new IllegalArgumentException("generationId is required");
		}
		if (type == null || !TYPES.contains(type)) {
			throw new IllegalArgumentException("type must be LOOK_READY or GENERATION_FAILED");
		}
		if (createdAt == null) {
			throw new IllegalArgumentException("createdAt is required");
		}
		if (deadlineAt == null) {
			throw new IllegalArgumentException("deadlineAt is required");
		}
		if (LOOK_READY.equals(type) && lookId == null) {
			throw new IllegalArgumentException("lookId is required");
		}
		if (GENERATION_FAILED.equals(type) && lookId != null) {
			throw new IllegalArgumentException("GENERATION_FAILED must not include lookId");
		}
		if (reason != null) {
			if (LOOK_READY.equals(type)) {
				throw new IllegalArgumentException("LOOK_READY must not include reason");
			}
			TerminalReason.valueOf(reason);
		}
	}

	/**
	 * Compatibility constructor without a reason for pre-V017 callers and row
	 * mappings; the optional reason defaults to absent (Q61/R12).
	 */
	public NotificationEvent(
		UUID id,
		UserId userId,
		GenerationJobId generationId,
		String type,
		LookId lookId,
		Instant createdAt,
		Instant deadlineAt
	) {
		this(id, userId, generationId, type, lookId, createdAt, deadlineAt, null);
	}

	public static NotificationEvent lookReady(
		UUID id,
		UserId userId,
		GenerationJobId generationId,
		LookId lookId,
		Instant createdAt,
		Instant deadlineAt
	) {
		return new NotificationEvent(id, userId, generationId, LOOK_READY, lookId, createdAt, deadlineAt);
	}

	/** Failure event carrying only durable internal delivery metadata. */
	public static NotificationEvent generationFailed(
		UUID id,
		UserId userId,
		GenerationJobId generationId,
		String terminalReason,
		Instant createdAt,
		Instant deadlineAt
	) {
		return new NotificationEvent(id, userId, generationId, GENERATION_FAILED, null, createdAt, deadlineAt, terminalReason);
	}

	/** Compatibility failure factory without a reason. */
	public static NotificationEvent generationFailed(
		UUID id,
		UserId userId,
		GenerationJobId generationId,
		Instant createdAt,
		Instant deadlineAt
	) {
		return generationFailed(id, userId, generationId, null, createdAt, deadlineAt);
	}

	public Map<String, String> pushPayload() {
		Map<String, String> payload = new LinkedHashMap<>();
		payload.put("type", type);
		if (LOOK_READY.equals(type)) {
			payload.put("lookId", lookId.value().toString());
		}
		if (GENERATION_FAILED.equals(type)) {
			payload.put("generationId", generationId.value().toString());
		}
		for (Map.Entry<String, String> entry : payload.entrySet()) {
			if (isHttpUrl(entry.getValue())) {
				throw new IllegalStateException("payload must not contain an http(s) URL");
			}
		}
		return Collections.unmodifiableMap(payload);
	}

	private static boolean isHttpUrl(String value) {
		if (value == null) {
			return false;
		}
		String lower = value.toLowerCase(Locale.ROOT);
		return lower.startsWith("http://") || lower.startsWith("https://");
	}
}
