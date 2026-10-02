package com.aifashionvault.notification;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.aifashionvault.shared.UserId;

/**
 * Owner-bound device-token registration and preference store (C20).
 *
 * <p>All owner-scoped operations take the caller's {@link UserId} and never
 * cross owners. {@link #prune(String)} is the one token-addressed delete: it is
 * used when the push provider reports a token as permanently unregistered, at
 * which point ownership no longer matters.</p>
 */
public interface DeviceRegistrationStore {

	/** Idempotent upsert by token: re-registering refreshes timestamps and moves the token to {@code userId}. */
	DeviceRegistration register(UserId userId, String deviceToken, String platform, Instant now);

	/** Owner-bound removal; returns true only when the caller owned the token. */
	boolean remove(UserId userId, String deviceToken);

	List<DeviceRegistration> listByUser(UserId userId);

	/** Deletes a token regardless of owner, for provider-reported unregistered tokens. */
	boolean prune(String deviceToken);

	/** Removes every registration for one owner (account deletion / sign-out sweep). */
	int removeAllForUser(UserId userId);

	NotificationPreference upsertPreference(UserId userId, boolean pushEnabled, Instant now);

	Optional<NotificationPreference> findPreference(UserId userId);
}
