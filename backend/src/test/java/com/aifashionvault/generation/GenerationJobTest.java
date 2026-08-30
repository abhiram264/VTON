package com.aifashionvault.generation;

import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.ClientRequestId;
import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;
import com.aifashionvault.shared.MediaAssetId;
import com.aifashionvault.shared.UploadId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationJobTest {

	private static final GenerationJobId JOB_ID = new GenerationJobId(
		UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
	private static final GenerationJobId OTHER_JOB_ID = new GenerationJobId(
		UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
	private static final UserId USER_ID = new UserId(
		UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final StudioSessionId SESSION_ID = new StudioSessionId(
		UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final Instant CREATED_AT = Instant.parse("2026-08-22T00:00:00Z");
	private static final Instant COMPLETED_AT = Instant.parse("2026-08-22T00:01:00Z");
	private static final ClientRequestId CLIENT_REQUEST_ID = new ClientRequestId("req-123");

	@Test
	void singleItemContractShapesAreFrozen() {
		assertThat(GenerationStatus.values()).extracting(Enum::name).containsExactly(
			"QUEUED", "PREFLIGHT", "SUBMITTED", "GENERATING", "QUALITY_CHECK", "RETRYING", "COMPLETED", "FAILED");
		assertThat(CreateSingleItemGenerationCommand.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly("ownerId", "productUploadId", "idempotencyKey");
		assertThat(FrozenSingleItemIntent.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly("generationId", "ownerId", "avatarImageId", "productMediaId", "renderStepOrdinal", "renderStepCount");
		assertThatThrownBy(() -> new FrozenSingleItemIntent(
			JOB_ID, USER_ID, new AvatarImageId(UUID.randomUUID()), new MediaAssetId(UUID.randomUUID()), 0, 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new CreateSingleItemGenerationCommand(USER_ID, new UploadId(UUID.randomUUID()), " "))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void recordShapeMatchesGenerationJobTable() {
		assertThat(GenerationJob.class.getRecordComponents())
			.extracting(RecordComponent::getName)
			.containsExactly(
				"id",
				"userId",
				"studioSessionId",
				"status",
				"requestedMode",
				"clientRequestId",
				"createdAt",
				"completedAt",
				"avatarImageId",
				"productVaultItemIds",
				"cancelledAt",
				"subjectDeletedAt",
				"requestFingerprint",
				"avatarRevision",
				"statusVersion",
				"updatedAt",
				"terminalReason",
				"currentStep",
				"totalSteps",
				"renderPlanVersion",
				"orchestrationDeadlineAt",
				"retryNotBeforeAt",
				"retryPolicySnapshot",
				"retryOfJobId");
		assertThat(GenerationJob.class.getRecordComponents())
			.extracting(RecordComponent::getType)
			.containsExactly(
				GenerationJobId.class,
				UserId.class,
				StudioSessionId.class,
				String.class,
				String.class,
				ClientRequestId.class,
				Instant.class,
				Instant.class,
				AvatarImageId.class,
				List.class,
				Instant.class,
				Instant.class,
				String.class,
				Integer.class,
				int.class,
				Instant.class,
				String.class,
				int.class,
				int.class,
				int.class,
				Instant.class,
				Instant.class,
				RetryPolicySnapshot.class,
				java.util.Optional.class);
	}

	@Test
	void constructorsPreserveLegacyNullRetryState() {
		GenerationJob convenience = new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "QUEUED", "try-on", CLIENT_REQUEST_ID, CREATED_AT, null, null, List.of(), null, null);
		GenerationJob full = new GenerationJob(
			JOB_ID,
			USER_ID,
			SESSION_ID,
			"COMPLETED",
			"try-on",
			CLIENT_REQUEST_ID,
			CREATED_AT,
			COMPLETED_AT,
			null,
			List.of(),
			null,
			null,
			null,
			null,
			0,
			CREATED_AT,
			null,
			1,
			1,
			1,
			CREATED_AT.plus(GenerationJob.ORCHESTRATION_DEADLINE));

		assertThat(convenience.retryNotBeforeAt()).isNull();
		assertThat(convenience.retryPolicySnapshot()).isNull();
		assertThat(full.retryNotBeforeAt()).isNull();
		assertThat(full.retryPolicySnapshot()).isNull();
		assertThat(convenience.retryOfJobId()).isEmpty();
		assertThat(full.retryOfJobId()).isEmpty();
	}

	@Test
	void retryOfJobIdDefaultsToEmptyAndRoundTripsLineage() {
		GenerationJob plain = new GenerationJob(
			JOB_ID,
			USER_ID,
			SESSION_ID,
			"QUEUED",
			"try-on",
			CLIENT_REQUEST_ID,
			CREATED_AT,
			null,
			null,
			List.of(),
			null,
			null,
			null,
			null,
			0,
			CREATED_AT,
			null,
			1,
			1,
			1,
			CREATED_AT.plus(GenerationJob.ORCHESTRATION_DEADLINE),
			null,
			RetryPolicySnapshot.forStepCount(1),
			null);
		assertThat(plain.retryOfJobId()).isEmpty();

		GenerationJob lineage = new GenerationJob(
			JOB_ID,
			USER_ID,
			SESSION_ID,
			"QUEUED",
			"try-on",
			CLIENT_REQUEST_ID,
			CREATED_AT,
			null,
			null,
			List.of(),
			null,
			null,
			null,
			null,
			0,
			CREATED_AT,
			null,
			1,
			1,
			1,
			CREATED_AT.plus(GenerationJob.ORCHESTRATION_DEADLINE),
			null,
			RetryPolicySnapshot.forStepCount(1),
			java.util.Optional.of(OTHER_JOB_ID));
		assertThat(lineage.retryOfJobId()).contains(OTHER_JOB_ID);
	}

	@Test
	void preLineageCompatibilityConstructorKeepsEmptyLineage() {
		GenerationJob legacy = new GenerationJob(
			JOB_ID,
			USER_ID,
			SESSION_ID,
			"QUEUED",
			"try-on",
			CLIENT_REQUEST_ID,
			CREATED_AT,
			null,
			null,
			List.of(),
			null,
			null,
			null,
			null,
			0,
			CREATED_AT,
			null,
			1,
			1,
			1,
			CREATED_AT.plus(GenerationJob.ORCHESTRATION_DEADLINE),
			null,
			RetryPolicySnapshot.forStepCount(1));
		assertThat(legacy.retryOfJobId()).isEmpty();
	}

	@Test
	void retryingJobCanPersistFutureRetryStateAndRemainsActive() {
		Instant retryNotBeforeAt = CREATED_AT.plusSeconds(10);
		RetryPolicySnapshot policy = RetryPolicySnapshot.forStepCount(1);

		GenerationJob job = new GenerationJob(
			JOB_ID,
			USER_ID,
			SESSION_ID,
			GenerationJob.RETRYING,
			"try-on",
			CLIENT_REQUEST_ID,
			CREATED_AT,
			null,
			null,
			List.of(),
			null,
			null,
			null,
			null,
			0,
			CREATED_AT,
			null,
			1,
			1,
			1,
			CREATED_AT.plus(GenerationJob.ORCHESTRATION_DEADLINE),
			retryNotBeforeAt,
			policy);

		assertThat(job.active()).isTrue();
		assertThat(job.retryNotBeforeAt()).isEqualTo(retryNotBeforeAt);
		assertThat(job.retryPolicySnapshot()).isEqualTo(policy);
	}
	@Test
	void retryNotBeforeAtMustBeStrictlyBeforeOrchestrationDeadline() {
		Instant orchestrationDeadlineAt = CREATED_AT.plus(GenerationJob.ORCHESTRATION_DEADLINE);
		RetryPolicySnapshot policy = RetryPolicySnapshot.forStepCount(1);

		assertThatThrownBy(() -> retryingJob(
			1, orchestrationDeadlineAt, orchestrationDeadlineAt, policy))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> retryingJob(
			1, orchestrationDeadlineAt, orchestrationDeadlineAt.plusNanos(1), policy))
			.isInstanceOf(IllegalArgumentException.class);

		Instant retryNotBeforeAt = CREATED_AT.plusSeconds(10);
		GenerationJob accepted = retryingJob(1, orchestrationDeadlineAt, retryNotBeforeAt, policy);
		assertThat(accepted.retryNotBeforeAt()).isEqualTo(retryNotBeforeAt);
	}

	@Test
	void retryPolicySnapshotMustAllowOneBaselineAttemptPerPlannedStep() {
		int totalSteps = 2;
		Instant orchestrationDeadlineAt = CREATED_AT.plus(GenerationJob.ORCHESTRATION_DEADLINE);
		Instant retryNotBeforeAt = CREATED_AT.plusSeconds(10);

		RetryPolicySnapshot belowBaseline = policyWithMaxPaidAttempts(totalSteps - 1, 0);
		assertThatThrownBy(() -> retryingJob(
			totalSteps, orchestrationDeadlineAt, retryNotBeforeAt, belowBaseline))
			.isInstanceOf(IllegalArgumentException.class);

		RetryPolicySnapshot exactBaseline = policyWithMaxPaidAttempts(totalSteps, 0);
		assertThat(retryingJob(totalSteps, orchestrationDeadlineAt, retryNotBeforeAt, exactBaseline)
			.retryPolicySnapshot().maxPaidAttempts())
			.isEqualTo(totalSteps);

		RetryPolicySnapshot maximum = policyWithMaxPaidAttempts(
			totalSteps + RetryPolicySnapshot.ADDITIONAL_LOGICAL_ATTEMPTS,
			RetryPolicySnapshot.ADDITIONAL_LOGICAL_ATTEMPTS);
		assertThat(retryingJob(totalSteps, orchestrationDeadlineAt, retryNotBeforeAt, maximum)
			.retryPolicySnapshot().maxPaidAttempts())
			.isEqualTo(totalSteps + RetryPolicySnapshot.ADDITIONAL_LOGICAL_ATTEMPTS);
	}

	private static GenerationJob retryingJob(
		int totalSteps,
		Instant orchestrationDeadlineAt,
		Instant retryNotBeforeAt,
		RetryPolicySnapshot retryPolicySnapshot) {
		return new GenerationJob(
			JOB_ID,
			USER_ID,
			SESSION_ID,
			GenerationJob.RETRYING,
			"try-on",
			CLIENT_REQUEST_ID,
			CREATED_AT,
			null,
			null,
			List.of(),
			null,
			null,
			null,
			null,
			0,
			CREATED_AT,
			null,
			1,
			totalSteps,
			1,
			orchestrationDeadlineAt,
			retryNotBeforeAt,
			retryPolicySnapshot);
	}

	private static RetryPolicySnapshot policyWithMaxPaidAttempts(
		int maxPaidAttempts,
		int additionalLogicalAttempts) {
		return new RetryPolicySnapshot(
			maxPaidAttempts,
			additionalLogicalAttempts,
			RetryPolicySnapshot.MAX_QUALITY_RETRIES_PER_STEP,
			RetryPolicySnapshot.MAX_REPLAY_SAFE_RETRANSMITS,
			RetryPolicySnapshot.MAX_TRANSMISSIONS_PER_ATTEMPT,
			RetryPolicySnapshot.BACKOFF_BASE,
			RetryPolicySnapshot.COMPUTED_BACKOFF_CAP,
			RetryPolicySnapshot.MAX_AUTONOMOUS_WAIT,
			GenerationJob.ORCHESTRATION_DEADLINE,
			true,
			false,
			true,
			RetryPolicySnapshot.DEFAULT_PROVIDER_TIMING_POLICY_ID);
	}


	@Test
	void retryNotBeforeAtRequiresRetryingAndPolicyCannotRaiseAttemptCap() {
		Instant retryNotBeforeAt = CREATED_AT.plusSeconds(10);
		RetryPolicySnapshot policy = RetryPolicySnapshot.forStepCount(1);

		assertThatThrownBy(() -> new GenerationJob(
			JOB_ID,
			USER_ID,
			SESSION_ID,
			GenerationJob.QUEUED,
			"try-on",
			CLIENT_REQUEST_ID,
			CREATED_AT,
			null,
			null,
			List.of(),
			null,
			null,
			null,
			null,
			0,
			CREATED_AT,
			null,
			1,
			1,
			1,
			CREATED_AT.plus(GenerationJob.ORCHESTRATION_DEADLINE),
			retryNotBeforeAt,
			policy))
			.isInstanceOf(IllegalArgumentException.class);

		RetryPolicySnapshot capRaisingPolicy = new RetryPolicySnapshot(
			policy.maxPaidAttempts() + 1,
			policy.additionalLogicalAttempts(),
			policy.maxQualityRetriesPerStep(),
			policy.maxReplaySafeRetransmits(),
			policy.maxTransmissionsPerAttempt(),
			policy.backoffBase(),
			policy.computedBackoffCap(),
			policy.maxAutonomousWait(),
			policy.orchestrationDeadline(),
			policy.technicalRetryEnabled(),
			policy.fallbackEnabled(),
			policy.qualityRegenerationEnabled(),
			policy.providerTimingPolicyId());

		assertThatThrownBy(() -> new GenerationJob(
			JOB_ID,
			USER_ID,
			SESSION_ID,
			GenerationJob.RETRYING,
			"try-on",
			CLIENT_REQUEST_ID,
			CREATED_AT,
			null,
			null,
			List.of(),
			null,
			null,
			null,
			null,
			0,
			CREATED_AT,
			null,
			1,
			1,
			1,
			CREATED_AT.plus(GenerationJob.ORCHESTRATION_DEADLINE),
			retryNotBeforeAt,
			capRaisingPolicy))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void requiredFieldsRejectNullAndBlankStatus() {
		assertThatThrownBy(() -> new GenerationJob(
			null, USER_ID, SESSION_ID, "QUEUED", "try-on", CLIENT_REQUEST_ID, CREATED_AT, null, null, List.of(), null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationJob(
			JOB_ID, null, SESSION_ID, "QUEUED", "try-on", CLIENT_REQUEST_ID, CREATED_AT, null, null, List.of(), null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, null, "try-on", CLIENT_REQUEST_ID, CREATED_AT, null, null, List.of(), null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "", "try-on", CLIENT_REQUEST_ID, CREATED_AT, null, null, List.of(), null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "   ", "try-on", CLIENT_REQUEST_ID, CREATED_AT, null, null, List.of(), null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "QUEUED", "try-on", CLIENT_REQUEST_ID, null, null, null, List.of(), null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "QUEUED", "try-on", CLIENT_REQUEST_ID, CREATED_AT, null, null, null, null, null))
			.isInstanceOf(IllegalArgumentException.class);
		List<VaultItemId> withNullElement = new ArrayList<>();
		withNullElement.add(null);
		assertThatThrownBy(() -> new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "QUEUED", "try-on", CLIENT_REQUEST_ID, CREATED_AT, null, null, withNullElement, null, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void optionalFieldsMayBeNull() {
		List<VaultItemId> products = new ArrayList<>();
		GenerationJob job = new GenerationJob(JOB_ID, USER_ID, null, "QUEUED", null, null, CREATED_AT, null, null, products, null, null);

		assertThat(job.id()).isEqualTo(JOB_ID);
		assertThat(job.userId()).isEqualTo(USER_ID);
		assertThat(job.studioSessionId()).isNull();
		assertThat(job.status()).isEqualTo("QUEUED");
		assertThat(job.requestedMode()).isNull();
		assertThat(job.clientRequestId()).isNull();
		assertThat(job.createdAt()).isEqualTo(CREATED_AT);
		assertThat(job.completedAt()).isNull();
		assertThat(job.avatarImageId()).isNull();
		assertThat(job.productVaultItemIds()).isEmpty();
		assertThat(job.cancelledAt()).isNull();
		assertThat(job.subjectDeletedAt()).isNull();
		products.add(new VaultItemId(UUID.fromString("33333333-3333-3333-3333-333333333333")));
		assertThat(job.productVaultItemIds()).isEmpty();
	}

	@Test
	void rememberIntentWithSameClientRequestIdReturnsFirstJob() {
		Map<ClientRequestId, GenerationJob> intents = new HashMap<>();
		GenerationJob first = job(JOB_ID, CLIENT_REQUEST_ID);
		GenerationJob second = new GenerationJob(
			OTHER_JOB_ID, USER_ID, SESSION_ID, "COMPLETED", "outfit", CLIENT_REQUEST_ID, CREATED_AT, COMPLETED_AT, null, List.of(), null, null);

		GenerationJob rememberedFirst = GenerationJob.rememberIntent(intents, first);
		GenerationJob rememberedSecond = GenerationJob.rememberIntent(intents, second);

		assertThat(rememberedFirst).isSameAs(first);
		assertThat(rememberedSecond).isSameAs(first);
		assertThat(rememberedSecond.id()).isEqualTo(first.id());
		assertThat(intents).hasSize(1);
		assertThat(intents.get(CLIENT_REQUEST_ID)).isSameAs(first);
	}

	@Test
	void differentClientRequestIdsCreateDifferentJobs() {
		Map<ClientRequestId, GenerationJob> intents = new HashMap<>();
		ClientRequestId otherRequestId = new ClientRequestId("req-456");
		GenerationJob first = job(JOB_ID, CLIENT_REQUEST_ID);
		GenerationJob second = job(OTHER_JOB_ID, otherRequestId);

		GenerationJob rememberedFirst = GenerationJob.rememberIntent(intents, first);
		GenerationJob rememberedSecond = GenerationJob.rememberIntent(intents, second);

		assertThat(rememberedFirst).isSameAs(first);
		assertThat(rememberedSecond).isSameAs(second);
		assertThat(rememberedFirst.id()).isNotEqualTo(rememberedSecond.id());
		assertThat(intents).containsExactlyInAnyOrderEntriesOf(Map.of(
			CLIENT_REQUEST_ID, first,
			otherRequestId, second));
	}

	@Test
	void nullClientRequestIdDoesNotCollide() {
		Map<ClientRequestId, GenerationJob> intents = new HashMap<>();
		GenerationJob keyed = job(JOB_ID, CLIENT_REQUEST_ID);
		GenerationJob firstNull = job(OTHER_JOB_ID, null);
		GenerationJob secondNull = job(new GenerationJobId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")), null);

		GenerationJob rememberedKeyed = GenerationJob.rememberIntent(intents, keyed);
		GenerationJob rememberedFirstNull = GenerationJob.rememberIntent(intents, firstNull);
		GenerationJob rememberedSecondNull = GenerationJob.rememberIntent(intents, secondNull);

		assertThat(rememberedKeyed).isSameAs(keyed);
		assertThat(rememberedFirstNull).isSameAs(firstNull);
		assertThat(rememberedSecondNull).isSameAs(secondNull);
		assertThat(rememberedFirstNull.id()).isNotEqualTo(rememberedSecondNull.id());
		assertThat(intents).containsExactlyInAnyOrderEntriesOf(Map.of(CLIENT_REQUEST_ID, keyed));
	}

	@Test
	void redeliveryOfSameGenerationJobIdIsSameJobTruth() {
		GenerationJob firstDelivery = job(JOB_ID, CLIENT_REQUEST_ID);
		GenerationJob redelivery = job(firstDelivery.id(), CLIENT_REQUEST_ID);

		assertThat(redelivery.id()).isEqualTo(firstDelivery.id());
		assertThat(redelivery.id()).isEqualTo(JOB_ID);
		assertThat(redelivery).isEqualTo(firstDelivery);
	}

	@Test
	void cancelledAtAndSubjectDeletedAtMayBeSetIndependentlyWithoutMediaTypes() {
		Instant cancelledAt = Instant.parse("2026-08-22T00:02:00Z");
		Instant subjectDeletedAt = Instant.parse("2026-08-22T00:03:00Z");
		AvatarImageId avatarImageId = new AvatarImageId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
		VaultItemId vaultItemId = new VaultItemId(UUID.fromString("55555555-5555-5555-5555-555555555555"));

		GenerationJob cancelledOnly = new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "FAILED", "try-on", CLIENT_REQUEST_ID, CREATED_AT, cancelledAt, avatarImageId, List.of(vaultItemId), cancelledAt, null);
		GenerationJob deletedOnly = new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "FAILED", "try-on", CLIENT_REQUEST_ID, CREATED_AT, subjectDeletedAt, avatarImageId, List.of(vaultItemId), null, subjectDeletedAt);
		GenerationJob both = new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "FAILED", "try-on", CLIENT_REQUEST_ID, CREATED_AT, cancelledAt, avatarImageId, List.of(vaultItemId), cancelledAt, subjectDeletedAt);

		assertThat(cancelledOnly.cancelledAt()).isEqualTo(cancelledAt);
		assertThat(cancelledOnly.subjectDeletedAt()).isNull();
		assertThat(cancelledOnly.status()).isEqualTo("FAILED");
		assertThat(cancelledOnly.terminalReason()).isEqualTo(GenerationJob.CANCELLED_BY_USER);
		assertThat(cancelledOnly.completedAt()).isEqualTo(cancelledAt);
		assertThat(deletedOnly.cancelledAt()).isNull();
		assertThat(deletedOnly.subjectDeletedAt()).isEqualTo(subjectDeletedAt);
		assertThat(deletedOnly.terminalReason()).isEqualTo(GenerationJob.SUBJECT_DELETED);
		assertThat(both.cancelledAt()).isEqualTo(cancelledAt);
		assertThat(both.subjectDeletedAt()).isEqualTo(subjectDeletedAt);
		assertThat(both.avatarImageId()).isEqualTo(avatarImageId);
		assertThat(both.productVaultItemIds()).containsExactly(vaultItemId);

		for (RecordComponent component : GenerationJob.class.getRecordComponents()) {
			assertThat(component.getType())
				.isNotEqualTo(byte[].class)
				.isNotEqualTo(URL.class)
				.isNotEqualTo(URI.class);
			String name = component.getName().toLowerCase(Locale.ROOT);
			assertThat(name).doesNotContain("url").doesNotContain("base64");
		}
	}

	@Test
	void rememberIntentIsSafeForConcurrentCallersOnConcurrentMap() throws Exception {
		for (int i = 0; i < 100; i++) {
			ConcurrentMap<ClientRequestId, GenerationJob> intents = new ConcurrentHashMap<>();
			ClientRequestId requestId = new ClientRequestId("req-concurrent");
			GenerationJob firstJob = job(new GenerationJobId(UUID.randomUUID()), requestId);
			GenerationJob secondJob = job(new GenerationJobId(UUID.randomUUID()), requestId);
			CyclicBarrier barrier = new CyclicBarrier(2);
			try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
				Future<GenerationJob> first = executor.submit(() -> rememberAtBarrier(intents, firstJob, barrier));
				Future<GenerationJob> second = executor.submit(() -> rememberAtBarrier(intents, secondJob, barrier));
				GenerationJob rememberedFirst = first.get();
				GenerationJob rememberedSecond = second.get();

				assertThat(rememberedFirst.id()).isEqualTo(rememberedSecond.id());
				assertThat(intents).hasSize(1);
				assertThat(intents.get(requestId).id()).isEqualTo(rememberedFirst.id());
				assertThat(rememberedFirst.id()).isIn(firstJob.id(), secondJob.id());
			}
		}
	}

	@Test
	void rejectsIllegalLifecycleNames() {
		assertThatThrownBy(() -> new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "CANCELLED", "try-on", CLIENT_REQUEST_ID, CREATED_AT, null, null, List.of(), null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "SUCCEEDED", "try-on", CLIENT_REQUEST_ID, CREATED_AT, COMPLETED_AT, null, List.of(), null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "RUNNING", "try-on", CLIENT_REQUEST_ID, CREATED_AT, null, null, List.of(), null, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void eightLifecycleNamesAreAccepted() {
		assertThat(job(JOB_ID, CLIENT_REQUEST_ID).status()).isEqualTo("QUEUED");
		assertThat(new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "PREFLIGHT", "try-on", CLIENT_REQUEST_ID, CREATED_AT, null, null, List.of(), null, null).status())
			.isEqualTo("PREFLIGHT");
		assertThat(new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "SUBMITTED", "try-on", CLIENT_REQUEST_ID, CREATED_AT, null, null, List.of(), null, null).status())
			.isEqualTo("SUBMITTED");
		assertThat(new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "GENERATING", "try-on", CLIENT_REQUEST_ID, CREATED_AT, null, null, List.of(), null, null).status())
			.isEqualTo("GENERATING");
		assertThat(new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "QUALITY_CHECK", "try-on", CLIENT_REQUEST_ID, CREATED_AT, null, null, List.of(), null, null).status())
			.isEqualTo("QUALITY_CHECK");
		assertThat(new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "RETRYING", "try-on", CLIENT_REQUEST_ID, CREATED_AT, null, null, List.of(), null, null).status())
			.isEqualTo("RETRYING");
		assertThat(new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "COMPLETED", "try-on", CLIENT_REQUEST_ID, CREATED_AT, COMPLETED_AT, null, List.of(), null, null).completedAt())
			.isEqualTo(COMPLETED_AT);
		assertThat(new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "FAILED", "try-on", CLIENT_REQUEST_ID, CREATED_AT, COMPLETED_AT, null, List.of(), Instant.parse("2026-08-22T00:02:00Z"), null)
			.terminalReason())
			.isEqualTo(GenerationJob.CANCELLED_BY_USER);
	}

	@Test
	void activeStatusRejectsCompletedAtAndTerminalRequiresIt() {
		assertThatThrownBy(() -> new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "QUEUED", "try-on", CLIENT_REQUEST_ID, CREATED_AT, COMPLETED_AT, null, List.of(), null, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationJob(
			JOB_ID,
			USER_ID,
			SESSION_ID,
			"COMPLETED",
			"try-on",
			CLIENT_REQUEST_ID,
			CREATED_AT,
			null,
			null,
			List.of(),
			null,
			null,
			null,
			null,
			0,
			CREATED_AT,
			null,
			1,
			1,
			1,
			CREATED_AT.plusSeconds(1800)))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private static GenerationJob rememberAtBarrier(
		Map<ClientRequestId, GenerationJob> intents,
		GenerationJob job,
		CyclicBarrier barrier) throws Exception {
		barrier.await();
		return GenerationJob.rememberIntent(intents, job);
	}

	private static GenerationJob job(GenerationJobId id, ClientRequestId clientRequestId) {
		return new GenerationJob(id, USER_ID, SESSION_ID, "QUEUED", "try-on", clientRequestId, CREATED_AT, null, null, List.of(), null, null);
	}
}
