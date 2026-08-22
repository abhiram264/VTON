package com.aifashionvault.notification;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.NotificationId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NotificationTest {

	private static final NotificationId ID = new NotificationId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
	private static final UserId USER_ID = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final Instant CREATED_AT = Instant.parse("2026-08-22T00:00:00Z");

	@Test
	void safePayloadWithTypeMetadataAndLookIdConstructs() {
		Map<String, String> payload = Map.of("lookId", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

		Notification notification = new Notification(ID, USER_ID, "LOOK_READY", payload, null, CREATED_AT);

		assertThat(notification.id()).isEqualTo(ID);
		assertThat(notification.userId()).isEqualTo(USER_ID);
		assertThat(notification.type()).isEqualTo("LOOK_READY");
		assertThat(notification.payload()).containsExactlyEntriesOf(payload);
		assertThat(notification.readAt()).isNull();
		assertThat(notification.createdAt()).isEqualTo(CREATED_AT);
	}

	@Test
	void forbiddenPayloadKeysThrow() {
		assertThatThrownBy(() -> notification(Map.of("avatar_url", "users/u/avatars/a.jpg")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> notification(Map.of("signed_s3_url", "users/u/looks/l.jpg")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> notification(Map.of("image_url", "users/u/looks/l.jpg")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> notification(Map.of("base64_image", "/9j/4AAQ")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void httpAndHttpsUrlPayloadValuesThrow() {
		assertThatThrownBy(() -> notification(Map.of("lookId", "https://example.invalid/looks/l.jpg")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> notification(Map.of("lookId", "HTTPS://example.invalid/looks/l.jpg")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> notification(Map.of("lookId", "http://example.invalid/looks/l.jpg")))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void userIdIsRequired() {
		assertThatThrownBy(() -> new Notification(ID, null, "LOOK_READY", Map.of(), null, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void emptyPayloadIsAllowed() {
		assertThat(notification(Map.of()).payload()).isEmpty();
	}

	@Test
	void payloadIsStoredAsUnmodifiableCopy() {
		Map<String, String> payload = new HashMap<>();
		payload.put("lookId", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

		Notification notification = notification(payload);
		payload.put("extra", "nope");

		assertThat(notification.payload()).containsOnlyKeys("lookId");
		assertThatThrownBy(() -> notification.payload().put("image", "x"))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void typeMustBeNonBlank() {
		assertThatThrownBy(() -> new Notification(ID, USER_ID, null, Map.of(), null, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Notification(ID, USER_ID, "", Map.of(), null, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Notification(ID, USER_ID, "   ", Map.of(), null, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void payloadIsRequired() {
		assertThatThrownBy(() -> new Notification(ID, USER_ID, "LOOK_READY", null, null, CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void ownsUserIdOnly() {
		assertThat(Notification.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly("id", "userId", "type", "payload", "readAt", "createdAt");
	}

	private static Notification notification(Map<String, String> payload) {
		return new Notification(ID, USER_ID, "LOOK_READY", payload, null, CREATED_AT);
	}
}
