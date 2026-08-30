package com.aifashionvault.generation;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.List;
import java.util.Map;
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

import com.aifashionvault.generation.vton.ProviderJobId;
import com.aifashionvault.generation.vton.ProviderOutputRef;
import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.AvatarProfileId;
import com.aifashionvault.shared.ClientRequestId;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.MediaAssetId;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.QualityResultId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.TerminalReason;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.autoconfigure.exclude=")
class JdbcGenerationStoreTest {

	private static final String POSTGRES_IMAGE = "postgres:18";
	private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(5);
	private static final Instant NOW = Instant.parse("2026-04-02T12:00:00Z");
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
	void findJobWhenMissingReturnsEmpty() {
		assertThat(store.findJob(new GenerationJobId(UUID.randomUUID()))).isEmpty();
	}

	@Test
	void insertJobPersistsProductsAndReloadsThem() {
		UserId userId = insertUser();
		AvatarImageId avatarImageId = insertAvatar(userId);
		VaultItemId firstProduct = insertVaultItem(userId);
		VaultItemId secondProduct = insertVaultItem(userId);
		GenerationJob job = job(
			userId,
			avatarImageId,
			List.of(firstProduct, secondProduct),
			new ClientRequestId("req-job-1"));

		store.insertJob(job);

		GenerationJob loaded = store.findJob(job.id()).orElseThrow();
		assertThat(loaded.id()).isEqualTo(job.id());
		assertThat(loaded.userId()).isEqualTo(userId);
		assertThat(loaded.studioSessionId()).isNull();
		assertThat(loaded.status()).isEqualTo("QUEUED");
		assertThat(loaded.requestedMode()).isEqualTo("try-on");
		assertThat(loaded.clientRequestId()).isEqualTo(job.clientRequestId());
		assertThat(loaded.createdAt()).isEqualTo(NOW);
		assertThat(loaded.avatarImageId()).isEqualTo(avatarImageId);
		assertThat(loaded.productVaultItemIds()).containsExactlyInAnyOrder(firstProduct, secondProduct);
		assertThat(jdbc.queryForList(
			"SELECT vault_item_id FROM generation_job_product WHERE generation_job_id = ?",
			UUID.class,
			job.id().value()))
			.containsExactlyInAnyOrder(firstProduct.value(), secondProduct.value());
	}
	@Test
	void insertJobPersistsRetryStateAndPolicySnapshot() {
		UserId userId = insertUser();
		Instant retryNotBeforeAt = NOW.plusSeconds(20);
		RetryPolicySnapshot snapshot = RetryPolicySnapshot.forStepCount(1);
		GenerationJob job = retryingJob(userId, retryNotBeforeAt, snapshot);

		store.insertJob(job);

		GenerationJob loaded = store.findJob(job.id()).orElseThrow();
		assertThat(loaded.retryNotBeforeAt()).isEqualTo(retryNotBeforeAt);
		assertThat(loaded.retryPolicySnapshot()).isEqualTo(snapshot);
		assertThat(jdbc.queryForObject(
			"SELECT retry_policy_snapshot FROM generation_job WHERE id = ?",
			String.class,
			job.id().value())).isEqualTo(snapshot.serialize());
	}
	@Test
	void malformedPersistedRetryPolicyCannotGrantARecoveryClaim() {
		UserId userId = insertUser();
		GenerationJob job = retryingJob(userId, NOW, RetryPolicySnapshot.forStepCount(1));
		GenerationAttempt predecessor = pendingAttempt(job.id(), 1);
		store.insertJob(job);
		store.insertAttempt(predecessor);
		jdbc.update(
			"UPDATE generation_job SET retry_policy_snapshot = ? WHERE id = ?",
			"malformed-retry-policy",
			job.id().value());

		assertThat(store.findJob(job.id()).orElseThrow().retryPolicySnapshot()).isNull();
		GenerationAttempt next = pendingAttempt(job.id(), 2);
		assertThat(store.claimDueRetry(job.id(), job.statusVersion(), NOW, next)).isFalse();
		assertThat(store.findJob(job.id()).orElseThrow().retryNotBeforeAt()).isEqualTo(NOW);
		assertThat(store.listAttempts(job.id())).extracting(GenerationAttempt::id)
			.containsExactly(predecessor.id());
	}
	@Test
	void nullPersistedRetryPolicyCannotGrantARecoveryClaim() {
		UserId userId = insertUser();
		GenerationJob job = retryingJob(userId, NOW, RetryPolicySnapshot.forStepCount(1));
		GenerationAttempt predecessor = pendingAttempt(job.id(), 1);
		store.insertJob(job);
		store.insertAttempt(predecessor);
		jdbc.update(
			"UPDATE generation_job SET retry_policy_snapshot = NULL WHERE id = ?",
			job.id().value());

		assertThat(store.findJob(job.id()).orElseThrow().retryPolicySnapshot()).isNull();
		GenerationAttempt next = pendingAttempt(job.id(), 2);
		assertThat(store.claimDueRetry(job.id(), job.statusVersion(), NOW, next)).isFalse();
		GenerationJob refused = store.findJob(job.id()).orElseThrow();
		assertThat(refused.status()).isEqualTo(GenerationJob.RETRYING);
		assertThat(refused.retryNotBeforeAt()).isEqualTo(NOW);
		assertThat(refused.statusVersion()).isEqualTo(job.statusVersion());
		assertThat(store.listAttempts(job.id())).extracting(GenerationAttempt::id)
			.containsExactly(predecessor.id());
	}
	@Test
	void emptyPersistedRetryPolicyCannotGrantARecoveryClaim() {
		UserId userId = insertUser();
		GenerationJob job = retryingJob(userId, NOW, RetryPolicySnapshot.forStepCount(1));
		GenerationAttempt predecessor = pendingAttempt(job.id(), 1);
		store.insertJob(job);
		store.insertAttempt(predecessor);
		jdbc.update(
			"UPDATE generation_job SET retry_policy_snapshot = ? WHERE id = ?",
			"   ",
			job.id().value());

		assertThat(store.findJob(job.id()).orElseThrow().retryPolicySnapshot()).isNull();
		GenerationAttempt next = pendingAttempt(job.id(), 2);
		assertThat(store.claimDueRetry(job.id(), job.statusVersion(), NOW, next)).isFalse();
		GenerationJob refused = store.findJob(job.id()).orElseThrow();
		assertThat(refused.status()).isEqualTo(GenerationJob.RETRYING);
		assertThat(refused.retryNotBeforeAt()).isEqualTo(NOW);
		assertThat(store.listAttempts(job.id())).extracting(GenerationAttempt::id)
			.containsExactly(predecessor.id());
	}
	@Test
	void persistedRetryPolicyBelowTotalStepsLoadsAsAbsentAndCannotGrantRecoveryClaim() {
		UserId userId = insertUser();
		RetryPolicySnapshot restrictive = new RetryPolicySnapshot(
			2,
			1,
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
		GenerationJob job = retryingJob(userId, NOW, restrictive);
		GenerationAttempt predecessor = pendingAttempt(job.id(), 1);
		store.insertJob(job);
		store.insertAttempt(predecessor);
		jdbc.update(
			"UPDATE generation_job SET total_steps = ?, retry_policy_snapshot = ? WHERE id = ?",
			3,
			restrictive.serialize(),
			job.id().value());

		GenerationJob loaded = store.findJob(job.id()).orElseThrow();
		assertThat(loaded.totalSteps()).isEqualTo(3);
		assertThat(loaded.retryPolicySnapshot()).isNull();
		assertThat(store.resolveRetryPolicy(job.id()))
			.isEqualTo(new RetryPolicyResolution(RetryPolicyResolution.State.INVALID, null));
		GenerationAttempt next = pendingAttempt(job.id(), 2);
		assertThat(store.claimDueRetry(job.id(), job.statusVersion(), NOW, next)).isFalse();
		assertThat(store.findJob(job.id()).orElseThrow().retryNotBeforeAt()).isEqualTo(NOW);
		assertThat(store.listAttempts(job.id())).extracting(GenerationAttempt::id)
			.containsExactly(predecessor.id());
	}





	@Test
	void insertAttemptPersistsSubmissionKeyBeforeProviderFieldsAreRequired() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt attempt = pendingAttempt(job.id(), 1);

		store.insertAttempt(attempt);

		GenerationAttempt loaded = store.findAttempt(attempt.id()).orElseThrow();
		assertThat(loaded.submissionKey()).isEqualTo(attempt.submissionKey());
		assertThat(loaded.providerJobId()).isNull();
		assertThat(loaded.submitOwnedAt()).isNull();
		assertThat(loaded.tempStorageKey()).isNull();
		assertThat(loaded.providerOutputRef()).isNull();
		assertThat(jdbc.queryForObject(
			"SELECT submission_key FROM generation_attempt WHERE id = ?",
			UUID.class,
			attempt.id().value())).isEqualTo(attempt.submissionKey().value());
		assertThat(jdbc.queryForObject(
			"SELECT provider_job_id FROM generation_attempt WHERE id = ?",
			String.class,
			attempt.id().value())).isNull();
	}
	@Test
	void attemptProvenanceRoundTripsAndCompletionPreservesImmutableFields() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttemptId attemptId = new GenerationAttemptId(UUID.randomUUID());
		SubmissionKey submissionKey = SubmissionKey.forAttempt(attemptId);
		GenerationAttempt pending = new GenerationAttempt(
			attemptId,
			job.id(),
			"provider-original",
			"route-original",
			null,
			1,
			NOW,
			null,
			null,
			null,
			new BigDecimal("8.75"),
			"USD",
			null,
			null,
			submissionKey,
			null,
			false,
			null,
			null,
			null,
			0,
			null,
			NOW.plusSeconds(300),
			null,
			"quality-policy-v1",
			"pricing-catalog-v1",
			new BigDecimal("12.5"),
			new BigDecimal("0.70"),
			new BigDecimal("8.75"),
			null,
			"ESTIMATED",
			null,
			NOW.minusSeconds(1),
			"credits");
		store.insertAttempt(pending);

		Instant completedAt = NOW.plusSeconds(2);
		Instant pricingObservedAt = NOW.plusSeconds(1);
		GenerationAttempt completion = new GenerationAttempt(
			attemptId,
			job.id(),
			"provider-replacement",
			"route-replacement",
			"provider-job-replacement",
			9,
			NOW,
			completedAt,
			1800,
			new BigDecimal("12.5"),
			new BigDecimal("99.99"),
			"EUR",
			"QUALITY_REJECTED",
			"SUCCESS",
			new SubmissionKey(UUID.randomUUID()),
			NOW.plusSeconds(1),
			true,
			new StorageKey("temp/generation/" + attemptId.value() + ".jpg"),
			new ProviderOutputRef("provider-output"),
			pending.renderStepId(),
			3,
			NOW.plusSeconds(1),
			NOW.plusSeconds(400),
			NOW.plusSeconds(50),
			"quality-policy-v2",
			"pricing-catalog-v2",
			new BigDecimal("14.0"),
			new BigDecimal("0.80"),
			new BigDecimal("11.20"),
			new BigDecimal("10.50"),
			"FINAL",
			new BigDecimal("13.125"),
			pricingObservedAt,
			"images");

		store.completeAttempt(completion);

