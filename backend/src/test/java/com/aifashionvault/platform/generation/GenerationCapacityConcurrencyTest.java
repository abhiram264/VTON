package com.aifashionvault.platform.generation;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.aifashionvault.generation.GenerationAttempt;
import com.aifashionvault.generation.GenerationJob;
import com.aifashionvault.generation.JdbcGenerationStore;
import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.shared.ClientRequestId;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
class GenerationCapacityConcurrencyTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final String PROVIDER = "example-provider";

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

	private JdbcGenerationStore generationStore;
	private JdbcGenerationCapacityLeaseStore capacityStore;

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
		jdbc.update(
			"TRUNCATE TABLE quality_result, generation_callback_inbox, generation_work_outbox, "
				+ "generation_quota_reservation, generation_notification_delivery, "
				+ "generation_notification_event, generation_provider_capacity_slot, "
				+ "generation_attempt, generation_render_step_item, generation_render_step, "
				+ "generation_job_product, generation_job, vault_item, studio_item, studio_session, "
				+ "avatar_image, avatar_profile, users CASCADE");
		generationStore = new JdbcGenerationStore(dataSource, CLOCK);
		capacityStore = new JdbcGenerationCapacityLeaseStore(dataSource);
	}

	@Test
	void durableSlotPreventsExcessCrossInstanceSubmissions() throws Exception {
		UserId userId = insertUser();
		GenerationAttempt first = insertAttempt(userId);
		GenerationAttempt second = insertAttempt(userId);
		Instant deadline = NOW.plusSeconds(30);

		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		AtomicBoolean firstWon = new AtomicBoolean();
		AtomicBoolean secondWon = new AtomicBoolean();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		try {
			pool.submit(() -> runAcquire(first.id(), deadline, ready, start, firstWon, failure));
			pool.submit(() -> runAcquire(second.id(), deadline, ready, start, secondWon, failure));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(failure.get()).isNull();
		assertThat(firstWon.get() != secondWon.get()).isTrue();
		Integer active = jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_provider_capacity_slot "
				+ "WHERE provider_key = ? AND released_at IS NULL",
			Integer.class,
			PROVIDER);
		assertThat(active).isEqualTo(1);
		assertThat(capacityStore.getClass().getDeclaredFields())
			.extracting(field -> field.getType().getSimpleName())
			.doesNotContain("Semaphore");
	}

	@Test
	void expiredLeaseCannotOutliveAttemptDeadline() {
		UserId userId = insertUser();
		GenerationAttempt first = insertAttempt(userId);
		GenerationAttempt second = insertAttempt(userId);
		Instant deadline = NOW.plusSeconds(30);

		assertThat(capacityStore.tryAcquire(PROVIDER, first.id(), NOW, deadline, 1)).isTrue();
		Instant storedExpiry = jdbc.queryForObject(
			"SELECT lease_expires_at FROM generation_provider_capacity_slot "
				+ "WHERE generation_attempt_id = ? AND released_at IS NULL",
			Timestamp.class,
			first.id().value()).toInstant();
		assertThat(storedExpiry).isEqualTo(deadline);

		assertThat(capacityStore.tryAcquire(PROVIDER, second.id(), deadline, deadline.plusSeconds(30), 1)).isTrue();
		Integer released = jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_provider_capacity_slot "
				+ "WHERE generation_attempt_id = ? AND released_at IS NOT NULL",
			Integer.class,
			first.id().value());
		assertThat(released).isEqualTo(1);
		Instant secondExpiry = jdbc.queryForObject(
			"SELECT lease_expires_at FROM generation_provider_capacity_slot "
				+ "WHERE generation_attempt_id = ? AND released_at IS NULL",
			Timestamp.class,
			second.id().value()).toInstant();
		assertThat(secondExpiry).isEqualTo(deadline.plusSeconds(30));
	}

	private void runAcquire(
		GenerationAttemptId attemptId,
		Instant deadline,
		CountDownLatch ready,
		CountDownLatch start,
		AtomicBoolean won,
		AtomicReference<Throwable> failure
	) {
		ready.countDown();
		try {
			if (!start.await(10, TimeUnit.SECONDS)) {
				failure.compareAndSet(null, new IllegalStateException("start timed out"));
				return;
			}
			won.set(capacityStore.tryAcquire(PROVIDER, attemptId, NOW, deadline, 1));
		}
		catch (Throwable thrown) {
			failure.compareAndSet(null, thrown);
		}
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

	private GenerationAttempt insertAttempt(UserId userId) {
		GenerationJob job = new GenerationJob(
			new GenerationJobId(UUID.randomUUID()),
			userId,
			null,
			GenerationJob.QUEUED,
			"try-on",
			new ClientRequestId("req-" + UUID.randomUUID()),
			NOW,
			null,
			null,
			List.of(),
			null,
			null);
		generationStore.insertJob(job);
		GenerationAttemptId attemptId = new GenerationAttemptId(UUID.randomUUID());
		GenerationAttempt attempt = new GenerationAttempt(
			attemptId,
			job.id(),
			PROVIDER,
			null,
			null,
			1,
			NOW,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			SubmissionKey.forAttempt(attemptId),
			null,
			false,
			null,
			null);
		generationStore.insertAttempt(attempt);
		return attempt;
	}
}
