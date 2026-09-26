package com.aifashionvault.notification;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.aifashionvault.shared.GenerationJobId;

public interface NotificationEventStore {

	boolean insert(NotificationEvent event);

	Optional<NotificationEvent> findById(UUID eventId);

	Optional<NotificationEvent> findByGenerationAndType(GenerationJobId generationId, String type);

	boolean ensureDelivery(UUID eventId, String deviceIdentity);

	Optional<DeviceDelivery> findDelivery(UUID eventId, String deviceIdentity);

	Optional<DueDelivery> claimDue(Instant now);

	void markAccepted(UUID deliveryId, UUID claimToken, long claimEpoch, int attemptCount, String providerMessageId, Instant now);

	void scheduleRetry(UUID deliveryId, UUID claimToken, long claimEpoch, int attemptCount, Instant nextAttemptAt);

	void markTerminal(UUID deliveryId, UUID claimToken, long claimEpoch, int attemptCount, String terminalResult, Instant now);

	record DueDelivery(NotificationEvent event, DeviceDelivery delivery, UUID claimToken, long claimEpoch) {

		public DueDelivery {
			if (event == null) {
				throw new IllegalArgumentException("event is required");
			}
			if (delivery == null) {
				throw new IllegalArgumentException("delivery is required");
			}
			if (claimToken == null) {
				throw new IllegalArgumentException("claimToken is required");
			}
			if (claimEpoch < 0) {
				throw new IllegalArgumentException("claimEpoch must be >= 0");
			}
		}
	}
}