		GenerationAttempt loaded = store.findAttempt(attemptId).orElseThrow();
		assertThat(loaded.provider()).isEqualTo(pending.provider());
		assertThat(loaded.model()).isEqualTo(pending.model());
		assertThat(loaded.providerJobId()).isNull();
		assertThat(loaded.attemptNumber()).isEqualTo(pending.attemptNumber());
		assertThat(loaded.submissionKey()).isEqualTo(pending.submissionKey());
		assertThat(loaded.completedAt()).isEqualTo(completedAt);
		assertThat(loaded.latencyMs()).isEqualTo(completion.latencyMs());
		assertThat(loaded.creditsUsed()).isEqualByComparingTo(completion.creditsUsed());
		assertThat(loaded.estimatedCost()).isEqualByComparingTo(pending.estimatedCost());
		assertThat(loaded.currency()).isEqualTo(pending.currency());
		assertThat(loaded.failureCode()).isEqualTo(completion.failureCode());
		assertThat(loaded.technicalOutcome()).isEqualTo(completion.technicalOutcome());
		assertThat(loaded.tempStorageKey()).isEqualTo(completion.tempStorageKey());
		assertThat(loaded.providerOutputRef()).isEqualTo(completion.providerOutputRef());
		assertThat(loaded.pricingCatalogVersion()).isEqualTo(pending.pricingCatalogVersion());
		assertThat(loaded.estimatedUnits()).isEqualByComparingTo(pending.estimatedUnits());
		assertThat(loaded.unitPrice()).isEqualByComparingTo(pending.unitPrice());
		assertThat(loaded.estimatedCostCeiling()).isEqualByComparingTo(pending.estimatedCostCeiling());
		assertThat(loaded.actualCost()).isEqualByComparingTo(completion.actualCost());
		assertThat(loaded.costStatus()).isEqualTo(completion.costStatus());
		assertThat(loaded.actualBilledUnits()).isEqualByComparingTo(completion.actualBilledUnits());
		assertThat(loaded.pricingObservedAt()).isEqualTo(pending.pricingObservedAt());
		assertThat(loaded.billingUnit()).isEqualTo(pending.billingUnit());
	}

	@Test
	void concurrentTryAcquireSubmitOwnershipHasExactlyOneWinner() throws Exception {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt attempt = pendingAttempt(job.id(), 1);
		store.insertAttempt(attempt);

		Instant firstOwnedAt = NOW;
		Instant secondOwnedAt = NOW.plusSeconds(1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		AtomicBoolean firstWon = new AtomicBoolean();
		AtomicBoolean secondWon = new AtomicBoolean();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		try {
			pool.submit(() -> runAcquire(attempt.id(), firstOwnedAt, ready, start, firstWon, failure));
			pool.submit(() -> runAcquire(attempt.id(), secondOwnedAt, ready, start, secondWon, failure));
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
		GenerationAttempt owned = store.findAttempt(attempt.id()).orElseThrow();
		assertThat(owned.submitOwnedAt()).isIn(firstOwnedAt, secondOwnedAt);
		assertThat(store.tryAcquireSubmitOwnership(attempt.id(), NOW.plusSeconds(5))).isFalse();
	}

	@Test
	void tryAcquireSubmitOwnershipFailsAfterCancelledDeletedOrCompleted() {
		UserId userId = insertUser();
		GenerationJob cancelled = insertQueuedJob(userId);
		GenerationJob deleted = insertQueuedJob(userId);
		GenerationJob completed = insertQueuedJob(userId);
		GenerationAttempt cancelledAttempt = pendingAttempt(cancelled.id(), 1);
		GenerationAttempt deletedAttempt = pendingAttempt(deleted.id(), 1);
		GenerationAttempt completedAttempt = pendingAttempt(completed.id(), 1);
		store.insertAttempt(cancelledAttempt);
		store.insertAttempt(deletedAttempt);
		store.insertAttempt(completedAttempt);

		store.markCancelled(cancelled.id(), NOW.plusSeconds(1));
		store.markSubjectDeleted(deleted.id(), NOW.plusSeconds(2));
		store.markJobCompleted(completed.id(), NOW.plusSeconds(3));

		assertThat(store.tryAcquireSubmitOwnership(cancelledAttempt.id(), NOW.plusSeconds(4))).isFalse();
		assertThat(store.tryAcquireSubmitOwnership(deletedAttempt.id(), NOW.plusSeconds(5))).isFalse();
		assertThat(store.tryAcquireSubmitOwnership(completedAttempt.id(), NOW.plusSeconds(6))).isFalse();
		assertThat(store.findAttempt(cancelledAttempt.id()).orElseThrow().submitOwnedAt()).isNull();
		assertThat(store.findAttempt(deletedAttempt.id()).orElseThrow().submitOwnedAt()).isNull();
		assertThat(store.findAttempt(completedAttempt.id()).orElseThrow().submitOwnedAt()).isNull();
	}

	@Test
	void completeTerminalPersistsAttemptAndJob() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt pending = pendingAttempt(job.id(), 1);
		store.insertAttempt(pending);

		Instant completedAt = NOW.plusSeconds(2);
		GenerationAttempt completed = new GenerationAttempt(
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

		store.completeTerminal(completed, completedAt);

		GenerationAttempt loadedAttempt = store.findAttempt(pending.id()).orElseThrow();
		GenerationJob loadedJob = store.findJob(job.id()).orElseThrow();
		assertThat(loadedAttempt.completedAt()).isEqualTo(completedAt);
		assertThat(loadedAttempt.technicalOutcome()).isEqualTo("SUCCESS");
		assertThat(loadedAttempt.tempStorageKey()).isEqualTo(completed.tempStorageKey());
		assertThat(loadedJob.completedAt()).isEqualTo(completedAt);
		assertThat(loadedJob.status()).isEqualTo("COMPLETED");
	}

	@Test
	void completeTerminalIfActiveSecondCallReturnsFalseAndDoesNotRewriteOutcome() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt pending = pendingAttempt(job.id(), 1);
		store.insertAttempt(pending);

		Instant successAt = NOW.plusSeconds(2);
		GenerationAttempt success = finishedAttempt(pending, successAt, "SUCCESS", null);
		assertThat(store.completeTerminalIfActive(success, successAt)).isTrue();

		Instant failedAt = NOW.plusSeconds(3);
		GenerationAttempt failed = finishedAttempt(pending, failedAt, "FAILED", "PROVIDER_ERROR");
		assertThat(store.completeTerminalIfActive(failed, failedAt)).isFalse();

		GenerationAttempt loadedAttempt = store.findAttempt(pending.id()).orElseThrow();
		GenerationJob loadedJob = store.findJob(job.id()).orElseThrow();
		assertThat(loadedAttempt.technicalOutcome()).isEqualTo("SUCCESS");
		assertThat(loadedAttempt.failureCode()).isNull();
		assertThat(loadedAttempt.completedAt()).isEqualTo(successAt);
		assertThat(loadedJob.completedAt()).isEqualTo(successAt);
		assertThat(loadedJob.status()).isEqualTo(GenerationJob.COMPLETED);
		assertThat(loadedJob.terminalReason()).isNull();
	}


	@Test
	void completeTerminalIfActiveFailureSetsFailedAndTerminalReason() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt pending = pendingAttempt(job.id(), 1);
		store.insertAttempt(pending);

		Instant failedAt = NOW.plusSeconds(2);
		GenerationAttempt failed = finishedAttempt(pending, failedAt, "SUCCESS", "QUALITY_REJECTED");
		assertThat(store.completeTerminalIfActive(failed, failedAt)).isTrue();

		GenerationJob loadedJob = store.findJob(job.id()).orElseThrow();
		assertThat(loadedJob.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(loadedJob.terminalReason()).isEqualTo("QUALITY_REJECTED");
		assertThat(loadedJob.completedAt()).isEqualTo(failedAt);
		assertThat(loadedJob.cancelledAt()).isNull();
		assertThat(store.completeTerminalIfActive(
			finishedAttempt(pending, failedAt.plusSeconds(1), "SUCCESS", null),
			failedAt.plusSeconds(1))).isFalse();
		assertThat(store.findJob(job.id()).orElseThrow().status()).isEqualTo(GenerationJob.FAILED);
		assertThat(store.findJob(job.id()).orElseThrow().terminalReason()).isEqualTo("QUALITY_REJECTED");
	}
	@Test
	void scheduleRetryCompletesAttemptAndInsertsNextAtomically() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt pending = pendingAttempt(job.id(), 1);
		store.insertAttempt(pending);
		GenerationAttempt completed = finishedAttempt(pending, NOW.plusSeconds(2), "FAILED", "RETRYABLE");
		GenerationAttempt next = pendingAttempt(job.id(), 2);

		store.scheduleRetry(completed, next);

		assertThat(store.findAttempt(pending.id()).orElseThrow().technicalOutcome()).isEqualTo("FAILED");
		assertThat(store.findAttempt(next.id())).isPresent();
		assertThat(store.listAttempts(job.id())).extracting(GenerationAttempt::id)
			.containsExactly(pending.id(), next.id());

		GenerationAttempt otherPending = pendingAttempt(job.id(), 3);
		store.insertAttempt(otherPending);
		GenerationAttempt otherCompleted = finishedAttempt(otherPending, NOW.plusSeconds(4), "FAILED", "RETRYABLE");
		GenerationAttempt missingJobNext = pendingAttempt(new GenerationJobId(UUID.randomUUID()), 4);
		assertThatThrownBy(() -> store.scheduleRetry(otherCompleted, missingJobNext))
			.isInstanceOf(org.springframework.dao.DataAccessException.class);
		assertThat(store.findAttempt(otherPending.id()).orElseThrow().completedAt()).isNull();
		assertThat(store.findAttempt(otherPending.id()).orElseThrow().technicalOutcome()).isNull();
		assertThat(store.findAttempt(missingJobNext.id())).isEmpty();
	}
	@Test
	void scheduleRetryIfActivePersistsRetryStateWithoutCreatingSuccessor() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt pending = pendingAttempt(job.id(), 1);
		store.insertAttempt(pending);
		GenerationAttempt completed = finishedAttempt(pending, NOW.plusSeconds(2), "FAILED", "RETRYABLE");
		Instant retryNotBeforeAt = NOW.plusSeconds(20);

		assertThat(store.scheduleRetryIfActive(completed, retryNotBeforeAt)).isTrue();

		GenerationJob loaded = store.findJob(job.id()).orElseThrow();
		assertThat(loaded.status()).isEqualTo(GenerationJob.RETRYING);
		assertThat(loaded.retryNotBeforeAt()).isEqualTo(retryNotBeforeAt);
		assertThat(loaded.statusVersion()).isEqualTo(job.statusVersion() + 1);
		assertThat(store.listAttempts(job.id())).extracting(GenerationAttempt::id)
			.containsExactly(pending.id());
		assertThat(store.findAttempt(pending.id()).orElseThrow().completedAt())
			.isEqualTo(completed.completedAt());
	}
	@Test
	void fencedScheduleRetryRejectsStaleVersionAndOldPredecessor() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt first = pendingAttempt(job.id(), 1);
		store.insertAttempt(first);
		GenerationAttempt completedFirst = finishedAttempt(first, NOW.plusSeconds(2), "FAILED", "RETRYABLE");
		Instant retryNotBeforeAt = NOW.plusSeconds(20);

		assertThat(store.scheduleRetryIfActive(completedFirst, job.statusVersion() - 1, retryNotBeforeAt)).isFalse();
		GenerationJob afterStaleVersion = store.findJob(job.id()).orElseThrow();
		assertThat(afterStaleVersion.status()).isEqualTo(job.status());
		assertThat(afterStaleVersion.statusVersion()).isEqualTo(job.statusVersion());
		assertThat(afterStaleVersion.retryNotBeforeAt()).isEqualTo(job.retryNotBeforeAt());
		assertThat(store.findAttempt(first.id()).orElseThrow().completedAt()).isNull();

		GenerationAttempt second = pendingAttempt(job.id(), 2);
		store.insertAttempt(second);
		GenerationJob beforeOldPredecessor = store.findJob(job.id()).orElseThrow();
		assertThat(store.scheduleRetryIfActive(
			completedFirst,
			beforeOldPredecessor.statusVersion(),
			retryNotBeforeAt)).isFalse();
		GenerationJob afterOldPredecessor = store.findJob(job.id()).orElseThrow();
		assertThat(afterOldPredecessor.status()).isEqualTo(beforeOldPredecessor.status());
		assertThat(afterOldPredecessor.statusVersion()).isEqualTo(beforeOldPredecessor.statusVersion());
		assertThat(afterOldPredecessor.retryNotBeforeAt()).isEqualTo(beforeOldPredecessor.retryNotBeforeAt());
		assertThat(store.findAttempt(first.id()).orElseThrow().completedAt()).isNull();

		GenerationAttempt completedSecond = finishedAttempt(second, NOW.plusSeconds(3), "FAILED", "RETRYABLE");
		GenerationJob beforeCurrentPredecessor = store.findJob(job.id()).orElseThrow();
		assertThat(store.scheduleRetryIfActive(
			completedSecond,
			beforeCurrentPredecessor.statusVersion(),
			retryNotBeforeAt)).isTrue();
		GenerationJob scheduled = store.findJob(job.id()).orElseThrow();
		assertThat(scheduled.status()).isEqualTo(GenerationJob.RETRYING);
		assertThat(scheduled.statusVersion()).isEqualTo(beforeCurrentPredecessor.statusVersion() + 1);
		assertThat(scheduled.retryNotBeforeAt()).isEqualTo(retryNotBeforeAt);
		assertThat(store.findAttempt(second.id()).orElseThrow().completedAt()).isEqualTo(completedSecond.completedAt());
		assertThat(store.scheduleRetryIfActive(
			completedSecond,
			beforeCurrentPredecessor.statusVersion(),
			retryNotBeforeAt)).isFalse();
	}
	@Test
	void fencedScheduleRetryRejectsRetryAtOrAfterOrchestrationDeadlineWithoutCompletingPredecessor() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt pending = pendingAttempt(job.id(), 1);
		store.insertAttempt(pending);
		GenerationAttempt completed = finishedAttempt(pending, NOW.plusSeconds(2), "FAILED", "RETRYABLE");

		for (Instant retryNotBeforeAt : List.of(
			job.orchestrationDeadlineAt(),
			job.orchestrationDeadlineAt().plusSeconds(1))) {
			GenerationJob before = store.findJob(job.id()).orElseThrow();
			assertThat(store.scheduleRetryIfActive(completed, before.statusVersion(), retryNotBeforeAt)).isFalse();
			GenerationJob after = store.findJob(job.id()).orElseThrow();
			assertThat(after.status()).isEqualTo(before.status());
			assertThat(after.statusVersion()).isEqualTo(before.statusVersion());
			assertThat(after.retryNotBeforeAt()).isEqualTo(before.retryNotBeforeAt());
			assertThat(store.findAttempt(pending.id()).orElseThrow().completedAt()).isNull();
		}
	}

	@Test
	void claimDueRetryRejectsStaleAndNotDueWorkersAndCreatesOneSuccessor() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		jdbc.update(
			"UPDATE generation_job SET retry_policy_snapshot = ? WHERE id = ?",
			RetryPolicySnapshot.forStepCount(1).serialize(),
			job.id().value());
		GenerationAttempt pending = pendingAttempt(job.id(), 1);
		store.insertAttempt(pending);
		GenerationAttempt completed = finishedAttempt(pending, NOW.plusSeconds(2), "FAILED", "RETRYABLE");
		Instant retryNotBeforeAt = NOW.plusSeconds(20);
		assertThat(store.scheduleRetryIfActive(completed, retryNotBeforeAt)).isTrue();
		GenerationJob scheduled = store.findJob(job.id()).orElseThrow();
		GenerationAttempt next = pendingAttempt(job.id(), 2);

		assertThat(store.claimDueRetry(job.id(), scheduled.statusVersion(), NOW.plusSeconds(19), next))
			.isFalse();
		assertThat(store.claimDueRetry(job.id(), scheduled.statusVersion() - 1, NOW.plusSeconds(20), next))
			.isFalse();
		assertThat(store.claimDueRetry(job.id(), scheduled.statusVersion(), NOW.plusSeconds(20), next))
			.isTrue();

		GenerationJob claimed = store.findJob(job.id()).orElseThrow();
		assertThat(claimed.status()).isEqualTo(GenerationJob.PREFLIGHT);
		assertThat(claimed.retryNotBeforeAt()).isNull();
		assertThat(claimed.statusVersion()).isEqualTo(scheduled.statusVersion() + 1);
		assertThat(store.listAttempts(job.id())).extracting(GenerationAttempt::id)
			.containsExactly(pending.id(), next.id());

		GenerationAttempt duplicate = pendingAttempt(job.id(), 3);
		assertThat(store.claimDueRetry(job.id(), claimed.statusVersion(), NOW.plusSeconds(21), duplicate))
			.isFalse();
		assertThat(store.listAttempts(job.id())).extracting(GenerationAttempt::id)
			.containsExactly(pending.id(), next.id());
		GenerationAttempt completedNext = finishedAttempt(next, NOW.plusSeconds(22), "FAILED", "RETRYABLE");
		Instant nextRetryNotBeforeAt = NOW.plusSeconds(40);
		assertThat(store.scheduleRetryIfActive(
			completedNext,
			claimed.statusVersion(),
			nextRetryNotBeforeAt)).isTrue();
		GenerationJob rescheduled = store.findJob(job.id()).orElseThrow();
		assertThat(rescheduled.status()).isEqualTo(GenerationJob.RETRYING);
		assertThat(rescheduled.retryNotBeforeAt()).isEqualTo(nextRetryNotBeforeAt);
		assertThat(rescheduled.statusVersion()).isEqualTo(claimed.statusVersion() + 1);
		assertThat(store.findAttempt(next.id()).orElseThrow().completedAt())
			.isEqualTo(completedNext.completedAt());
	}
	@Test
	void claimDueRetryRejectsFourthAttemptOnOneStepButAllowsLaterStep() {
		UserId userId = insertUser();
		GenerationJob job = retryingMultiStepJob(userId, 3);
		UUID firstStepId = UUID.randomUUID();
		UUID secondStepId = UUID.randomUUID();
		store.insertJob(job);
		insertRenderStep(job, firstStepId, 1);
		insertRenderStep(job, secondStepId, 2);

		GenerationAttempt first = pendingAttemptWithStep(job.id(), 1, firstStepId);
		GenerationAttempt second = pendingAttemptWithStep(job.id(), 2, firstStepId);
		GenerationAttempt third = pendingAttemptWithStep(job.id(), 3, firstStepId);
		store.insertAttempt(first);
		store.insertAttempt(second);
		store.insertAttempt(third);

		GenerationAttempt fourthOnFirstStep = pendingAttemptWithStep(job.id(), 4, firstStepId);
		assertThat(store.claimDueRetry(job.id(), job.statusVersion(), NOW, fourthOnFirstStep)).isFalse();
		GenerationJob rejected = store.findJob(job.id()).orElseThrow();
		assertThat(rejected.status()).isEqualTo(GenerationJob.RETRYING);
		assertThat(rejected.retryNotBeforeAt()).isEqualTo(NOW);
		assertThat(rejected.statusVersion()).isEqualTo(job.statusVersion());
		assertThat(store.listAttempts(job.id())).extracting(GenerationAttempt::id)
			.containsExactly(first.id(), second.id(), third.id());

		GenerationAttempt firstBaselineForLaterStep = pendingAttemptWithStep(job.id(), 4, secondStepId);
		assertThat(store.claimDueRetry(job.id(), rejected.statusVersion(), NOW, firstBaselineForLaterStep)).isTrue();
		assertThat(store.listAttempts(job.id())).extracting(GenerationAttempt::renderStepId)
			.containsExactly(firstStepId, firstStepId, firstStepId, secondStepId);
	}

	@Test
	void claimDueRetryRejectsNonMonotonicSuccessorAttemptNumber() {
		UserId userId = insertUser();
		GenerationJob job = retryingJob(userId, NOW, RetryPolicySnapshot.forStepCount(1));
		GenerationAttempt predecessor = pendingAttempt(job.id(), 1);
		store.insertJob(job);
		store.insertAttempt(predecessor);

		GenerationAttempt nonMonotonic = pendingAttempt(job.id(), 3);
		assertThat(store.claimDueRetry(job.id(), job.statusVersion(), NOW, nonMonotonic)).isFalse();
		assertThat(store.findJob(job.id()).orElseThrow().retryNotBeforeAt()).isEqualTo(NOW);
		assertThat(store.listAttempts(job.id())).extracting(GenerationAttempt::id)
			.containsExactly(predecessor.id());
	}

	@Test
	void v2OneStepJobAllowsThirdPaidAttemptButDeniesFourth() {
		UserId userId = insertUser();
		GenerationJob job = retryingMultiStepJobV2(userId, 1);
		UUID stepId = UUID.randomUUID();
		store.insertJob(job);
		insertRenderStep(job, stepId, 1);
		GenerationAttempt first = pendingAttemptWithStep(job.id(), 1, stepId);
		GenerationAttempt second = pendingAttemptWithStep(job.id(), 2, stepId);
		store.insertAttempt(first);
		store.insertAttempt(second);

		GenerationAttempt third = pendingAttemptWithStep(job.id(), 3, stepId);
		assertThat(store.claimDueRetry(job.id(), job.statusVersion(), NOW, third)).isTrue();
		GenerationJob claimed = store.findJob(job.id()).orElseThrow();
		assertThat(claimed.status()).isEqualTo(GenerationJob.PREFLIGHT);

		Instant retryNotBeforeAt = NOW.plusSeconds(20);
		assertThat(store.scheduleRetryIfActive(
			finishedAttempt(third, NOW.plusSeconds(2), "FAILED", "RETRYABLE"),
			claimed.statusVersion(),
			retryNotBeforeAt)).isTrue();
		GenerationJob rescheduled = store.findJob(job.id()).orElseThrow();

		GenerationAttempt fourth = pendingAttemptWithStep(job.id(), 4, stepId);
		assertThat(store.claimDueRetry(job.id(), rescheduled.statusVersion(), retryNotBeforeAt, fourth)).isFalse();
		assertThat(store.findJob(job.id()).orElseThrow().status()).isEqualTo(GenerationJob.RETRYING);
		assertThat(store.findJob(job.id()).orElseThrow().retryNotBeforeAt()).isEqualTo(retryNotBeforeAt);
		assertThat(store.listAttempts(job.id())).extracting(GenerationAttempt::id)
			.containsExactly(first.id(), second.id(), third.id());
	}

	@Test
	void v2TwoStepJobDeniesSecondExtraWhileSecondBaselineRemains() {
		UserId userId = insertUser();
		GenerationJob job = retryingMultiStepJobV2(userId, 2);
		UUID firstStepId = UUID.randomUUID();
		UUID secondStepId = UUID.randomUUID();
		store.insertJob(job);
		insertRenderStep(job, firstStepId, 1);
		insertRenderStep(job, secondStepId, 2);
		GenerationAttempt firstBaseline = pendingAttemptWithStep(job.id(), 1, firstStepId);
		store.insertAttempt(firstBaseline);

		GenerationAttempt extra = pendingAttemptWithStep(job.id(), 2, firstStepId);
		assertThat(store.claimDueRetry(job.id(), job.statusVersion(), NOW, extra)).isTrue();
		GenerationJob claimed = store.findJob(job.id()).orElseThrow();

		Instant retryNotBeforeAt = NOW.plusSeconds(20);
		assertThat(store.scheduleRetryIfActive(
			finishedAttempt(extra, NOW.plusSeconds(2), "FAILED", "RETRYABLE"),
			claimed.statusVersion(),
			retryNotBeforeAt)).isTrue();
		GenerationJob rescheduled = store.findJob(job.id()).orElseThrow();

		GenerationAttempt secondExtra = pendingAttemptWithStep(job.id(), 3, firstStepId);
		assertThat(store.claimDueRetry(job.id(), rescheduled.statusVersion(), retryNotBeforeAt, secondExtra))
			.isFalse();
		assertThat(store.findJob(job.id()).orElseThrow().status()).isEqualTo(GenerationJob.RETRYING);

		GenerationAttempt secondBaseline = pendingAttemptWithStep(job.id(), 3, secondStepId);
		assertThat(store.claimDueRetry(job.id(), rescheduled.statusVersion(), retryNotBeforeAt, secondBaseline))
			.isTrue();
		assertThat(store.listAttempts(job.id())).extracting(GenerationAttempt::renderStepId)
			.containsExactly(firstStepId, firstStepId, secondStepId);
	}

	@Test
	void v2ThreeStepJobDeniesSparePaidRetryWhileBaselinesRemain() {
		UserId userId = insertUser();
		GenerationJob job = retryingMultiStepJobV2(userId, 3);
		UUID firstStepId = UUID.randomUUID();
		UUID secondStepId = UUID.randomUUID();
		UUID thirdStepId = UUID.randomUUID();
		store.insertJob(job);
		insertRenderStep(job, firstStepId, 1);
		insertRenderStep(job, secondStepId, 2);
		insertRenderStep(job, thirdStepId, 3);
		GenerationAttempt firstBaseline = pendingAttemptWithStep(job.id(), 1, firstStepId);
		GenerationAttempt secondBaseline = pendingAttemptWithStep(job.id(), 2, secondStepId);
		store.insertAttempt(firstBaseline);
		store.insertAttempt(secondBaseline);

		GenerationAttempt spare = pendingAttemptWithStep(job.id(), 3, firstStepId);
		assertThat(store.claimDueRetry(job.id(), job.statusVersion(), NOW, spare)).isFalse();
		assertThat(store.findJob(job.id()).orElseThrow().status()).isEqualTo(GenerationJob.RETRYING);
		assertThat(store.listAttempts(job.id())).hasSize(2);

		GenerationAttempt thirdBaseline = pendingAttemptWithStep(job.id(), 3, thirdStepId);
		assertThat(store.claimDueRetry(job.id(), job.statusVersion(), NOW, thirdBaseline)).isTrue();
		assertThat(store.listAttempts(job.id())).extracting(GenerationAttempt::renderStepId)
			.containsExactly(firstStepId, secondStepId, thirdStepId);
	}

	@Test
	void v1MultiStepJobKeepsHistoricalCapWithoutReservation() {
		UserId userId = insertUser();
		GenerationJob job = retryingMultiStepJob(userId, 2);
		UUID firstStepId = UUID.randomUUID();
		UUID secondStepId = UUID.randomUUID();
		store.insertJob(job);
		insertRenderStep(job, firstStepId, 1);
		insertRenderStep(job, secondStepId, 2);
		store.insertAttempt(pendingAttemptWithStep(job.id(), 1, firstStepId));
		store.insertAttempt(pendingAttemptWithStep(job.id(), 2, firstStepId));

		GenerationAttempt thirdExtra = pendingAttemptWithStep(job.id(), 3, firstStepId);
		assertThat(store.claimDueRetry(job.id(), job.statusVersion(), NOW, thirdExtra)).isTrue();
		assertThat(store.listAttempts(job.id())).extracting(GenerationAttempt::renderStepId)
			.containsExactly(firstStepId, firstStepId, firstStepId);
	}

	@Test
	void resolveRetryPolicyDistinguishesMissingEmptyMalformedAndUnsupportedRows() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);

		assertThat(store.resolveRetryPolicy(job.id()))
			.isEqualTo(new RetryPolicyResolution(RetryPolicyResolution.State.MISSING, null));
		assertThat(store.findJob(job.id()).orElseThrow().retryPolicySnapshot()).isNull();

		jdbc.update(
			"UPDATE generation_job SET retry_policy_snapshot = ? WHERE id = ?",
			"   ",
			job.id().value());
		assertThat(store.resolveRetryPolicy(job.id()))
			.isEqualTo(new RetryPolicyResolution(RetryPolicyResolution.State.EMPTY, null));
		assertThat(store.findJob(job.id()).orElseThrow().retryPolicySnapshot()).isNull();

		jdbc.update(
			"UPDATE generation_job SET retry_policy_snapshot = ? WHERE id = ?",
			"malformed-retry-policy",
			job.id().value());
		assertThat(store.resolveRetryPolicy(job.id()))
			.isEqualTo(new RetryPolicyResolution(RetryPolicyResolution.State.INVALID, null));
		assertThat(store.findJob(job.id()).orElseThrow().retryPolicySnapshot()).isNull();

		String unsupported = RetryPolicySnapshot.forStepCountV2(1).serialize().replace("version=2", "version=3");
		jdbc.update(
			"UPDATE generation_job SET retry_policy_snapshot = ? WHERE id = ?",
			unsupported,
			job.id().value());
		assertThat(store.resolveRetryPolicy(job.id()))
			.isEqualTo(new RetryPolicyResolution(RetryPolicyResolution.State.UNSUPPORTED_VERSION, null));
		assertThat(store.findJob(job.id()).orElseThrow().retryPolicySnapshot()).isNull();

		assertThat(store.resolveRetryPolicy(new GenerationJobId(UUID.randomUUID()))).isNull();
	}

	@Test
	void resolveRetryPolicyParsesValidV1AndV2RowsWithoutDefaultSubstitution() {
		UserId userId = insertUser();
		GenerationJob v1Job = insertQueuedJob(userId);
		RetryPolicySnapshot v1 = RetryPolicySnapshot.forStepCount(2);
		jdbc.update(
			"UPDATE generation_job SET total_steps = 2, retry_policy_snapshot = ? WHERE id = ?",
			v1.serialize(),
			v1Job.id().value());
		assertThat(store.resolveRetryPolicy(v1Job.id()))
			.isEqualTo(new RetryPolicyResolution(RetryPolicyResolution.State.VALID, v1));
		GenerationJob loadedV1 = store.findJob(v1Job.id()).orElseThrow();
		assertThat(loadedV1.retryPolicySnapshot()).isEqualTo(v1);
		assertThat(loadedV1.retryPolicySnapshot().snapshotVersion())
			.isEqualTo(RetryPolicySnapshot.SNAPSHOT_VERSION_V1);
		assertThat(loadedV1.retryPolicySnapshot().maxPaidAttempts())
			.isEqualTo(loadedV1.totalSteps() + RetryPolicySnapshot.ADDITIONAL_LOGICAL_ATTEMPTS);

		GenerationJob v2Job = insertQueuedJob(userId);
		RetryPolicySnapshot v2 = RetryPolicySnapshot.forStepCountV2(2);
		jdbc.update(
			"UPDATE generation_job SET total_steps = 2, retry_policy_snapshot = ? WHERE id = ?",
			v2.serialize(),
			v2Job.id().value());
		assertThat(store.resolveRetryPolicy(v2Job.id()))
			.isEqualTo(new RetryPolicyResolution(RetryPolicyResolution.State.VALID, v2));
		GenerationJob loadedV2 = store.findJob(v2Job.id()).orElseThrow();
		assertThat(loadedV2.retryPolicySnapshot()).isEqualTo(v2);
		assertThat(loadedV2.retryPolicySnapshot().snapshotVersion())
			.isEqualTo(RetryPolicySnapshot.SNAPSHOT_VERSION_V2);
		assertThat(loadedV2.retryPolicySnapshot().maxPaidAttempts())
			.isEqualTo(GenerationRetryBudget.DEFAULT_MAX_PAID_ATTEMPTS);
		assertThat(loadedV2.retryPolicySnapshot().maxPaidAttempts())
			.isNotEqualTo(loadedV2.totalSteps() + RetryPolicySnapshot.ADDITIONAL_LOGICAL_ATTEMPTS);
	}

	@Test
	void createAcceptedRejectsJobWithoutValidPersistablePolicy() {
		UserId userId = insertUser();
		GenerationJob missing = acceptedJob(
			userId,
			null,
			List.of(),
			new ClientRequestId("req-no-policy"),
			"fp-no-policy",
			null);
		assertThatThrownBy(() -> store.createAccepted(missing))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_job WHERE user_id = ?",
			Integer.class,
			userId.value())).isZero();

		RetryPolicySnapshot belowFixedCap = new RetryPolicySnapshot(
			2,
			1,
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
			RetryPolicySnapshot.DEFAULT_PROVIDER_TIMING_POLICY_ID,
			RetryPolicySnapshot.SNAPSHOT_VERSION_V2,
			GenerationRetryBudget.MAX_TECHNICAL_RETRIES_PER_JOB,
			GenerationRetryBudget.MAX_EVALUATOR_RETRIES_PER_CANDIDATE,
			RetryPolicySnapshot.DEFAULT_PROVIDER_ROUTING_POLICY_ID);
		GenerationJob belowFixedCapJob = acceptedJob(
			userId,
			null,
			List.of(),
			new ClientRequestId("req-below-cap"),
			"fp-below-cap",
			belowFixedCap);
		assertThatThrownBy(() -> store.createAccepted(belowFixedCapJob))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_job WHERE user_id = ?",
			Integer.class,
			userId.value())).isZero();
	}

	@Test
	void createAcceptedPersistsRetryOfJobIdLineage() {
		UserId userId = insertUser();
		GenerationJob predecessor = insertQueuedJob(userId);
		GenerationJob retry = new GenerationJob(
			new GenerationJobId(UUID.randomUUID()),
			userId,
			null,
			"QUEUED",
			"try-on",
			new ClientRequestId("req-try-again"),
			NOW,
			null,
			null,
			List.of(),
			null,
			null,
			"fp-try-again",
			null,
			0,
			NOW,
			null,
			1,
			1,
			1,
			NOW.plusSeconds(1800),
			null,
			RetryPolicySnapshot.forStepCountV2(1),
			Optional.of(predecessor.id()));

		GenerationStore.CreateAcceptedResult result = store.createAccepted(retry);

		assertThat(result).isInstanceOf(GenerationStore.CreateAcceptedResult.Created.class);
		assertThat(store.findJob(retry.id()).orElseThrow().retryOfJobId()).contains(predecessor.id());
		assertThat(store.findJob(predecessor.id()).orElseThrow().retryOfJobId()).isEmpty();
	}

	@Test
	void attemptPolicyColumnsRoundTripAndDatabaseChecksRejectInvalidValues() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttemptId attemptId = new GenerationAttemptId(UUID.randomUUID());
		GenerationAttempt attempt = pendingAttemptWithPolicy(job.id(), attemptId, 1, "fashn-error-mapping-v1", 1);
		store.insertAttempt(attempt);

		GenerationAttempt loaded = store.findAttempt(attemptId).orElseThrow();
		assertThat(loaded.errorMappingContractVersion()).isEqualTo("fashn-error-mapping-v1");
		assertThat(loaded.evaluatorRetryCount()).isEqualTo(1);
		assertThat(jdbc.queryForObject(
			"SELECT error_mapping_contract_version FROM generation_attempt WHERE id = ?",
			String.class,
			attemptId.value())).isEqualTo("fashn-error-mapping-v1");
		assertThat(jdbc.queryForObject(
			"SELECT evaluator_retry_count FROM generation_attempt WHERE id = ?",
			Integer.class,
			attemptId.value())).isEqualTo(1);

		assertThatThrownBy(() -> jdbc.update(
			"INSERT INTO generation_attempt "
				+ "(id, generation_job_id, provider, attempt_number, started_at, evaluator_retry_count) "
				+ "VALUES (?, ?, 'example-provider', 2, ?, 2)",
			UUID.randomUUID(),
			job.id().value(),
			Timestamp.from(NOW)))
			.isInstanceOf(org.springframework.dao.DataAccessException.class);
		assertThatThrownBy(() -> jdbc.update(
			"INSERT INTO generation_attempt "
				+ "(id, generation_job_id, provider, attempt_number, started_at, error_mapping_contract_version) "
				+ "VALUES (?, ?, 'example-provider', 3, ?, 'not a token')",
			UUID.randomUUID(),
			job.id().value(),
			Timestamp.from(NOW)))
			.isInstanceOf(org.springframework.dao.DataAccessException.class);
	}

	@Test
	void saveQualityResultLeavesEvaluatorInfrastructureAtDatabaseDefault() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt pending = pendingAttempt(job.id(), 1);
		store.insertAttempt(pending);

		store.saveQualityResult(new QualityResult(
			new QualityResultId(UUID.randomUUID()),
			pending.id(),
			QualityDecision.FAIL,
			new BigDecimal("0.1"),
			new BigDecimal("0.2"),
			new BigDecimal("0.3"),
			Boolean.FALSE,
			Boolean.TRUE,
			NOW,
			"quality-v2",
			"IMAGE_INVALID"));

		assertThat(jdbc.queryForObject(
			"SELECT evaluator_infrastructure FROM quality_result WHERE generation_attempt_id = ?",
			Boolean.class,
			pending.id().value())).isFalse();
	}


	@Test
	void listDueRetryingUsesDurableTimestamp() {
		UserId userId = insertUser();
		GenerationJob due = retryingJob(userId, NOW, RetryPolicySnapshot.forStepCount(1));
		GenerationJob future = retryingJob(userId, NOW.plusSeconds(1), RetryPolicySnapshot.forStepCount(1));
		GenerationJob queued = insertQueuedJob(userId);
		store.insertJob(due);
		store.insertJob(future);

		assertThat(store.listDueRetrying(NOW)).extracting(GenerationJob::id)
			.containsExactly(due.id());
		assertThat(store.findJob(queued.id()).orElseThrow().retryNotBeforeAt()).isNull();
	}

	@Test
	void tryRecordTransmissionIsCappedAndStopsAfterAcceptanceOrCompletion() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt attempt = pendingAttempt(job.id(), 1);
		store.insertAttempt(attempt);

		assertThat(store.tryRecordTransmission(attempt.id(), NOW)).isTrue();
		assertThat(store.tryRecordTransmission(attempt.id(), NOW)).isTrue();
		assertThat(store.tryRecordTransmission(attempt.id(), NOW)).isTrue();
		assertThat(store.tryRecordTransmission(attempt.id(), NOW)).isFalse();
		assertThat(store.findAttempt(attempt.id()).orElseThrow().transmissionCount()).isEqualTo(3);

		jdbc.update(
			"UPDATE generation_attempt SET provider_accepted_at = ? WHERE id = ?",
			Timestamp.from(NOW),
			attempt.id().value());
		assertThat(store.tryRecordTransmission(attempt.id(), NOW)).isFalse();
		assertThat(store.findAttempt(attempt.id()).orElseThrow().transmissionCount()).isEqualTo(3);

		GenerationAttempt completedAttempt = pendingAttempt(job.id(), 2);
		store.insertAttempt(completedAttempt);
		store.completeAttempt(finishedAttempt(completedAttempt, NOW.plusSeconds(1), "FAILED", "RETRYABLE"));
		assertThat(store.tryRecordTransmission(completedAttempt.id(), NOW.plusSeconds(2))).isFalse();
	}
	@Test
	void tryRecordTransmissionRejectsAtRecoveryAndOrchestrationDeadlines() {
		UserId userId = insertUser();

		GenerationJob recoveryJob = insertQueuedJob(userId);
		GenerationAttempt recoveryAttempt = pendingAttempt(recoveryJob.id(), 1);
		store.insertAttempt(recoveryAttempt);
		jdbc.update(
			"UPDATE generation_attempt SET submission_recovery_deadline_at = ? WHERE id = ?",
			Timestamp.from(NOW),
			recoveryAttempt.id().value());
		assertThat(store.tryRecordTransmission(recoveryAttempt.id(), NOW)).isFalse();

		GenerationJob orchestrationJob = insertQueuedJob(userId);
		GenerationAttempt orchestrationAttempt = pendingAttempt(orchestrationJob.id(), 1);
		store.insertAttempt(orchestrationAttempt);
		jdbc.update(
			"UPDATE generation_job SET orchestration_deadline_at = ? WHERE id = ?",
			Timestamp.from(NOW),
			orchestrationJob.id().value());
		assertThat(store.tryRecordTransmission(orchestrationAttempt.id(), NOW)).isFalse();
	}


	@Test
	void advanceStepIfActiveUsesVersionFenceAndPreservesPlanShape() {
		UserId userId = insertUser();
		GenerationJob job = multiStepJob(userId, 1, 2);
		store.insertJob(job);

		assertThat(store.advanceStepIfActive(job.id(), job.statusVersion(), 1)).isTrue();
		GenerationJob advanced = store.findJob(job.id()).orElseThrow();
		assertThat(advanced.currentStep()).isEqualTo(2);
		assertThat(advanced.totalSteps()).isEqualTo(2);
		assertThat(advanced.renderPlanVersion()).isEqualTo(job.renderPlanVersion());
		assertThat(advanced.statusVersion()).isEqualTo(job.statusVersion() + 1);
		assertThat(store.advanceStepIfActive(job.id(), job.statusVersion(), 1)).isFalse();
		assertThat(store.advanceStepIfActive(job.id(), advanced.statusVersion(), 1)).isFalse();
		assertThat(store.findJob(job.id()).orElseThrow().currentStep()).isEqualTo(2);
	}

	@Test
	void terminalTransitionsClearRetryTimestamp() {
		UserId userId = insertUser();
		GenerationJob completedJob = retryingJob(userId, NOW, RetryPolicySnapshot.forStepCount(1));
		GenerationAttempt completedPending = pendingAttempt(completedJob.id(), 1);
		store.insertJob(completedJob);
		store.insertAttempt(completedPending);
		assertThat(store.completeTerminalIfActive(
			finishedAttempt(completedPending, NOW.plusSeconds(1), "SUCCESS", null),
			NOW.plusSeconds(1))).isTrue();
		assertThat(store.findJob(completedJob.id()).orElseThrow().retryNotBeforeAt()).isNull();

		GenerationJob cancelledJob = retryingJob(userId, NOW, RetryPolicySnapshot.forStepCount(1));
		store.insertJob(cancelledJob);
		assertThat(store.cancelIfActive(cancelledJob.id(), NOW.plusSeconds(1))).isTrue();
		assertThat(store.findJob(cancelledJob.id()).orElseThrow().retryNotBeforeAt()).isNull();

		GenerationJob deletedJob = retryingJob(userId, NOW, RetryPolicySnapshot.forStepCount(1));
		store.insertJob(deletedJob);
		store.markSubjectDeleted(deletedJob.id(), NOW.plusSeconds(1));
		assertThat(store.findJob(deletedJob.id()).orElseThrow().retryNotBeforeAt()).isNull();

		GenerationJob overdueJob = retryingJob(
			userId,
			NOW.minusSeconds(2),
			RetryPolicySnapshot.forStepCount(1),
			NOW.minusSeconds(1));
		store.insertJob(overdueJob);
		assertThat(store.failIfDeadlineExceeded(overdueJob.id(), NOW)).isTrue();
		assertThat(store.findJob(overdueJob.id()).orElseThrow().retryNotBeforeAt()).isNull();
	}

	@Test
	void directCompletionAndBulkDeletionClearRetryTimestamp() {
		UserId completionUser = insertUser();
		GenerationJob directlyCompleted = retryingJob(
			completionUser,
			NOW,
			RetryPolicySnapshot.forStepCount(1));
		store.insertJob(directlyCompleted);
		store.markJobCompleted(directlyCompleted.id(), NOW.plusSeconds(1));
		assertThat(store.findJob(directlyCompleted.id()).orElseThrow().retryNotBeforeAt()).isNull();

		UserId deletionUser = insertUser();
		GenerationJob bulkDeleted = retryingJob(
			deletionUser,
			NOW,
			RetryPolicySnapshot.forStepCount(1));
		store.insertJob(bulkDeleted);
		store.markAllSubjectDeleted(deletionUser, NOW.plusSeconds(1));
		assertThat(store.findJob(bulkDeleted.id()).orElseThrow().retryNotBeforeAt()).isNull();
	}


	@Test
	void recordProviderExecutionIfActiveReturnsFalseAfterJobCompleted() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt pending = pendingAttempt(job.id(), 1);
		store.insertAttempt(pending);

		assertThat(store.recordProviderExecutionIfActive(pending.id(), null)).isTrue();
		assertThat(store.recordProviderExecutionIfActive(pending.id(), new ProviderJobId("provider-job-active")))
			.isTrue();
		assertThat(store.recordProviderExecutionIfActive(pending.id(), new ProviderJobId("provider-job-different")))
			.isFalse();
		store.completeTerminalIfActive(
			finishedAttempt(pending, NOW.plusSeconds(2), "SUCCESS", null),
			NOW.plusSeconds(2));

		assertThat(store.recordProviderExecutionIfActive(pending.id(), new ProviderJobId("provider-job-late")))
			.isFalse();
		assertThat(store.findAttempt(pending.id()).orElseThrow().providerJobId()).isEqualTo("provider-job-active");
	}
	@Test
	void recordProviderExecutionIfActivePersistsAcceptanceAndStopsTransmission() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt attempt = pendingAttempt(job.id(), 1);
		store.insertAttempt(attempt);

		assertThat(store.recordProviderExecutionIfActive(attempt.id(), null)).isTrue();
		assertThat(store.findAttempt(attempt.id()).orElseThrow().providerAcceptedAt()).isNull();

		ProviderJobId acceptedJobId = new ProviderJobId("provider-job-accepted");
		assertThat(store.recordProviderExecutionIfActive(attempt.id(), acceptedJobId)).isTrue();

		GenerationAttempt loaded = store.findAttempt(attempt.id()).orElseThrow();
		assertThat(loaded.providerJobId()).isEqualTo(acceptedJobId.value());
		assertThat(loaded.providerAcceptedAt()).isEqualTo(NOW);
		assertThat(store.tryRecordTransmission(attempt.id(), NOW)).isFalse();
		assertThat(store.findAttempt(attempt.id()).orElseThrow().transmissionCount()).isZero();
	}

	@Test
	void recordProviderExecutionIsWriteOnceAndNullSafe() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt pending = pendingAttempt(job.id(), 1);
		store.insertAttempt(pending);

		store.recordProviderExecution(pending.id(), null);
		store.recordProviderExecution(pending.id(), new ProviderJobId("provider-job-once"));
		store.recordProviderExecution(pending.id(), new ProviderJobId("provider-job-once"));
		store.recordProviderExecution(pending.id(), new ProviderJobId("provider-job-different"));

		assertThat(store.findAttempt(pending.id()).orElseThrow().providerJobId())
			.isEqualTo("provider-job-once");

		GenerationJob deleted = insertQueuedJob(userId);
		GenerationAttempt deletedAttempt = pendingAttempt(deleted.id(), 1);
		store.insertAttempt(deletedAttempt);
		store.markSubjectDeleted(deleted.id(), NOW.plusSeconds(1));
		store.recordProviderExecution(deletedAttempt.id(), new ProviderJobId("provider-job-deleted"));
		assertThat(store.findAttempt(deletedAttempt.id()).orElseThrow().providerJobId()).isNull();
	}

	@Test
	void markSubjectDeletedForAvatarMarksOnlyMatchingJobs() {
		UserId userId = insertUser();
		UserId otherUserId = insertUser();
		AvatarImageId matchingAvatar = insertAvatar(userId);
		AvatarImageId otherAvatar = insertAdditionalAvatarImage(userId, matchingAvatar);

		GenerationJob matching = insertQueuedJob(userId, matchingAvatar);
		GenerationJob otherImage = insertQueuedJob(userId, otherAvatar);
		GenerationJob alreadyDeleted = insertQueuedJob(userId, matchingAvatar);
		GenerationJob otherUser = insertQueuedJob(otherUserId);
		Instant earlier = NOW.plusSeconds(1);
		store.markSubjectDeleted(alreadyDeleted.id(), earlier);

		Instant when = NOW.plusSeconds(9);
		store.markSubjectDeletedForAvatar(userId, matchingAvatar, when);

		assertThat(store.findJob(matching.id()).orElseThrow().subjectDeletedAt()).isEqualTo(when);
		assertThat(store.findJob(otherImage.id()).orElseThrow().subjectDeletedAt()).isNull();
		assertThat(store.findJob(alreadyDeleted.id()).orElseThrow().subjectDeletedAt()).isEqualTo(earlier);
		assertThat(store.findJob(otherUser.id()).orElseThrow().subjectDeletedAt()).isNull();
	}


	@Test
	void markAllSubjectDeletedMarksAllUserJobs() {
		UserId userId = insertUser();
		UserId otherUserId = insertUser();
		GenerationJob first = insertQueuedJob(userId);
		GenerationJob second = insertQueuedJob(userId);
		GenerationJob alreadyDeleted = insertQueuedJob(userId);
		GenerationJob other = insertQueuedJob(otherUserId);
		Instant earlier = NOW.plusSeconds(1);
		store.markSubjectDeleted(alreadyDeleted.id(), earlier);

		Instant when = NOW.plusSeconds(8);
		store.markAllSubjectDeleted(userId, when);

		assertThat(store.findJob(first.id()).orElseThrow().subjectDeletedAt()).isEqualTo(when);
		assertThat(store.findJob(second.id()).orElseThrow().subjectDeletedAt()).isEqualTo(when);
		assertThat(store.findJob(alreadyDeleted.id()).orElseThrow().subjectDeletedAt()).isEqualTo(earlier);
		assertThat(store.findJob(other.id()).orElseThrow().subjectDeletedAt()).isNull();
		assertThat(store.listJobsByUser(userId))
			.extracting(GenerationJob::id)
			.containsExactlyInAnyOrder(first.id(), second.id(), alreadyDeleted.id());
		assertThat(store.listJobsByUser(otherUserId))
			.extracting(GenerationJob::id)
			.containsExactly(other.id());
	}

	@Test
	void completeAttemptRetainsEconomicsAndQualityResultWithoutMedia() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt pending = pendingAttempt(job.id(), 1);
		store.insertAttempt(pending);
		assertThat(store.tryAcquireSubmitOwnership(pending.id(), NOW)).isTrue();
		store.recordProviderExecution(pending.id(), new ProviderJobId("provider-job-1"));

		Instant completedAt = NOW.plusSeconds(2);
		GenerationAttempt completed = new GenerationAttempt(
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
			"QUALITY_REJECTED",
			"SUCCESS",
			pending.submissionKey(),
			NOW,
			false,
			new StorageKey("temp/user/generation/" + pending.id().value() + ".jpg"),
			new ProviderOutputRef("provider-output-1"));
		store.completeAttempt(completed);

		QualityResult quality = new QualityResult(
			new QualityResultId(UUID.randomUUID()),
			pending.id(),
			QualityDecision.FAIL,
			new BigDecimal("0.11"),
			new BigDecimal("0.22"),
			new BigDecimal("0.33"),
			Boolean.TRUE,
			Boolean.TRUE,
			completedAt);
		store.saveQualityResult(quality);

		GenerationAttempt loaded = store.findAttempt(pending.id()).orElseThrow();
		assertThat(loaded.providerJobId()).isEqualTo("provider-job-1");
		assertThat(loaded.creditsUsed()).isEqualByComparingTo("2.0");
		assertThat(loaded.estimatedCost()).isEqualByComparingTo("0.04");
		assertThat(loaded.currency()).isEqualTo("USD");
		assertThat(loaded.failureCode()).isEqualTo("QUALITY_REJECTED");
		assertThat(loaded.technicalOutcome()).isEqualTo("SUCCESS");
		assertThat(loaded.tempStorageKey()).isEqualTo(completed.tempStorageKey());
		assertThat(loaded.providerOutputRef()).isEqualTo(completed.providerOutputRef());
		assertThat(jdbc.queryForObject(
			"SELECT decision FROM quality_result WHERE generation_attempt_id = ?",
			String.class,
			pending.id().value())).isEqualTo(QualityDecision.FAIL.name());
		assertThat(jdbc.queryForObject(
			"SELECT identity_preservation FROM quality_result WHERE generation_attempt_id = ?",
			BigDecimal.class,
			pending.id().value())).isEqualByComparingTo("0.11");
		assertNoMediaInRows();
	}

	@Test
	void saveQualityResultIsIdempotentOnGenerationAttemptId() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt pending = pendingAttempt(job.id(), 1);
		store.insertAttempt(pending);

		QualityResultId firstId = new QualityResultId(UUID.randomUUID());
		Instant firstAt = NOW.plusSeconds(2);
		QualityResult first = new QualityResult(
			firstId,
			pending.id(),
			QualityDecision.FAIL,
			new BigDecimal("0.11"),
			new BigDecimal("0.22"),
			new BigDecimal("0.33"),
			Boolean.TRUE,
			Boolean.TRUE,
			firstAt,
			"quality-policy-v1",
			"QUALITY_REJECTED");
		store.saveQualityResult(first);

		QualityResult replay = new QualityResult(
			new QualityResultId(UUID.randomUUID()),
			pending.id(),
			QualityDecision.FAIL,
			new BigDecimal("0.11"),
			new BigDecimal("0.22"),
			new BigDecimal("0.33"),
			Boolean.TRUE,
			Boolean.TRUE,
			NOW.plusSeconds(3),
			"quality-policy-v1",
			"QUALITY_REJECTED");
		store.saveQualityResult(replay);

		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM quality_result WHERE generation_attempt_id = ?",
			Integer.class,
			pending.id().value())).isEqualTo(1);
		Map<String, Object> row = jdbc.queryForMap(
			"SELECT id, decision, identity_preservation, garment_fidelity, color_fidelity, "
				+ "anatomy_valid, artifact_detected, quality_policy_version, reason_code "
				+ "FROM quality_result WHERE generation_attempt_id = ?",
			pending.id().value());
		assertThat(row.get("id")).isEqualTo(firstId.value());
		assertThat(row.get("decision")).isEqualTo(QualityDecision.FAIL.name());
		assertThat((BigDecimal) row.get("identity_preservation")).isEqualByComparingTo("0.11");
		assertThat((BigDecimal) row.get("garment_fidelity")).isEqualByComparingTo("0.22");
		assertThat((BigDecimal) row.get("color_fidelity")).isEqualByComparingTo("0.33");
		assertThat(row.get("anatomy_valid")).isEqualTo(true);
		assertThat(row.get("artifact_detected")).isEqualTo(true);
		assertThat(row.get("quality_policy_version")).isEqualTo("quality-policy-v1");
		assertThat(row.get("reason_code")).isEqualTo("QUALITY_REJECTED");
		assertNoMediaInRows();
	}
	@Test
	void saveQualityResultRejectsConflictingCompletedDecision() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt pending = pendingAttempt(job.id(), 1);
		store.insertAttempt(pending);

		QualityResult first = new QualityResult(
			new QualityResultId(UUID.randomUUID()),
			pending.id(),
			QualityDecision.PASS,
			new BigDecimal("0.91"),
			new BigDecimal("0.92"),
			new BigDecimal("0.93"),
			Boolean.TRUE,
			Boolean.FALSE,
			NOW.plusSeconds(2),
			"quality-policy-v2",
			"QUALITY_ACCEPTED");
		store.saveQualityResult(first);

		QualityResult conflict = new QualityResult(
			new QualityResultId(UUID.randomUUID()),
			pending.id(),
			QualityDecision.FAIL,
			new BigDecimal("0.11"),
			new BigDecimal("0.12"),
			new BigDecimal("0.13"),
			Boolean.FALSE,
			Boolean.TRUE,
			NOW.plusSeconds(3),
			"quality-policy-v3",
			"QUALITY_REJECTED");
		assertThatThrownBy(() -> store.saveQualityResult(conflict))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("quality result conflict for generation attempt " + pending.id().value());

		assertThat(jdbc.queryForObject(
			"SELECT decision FROM quality_result WHERE generation_attempt_id = ?",
			String.class,
			pending.id().value())).isEqualTo(QualityDecision.PASS.name());
	}

	@Test
	void saveQualityResultIsIdempotentForPopulatedQualityV2Result() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt pending = pendingAttempt(job.id(), 1);
		store.insertAttempt(pending);

		QualityResultId firstId = new QualityResultId(UUID.randomUUID());
		QualityResult first = new QualityResult(
			firstId,
			pending.id(),
			QualityDecision.PASS,
			new BigDecimal("0.91"),
			new BigDecimal("0.92"),
			new BigDecimal("0.93"),
			Boolean.TRUE,
			Boolean.FALSE,
			NOW.plusSeconds(2),
			"quality-v2",
			null);
		store.saveQualityResult(first);

		QualityResult replay = new QualityResult(
			new QualityResultId(UUID.randomUUID()),
			pending.id(),
			QualityDecision.PASS,
			new BigDecimal("0.91"),
			new BigDecimal("0.92"),
			new BigDecimal("0.93"),
			Boolean.TRUE,
			Boolean.FALSE,
			NOW.plusSeconds(3),
			"quality-v2",
			null);
		store.saveQualityResult(replay);

		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM quality_result WHERE generation_attempt_id = ?",
			Integer.class,
			pending.id().value())).isEqualTo(1);
		Map<String, Object> row = jdbc.queryForMap(
			"SELECT id, decision, identity_preservation, garment_fidelity, color_fidelity, "
				+ "anatomy_valid, artifact_detected, quality_policy_version, reason_code "
				+ "FROM quality_result WHERE generation_attempt_id = ?",
			pending.id().value());
		assertThat(row.get("id")).isEqualTo(firstId.value());
		assertThat(row.get("decision")).isEqualTo(QualityDecision.PASS.name());
		assertThat((BigDecimal) row.get("identity_preservation")).isEqualByComparingTo("0.91");
		assertThat((BigDecimal) row.get("garment_fidelity")).isEqualByComparingTo("0.92");
		assertThat((BigDecimal) row.get("color_fidelity")).isEqualByComparingTo("0.93");
		assertThat(row.get("anatomy_valid")).isEqualTo(true);
		assertThat(row.get("artifact_detected")).isEqualTo(false);
		assertThat(row.get("quality_policy_version")).isEqualTo("quality-v2");
		assertThat(row.get("reason_code")).isNull();
		assertNoMediaInRows();
	}

	@Test
	void saveQualityResultRejectsConflictingQualityV2DecisionOrScores() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt pending = pendingAttempt(job.id(), 1);
		store.insertAttempt(pending);

		QualityResult first = new QualityResult(
			new QualityResultId(UUID.randomUUID()),
			pending.id(),
			QualityDecision.PASS,
			new BigDecimal("0.91"),
			new BigDecimal("0.92"),
			new BigDecimal("0.93"),
			Boolean.TRUE,
			Boolean.FALSE,
			NOW.plusSeconds(2),
			"quality-v2",
			null);
		store.saveQualityResult(first);

		QualityResult conflictingDecision = new QualityResult(
			new QualityResultId(UUID.randomUUID()),
			pending.id(),
			QualityDecision.FAIL,
			new BigDecimal("0.91"),
			new BigDecimal("0.92"),
			new BigDecimal("0.93"),
			Boolean.TRUE,
			Boolean.FALSE,
			NOW.plusSeconds(3),
			"quality-v2",
			"ANATOMY_INVALID");
		assertThatThrownBy(() -> store.saveQualityResult(conflictingDecision))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("quality result conflict for generation attempt " + pending.id().value());

		QualityResult conflictingScores = new QualityResult(
			new QualityResultId(UUID.randomUUID()),
			pending.id(),
			QualityDecision.PASS,
			new BigDecimal("0.99"),
			new BigDecimal("0.92"),
			new BigDecimal("0.93"),
			Boolean.TRUE,
			Boolean.FALSE,
			NOW.plusSeconds(4),
			"quality-v2",
			null);
		assertThatThrownBy(() -> store.saveQualityResult(conflictingScores))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("quality result conflict for generation attempt " + pending.id().value());

		assertThat(jdbc.queryForObject(
			"SELECT decision FROM quality_result WHERE generation_attempt_id = ?",
			String.class,
			pending.id().value())).isEqualTo(QualityDecision.PASS.name());
	}

	@Test
	void findQualityResultRoundTripsPopulatedRowExactly() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt pending = pendingAttempt(job.id(), 1);
		store.insertAttempt(pending);

		QualityResult saved = new QualityResult(
			new QualityResultId(UUID.randomUUID()),
			pending.id(),
			QualityDecision.PASS,
			new BigDecimal("0.88"),
			new BigDecimal("0.86"),
			new BigDecimal("0.90"),
			Boolean.TRUE,
			Boolean.FALSE,
			NOW.plusSeconds(5),
			"quality-v2",
			null);
		store.saveQualityResult(saved);

		assertThat(store.findQualityResult(pending.id())).contains(saved);
	}

	@Test
	void findQualityResultReturnsEmptyWhenNoRowWasPersisted() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt pending = pendingAttempt(job.id(), 1);
		store.insertAttempt(pending);

		assertThat(store.findQualityResult(pending.id())).isEmpty();
		assertThat(store.findQualityResult(new GenerationAttemptId(UUID.randomUUID()))).isEmpty();
	}

	@Test
	void markCancelledSubjectDeletedAndCompletedPersistFlags() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		Instant cancelledAt = NOW.plusSeconds(3);
		Instant subjectDeletedAt = NOW.plusSeconds(4);

		store.markCancelled(job.id(), cancelledAt);
		store.markSubjectDeleted(job.id(), subjectDeletedAt);
		store.markJobCompleted(job.id(), NOW.plusSeconds(5));

		GenerationJob loaded = store.findJob(job.id()).orElseThrow();
		assertThat(loaded.status()).isEqualTo("FAILED");
		assertThat(loaded.terminalReason()).isEqualTo(GenerationJob.CANCELLED_BY_USER);
		assertThat(loaded.cancelledAt()).isEqualTo(cancelledAt);
		assertThat(loaded.subjectDeletedAt()).isEqualTo(subjectDeletedAt);
		assertThat(loaded.completedAt()).isEqualTo(cancelledAt);
	}

	@Test
	void listAttemptsReturnsAttemptsForJobAndMarkIndeterminate() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationJob other = insertQueuedJob(userId);
		GenerationAttempt first = pendingAttempt(job.id(), 1);
		GenerationAttempt second = pendingAttempt(job.id(), 2);
		store.insertAttempt(first);
		store.insertAttempt(second);
		store.insertAttempt(pendingAttempt(other.id(), 1));

		store.markSubmissionIndeterminate(first.id());

		assertThat(store.listAttempts(job.id()))
			.extracting(GenerationAttempt::id)
			.containsExactly(first.id(), second.id());
		assertThat(store.findAttempt(first.id()).orElseThrow().submissionIndeterminate()).isTrue();
		assertThat(store.findAttempt(second.id()).orElseThrow().submissionIndeterminate()).isFalse();
	}

	@Test
	void createAcceptedPersistsFingerprintPlanQuotaAndPendingOutbox() {
		UserId userId = insertUser();
		AvatarImageId avatarImageId = insertAvatar(userId);
		VaultItemId bottom = insertVaultItem(userId, "bottom");
		VaultItemId top = insertVaultItem(userId, "top");
		GenerationJob job = acceptedJob(
			userId,
			avatarImageId,
			List.of(top, bottom),
			new ClientRequestId("req-create-1"),
			"fp-create-1");

		GenerationStore.CreateAcceptedResult result = store.createAccepted(job);

		assertThat(result).isInstanceOf(GenerationStore.CreateAcceptedResult.Created.class);
		GenerationJob loaded = store.findJob(job.id()).orElseThrow();
		assertThat(loaded.status()).isEqualTo("QUEUED");
		assertThat(loaded.requestFingerprint()).isEqualTo("fp-create-1");
		assertThat(loaded.totalSteps()).isEqualTo(2);
		assertThat(loaded.currentStep()).isEqualTo(1);
		assertThat(loaded.orchestrationDeadlineAt()).isEqualTo(NOW.plusSeconds(1800));
		RenderPlan plan = store.findRenderPlan(job.id()).orElseThrow();
		assertThat(plan.steps()).extracting(step -> step.items().getFirst().vaultItemId())
			.containsExactly(bottom, top);
		assertThat(store.findQuotaReservation(job.id())).isPresent();
		assertThat(store.listWorkOutbox(job.id()))
			.hasSize(1)
			.first()
			.satisfies(event -> {
				assertThat(event.publishState()).isEqualTo(WorkOutbox.PENDING);
				assertThat(event.notBefore()).isEqualTo(NOW);
			});
	}

	@Test
	void createAcceptedReplaySameFingerprintDoesNotDuplicateQuotaOrOutbox() {
		UserId userId = insertUser();
		GenerationJob job = acceptedJob(userId, null, List.of(), new ClientRequestId("req-replay"), "fp-same");

		assertThat(store.createAccepted(job)).isInstanceOf(GenerationStore.CreateAcceptedResult.Created.class);
		GenerationStore.CreateAcceptedResult replay = store.createAccepted(new GenerationJob(
			new GenerationJobId(UUID.randomUUID()),
			userId,
			null,
			"QUEUED",
			"try-on",
			job.clientRequestId(),
			NOW,
			null,
			null,
			List.of(),
			null,
			null,
			"fp-same",
			null,
			0,
			NOW,
			null,
			1,
			1,
			1,
			NOW.plusSeconds(1800),
			null,
			RetryPolicySnapshot.forStepCountV2(1)));

		assertThat(replay).isInstanceOf(GenerationStore.CreateAcceptedResult.Replayed.class);
		assertThat(((GenerationStore.CreateAcceptedResult.Replayed) replay).job().id()).isEqualTo(job.id());
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_job WHERE user_id = ? AND client_request_id = ?",
			Integer.class,
			userId.value(),
			job.clientRequestId().value())).isEqualTo(1);
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_quota_reservation WHERE user_id = ?",
			Integer.class,
			userId.value())).isEqualTo(1);
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_work_outbox WHERE generation_job_id = ?",
			Integer.class,
			job.id().value())).isEqualTo(1);
	}

	@Test
	void createSingleItemPersistsFrozenIntentClaimAndOneOutboxWithReplayPrecedence() {
		UserId owner = insertUser();
		AvatarImageId avatar = insertAvatar(owner);
		UploadId upload = new UploadId(UUID.randomUUID());
		GenerationJob job = acceptedJob(owner, avatar, List.of(), new ClientRequestId("single-key"), "single-fp");
		FrozenSingleItemIntent intent = new FrozenSingleItemIntent(
			job.id(), owner, avatar, new MediaAssetId(UUID.randomUUID()), 1, 1);
		CreateSingleItemGenerationCommand command = new CreateSingleItemGenerationCommand(owner, upload, "single-key");

		assertThat(store.createSingleItem(command, intent, job))
			.isInstanceOf(GenerationStore.CreateSingleItemResult.Created.class);
		assertThat(jdbc.queryForMap(
			"SELECT product_source_upload_id, product_media_id, render_step_ordinal, render_step_count "
				+ "FROM generation_job WHERE id = ?", job.id().value()))
			.containsEntry("product_source_upload_id", upload.value())
			.containsEntry("product_media_id", intent.productMediaId().value())
			.containsEntry("render_step_ordinal", 1)
			.containsEntry("render_step_count", 1);
		assertThat(store.listWorkOutbox(job.id())).hasSize(1);

		assertThat(store.findFrozenSingleItemIntent(job.id())).contains(intent);
		assertThat(store.findProductSourceUploadId(job.id(), owner)).contains(upload);
		assertThat(store.findProductSourceUploadId(job.id(), insertUser())).isEmpty();
		assertThat(store.findProductSourceUploadId(new GenerationJobId(UUID.randomUUID()), owner)).isEmpty();
		GenerationJob legacyJob = acceptedJob(
			owner, avatar, List.of(), new ClientRequestId("legacy-key"), "legacy-fp");
		store.createAccepted(legacyJob);
		assertThat(store.findProductSourceUploadId(legacyJob.id(), owner)).isEmpty();
		GenerationJob replayJob = acceptedJob(owner, avatar, List.of(), new ClientRequestId("single-key"), "single-fp");
		FrozenSingleItemIntent replayIntent = new FrozenSingleItemIntent(
			replayJob.id(), owner, avatar, new MediaAssetId(UUID.randomUUID()), 1, 1);
		assertThat(store.createSingleItem(command, replayIntent, replayJob))
			.isInstanceOf(GenerationStore.CreateSingleItemResult.Replayed.class);
		assertThat(store.listWorkOutbox(job.id())).hasSize(1);

		GenerationJob secondIntent = acceptedJob(owner, avatar, List.of(), new ClientRequestId("other-key"), "other-fp");
		FrozenSingleItemIntent secondFrozen = new FrozenSingleItemIntent(
			secondIntent.id(), owner, avatar, new MediaAssetId(UUID.randomUUID()), 1, 1);
		assertThat(store.createSingleItem(
			new CreateSingleItemGenerationCommand(owner, upload, "other-key"), secondFrozen, secondIntent))
			.isInstanceOf(GenerationStore.CreateSingleItemResult.ProductUploadAlreadyClaimed.class);
	}

	@Test
	void concurrentDifferentIntentsForSameUploadYieldOneClaim() throws Exception {
		UserId owner = insertUser();
		AvatarImageId avatar = insertAvatar(owner);
		UploadId upload = new UploadId(UUID.randomUUID());
		GenerationJob firstJob = acceptedJob(
			owner, avatar, List.of(), new ClientRequestId("claim-first"), "claim-fp-first");
		GenerationJob secondJob = acceptedJob(
			owner, avatar, List.of(), new ClientRequestId("claim-second"), "claim-fp-second");
		CreateSingleItemGenerationCommand firstCommand =
			new CreateSingleItemGenerationCommand(owner, upload, "claim-first");
		CreateSingleItemGenerationCommand secondCommand =
			new CreateSingleItemGenerationCommand(owner, upload, "claim-second");
		FrozenSingleItemIntent firstIntent = new FrozenSingleItemIntent(
			firstJob.id(), owner, avatar, new MediaAssetId(UUID.randomUUID()), 1, 1);
		FrozenSingleItemIntent secondIntent = new FrozenSingleItemIntent(
			secondJob.id(), owner, avatar, new MediaAssetId(UUID.randomUUID()), 1, 1);
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		AtomicReference<GenerationStore.CreateSingleItemResult> firstResult = new AtomicReference<>();
		AtomicReference<GenerationStore.CreateSingleItemResult> secondResult = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		try {
			pool.submit(() -> runCreateSingleItem(
				firstCommand, firstIntent, firstJob, ready, start, firstResult, failure));
			pool.submit(() -> runCreateSingleItem(
				secondCommand, secondIntent, secondJob, ready, start, secondResult, failure));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(failure.get()).isNull();
		assertThat(List.of(firstResult.get(), secondResult.get()))
			.filteredOn(GenerationStore.CreateSingleItemResult.Created.class::isInstance).hasSize(1);
		assertThat(List.of(firstResult.get(), secondResult.get()))
			.filteredOn(GenerationStore.CreateSingleItemResult.ProductUploadAlreadyClaimed.class::isInstance).hasSize(1);
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_job WHERE product_source_upload_id = ?",
			Integer.class,
			upload.value())).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM generation_work_outbox", Integer.class)).isEqualTo(1);
	}

	@Test
	void createAcceptedDifferentFingerprintIsConflict() {
		UserId userId = insertUser();
		GenerationJob job = acceptedJob(userId, null, List.of(), new ClientRequestId("req-conflict"), "fp-one");
		store.createAccepted(job);

		GenerationStore.CreateAcceptedResult conflict = store.createAccepted(new GenerationJob(
			new GenerationJobId(UUID.randomUUID()),
			userId,
			null,
			"QUEUED",
			"try-on",
			job.clientRequestId(),
			NOW,
			null,
			null,
			List.of(),
			null,
			null,
			"fp-two",
			null,
			0,
			NOW,
			null,
			1,
			1,
			1,
			NOW.plusSeconds(1800),
			null,
			RetryPolicySnapshot.forStepCountV2(1)));

		assertThat(conflict).isInstanceOf(GenerationStore.CreateAcceptedResult.FingerprintConflict.class);
		assertThat(((GenerationStore.CreateAcceptedResult.FingerprintConflict) conflict).existing().id())
			.isEqualTo(job.id());
	}

	@Test
	void concurrentSameKeyCreatesYieldOneJob() throws Exception {
		UserId userId = insertUser();
		ClientRequestId requestId = new ClientRequestId("req-concurrent");
		GenerationJob first = acceptedJob(userId, null, List.of(), requestId, "fp-concurrent");
		GenerationJob second = new GenerationJob(
			new GenerationJobId(UUID.randomUUID()),
			userId,
			null,
			"QUEUED",
			"try-on",
			requestId,
			NOW,
			null,
			null,
			List.of(),
			null,
			null,
			"fp-concurrent",
			null,
			0,
			NOW,
			null,
			1,
			1,
			1,
			NOW.plusSeconds(1800),
			null,
			RetryPolicySnapshot.forStepCountV2(1));
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		AtomicReference<GenerationStore.CreateAcceptedResult> firstResult = new AtomicReference<>();
		AtomicReference<GenerationStore.CreateAcceptedResult> secondResult = new AtomicReference<>();
		AtomicReference<Throwable> failure = new AtomicReference<>();
		try {
			pool.submit(() -> runCreate(first, ready, start, firstResult, failure));
			pool.submit(() -> runCreate(second, ready, start, secondResult, failure));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			pool.shutdown();
			assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(failure.get()).isNull();
		assertThat(firstResult.get()).isNotNull();
		assertThat(secondResult.get()).isNotNull();
		GenerationJobId winner = createdOrReplayedId(firstResult.get());
		assertThat(createdOrReplayedId(secondResult.get())).isEqualTo(winner);
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_job WHERE user_id = ? AND client_request_id = ?",
			Integer.class,
			userId.value(),
			requestId.value())).isEqualTo(1);
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_quota_reservation WHERE user_id = ?",
			Integer.class,
			userId.value())).isEqualTo(1);
	}

	@Test
	void cancelVersusCompleteHasOneWinner() throws Exception {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt pending = pendingAttempt(job.id(), 1);
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
					completeWon.set(store.completeTerminalIfActive(
						finishedAttempt(pending, when, "SUCCESS", null),
						when));
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
			assertThat(loaded.status()).isEqualTo("FAILED");
			assertThat(loaded.terminalReason()).isEqualTo(GenerationJob.CANCELLED_BY_USER);
			assertThat(loaded.cancelledAt()).isEqualTo(when);
		}
		else {
			assertThat(loaded.status()).isEqualTo("COMPLETED");
			assertThat(loaded.cancelledAt()).isNull();
		}
	}

	@Test
	void findJobForUserIsOwnerScoped() {
		UserId owner = insertUser();
		UserId other = insertUser();
		GenerationJob job = insertQueuedJob(owner);

		assertThat(store.findJobForUser(job.id(), owner)).isPresent();
		assertThat(store.findJobForUser(job.id(), other)).isEmpty();
		assertThat(store.findJobForUser(new GenerationJobId(UUID.randomUUID()), owner)).isEmpty();
	}

	@Test
	void cancelSetsFailedCancelledByUserAndTimestamps() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		Instant cancelledAt = NOW.plusSeconds(3);

		assertThat(store.cancelIfActive(job.id(), cancelledAt)).isTrue();
		assertThat(store.cancelIfActive(job.id(), cancelledAt.plusSeconds(1))).isFalse();

		GenerationJob loaded = store.findJob(job.id()).orElseThrow();
		assertThat(loaded.status()).isEqualTo("FAILED");
		assertThat(loaded.terminalReason()).isEqualTo(GenerationJob.CANCELLED_BY_USER);
		assertThat(loaded.cancelledAt()).isEqualTo(cancelledAt);
		assertThat(loaded.completedAt()).isEqualTo(cancelledAt);
	}

	@Test
	void markSubmittedAndGeneratingRequireDurableProviderJobId() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt pending = pendingAttempt(job.id(), 1);
		store.insertAttempt(pending);

		assertThat(store.markSubmitted(job.id())).isFalse();
		assertThat(store.markGenerating(job.id())).isFalse();
		store.recordProviderExecution(pending.id(), new ProviderJobId("provider-job-transition"));
		assertThat(store.markSubmitted(job.id())).isTrue();
		assertThat(store.findJob(job.id()).orElseThrow().status()).isEqualTo("SUBMITTED");
		assertThat(store.markGenerating(job.id())).isTrue();
		assertThat(store.findJob(job.id()).orElseThrow().status()).isEqualTo("GENERATING");
	}
	@Test
	void markSubmittedAcceptsClaimedRetryingJobWithPendingCurrentAttempt() {
		UserId userId = insertUser();
		GenerationJob job = retryingJob(userId, NOW, RetryPolicySnapshot.forStepCount(1));
		GenerationAttempt predecessor = pendingAttempt(job.id(), 1);
		store.insertJob(job);
		store.insertAttempt(predecessor);

		GenerationAttempt next = pendingAttempt(job.id(), 2);
		assertThat(store.claimDueRetry(job.id(), job.statusVersion(), NOW, next)).isTrue();
		assertThat(store.recordProviderExecutionIfActive(
			next.id(),
			new ProviderJobId("provider-job-retry"))).isTrue();

		assertThat(store.markSubmitted(job.id())).isTrue();
		assertThat(store.findJob(job.id()).orElseThrow().status()).isEqualTo(GenerationJob.SUBMITTED);
	}
	@Test
	void markQualityCheckIfActiveFencesGeneratingToQualityCheckTransition() {
		UserId userId = insertUser();
		GenerationJob job = insertGeneratingJob(userId);
		GenerationJob generating = store.findJob(job.id()).orElseThrow();
		assertThat(generating.status()).isEqualTo(GenerationJob.GENERATING);

		assertThat(store.markQualityCheckIfActive(job.id(), generating.statusVersion() - 1)).isFalse();
		assertThat(store.findJob(job.id()).orElseThrow().status()).isEqualTo(GenerationJob.GENERATING);

		assertThat(store.markQualityCheckIfActive(job.id(), generating.statusVersion())).isTrue();
		GenerationJob qualityCheck = store.findJob(job.id()).orElseThrow();
		assertThat(qualityCheck.status()).isEqualTo(GenerationJob.QUALITY_CHECK);
		assertThat(qualityCheck.statusVersion()).isEqualTo(generating.statusVersion() + 1);
		assertThat(qualityCheck.updatedAt()).isEqualTo(NOW);

		assertThat(store.markQualityCheckIfActive(job.id(), generating.statusVersion())).isFalse();
		GenerationJob fenced = store.findJob(job.id()).orElseThrow();
		assertThat(fenced.status()).isEqualTo(GenerationJob.QUALITY_CHECK);
		assertThat(fenced.statusVersion()).isEqualTo(generating.statusVersion() + 1);
	}

	@Test
	void markQualityCheckIfActiveIsIdempotentWhenQualityCheckAlreadyHoldsTheFence() {
		UserId userId = insertUser();
		GenerationJob job = insertGeneratingJob(userId);
		int generatingVersion = store.findJob(job.id()).orElseThrow().statusVersion();
		assertThat(store.markQualityCheckIfActive(job.id(), generatingVersion)).isTrue();
		int fencedVersion = store.findJob(job.id()).orElseThrow().statusVersion();

		assertThat(store.markQualityCheckIfActive(job.id(), fencedVersion)).isTrue();
		GenerationJob replayed = store.findJob(job.id()).orElseThrow();
		assertThat(replayed.status()).isEqualTo(GenerationJob.QUALITY_CHECK);
		assertThat(replayed.statusVersion()).isEqualTo(fencedVersion);
	}

	@Test
	void markQualityCheckIfActiveRejectsCompletedCancelledAndSubjectDeletedJobs() {
		UserId userId = insertUser();

		GenerationJob completed = insertGeneratingJob(userId);
		store.markJobCompleted(completed.id(), NOW.plusSeconds(1));
		assertThat(store.markQualityCheckIfActive(completed.id(), 0)).isFalse();

		GenerationJob cancelled = insertGeneratingJob(userId);
		store.cancelIfActive(cancelled.id(), NOW.plusSeconds(1));
		assertThat(store.markQualityCheckIfActive(cancelled.id(), 0)).isFalse();

		GenerationJob deleted = insertGeneratingJob(userId);
		store.markSubjectDeleted(deleted.id(), NOW.plusSeconds(1));
		assertThat(store.markQualityCheckIfActive(deleted.id(), 0)).isFalse();
	}



	@Test
	void createAcceptedEnforcesAdmissionLimits() {
		UserId userId = insertUser();
		GenerationJob first = acceptedJob(userId, null, List.of(), new ClientRequestId("req-limit-1"), "fp-1");
		GenerationJob second = acceptedJob(userId, null, List.of(), new ClientRequestId("req-limit-2"), "fp-2");
		GenerationJob third = acceptedJob(userId, null, List.of(), new ClientRequestId("req-limit-3"), "fp-3");
		assertThat(store.createAccepted(first)).isInstanceOf(GenerationStore.CreateAcceptedResult.Created.class);
		assertThat(store.createAccepted(second)).isInstanceOf(GenerationStore.CreateAcceptedResult.Created.class);
		assertThat(store.createAccepted(third)).isInstanceOf(GenerationStore.CreateAcceptedResult.QuotaRejected.class);

		List<VaultItemId> tooMany = new java.util.ArrayList<>();
		for (int i = 0; i < 7; i++) {
			tooMany.add(insertVaultItem(userId));
		}
		GenerationJob oversized = acceptedJob(
			userId,
			null,
			tooMany,
			new ClientRequestId("req-limit-products"),
			"fp-products",
			RetryPolicySnapshot.forStepCount(tooMany.size()));
		assertThat(store.createAccepted(oversized))
			.isInstanceOf(GenerationStore.CreateAcceptedResult.QuotaRejected.class);
	}

	@Test
	void insertJobLoadsNewColumns() {
		UserId userId = insertUser();
		GenerationJob job = acceptedJob(userId, null, List.of(), new ClientRequestId("req-insert-cols"), "fp-insert");
		store.insertJob(job);

		GenerationJob loaded = store.findJob(job.id()).orElseThrow();
		assertThat(loaded.requestFingerprint()).isEqualTo("fp-insert");
		assertThat(loaded.statusVersion()).isZero();
		assertThat(loaded.currentStep()).isEqualTo(1);
		assertThat(loaded.totalSteps()).isEqualTo(1);
		assertThat(loaded.orchestrationDeadlineAt()).isEqualTo(NOW.plusSeconds(1800));
	}

	@Test
	void markPreflightMovesQueuedAndIsIdempotent() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);

		assertThat(store.markPreflight(job.id())).isTrue();
		assertThat(store.findJob(job.id()).orElseThrow().status()).isEqualTo(GenerationJob.PREFLIGHT);
		assertThat(store.markPreflight(job.id())).isFalse();
		assertThat(store.findJob(job.id()).orElseThrow().status()).isEqualTo(GenerationJob.PREFLIGHT);
	}

	@Test
	void failIfDeadlineExceededTerminalizesActiveJobTogether() {
		UserId userId = insertUser();
		GenerationJob job = overdueJob(userId, new ClientRequestId("req-deadline"));
		store.insertJob(job);

		assertThat(store.failIfDeadlineExceeded(job.id(), NOW)).isTrue();
		GenerationJob loaded = store.findJob(job.id()).orElseThrow();
		assertThat(loaded.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(loaded.terminalReason()).isEqualTo(TerminalReason.ORCHESTRATION_DEADLINE_EXCEEDED.name());
		assertThat(loaded.completedAt()).isEqualTo(NOW);
		assertThat(store.failIfDeadlineExceeded(job.id(), NOW.plusSeconds(1))).isFalse();
	}

	@Test
	void failIfDeadlineExceededLeavesFutureDeadlineActive() {
		UserId userId = insertUser();
		GenerationJob job = insertQueuedJob(userId);

		assertThat(store.failIfDeadlineExceeded(job.id(), NOW)).isFalse();
		GenerationJob loaded = store.findJob(job.id()).orElseThrow();
		assertThat(loaded.status()).isEqualTo(GenerationJob.QUEUED);
		assertThat(loaded.completedAt()).isNull();
	}
	@Test
	void deleteUserLinkedGenerationDataUnlinksLooksAndRemovesGenerationLedger() {
		UserId owner = insertUser();
		UserId otherUser = insertUser();
		GenerationJob owned = acceptedJob(
			owner,
			null,
			List.of(),
			new ClientRequestId("req-delete-owner"),
			"fp-delete-owner");
		assertThat(store.createAccepted(owned))
			.isInstanceOf(GenerationStore.CreateAcceptedResult.Created.class);
		GenerationJob other = insertQueuedJob(otherUser);
		GenerationAttempt attempt = pendingAttempt(owned.id(), 1);
		store.insertAttempt(attempt);

		store.saveQualityResult(new QualityResult(
			new QualityResultId(UUID.randomUUID()),
			attempt.id(),
			QualityDecision.FAIL,
			new BigDecimal("0.1"),
			new BigDecimal("0.2"),
			new BigDecimal("0.3"),
			Boolean.FALSE,
			Boolean.TRUE,
			NOW,
			"quality-policy-v1",
			"QUALITY_REJECTED"));

		UUID lookId = UUID.randomUUID();
		jdbc.update(
			"INSERT INTO look "
				+ "(id, user_id, avatar_profile_id, generation_job_id, studio_session_id, storage_key, "
				+ "render_plan, provider, provider_model, quality_status, created_at) "
				+ "VALUES (?, ?, NULL, ?, NULL, ?, NULL, NULL, NULL, ?, ?)",
			lookId,
			owner.value(),
			owned.id().value(),
			"users/" + owner.value() + "/looks/" + lookId + ".jpg",
			"PASS",
			Timestamp.from(NOW));
		UUID eventId = UUID.randomUUID();
		jdbc.update(
			"INSERT INTO generation_notification_event "
				+ "(id, user_id, generation_job_id, type, look_id, created_at, deadline_at) "
				+ "VALUES (?, ?, ?, ?, ?, ?, ?)",
			eventId,
			owner.value(),
			owned.id().value(),
			"LOOK_READY",
			lookId,
			Timestamp.from(NOW),
			Timestamp.from(NOW.plusSeconds(60)));
		jdbc.update(
			"INSERT INTO generation_notification_delivery "
				+ "(id, event_id, device_identity, status, attempt_count, next_attempt_at, "
				+ "provider_message_id, terminal_result) VALUES (?, ?, ?, ?, 0, NULL, NULL, NULL)",
			UUID.randomUUID(),
			eventId,
			"device-owner",
			"PENDING");
		jdbc.update(
			"INSERT INTO generation_callback_inbox "
				+ "(id, dedupe_hash, generation_attempt_id, candidate_provider_job_id, "
				+ "safe_terminal_hint, received_at) VALUES (?, ?, ?, ?, ?, ?)",
			UUID.randomUUID(),
			"delete-callback",
			attempt.id().value(),
			"provider-job",
			"SUCCESS",
			Timestamp.from(NOW));
		jdbc.update(
			"INSERT INTO generation_provider_capacity_slot "
				+ "(id, provider_key, generation_attempt_id, leased_at, lease_expires_at, released_at) "
				+ "VALUES (?, ?, ?, ?, ?, NULL)",
			UUID.randomUUID(),
			"example-provider",
			attempt.id().value(),
			Timestamp.from(NOW),
			Timestamp.from(NOW.plusSeconds(60)));

		store.deleteUserLinkedGenerationData(owner);
		store.deleteUserLinkedGenerationData(owner);

		assertThat(store.findJob(owned.id())).isEmpty();
		assertThat(store.findAttempt(attempt.id())).isEmpty();
		assertThat(store.findJob(other.id())).isPresent();
		assertThat(jdbc.queryForObject(
			"SELECT generation_job_id FROM look WHERE id = ?",
			UUID.class,
			lookId)).isNull();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_job WHERE id = ?",
			Integer.class,
			owned.id().value())).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_attempt WHERE id = ?",
			Integer.class,
			attempt.id().value())).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM quality_result WHERE generation_attempt_id = ?",
			Integer.class,
			attempt.id().value())).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_callback_inbox WHERE generation_attempt_id = ?",
			Integer.class,
			attempt.id().value())).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_provider_capacity_slot WHERE generation_attempt_id = ?",
			Integer.class,
			attempt.id().value())).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_work_outbox WHERE generation_job_id = ?",
			Integer.class,
			owned.id().value())).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_quota_reservation WHERE generation_job_id = ?",
			Integer.class,
			owned.id().value())).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_notification_event WHERE generation_job_id = ?",
			Integer.class,
			owned.id().value())).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_notification_delivery WHERE event_id = ?",
			Integer.class,
			eventId)).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_render_step WHERE generation_job_id = ?",
			Integer.class,
			owned.id().value())).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM generation_job_product WHERE generation_job_id = ?",
			Integer.class,
			owned.id().value())).isZero();
		assertThat(jdbc.queryForObject(
			"SELECT COUNT(*) FROM look WHERE id = ?",
			Integer.class,
			lookId)).isEqualTo(1);
	}

	private void runAcquire(
		GenerationAttemptId attemptId,
		Instant ownedAt,
		CountDownLatch ready,
		CountDownLatch start,
		AtomicBoolean won,
		AtomicReference<Throwable> failure) {
		ready.countDown();
		try {
			if (!start.await(10, TimeUnit.SECONDS)) {
				failure.compareAndSet(null, new IllegalStateException("acquire start latch timed out"));
				return;
			}
			won.set(store.tryAcquireSubmitOwnership(attemptId, ownedAt));
		}
		catch (Throwable thrown) {
			failure.compareAndSet(null, thrown);
		}
	}

	private void runCreate(
		GenerationJob job,
		CountDownLatch ready,
		CountDownLatch start,
		AtomicReference<GenerationStore.CreateAcceptedResult> result,
		AtomicReference<Throwable> failure) {
		ready.countDown();
		try {
			if (!start.await(10, TimeUnit.SECONDS)) {
				failure.compareAndSet(null, new IllegalStateException("create start latch timed out"));
				return;
			}
			result.set(store.createAccepted(job));
		}
		catch (Throwable thrown) {
			failure.compareAndSet(null, thrown);
		}
	}

	private void runCreateSingleItem(
		CreateSingleItemGenerationCommand command,
		FrozenSingleItemIntent intent,
		GenerationJob job,
		CountDownLatch ready,
		CountDownLatch start,
		AtomicReference<GenerationStore.CreateSingleItemResult> result,
		AtomicReference<Throwable> failure) {
		ready.countDown();
		try {
			if (!start.await(10, TimeUnit.SECONDS)) {
				failure.compareAndSet(null, new IllegalStateException("single-item create start latch timed out"));
				return;
			}
			result.set(store.createSingleItem(command, intent, job));
		}
		catch (Throwable thrown) {
			failure.compareAndSet(null, thrown);
		}
	}

	private static GenerationJobId createdOrReplayedId(GenerationStore.CreateAcceptedResult result) {
		if (result instanceof GenerationStore.CreateAcceptedResult.Created created) {
			return created.job().id();
		}
		if (result instanceof GenerationStore.CreateAcceptedResult.Replayed replayed) {
			return replayed.job().id();
		}
		throw new AssertionError("unexpected create result " + result);
	}

	private static GenerationJob acceptedJob(
		UserId userId,
		AvatarImageId avatarImageId,
		List<VaultItemId> products,
		ClientRequestId clientRequestId,
		String fingerprint) {
		return acceptedJob(
			userId,
			avatarImageId,
			products,
			clientRequestId,
			fingerprint,
			RetryPolicySnapshot.forStepCountV2(products.isEmpty() ? 1 : products.size()));
	}

	private static GenerationJob acceptedJob(
		UserId userId,
		AvatarImageId avatarImageId,
		List<VaultItemId> products,
		ClientRequestId clientRequestId,
		String fingerprint,
		RetryPolicySnapshot snapshot) {
		return new GenerationJob(
			new GenerationJobId(UUID.randomUUID()),
			userId,
			null,
			"QUEUED",
			"try-on",
			clientRequestId,
			NOW,
			null,
			avatarImageId,
			products,
			null,
			null,
			fingerprint,
			null,
			0,
			NOW,
			null,
			1,
			products.isEmpty() ? 1 : products.size(),
			1,
			NOW.plusSeconds(1800),
			null,
			snapshot);
	}

	private static GenerationJob overdueJob(UserId userId, ClientRequestId clientRequestId) {
		return new GenerationJob(
			new GenerationJobId(UUID.randomUUID()),
			userId,
			null,
			GenerationJob.QUEUED,
			"try-on",
			clientRequestId,
			NOW,
			null,
			null,
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
			NOW.minusSeconds(1));
	}

	private GenerationJob insertQueuedJob(UserId userId) {
		return insertQueuedJob(userId, null);
	}

	private GenerationJob insertQueuedJob(UserId userId, AvatarImageId avatarImageId) {
		GenerationJob job = job(userId, avatarImageId, List.of(), new ClientRequestId("req-" + UUID.randomUUID()));
		store.insertJob(job);
		return job;
	}

	private GenerationJob insertGeneratingJob(UserId userId) {
		GenerationJob job = insertQueuedJob(userId);
		GenerationAttempt attempt = pendingAttempt(job.id(), 1);
		store.insertAttempt(attempt);
		store.recordProviderExecution(attempt.id(), new ProviderJobId("provider-job-" + UUID.randomUUID()));
		store.markSubmitted(job.id());
		store.markGenerating(job.id());
		return job;
	}


	private static GenerationJob job(
		UserId userId,
		AvatarImageId avatarImageId,
		List<VaultItemId> products,
		ClientRequestId clientRequestId) {
		return new GenerationJob(
			new GenerationJobId(UUID.randomUUID()),
			userId,
			null,
			"QUEUED",
			"try-on",
			clientRequestId,
			NOW,
			null,
			avatarImageId,
			products,
			null,
			null);
	}

	private static GenerationJob retryingJob(
		UserId userId,
		Instant retryNotBeforeAt,
		RetryPolicySnapshot snapshot) {
		return retryingJob(userId, retryNotBeforeAt, snapshot, NOW.plusSeconds(1800));
	}

	private static GenerationJob retryingJob(
		UserId userId,
		Instant retryNotBeforeAt,
		RetryPolicySnapshot snapshot,
		Instant orchestrationDeadlineAt) {
		return new GenerationJob(
			new GenerationJobId(UUID.randomUUID()),
			userId,
			null,
			GenerationJob.RETRYING,
			"try-on",
			new ClientRequestId("req-retrying-" + UUID.randomUUID()),
			NOW,
			null,
			null,
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
			orchestrationDeadlineAt,
			retryNotBeforeAt,
			snapshot);
	}
	private static GenerationJob retryingMultiStepJob(UserId userId, int totalSteps) {
		return new GenerationJob(
			new GenerationJobId(UUID.randomUUID()),
			userId,
			null,
			GenerationJob.RETRYING,
			"try-on",
			new ClientRequestId("req-retrying-multistep-" + UUID.randomUUID()),
			NOW,
			null,
			null,
			List.of(),
			null,
			null,
			null,
			null,
			0,
			NOW,
			null,
			1,
			totalSteps,
			3,
			NOW.plusSeconds(1800),
			NOW,
			RetryPolicySnapshot.forStepCount(totalSteps));
	}
	private static GenerationJob retryingMultiStepJobV2(UserId userId, int totalSteps) {
		return new GenerationJob(
			new GenerationJobId(UUID.randomUUID()),
			userId,
			null,
			GenerationJob.RETRYING,
			"try-on",
			new ClientRequestId("req-retrying-multistep-v2-" + UUID.randomUUID()),
			NOW,
			null,
			null,
			List.of(),
			null,
			null,
			null,
			null,
			0,
			NOW,
			null,
			1,
			totalSteps,
			3,
			NOW.plusSeconds(1800),
			NOW,
			RetryPolicySnapshot.forStepCountV2(totalSteps));
	}


	private static GenerationJob multiStepJob(UserId userId, int currentStep, int totalSteps) {
		return new GenerationJob(
			new GenerationJobId(UUID.randomUUID()),
			userId,
			null,
			GenerationJob.QUEUED,
			"try-on",
			new ClientRequestId("req-multistep-" + UUID.randomUUID()),
			NOW,
			null,
			null,
			List.of(),
			null,
			null,
			null,
			null,
			0,
			NOW,
			null,
			currentStep,
			totalSteps,
			3,
			NOW.plusSeconds(1800),
			null,
			RetryPolicySnapshot.forStepCount(totalSteps));
	}

	private static GenerationAttempt pendingAttempt(GenerationJobId jobId, int attemptNumber) {
		GenerationAttemptId attemptId = new GenerationAttemptId(UUID.randomUUID());
		return new GenerationAttempt(
			attemptId,
			jobId,
			"example-provider",
			null,
			null,
			attemptNumber,
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
	private static GenerationAttempt pendingAttemptWithStep(
		GenerationJobId jobId,
		int attemptNumber,
		UUID renderStepId
	) {
		GenerationAttempt attempt = pendingAttempt(jobId, attemptNumber);
		return new GenerationAttempt(
			attempt.id(),
			attempt.generationJobId(),
			attempt.provider(),
			attempt.model(),
			attempt.providerJobId(),
			attempt.attemptNumber(),
			attempt.startedAt(),
			attempt.completedAt(),
			attempt.latencyMs(),
			attempt.creditsUsed(),
			attempt.estimatedCost(),
			attempt.currency(),
			attempt.failureCode(),
			attempt.technicalOutcome(),
			attempt.submissionKey(),
			attempt.submitOwnedAt(),
			attempt.submissionIndeterminate(),
			attempt.tempStorageKey(),
			attempt.providerOutputRef(),
			renderStepId,
			attempt.transmissionCount(),
			attempt.providerAcceptedAt(),
			attempt.executionDeadlineAt(),
			attempt.submissionRecoveryDeadlineAt(),
			attempt.qualityPolicyVersion(),
			attempt.pricingCatalogVersion(),
			attempt.estimatedUnits(),
			attempt.unitPrice(),
			attempt.estimatedCostCeiling(),
			attempt.actualCost(),
			attempt.costStatus());
	}
	private static GenerationAttempt pendingAttemptWithPolicy(
		GenerationJobId jobId,
		GenerationAttemptId attemptId,
		int attemptNumber,
		String errorMappingContractVersion,
		int evaluatorRetryCount) {
		return new GenerationAttempt(
			attemptId,
			jobId,
			"example-provider",
			null,
			null,
			attemptNumber,
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
			null,
			null,
			0,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			errorMappingContractVersion,
			evaluatorRetryCount);
	}

	private void insertRenderStep(GenerationJob job, UUID renderStepId, int stepIndex) {
		jdbc.update(
			"INSERT INTO generation_render_step "
				+ "(id, generation_job_id, render_plan_version, step_index, created_at) "
				+ "VALUES (?, ?, ?, ?, ?)",
			renderStepId,
			job.id().value(),
			job.renderPlanVersion(),
			stepIndex,
			Timestamp.from(NOW));
	}


	private static GenerationAttempt finishedAttempt(
		GenerationAttempt pending,
		Instant completedAt,
		String technicalOutcome,
		String failureCode) {
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
			failureCode,
			technicalOutcome,
			pending.submissionKey(),
			NOW,
			false,
			new StorageKey("temp/user/generation/" + pending.id().value() + ".jpg"),
			new ProviderOutputRef("provider-output-1"));
	}

	private void assertNoMediaInRows() {
		for (String table : List.of("generation_job", "generation_attempt", "quality_result")) {
			List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM " + table);
			for (Map<String, Object> row : rows) {
				for (Object value : row.values()) {
					if (value == null) {
						continue;
					}
					if (value instanceof byte[] bytes) {
						assertThat(bytes).isEmpty();
						continue;
					}
					assertThat(String.valueOf(value))
						.doesNotContain("http://", "https://", "data:image", "/9j/", "iVBOR");
				}
			}
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

	private AvatarImageId insertAvatar(UserId userId) {
		AvatarProfileId profileId = new AvatarProfileId(UUID.randomUUID());
		AvatarImageId imageId = new AvatarImageId(UUID.randomUUID());
		jdbc.update(
			"INSERT INTO avatar_profile (id, user_id, primary_image_id, status, revision, created_at, updated_at) "
				+ "VALUES (?, ?, NULL, 'ACTIVE', 1, ?, ?)",
			profileId.value(),
			userId.value(),
			Timestamp.from(NOW),
			Timestamp.from(NOW));
		jdbc.update(
			"INSERT INTO avatar_image (id, profile_id, storage_key, quality_status, created_at) VALUES (?, ?, ?, 'ACCEPTED', ?)",
			imageId.value(),
			profileId.value(),
			"users/" + userId.value() + "/avatars/" + imageId.value() + ".jpg",
			Timestamp.from(NOW));
		jdbc.update(
			"UPDATE avatar_profile SET primary_image_id = ? WHERE id = ?",
			imageId.value(),
			profileId.value());
		return imageId;
	}

	private AvatarImageId insertAdditionalAvatarImage(UserId userId, AvatarImageId existing) {
		UUID profileId = jdbc.queryForObject(
			"SELECT profile_id FROM avatar_image WHERE id = ?",
			UUID.class,
			existing.value());
		AvatarImageId imageId = new AvatarImageId(UUID.randomUUID());
		jdbc.update(
			"INSERT INTO avatar_image (id, profile_id, storage_key, quality_status, created_at) VALUES (?, ?, ?, 'ACCEPTED', ?)",
			imageId.value(),
			profileId,
			"users/" + userId.value() + "/avatars/" + imageId.value() + ".jpg",
			Timestamp.from(NOW));
		return imageId;
	}


	private VaultItemId insertVaultItem(UserId userId) {
		return insertVaultItem(userId, null);
	}

	private VaultItemId insertVaultItem(UserId userId, String category) {
		VaultItemId vaultItemId = new VaultItemId(UUID.randomUUID());
		jdbc.update(
			"INSERT INTO vault_item (id, user_id, storage_key, category, created_at) VALUES (?, ?, ?, ?, ?)",
			vaultItemId.value(),
			userId.value(),
			"users/" + userId.value() + "/vault/" + vaultItemId.value() + ".jpg",
			category,
			Timestamp.from(NOW));
		return vaultItemId;
	}
}
