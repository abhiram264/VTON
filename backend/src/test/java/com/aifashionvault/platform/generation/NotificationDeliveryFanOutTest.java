package com.aifashionvault.platform.generation;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.admission.GlobalCostCircuitBreaker;
import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.generation.ProviderHealthGate;
import com.aifashionvault.generation.QualityGate;
import com.aifashionvault.generation.vton.VtonProvider;
import com.aifashionvault.look.LookStore;
import com.aifashionvault.notification.DeviceRegistrationStore;
import com.aifashionvault.notification.JdbcDeviceRegistrationStore;
import com.aifashionvault.notification.JdbcNotificationEventStore;
import com.aifashionvault.notification.NotificationEvent;
import com.aifashionvault.notification.NotificationStore;
import com.aifashionvault.platform.objectstore.GenerationMediaStore;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.queue.GenerationWorkQueue;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * C20 delivery fan-out: a durable notification event must also create the
 * per-device delivery rows the FCM worker's {@code claimDue} joins on. Before
 * this, events were inserted with no deliveries, so push could never be sent.
 */
@SpringBootTest(properties = "spring.autoconfigure.exclude=")
class NotificationDeliveryFanOutTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-04-02T12:00:00Z");
	private static final String DEVICE_A = "device-token-fanout-a";
	private static final String DEVICE_B = "device-token-fanout-b";

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

	private JdbcNotificationEventStore eventStore;
	private DeviceRegistrationStore registrations;

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
	void setUp() {
		jdbc.update("TRUNCATE TABLE generation_notification_delivery, generation_notification_event, "
			+ "device_registration, notification_preference, look, generation_job, users CASCADE");
		eventStore = new JdbcNotificationEventStore(dataSource);
		registrations = new JdbcDeviceRegistrationStore(dataSource);
	}

	@Test
	void readyEventAddressesEveryRegisteredDeviceAndBecomesClaimable() {
		UserId userId = insertUser();
		GenerationJobId jobId = insertJob(userId);
		LookId lookId = insertLook(userId, jobId);
		registrations.register(userId, DEVICE_A, "ANDROID", NOW);
		registrations.register(userId, DEVICE_B, "IOS", NOW);
		NotificationEvent event = NotificationEvent.lookReady(
			UUID.randomUUID(), userId, jobId, lookId, NOW, NOW.plus(Duration.ofHours(24)));

		processor().insertNotificationEvent(event);

		assertThat(deliveryTokens(event.id())).containsExactlyInAnyOrder(DEVICE_A, DEVICE_B);
		// The FCM worker claims from this join; a delivery row is what makes it sendable.
		assertThat(eventStore.claimDue(NOW)).isPresent();
	}

	@Test
	void noRegisteredDeviceCreatesNoDelivery() {
		UserId userId = insertUser();
		GenerationJobId jobId = insertJob(userId);
		LookId lookId = insertLook(userId, jobId);
		NotificationEvent event = NotificationEvent.lookReady(
			UUID.randomUUID(), userId, jobId, lookId, NOW, NOW.plus(Duration.ofHours(24)));

		processor().insertNotificationEvent(event);

		assertThat(deliveryTokens(event.id())).isEmpty();
		assertThat(eventStore.claimDue(NOW)).isEmpty();
	}

	@Test
	void pushDisabledOwnerReceivesNoDelivery() {
		UserId userId = insertUser();
		GenerationJobId jobId = insertJob(userId);
		LookId lookId = insertLook(userId, jobId);
		registrations.register(userId, DEVICE_A, "ANDROID", NOW);
		registrations.upsertPreference(userId, false, NOW);
		NotificationEvent event = NotificationEvent.lookReady(
			UUID.randomUUID(), userId, jobId, lookId, NOW, NOW.plus(Duration.ofHours(24)));

		processor().insertNotificationEvent(event);

		assertThat(deliveryTokens(event.id())).isEmpty();
	}

	@Test
	void replayDoesNotDuplicateDeliveries() {
		UserId userId = insertUser();
		GenerationJobId jobId = insertJob(userId);
		LookId lookId = insertLook(userId, jobId);
		registrations.register(userId, DEVICE_A, "ANDROID", NOW);
		NotificationEvent event = NotificationEvent.lookReady(
			UUID.randomUUID(), userId, jobId, lookId, NOW, NOW.plus(Duration.ofHours(24)));
		GenerationWorkProcessor processor = processor();

		processor.insertNotificationEvent(event);
		// Second pass: the event insert is a replay no-op, but the fan-out must stay
		// idempotent (UNIQUE(event_id, device_identity)) rather than duplicate rows.
		processor.insertNotificationEvent(event);

		assertThat(deliveryTokens(event.id())).containsExactly(DEVICE_A);
	}

	@Test
	void absentDeviceRegistryKeepsTheEventButAddressesNobody() {
		UserId userId = insertUser();
		GenerationJobId jobId = insertJob(userId);
		LookId lookId = insertLook(userId, jobId);
		registrations.register(userId, DEVICE_A, "ANDROID", NOW);
		NotificationEvent event = NotificationEvent.lookReady(
			UUID.randomUUID(), userId, jobId, lookId, NOW, NOW.plus(Duration.ofHours(24)));

		processor(null).insertNotificationEvent(event);

		assertThat(eventStore.findById(event.id())).isPresent();
		assertThat(deliveryTokens(event.id())).isEmpty();
	}

	private List<String> deliveryTokens(UUID eventId) {
		return jdbc.queryForList(
			"SELECT device_identity FROM generation_notification_delivery WHERE event_id = ?",
			String.class,
			eventId);
	}

	private GenerationWorkProcessor processor() {
		return processor(registrations);
	}

	private GenerationWorkProcessor processor(DeviceRegistrationStore deviceRegistrations) {
		ObjectStore objectStore = mock(ObjectStore.class);
		return new GenerationWorkProcessor(
			mock(GenerationStore.class),
			mock(LookStore.class),
			mock(NotificationStore.class),
			mock(GenerationWorkQueue.class),
			mock(VtonProvider.class),
			mock(AuthorizedGenerationMediaResolver.class),
			mock(PrivacyApprovedInputTransfer.class),
			mock(QualityGate.class),
			mock(GenerationMediaStore.class),
			objectStore,
			Clock.fixed(NOW, ZoneOffset.UTC),
			null,
			null,
			null,
			null,
			null,
			eventStore,
			() -> 0.0d,
			mock(GlobalCostCircuitBreaker.class),
			mock(ProviderHealthGate.class),
			null,
			deviceRegistrations);
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
}
