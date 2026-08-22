package com.aifashionvault.generation;

import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import com.aifashionvault.shared.ClientRequestId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.UserId;

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
				"completedAt");
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
				Instant.class);
	}

	@Test
	void requiredFieldsRejectNullAndBlankStatus() {
		assertThatThrownBy(() -> new GenerationJob(
			null, USER_ID, SESSION_ID, "QUEUED", "try-on", CLIENT_REQUEST_ID, CREATED_AT, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationJob(
			JOB_ID, null, SESSION_ID, "QUEUED", "try-on", CLIENT_REQUEST_ID, CREATED_AT, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, null, "try-on", CLIENT_REQUEST_ID, CREATED_AT, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "", "try-on", CLIENT_REQUEST_ID, CREATED_AT, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "   ", "try-on", CLIENT_REQUEST_ID, CREATED_AT, null))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new GenerationJob(
			JOB_ID, USER_ID, SESSION_ID, "QUEUED", "try-on", CLIENT_REQUEST_ID, null, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void optionalFieldsMayBeNull() {
		GenerationJob job = new GenerationJob(JOB_ID, USER_ID, null, "QUEUED", null, null, CREATED_AT, null);

		assertThat(job.id()).isEqualTo(JOB_ID);
		assertThat(job.userId()).isEqualTo(USER_ID);
		assertThat(job.studioSessionId()).isNull();
		assertThat(job.status()).isEqualTo("QUEUED");
		assertThat(job.requestedMode()).isNull();
		assertThat(job.clientRequestId()).isNull();
		assertThat(job.createdAt()).isEqualTo(CREATED_AT);
		assertThat(job.completedAt()).isNull();
	}

	@Test
	void rememberIntentWithSameClientRequestIdReturnsFirstJob() {
		Map<ClientRequestId, GenerationJob> intents = new HashMap<>();
		GenerationJob first = job(JOB_ID, CLIENT_REQUEST_ID);
		GenerationJob second = new GenerationJob(
			OTHER_JOB_ID, USER_ID, SESSION_ID, "COMPLETED", "outfit", CLIENT_REQUEST_ID, CREATED_AT, COMPLETED_AT);

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

	private static GenerationJob rememberAtBarrier(
		Map<ClientRequestId, GenerationJob> intents,
		GenerationJob job,
		CyclicBarrier barrier) throws Exception {
		barrier.await();
		return GenerationJob.rememberIntent(intents, job);
	}

	private static GenerationJob job(GenerationJobId id, ClientRequestId clientRequestId) {
		return new GenerationJob(id, USER_ID, SESSION_ID, "QUEUED", "try-on", clientRequestId, CREATED_AT, null);
	}
}
