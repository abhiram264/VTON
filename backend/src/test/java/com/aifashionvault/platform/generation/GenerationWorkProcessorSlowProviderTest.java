package com.aifashionvault.platform.generation;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.aifashionvault.admission.AbuseDecision;
import com.aifashionvault.admission.AbuseDecisionReason;
import com.aifashionvault.admission.AbuseBudgetPolicy;
import com.aifashionvault.admission.AggregateBackedCostCircuitBreaker;
import com.aifashionvault.admission.GlobalCostCircuitBreaker;
import com.aifashionvault.admission.ProviderExecutionBudgetRequest;
import com.aifashionvault.generation.CostQuery;
import com.aifashionvault.generation.CallbackInbox;
import com.aifashionvault.generation.CallbackInboxStore;
import com.aifashionvault.generation.GenerationAttempt;
import com.aifashionvault.generation.GenerationJob;
import com.aifashionvault.generation.GenerationRetryBudget;
import com.aifashionvault.generation.GenerationStore;
import com.aifashionvault.generation.PricingSnapshot;
import com.aifashionvault.generation.QualityEvaluationOutcome;
import com.aifashionvault.generation.QualityEvaluationRequest;
import com.aifashionvault.generation.QualityGate;
import com.aifashionvault.generation.QualityResult;
import com.aifashionvault.generation.RenderPlan;
import com.aifashionvault.generation.RenderStep;
import com.aifashionvault.generation.RetryPolicyResolution;
import com.aifashionvault.generation.RetryPolicySnapshot;
import com.aifashionvault.generation.RetryBackoff;
import com.aifashionvault.generation.vton.DefaultProviderRegistry;
import com.aifashionvault.generation.vton.GenerationSubmission;
import com.aifashionvault.generation.vton.GenerationStatus;
import com.aifashionvault.generation.vton.InputTransferMethod;
import com.aifashionvault.generation.vton.ItemCategory;
import com.aifashionvault.generation.vton.OutputDeliveryMethod;
import com.aifashionvault.generation.vton.PrivacyTransferPolicy;
import com.aifashionvault.generation.vton.PrivateMediaRef;
import com.aifashionvault.generation.vton.ProviderFailure;
import com.aifashionvault.generation.vton.ProviderFailureCategory;
import com.aifashionvault.generation.vton.ProviderJobId;
import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.generation.vton.ProviderOutputRef;
import com.aifashionvault.generation.vton.ProviderRegistry;
import com.aifashionvault.generation.vton.ProviderUsage;
import com.aifashionvault.generation.vton.StatusQueryResult;
import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.generation.vton.VtonProductAsset;
import com.aifashionvault.look.Look;
import com.aifashionvault.look.LookStore;
import com.aifashionvault.notification.Notification;
import com.aifashionvault.notification.NotificationStore;
import com.aifashionvault.platform.generation.AuthorizedGenerationMediaResolver.ResolvedMedia;
import com.aifashionvault.platform.generation.AuthorizedGenerationMediaResolver.GenerationInputMissingException;
import com.aifashionvault.platform.generation.AuthorizedGenerationMediaResolver.UnauthorizedGenerationMediaException;
import com.aifashionvault.platform.objectstore.ClaimCommand;
import com.aifashionvault.platform.objectstore.ClaimResult;
import com.aifashionvault.platform.objectstore.GenerationClaimCommand;
import com.aifashionvault.platform.objectstore.GenerationMediaStore;
import com.aifashionvault.platform.objectstore.ImageMediaPolicy;
import com.aifashionvault.platform.objectstore.MediaDeclaration;
import com.aifashionvault.platform.objectstore.ObjectMetadata;
import com.aifashionvault.platform.objectstore.ObjectRef;
import com.aifashionvault.platform.objectstore.ObjectStore;
import com.aifashionvault.platform.objectstore.PresignedAccess;
import com.aifashionvault.shared.StorageDomain;
import com.aifashionvault.platform.objectstore.StoredObject;
import com.aifashionvault.platform.objectstore.UploadCapability;
import com.aifashionvault.platform.objectstore.UploadPurpose;
import com.aifashionvault.platform.queue.GenerationWorkLease;
import com.aifashionvault.platform.queue.GenerationWorkQueue;
import com.aifashionvault.platform.queue.SqsGenerationWorkQueue;
import com.aifashionvault.platform.queue.WorkOutboxStore;
import com.aifashionvault.shared.AvatarImageId;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.GenerationJobId;
import com.aifashionvault.shared.LookId;
import com.aifashionvault.shared.NotificationId;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.QualityResultId;
import com.aifashionvault.shared.StorageKey;
import com.aifashionvault.shared.TerminalReason;
import com.aifashionvault.shared.UploadId;
import com.aifashionvault.shared.UserId;
import com.aifashionvault.shared.VaultItemId;

