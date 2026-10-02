package com.aifashionvault.platform.web;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.databind.node.ObjectNode;

import com.aifashionvault.notification.DeviceRegistration;
import com.aifashionvault.notification.DeviceRegistrationStore;
import com.aifashionvault.notification.NotificationPreference;
import com.aifashionvault.shared.UserId;

/**
 * Authenticated, owner-bound push device registration (C20). Registration is an
 * idempotent upsert keyed on the device token; removal is owner-bound; the
 * token-addressed prune used for provider-reported unregistered tokens is not
 * exposed over HTTP.
 */
@RestController
@RequestMapping("/me")
public class DeviceRegistrationController {

	private static final int MAX_TOKEN_LENGTH = 4096;

	private final DeviceRegistrationStore store;
	private final Clock clock;

	public DeviceRegistrationController(
		ObjectProvider<DeviceRegistrationStore> stores,
		ObjectProvider<Clock> clocks
	) {
		this.store = stores.getIfAvailable();
		Clock configured = clocks.getIfAvailable();
		this.clock = configured == null ? Clock.systemUTC() : configured;
	}

	@PostMapping("/devices")
	public DeviceResponse register(@AuthenticationPrincipal UserId owner, @RequestBody ObjectNode body) {
		String deviceToken = requiredText(body, "deviceToken", MAX_TOKEN_LENGTH);
		String platform = requiredText(body, "platform", 16).toUpperCase(Locale.ROOT);
		if (!DeviceRegistration.isSupportedPlatform(platform)) {
			throw new Invalid();
		}
		return DeviceResponse.from(requiredStore().register(owner, deviceToken, platform, clock.instant()));
	}

	@GetMapping("/devices")
	public List<DeviceResponse> list(@AuthenticationPrincipal UserId owner) {
		return requiredStore().listByUser(owner).stream().map(DeviceResponse::from).toList();
	}

	/**
	 * Idempotent and non-disclosing: always 204. The token is deleted only when
	 * the caller owns it, so another account's delete leaves the row intact and
	 * the response never reveals whether the token belongs to someone else.
	 */
	@DeleteMapping("/devices")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@AuthenticationPrincipal UserId owner, @RequestBody ObjectNode body) {
		requiredStore().remove(owner, requiredText(body, "deviceToken", MAX_TOKEN_LENGTH));
	}

	@GetMapping("/notification-preferences")
	public PreferenceResponse preferences(@AuthenticationPrincipal UserId owner) {
		return requiredStore().findPreference(owner)
			.map(PreferenceResponse::from)
			.orElseGet(PreferenceResponse::defaultEnabled);
	}

	@PutMapping("/notification-preferences")
	public PreferenceResponse updatePreferences(
		@AuthenticationPrincipal UserId owner,
		@RequestBody ObjectNode body
	) {
		if (!body.has("pushEnabled") || !body.get("pushEnabled").isBoolean()) {
			throw new Invalid();
		}
		NotificationPreference updated = requiredStore().upsertPreference(
			owner, body.get("pushEnabled").booleanValue(), clock.instant());
		return PreferenceResponse.from(updated);
	}

	@ExceptionHandler({Invalid.class, org.springframework.http.converter.HttpMessageNotReadableException.class})
	ResponseEntity<Problem> invalid() {
		return ResponseEntity.status(HttpStatus.BAD_REQUEST)
			.contentType(MediaType.APPLICATION_JSON)
			.body(new Problem("INVALID_DEVICE_REGISTRATION"));
	}

	private DeviceRegistrationStore requiredStore() {
		if (store == null) {
			throw new IllegalStateException("device registration store unavailable");
		}
		return store;
	}

	private static String requiredText(ObjectNode body, String key, int maxLength) {
		if (!body.has(key) || !body.get(key).isString()) {
			throw new Invalid();
		}
		String value = body.get(key).stringValue().strip();
		if (value.isEmpty() || value.length() > maxLength) {
			throw new Invalid();
		}
		return value;
	}

	public record DeviceResponse(
		String id,
		String deviceToken,
		String platform,
		Instant createdAt,
		Instant updatedAt,
		Instant lastSeenAt
	) {
		static DeviceResponse from(DeviceRegistration registration) {
			return new DeviceResponse(
				registration.id().toString(),
				registration.deviceToken(),
				registration.platform(),
				registration.createdAt(),
				registration.updatedAt(),
				registration.lastSeenAt());
		}
	}

	public record PreferenceResponse(boolean pushEnabled, Instant updatedAt) {
		static PreferenceResponse from(NotificationPreference preference) {
			return new PreferenceResponse(preference.pushEnabled(), preference.updatedAt());
		}

		static PreferenceResponse defaultEnabled() {
			return new PreferenceResponse(true, null);
		}
	}

	record Problem(String code) {}

	private static final class Invalid extends RuntimeException {}
}
