package com.aifashionvault.platform.generation;

import java.math.BigDecimal;
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
import com.aifashionvault.generation.vton.ProviderOutputRef;
import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.shared.ClientRequestId;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
class GenerationCancelCompleteConcurrencyTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

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

	private JdbcGenerationStore store;

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
		store = new JdbcGenerationStore(dataSource, CLOCK);
	}

	@Test
	void cancelVersusCompleteHasExactlyOneTerminalWinner() throws Exception {
		UserId userId = insertUser();
		GenerationJob job = queuedJob(userId);
		store.insertJob(job);
		GenerationAttempt pending = pendingAttempt(job.id());
		store.insertAttempt(pending);
		Instant when = NOW.plusSeconds(2);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		AtomicBoolean cancelWon = new AtomicBoolean();
		AtomicBoolean completeWon = new AtomicBoolean();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		try {
			pool.submit(() -> {
				ready.countDown();
				try {
					if (!start.await(10, TimeUnit.SECONDS)) {
						failure.compareAndSet(null, new IllegalStateException("start timed out"));
						return;
					}
					cancelWon.set(store.cancelIfActive(job.id(), when));
				}
				catch (Throwable thrown) {
					failure.compareAndSet(null, thrown);
				}
			});
			pool.submit(() -> {
				ready.countDown();
				try {
					if (!start.await(10, TimeUnit.SECONDS)) {
						failure.compareAndSet(null, new IllegalStateException("start timed out"));
						return;
					}
					completeWon.set(store.completeTerminalIfActive(finishedSuccess(pending, when), when));
				}
				catch (Throwable thrown) {
					failure.compareAndSet(null, thrown);
				}
			});
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(failure.get()).isNull();
		assertThat(cancelWon.get() != completeWon.get()).isTrue();
		GenerationJob loaded = store.findJob(job.id()).orElseThrow();
		assertThat(loaded.completedAt()).isEqualTo(when);
		if (cancelWon.get()) {
			assertThat(loaded.status()).isEqualTo(GenerationJob.FAILED);
			assertThat(loaded.terminalReason()).isEqualTo(GenerationJob.CANCELLED_BY_USER);
			assertThat(loaded.cancelledAt()).isEqualTo(when);
		}
		else {
			assertThat(loaded.status()).isEqualTo(GenerationJob.COMPLETED);
			assertThat(loaded.cancelledAt()).isNull();
			assertThat(loaded.terminalReason()).isNull();
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

	private static GenerationJob queuedJob(UserId userId) {
		return new GenerationJob(
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
	}

	private static GenerationAttempt pendingAttempt(GenerationJobId jobId) {
		GenerationAttemptId attemptId = new GenerationAttemptId(UUID.randomUUID());
		return new GenerationAttempt(
			attemptId,
			jobId,
			"example-provider",
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
	}

	private static GenerationAttempt finishedSuccess(GenerationAttempt pending, Instant completedAt) {
		return new GenerationAttempt(
			pending.id(),
			pending.generationJobId(),
			pending.provider(),
			"example-model",
			"provider-job-1",
			pending.attemptNumber(),
			pending.startedAt(),
			completedAt,
			1800,
			new BigDecimal("2.0"),
			new BigDecimal("0.04"),
			"USD",
			null,
			"SUCCESS",
			pending.submissionKey(),
			NOW,
			false,
			new StorageKey("temp/user/generation/" + pending.id().value() + ".jpg"),
			new ProviderOutputRef("provider-output-1"));
	}
}
