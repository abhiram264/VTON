package com.aifashionvault.notification;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface NotificationEventStore {

	boolean insert(NotificationEvent event);

	Optional<NotificationEvent> findById(UUID eventId);

	boolean ensureDelivery(UUID eventId, String deviceIdentity);

	Optional<DeviceDelivery> findDelivery(UUID eventId, String deviceIdentity);

	Optional<DueDelivery> claimDue(Instant now);

	void markAccepted(UUID deliveryId, int attemptCount, String providerMessageId, Instant now);

	void scheduleRetry(UUID deliveryId, int attemptCount, Instant nextAttemptAt);

	void markTerminal(UUID deliveryId, int attemptCount, String terminalResult, Instant now);

	record DueDelivery(NotificationEvent event, DeviceDelivery delivery) {

		public DueDelivery {
			if (event == null) {
				throw new IllegalArgumentException("event is required");
			}
			if (delivery == null) {
				throw new IllegalArgumentException("delivery is required");
			}
		}
	}
}