import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationWorkProcessorSlowProviderTest {

	private static final Instant NOW = Instant.parse("2026-08-26T12:00:00Z");
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final UserId USER_ID = new UserId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
	private static final GenerationJobId JOB_ID = new GenerationJobId(
		UUID.fromString("55555555-5555-5555-5555-555555555555"));
	private static final AvatarImageId AVATAR_IMAGE_ID = new AvatarImageId(
		UUID.fromString("22222222-2222-2222-2222-222222222222"));
	private static final VaultItemId PRODUCT_ID = new VaultItemId(
		UUID.fromString("33333333-3333-3333-3333-333333333333"));
	private static final String AVATAR_KEY = "users/11111111-1111-1111-1111-111111111111/avatars/"
		+ "22222222-2222-2222-2222-222222222222.jpg";
	private static final String PRODUCT_KEY = "users/11111111-1111-1111-1111-111111111111/vault/"
		+ "33333333-3333-3333-3333-333333333333.jpg";
	private static final int MAX_LOGICAL_ATTEMPTS_PER_STEP = 3;


	private InMemoryGenerationStore generationStore;
	private InMemoryLookStore lookStore;
	private InMemoryNotificationStore notificationStore;
	private InMemoryQueue queue;
	private final List<String> operations = new CopyOnWriteArrayList<>();
	private InMemoryObjects objects;
	private AuthorizedGenerationMediaResolver resolver;
	private PrivacyApprovedInputTransfer transfer;
	private ScriptedQualityGate qualityGate;
	private RecordingVtonProvider provider;
	private GenerationWorkProcessor processor;
	private GenerationWorkLease lease;
	private TestCostCircuitBreaker costCircuitBreaker;
	private TestProviderHealthGate providerHealthGate;

	@BeforeEach
	void setUp() {
		generationStore = new InMemoryGenerationStore();
		lookStore = new InMemoryLookStore(operations);
		notificationStore = new InMemoryNotificationStore(operations);
		queue = new InMemoryQueue(operations);
		objects = new InMemoryObjects();
		objects.put(new ObjectRef(StorageDomain.AVATAR, AVATAR_KEY), new byte[] {1, 2, 3});
		objects.put(new ObjectRef(StorageDomain.VAULT, PRODUCT_KEY), new byte[] {4, 5, 6});
		resolver = mock(AuthorizedGenerationMediaResolver.class);
		when(resolver.resolve(any())).thenReturn(resolvedMedia());
		transfer = new PrivacyApprovedInputTransfer(policy(), objects);
		qualityGate = new ScriptedQualityGate();
		provider = new RecordingVtonProvider();
		costCircuitBreaker = new TestCostCircuitBreaker();
		providerHealthGate = new TestProviderHealthGate();
		processor = processor(provider);
		lease = new GenerationWorkLease(JOB_ID.value(), "receipt-1");
	}

	/**
	 * Pins the queue-sizing invariant behind multi-minute provider calls
	 * (InferHub latency is ~170s): each heartbeat must extend visibility far
	 * enough that coverage never lapses between beats, and the extension must
	 * fit under the SQS visibility timeout so the SQS queue never rejects it.
	 * HEARTBEAT_INTERVAL is derived from SqsGenerationWorkQueue's constant, so
	 * pinning the queue constants pins the processor's values too.
	 */
	@Test
	void heartbeatIntervalIsShorterThanVisibilityExtensionWithinSqsTimeout() {
		long heartbeatSeconds = SqsGenerationWorkQueue.HEARTBEAT_INTERVAL_SECONDS;
		long extensionSeconds = GenerationWorkProcessor.VISIBILITY_EXTENSION.toSeconds();
		long timeoutSeconds = SqsGenerationWorkQueue.VISIBILITY_TIMEOUT_SECONDS;

		assertThat(heartbeatSeconds).isLessThan(extensionSeconds);
		assertThat(extensionSeconds).isLessThanOrEqualTo(timeoutSeconds);
	}

	/**
	 * A provider call that stays in flight across several real visibility
	 * windows (~170s of InferHub latency, compressed to milliseconds) must see
	 * its lease visibility extended, must not be reaped mid-flight, and must
	 * dispatch exactly once and complete when the call finally returns.
	 */
	@Test
	void slowProviderCallStaysExtendedDispatchesOnceAndCompletes() throws Exception {
		CountDownLatch releaseProvider = new CountDownLatch(1);
		provider.beforeSubmit(() -> {
			try {
				releaseProvider.await();
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		});
		generationStore.insertJob(queuedJob());

		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			Future<?> work = pool.submit(() -> processor.process(lease));

			awaitTrue(() -> provider.submitCount() == 1);

			// In flight: visibility was extended so the message cannot become
			// visible again mid-call, nothing reaped the lease, nothing
			// completed the job.
			assertThat(queue.extensions()).isGreaterThanOrEqualTo(1);
			assertThat(queue.deleted()).isEmpty();
			assertThat(generationStore.findJob(JOB_ID).orElseThrow().completedAt()).isNull();

			releaseProvider.countDown();
			work.get(15, TimeUnit.SECONDS);
		}
		finally {
			pool.shutdownNow();
		}

		// End to end: exactly one dispatch (one submit, one submission key),
		// the job completed, and the lease was acknowledged exactly once.
		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(provider.submittedKeys()).hasSize(1);
		assertThat(lookStore.inserted()).hasSize(1);
		assertThat(queue.deleted()).containsExactly(lease);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().completedAt()).isEqualTo(NOW);
	}

	private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (!condition.getAsBoolean()) {
			if (System.nanoTime() > deadline) {
				throw new AssertionError("condition not met within 10s");
			}
			Thread.sleep(10);
		}
	}

	private GenerationWorkProcessor processor(com.aifashionvault.generation.vton.VtonProvider vtonProvider) {
		lookStore.attach(generationStore);
		return new GenerationWorkProcessor(
			generationStore,
			lookStore,
			notificationStore,
			queue,
			vtonProvider,
			resolver,
			transfer,
			qualityGate,
			objects,
			objects,
			CLOCK,
			null,
			null,
			null,
			null,
			null,
			null,
			() -> 0.0,
			costCircuitBreaker,
			providerHealthGate);
	}

	private static GenerationJob queuedJob() {
		return queuedJobWithDeadline(NOW.plus(GenerationJob.ORCHESTRATION_DEADLINE));
	}

	private static GenerationJob queuedJobWithDeadline(Instant deadline) {
		return new GenerationJob(
			JOB_ID,
			USER_ID,
			null,
			GenerationJob.QUEUED,
			"try-on",
			null,
			NOW,
			null,
			AVATAR_IMAGE_ID,
			List.of(PRODUCT_ID),
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
			deadline,
			null,
			RetryPolicySnapshot.forStepCount(1));
	}

	private static ResolvedMedia resolvedMedia() {
		return new ResolvedMedia(
			new PrivateMediaRef(new StorageKey(AVATAR_KEY), OptionalLong.of(3)),
			List.of(new VtonProductAsset(
				new PrivateMediaRef(new StorageKey(PRODUCT_KEY), OptionalLong.of(3)),
				new ItemCategory("garment"))));
	}

	private static PrivacyTransferPolicy policy() {
		return new PrivacyTransferPolicy(
			Set.of(InputTransferMethod.BOUNDED_INLINE_DATA, InputTransferMethod.EPHEMERAL_SIGNED_READ),
			Set.of(OutputDeliveryMethod.BOUNDED_INLINE_DATA),
			1_000_000L,
			Duration.ofMinutes(5),
			false);
	}

	private static final class ScriptedQualityGate implements QualityGate {
		private QualityDecision decision = QualityDecision.PASS;
		private String reasonCode;
		private int infrastructureFailures;
		private Runnable beforeEvaluate = () -> {
		};
		private volatile QualityEvaluationRequest lastRequest;

		@Override
		public QualityResult evaluate(com.aifashionvault.shared.GenerationAttemptId attemptId, byte[] applicationOwnedImage) {
			beforeEvaluate.run();
			if (infrastructureFailures > 0) {
				infrastructureFailures--;
				return new QualityResult(
					new QualityResultId(UUID.randomUUID()),
					attemptId,
					QualityDecision.FAIL,
					null,
					null,
					null,
					null,
					null,
					NOW,
					QualityGate.BASELINE_POLICY_VERSION,
					"EVALUATOR_DOWN");
			}
			return new QualityResult(
				new QualityResultId(UUID.randomUUID()),
				attemptId,
				decision,
				null,
				null,
				null,
				null,
				null,
				NOW);
		}

		@Override
		public QualityEvaluationOutcome evaluate(QualityEvaluationRequest request) {
			beforeEvaluate.run();
			lastRequest = request;
			if (infrastructureFailures > 0) {
				infrastructureFailures--;
				return new QualityEvaluationOutcome.Unavailable("EVALUATOR_UNAVAILABLE");
			}
			String bound = QualityGate.boundPolicyVersion(request.boundPolicyVersion());
			if (QualityGate.QUALITY_V2_POLICY_VERSION.equals(bound)) {
				return new QualityEvaluationOutcome.Completed(new QualityResult(
					new QualityResultId(UUID.randomUUID()),
					request.attemptId(),
					decision,
					new BigDecimal("0.95"),
					new BigDecimal("0.90"),
					new BigDecimal("0.88"),
					true,
					false,
					NOW,
					bound,
					reasonCode));
			}
			return new QualityEvaluationOutcome.Completed(new QualityResult(
				new QualityResultId(UUID.randomUUID()),
				request.attemptId(),
				decision,
				null,
				null,
				null,
				null,
				null,
				NOW,
				bound,
				reasonCode));
		}

		private QualityEvaluationRequest lastRequest() {
			return lastRequest;
		}
	}

	private static final class InMemoryGenerationStore implements GenerationStore {
		private final Map<GenerationJobId, GenerationJob> jobs = new ConcurrentHashMap<>();
		private final Map<GenerationAttemptId, GenerationAttempt> attempts = new ConcurrentHashMap<>();
		private final Map<GenerationJobId, RenderPlan> renderPlans = new ConcurrentHashMap<>();
		private final Map<GenerationAttemptId, QualityResult> qualityResults = new ConcurrentHashMap<>();
		private final Map<GenerationJobId, String> categories = new ConcurrentHashMap<>();
		private volatile boolean throwOnFindAttemptCategory;

		private void setAttemptCategory(GenerationJobId jobId, String category) {
			if (category != null) {
				categories.put(jobId, category);
			}
			else {
				categories.remove(jobId);
			}
		}

		private void throwOnFindAttemptCategory(boolean throwOnFindAttemptCategory) {
			this.throwOnFindAttemptCategory = throwOnFindAttemptCategory;
		}

		@Override
		public Optional<String> findAttemptCategory(GenerationJobId jobId, UUID renderStepId) {
			if (throwOnFindAttemptCategory) {
				throw new UnsupportedOperationException("findAttemptCategory");
			}
			return Optional.ofNullable(categories.get(jobId));
		}
		private final AtomicInteger markJobCompletedFailures = new AtomicInteger();
		private final AtomicInteger failAfterAttemptComplete = new AtomicInteger();
		private final AtomicInteger failAfterRetryComplete = new AtomicInteger();
		private final AtomicInteger completeTerminalIfActiveCalls = new AtomicInteger();
		private volatile Runnable afterFindJob = () -> {
		};
		private volatile Runnable afterAcquire = () -> {
		};
		private volatile Runnable afterCompleteTerminalIfActive = () -> {
		};
		private volatile Runnable beforeRetryPersistence = () -> {
		};
		private volatile Runnable beforeQualityCheckCas = () -> {
		};
		private volatile Runnable afterSaveQualityResult = () -> {
		};
		private volatile String persistedRetryPolicyOverride;

		private void failMarkJobCompletedOnce() {
			markJobCompletedFailures.set(1);
		}

		private void failAfterRetryCompleteOnce() {
			failAfterRetryComplete.set(1);
		}

		private void failAfterAttemptCompleteOnce() {
			failAfterAttemptComplete.set(1);
		}
		private void beforeRetryPersistence(Runnable action) {
			this.beforeRetryPersistence = action == null ? () -> {
			} : action;
		}
		private void beforeQualityCheckCas(Runnable action) {
			this.beforeQualityCheckCas = action == null ? () -> {
			} : action;
		}
		private void overridePersistedRetryPolicy(String serialized) {
			this.persistedRetryPolicyOverride = serialized;
		}

		private boolean retryJobActive(GenerationJob job) {
			return jobActive(job) && job.active();
		}

		private boolean jobActive(GenerationJob job) {
			return job != null
				&& job.completedAt() == null
				&& job.cancelledAt() == null
				&& job.subjectDeletedAt() == null;
		}
		private boolean admitForStore(GenerationJob job, int used) {
			RetryPolicySnapshot policy = job.retryPolicySnapshot() == null
				? RetryPolicySnapshot.forStepCount(job.totalSteps())
				: job.retryPolicySnapshot();
			return used < Math.min(
				policy.maxPaidAttempts(),
				job.totalSteps() + RetryPolicySnapshot.ADDITIONAL_LOGICAL_ATTEMPTS);
		}

		private boolean admitForStore(GenerationJob job, int used, UUID renderStepId) {
			if (!admitForStore(job, used)) {
				return false;
			}
			long attemptsForStep = listAttempts(job.id()).stream()
				.filter(existing -> renderStepId == null
					? existing.renderStepId() == null
					: renderStepId.equals(existing.renderStepId()))
				.count();
			return attemptsForStep < MAX_LOGICAL_ATTEMPTS_PER_STEP;
		}
		private Optional<GenerationJob> peekJob(GenerationJobId jobId) {
			return Optional.ofNullable(jobs.get(jobId));
		}

		@Override
		public void insertJob(GenerationJob job) {
			jobs.put(job.id(), job);
		}

		@Override
		public Optional<GenerationJob> findJob(GenerationJobId jobId) {
			Optional<GenerationJob> found = Optional.ofNullable(jobs.get(jobId));
			afterFindJob.run();
			return found;
		}
		/**
		 * Resolves the fail-closed policy state exactly like the JDBC read path
		 * (R9/Q5): the scripted raw row, when present, is parsed through the
		 * shared resolution so malformed and unknown-version rows keep their
		 * exact state; otherwise the snapshot carried on the job decides
		 * between VALID and MISSING.
		 */
		@Override
		public RetryPolicyResolution resolveRetryPolicy(GenerationJobId jobId) {
			String override = persistedRetryPolicyOverride;
			if (override != null) {
				return RetryPolicySnapshot.parseResolution(override);
			}
			GenerationJob job = jobs.get(jobId);
			if (job == null) {
				return null;
			}
			RetryPolicySnapshot snapshot = job.retryPolicySnapshot();
			if (snapshot == null) {
				return new RetryPolicyResolution(RetryPolicyResolution.State.MISSING, null);
			}
			return new RetryPolicyResolution(RetryPolicyResolution.State.VALID, snapshot);
		}
		private void renderPlan(GenerationJobId jobId, RenderPlan plan) {
			renderPlans.put(jobId, plan);
		}

		@Override
		public Optional<RenderPlan> findRenderPlan(GenerationJobId jobId) {
			return Optional.ofNullable(renderPlans.get(jobId));
		}

		@Override
		public void insertAttempt(GenerationAttempt attempt) {
			attempts.put(attempt.id(), attempt);
		}

		@Override
		public Optional<GenerationAttempt> findAttempt(GenerationAttemptId attemptId) {
			return Optional.ofNullable(attempts.get(attemptId));
		}

		@Override
		public List<GenerationAttempt> listAttempts(GenerationJobId jobId) {
			return attempts.values().stream()
				.filter(attempt -> attempt.generationJobId().equals(jobId))
				.sorted((left, right) -> Integer.compare(left.attemptNumber(), right.attemptNumber()))
				.toList();
		}

		@Override
		public synchronized boolean tryAcquireSubmitOwnership(GenerationAttemptId attemptId, Instant ownedAt) {
			GenerationAttempt attempt = attempts.get(attemptId);
			if (attempt == null || attempt.submitOwnedAt() != null) {
				afterAcquire.run();
				return false;
			}
			GenerationJob job = jobs.get(attempt.generationJobId());
			if (job == null || job.cancelledAt() != null || job.subjectDeletedAt() != null || job.completedAt() != null) {
				afterAcquire.run();
				return false;
			}
			attempts.put(attemptId, copy(attempt, ownedAt, attempt.providerJobId(), attempt.submissionIndeterminate()));
			afterAcquire.run();
			return true;
		}

		@Override
		public synchronized void recordProviderExecution(GenerationAttemptId attemptId, ProviderJobId providerJobId) {
			GenerationAttempt attempt = attempts.get(attemptId);
			if (attempt == null) {
				return;
			}
			String candidate = providerJobId == null ? null : providerJobId.value();
			if (attempt.hasDurableProviderJobId()
				&& candidate != null
				&& !attempt.providerJobId().equals(candidate)) {
				return;
			}
			String retained = attempt.hasDurableProviderJobId() ? attempt.providerJobId() : candidate;
			attempts.put(attemptId, copy(attempt, attempt.submitOwnedAt(), retained, attempt.submissionIndeterminate()));
		}

		@Override
		public synchronized void markSubmissionIndeterminate(GenerationAttemptId attemptId) {
			GenerationAttempt attempt = attempts.get(attemptId);
			attempts.put(attemptId, copy(attempt, attempt.submitOwnedAt(), attempt.providerJobId(), true));
		}

		@Override
		public synchronized void saveQualityResult(QualityResult qualityResult) {
			qualityResults.put(qualityResult.generationAttemptId(), qualityResult);
			afterSaveQualityResult.run();
		}

		@Override
		public synchronized Optional<QualityResult> findQualityResult(GenerationAttemptId attemptId) {
			return Optional.ofNullable(qualityResults.get(attemptId));
		}

		@Override
		public synchronized boolean markQualityCheckIfActive(GenerationJobId jobId, int expectedStatusVersion) {
			beforeQualityCheckCas.run();
			GenerationJob job = jobs.get(jobId);
			if (!jobActive(job) || job.statusVersion() != expectedStatusVersion) {
				return false;
			}
			if (GenerationJob.QUALITY_CHECK.equals(job.status())) {
				return true;
			}
			if (!GenerationJob.GENERATING.equals(job.status())) {
				return false;
			}
			jobs.put(jobId, withLifecycle(
				job,
				GenerationJob.QUALITY_CHECK,
				null,
				job.cancelledAt(),
				job.subjectDeletedAt(),
				job.terminalReason()));
			return true;
		}

		@Override
		public void markCancelled(GenerationJobId jobId, Instant cancelledAt) {
			GenerationJob job = jobs.get(jobId);
			jobs.put(jobId, withLifecycle(
				job,
				GenerationJob.FAILED,
				cancelledAt,
				cancelledAt,
				job.subjectDeletedAt(),
				GenerationJob.CANCELLED_BY_USER));
		}

		@Override
		public synchronized void markSubjectDeleted(GenerationJobId jobId, Instant subjectDeletedAt) {
			GenerationJob job = jobs.get(jobId);
			String reason = job.terminalReason() != null ? job.terminalReason() : GenerationJob.SUBJECT_DELETED;
			Instant completedAt = job.completedAt() != null ? job.completedAt() : subjectDeletedAt;
			jobs.put(jobId, withLifecycle(
				job,
				GenerationJob.FAILED,
				completedAt,
				job.cancelledAt(),
				subjectDeletedAt,
				reason));
		}

		@Override
		public synchronized void completeAttempt(GenerationAttempt attempt) {
			attempts.put(attempt.id(), attempt);
			if (failAfterAttemptComplete.getAndDecrement() > 0) {
				throw new RuntimeException("crash after durable attempt complete");
			}
		}

		@Override
		public void markJobCompleted(GenerationJobId jobId, Instant completedAt) {
			if (markJobCompletedFailures.getAndDecrement() > 0) {
				throw new RuntimeException("crash after attempt complete");
			}
			GenerationJob job = jobs.get(jobId);
			jobs.put(jobId, withLifecycle(
				job,
				GenerationJob.COMPLETED,
				completedAt,
				job.cancelledAt(),
				job.subjectDeletedAt(),
				job.terminalReason()));
		}

		@Override
		public synchronized boolean completeTerminalIfActive(GenerationAttempt attempt, Instant jobCompletedAt) {
			completeTerminalIfActiveCalls.incrementAndGet();
			GenerationJob job = jobs.get(attempt.generationJobId());
			if (!jobActive(job)) {
				afterCompleteTerminalIfActive.run();
				return false;
			}
			completeAttempt(attempt);
			String failureReason = attempt.failureCode() != null && !attempt.failureCode().isBlank()
				? attempt.failureCode()
				: "FAILED".equals(attempt.technicalOutcome()) ? "PROVIDER_EXECUTION_FAILED" : null;
			if (failureReason != null) {
				jobs.put(
					attempt.generationJobId(),
					withLifecycle(
						job,
						GenerationJob.FAILED,
						jobCompletedAt,
						job.cancelledAt(),
						job.subjectDeletedAt(),
						failureReason));
			}
			else {
				markJobCompleted(attempt.generationJobId(), jobCompletedAt);
			}
			afterCompleteTerminalIfActive.run();
			return true;
		}

		@Override
		public synchronized boolean cancelIfActive(GenerationJobId jobId, Instant cancelledAt) {
			GenerationJob job = jobs.get(jobId);
			if (!jobActive(job)) {
				return false;
			}
			jobs.put(jobId, withLifecycle(
				job,
				GenerationJob.FAILED,
				cancelledAt,
				cancelledAt,
				job.subjectDeletedAt(),
				GenerationJob.CANCELLED_BY_USER));
			return true;
		}

		@Override
		public void completeTerminal(GenerationAttempt attempt, Instant jobCompletedAt) {
			completeTerminalIfActive(attempt, jobCompletedAt);
		}

		@Override
		public synchronized void scheduleRetry(GenerationAttempt completedAttempt, GenerationAttempt nextAttempt) {
			beforeRetryPersistence.run();
			completeAttempt(completedAttempt);
			if (failAfterRetryComplete.getAndDecrement() > 0) {
				throw new RuntimeException("crash after retry complete");
			}
			if (attempts.containsKey(nextAttempt.id())
				|| listAttempts(nextAttempt.generationJobId()).stream()
					.anyMatch(existing -> existing.attemptNumber() == nextAttempt.attemptNumber())) {
				return;
			}
			insertAttempt(nextAttempt);
		}
		@Override
		public synchronized boolean scheduleRetryIfActive(
			GenerationAttempt completedAttempt,
			GenerationAttempt nextAttempt
		) {
			if (completedAttempt == null) {
				throw new IllegalArgumentException("completedAttempt is required");
			}
			if (nextAttempt == null) {
				throw new IllegalArgumentException("nextAttempt is required");
			}
			if (!completedAttempt.generationJobId().equals(nextAttempt.generationJobId())) {
				throw new IllegalArgumentException("attempts must belong to the same generation job");
			}
			beforeRetryPersistence.run();
			GenerationJob job = jobs.get(completedAttempt.generationJobId());
			if (!retryJobActive(job)) {
				return false;
			}
			completeAttempt(completedAttempt);
			if (failAfterRetryComplete.getAndDecrement() > 0) {
				throw new RuntimeException("crash after retry complete");
			}
			if (attempts.containsKey(nextAttempt.id())
				|| listAttempts(nextAttempt.generationJobId()).stream()
					.anyMatch(existing -> existing.attemptNumber() == nextAttempt.attemptNumber())) {
				return true;
			}
			insertAttempt(nextAttempt);
			return true;
		}

		@Override
		public synchronized boolean scheduleRetryIfActive(
			GenerationAttempt completedAttempt,
			Instant retryNotBeforeAt
		) {
			if (completedAttempt == null) {
				throw new IllegalArgumentException("completedAttempt is required");
			}
			if (completedAttempt.completedAt() == null) {
				throw new IllegalArgumentException("completedAttempt must be completed");
			}
			if (retryNotBeforeAt == null) {
				throw new IllegalArgumentException("retryNotBeforeAt is required");
			}
			GenerationJob job = jobs.get(completedAttempt.generationJobId());
			beforeRetryPersistence.run();
			job = jobs.get(completedAttempt.generationJobId());
			if (!retryJobActive(job)
				|| (GenerationJob.RETRYING.equals(job.status()) && job.retryNotBeforeAt() != null)) {
				return false;
			}
			completeAttempt(completedAttempt);
			jobs.put(
				job.id(),
				withLifecycle(
					job,
					GenerationJob.RETRYING,
					null,
					job.cancelledAt(),
					job.subjectDeletedAt(),
					job.terminalReason(),
					retryNotBeforeAt));
			if (failAfterRetryComplete.getAndDecrement() > 0) {
				throw new RuntimeException("crash after retry complete");
			}
			return true;
		}

		@Override
		public synchronized boolean scheduleRetryIfActive(
			GenerationAttempt completedAttempt,
			int expectedStatusVersion,
			Instant retryNotBeforeAt
		) {
			if (completedAttempt == null) {
				throw new IllegalArgumentException("completedAttempt is required");
			}
			if (completedAttempt.completedAt() == null) {
				throw new IllegalArgumentException("completedAttempt must be completed");
			}
			if (retryNotBeforeAt == null) {
				throw new IllegalArgumentException("retryNotBeforeAt is required");
			}
			GenerationJob job = jobs.get(completedAttempt.generationJobId());
			beforeRetryPersistence.run();
			job = jobs.get(completedAttempt.generationJobId());
			if (!retryJobActive(job)
				|| GenerationJob.RETRYING.equals(job.status())
				|| job.statusVersion() != expectedStatusVersion) {
				return false;
			}
			GenerationAttempt persisted = attempts.get(completedAttempt.id());
			List<GenerationAttempt> currentAttempts = listAttempts(completedAttempt.generationJobId());
			if (persisted == null
				|| !persisted.generationJobId().equals(completedAttempt.generationJobId())
				|| persisted.attemptNumber() != completedAttempt.attemptNumber()
				|| currentAttempts.isEmpty()
				|| !currentAttempts.getLast().id().equals(completedAttempt.id())) {
				return false;
			}
			completeAttempt(completedAttempt);
			jobs.put(
				job.id(),
				withLifecycle(
					job,
					GenerationJob.RETRYING,
					null,
					job.cancelledAt(),
					job.subjectDeletedAt(),
					job.terminalReason(),
					retryNotBeforeAt));
			if (failAfterRetryComplete.getAndDecrement() > 0) {
				throw new RuntimeException("crash after retry complete");
			}
			return true;
		}

		@Override
		public synchronized boolean claimDueRetry(
			GenerationJobId jobId,
			int expectedStatusVersion,
			Instant now,
			GenerationAttempt nextAttempt
		) {
			if (jobId == null || now == null || nextAttempt == null) {
				throw new IllegalArgumentException("claim arguments are required");
			}
			if (!jobId.equals(nextAttempt.generationJobId())) {
				throw new IllegalArgumentException("nextAttempt must belong to the claimed generation job");
			}
			GenerationJob job = jobs.get(jobId);
			if (!retryJobActive(job)
				|| !GenerationJob.RETRYING.equals(job.status())
				|| job.retryNotBeforeAt() == null
				|| now.isBefore(job.retryNotBeforeAt())
				|| job.statusVersion() != expectedStatusVersion
				|| !admitForStore(job, listAttempts(jobId).size(), nextAttempt.renderStepId())
				|| attempts.containsKey(nextAttempt.id())
				|| listAttempts(jobId).stream().anyMatch(
					existing -> existing.attemptNumber() >= nextAttempt.attemptNumber())) {
				return false;
			}
			// Fail closed on non-VALID persisted policy rows like the JDBC claim (R9).
			RetryPolicyResolution policyResolution = resolveRetryPolicy(jobId);
			if (policyResolution == null || policyResolution.state() != RetryPolicyResolution.State.VALID) {
				return false;
			}
			jobs.put(
				jobId,
				withLifecycle(
					job,
					GenerationJob.PREFLIGHT,
					null,
					job.cancelledAt(),
					job.subjectDeletedAt(),
					job.terminalReason(),
					null));
			insertAttempt(nextAttempt);
			return true;
		}

		@Override
		public List<GenerationJob> listDueRetrying(Instant now) {
			return jobs.values().stream()
				.filter(job -> retryJobActive(job)
					&& GenerationJob.RETRYING.equals(job.status())
					&& job.retryNotBeforeAt() != null
					&& !now.isBefore(job.retryNotBeforeAt()))
				.toList();
		}

		@Override
		public synchronized boolean tryRecordTransmission(GenerationAttemptId attemptId, Instant now) {
			GenerationAttempt attempt = attempts.get(attemptId);
			if (attempt == null
				|| attempt.completedAt() != null
				|| attempt.providerAcceptedAt() != null
				|| attempt.transmissionCount() >= 3) {
				return false;
			}
			GenerationJob job = jobs.get(attempt.generationJobId());
			if (!jobActive(job)
				|| (attempt.submissionRecoveryDeadlineAt() != null
					&& !now.isBefore(attempt.submissionRecoveryDeadlineAt()))
				|| (job.orchestrationDeadlineAt() != null
					&& !now.isBefore(job.orchestrationDeadlineAt()))) {
				return false;
			}
			attempts.put(attemptId, copyTransmission(attempt));
			return true;
		}

		@Override
		public synchronized boolean advanceStepIfActive(
			GenerationJobId jobId,
			int expectedStatusVersion,
			int completedStep
		) {
			GenerationJob job = jobs.get(jobId);
			if (!jobActive(job)
				|| job.statusVersion() != expectedStatusVersion
				|| job.currentStep() != completedStep
				|| completedStep >= job.totalSteps()) {
				return false;
			}
			jobs.put(
				jobId,
				new GenerationJob(
					job.id(),
					job.userId(),
					job.studioSessionId(),
					job.status(),
					job.requestedMode(),
					job.clientRequestId(),
					job.createdAt(),
					job.completedAt(),
					job.avatarImageId(),
					job.productVaultItemIds(),
					job.cancelledAt(),
					job.subjectDeletedAt(),
					job.requestFingerprint(),
					job.avatarRevision(),
					job.statusVersion() + 1,
					job.updatedAt(),
					job.terminalReason(),
					completedStep + 1,
					job.totalSteps(),
					job.renderPlanVersion(),
					job.orchestrationDeadlineAt(),
					job.retryNotBeforeAt(),
					job.retryPolicySnapshot()));
			return true;
		}

		@Override
		public synchronized boolean recordProviderExecutionIfActive(
			GenerationAttemptId attemptId,
			ProviderJobId providerJobId
		) {
			GenerationAttempt attempt = attempts.get(attemptId);
			if (attempt == null) {
				return false;
			}
			if (!jobActive(jobs.get(attempt.generationJobId()))) {
				return false;
			}
			if (attempt.hasDurableProviderJobId()
				&& providerJobId != null
				&& !attempt.providerJobId().equals(providerJobId.value())) {
				return false;
			}
			recordProviderExecution(attemptId, providerJobId);
			return true;
		}

		@Override
		public boolean markSubmitted(GenerationJobId jobId) {
			GenerationJob job = jobs.get(jobId);
			if (!jobActive(job)
				|| (!GenerationJob.QUEUED.equals(job.status())
					&& !GenerationJob.PREFLIGHT.equals(job.status())
					&& !GenerationJob.RETRYING.equals(job.status()))) {
				return false;
			}
			boolean hasProviderId = listAttempts(jobId).stream()
				.anyMatch(attempt -> attempt.providerJobId() != null
					&& !attempt.providerJobId().isBlank()
					&& attempt.completedAt() == null);
			if (!hasProviderId) {
				return false;
			}
			jobs.put(jobId, withLifecycle(
				job,
				GenerationJob.SUBMITTED,
				null,
				job.cancelledAt(),
				job.subjectDeletedAt(),
				job.terminalReason()));
			return true;
		}

		@Override
		public boolean markGenerating(GenerationJobId jobId) {
			GenerationJob job = jobs.get(jobId);
			if (!jobActive(job)
				|| (!GenerationJob.SUBMITTED.equals(job.status()) && !GenerationJob.GENERATING.equals(job.status()))) {
				return false;
			}
			jobs.put(jobId, withLifecycle(
				job,
				GenerationJob.GENERATING,
				null,
				job.cancelledAt(),
				job.subjectDeletedAt(),
				job.terminalReason()));
			return true;
		}

		@Override
		public boolean markPreflight(GenerationJobId jobId) {
			GenerationJob job = jobs.get(jobId);
			if (!jobActive(job) || !GenerationJob.QUEUED.equals(job.status())) {
				return false;
			}
			jobs.put(jobId, withLifecycle(
				job,
				GenerationJob.PREFLIGHT,
				null,
				job.cancelledAt(),
				job.subjectDeletedAt(),
				job.terminalReason()));
			return true;
		}

		@Override
		public boolean failIfDeadlineExceeded(GenerationJobId jobId, Instant now) {
			GenerationJob job = jobs.get(jobId);
			if (!jobActive(job) || job.orchestrationDeadlineAt().isAfter(now)) {
				return false;
			}
			jobs.put(jobId, withLifecycle(
				job,
				GenerationJob.FAILED,
				now,
				job.cancelledAt(),
				job.subjectDeletedAt(),
				TerminalReason.ORCHESTRATION_DEADLINE_EXCEEDED.name()));
			return true;
		}

		private static GenerationJob withLifecycle(
			GenerationJob job,
			String status,
			Instant completedAt,
			Instant cancelledAt,
			Instant subjectDeletedAt,
			String terminalReason
		) {
			return withLifecycle(
				job,
				status,
				completedAt,
				cancelledAt,
				subjectDeletedAt,
				terminalReason,
				GenerationJob.RETRYING.equals(status) ? job.retryNotBeforeAt() : null);
		}

		private static GenerationJob withLifecycle(
			GenerationJob job,
			String status,
			Instant completedAt,
			Instant cancelledAt,
			Instant subjectDeletedAt,
			String terminalReason,
			Instant retryNotBeforeAt
		) {
			return new GenerationJob(
				job.id(),
				job.userId(),
				job.studioSessionId(),
				status,
				job.requestedMode(),
				job.clientRequestId(),
				job.createdAt(),
				completedAt,
				job.avatarImageId(),
				job.productVaultItemIds(),
				cancelledAt,
				subjectDeletedAt,
				job.requestFingerprint(),
				job.avatarRevision(),
				job.statusVersion() + 1,
				job.updatedAt(),
				terminalReason,
				job.currentStep(),
				job.totalSteps(),
				job.renderPlanVersion(),
				job.orchestrationDeadlineAt(),
				retryNotBeforeAt,
				job.retryPolicySnapshot());
		}

		@Override
		public void markAllSubjectDeleted(UserId userId, Instant when) {
			jobs.replaceAll((id, job) -> {
				if (!job.userId().equals(userId) || job.subjectDeletedAt() != null) {
					return job;
				}
				String reason = job.terminalReason() != null ? job.terminalReason() : GenerationJob.SUBJECT_DELETED;
				Instant completedAt = job.completedAt() != null ? job.completedAt() : when;
				return withLifecycle(job, GenerationJob.FAILED, completedAt, job.cancelledAt(), when, reason);
			});
		}

		@Override
		public List<GenerationJob> listJobsByUser(UserId userId) {
			return jobs.values().stream()
				.filter(job -> job.userId().equals(userId))
				.sorted((left, right) -> {
					int created = left.createdAt().compareTo(right.createdAt());
					return created != 0 ? created : left.id().value().compareTo(right.id().value());
				})
				.toList();
		}

		private void clearJob(GenerationJobId jobId) {
			jobs.remove(jobId);
			attempts.values().removeIf(attempt -> attempt.generationJobId().equals(jobId));
		}

		private static GenerationAttempt copy(
			GenerationAttempt attempt,
			Instant ownedAt,
			String providerJobId,
			boolean indeterminate
		) {
			return new GenerationAttempt(
				attempt.id(),
				attempt.generationJobId(),
				attempt.provider(),
				attempt.model(),
				providerJobId,
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
				ownedAt,
				indeterminate,
				attempt.tempStorageKey(),
				attempt.providerOutputRef(),
				attempt.renderStepId(),
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
				attempt.costStatus(),
				attempt.actualBilledUnits(),
				attempt.pricingObservedAt(),
				attempt.billingUnit());
		}
		private static GenerationAttempt copyTransmission(GenerationAttempt attempt) {
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
				attempt.renderStepId(),
				attempt.transmissionCount() + 1,
				attempt.providerAcceptedAt(),
				attempt.executionDeadlineAt(),
				attempt.submissionRecoveryDeadlineAt(),
				attempt.qualityPolicyVersion(),
				attempt.pricingCatalogVersion(),
				attempt.estimatedUnits(),
				attempt.unitPrice(),
				attempt.estimatedCostCeiling(),
				attempt.actualCost(),
				attempt.costStatus(),
				attempt.actualBilledUnits(),
				attempt.pricingObservedAt(),
				attempt.billingUnit());
		}
	}

	private static final class InMemoryLookStore implements LookStore {
		private final List<Look> inserted = new CopyOnWriteArrayList<>();
		private final List<String> operations;

		private InMemoryLookStore(List<String> operations) {
			this.operations = operations;
		}
		private boolean throwOnInsert;
		private volatile InMemoryGenerationStore generationStore;

		private void attach(InMemoryGenerationStore store) {
			this.generationStore = store;
		}

		@Override
		public Look insert(Look look) {
			if (throwOnInsert) {
				throw new RuntimeException("look insert failed");
			}
			inserted.add(look);
			operations.add("LOOK_INSERTED");
			return look;
		}

		@Override
		public synchronized Optional<Look> insertIfJobActive(Look look) {
			if (throwOnInsert) {
				throw new RuntimeException("look insert failed");
			}
			InMemoryGenerationStore store = generationStore;
			if (store != null) {
				Optional<GenerationJob> found = store.peekJob(look.generationJobId());
				if (found.isEmpty()) {
					return Optional.empty();
				}
				GenerationJob job = found.get();
				if (job.completedAt() != null || job.cancelledAt() != null || job.subjectDeletedAt() != null) {
					return Optional.empty();
				}
			}
			Optional<Look> existing = findByGenerationJobId(look.generationJobId());
			if (existing.isPresent()) {
				return existing;
			}
			inserted.add(look);
			operations.add("LOOK_INSERTED");
			return Optional.of(look);
		}

		@Override
		public Optional<Look> findById(LookId id) {
			return inserted.stream().filter(look -> look.id().equals(id)).findFirst();
		}

		@Override
		public Optional<Look> findByGenerationJobId(com.aifashionvault.shared.GenerationJobId generationJobId) {
			return inserted.stream().filter(look -> generationJobId.equals(look.generationJobId())).findFirst();
		}

		private List<Look> inserted() {
			return inserted;
		}
	}

	private static final class InMemoryNotificationStore implements NotificationStore {
		private final List<Notification> inserted = new CopyOnWriteArrayList<>();
		private final List<String> operations;

		private InMemoryNotificationStore(List<String> operations) {
			this.operations = operations;
		}

		@Override
		public void insert(Notification notification) {
			inserted.add(notification);
			operations.add(notification.type());
		}

		@Override
		public Optional<com.aifashionvault.notification.Notification> findById(NotificationId id) {
			return inserted.stream().filter(notification -> notification.id().equals(id)).findFirst();
		}

		private List<Notification> inserted() {
			return inserted;
		}

		private boolean lookReadyAfterLook() {
			return operations.indexOf("LOOK_READY") > operations.indexOf("LOOK_INSERTED");
		}
	}

	private static final class InMemoryQueue implements GenerationWorkQueue {
		private final List<GenerationWorkLease> deleted = new CopyOnWriteArrayList<>();
		private final List<String> operations;

		private InMemoryQueue(List<String> operations) {
			this.operations = operations;
		}
		private boolean throwOnExtend;

		@Override
		public void send(UUID generationJobId) {
		}

		@Override
		public Optional<GenerationWorkLease> receive() {
			return Optional.empty();
		}

		@Override
		public void extendVisibility(GenerationWorkLease lease, Duration extension) {
			extensions.incrementAndGet();
			if (throwOnExtend) {
				throw new RuntimeException("visibility extension failed");
			}
		}

		private int extensions() {
			return extensions.get();
		}

		private final AtomicInteger extensions = new AtomicInteger();

		@Override
		public void delete(GenerationWorkLease lease) {
			deleted.add(lease);
			operations.add("LEASE_ACK");
		}

		private List<GenerationWorkLease> deleted() {
			return deleted;
		}
	}

	private static final class InMemoryObjects implements ObjectStore, GenerationMediaStore {
		private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
		private final AtomicInteger headCount = new AtomicInteger();
		private final AtomicInteger readCount = new AtomicInteger();
		private final AtomicInteger presignCount = new AtomicInteger();
		private final AtomicInteger putCount = new AtomicInteger();
		private final AtomicInteger tempWriteCount = new AtomicInteger();
		private final Set<String> uniqueTempKeys = ConcurrentHashMap.newKeySet();
		private final Set<String> uniqueLookKeys = ConcurrentHashMap.newKeySet();
		private final List<String> deletedKeys = new CopyOnWriteArrayList<>();
		private volatile Runnable afterClaim = () -> {
		};

		private volatile int failPutBoundedWithStorageUnavailable;

		private void put(ObjectRef ref, byte[] bytes) {
			objects.put(ref.objectKey(), bytes);
		}

		@Override
		public void putBounded(StorageKey dest, InputStream source, long maxBytes, String mediaType) {
			if (failPutBoundedWithStorageUnavailable > 0) {
				failPutBoundedWithStorageUnavailable--;
				throw new com.aifashionvault.platform.objectstore.ObjectStoreExceptions.ObjectStoreUnavailableException(
					"object store unavailable");
			}
			try {
				byte[] bytes = source.readAllBytes();
				if (bytes.length > maxBytes) {
					throw new IllegalStateException("provider output exceeds declared media bound");
				}
				putCount.incrementAndGet();
				if (dest.value().startsWith("temp/")) {
					tempWriteCount.incrementAndGet();
					uniqueTempKeys.add(dest.value());
				}
				objects.put(dest.value(), bytes);
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
		}

		@Override
		public ClaimResult claim(GenerationClaimCommand command) {
			byte[] source = objects.get(command.sourceTempKey().value());
			if (source == null) {
				return new ClaimResult.Conflict();
			}
			if (objects.containsKey(command.destinationLookKey().value())) {
				return new ClaimResult.Replay();
			}
			objects.put(command.destinationLookKey().value(), source);
			uniqueLookKeys.add(command.destinationLookKey().value());
			afterClaim.run();
			return new ClaimResult.Copied();
		}

		@Override public void preflightProductInput(com.aifashionvault.platform.objectstore.ProductInputClaimCommand command) {
			throw new UnsupportedOperationException("product input");
		}

		@Override public ClaimResult claimProductInput(com.aifashionvault.platform.objectstore.ProductInputClaimCommand command) {
			throw new UnsupportedOperationException("product input");
		}

		@Override public void releaseProductInput(com.aifashionvault.platform.objectstore.ProductInputClaimCommand command) {
			throw new UnsupportedOperationException("product input");
		}

		@Override
		public UploadCapability presignStagingPut(UserId userId, UploadId uploadId, MediaDeclaration declaration) {
			throw new UnsupportedOperationException("presignStagingPut");
		}

		@Override
		public ObjectMetadata head(ObjectRef object) {
			headCount.incrementAndGet();
			byte[] bytes = objects.get(object.objectKey());
			if (bytes == null) {
				throw new IllegalStateException("missing");
			}
			return new ObjectMetadata(bytes.length, "image/jpeg", "v1");
		}

		@Override
		public byte[] readBounded(ObjectRef object, String expectedVersion, int maxBytes) {
			readCount.incrementAndGet();
			byte[] bytes = objects.get(object.objectKey());
			return bytes == null ? new byte[0] : bytes;
		}

		@Override
		public void delete(ObjectRef object) {
			objects.remove(object.objectKey());
			deletedKeys.add(object.objectKey());
		}

		@Override
		public List<StoredObject> list(StorageDomain boundary, String prefix) {
			return List.of();
		}

		@Override
		public ClaimResult claim(ClaimCommand command) {
			throw new UnsupportedOperationException("claim");
		}

		@Override
		public void purgeUserObjects(UserId userId) {
		}

		@Override
		public void cleanupTemp() {
		}

		@Override
		public PresignedAccess presignGet(ObjectRef object, Duration ttl) {
			presignCount.incrementAndGet();
			return new PresignedAccess(
				object,
				"GET",
				URI.create("https://private.example.test/object"),
				Map.of(),
				NOW.plus(ttl));
		}

		private int headCount() {
			return headCount.get();
		}

		private int readCount() {
			return readCount.get();
		}

		private int presignCount() {
			return presignCount.get();
		}

		private int putCount() {
			return putCount.get();
		}

		private int tempWriteCount() {
			return tempWriteCount.get();
		}

		private Set<String> uniqueTempKeys() {
			return uniqueTempKeys;
		}

		private Set<String> uniqueLookKeys() {
			return uniqueLookKeys;
		}

		private List<String> deletedKeys() {
			return deletedKeys;
		}
	}

	private static final class TestCostCircuitBreaker implements GlobalCostCircuitBreaker {
		private volatile boolean blocked;
		private final List<ProviderExecutionBudgetRequest> requests = new CopyOnWriteArrayList<>();

		@Override
		public AbuseDecision authorize(ProviderExecutionBudgetRequest request) {
			requests.add(request);
			return new AbuseDecision(
				!blocked,
				blocked ? AbuseDecisionReason.USER_COST_BUDGET_EXHAUSTED : AbuseDecisionReason.ALLOWED,
				BigDecimal.ZERO,
				BigDecimal.ONE,
				request.currency(),
				request.now().minus(Duration.ofDays(1)),
				request.now().plus(Duration.ofDays(1)));
		}

		private void trip() {
			blocked = true;
		}

		private List<ProviderExecutionBudgetRequest> requests() {
			return List.copyOf(requests);
		}
	}

	private static final class TestProviderHealthGate implements com.aifashionvault.generation.ProviderHealthGate {
		private volatile boolean healthy = true;

		@Override
		public boolean allowsNewExecution(com.aifashionvault.generation.vton.ProviderKey provider) {
			return healthy;
		}

		private void close() {
			healthy = false;
		}
	}
}
