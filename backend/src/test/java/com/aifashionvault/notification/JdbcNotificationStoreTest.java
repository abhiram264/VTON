package com.aifashionvault.notification;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.NotificationId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
class JdbcNotificationStoreTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-04-02T12:00:00Z");

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

	private JdbcNotificationStore store;

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
		jdbc.update("TRUNCATE TABLE notification, users CASCADE");
		store = new JdbcNotificationStore(dataSource);
	}

	@Test
	void lookReadyRoundTripPayloadHasOnlyLookId() {
		UserId userId = insertUser();
		NotificationId id = new NotificationId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
		LookId lookId = new LookId(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
		Notification written = Notification.lookReady(id, userId, lookId, NOW);

		store.insert(written);
		Notification loaded = store.findById(id).orElseThrow();

		assertThat(loaded.id()).isEqualTo(id);
		assertThat(loaded.userId()).isEqualTo(userId);
		assertThat(loaded.type()).isEqualTo("LOOK_READY");
		assertThat(loaded.payload()).hasSize(1).containsEntry("lookId", lookId.value().toString());
		assertThat(loaded.readAt()).isNull();
		assertThat(loaded.createdAt()).isEqualTo(NOW);
		assertSafePayload(loaded);
		assertThat(jsonbKeys(id)).containsExactly("lookId");
	}
	@Test
	void generationFailedRoundTripPayloadHasOnlyGenerationId() {
		UserId userId = insertUser();
		NotificationId id = new NotificationId(UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"));
		GenerationJobId generationJobId = new GenerationJobId(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"));
		Notification written = Notification.generationFailed(id, userId, generationJobId, NOW);

		store.insert(written);
		Notification loaded = store.findById(id).orElseThrow();

		assertThat(loaded.id()).isEqualTo(id);
		assertThat(loaded.userId()).isEqualTo(userId);
		assertThat(loaded.type()).isEqualTo("GENERATION_FAILED");
		assertThat(loaded.payload())
			.containsOnlyKeys("generationId")
			.containsEntry("generationId", generationJobId.value().toString());
		assertThat(loaded.readAt()).isNull();
		assertThat(loaded.createdAt()).isEqualTo(NOW);
		assertSafePayload(loaded);
		assertThat(jsonbKeys(id)).containsExactly("generationId");
	}

	@Test
	void generationFailedRoundTripPayloadOmitsInternalReason() {
		UserId userId = insertUser();
		NotificationId id = new NotificationId(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"));
		GenerationJobId generationJobId = new GenerationJobId(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"));
		Notification written = Notification.generationFailed(id, userId, generationJobId,
			com.aifashionvault.shared.TerminalReason.PROVIDER_RATE_LIMIT_EXHAUSTED.name(), NOW);
		store.insert(written);
		Notification loaded = store.findById(id).orElseThrow();
		assertThat(loaded.payload()).containsOnlyKeys("generationId")
			.containsEntry("generationId", generationJobId.value().toString());
		assertThat(jsonbKeys(id)).containsExactly("generationId");
	}

	@Test
	void generationFailedEventRoundTripPreservesReason() {
		UserId userId = insertUser();
		GenerationJobId generationId = insertJob(userId);
		JdbcNotificationEventStore eventStore = new JdbcNotificationEventStore(dataSource);
		UUID eventId = UUID.randomUUID();
		NotificationEvent written = NotificationEvent.generationFailed(
			eventId,
			userId,
			generationId,
			com.aifashionvault.shared.TerminalReason.QUALITY_REJECTED.name(),
			NOW,
			NOW.plus(Duration.ofDays(1)));

		assertThat(eventStore.insert(written)).isTrue();

		NotificationEvent loaded = eventStore.findById(eventId).orElseThrow();
		assertThat(loaded.reason()).isEqualTo(com.aifashionvault.shared.TerminalReason.QUALITY_REJECTED.name());
		assertThat(loaded.pushPayload())
			.containsOnlyKeys("type", "generationId")
			.containsEntry("generationId", generationId.value().toString());
		assertThat(eventStore.ensureDelivery(eventId, "device-event-test")).isTrue();
		assertThat(eventStore.claimDue(NOW).orElseThrow().event().reason())
			.isEqualTo(com.aifashionvault.shared.TerminalReason.QUALITY_REJECTED.name());
	}

	@Test
	void generationFailedEventWithoutReasonRoundTripsNull() {
		UserId userId = insertUser();
		GenerationJobId generationId = insertJob(userId);
		JdbcNotificationEventStore eventStore = new JdbcNotificationEventStore(dataSource);
		UUID eventId = UUID.randomUUID();
		NotificationEvent written = NotificationEvent.generationFailed(eventId, userId, generationId, NOW, NOW.plus(Duration.ofDays(1)));

		assertThat(eventStore.insert(written)).isTrue();

		NotificationEvent loaded = eventStore.findById(eventId).orElseThrow();
		assertThat(loaded.reason()).isNull();
		assertThat(loaded.pushPayload()).containsOnlyKeys("type", "generationId");
		assertThat(eventStore.ensureDelivery(eventId, "device-event-test")).isTrue();
		assertThat(eventStore.claimDue(NOW).orElseThrow().event().reason()).isNull();
	}

	@Test
	void findByIdReturnsEmptyWhenMissing() {
		assertThat(store.findById(new NotificationId(UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"))))
			.isEmpty();
	}

	@Test
	void unsafeNotificationCannotBeConstructed() {
		NotificationId id = new NotificationId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
		UserId userId = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));

		assertThatThrownBy(() -> new Notification(
			id,
			userId,
			"LOOK_READY",
			Map.of("lookId", "https://example.invalid/looks/l.jpg"),
			null,
			NOW))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Notification(
			id,
			userId,
			"INFO",
			Map.of("providerJobId", "remote-42"),
			null,
			NOW))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Notification(
			id,
			userId,
			"INFO",
			Map.of("storageKey", "users/u/looks/l.jpg"),
			null,
			NOW))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Notification(
			id,
			userId,
			"INFO",
			Map.of("objectKey", "users/u/looks/l.jpg"),
			null,
			NOW))
			.isInstanceOf(IllegalArgumentException.class);
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

	private List<String> jsonbKeys(NotificationId id) {
		return jdbc.queryForList(
			"SELECT jsonb_object_keys(payload) FROM notification WHERE id = ?",
			String.class,
			id.value());
	}

	private static void assertSafePayload(Notification notification) {
		assertThat(notification.payload().keySet())
			.doesNotContain(
				"providerJobId",
				"provider_job_id",
				"storageKey",
				"storage_key",
				"objectKey",
				"object_key");
		assertThat(notification.payload().values())
			.noneMatch(JdbcNotificationStoreTest::isHttpUrl);
	}

	private static boolean isHttpUrl(String value) {
		if (value == null) {
			return false;
		}
		String lower = value.toLowerCase(Locale.ROOT);
		return lower.startsWith("http://") || lower.startsWith("https://");
	}
}
