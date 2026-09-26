package com.aifashionvault.notification;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.ObservationFacade;
import com.aifashionvault.shared.UserId;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.tck.TestObservationRegistry;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
	"spring.autoconfigure.exclude=",
	// Modulith's observability BPP CGLIB-proxies module beans; the final
	// FcmClient cannot be subclassed, so module tracing stays off here.
	"management.tracing.enabled=false",
	"vault.notification.project-id=test-project",
	"vault.notification.access-token=test-token",
	// Keep the autonomous delivery tick out of other focused test contexts' way.
	"vault.notification.poll-delay=PT1S" })
@Import(NotificationConfigurationTest.DeliveryBeans.class)
class NotificationConfigurationTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-04-02T12:00:00Z");
	private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(60);
	private static final String DEVICE = "device-token-a";
	private static final String LOOK_READY_MESSAGE = "{\"name\":\"projects/test-project/messages/msg-look\"}";

	static {
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
	}

	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("postgres")
		.withStartupTimeout(STARTUP_TIMEOUT);

	private static final FakeFcmServer FCM = startFakeFcmServer();

	@Autowired
	private FcmDeliveryWorker worker;

	@Autowired
	private FcmClient productionFcmClient;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private TestObservationRegistry observations;

	@Autowired
	private SimpleMeterRegistry meters;

	@Autowired
	private MutableClock clock;

	@DynamicPropertySource
	static void registerDatasource(DynamicPropertyRegistry registry) {
		POSTGRES.start();
		registry.add("vault.db.url", POSTGRES::getJdbcUrl);
		registry.add("vault.db.username", POSTGRES::getUsername);
		registry.add("vault.db.password", POSTGRES::getPassword);
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("vault.notification.fcm-base-url", FCM::baseUrl);
	}

	@BeforeEach
	void cleanNotificationTables() {
		jdbc.update(
			"TRUNCATE TABLE generation_notification_delivery, generation_notification_event, look, generation_job, users CASCADE");
	}

	@Test
	void scheduledPollAutonomouslyProcessesQueuedDeliveryAndEmitsTheFrozenNotificationTelemetry() {
		FCM.enqueue(200, null, LOOK_READY_MESSAGE);
		clock.set(NOW);
		seedLookReady();

		awaitDelivery();

		assertThat(FCM.requests()).hasSize(1);
		assertThat(FCM.requests().getFirst().path())
			.isEqualTo("/v1/projects/test-project/messages:send");
		DeviceDelivery delivery = waitForSingleDeliveryRow();
		assertThat(delivery.acceptedForDelivery()).isTrue();
		assertThat(delivery.terminalResult()).isEqualTo(DeviceDelivery.ACCEPTED_FOR_DELIVERY);
		assertThat(delivery.attemptCount()).isEqualTo(1);
		assertThat(delivery.providerMessageId()).isEqualTo("projects/test-project/messages/msg-look");
		observations.assertThat().hasHandledContextsThatSatisfy(contexts -> {
			List<Observation.Context> sendSpans = contexts.stream()
				.filter(context -> ObservationFacade.NOTIFICATION_SEND.equals(context.getName()))
				.toList();
			assertThat(sendSpans).hasSize(1);
			Observation.Context sendSpan = sendSpans.getFirst();
			assertThat(sendSpan.getLowCardinalityKeyValues())
				.containsExactly(KeyValue.of("outcome", "ACCEPTED"));
			assertThat(sendSpan.getHighCardinalityKeyValues()).isEmpty();
			assertThat(sendSpan.getError()).isNull();
			assertThat(sendSpan.getLowCardinalityKeyValues().toString()
				+ sendSpan.getHighCardinalityKeyValues())
				.doesNotContain(DEVICE, "test-token", "msg-look");
		});
		assertThat(meters.get(NotificationMetrics.SEND_OUTCOME)
			.tag("channel", "FCM")
			.tag("outcome", "ACCEPTED")
			.counter()
			.count()).isEqualTo(1.0d);
		assertThat(meters.get(NotificationMetrics.SEND_DURATION)
			.tag("channel", "FCM")
			.tag("outcome", "ACCEPTED")
			.timer()
			.count()).isEqualTo(1L);
	}

	@Test
	void productionClientBindsTheDeliveryConfigurationDefaultWhenNoBaseUrlOverrideExists() {
		NotificationDeliveryConfiguration defaults = new NotificationDeliveryConfiguration();
		assertThat(defaults.getFcmBaseUrl()).isEqualTo("https://fcm.googleapis.com");
		assertThat(productionFcmClient).isNotNull();
	}

	@Test
	void slimContextWithoutFcmClientStaysLoadableAndHasNoWorker() {
		new ApplicationContextRunner()
			.withUserConfiguration(NotificationConfiguration.class)
			.withBean(DataSource.class, SimpleDriverDataSource::new)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBeansOfType(JdbcNotificationEventStore.class)).hasSize(1);
				assertThat(context.getBeansOfType(FcmClient.class)).isEmpty();
				assertThat(context.getBeansOfType(FcmDeliveryWorker.class)).isEmpty();
				assertThat(context.getBeansOfType(NotificationConfiguration.FcmDeliveryScheduler.class)).hasSize(1);
			});
	}

	@Test
	void slimContextWithoutAnyDependenciesStaysLoadable() {
		new ApplicationContextRunner()
			.withUserConfiguration(NotificationConfiguration.class)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBeansOfType(JdbcNotificationEventStore.class)).isEmpty();
				assertThat(context.getBeansOfType(FcmClient.class)).isEmpty();
				assertThat(context.getBeansOfType(FcmDeliveryWorker.class)).isEmpty();
				assertThat(context.getBeansOfType(NotificationConfiguration.FcmDeliveryScheduler.class)).hasSize(1);
			});
	}

	private void seedLookReady() {
		UserId userId = insertUser();
		GenerationJobId generationId = insertJob(userId);
		LookId lookId = insertLook(userId, generationId);
		worker.enqueue(
			NotificationEvent.lookReady(
				UUID.randomUUID(),
				userId,
				generationId,
				lookId,
				NOW,
				NOW.plus(Duration.ofHours(24))),
			List.of(DEVICE));
	}

	private void awaitDelivery() {
		waitUntil("scheduled poll delivered the queued notification", WAIT_TIMEOUT,
			() -> !FCM.requests().isEmpty());
	}

	private DeviceDelivery waitForSingleDeliveryRow() {
		waitUntil("exactly one persisted delivery row", WAIT_TIMEOUT,
			() -> deliveryRows().size() == 1);
		List<DeviceDelivery> deliveries = deliveryRows();
		assertThat(deliveries).hasSize(1);
		return deliveries.getFirst();
	}

	private List<DeviceDelivery> deliveryRows() {
		return jdbc.query(
			"SELECT id, event_id, device_identity, status, attempt_count, next_attempt_at, "
				+ "provider_message_id, terminal_result FROM generation_notification_delivery",
			(rs, rowNum) -> new DeviceDelivery(
				rs.getObject("id", UUID.class),
				rs.getObject("event_id", UUID.class),
				rs.getString("device_identity"),
				rs.getString("status"),
				rs.getInt("attempt_count"),
				toInstant(rs.getTimestamp("next_attempt_at")),
				rs.getString("provider_message_id"),
				rs.getString("terminal_result")));
	}

	private static java.time.Instant toInstant(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	private static void waitUntil(String condition, Duration timeout, java.util.function.BooleanSupplier probe) {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (probe.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(100);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("interrupted while waiting for " + condition, ex);
			}
		}
		throw new AssertionError("timed out waiting for " + condition);
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

	private static FakeFcmServer startFakeFcmServer() {
		try {
			return FakeFcmServer.start();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DeliveryBeans {

		@Bean
		@Primary
		TestObservationRegistry testObservationRegistry() {
			return TestObservationRegistry.create();
		}

		@Bean
		@Primary
		SimpleMeterRegistry simpleMeterRegistry() {
			return new SimpleMeterRegistry();
		}

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock(NOW);
		}

	}

	/** Mutable application clock so the scheduled delivery tick reads a pinned instant. */
	static final class MutableClock extends Clock {

		private volatile Instant instant;

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
		private final List<CapturedSend> requests = new CopyOnWriteArrayList<>();

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

		private List<CapturedSend> requests() {
			return requests;
		}

		private void handle(HttpExchange exchange) throws IOException {
			exchange.getRequestBody().readAllBytes();
			requests.add(new CapturedSend(exchange.getRequestURI().getPath()));
			ScriptedResponse response = script.poll();
			if (response == null) {
				response = new ScriptedResponse(200, null, LOOK_READY_MESSAGE);
			}
			byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(response.status(), body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		}
	}

	private record ScriptedResponse(int status, String retryAfter, String body) {
	}

	private record CapturedSend(String path) {
	}
}
