package com.aifashionvault.notification;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.ObservationFacade;
import com.aifashionvault.shared.UserId;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.common.KeyValue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.tck.TestObservationRegistry;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
class FcmDeliveryTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-04-02T12:00:00Z");
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String PROJECT_ID = "test-project";
	private static final String ACCESS_TOKEN = "test-token";
	private static final String DEVICE = "device-token-a";
	private static final String LOOK_READY_MESSAGE = "{\"name\":\"projects/test-project/messages/msg-look\"}";
	private static final String FAILED_MESSAGE = "{\"name\":\"projects/test-project/messages/msg-fail\"}";

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbc;

	private JdbcNotificationEventStore store;
	private MutableClock clock;
	private NotificationDeliveryConfiguration config;
	private FakeFcmServer fcm;
	private FcmDeliveryWorker worker;
	private TestObservationRegistry observations;
	private SimpleMeterRegistry meters;

	@DynamicPropertySource
	static void registerDatasource(DynamicPropertyRegistry registry) {
		POSTGRES.start();
		registry.add("vault.db.url", POSTGRES::getJdbcUrl);
		registry.add("vault.db.username", POSTGRES::getUsername);
		registry.add("vault.db.password", POSTGRES::getPassword);
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	@BeforeEach
	void setUp() throws IOException {
		jdbc.update(
			"TRUNCATE TABLE generation_notification_delivery, generation_notification_event, look, generation_job, users CASCADE");
		store = new JdbcNotificationEventStore(dataSource);
		clock = new MutableClock(NOW);
		observations = TestObservationRegistry.create();
		meters = new SimpleMeterRegistry();
		config = new NotificationDeliveryConfiguration();
		config.setProjectId(PROJECT_ID);
		config.setAccessToken(ACCESS_TOKEN);
		fcm = FakeFcmServer.start();
		worker = newWorker();
	}

	@AfterEach
	void tearDown() {
		if (fcm != null) {
			fcm.stop();
		}
	}

	@Test
	void lookReadyPayloadIsExactAndContainsNoMediaUrls() {
		Seed seed = seedLookReady();
		fcm.enqueue(200, null, LOOK_READY_MESSAGE);

		assertThat(worker.processNext(clock.instant())).isTrue();

		assertThat(fcm.requests).hasSize(1);
		CapturedSend send = fcm.requests.getFirst();
		assertThat(send.path()).isEqualTo("/v1/projects/test-project/messages:send");
		assertThat(send.authorization()).isEqualTo("Bearer " + ACCESS_TOKEN);
		assertThat(send.data().keySet()).containsExactly("type", "lookId");
		assertThat(send.data())
			.containsEntry("type", "LOOK_READY")
			.containsEntry("lookId", seed.lookId().value().toString());
		assertNoMedia(send.data());
		assertThat(send.androidTtl()).isEqualTo("86400s");
		DeviceDelivery delivery = store.findDelivery(seed.eventId(), DEVICE).orElseThrow();
		assertThat(delivery.acceptedForDelivery()).isTrue();
		assertThat(delivery.terminalResult()).isEqualTo(DeviceDelivery.ACCEPTED_FOR_DELIVERY);
		assertThat(delivery.terminalResult()).isNotEqualTo("DELIVERED");
		assertThat(delivery.providerMessageId()).isEqualTo("projects/test-project/messages/msg-look");
	}

	@Test
	void generationFailedPayloadCarriesTypeAndGenerationIdOnly() {
		Seed seed = seedGenerationFailed();
		fcm.enqueue(200, null, FAILED_MESSAGE);

		assertThat(worker.processNext(clock.instant())).isTrue();

		CapturedSend send = fcm.requests.getFirst();
		assertThat(send.data().keySet()).containsExactly("type", "generationId");
		assertThat(send.data()).containsEntry("type", "GENERATION_FAILED");
		assertThat(send.data()).containsEntry("generationId", seed.generationId().value().toString());
		assertThat(send.data()).doesNotContainKey("lookId");
		assertNoMedia(send.data());
		DeviceDelivery delivery = store.findDelivery(seed.eventId(), DEVICE).orElseThrow();
		assertThat(delivery.terminalResult()).isEqualTo(DeviceDelivery.ACCEPTED_FOR_DELIVERY);
	}

	@Test
	void uniqueDeviceDeliverySendsOnce() {
		Seed seed = seedLookReady();
		assertThat(store.ensureDelivery(seed.eventId(), DEVICE)).isFalse();
		fcm.enqueue(200, null, LOOK_READY_MESSAGE);
		fcm.enqueue(200, null, LOOK_READY_MESSAGE);

		assertThat(worker.processNext(clock.instant())).isTrue();
		assertThat(worker.processNext(clock.instant())).isFalse();

		assertThat(fcm.requests).hasSize(1);
		Integer rows = jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_notification_delivery WHERE event_id = ? AND device_identity = ?",
			Integer.class,
			seed.eventId(),
			DEVICE);
		assertThat(rows).isEqualTo(1);
	}

	@Test
	void retryAfterIsHonoredAndNeverBelowTenSeconds() {
		Seed seed = seedLookReady();
		fcm.enqueue(429, "45", "");
		fcm.enqueue(200, null, LOOK_READY_MESSAGE);

		assertThat(worker.processNext(clock.instant())).isTrue();
		DeviceDelivery waiting = store.findDelivery(seed.eventId(), DEVICE).orElseThrow();
		assertThat(waiting.status()).isEqualTo(DeviceDelivery.PENDING);
		assertThat(waiting.nextAttemptAt()).isEqualTo(NOW.plusSeconds(45));
		assertThat(fcm.requests).hasSize(1);

		clock.set(NOW.plusSeconds(10));
		assertThat(worker.processNext(clock.instant())).isFalse();
		assertThat(fcm.requests).hasSize(1);

		clock.set(NOW.plusSeconds(45));
		assertThat(worker.processNext(clock.instant())).isTrue();
		assertThat(fcm.requests).hasSize(2);
		assertThat(store.findDelivery(seed.eventId(), DEVICE).orElseThrow().acceptedForDelivery()).isTrue();

		Seed second = seedLookReady();
		fcm.enqueue(429, "2", "");
		assertThat(worker.processNext(clock.instant())).isTrue();
		assertThat(store.findDelivery(second.eventId(), DEVICE).orElseThrow().nextAttemptAt())
			.isEqualTo(clock.instant().plusSeconds(10));
	}

	@Test
	void status429And5xxRetryUntilSuccess() {
		Seed seed = seedLookReady();
		fcm.enqueue(429, null, "");
		fcm.enqueue(503, null, "");
		fcm.enqueue(200, null, LOOK_READY_MESSAGE);

		assertThat(worker.processNext(clock.instant())).isTrue();
		clock.set(store.findDelivery(seed.eventId(), DEVICE).orElseThrow().nextAttemptAt());
		assertThat(worker.processNext(clock.instant())).isTrue();
		clock.set(store.findDelivery(seed.eventId(), DEVICE).orElseThrow().nextAttemptAt());
		assertThat(worker.processNext(clock.instant())).isTrue();

		assertThat(fcm.requests).hasSize(3);
		assertThat(store.findDelivery(seed.eventId(), DEVICE).orElseThrow().acceptedForDelivery()).isTrue();
	}

	@Test
	void attemptCapStopsAfterTenSends() {
		Seed seed = seedLookReady();
		for (int i = 0; i < 12; i++) {
			fcm.enqueue(500, null, "");
		}

		for (int i = 0; i < 10; i++) {
			assertThat(worker.processNext(clock.instant())).isTrue();
			DeviceDelivery delivery = store.findDelivery(seed.eventId(), DEVICE).orElseThrow();
			if (delivery.nextAttemptAt() != null) {
				clock.set(delivery.nextAttemptAt());
			}
		}

		assertThat(fcm.requests).hasSize(10);
		DeviceDelivery exhausted = store.findDelivery(seed.eventId(), DEVICE).orElseThrow();
		assertThat(exhausted.status()).isEqualTo(DeviceDelivery.FAILED);
		assertThat(exhausted.attemptCount()).isEqualTo(10);
		assertThat(exhausted.terminalResult()).isEqualTo(DeviceDelivery.ATTEMPTS_EXHAUSTED);
		assertThat(worker.processNext(clock.instant())).isFalse();
		assertThat(fcm.requests).hasSize(10);
	}

	@Test
	void deadlineStopsDeliveryWithoutAnotherSend() {
		UserId userId = insertUser();
		GenerationJobId generationId = insertJob(userId);
		LookId lookId = insertLook(userId, generationId);
		UUID eventId = UUID.randomUUID();
		worker.enqueue(
			NotificationEvent.lookReady(eventId, userId, generationId, lookId, NOW, NOW),
			List.of(DEVICE));
		fcm.enqueue(200, null, LOOK_READY_MESSAGE);

		assertThat(worker.processNext(clock.instant())).isTrue();

		assertThat(fcm.requests).isEmpty();
		assertThat(store.findDelivery(eventId, DEVICE).orElseThrow().terminalResult())
			.isEqualTo(DeviceDelivery.DEADLINE_EXCEEDED);
	}

	@Test
	void acceptedSendEmitsExactlyOneStoppedSpanAndFrozenOutcomeAndDurationSamples() {
		Seed seed = seedLookReady();
		fcm.enqueue(200, null, LOOK_READY_MESSAGE);

		assertThat(worker.processNext(clock.instant())).isTrue();
		observations.assertThat().hasSingleObservationThat()
			.hasNameEqualTo(ObservationFacade.NOTIFICATION_SEND)
			.hasLowCardinalityKeyValue("outcome", "ACCEPTED")
			.hasBeenStopped();

		observations.assertThat().hasHandledContextsThatSatisfy(contexts -> {
			assertThat(contexts).hasSize(1);
			assertStoppedSendSpan(contexts.get(0), "ACCEPTED");
			String exported = contexts.get(0).getName() + contexts.get(0).getAllKeyValues().toString();
			assertThat(exported).doesNotContain(
				DEVICE,
				ACCESS_TOKEN,
				seed.eventId().toString(),
				seed.lookId().value().toString(),
				"projects/test-project/messages/msg-look");
		});
		assertSendSamples("ACCEPTED", 1);
		assertThat(meters.getMeters()).hasSize(2);
	}

	@Test
	void retryScheduledSendEmitsSpanAndFrozenOutcomeAndDurationSamples() {
		seedLookReady();
		fcm.enqueue(429, "45", "");

		assertThat(worker.processNext(clock.instant())).isTrue();
		observations.assertThat().hasSingleObservationThat()
			.hasNameEqualTo(ObservationFacade.NOTIFICATION_SEND)
			.hasLowCardinalityKeyValue("outcome", "RETRY_SCHEDULED")
			.hasBeenStopped();

		observations.assertThat().hasHandledContextsThatSatisfy(contexts -> {
			assertThat(contexts).hasSize(1);
			assertStoppedSendSpan(contexts.get(0), "RETRY_SCHEDULED");
		});
		assertSendSamples("RETRY_SCHEDULED", 1);
	}

	@Test
	void permanentFailureSendEmitsSpanAndFrozenOutcomeAndDurationSamples() {
		seedLookReady();
		fcm.enqueue(400, null, "");

		assertThat(worker.processNext(clock.instant())).isTrue();
		observations.assertThat().hasSingleObservationThat()
			.hasNameEqualTo(ObservationFacade.NOTIFICATION_SEND)
			.hasLowCardinalityKeyValue("outcome", "PERMANENT_FAILURE")
			.hasBeenStopped();

		observations.assertThat().hasHandledContextsThatSatisfy(contexts -> {
			assertThat(contexts).hasSize(1);
			assertStoppedSendSpan(contexts.get(0), "PERMANENT_FAILURE");
		});
		assertSendSamples("PERMANENT_FAILURE", 1);
	}

	@Test
	void attemptsExhaustedOnFinalSendEmitsSpanAndFrozenOutcomeAndDurationSamples() {
		Seed seed = seedLookReady();
		for (int i = 0; i < 10; i++) {
			fcm.enqueue(503, null, "");
		}

		for (int i = 0; i < 10; i++) {
			assertThat(worker.processNext(clock.instant())).isTrue();
			if (i < 9) {
				clock.set(store.findDelivery(seed.eventId(), DEVICE).orElseThrow().nextAttemptAt());
			}
		}

		observations.assertThat().hasHandledContextsThatSatisfy(contexts -> {
			assertThat(contexts).hasSize(10);
			long scheduled = 0;
			long exhausted = 0;
			for (Observation.Context context : contexts) {
				if (KeyValue.of("outcome", "RETRY_SCHEDULED").equals(context.getLowCardinalityKeyValue("outcome"))) {
					assertStoppedSendSpan(context, "RETRY_SCHEDULED");
					scheduled++;
				}
				else {
					assertStoppedSendSpan(context, "ATTEMPTS_EXHAUSTED");
					exhausted++;
				}
			}
			assertThat(scheduled).isEqualTo(9);
			assertThat(exhausted).isEqualTo(1);
		});
		assertSendSamples("RETRY_SCHEDULED", 9);
		assertSendSamples("ATTEMPTS_EXHAUSTED", 1);
	}

	@Test
	void preSendDeadlineTerminationEmitsNoSpanAndNoMetricSamples() {
		UserId userId = insertUser();
		GenerationJobId generationId = insertJob(userId);
		LookId lookId = insertLook(userId, generationId);
		UUID eventId = UUID.randomUUID();
		worker.enqueue(
			NotificationEvent.lookReady(eventId, userId, generationId, lookId, NOW, NOW),
			List.of(DEVICE));
		fcm.enqueue(200, null, LOOK_READY_MESSAGE);

		assertThat(worker.processNext(clock.instant())).isTrue();

		assertThat(fcm.requests).isEmpty();
		observations.assertThat().doesNotHaveAnyObservation();
		assertThat(meters.getMeters()).isEmpty();
		assertThat(store.findDelivery(eventId, DEVICE).orElseThrow().terminalResult())
			.isEqualTo(DeviceDelivery.DEADLINE_EXCEEDED);
	}

	@Test
	void preSendAttemptsExhaustedTerminationEmitsNoSpanAndNoMetricSamples() {
		Seed seed = seedLookReady();
		jdbc.update(
			"UPDATE generation_notification_delivery SET attempt_count = 10 WHERE event_id = ?",
			seed.eventId());
		fcm.enqueue(200, null, LOOK_READY_MESSAGE);

		assertThat(worker.processNext(clock.instant())).isTrue();

		assertThat(fcm.requests).isEmpty();
		observations.assertThat().doesNotHaveAnyObservation();
		assertThat(meters.getMeters()).isEmpty();
		assertThat(store.findDelivery(seed.eventId(), DEVICE).orElseThrow().terminalResult())
			.isEqualTo(DeviceDelivery.ATTEMPTS_EXHAUSTED);
	}

	@Test
	void sendExceptionMarksSpanErrorRecordsNoSamplesAndPropagatesUnchanged() {
		Seed seed = seedLookReady();
		FcmDeliveryWorker brokenToken = workerWithBlankAccessToken();

		assertThatThrownBy(() -> brokenToken.processNext(clock.instant()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("access token is required");

		assertStoppedErrorSpan();
		assertThat(meters.getMeters()).isEmpty();
		DeviceDelivery unchanged = store.findDelivery(seed.eventId(), DEVICE).orElseThrow();
		assertThat(unchanged.status()).isEqualTo(DeviceDelivery.PENDING);
		assertThat(unchanged.attemptCount()).isZero();
	}

	@Test
	void onStartHandlerFailureDoesNotEscapeAndAcceptedSendStillPersistsOnce() {
		Seed seed = seedLookReady();
		fcm.enqueue(200, null, LOOK_READY_MESSAGE);
		FcmDeliveryWorker instrumented = workerWith(FailingHandlers.onStart());

		assertThat(instrumented.processNext(clock.instant())).isTrue();

		assertThat(fcm.requests).hasSize(1);
		assertAcceptedExactlyOnce(seed);
	}

	@Test
	void onErrorHandlerFailureDoesNotEscapeAndOriginalErrorStillPropagatesUnchanged() {
		Seed seed = seedLookReady();
		FcmDeliveryWorker instrumented = workerWith(FailingHandlers.onError(), blankTokenClient());

		assertThatThrownBy(() -> instrumented.processNext(clock.instant()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("access token is required");

		assertThat(fcm.requests).isEmpty();
		DeviceDelivery unchanged = store.findDelivery(seed.eventId(), DEVICE).orElseThrow();
		assertThat(unchanged.status()).isEqualTo(DeviceDelivery.PENDING);
		assertThat(unchanged.attemptCount()).isZero();
	}

	@Test
	void onStopHandlerFailureDoesNotEscapeAndAcceptedSendStillPersistsOnce() {
		Seed seed = seedLookReady();
		fcm.enqueue(200, null, LOOK_READY_MESSAGE);
		FcmDeliveryWorker instrumented = workerWith(FailingHandlers.onStop());

		assertThat(instrumented.processNext(clock.instant())).isTrue();

		assertThat(fcm.requests).hasSize(1);
		assertAcceptedExactlyOnce(seed);
	}

	@Test
	void retryScheduledSendStillPersistsWhenOnStopThrows() {
		Seed seed = seedLookReady();
		fcm.enqueue(429, "45", "");
		FcmDeliveryWorker instrumented = workerWith(FailingHandlers.onStop());

		assertThat(instrumented.processNext(clock.instant())).isTrue();

		assertThat(fcm.requests).hasSize(1);
		DeviceDelivery waiting = store.findDelivery(seed.eventId(), DEVICE).orElseThrow();
		assertThat(waiting.status()).isEqualTo(DeviceDelivery.PENDING);
		assertThat(waiting.attemptCount()).isEqualTo(1);
		assertThat(waiting.nextAttemptAt()).isEqualTo(NOW.plusSeconds(45));
	}

	@Test
	void failedSendStillPersistsTerminalWhenOnStopThrows() {
		Seed seed = seedLookReady();
		fcm.enqueue(400, null, "");
		FcmDeliveryWorker instrumented = workerWith(FailingHandlers.onStop());

		assertThat(instrumented.processNext(clock.instant())).isTrue();

		assertThat(fcm.requests).hasSize(1);
		DeviceDelivery terminal = store.findDelivery(seed.eventId(), DEVICE).orElseThrow();
		assertThat(terminal.status()).isEqualTo(DeviceDelivery.FAILED);
		assertThat(terminal.terminalResult()).isEqualTo(DeviceDelivery.PERMANENT_FAILURE);
	}

	private void assertAcceptedExactlyOnce(Seed seed) {
		DeviceDelivery delivery = store.findDelivery(seed.eventId(), DEVICE).orElseThrow();
		assertThat(delivery.acceptedForDelivery()).isTrue();
		assertThat(delivery.attemptCount()).isEqualTo(1);
		assertThat(delivery.terminalResult()).isEqualTo(DeviceDelivery.ACCEPTED_FOR_DELIVERY);
		assertThat(delivery.providerMessageId()).isEqualTo("projects/test-project/messages/msg-look");
		Integer rows = jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_notification_delivery WHERE event_id = ?",
			Integer.class,
			seed.eventId());
		assertThat(rows).isEqualTo(1);
	}

	private FcmDeliveryWorker workerWith(ObservationHandler<Observation.Context> handler) {
		return workerWith(handler, newClient(() -> ACCESS_TOKEN));
	}

	private FcmDeliveryWorker workerWith(ObservationHandler<Observation.Context> handler, FcmClient client) {
		ObservationRegistry registry = ObservationRegistry.create();
		registry.observationConfig().observationHandler(handler);
		return new FcmDeliveryWorker(store, client, config, zeroJitter(), meters, registry);
	}

	private FcmClient blankTokenClient() {
		return newClient(() -> "");
	}

	private FcmClient newClient(java.util.function.Supplier<String> accessToken) {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(2))
			.build();
		return new FcmClient(fcm.baseUrl(), PROJECT_ID, accessToken, config.ttl(), httpClient);
	}

	private FcmDeliveryWorker workerWithBlankAccessToken() {
		return new FcmDeliveryWorker(store, blankTokenClient(), config, zeroJitter(), meters, observations);
	}

	private void assertSendSamples(String outcome, long count) {
		assertThat(meters.get(NotificationMetrics.SEND_OUTCOME)
			.tag("channel", "FCM")
			.tag("outcome", outcome)
			.counter()
			.count()).isEqualTo((double) count);
		assertThat(meters.get(NotificationMetrics.SEND_DURATION)
			.tag("channel", "FCM")
			.tag("outcome", outcome)
			.timer()
			.count()).isEqualTo(count);
	}

	private static void assertStoppedSendSpan(Observation.Context context, String outcome) {
		assertThat(context.getName()).isEqualTo(ObservationFacade.NOTIFICATION_SEND);
		assertThat(context.getError()).isNull();
		assertThat(context.getLowCardinalityKeyValues()).containsExactly(KeyValue.of("outcome", outcome));
		assertThat(context.getHighCardinalityKeyValues()).isEmpty();
	}

	private void assertStoppedErrorSpan() {
		observations.assertThat().hasSingleObservationThat()
			.hasNameEqualTo(ObservationFacade.NOTIFICATION_SEND)
			.hasBeenStopped()
			.assertThatError()
			.isInstanceOf(IllegalStateException.class);
	}

	private FcmDeliveryWorker newWorker() {
		return new FcmDeliveryWorker(store, newClient(() -> ACCESS_TOKEN), config, zeroJitter(), meters, observations);
	}

	private Seed seedLookReady() {
		UserId userId = insertUser();
		GenerationJobId generationId = insertJob(userId);
		LookId lookId = insertLook(userId, generationId);
		UUID eventId = UUID.randomUUID();
		worker.enqueue(
			NotificationEvent.lookReady(eventId, userId, generationId, lookId, NOW, NOW.plus(config.ttl())),
			List.of(DEVICE));
		return new Seed(eventId, userId, generationId, lookId);
	}

	private Seed seedGenerationFailed() {
		UserId userId = insertUser();
		GenerationJobId generationId = insertJob(userId);
		UUID eventId = UUID.randomUUID();
		worker.enqueue(
			NotificationEvent.generationFailed(
				eventId,
				userId,
				generationId,
				com.aifashionvault.shared.TerminalReason.PROVIDER_UNAVAILABLE.name(),
				NOW,
				NOW.plus(config.ttl())),
			List.of(DEVICE));
		return new Seed(eventId, userId, generationId, null);
	}

	private UserId insertUser() {
		UserId userId = new UserId(UUID.randomUUID());
		jdbc.update(
			"INSERT INTO users (id, created_at, updated_at, deleted_at) VALUES (?, ?, ?, NULL)",
			userId.value(),
			Timestamp.from(NOW),
			Timestamp.from(NOW));
		return userId;
	}

	private GenerationJobId insertJob(UserId userId) {
		GenerationJobId jobId = new GenerationJobId(UUID.randomUUID());
		jdbc.update(
			"INSERT INTO generation_job (id, user_id, status, created_at) VALUES (?, ?, 'QUEUED', ?)",
			jobId.value(),
			userId.value(),
			Timestamp.from(NOW));
		return jobId;
	}

	private LookId insertLook(UserId userId, GenerationJobId jobId) {
		LookId lookId = new LookId(UUID.randomUUID());
		jdbc.update(
			"INSERT INTO look (id, user_id, generation_job_id, storage_key, quality_status, created_at) "
				+ "VALUES (?, ?, ?, ?, 'PASS', ?)",
			lookId.value(),
			userId.value(),
			jobId.value(),
			"users/" + userId.value() + "/looks/" + lookId.value() + ".jpg",
			Timestamp.from(NOW));
		return lookId;
	}

	private static void assertNoMedia(Map<String, String> data) {
		assertThat(data.keySet())
			.doesNotContain(
				"image_url",
				"imageUrl",
				"image",
				"signed_url",
				"signedUrl",
				"storageKey",
				"storage_key",
				"objectKey",
				"object_key",
				"providerJobId",
				"provider_job_id",
				"mediaUrl",
				"media_url",
				"base64_image");
		assertThat(data.values()).noneMatch(value -> {
			if (value == null) {
				return false;
			}
			String lower = value.toLowerCase(Locale.ROOT);
			return lower.startsWith("http://") || lower.startsWith("https://");
		});
	}

	private static Random zeroJitter() {
		return new Random() {
			@Override
			public double nextDouble() {
				return 0.0d;
			}
		};
	}

	private record Seed(UUID eventId, UserId userId, GenerationJobId generationId, LookId lookId) {
	}

	private static final class FailingHandlers {

		private FailingHandlers() {
		}

		private static ObservationHandler<Observation.Context> onStart() {
			return throwing(FailingStage.ON_START);
		}

		private static ObservationHandler<Observation.Context> onError() {
			return throwing(FailingStage.ON_ERROR);
		}

		private static ObservationHandler<Observation.Context> onStop() {
			return throwing(FailingStage.ON_STOP);
		}

		private static ObservationHandler<Observation.Context> throwing(FailingStage stage) {
			return new ObservationHandler<Observation.Context>() {

				@Override
				public boolean supportsContext(Observation.Context context) {
					return true;
				}

				@Override
				public void onStart(Observation.Context context) {
					if (stage == FailingStage.ON_START) {
						throw new IllegalStateException("canary onStart handler failure");
					}
				}

				@Override
				public void onEvent(Observation.Event event, Observation.Context context) {
					if (stage == FailingStage.ON_ERROR) {
						throw new IllegalStateException("canary onError handler failure");
					}
				}

				@Override
				public void onStop(Observation.Context context) {
					if (stage == FailingStage.ON_STOP) {
						throw new IllegalStateException("canary onStop handler failure");
					}
				}
			};
		}
	}

	private enum FailingStage {
		ON_START,
		ON_ERROR,
		ON_STOP
	}

	private static final class MutableClock extends Clock {

		private Instant instant;

		private MutableClock(Instant instant) {
			this.instant = instant;
		}

		private void set(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return Clock.fixed(this.instant, zone);
		}

		@Override
		public Instant instant() {
			return this.instant;
		}
	}

	private static final class FakeFcmServer {

		private final HttpServer server;
		private final ConcurrentLinkedQueue<ScriptedResponse> script = new ConcurrentLinkedQueue<>();
		private final List<CapturedSend> requests = new ArrayList<>();

		private FakeFcmServer(HttpServer server) {
			this.server = server;
		}

		private static FakeFcmServer start() throws IOException {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			FakeFcmServer fake = new FakeFcmServer(server);
			server.createContext("/v1/projects/test-project/messages:send", fake::handle);
			server.start();
			return fake;
		}

		private void enqueue(int status, String retryAfter, String body) {
			script.add(new ScriptedResponse(status, retryAfter, body));
		}

		private String baseUrl() {
			InetSocketAddress address = server.getAddress();
			return "http://127.0.0.1:" + address.getPort();
		}

		private void stop() {
			server.stop(0);
		}

		private void handle(HttpExchange exchange) throws IOException {
			byte[] raw = readAll(exchange.getRequestBody());
			String body = new String(raw, StandardCharsets.UTF_8);
			JsonNode root = MAPPER.readTree(body);
			JsonNode message = root.get("message");
			JsonNode dataNode = message.get("data");
			Map<String, String> data = new java.util.LinkedHashMap<>();
			for (String key : dataNode.propertyNames()) {
				data.put(key, dataNode.get(key).stringValue());
			}
			String androidTtl = message.get("android").get("ttl").stringValue();
			synchronized (requests) {
				requests.add(new CapturedSend(
					exchange.getRequestMethod(),
					exchange.getRequestURI().getPath(),
					exchange.getRequestHeaders().getFirst("Authorization"),
					body,
					data,
					androidTtl));
			}
			ScriptedResponse response = script.poll();
			if (response == null) {
				response = new ScriptedResponse(200, null, LOOK_READY_MESSAGE);
			}
			if (response.retryAfter() != null) {
				exchange.getResponseHeaders().add("Retry-After", response.retryAfter());
			}
			byte[] responseBody = response.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(response.status(), responseBody.length);
			exchange.getResponseBody().write(responseBody);
			exchange.close();
		}

		private static byte[] readAll(InputStream input) throws IOException {
			return input.readAllBytes();
		}
	}

	private record ScriptedResponse(int status, String retryAfter, String body) {
	}

	private record CapturedSend(
		String method,
		String path,
		String authorization,
		String body,
		Map<String, String> data,
		String androidTtl
	) {
	}
}
