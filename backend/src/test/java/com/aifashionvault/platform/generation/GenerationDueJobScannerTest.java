package com.aifashionvault.platform.generation;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.GenerationAttempt;
import com.aifashionvault.generation.GenerationJob;
import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.generation.QualityResult;
import com.aifashionvault.generation.RetryPolicySnapshot;
import com.aifashionvault.generation.vton.ProviderJobId;
import com.aifashionvault.platform.queue.WorkOutboxStore;
import com.aifashionvault.shared.ClientRequestId;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.StudioSessionId;
import com.aifashionvault.shared.UserId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deterministic lost-wakeup repair tests for {@link GenerationDueJobScanner}
 * (Q42): fixed clock, fake stores, and no real sleeps. The scanner must only
 * insert opaque job IDs into the durable outbox and never touch attempt or
 * job state.
 */
class GenerationDueJobScannerTest {

	private static final Instant NOW = Instant.parse("2026-08-28T09:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final UserId USER_ID = new UserId(
		UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final StudioSessionId SESSION_ID = new StudioSessionId(
		UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final ClientRequestId CLIENT_REQUEST_ID = new ClientRequestId("req-scanner");
	private static final Instant CREATED_AT = NOW.minusSeconds(300);
	private static final Instant DEFAULT_DEADLINE = CREATED_AT.plus(GenerationJob.ORCHESTRATION_DEADLINE);

	private FakeGenerationStore generationStore;
	private RecordingOutboxStore outboxStore;
	private GenerationDueJobScanner scanner;

	@BeforeEach
	void setUp() {
		generationStore = new FakeGenerationStore();
		outboxStore = new RecordingOutboxStore();
		scanner = new GenerationDueJobScanner(generationStore, outboxStore, CLOCK);
	}

	@Test
	void dueRetryingJobIsReenqueuedWithPersistedRetryNotBeforeAt() {
		Instant retryNotBeforeAt = NOW.minusSeconds(15);
		GenerationJob due = retryingJob(retryNotBeforeAt, DEFAULT_DEADLINE);
		generationStore.put(due);
		generationStore.setDueJobs(List.of(due));

		int repaired = scanner.scanDueJobs();

		assertThat(repaired).isEqualTo(1);
		assertThat(generationStore.listedAt).containsExactly(NOW);
		assertThat(outboxStore.insertions).containsExactly(
			new RecordingOutboxStore.Inserted(due.id().value(), retryNotBeforeAt));
		GenerationJob unchanged = generationStore.findJob(due.id()).orElseThrow();
		assertThat(unchanged.status()).isEqualTo(GenerationJob.RETRYING);
		assertThat(unchanged.statusVersion()).isEqualTo(due.statusVersion());
		assertThat(unchanged.retryNotBeforeAt()).isEqualTo(retryNotBeforeAt);
	}

	@Test
	void terminalJobsReturnedByStoreAreSkippedWithoutInserts() {
		GenerationJob failed = new GenerationJob(
			new GenerationJobId(UUID.randomUUID()),
			USER_ID,
			SESSION_ID,
			GenerationJob.FAILED,
			"try-on",
			CLIENT_REQUEST_ID,
			CREATED_AT,
			NOW,
			null,
			List.of(),
			null,
			null,
			"scanner-fingerprint",
			1,
			3,
			CREATED_AT,
			"RETRY_BUDGET_EXHAUSTED",
			1,
			1,
			1,
			DEFAULT_DEADLINE,
			null,
			RetryPolicySnapshot.forStepCount(1),
			Optional.empty());
		GenerationJob completed = new GenerationJob(
			new GenerationJobId(UUID.randomUUID()),
			USER_ID,
			SESSION_ID,
			GenerationJob.COMPLETED,
			"try-on",
			CLIENT_REQUEST_ID,
			CREATED_AT,
			NOW,
			null,
			List.of(),
			null,
			null,
			"scanner-fingerprint",
			1,
			3,
			CREATED_AT,
			null,
			1,
			1,
			1,
			DEFAULT_DEADLINE,
			null,
			RetryPolicySnapshot.forStepCount(1),
			Optional.empty());
		generationStore.setDueJobs(List.of(failed, completed));

		int repaired = scanner.scanDueJobs();

		assertThat(repaired).isZero();
		assertThat(outboxStore.insertions).isEmpty();
		assertThat(generationStore.listedAt).containsExactly(NOW);
	}

	@Test
	void noDueJobsProduceNoInserts() {
		generationStore.setDueJobs(List.of());

		int repaired = scanner.scanDueJobs();

		assertThat(repaired).isZero();
		assertThat(generationStore.listedAt).containsExactly(NOW);
		assertThat(outboxStore.insertions).isEmpty();
	}

	@Test
	void scanIsBoundedToMaxJobsPerTick() {
		List<GenerationJob> dueJobs = IntStream.range(0, GenerationDueJobScanner.MAX_JOBS_PER_SCAN + 30)
			.mapToObj(index -> retryingJob(NOW.minusSeconds(index + 1L), DEFAULT_DEADLINE))
			.toList();
		generationStore.setDueJobs(dueJobs);

		int repaired = scanner.scanDueJobs();

		assertThat(repaired).isEqualTo(GenerationDueJobScanner.MAX_JOBS_PER_SCAN);
		List<RecordingOutboxStore.Inserted> expected = dueJobs.stream()
			.limit(GenerationDueJobScanner.MAX_JOBS_PER_SCAN)
			.map(job -> new RecordingOutboxStore.Inserted(job.id().value(), job.retryNotBeforeAt()))
			.toList();
		assertThat(outboxStore.insertions).containsExactlyElementsOf(expected);
	}

	@Test
	void deadlineExceededJobIsStillReenqueued() {
		// The processor tick owns deadline termination (failIfDeadlineExceeded);
		// the scanner only repairs wake-ups, so overdue jobs stay re-enqueueable.
		Instant retryNotBeforeAt = NOW.minusSeconds(120);
		Instant overdueDeadline = NOW.minusSeconds(60);
		GenerationJob overdue = retryingJob(retryNotBeforeAt, overdueDeadline);
		generationStore.put(overdue);
		generationStore.setDueJobs(List.of(overdue));

		int repaired = scanner.scanDueJobs();

		assertThat(repaired).isEqualTo(1);
		assertThat(outboxStore.insertions).containsExactly(
			new RecordingOutboxStore.Inserted(overdue.id().value(), retryNotBeforeAt));
	}

	@Test
	void outboxActiveGuardVetoSkipsWithoutInsert() {
		// Job was cancelled after listDueRetrying: the store's active-job
		// guard refuses the insert and the scanner counts no repair.
		GenerationJob cancelledAfterListing = retryingJob(NOW.minusSeconds(15), DEFAULT_DEADLINE);
		generationStore.setDueJobs(List.of(cancelledAfterListing));
		outboxStore.registerActive(cancelledAfterListing.id().value(), false);

		int repaired = scanner.scanDueJobs();

		assertThat(repaired).isZero();
		assertThat(outboxStore.insertions).isEmpty();
	}

	@Test
	void insertFailureDoesNotPreventRemainingJobs() {
		GenerationJob failing = retryingJob(NOW.minusSeconds(20), DEFAULT_DEADLINE);
		GenerationJob healthy = retryingJob(NOW.minusSeconds(10), DEFAULT_DEADLINE);
		generationStore.setDueJobs(List.of(failing, healthy));
		outboxStore.failNextInsertFor(failing.id().value());

		int repaired = scanner.scanDueJobs();

		assertThat(repaired).isEqualTo(1);
		assertThat(outboxStore.insertions).containsExactly(
			new RecordingOutboxStore.Inserted(healthy.id().value(), healthy.retryNotBeforeAt()));
	}

	@Test
	void scheduledScanSwallowsRuntimeException() {
		generationStore.failListing = true;

		scanner.scheduledScan();

		assertThat(outboxStore.insertions).isEmpty();
	}

	private static GenerationJob retryingJob(Instant retryNotBeforeAt, Instant orchestrationDeadlineAt) {
		return new GenerationJob(
			new GenerationJobId(UUID.randomUUID()),
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
			"scanner-fingerprint",
			1,
			3,
			CREATED_AT,
			null,
			1,
			1,
			1,
			orchestrationDeadlineAt,
			retryNotBeforeAt,
			RetryPolicySnapshot.forStepCount(1),
			Optional.empty());
	}

	private static final class FakeGenerationStore implements GenerationStore {

		private final Map<GenerationJobId, GenerationJob> jobs = new LinkedHashMap<>();
		private final List<Instant> listedAt = new ArrayList<>();
		private List<GenerationJob> dueJobs = List.of();
		private boolean failListing;

		void put(GenerationJob job) {
			jobs.put(job.id(), job);
		}

		void setDueJobs(List<GenerationJob> jobs) {
			dueJobs = List.copyOf(jobs);
		}

		@Override
		public List<GenerationJob> listDueRetrying(Instant now) {
			if (failListing) {
				throw new IllegalStateException("simulated store failure");
			}
			listedAt.add(now);
			return dueJobs;
		}

		@Override
		public void insertJob(GenerationJob job) {
			jobs.put(job.id(), job);
		}

		@Override
		public Optional<GenerationJob> findJob(GenerationJobId jobId) {
			return Optional.ofNullable(jobs.get(jobId));
		}

		@Override
		public void insertAttempt(GenerationAttempt attempt) {
			throw new UnsupportedOperationException("scanner must not touch attempts");
		}

		@Override
		public Optional<GenerationAttempt> findAttempt(GenerationAttemptId attemptId) {
			throw new UnsupportedOperationException("scanner must not touch attempts");
		}

		@Override
		public List<GenerationAttempt> listAttempts(GenerationJobId jobId) {
			throw new UnsupportedOperationException("scanner must not touch attempts");
		}

		@Override
		public boolean tryAcquireSubmitOwnership(GenerationAttemptId attemptId, Instant ownedAt) {
			throw new UnsupportedOperationException("scanner must not touch attempts");
		}

		@Override
		public void recordProviderExecution(GenerationAttemptId attemptId, ProviderJobId providerJobId) {
			throw new UnsupportedOperationException("scanner must not touch attempts");
		}

		@Override
		public void markSubmissionIndeterminate(GenerationAttemptId attemptId) {
			throw new UnsupportedOperationException("scanner must not touch attempts");
		}

		@Override
		public void saveQualityResult(QualityResult qualityResult) {
			throw new UnsupportedOperationException("scanner must not touch attempts");
		}

		@Override
		public void markCancelled(GenerationJobId jobId, Instant cancelledAt) {
			throw new UnsupportedOperationException("scanner must not touch job state");
		}

		@Override
		public void markSubjectDeleted(GenerationJobId jobId, Instant subjectDeletedAt) {
			throw new UnsupportedOperationException("scanner must not touch job state");
		}

		@Override
		public void completeAttempt(GenerationAttempt attempt) {
			throw new UnsupportedOperationException("scanner must not touch attempts");
		}

		@Override
		public void markJobCompleted(GenerationJobId jobId, Instant completedAt) {
			throw new UnsupportedOperationException("scanner must not touch job state");
		}
	}

	private static final class RecordingOutboxStore implements WorkOutboxStore {

		record Inserted(UUID generationJobId, Instant notBefore) {
		}

		private final Map<UUID, Boolean> activeJobs = new HashMap<>();
		private final List<UUID> failingInserts = new ArrayList<>();
		private final List<Inserted> insertions = new ArrayList<>();

		void registerActive(UUID generationJobId, boolean active) {
			activeJobs.put(generationJobId, active);
		}

		void failNextInsertFor(UUID generationJobId) {
			failingInserts.add(generationJobId);
		}

		@Override
		public boolean insertSuccessorIfActive(UUID generationJobId, Instant notBefore) {
			if (failingInserts.remove(generationJobId)) {
				throw new IllegalStateException("simulated outbox failure");
			}
			boolean active = activeJobs.getOrDefault(generationJobId, true);
			if (active) {
				insertions.add(new Inserted(generationJobId, notBefore));
			}
			return active;
		}

		@Override
		public void insertSuccessor(UUID generationJobId, Instant notBefore) {
			insertions.add(new Inserted(generationJobId, notBefore));
		}

		@Override
		public Optional<Claim> claimNext() {
			return Optional.empty();
		}

		@Override
		public void markSent(UUID outboxId) {
			throw new UnsupportedOperationException("scanner must not publish");
		}

		@Override
		public void markPublishFailed(UUID outboxId, String errorCategory) {
			throw new UnsupportedOperationException("scanner must not publish");
		}
	}
}
