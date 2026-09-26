package com.aifashionvault.notification;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import com.aifashionvault.admission.AbuseDecision;
import com.aifashionvault.admission.AbuseDecisionReason;
import com.aifashionvault.admission.GlobalCostCircuitBreaker;
import com.aifashionvault.admission.ProviderExecutionBudgetRequest;
import com.aifashionvault.generation.GenerationAttempt;
import com.aifashionvault.generation.GenerationJob;
import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.generation.QualityResult;
import com.aifashionvault.generation.vton.InputTransferMethod;
import com.aifashionvault.generation.vton.OutputDeliveryMethod;
import com.aifashionvault.generation.vton.PrivacyTransferPolicy;
import com.aifashionvault.generation.vton.ProviderJobId;
import com.aifashionvault.look.Look;
import com.aifashionvault.look.LookStore;
import com.aifashionvault.platform.generation.AuthorizedGenerationMediaResolver;
import com.aifashionvault.platform.generation.GenerationWorkProcessor;
import com.aifashionvault.platform.generation.PrivacyApprovedInputTransfer;
import com.aifashionvault.platform.generation.RecordingVtonProvider;
import com.aifashionvault.platform.objectstore.GenerationMediaStore;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.queue.GenerationWorkLease;
import com.aifashionvault.platform.queue.GenerationWorkQueue;
import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.NotificationId;
import com.aifashionvault.shared.TerminalReason;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class NotificationTest {

	private static final NotificationId ID = new NotificationId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
	private static final UserId USER_ID = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final Instant CREATED_AT = Instant.parse("2026-08-22T00:00:00Z");
	private static final Instant NOW = CREATED_AT;
	private static final LookId LOOK_ID = new LookId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
	private static final GenerationJobId GENERATION_JOB_ID = new GenerationJobId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));
	private static final GenerationJobId JOB_ID = GENERATION_JOB_ID;
	private static final AvatarImageId AVATAR_IMAGE_ID = new AvatarImageId(UUID.fromString("22222222-2222-2222-2222-222222222222"));

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
	void lookReadyProducesTypeAndLookIdOnly() {
		Notification notification = Notification.lookReady(ID, USER_ID, LOOK_ID, CREATED_AT);

		assertThat(notification.id()).isEqualTo(ID);
		assertThat(notification.userId()).isEqualTo(USER_ID);
		assertThat(notification.type()).isEqualTo("LOOK_READY");
		assertThat(notification.payload()).hasSize(1).containsEntry("lookId", LOOK_ID.value().toString());
		assertThat(notification.readAt()).isNull();
		assertThat(notification.createdAt()).isEqualTo(CREATED_AT);
	}

	@Test
	void generationFailedProducesGenerationIdOnlyWhenReasonAbsent() {
		Notification notification = Notification.generationFailed(ID, USER_ID, GENERATION_JOB_ID, CREATED_AT);

		assertThat(notification.id()).isEqualTo(ID);
		assertThat(notification.userId()).isEqualTo(USER_ID);
		assertThat(notification.type()).isEqualTo("GENERATION_FAILED");
		assertThat(notification.payload())
			.containsOnlyKeys("generationId")
			.containsEntry("generationId", GENERATION_JOB_ID.value().toString());
		assertThat(notification.readAt()).isNull();
		assertThat(notification.createdAt()).isEqualTo(CREATED_AT);
	}

    @Test
    void generationFailedProducesGenerationIdOnlyEvenWhenInternalReasonPresent() {
        Notification notification = Notification.generationFailed(
            ID, USER_ID, GENERATION_JOB_ID, TerminalReason.QUALITY_REJECTED.name(), CREATED_AT);
        assertThat(notification.type()).isEqualTo("GENERATION_FAILED");
        assertThat(notification.payload())
            .containsOnlyKeys("generationId")
            .containsEntry("generationId", GENERATION_JOB_ID.value().toString());
    }

	@Test
	void generationFailedOmitsReasonWhenBlank() {
		Notification notification = Notification.generationFailed(ID, USER_ID, GENERATION_JOB_ID, "   ", CREATED_AT);
		assertThat(notification.payload()).containsOnlyKeys("generationId");
	}

	@Test
	void generationFailedRequiresGenerationIdKey() {
		assertThatThrownBy(() -> new Notification(
			ID,
			USER_ID,
			"GENERATION_FAILED",
			Map.of("reason", TerminalReason.QUALITY_REJECTED.name()),
			null,
			CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Notification(
			ID,
			USER_ID,
			"GENERATION_FAILED",
			Map.of(),
			null,
			CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void notificationEventFailurePayloadCarriesTypeAndGenerationIdOnly() {
		NotificationEvent withReason = NotificationEvent.generationFailed(
			UUID.randomUUID(), USER_ID, GENERATION_JOB_ID,
			TerminalReason.PROVIDER_UNAVAILABLE.name(), CREATED_AT, CREATED_AT.plusSeconds(86400L));
		assertThat(withReason.pushPayload()).containsExactlyInAnyOrderEntriesOf(
			Map.of("type", "GENERATION_FAILED", "generationId", GENERATION_JOB_ID.value().toString()));
		NotificationEvent ready = NotificationEvent.lookReady(
			UUID.randomUUID(), USER_ID, GENERATION_JOB_ID, LOOK_ID, CREATED_AT, CREATED_AT.plusSeconds(86400L));
		assertThat(ready.pushPayload()).containsExactlyInAnyOrderEntriesOf(
			Map.of("type", "LOOK_READY", "lookId", LOOK_ID.value().toString()));
	}

	@Test
	void notificationEventRejectsForeignReasonVocabulary() {
		assertThatThrownBy(() -> NotificationEvent.generationFailed(
			UUID.randomUUID(),
			USER_ID,
			GENERATION_JOB_ID,
			"rate_limit_429",
			CREATED_AT,
			CREATED_AT.plusSeconds(86400L)))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new NotificationEvent(
			UUID.randomUUID(),
			USER_ID,
			GENERATION_JOB_ID,
			"LOOK_READY",
			LOOK_ID,
			CREATED_AT,
			CREATED_AT.plusSeconds(86400L),
			TerminalReason.QUALITY_REJECTED.name()))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void extraKeysOnLookReadyAndGenerationFailedThrow() {
		assertThatThrownBy(() -> new Notification(
			ID,
			USER_ID,
			"LOOK_READY",
			Map.of("lookId", LOOK_ID.value().toString(), "extra", "nope"),
			null,
			CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Notification(
			ID,
			USER_ID,
			"GENERATION_FAILED",
			Map.of("generationId", GENERATION_JOB_ID.value().toString(), "extra", "nope"),
			null,
			CREATED_AT))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void providerJobIdAndObjectLocatorKeysThrow() {
		assertThatThrownBy(() -> notification(Map.of("providerJobId", "remote-42")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> notification(Map.of("storageKey", "users/u/looks/l.jpg")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> notification(Map.of("objectKey", "users/u/looks/l.jpg")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> notification(Map.of("signedUrl", "users/u/looks/l.jpg")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> notification(Map.of("imageUrl", "users/u/looks/l.jpg")))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> notification(Map.of("provider_job_id", "remote-42")))
			.isInstanceOf(IllegalArgumentException.class);
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
		assertThat(new Notification(ID, USER_ID, "INFO", Map.of(), null, CREATED_AT).payload()).isEmpty();
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

    @Test
    void processorFailureNotificationCarriesGenerationIdOnly() {
        DeadlineScriptedGenerationStore generationStore = new DeadlineScriptedGenerationStore();
        generationStore.insertJob(queuedJob());
        generationStore.expireInto(failedJob(TerminalReason.QUALITY_REJECTED.name(), null));
        CapturingNotificationStore notifications = new CapturingNotificationStore();
        CapturingNotificationEventStore events = new CapturingNotificationEventStore();
        DeletingQueue queue = new DeletingQueue();
        processorFor(generationStore, notifications, events, queue)
            .process(new GenerationWorkLease(JOB_ID.value(), "receipt-1"));
        assertThat(notifications.inserted()).hasSize(1);
        assertThat(notifications.inserted().getFirst().payload())
            .containsOnlyKeys("generationId")
            .containsEntry("generationId", JOB_ID.value().toString());
        assertThat(events.inserted().getFirst().pushPayload())
            .containsExactlyInAnyOrderEntriesOf(Map.of("type", "GENERATION_FAILED", "generationId", JOB_ID.value().toString()));
        assertThat(queue.deleted()).hasSize(1);
    }

	@Test
	void processorFailureNotificationOmitsReasonForLegacyTerminalReason() {
		DeadlineScriptedGenerationStore generationStore = new DeadlineScriptedGenerationStore();
		generationStore.insertJob(queuedJob());
		generationStore.expireInto(failedJob("INTERNAL_ORCHESTRATION_STALLED", null));
		CapturingNotificationStore notifications = new CapturingNotificationStore();
		CapturingNotificationEventStore events = new CapturingNotificationEventStore();
		DeletingQueue queue = new DeletingQueue();

		processorFor(generationStore, notifications, events, queue)
			.process(new GenerationWorkLease(JOB_ID.value(), "receipt-1"));

		assertThat(notifications.inserted()).hasSize(1);
		Notification failed = notifications.inserted().getFirst();
		assertThat(failed.payload())
			.containsOnlyKeys("generationId")
			.containsEntry("generationId", JOB_ID.value().toString());
		assertThat(events.inserted()).hasSize(1);
		assertThat(events.inserted().getFirst().reason()).isNull();
		assertThat(queue.deleted()).hasSize(1);
	}

	@Test
	void processorSuppressesFailureNotificationAfterSubjectDeletion() {
		DeadlineScriptedGenerationStore generationStore = new DeadlineScriptedGenerationStore();
		generationStore.insertJob(queuedJob());
		generationStore.expireInto(failedJob(GenerationJob.SUBJECT_DELETED, NOW));
		CapturingNotificationStore notifications = new CapturingNotificationStore();

		CapturingNotificationEventStore events = new CapturingNotificationEventStore();
		DeletingQueue queue = new DeletingQueue();
		processorFor(generationStore, notifications, events, queue)
			.process(new GenerationWorkLease(JOB_ID.value(), "receipt-1"));
		assertThat(notifications.inserted()).isEmpty();
		assertThat(events.inserted()).isEmpty();
		assertThat(queue.deleted()).hasSize(1);
	}
	private static GenerationWorkProcessor processorFor(
		GenerationStore generationStore,
		CapturingNotificationStore notifications,
		CapturingNotificationEventStore events,
		DeletingQueue queue
	) {
		ObjectStore objectStore = mock(ObjectStore.class);
		return new GenerationWorkProcessor(
			generationStore,
			emptyLookStore(),
			notifications,
			queue,
			new RecordingVtonProvider(),
			mock(AuthorizedGenerationMediaResolver.class),
			new PrivacyApprovedInputTransfer(transferPolicy(), objectStore),
			(attemptId, image) -> {
				throw new UnsupportedOperationException("quality gate is unused in these tests");
			},
			mock(GenerationMediaStore.class),
			objectStore,
			Clock.fixed(NOW, ZoneOffset.UTC),
			null,
			null,
			null,
			null,
			null,
			events,
			() -> 0.0d,
			noCostCircuitBreaker(),
			provider -> true);
	}

	private static LookStore emptyLookStore() {
		return new LookStore() {
			@Override
			public Look insert(Look look) {
				return look;
			}

			@Override
			public Optional<Look> findById(LookId id) {
				return Optional.empty();
			}

			@Override
			public Optional<Look> findByGenerationJobId(GenerationJobId generationJobId) {
				return Optional.empty();
			}
		};
	}

	private static GlobalCostCircuitBreaker noCostCircuitBreaker() {
		return new GlobalCostCircuitBreaker() {
			@Override
			public AbuseDecision authorize(ProviderExecutionBudgetRequest request) {
				return new AbuseDecision(
					true,
					AbuseDecisionReason.ALLOWED,
					BigDecimal.ZERO,
					BigDecimal.ONE,
					request.currency(),
					request.now().minus(Duration.ofDays(1)),
					request.now().plus(Duration.ofDays(1)));
			}
		};
	}

	private static PrivacyTransferPolicy transferPolicy() {
		return new PrivacyTransferPolicy(
			Set.of(InputTransferMethod.BOUNDED_INLINE_DATA, InputTransferMethod.EPHEMERAL_SIGNED_READ),
			Set.of(OutputDeliveryMethod.BOUNDED_INLINE_DATA),
			1_000_000L,
			Duration.ofMinutes(5),
			false);
	}

	private static GenerationJob queuedJob() {
		return new GenerationJob(
			JOB_ID,
			USER_ID,
			null,
			GenerationJob.QUEUED,
			"try-on",
			null,
			NOW,
			null,
			AVATAR_IMAGE_ID,
			List.of(),
			null,
			null,
			null,
			null,
			0,
			NOW,
			null,
			1,
			1,
			1,
			NOW.plus(GenerationJob.ORCHESTRATION_DEADLINE),
			null,
			null);
	}

	private static GenerationJob failedJob(String terminalReason, Instant subjectDeletedAt) {
		return new GenerationJob(
			JOB_ID,
			USER_ID,
			null,
			GenerationJob.FAILED,
			"try-on",
			null,
			NOW,
			NOW,
			AVATAR_IMAGE_ID,
			List.of(),
			null,
			subjectDeletedAt,
			null,
			null,
			1,
			NOW,
			terminalReason,
			1,
			1,
			1,
			NOW.plus(GenerationJob.ORCHESTRATION_DEADLINE),
			null,
			null);
	}

	private static final class DeadlineScriptedGenerationStore implements GenerationStore {

		private volatile GenerationJob current;
		private volatile GenerationJob terminalSwap;

		void expireInto(GenerationJob swapped) {
			this.terminalSwap = swapped;
		}

		@Override
		public void insertJob(GenerationJob job) {
			this.current = job;
		}

		@Override
		public Optional<GenerationJob> findJob(GenerationJobId jobId) {
			return Optional.ofNullable(current);
		}

		@Override
		public boolean failIfDeadlineExceeded(GenerationJobId jobId, Instant now) {
			current = terminalSwap;
			return true;
		}

		@Override
		public void insertAttempt(GenerationAttempt attempt) {
		}

		@Override
		public Optional<GenerationAttempt> findAttempt(GenerationAttemptId attemptId) {
			return Optional.empty();
		}

		@Override
		public List<GenerationAttempt> listAttempts(GenerationJobId jobId) {
			return List.of();
		}

		@Override
		public boolean tryAcquireSubmitOwnership(GenerationAttemptId attemptId, Instant ownedAt) {
			return false;
		}

		@Override
		public void recordProviderExecution(GenerationAttemptId attemptId, ProviderJobId providerJobId) {
		}

		@Override
		public void markSubmissionIndeterminate(GenerationAttemptId attemptId) {
		}

		@Override
		public void saveQualityResult(QualityResult qualityResult) {
		}

		@Override
		public void markCancelled(GenerationJobId jobId, Instant cancelledAt) {
		}

		@Override
		public void markSubjectDeleted(GenerationJobId jobId, Instant subjectDeletedAt) {
		}

		@Override
		public void completeAttempt(GenerationAttempt attempt) {
		}

		@Override
		public void markJobCompleted(GenerationJobId jobId, Instant completedAt) {
		}
	}

	private static final class CapturingNotificationStore implements NotificationStore {

		private final List<Notification> inserted = new CopyOnWriteArrayList<>();

		List<Notification> inserted() {
			return inserted;
		}

		@Override
		public void insert(Notification notification) {
			inserted.add(notification);
		}

		@Override
		public Optional<Notification> findById(NotificationId id) {
			return inserted.stream().filter(notification -> notification.id().equals(id)).findFirst();
		}
	}

	private static final class CapturingNotificationEventStore implements NotificationEventStore {

		private final List<NotificationEvent> inserted = new CopyOnWriteArrayList<>();

		List<NotificationEvent> inserted() {
			return inserted;
		}

		@Override
		public boolean insert(NotificationEvent event) {
			inserted.add(event);
			return true;
		}

		@Override
		public Optional<NotificationEvent> findById(UUID eventId) {
			return Optional.empty();
		}

		@Override
		public Optional<NotificationEvent> findByGenerationAndType(GenerationJobId generationId, String type) {
			return Optional.empty();
		}
		@Override
		public boolean ensureDelivery(UUID eventId, String deviceIdentity) {
			throw new UnsupportedOperationException("ensureDelivery is unused in these tests");
		}

		@Override
		public Optional<DeviceDelivery> findDelivery(UUID eventId, String deviceIdentity) {
			throw new UnsupportedOperationException("findDelivery is unused in these tests");
		}

		@Override
		public Optional<DueDelivery> claimDue(Instant now) {
			throw new UnsupportedOperationException("claimDue is unused in these tests");
		}

		@Override
		public void markAccepted(UUID deliveryId, UUID claimToken, long claimEpoch, int attemptCount, String providerMessageId, Instant now) {
			throw new UnsupportedOperationException("markAccepted is unused in these tests");
		}

		@Override
		public void scheduleRetry(UUID deliveryId, UUID claimToken, long claimEpoch, int attemptCount, Instant nextAttemptAt) {
			throw new UnsupportedOperationException("scheduleRetry is unused in these tests");
		}

		@Override
		public void markTerminal(UUID deliveryId, UUID claimToken, long claimEpoch, int attemptCount, String terminalResult, Instant now) {
			throw new UnsupportedOperationException("markTerminal is unused in these tests");
		}
	}

	private static final class DeletingQueue implements GenerationWorkQueue {

		private final List<GenerationWorkLease> deleted = new CopyOnWriteArrayList<>();

		List<GenerationWorkLease> deleted() {
			return deleted;
		}

		@Override
		public void send(UUID generationJobId) {
		}

		@Override
		public Optional<GenerationWorkLease> receive() {
			return Optional.empty();
		}

		@Override
		public void extendVisibility(GenerationWorkLease lease, Duration extension) {
		}

		@Override
		public void delete(GenerationWorkLease lease) {
			deleted.add(lease);
		}
	}

	private static Notification notification(Map<String, String> payload) {
		return new Notification(ID, USER_ID, "INFO", payload, null, CREATED_AT);
	}

}
