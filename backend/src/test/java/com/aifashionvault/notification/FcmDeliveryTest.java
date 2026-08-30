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
import com.aifashionvault.shared.UserId;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

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

	private FcmDeliveryWorker newWorker() {
		HttpClient httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(2))
			.build();
		FcmClient client = new FcmClient(fcm.baseUrl(), PROJECT_ID, () -> ACCESS_TOKEN, config.ttl(), httpClient);
		return new FcmDeliveryWorker(store, client, config, zeroJitter());
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
