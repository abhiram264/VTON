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
import java.util.concurrent.atomic.AtomicInteger;
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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GenerationOrchestrationTest {

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
	private static final Pattern SENSITIVE = Pattern.compile(
		"https?://|/9j/|iVBOR|Bearer |base64|X-Amz-Signature|sk-[A-Za-z0-9]|provider-body",
		Pattern.CASE_INSENSITIVE);
	private static final int MAX_LOGICAL_ATTEMPTS_PER_STEP = 3;


	private InMemoryGenerationStore generationStore;
	private InMemoryLookStore lookStore;
	private InMemoryNotificationStore notificationStore;
	private InMemoryQueue queue;
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
		lookStore = new InMemoryLookStore();
		notificationStore = new InMemoryNotificationStore();
		queue = new InMemoryQueue();
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

	@Test
	void t1_happyPathOneSubmitTempPassLookReadyThenDelete() {
		generationStore.insertJob(queuedJob());

		processor.process(lease);

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(provider.getStatusCount()).isEqualTo(1);
		assertThat(provider.openOutputCount()).isEqualTo(1);
		assertThat(objects.tempWriteCount()).isEqualTo(1);
		assertThat(lookStore.inserted()).hasSize(1);
		assertThat(lookStore.inserted().getFirst().qualityStatus()).isEqualTo(QualityDecision.PASS);
		assertThat(notificationStore.inserted()).hasSize(1);
		assertThat(notificationStore.inserted().getFirst().type()).isEqualTo("LOOK_READY");
		assertThat(notificationStore.lookReadyAfterLook()).isTrue();
		assertThat(queue.deleted()).containsExactly(lease);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().completedAt()).isEqualTo(NOW);
	}

	@Test
	void firstValidTurnMovesQueuedToPreflight() {
		generationStore.insertJob(queuedJob());
		provider.statusResult(new StatusQueryResult.Observed(new GenerationStatus.Queued()));

		processor.process(lease);

		GenerationJob loaded = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(loaded.status()).isNotEqualTo(GenerationJob.QUEUED);
		assertThat(loaded.status()).isIn(GenerationJob.PREFLIGHT, GenerationJob.SUBMITTED);
		assertThat(loaded.completedAt()).isNull();
	}

	@Test
	void terminalJobAcknowledgesReceiptWithoutWork() {
		generationStore.insertJob(completedJob());

		processor.process(lease);

		assertThat(provider.submitCount()).isZero();
		assertThat(provider.getStatusCount()).isZero();
		assertThat(lookStore.inserted()).isEmpty();
		assertThat(notificationStore.inserted()).isEmpty();
		assertThat(queue.deleted()).containsExactly(lease);
	}

	@Test
	void successorOutboxIsCommittedBeforeNonterminalAck() {
		RecordingWorkOutbox outbox = new RecordingWorkOutbox();
		processor = processor(provider, null, outbox);
		generationStore.insertJob(queuedJob());
		provider.statusResult(new StatusQueryResult.Observed(new GenerationStatus.Queued()));

		processor.process(lease);

		assertThat(outbox.successors()).hasSize(1);
		assertThat(outbox.successors().getFirst().generationJobId()).isEqualTo(JOB_ID.value());
		assertThat(outbox.successors().getFirst().notBefore()).isEqualTo(NOW.plusSeconds(3));
		assertThat(queue.deleted()).containsExactly(lease);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().completedAt()).isNull();
	}

	@Test
	void deadlineTerminalizesActiveJob() {
		generationStore.insertJob(overdueJob());

		processor.process(lease);

		GenerationJob loaded = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(loaded.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(loaded.terminalReason()).isEqualTo(TerminalReason.ORCHESTRATION_DEADLINE_EXCEEDED.name());
		assertThat(loaded.completedAt()).isEqualTo(NOW);
		assertThat(provider.submitCount()).isZero();
		assertThat(notificationStore.inserted()).extracting(Notification::type).containsExactly("GENERATION_FAILED");
        assertThat(notificationStore.inserted().getFirst().payload()).containsOnlyKeys("generationId");
		assertThat(queue.deleted()).containsExactly(lease);
	}

	@Test
	void duplicateDeliveryAfterTerminalIsNoOp() {
		generationStore.insertJob(queuedJob());

		processor.process(lease);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().completedAt()).isEqualTo(NOW);
		int notifications = notificationStore.inserted().size();
		GenerationWorkLease second = new GenerationWorkLease(JOB_ID.value(), "receipt-2");

		processor.process(second);

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(lookStore.inserted()).hasSize(1);
		assertThat(notificationStore.inserted()).hasSize(notifications);
		assertThat(queue.deleted()).containsExactly(lease, second);
	}

	@Test
	void t6_missingJobIsNoOp() {
		processor.process(lease);

		assertThat(provider.submitCount()).isZero();
		assertThat(provider.getStatusCount()).isZero();
		assertThat(objects.headCount()).isZero();
		assertThat(objects.readCount()).isZero();
		assertThat(objects.presignCount()).isZero();
		assertThat(objects.putCount()).isZero();
		assertThat(lookStore.inserted()).isEmpty();
		assertThat(notificationStore.inserted()).isEmpty();
		assertThat(queue.deleted()).containsExactly(lease);
	}

	@Test
	void t7_missingOrUnauthorizedMediaFailsBeforeSubmit() {
		generationStore.insertJob(queuedJob());
		when(resolver.resolve(any())).thenThrow(new UnauthorizedGenerationMediaException("generation media is not authorized"));

		processor.process(lease);

		assertThat(provider.submitCount()).isZero();
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		assertThat(generationStore.listAttempts(JOB_ID).getFirst().technicalOutcome()).isEqualTo("FAILED");
		assertThat(generationStore.listAttempts(JOB_ID).getFirst().failureCode())
			.isEqualTo(TerminalReason.INTERNAL_INVARIANT_FAILURE.name());
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.FAILED);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().terminalReason())
			.isEqualTo(TerminalReason.INTERNAL_INVARIANT_FAILURE.name());
		assertThat(lookStore.inserted()).isEmpty();
		assertThat(notificationStore.inserted()).hasSize(1);
		assertThat(notificationStore.inserted().getFirst().type()).isEqualTo("GENERATION_FAILED");
		assertThat(queue.deleted()).containsExactly(lease);
	}

	@Test
	void t8_duplicateProcessSameAttemptSubmitsAtMostOnce() {
		generationStore.insertJob(queuedJob());

		processor.process(lease);
		processor.process(new GenerationWorkLease(JOB_ID.value(), "receipt-2"));

		assertThat(provider.submitCount()).isLessThanOrEqualTo(1);
		assertThat(lookStore.inserted()).hasSize(1);
	}

	@Test
	void t9_concurrentProcessSubmitsExactlyOnce() throws Exception {
		generationStore.insertJob(queuedJob());
		generationStore.insertAttempt(openAttempt(1, false, null));
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(2);
		try {
			pool.submit(() -> runProcess(start, done, new GenerationWorkLease(JOB_ID.value(), "r-a")));
			pool.submit(() -> runProcess(start, done, new GenerationWorkLease(JOB_ID.value(), "r-b")));
			start.countDown();
			assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(provider.submitCount()).isEqualTo(1);
	}

	@Test
	void t10_persistedProviderJobIdPollsInsteadOfSubmit() {
		generationStore.insertJob(queuedJob());
		generationStore.insertAttempt(openAttempt(1, false, "provider-job-1"));

		processor.process(lease);

		assertThat(provider.submitCount()).isZero();
		assertThat(provider.getStatusCount()).isEqualTo(1);
		assertThat(lookStore.inserted()).hasSize(1);
	}

	@Test
	void acceptedExecutionDeadlineTerminalizesWithProviderTimeoutExhausted() {
		generationStore.insertJob(queuedJob());
		generationStore.insertAttempt(expiredAcceptedAttempt(1));

		processor.process(lease);

		GenerationJob loaded = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(loaded.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(loaded.terminalReason()).isEqualTo(TerminalReason.PROVIDER_TIMEOUT_EXHAUSTED.name());
		assertThat(loaded.retryNotBeforeAt()).isNull();
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		GenerationAttempt finished = generationStore.listAttempts(JOB_ID).getFirst();
		assertThat(finished.technicalOutcome()).isEqualTo("FAILED");
		assertThat(finished.failureCode()).isEqualTo(TerminalReason.PROVIDER_TIMEOUT_EXHAUSTED.name());
		assertThat(provider.getStatusCount()).isZero();
		assertThat(queue.deleted()).containsExactly(lease);
	}

	@Test
	void acceptedExecutionDeadlineExhaustedBudgetFailsWithoutRetry() {
		generationStore.insertJob(queuedJob());
		generationStore.insertAttempt(completedAttempt(1));
		generationStore.insertAttempt(completedAttempt(2));
		generationStore.insertAttempt(expiredAcceptedAttempt(3));

		processor.process(lease);

		GenerationJob loaded = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(loaded.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(loaded.terminalReason()).isEqualTo(TerminalReason.PROVIDER_TIMEOUT_EXHAUSTED.name());
		assertThat(loaded.retryNotBeforeAt()).isNull();
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(3);
		assertThat(provider.getStatusCount()).isZero();
		assertThat(generationStore.listAttempts(JOB_ID).getLast().failureCode())
			.isEqualTo(TerminalReason.PROVIDER_TIMEOUT_EXHAUSTED.name());
		assertThat(queue.deleted()).containsExactly(lease);
	}

	@Test
	void unknownUnavailablePollFailsClosedWithoutAnotherPoll() {
		generationStore.insertJob(queuedJob());
		generationStore.insertAttempt(openAttempt(1, false, "provider-job-1"));
		provider.statusResult(new StatusQueryResult.Unavailable(
			new ProviderFailure(ProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE, true, "UNKNOWN")));

		processor.process(lease);

		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.FAILED);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().terminalReason()).isEqualTo("PROVIDER_UNKNOWN_FAILURE");
		assertThat(provider.getStatusCount()).isEqualTo(1);
		processor.process(new GenerationWorkLease(JOB_ID.value(), "duplicate"));
		assertThat(provider.getStatusCount()).isEqualTo(1);
	}

	@Test
	void unknownUnavailableCaptureFailsClosedWithoutAnotherPoll() {
		generationStore.insertJob(queuedJob());
		provider.statusResult(new StatusQueryResult.Unavailable(
			new ProviderFailure(ProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE, true, "UNKNOWN")));

		processor.process(lease);

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.FAILED);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().terminalReason()).isEqualTo("PROVIDER_UNKNOWN_FAILURE");
		assertThat(provider.getStatusCount()).isEqualTo(1);
		processor.process(new GenerationWorkLease(JOB_ID.value(), "duplicate"));
		assertThat(provider.getStatusCount()).isEqualTo(1);
	}

	@Test
	void dueRetryWithExhaustedBudgetBecomesFailed() {
		generationStore.insertJob(retryingJob());
		generationStore.insertAttempt(completedAttempt(1));
		generationStore.insertAttempt(completedAttempt(2));
		generationStore.insertAttempt(completedAttempt(3));

		processor.process(lease);

		GenerationJob loaded = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(loaded.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(loaded.retryNotBeforeAt()).isNull();
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(3);
		assertThat(provider.submitCount()).isZero();
		assertThat(queue.deleted()).containsExactly(lease);
	}

	@Test
	void terminalMetricsUseWhitelistedCategories() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		processor = processor(provider, null, null, null, new GenerationMetrics(registry));
		generationStore.insertJob(queuedJob());

		processor.process(lease);

		assertThat(registry.find(GenerationMetrics.TERMINAL)
			.tag("category", GenerationMetrics.TERMINAL_COMPLETED)
			.counter())
			.isNotNull();

		generationStore.clearJob(JOB_ID);
		generationStore.insertJob(queuedJob());
		qualityGate.decision = QualityDecision.FAIL;

		processor.process(new GenerationWorkLease(JOB_ID.value(), "failed"));

		assertThat(registry.find(GenerationMetrics.TERMINAL)
			.tag("category", GenerationMetrics.TERMINAL_FAILED)
			.counter())
			.isNotNull();
	}

	@Test
	void completedAttemptFeedsUserAttributedSpendExactlyOnce() {
		GenerationMetrics metrics = new GenerationMetrics(new SimpleMeterRegistry(), CLOCK);
		processor = processor(provider, null, null, null, metrics);
		generationStore.insertJob(queuedJob());
		provider.usage(new ProviderUsage(new BigDecimal("1.50"), new BigDecimal("0.20"), "usd"));

		processor.process(lease);
		processor.process(new GenerationWorkLease(JOB_ID.value(), "duplicate"));

		var aggregate = metrics.aggregate(new CostQuery(
			USER_ID,
			"recording",
			"default",
			"USD",
			NOW.minusSeconds(1),
			NOW.plusSeconds(1)));
		assertThat(aggregate.totalCost()).isEqualByComparingTo("0.20");
		assertThat(aggregate.requestCount()).isEqualTo(1);
		assertThat(aggregate.qualityApprovedLookCount()).isEqualTo(1);
		var breaker = new AggregateBackedCostCircuitBreaker(
			metrics,
			new AbuseBudgetPolicy(
				BigDecimal.TEN,
				new BigDecimal("0.25"),
				Map.of("recording", BigDecimal.TEN),
				"USD",
				Duration.ofDays(1)));
		assertThat(breaker.authorize(new ProviderExecutionBudgetRequest(
			USER_ID,
			"recording",
			"default",
			new BigDecimal("0.05"),
			"USD",
			NOW.plusSeconds(1))).reason()).isEqualTo(AbuseDecisionReason.USER_COST_BUDGET_EXHAUSTED);
	}


	@Test
	void t11_retryableRejectedClaimsNewAttemptOnlyWhenDue() {
		generationStore.insertJob(queuedJob());
		provider.submitResult(new GenerationSubmission.Rejected(RecordingVtonProvider.retryableReject()));

		processor.process(lease);

		GenerationJob scheduled = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(scheduled.status()).isEqualTo(GenerationJob.RETRYING);
		assertThat(scheduled.retryNotBeforeAt()).isEqualTo(NOW);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		assertThat(queue.deleted()).containsExactly(lease);

		provider.submitResult(new GenerationSubmission.Accepted(new ProviderJobId("provider-job-retry"), Optional.empty()));
		processor.process(new GenerationWorkLease(JOB_ID.value(), "retry-due"));
		List<GenerationAttempt> attempts = generationStore.listAttempts(JOB_ID);
		assertThat(attempts).hasSize(2);
		assertThat(attempts.getFirst().technicalOutcome()).isEqualTo("FAILED");
		assertThat(attempts.getFirst().failureCode()).isEqualTo("PROVIDER_TEMPORARY");
		assertThat(attempts.get(1).attemptNumber()).isEqualTo(2);
		assertThat(attempts.get(1).completedAt()).isEqualTo(NOW);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.COMPLETED);
		assertThat(provider.submitCount()).isEqualTo(2);
	}
	@Test
	void repeatedRetryableFailuresExhaustGlobalTechnicalRetryBudgetWithoutStrandingRetrying() {
		generationStore.insertJob(queuedJob());
		provider.submitResult(new GenerationSubmission.Rejected(RecordingVtonProvider.retryableReject()));

		processor.process(lease);

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		assertThat(generationStore.listAttempts(JOB_ID))
			.extracting(GenerationAttempt::completedAt)
			.containsExactly(NOW);
		GenerationJob afterFirst = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(afterFirst.status()).isEqualTo(GenerationJob.RETRYING);
		assertThat(afterFirst.retryNotBeforeAt()).isEqualTo(NOW);

		processor.process(new GenerationWorkLease(JOB_ID.value(), "retry-due-2"));

		assertThat(provider.submitCount()).isEqualTo(2);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(2);
		assertThat(generationStore.listAttempts(JOB_ID))
			.extracting(GenerationAttempt::attemptNumber)
			.containsExactly(1, 2);
		assertThat(generationStore.listAttempts(JOB_ID))
			.extracting(GenerationAttempt::completedAt)
			.containsExactly(NOW, NOW);
		GenerationJob afterSecond = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(afterSecond.status()).isEqualTo(GenerationJob.RETRYING);
		assertThat(afterSecond.retryNotBeforeAt()).isEqualTo(NOW);

		processor.process(new GenerationWorkLease(JOB_ID.value(), "retry-due-3"));

		assertThat(provider.submitCount()).isEqualTo(3);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(3);
		assertThat(generationStore.listAttempts(JOB_ID))
			.extracting(GenerationAttempt::attemptNumber)
			.containsExactly(1, 2, 3);
		assertThat(generationStore.listAttempts(JOB_ID))
			.extracting(GenerationAttempt::completedAt)
			.containsExactly(NOW, NOW, NOW);
		GenerationJob exhausted = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(exhausted.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(exhausted.terminalReason()).isEqualTo("RETRY_BUDGET_EXHAUSTED");
		assertThat(exhausted.retryNotBeforeAt()).isNull();
		assertThat(queue.deleted()).containsExactly(
			lease,
			new GenerationWorkLease(JOB_ID.value(), "retry-due-2"),
			new GenerationWorkLease(JOB_ID.value(), "retry-due-3"));
	}
	@Test
	void dueRetryAtStepConcentrationLimitFailsWithoutFourthAttempt() {
		RenderPlan plan = twoStepPlan();
		UUID stepId = plan.steps().getFirst().id();
		generationStore.renderPlan(JOB_ID, plan);
		generationStore.insertJob(retryingJobForPlan(plan));
		generationStore.insertAttempt(completedStepAttempt(1, stepId, "FAILED", "PROVIDER_TEMPORARY"));
		generationStore.insertAttempt(completedStepAttempt(2, stepId, "FAILED", "PROVIDER_TEMPORARY"));
		generationStore.insertAttempt(completedStepAttempt(3, stepId, "FAILED", "PROVIDER_TEMPORARY"));

		processor.process(lease);

		GenerationJob loaded = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(3);
		assertThat(generationStore.listAttempts(JOB_ID)).extracting(GenerationAttempt::renderStepId)
			.containsOnly(stepId);
		assertThat(loaded.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(loaded.terminalReason()).isEqualTo("RETRY_BUDGET_EXHAUSTED");
		assertThat(loaded.retryNotBeforeAt()).isNull();
		assertThat(provider.submitCount()).isZero();
		assertThat(queue.deleted()).containsExactly(lease);
	}

	@Test
	void successfulStepWithThreeAttemptsStillMintsLaterBaseline() {
		RenderPlan plan = threeStepPlan();
		UUID firstStepId = plan.steps().getFirst().id();
		UUID secondStepId = plan.steps().get(1).id();
		generationStore.renderPlan(JOB_ID, plan);
		generationStore.insertJob(jobForPlan(plan));
		generationStore.insertAttempt(completedStepAttempt(1, firstStepId, "FAILED", "PROVIDER_TEMPORARY"));
		generationStore.insertAttempt(completedStepAttempt(2, firstStepId, "FAILED", "PROVIDER_TEMPORARY"));
		generationStore.insertAttempt(completedStepAttempt(3, firstStepId, "SUCCESS", null));

		processor.process(lease);

		GenerationJob loaded = generationStore.findJob(JOB_ID).orElseThrow();
		List<GenerationAttempt> attempts = generationStore.listAttempts(JOB_ID);
		assertThat(loaded.currentStep()).isEqualTo(2);
		assertThat(attempts).hasSize(4);
		assertThat(attempts).extracting(GenerationAttempt::renderStepId)
			.containsExactly(firstStepId, firstStepId, firstStepId, secondStepId);
		assertThat(attempts.getLast().completedAt()).isNull();
		assertThat(provider.submitCount()).isZero();
	}

	@Test
	void retryableFailurePersistsDurableRetryBeforeCreatingSuccessor() {
		generationStore.insertJob(queuedJob());
		provider.submitResult(new GenerationSubmission.Rejected(RecordingVtonProvider.retryableReject()));

		processor.process(lease);

		GenerationJob scheduled = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(scheduled.status()).isEqualTo(GenerationJob.RETRYING);
		assertThat(scheduled.retryNotBeforeAt()).isNotNull();
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		assertThat(generationStore.listAttempts(JOB_ID).getFirst().completedAt()).isEqualTo(NOW);
		assertThat(queue.deleted()).containsExactly(lease);
	}
	@Test
	void fencedRetryFakeRejectsStaleVersionAndOldPredecessor() {
		generationStore.insertJob(queuedJob());
		GenerationAttempt first = openAttempt(1, false, "provider-job-1");
		generationStore.insertAttempt(first);
		GenerationAttempt completedFirst = completedAttempt(1);
		Instant retryNotBeforeAt = NOW.plusSeconds(20);

		GenerationJob initial = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(generationStore.scheduleRetryIfActive(
			completedFirst,
			initial.statusVersion() - 1,
			retryNotBeforeAt)).isFalse();
		GenerationJob afterStaleVersion = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(afterStaleVersion.status()).isEqualTo(initial.status());
		assertThat(afterStaleVersion.statusVersion()).isEqualTo(initial.statusVersion());
		assertThat(afterStaleVersion.retryNotBeforeAt()).isEqualTo(initial.retryNotBeforeAt());
		assertThat(generationStore.findAttempt(first.id()).orElseThrow().completedAt()).isNull();

		GenerationAttempt second = openAttempt(2, false, "provider-job-2");
		generationStore.insertAttempt(second);
		GenerationJob beforeOldPredecessor = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(generationStore.scheduleRetryIfActive(
			completedFirst,
			beforeOldPredecessor.statusVersion(),
			retryNotBeforeAt)).isFalse();
		GenerationJob afterOldPredecessor = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(afterOldPredecessor.status()).isEqualTo(beforeOldPredecessor.status());
		assertThat(afterOldPredecessor.statusVersion()).isEqualTo(beforeOldPredecessor.statusVersion());
		assertThat(afterOldPredecessor.retryNotBeforeAt()).isEqualTo(beforeOldPredecessor.retryNotBeforeAt());
		assertThat(generationStore.findAttempt(first.id()).orElseThrow().completedAt()).isNull();

		GenerationAttempt completedSecond = completedAttempt(2);
		GenerationJob beforeCurrentPredecessor = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(generationStore.scheduleRetryIfActive(
			completedSecond,
			beforeCurrentPredecessor.statusVersion(),
			retryNotBeforeAt)).isTrue();
		GenerationJob scheduled = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(scheduled.status()).isEqualTo(GenerationJob.RETRYING);
		assertThat(scheduled.statusVersion()).isEqualTo(beforeCurrentPredecessor.statusVersion() + 1);
		assertThat(scheduled.retryNotBeforeAt()).isEqualTo(retryNotBeforeAt);
		assertThat(generationStore.findAttempt(second.id()).orElseThrow().completedAt())
			.isEqualTo(completedSecond.completedAt());
		assertThat(generationStore.scheduleRetryIfActive(
			completedSecond,
			beforeCurrentPredecessor.statusVersion(),
			retryNotBeforeAt)).isFalse();
	}

	@Test
	void t12_indeterminateUnsafeDoesNotResubmit() {
		RecordingWorkOutbox outbox = new RecordingWorkOutbox();
		processor = processor(provider, outbox, CLOCK);
		generationStore.insertJob(queuedJob());
		provider.submitResult(new GenerationSubmission.Indeterminate(RecordingVtonProvider.indeterminateFailure()));

		processor.process(lease);
		processor.process(new GenerationWorkLease(JOB_ID.value(), "receipt-2"));

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID).getFirst().submissionIndeterminate()).isTrue();
		// Q23/R23: an unsafe indeterminate submission waits out the bounded
		// recovery window instead of terminalizing or resubmitting.
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().completedAt()).isNull();
		assertThat(notificationStore.inserted()).isEmpty();
		assertThat(queue.deleted()).hasSize(2);
		assertThat(outbox.successors()).extracting(successor -> successor.notBefore())
			.containsExactly(NOW.plusSeconds(3), NOW.plusSeconds(3));

		processor = processor(provider, outbox, Clock.fixed(NOW.plus(Duration.ofMinutes(5)), ZoneOffset.UTC));
		processor.process(new GenerationWorkLease(JOB_ID.value(), "receipt-3"));

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.FAILED);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().terminalReason())
			.isEqualTo(TerminalReason.SUBMISSION_OUTCOME_INDETERMINATE.name());
		assertThat(notificationStore.inserted()).extracting(Notification::type).contains("GENERATION_FAILED");
        assertThat(notificationStore.inserted().getFirst().payload()).containsOnlyKeys("generationId");
	}

	@Test
	void coincidentDeadlinesTerminateIndeterminateAttemptWithRecoveryReason() {
		RecordingWorkOutbox outbox = new RecordingWorkOutbox();
		processor = processor(provider, outbox, CLOCK);
		Instant coincidentDeadline = NOW.plus(Duration.ofMinutes(4));
		generationStore.insertJob(queuedJobWithDeadline(coincidentDeadline));
		provider.submitResult(new GenerationSubmission.Indeterminate(RecordingVtonProvider.indeterminateFailure()));

		processor.process(lease);
		processor.process(new GenerationWorkLease(JOB_ID.value(), "receipt-2"));

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID).getFirst().submissionIndeterminate()).isTrue();
		// The short orchestration window clamps the recovery deadline onto it,
		// so both deadlines coincide at the same instant.
		assertThat(generationStore.listAttempts(JOB_ID).getFirst().submissionRecoveryDeadlineAt())
			.isEqualTo(coincidentDeadline);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().completedAt()).isNull();

		processor = processor(
			provider,
			outbox,
			Clock.fixed(coincidentDeadline.plusSeconds(1), ZoneOffset.UTC));
		processor.process(new GenerationWorkLease(JOB_ID.value(), "receipt-3"));

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.FAILED);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().terminalReason())
			.isEqualTo(TerminalReason.SUBMISSION_OUTCOME_INDETERMINATE.name());
		assertThat(notificationStore.inserted()).extracting(Notification::type).contains("GENERATION_FAILED");
        assertThat(notificationStore.inserted().getFirst().payload()).containsOnlyKeys("generationId");
		assertThat(queue.deleted()).containsExactly(
			lease,
			new GenerationWorkLease(JOB_ID.value(), "receipt-2"),
			new GenerationWorkLease(JOB_ID.value(), "receipt-3"));
	}

	@Test
	void t13_replaySafeSameKeyReusesSubmissionKey() {
		ReplaySafeFakeVtonProvider replaySafe = new ReplaySafeFakeVtonProvider();
		processor = processor(replaySafe);
		generationStore.insertJob(queuedJob());

		processor.process(lease);
		processor.process(new GenerationWorkLease(JOB_ID.value(), "receipt-2"));

		assertThat(replaySafe.submitCount()).isEqualTo(2);
		assertThat(replaySafe.submittedKeys()).hasSize(2);
		assertThat(replaySafe.submittedKeys().getFirst()).isEqualTo(replaySafe.submittedKeys().get(1));
		assertThat(replaySafe.remoteId(replaySafe.submittedKeys().getFirst())).isNotNull();
	}

	@Test
	void t14_extendVisibilityFailureStillOneSubmit() {
		generationStore.insertJob(queuedJob());
		queue.throwOnExtend = true;

		processor.process(lease);
		processor.process(new GenerationWorkLease(JOB_ID.value(), "receipt-2"));

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(lookStore.inserted()).hasSize(1);
	}

	@Test
	void t15_deleteOnlyAfterTerminalOrNoOp() {
		processor.process(lease);
		assertThat(queue.deleted()).containsExactly(lease);

		queue.deleted().clear();
		generationStore.insertJob(queuedJob());
		lookStore.throwOnInsert = true;
		assertThatThrownBy(() -> processor.process(new GenerationWorkLease(JOB_ID.value(), "receipt-2")))
			.isInstanceOf(RuntimeException.class);
		assertThat(queue.deleted()).isEmpty();
	}

	@Test
	void t17_malformedOutputCreatesNoLook() {
		generationStore.insertJob(queuedJob());
		byte[][] payloads = {
			new byte[0],
			"https://cdn.provider.test/out.jpg".getBytes(StandardCharsets.UTF_8),
			"not-an-image".getBytes(StandardCharsets.UTF_8),
			new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5, 6, 7}
		};
		for (int i = 0; i < payloads.length; i++) {
			generationStore.clearJob(JOB_ID);
			lookStore.inserted().clear();
			notificationStore.inserted().clear();
			queue.deleted().clear();
			generationStore.insertJob(queuedJob());
			provider = new RecordingVtonProvider();
			provider.outputBytes(payloads[i]);
			if (i == 3) {
				provider.outputMaxBytes(4L);
			}
			processor = processor(provider);
			processor.process(new GenerationWorkLease(JOB_ID.value(), "malformed-" + i));
			assertThat(lookStore.inserted()).isEmpty();
			assertThat(queue.deleted()).isNotEmpty();
		}
	}

	@Test
	void t19_qualityRetryDiscardsTempNoLookWhileBudgetRemains() {
		generationStore.insertJob(queuedJob());
		qualityGate.decision = QualityDecision.RETRY;

		processor.process(lease);

		assertThat(lookStore.inserted()).isEmpty();
		assertThat(objects.deletedKeys()).isNotEmpty();
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		assertThat(generationStore.listAttempts(JOB_ID).getFirst().failureCode()).isEqualTo("QUALITY_REJECTED");
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.RETRYING);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().retryNotBeforeAt()).isEqualTo(NOW);
		assertThat(queue.deleted()).containsExactly(lease);

		qualityGate.decision = QualityDecision.PASS;
		processor.process(new GenerationWorkLease(JOB_ID.value(), "quality-due"));

		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(2);
		assertThat(lookStore.inserted()).hasSize(1);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.COMPLETED);
	}
	@Test
	void nonRetryableProviderFailureNeverCreatesRetryAttempt() {
		generationStore.insertJob(queuedJob());
		provider.submitResult(new GenerationSubmission.Rejected(
			new ProviderFailure(ProviderFailureCategory.INVALID_REQUEST, true, "INPUT_ERROR")));

		processor.process(lease);

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.FAILED);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().terminalReason()).isEqualTo("INPUT_INVALID");
	}

	@Test
	void unknownProviderFailureFailsClosedEvenWhenMarkedRetryable() {
		generationStore.insertJob(queuedJob());
		provider.submitResult(new GenerationSubmission.Rejected(
			new ProviderFailure(ProviderFailureCategory.UNKNOWN_PROVIDER_FAILURE, true, "UNKNOWN")));

		processor.process(lease);

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.FAILED);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().terminalReason()).isEqualTo("PROVIDER_UNKNOWN_FAILURE");
	}

	@Test
	void retryAfterIsMinimumAndCeilingDeclinesRetry() {
		generationStore.insertJob(queuedJob());
		provider.submitResult(new GenerationSubmission.Rejected(
			new ProviderFailure(
				ProviderFailureCategory.RATE_CAPACITY,
				true,
				"RATE_LIMIT",
				NOW.plusSeconds(10))));

		processor.process(lease);

		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.RETRYING);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().retryNotBeforeAt()).isEqualTo(NOW.plusSeconds(10));
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);

		generationStore.clearJob(JOB_ID);
		generationStore.insertJob(queuedJob());
		provider = new RecordingVtonProvider();
		provider.submitResult(new GenerationSubmission.Rejected(
			new ProviderFailure(
				ProviderFailureCategory.RATE_CAPACITY,
				true,
				"RATE_LIMIT",
				NOW.plus(Duration.ofMinutes(5)).plusNanos(1))));
		processor = processor(provider);

		processor.process(new GenerationWorkLease(JOB_ID.value(), "ceiling"));

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.RETRYING);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().retryNotBeforeAt())
			.isEqualTo(NOW.plus(RetryBackoff.MAX_AUTONOMOUS_WAIT));

		generationStore.clearJob(JOB_ID);
		generationStore.insertJob(queuedJobWithDeadline(NOW.plus(Duration.ofMinutes(4))));
		provider = new RecordingVtonProvider();
		provider.submitResult(new GenerationSubmission.Rejected(
			new ProviderFailure(
				ProviderFailureCategory.RATE_CAPACITY,
				true,
				"RATE_LIMIT",
				NOW.plus(Duration.ofMinutes(5)).plusNanos(1))));
		processor = processor(provider);

		processor.process(new GenerationWorkLease(JOB_ID.value(), "deadline"));

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.FAILED);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().terminalReason())
			.isEqualTo("PROVIDER_RATE_LIMIT_EXHAUSTED");
	}
	@Test
	void multiStepPlanAdvancesOnlyAfterQualityAcceptanceAndSharesPlanIdentity() {
		RenderPlan plan = twoStepPlan();
		generationStore.renderPlan(JOB_ID, plan);
		generationStore.insertJob(jobForPlan(plan));

		processor.process(lease);

		GenerationJob progressed = generationStore.findJob(JOB_ID).orElseThrow();
		List<GenerationAttempt> firstStepAttempts = generationStore.listAttempts(JOB_ID);
		assertThat(progressed.currentStep()).isEqualTo(2);
		assertThat(progressed.totalSteps()).isEqualTo(2);
		assertThat(progressed.renderPlanVersion()).isEqualTo(1);
		assertThat(progressed.completedAt()).isNull();
		assertThat(firstStepAttempts).extracting(GenerationAttempt::renderStepId)
			.containsExactly(plan.steps().getFirst().id(), plan.steps().get(1).id());
		assertThat(firstStepAttempts.getFirst().renderStepId()).isEqualTo(plan.steps().getFirst().id());
		assertThat(firstStepAttempts.get(1).renderStepId()).isEqualTo(plan.steps().get(1).id());
		assertThat(firstStepAttempts.getFirst().completedAt()).isEqualTo(NOW);
		assertThat(firstStepAttempts.get(1).completedAt()).isNull();
		assertThat(lookStore.inserted()).isEmpty();

		processor.process(new GenerationWorkLease(JOB_ID.value(), "step-2"));

		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(2);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().completedAt()).isEqualTo(NOW);
		assertThat(lookStore.inserted()).hasSize(1);
	}
	@Test
	void multiStepCompletionRedeliveryAdvancesAndWakesNextBaseline() {
		RenderPlan plan = twoStepPlan();
		generationStore.renderPlan(JOB_ID, plan);
		generationStore.insertJob(jobForPlan(plan));
		RecordingWorkOutbox outbox = new RecordingWorkOutbox();
		processor = processor(provider, null, outbox);
		generationStore.failAfterAttemptCompleteOnce();

		assertThatThrownBy(() -> processor.process(lease))
			.isInstanceOf(RuntimeException.class)
			.hasMessage("crash after durable attempt complete");
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().currentStep()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		assertThat(generationStore.listAttempts(JOB_ID).getFirst().completedAt()).isEqualTo(NOW);
		assertThat(queue.deleted()).isEmpty();

		GenerationWorkLease redelivery = new GenerationWorkLease(JOB_ID.value(), "step-1-redelivery");
		processor.process(redelivery);

		GenerationJob recovered = generationStore.findJob(JOB_ID).orElseThrow();
		List<GenerationAttempt> attempts = generationStore.listAttempts(JOB_ID);
		assertThat(recovered.currentStep()).isEqualTo(2);
		assertThat(attempts).hasSize(2);
		assertThat(attempts).extracting(GenerationAttempt::renderStepId)
			.containsExactly(plan.steps().getFirst().id(), plan.steps().get(1).id());
		assertThat(attempts.stream()
			.filter(attempt -> plan.steps().get(1).id().equals(attempt.renderStepId()))
			.filter(attempt -> attempt.completedAt() == null))
			.hasSize(1);
		assertThat(outbox.successors()).hasSize(1);
		assertThat(outbox.successors().getFirst().generationJobId()).isEqualTo(JOB_ID.value());
		assertThat(queue.deleted()).containsExactly(redelivery);
	}


	@Test
	void t20_qualityFailDiscardsTempNoLook() {
		generationStore.insertJob(queuedJob());
		qualityGate.decision = QualityDecision.FAIL;

		processor.process(lease);

		assertThat(lookStore.inserted()).isEmpty();
		assertThat(objects.deletedKeys()).isNotEmpty();
		assertThat(notificationStore.inserted()).extracting(Notification::type).containsExactly("GENERATION_FAILED");
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.FAILED);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().terminalReason()).isEqualTo("QUALITY_REJECTED");
		assertThat(queue.deleted()).containsExactly(lease);
	}

	@Test
	void t21_redeliveryAfterTempWriteDoesNotDuplicateObjectOrLook() {
		generationStore.insertJob(queuedJob());
		AtomicInteger evaluations = new AtomicInteger();
		qualityGate.beforeEvaluate = () -> {
			if (evaluations.getAndIncrement() == 0) {
				throw new RuntimeException("crash after temp");
			}
		};

		assertThatThrownBy(() -> processor.process(lease)).isInstanceOf(RuntimeException.class);
		processor.process(new GenerationWorkLease(JOB_ID.value(), "receipt-2"));

		assertThat(lookStore.inserted()).hasSize(1);
		assertThat(objects.uniqueTempKeys()).hasSize(1);
		assertThat(objects.uniqueLookKeys()).hasSize(1);
	}

	@Test
	void t22_deletionBeforeOwnershipSkipsHeadPresignSubmit() {
		GenerationJob job = queuedJob();
		generationStore.insertJob(job);
		generationStore.markSubjectDeleted(JOB_ID, NOW);

		processor.process(lease);

		verify(resolver, never()).resolve(any());
		assertThat(provider.submitCount()).isZero();
		assertThat(objects.headCount()).isZero();
		assertThat(objects.presignCount()).isZero();
		assertThat(queue.deleted()).containsExactly(lease);
	}

	@Test
	void t23_deletionAfterSubmitDiscardsNoLookNoErasureClaim() {
		generationStore.insertJob(queuedJob());
		Logger logger = (Logger) LoggerFactory.getLogger(GenerationWorkProcessor.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		provider.beforeGetStatus(() -> generationStore.markSubjectDeleted(JOB_ID, NOW));
		try {
			processor.process(lease);
			assertThat(lookStore.inserted()).isEmpty();
			assertThat(notificationStore.inserted()).noneMatch(notification -> "LOOK_READY".equals(notification.type()));
			String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
			assertThat(logs.toLowerCase(Locale.ROOT)).doesNotContain("retroactive", "erased from provider", "revoked from provider");
			assertThat(queue.deleted()).containsExactly(lease);
		}
		finally {
			logger.detachAppender(appender);
		}
	}

	@Test
	void t24_deletionVsPassRaceCommitsNoLook() {
		generationStore.insertJob(queuedJob());
		qualityGate.decision = QualityDecision.PASS;
		qualityGate.beforeEvaluate = () -> generationStore.markSubjectDeleted(JOB_ID, NOW);

		processor.process(lease);

		assertThat(lookStore.inserted()).isEmpty();
		assertThat(notificationStore.inserted()).noneMatch(notification -> "LOOK_READY".equals(notification.type()));
		assertThat(queue.deleted()).containsExactly(lease);
	}
	@Test
	void providerAcceptedUsageSubjectDeletionRaceRetainsAttemptHistoryWithoutLook() {
		generationStore.insertJob(queuedJob());
		provider.usage(new ProviderUsage(new BigDecimal("1.50"), new BigDecimal("0.20"), "usd"));
		qualityGate.beforeEvaluate = () -> generationStore.markSubjectDeleted(JOB_ID, NOW);

		processor.process(lease);

		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		GenerationAttempt completed = generationStore.listAttempts(JOB_ID).getFirst();
		assertThat(completed.completedAt()).isEqualTo(NOW);
		assertThat(completed.technicalOutcome()).isEqualTo("SUCCESS");
		assertThat(completed.failureCode()).isEqualTo(TerminalReason.USER_DELETED.name());
		assertThat(completed.creditsUsed()).isEqualByComparingTo("1.50");
		assertThat(completed.actualBilledUnits()).isEqualByComparingTo("1.50");
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.FAILED);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().terminalReason())
			.isEqualTo(GenerationJob.SUBJECT_DELETED);
		assertThat(lookStore.inserted()).isEmpty();
		assertThat(notificationStore.inserted()).noneMatch(notification -> "LOOK_READY".equals(notification.type()));

		processor.process(new GenerationWorkLease(JOB_ID.value(), "receipt-2"));

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		GenerationAttempt retained = generationStore.listAttempts(JOB_ID).getFirst();
		assertThat(retained.completedAt()).isEqualTo(NOW);
		assertThat(retained.technicalOutcome()).isEqualTo("SUCCESS");
		assertThat(retained.failureCode()).isEqualTo(TerminalReason.USER_DELETED.name());
		assertThat(retained.creditsUsed()).isEqualByComparingTo("1.50");
		assertThat(retained.actualBilledUnits()).isEqualByComparingTo("1.50");
		assertThat(lookStore.inserted()).isEmpty();
		assertThat(notificationStore.inserted()).noneMatch(notification -> "LOOK_READY".equals(notification.type()));
		assertThat(queue.deleted()).containsExactly(lease, new GenerationWorkLease(JOB_ID.value(), "receipt-2"));
	}
	@Test
	void qualityFailAfterSubjectDeletionRetainsAcceptedUsageWithoutLook() {
		generationStore.insertJob(queuedJob());
		qualityGate.decision = QualityDecision.FAIL;
		provider.usage(new ProviderUsage(new BigDecimal("1.50"), new BigDecimal("0.20"), "usd"));
		qualityGate.beforeEvaluate = () -> generationStore.markSubjectDeleted(JOB_ID, NOW);

		processor.process(lease);

		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		GenerationAttempt completed = generationStore.listAttempts(JOB_ID).getFirst();
		assertThat(completed.completedAt()).isEqualTo(NOW);
		assertThat(completed.technicalOutcome()).isEqualTo("SUCCESS");
		assertThat(completed.failureCode()).isEqualTo("QUALITY_REJECTED");
		assertThat(completed.creditsUsed()).isEqualByComparingTo("1.50");
		assertThat(completed.actualBilledUnits()).isEqualByComparingTo("1.50");
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.FAILED);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().terminalReason())
			.isEqualTo(GenerationJob.SUBJECT_DELETED);
		assertThat(lookStore.inserted()).isEmpty();
		assertThat(notificationStore.inserted()).noneMatch(notification -> "LOOK_READY".equals(notification.type()));

		processor.process(new GenerationWorkLease(JOB_ID.value(), "receipt-2"));

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		GenerationAttempt retained = generationStore.listAttempts(JOB_ID).getFirst();
		assertThat(retained.completedAt()).isEqualTo(NOW);
		assertThat(retained.technicalOutcome()).isEqualTo("SUCCESS");
		assertThat(retained.failureCode()).isEqualTo("QUALITY_REJECTED");
		assertThat(retained.creditsUsed()).isEqualByComparingTo("1.50");
		assertThat(retained.actualBilledUnits()).isEqualByComparingTo("1.50");
		assertThat(lookStore.inserted()).isEmpty();
		assertThat(notificationStore.inserted()).noneMatch(notification -> "LOOK_READY".equals(notification.type()));
		assertThat(queue.deleted()).containsExactly(lease, new GenerationWorkLease(JOB_ID.value(), "receipt-2"));
	}
	@Test
	void qualityRetryAfterSubjectDeletionWithRemainingBudgetRetainsAcceptedUsageWithoutSuccessor() {
		RecordingWorkOutbox outbox = new RecordingWorkOutbox();
		processor = processor(provider, null, outbox);
		generationStore.insertJob(queuedJob());
		qualityGate.decision = QualityDecision.RETRY;
		provider.usage(new ProviderUsage(new BigDecimal("1.50"), new BigDecimal("0.20"), "usd"));
		qualityGate.beforeEvaluate = () -> generationStore.markSubjectDeleted(JOB_ID, NOW);

		processor.process(lease);

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		GenerationAttempt completed = generationStore.listAttempts(JOB_ID).getFirst();
		assertThat(completed.completedAt()).isEqualTo(NOW);
		assertThat(completed.technicalOutcome()).isEqualTo("SUCCESS");
		assertThat(completed.failureCode()).isEqualTo("QUALITY_REJECTED");
		assertThat(completed.creditsUsed()).isEqualByComparingTo("1.50");
		assertThat(completed.actualBilledUnits()).isEqualByComparingTo("1.50");
		assertThat(completed.estimatedCost()).isEqualByComparingTo("0.10");
		assertThat(completed.actualCost()).isEqualByComparingTo("0.20");
		assertThat(completed.currency()).isEqualTo("USD");
		assertThat(completed.costStatus()).isEqualTo(PricingSnapshot.COST_FINAL);
		assertThat(completed.pricingObservedAt()).isEqualTo(NOW);
		assertThat(completed.billingUnit()).isEqualTo("generation");
		GenerationJob job = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(job.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(job.terminalReason()).isEqualTo(GenerationJob.SUBJECT_DELETED);
		assertThat(lookStore.inserted()).isEmpty();
		assertThat(notificationStore.inserted()).noneMatch(notification -> "LOOK_READY".equals(notification.type()));
		assertThat(outbox.successors()).isEmpty();
		assertThat(queue.deleted()).containsExactly(lease);

		GenerationWorkLease duplicate = new GenerationWorkLease(JOB_ID.value(), "receipt-2");
		processor.process(duplicate);

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		GenerationAttempt retained = generationStore.listAttempts(JOB_ID).getFirst();
		assertThat(retained.completedAt()).isEqualTo(NOW);
		assertThat(retained.technicalOutcome()).isEqualTo("SUCCESS");
		assertThat(retained.failureCode()).isEqualTo("QUALITY_REJECTED");
		assertThat(retained.creditsUsed()).isEqualByComparingTo("1.50");
		assertThat(retained.actualBilledUnits()).isEqualByComparingTo("1.50");
		assertThat(lookStore.inserted()).isEmpty();
		assertThat(notificationStore.inserted()).noneMatch(notification -> "LOOK_READY".equals(notification.type()));
		assertThat(outbox.successors()).isEmpty();
		assertThat(queue.deleted()).containsExactly(lease, duplicate);
	}
	@Test
	void qualityRetryDeletionBetweenPrecheckAndRetryPersistenceDoesNotCreateSuccessor() {
		RecordingWorkOutbox outbox = new RecordingWorkOutbox();
		processor = processor(provider, null, outbox);
		generationStore.insertJob(queuedJob());
		generationStore.insertAttempt(openAttempt(1, false, null));
		qualityGate.decision = QualityDecision.RETRY;
		provider.usage(new ProviderUsage(new BigDecimal("1.50"), new BigDecimal("0.20"), "usd"));
		generationStore.beforeRetryPersistence(() -> generationStore.markSubjectDeleted(JOB_ID, NOW));

		processor.process(lease);

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(provider.getStatusCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		GenerationAttempt completed = generationStore.listAttempts(JOB_ID).getFirst();
		assertThat(completed.completedAt()).isEqualTo(NOW);
		assertThat(completed.technicalOutcome()).isEqualTo("SUCCESS");
		assertThat(completed.failureCode()).isEqualTo("QUALITY_REJECTED");
		assertThat(completed.creditsUsed()).isEqualByComparingTo("1.50");
		assertThat(completed.actualBilledUnits()).isEqualByComparingTo("1.50");
		assertThat(completed.estimatedCost()).isEqualByComparingTo("0.10");
		assertThat(completed.actualCost()).isEqualByComparingTo("0.20");
		assertThat(completed.currency()).isEqualTo("USD");
		assertThat(completed.costStatus()).isEqualTo(PricingSnapshot.COST_FINAL);
		assertThat(completed.pricingObservedAt()).isEqualTo(NOW);
		assertThat(completed.billingUnit()).isEqualTo("generation");
		GenerationJob job = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(job.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(job.terminalReason()).isEqualTo(GenerationJob.SUBJECT_DELETED);
		assertThat(lookStore.inserted()).isEmpty();
		assertThat(notificationStore.inserted()).noneMatch(notification -> "LOOK_READY".equals(notification.type()));
		assertThat(outbox.successors()).isEmpty();
		assertThat(queue.deleted()).containsExactly(lease);

		GenerationWorkLease duplicate = new GenerationWorkLease(JOB_ID.value(), "receipt-2");
		processor.process(duplicate);

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(provider.getStatusCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID)).containsExactly(completed);
		assertThat(lookStore.inserted()).isEmpty();
		assertThat(notificationStore.inserted()).noneMatch(notification -> "LOOK_READY".equals(notification.type()));
		assertThat(outbox.successors()).isEmpty();
		assertThat(queue.deleted()).containsExactly(lease, duplicate);
	}


	@Test
	void exhaustedQualityRetryAfterSubjectDeletionRetainsAcceptedUsageWithoutLook() {
		generationStore.insertJob(queuedJob());
		generationStore.insertAttempt(completedAttempt(1));
		generationStore.insertAttempt(completedAttempt(2));
		generationStore.insertAttempt(openAttempt(3, false, null));
		qualityGate.decision = QualityDecision.RETRY;
		provider.usage(new ProviderUsage(new BigDecimal("1.50"), new BigDecimal("0.20"), "usd"));
		qualityGate.beforeEvaluate = () -> generationStore.markSubjectDeleted(JOB_ID, NOW);

		processor.process(lease);

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(provider.getStatusCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(3);
		GenerationAttempt completed = generationStore.listAttempts(JOB_ID).getLast();
		assertThat(completed.attemptNumber()).isEqualTo(3);
		assertThat(completed.completedAt()).isEqualTo(NOW);
		assertThat(completed.technicalOutcome()).isEqualTo("SUCCESS");
		assertThat(completed.failureCode()).isEqualTo("QUALITY_REJECTED");
		assertThat(completed.creditsUsed()).isEqualByComparingTo("1.50");
		assertThat(completed.actualBilledUnits()).isEqualByComparingTo("1.50");
		assertThat(completed.estimatedCost()).isEqualByComparingTo("0.10");
		assertThat(completed.actualCost()).isEqualByComparingTo("0.20");
		assertThat(completed.currency()).isEqualTo("USD");
		assertThat(completed.costStatus()).isEqualTo(PricingSnapshot.COST_FINAL);
		assertThat(completed.pricingObservedAt()).isEqualTo(NOW);
		assertThat(completed.billingUnit()).isEqualTo("generation");
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.FAILED);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().terminalReason())
			.isEqualTo(GenerationJob.SUBJECT_DELETED);
		assertThat(lookStore.inserted()).isEmpty();
		assertThat(notificationStore.inserted()).noneMatch(notification -> "LOOK_READY".equals(notification.type()));
		assertThat(queue.deleted()).containsExactly(lease);

		GenerationWorkLease duplicate = new GenerationWorkLease(JOB_ID.value(), "receipt-2");
		processor.process(duplicate);

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(provider.getStatusCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(3);
		GenerationAttempt retained = generationStore.listAttempts(JOB_ID).getLast();
		assertThat(retained.completedAt()).isEqualTo(NOW);
		assertThat(retained.technicalOutcome()).isEqualTo("SUCCESS");
		assertThat(retained.failureCode()).isEqualTo("QUALITY_REJECTED");
		assertThat(retained.creditsUsed()).isEqualByComparingTo("1.50");
		assertThat(retained.actualBilledUnits()).isEqualByComparingTo("1.50");
		assertThat(lookStore.inserted()).isEmpty();
		assertThat(notificationStore.inserted()).noneMatch(notification -> "LOOK_READY".equals(notification.type()));
		assertThat(queue.deleted()).containsExactly(lease, duplicate);
	}

	@Test
	void providerFailureAfterSubjectDeletionRetainsAcceptedUsageWithoutLook() {
		generationStore.insertJob(queuedJob());
		provider.statusResult(new StatusQueryResult.Observed(
			new GenerationStatus.Failed(RecordingVtonProvider.retryableReject(), Optional.empty())));
		provider.usage(new ProviderUsage(new BigDecimal("1.50"), new BigDecimal("0.20"), "usd"));
		provider.beforeGetStatus(() -> generationStore.markSubjectDeleted(JOB_ID, NOW));

		processor.process(lease);

		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		GenerationAttempt completed = generationStore.listAttempts(JOB_ID).getFirst();
		assertThat(completed.completedAt()).isEqualTo(NOW);
		assertThat(completed.technicalOutcome()).isEqualTo("FAILED");
		assertThat(completed.failureCode()).isEqualTo("PROVIDER_TEMPORARY");
		assertThat(completed.creditsUsed()).isEqualByComparingTo("1.50");
		assertThat(completed.actualBilledUnits()).isEqualByComparingTo("1.50");
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.FAILED);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().terminalReason())
			.isEqualTo(GenerationJob.SUBJECT_DELETED);
		assertThat(lookStore.inserted()).isEmpty();
		assertThat(notificationStore.inserted()).noneMatch(notification -> "LOOK_READY".equals(notification.type()));

		processor.process(new GenerationWorkLease(JOB_ID.value(), "receipt-2"));

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(provider.getStatusCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		GenerationAttempt retained = generationStore.listAttempts(JOB_ID).getFirst();
		assertThat(retained.completedAt()).isEqualTo(NOW);
		assertThat(retained.technicalOutcome()).isEqualTo("FAILED");
		assertThat(retained.failureCode()).isEqualTo("PROVIDER_TEMPORARY");
		assertThat(retained.creditsUsed()).isEqualByComparingTo("1.50");
		assertThat(retained.actualBilledUnits()).isEqualByComparingTo("1.50");
		assertThat(lookStore.inserted()).isEmpty();
		assertThat(notificationStore.inserted()).noneMatch(notification -> "LOOK_READY".equals(notification.type()));
		assertThat(queue.deleted()).containsExactly(lease, new GenerationWorkLease(JOB_ID.value(), "receipt-2"));
	}

	@Test
	void t25_crossTenantResolverFailsBeforeStore() {
		generationStore.insertJob(queuedJob());
		when(resolver.resolve(any())).thenThrow(new UnauthorizedGenerationMediaException("generation media is not authorized"));
		int heads = objects.headCount();
		int reads = objects.readCount();
		int presigns = objects.presignCount();

		processor.process(lease);

		assertThat(provider.submitCount()).isZero();
		assertThat(objects.headCount()).isEqualTo(heads);
		assertThat(objects.readCount()).isEqualTo(reads);
		assertThat(objects.presignCount()).isEqualTo(presigns);
		assertThat(objects.putCount()).isZero();
	}

	@Test
	void t28_logsOmitSensitiveClasses() throws IOException {
		generationStore.insertJob(queuedJob());
		Logger logger = (Logger) LoggerFactory.getLogger(GenerationWorkProcessor.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			processor.process(lease);
			String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
			assertThat(SENSITIVE.matcher(logs).find()).isFalse();
			assertThat(logs).doesNotContain(new String(RecordingVtonProvider.DEFAULT_OUTPUT, StandardCharsets.ISO_8859_1));
			String source = Files.readString(processorSource());
			assertThat(source).doesNotContain("Fashn", "Redis", "redis", "Bearer ", "base64");
		}
		finally {
			logger.detachAppender(appender);
		}
	}

	@Test
	void t29_lookReadyOnlyAfterLookInsertLookIdOnly() {
		generationStore.insertJob(queuedJob());

		processor.process(lease);

		assertThat(notificationStore.lookReadyAfterLook()).isTrue();
		Notification ready = notificationStore.inserted().getFirst();
		assertThat(ready.type()).isEqualTo("LOOK_READY");
		assertThat(ready.payload()).containsOnlyKeys("lookId");
		assertThat(ready.payload().get("lookId")).isEqualTo(lookStore.inserted().getFirst().id().value().toString());
	}

	@Test
	void t30_generationFailedPayloadTypeOnly() {
		generationStore.insertJob(queuedJob());
		qualityGate.decision = QualityDecision.FAIL;

		processor.process(lease);

		Notification failed = notificationStore.inserted().getFirst();
		assertThat(failed.type()).isEqualTo("GENERATION_FAILED");
        assertThat(failed.payload()).containsOnlyKeys("generationId");
        assertThat(failed.payload().get("generationId")).isEqualTo(JOB_ID.value().toString());
	}

	@Test
	void t31_qualityRejectedAttemptStillStored() {
		generationStore.insertJob(queuedJob());
		qualityGate.decision = QualityDecision.FAIL;

		processor.process(lease);

		GenerationAttempt attempt = generationStore.listAttempts(JOB_ID).getFirst();
		assertThat(attempt.failureCode()).isEqualTo("QUALITY_REJECTED");
		assertThat(attempt.technicalOutcome()).isEqualTo("SUCCESS");
		assertThat(attempt.completedAt()).isNotNull();
	}

	@Test
	void qualityRejectedProviderSuccessKeepsAttemptEconomics() {
		generationStore.insertJob(queuedJob());
		qualityGate.decision = QualityDecision.FAIL;
		provider.usage(new ProviderUsage(new BigDecimal("1.50"), new BigDecimal("0.20"), "usd"));

		processor.process(lease);

		GenerationAttempt attempt = generationStore.listAttempts(JOB_ID).getFirst();
		assertThat(attempt.failureCode()).isEqualTo("QUALITY_REJECTED");
		assertThat(attempt.technicalOutcome()).isEqualTo("SUCCESS");
		assertThat(attempt.costStatus()).isEqualTo(PricingSnapshot.COST_FINAL);
		assertThat(attempt.pricingCatalogVersion()).isEqualTo("mvp-2026-08");
		assertThat(attempt.estimatedCost()).isEqualByComparingTo("0.10");
		assertThat(attempt.actualCost()).isEqualByComparingTo("0.20");
	}

	@Test
	void t32_transportRetriesDoNotExtraSubmitAndBudgetCapsPaidAttempts() {
		generationStore.insertJob(queuedJob());
		provider.submitException(new RuntimeException("connection reset"));

		processor.process(lease);

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);

		provider.submitException(null);
		provider.submitResult(new GenerationSubmission.Rejected(RecordingVtonProvider.retryableReject()));
		generationStore = new InMemoryGenerationStore();
		generationStore.insertJob(queuedJob());
		processor = processor(provider);
		for (int i = 0; i < 5; i++) {
			processor.process(new GenerationWorkLease(JOB_ID.value(), "budget-" + i));
		}
		assertThat(generationStore.listAttempts(JOB_ID).size())
			.isLessThanOrEqualTo(GenerationRetryBudget.DEFAULT_MAX_PAID_ATTEMPTS);
		assertThat(provider.submitCount()).isLessThanOrEqualTo(1 + GenerationRetryBudget.DEFAULT_MAX_PAID_ATTEMPTS);
	}

	@Test
	void crashAfterAttemptCompleteBeforeJobCompleteResumesNotification() {
		generationStore.insertJob(queuedJob());
		generationStore.failMarkJobCompletedOnce();

		assertThatThrownBy(() -> processor.process(lease)).isInstanceOf(RuntimeException.class);
		assertThat(generationStore.listAttempts(JOB_ID).getFirst().completedAt()).isEqualTo(NOW);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().completedAt()).isNull();
		assertThat(queue.deleted()).isEmpty();

		processor.process(new GenerationWorkLease(JOB_ID.value(), "receipt-2"));

		assertThat(generationStore.findJob(JOB_ID).orElseThrow().completedAt()).isEqualTo(NOW);
		assertThat(lookStore.inserted()).hasSize(1);
		assertThat(notificationStore.inserted()).extracting(Notification::type).contains("LOOK_READY");
		assertThat(queue.deleted()).isNotEmpty();
	}

	@Test
	void ownedWithoutProviderJobIdTerminalizesUnknownOutcome() {
		Instant recoveryDeadlineAt = NOW.plus(Duration.ofMinutes(5));
		RecordingWorkOutbox outbox = new RecordingWorkOutbox();
		processor = processor(provider, outbox, CLOCK);
		generationStore.insertJob(queuedJob());
		generationStore.insertAttempt(openIndeterminateRecoveryAttempt(recoveryDeadlineAt, false));

		processor.process(lease);

		// Q23/R23: an owned attempt without a provider execution id enters
		// bounded indeterminate recovery instead of terminalizing.
		assertThat(provider.submitCount()).isZero();
		GenerationAttempt attempt = generationStore.listAttempts(JOB_ID).getFirst();
		assertThat(attempt.submissionIndeterminate()).isTrue();
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().completedAt()).isNull();
		assertThat(notificationStore.inserted()).isEmpty();
		assertThat(queue.deleted()).containsExactly(lease);
		assertThat(outbox.successors()).extracting(successor -> successor.notBefore())
			.containsExactly(NOW.plusSeconds(3));

		GenerationWorkLease second = new GenerationWorkLease(JOB_ID.value(), "receipt-2");
		processor.process(second);

		// Inside the recovery window the blocked unsafe replay keeps waiting:
		// no resubmission, no terminal write.
		assertThat(provider.submitCount()).isZero();
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().completedAt()).isNull();
		assertThat(notificationStore.inserted()).isEmpty();
		assertThat(queue.deleted()).containsExactly(lease, second);

		// Past the recovery deadline the wait terminates indeterminate.
		processor = processor(provider, outbox, Clock.fixed(recoveryDeadlineAt, ZoneOffset.UTC));
		GenerationWorkLease third = new GenerationWorkLease(JOB_ID.value(), "receipt-3");
		processor.process(third);

		assertThat(provider.submitCount()).isZero();
		assertThat(generationStore.listAttempts(JOB_ID).getFirst().failureCode())
			.isEqualTo(TerminalReason.SUBMISSION_OUTCOME_INDETERMINATE.name());
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().completedAt()).isEqualTo(recoveryDeadlineAt);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.FAILED);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().terminalReason())
			.isEqualTo(TerminalReason.SUBMISSION_OUTCOME_INDETERMINATE.name());
		assertThat(notificationStore.inserted()).extracting(Notification::type).contains("GENERATION_FAILED");
        assertThat(notificationStore.inserted().getFirst().payload()).containsOnlyKeys("generationId");
		assertThat(queue.deleted()).containsExactly(lease, second, third);
	}

	@Test
	void blockedUnsafeReplayWaitsForRecoveryDeadlineThenTerminatesIndeterminate() {
		Instant recoveryDeadlineAt = NOW.plus(Duration.ofMinutes(5));
		RecordingWorkOutbox outbox = new RecordingWorkOutbox();
		processor = processor(provider, outbox, CLOCK);
		generationStore.insertJob(queuedJob());
		generationStore.insertAttempt(openIndeterminateRecoveryAttempt(recoveryDeadlineAt, true));

		processor.process(lease);

		assertThat(provider.submitCount()).isZero();
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().completedAt()).isNull();
		assertThat(notificationStore.inserted()).isEmpty();
		assertThat(queue.deleted()).containsExactly(lease);

		processor = processor(provider, outbox, Clock.fixed(recoveryDeadlineAt, ZoneOffset.UTC));
		GenerationWorkLease second = new GenerationWorkLease(JOB_ID.value(), "receipt-2");
		processor.process(second);

		assertThat(provider.submitCount()).isZero();
		assertThat(generationStore.listAttempts(JOB_ID).getFirst().failureCode())
			.isEqualTo(TerminalReason.SUBMISSION_OUTCOME_INDETERMINATE.name());
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.FAILED);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().terminalReason())
			.isEqualTo(TerminalReason.SUBMISSION_OUTCOME_INDETERMINATE.name());
		assertThat(notificationStore.inserted()).extracting(Notification::type).contains("GENERATION_FAILED");
        assertThat(notificationStore.inserted().getFirst().payload()).containsOnlyKeys("generationId");
		assertThat(queue.deleted()).containsExactly(lease, second);
	}

	@Test
	void concurrentLiveSubmitAndOwnedRedeliveryDoesNotEmitReadyAndFailed() throws Exception {
		generationStore.insertJob(queuedJob());
		CountDownLatch submitEntered = new CountDownLatch(1);
		CountDownLatch releaseSubmit = new CountDownLatch(1);
		provider.beforeSubmit(() -> {
			submitEntered.countDown();
			try {
				if (!releaseSubmit.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("submit was not released");
				}
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(ex);
			}
		});
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			var live = pool.submit(() -> processor.process(lease));
			assertThat(submitEntered.await(5, TimeUnit.SECONDS)).isTrue();
			processor.process(new GenerationWorkLease(JOB_ID.value(), "receipt-observer"));
			releaseSubmit.countDown();
			live.get(5, TimeUnit.SECONDS);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(lookStore.inserted()).hasSizeLessThanOrEqualTo(1);
		List<String> types = notificationStore.inserted().stream().map(Notification::type).toList();
		assertThat(types.contains("LOOK_READY") && types.contains("GENERATION_FAILED")).isFalse();
		assertThat(lookStore.inserted()).hasSize(1);
		assertThat(types).contains("LOOK_READY").doesNotContain("GENERATION_FAILED");
	}

	@Test
	void qualityRetryCrashBeforeDueClaimDoesNotTerminalFail() {
		generationStore.insertJob(queuedJob());
		qualityGate.decision = QualityDecision.RETRY;
		generationStore.failAfterRetryCompleteOnce();

		assertThatThrownBy(() -> processor.process(lease)).isInstanceOf(RuntimeException.class);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		assertThat(generationStore.listAttempts(JOB_ID).getFirst().failureCode()).isEqualTo("QUALITY_REJECTED");
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.RETRYING);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().retryNotBeforeAt()).isEqualTo(NOW);
		assertThat(notificationStore.inserted()).isEmpty();

		qualityGate.decision = QualityDecision.PASS;
		processor.process(new GenerationWorkLease(JOB_ID.value(), "receipt-2"));

		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(2);
		assertThat(generationStore.listAttempts(JOB_ID).get(1).completedAt()).isEqualTo(NOW);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().completedAt()).isEqualTo(NOW);
		assertThat(notificationStore.inserted()).extracting(Notification::type).contains("LOOK_READY");
		assertThat(queue.deleted()).containsExactly(new GenerationWorkLease(JOB_ID.value(), "receipt-2"));
	}

	@Test
	void failNotificationEmittedOnlyAfterJobCompleted() {
		generationStore.insertJob(queuedJob());
		qualityGate.decision = QualityDecision.FAIL;
		generationStore.afterCompleteTerminalIfActive = () -> {
			assertThat(generationStore.findJob(JOB_ID).orElseThrow().completedAt()).isEqualTo(NOW);
			assertThat(notificationStore.inserted()).isEmpty();
		};

		processor.process(lease);

		assertThat(generationStore.completeTerminalIfActiveCalls.get()).isEqualTo(1);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().completedAt()).isEqualTo(NOW);
		assertThat(notificationStore.inserted()).extracting(Notification::type).containsExactly("GENERATION_FAILED");
	}

	@Test
	void deleteBetweenFindAndAcquireDoesNotSubmit() {
		generationStore.insertJob(queuedJob());
		generationStore.afterFindJob = () -> generationStore.markSubjectDeleted(JOB_ID, NOW);

		processor.process(lease);

		assertThat(provider.submitCount()).isZero();
		assertThat(lookStore.inserted()).isEmpty();
		assertThat(queue.deleted()).containsExactly(lease);
	}

	@Test
	void deleteAfterAcquireDoesNotSubmit() {
		generationStore.insertJob(queuedJob());
		generationStore.afterAcquire = () -> generationStore.markSubjectDeleted(JOB_ID, NOW);

		processor.process(lease);

		assertThat(provider.submitCount()).isZero();
		assertThat(lookStore.inserted()).isEmpty();
		assertThat(queue.deleted()).containsExactly(lease);
	}

	@Test
	void deleteAfterPromoteBeforeLookInsertDiscardsLookObject() {
		generationStore.insertJob(queuedJob());
		objects.afterClaim = () -> generationStore.markSubjectDeleted(JOB_ID, NOW);

		processor.process(lease);

		assertThat(lookStore.inserted()).isEmpty();
		assertThat(notificationStore.inserted()).noneMatch(notification -> "LOOK_READY".equals(notification.type()));
		assertThat(objects.deletedKeys().stream().anyMatch(key -> key.contains("/looks/"))).isTrue();
		assertThat(queue.deleted()).containsExactly(lease);
	}

	@Test
	void providerUsageIsPersistedOnTerminalAttempt() {
		generationStore.insertJob(queuedJob());
		provider.usage(new ProviderUsage(new BigDecimal("1.50"), new BigDecimal("0.20"), "usd"));

		processor.process(lease);

		GenerationAttempt attempt = generationStore.listAttempts(JOB_ID).getFirst();
		assertThat(attempt.creditsUsed()).isEqualByComparingTo("1.50");
		assertThat(attempt.actualBilledUnits()).isEqualByComparingTo("1.50");
		assertThat(attempt.estimatedCost()).isEqualByComparingTo("0.10");
		assertThat(attempt.actualCost()).isEqualByComparingTo("0.20");
		assertThat(attempt.currency()).isEqualTo("USD");
		assertThat(attempt.costStatus()).isEqualTo(PricingSnapshot.COST_FINAL);
		assertThat(attempt.pricingObservedAt()).isEqualTo(NOW);
		assertThat(attempt.billingUnit()).isEqualTo("generation");
	}

	@Test
	void missingProviderUsagePreservesExistingAttemptEconomics() {
		generationStore.insertJob(queuedJob());
		GenerationAttempt attempt = new GenerationAttempt(
			new GenerationAttemptId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1")),
			JOB_ID,
			"recording",
			"default",
			"provider-job-1",
			1,
			NOW,
			null,
			null,
			new BigDecimal("0.13"),
			new BigDecimal("0.20"),
			"USD",
			null,
			null,
			SubmissionKey.forAttempt(new GenerationAttemptId(
				UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1"))),
			NOW,
			false,
			null,
			null,
			null,
			0,
			null,
			null,
			null,
			QualityGate.BASELINE_POLICY_VERSION,
			"catalog-v1",
			new BigDecimal("1"),
			new BigDecimal("0.20"),
			new BigDecimal("0.20"),
			new BigDecimal("0.13"),
			PricingSnapshot.COST_ESTIMATED,
			new BigDecimal("3.25"),
			NOW.minusSeconds(5),
			"images");
		generationStore.insertAttempt(attempt);

		processor.process(lease);

		GenerationAttempt completed = generationStore.findAttempt(attempt.id()).orElseThrow();
		assertThat(completed.actualBilledUnits()).isEqualByComparingTo("3.25");
		assertThat(completed.actualCost()).isEqualByComparingTo("0.13");
		assertThat(completed.costStatus()).isEqualTo(PricingSnapshot.COST_ESTIMATED);
		assertThat(completed.pricingObservedAt()).isEqualTo(NOW.minusSeconds(5));
		assertThat(completed.billingUnit()).isEqualTo("images");
	}

	@Test
	void qualityPassDiscardsTempAfterPromote() {
		generationStore.insertJob(queuedJob());

		processor.process(lease);

		assertThat(lookStore.inserted()).hasSize(1);
		assertThat(objects.deletedKeys().stream().anyMatch(key -> key.startsWith("temp/"))).isTrue();
		assertThat(objects.uniqueLookKeys()).hasSize(1);
	}

	@Test
	void providerSwapUsesAttemptProviderFromRegistry() {
		RecordingVtonProvider selected = new RecordingVtonProvider();
		RecordingVtonProvider other = new RecordingVtonProvider(new ProviderKey("other"));
		ProviderRegistry registry = new DefaultProviderRegistry(
			List.of(selected, other),
			selected.providerKey(),
			Map.of());
		processor = processor(selected, registry);
		generationStore.insertJob(queuedJob());
		generationStore.insertAttempt(openAttempt(1, false, null, null, "other"));

		processor.process(lease);

		assertThat(other.submitCount()).isEqualTo(1);
		assertThat(selected.submitCount()).isZero();
		assertThat(lookStore.inserted()).hasSize(1);
		assertThat(lookStore.inserted().getFirst().provider()).isEqualTo("other");
	}

	@Test
	void cancelledJobDoesNotEmitFailureNotification() {
		generationStore.insertJob(queuedJob());
		assertThat(generationStore.cancelIfActive(JOB_ID, NOW)).isTrue();

		processor.process(lease);

		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.FAILED);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().terminalReason())
			.isEqualTo(GenerationJob.CANCELLED_BY_USER);
		assertThat(notificationStore.inserted()).isEmpty();
		assertThat(provider.submitCount()).isZero();
	}

	@Test
	void callbackCandidateConfirmedViaStatusBecomesProviderJobId() {
		InMemoryCallbackInbox inbox = new InMemoryCallbackInbox();
		processor = processor(provider, null, null, inbox);
		generationStore.insertJob(queuedJob());
		GenerationAttempt attempt = openAttempt(1, false, null, NOW, "recording");
		generationStore.insertAttempt(attempt);
		inbox.accept(
			"token-hash-confirm",
			"callback-job-9",
			"SUCCEEDED",
			NOW);
		inbox.bindAttempt("token-hash-confirm", attempt.id());
		provider.statusResult(new StatusQueryResult.Observed(new GenerationStatus.Queued()));

		processor.process(lease);

		assertThat(generationStore.findAttempt(attempt.id()).orElseThrow().providerJobId())
			.isEqualTo("callback-job-9");
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.SUBMITTED);
		assertThat(provider.submitCount()).isZero();
		assertThat(provider.getStatusCount()).isGreaterThanOrEqualTo(1);
	}

	@Test
	void unconfirmableCallbackCandidateIsNotPersisted() {
		InMemoryCallbackInbox inbox = new InMemoryCallbackInbox();
		processor = processor(provider, null, null, inbox);
		generationStore.insertJob(queuedJob());
		GenerationAttempt attempt = openAttempt(1, false, null, NOW, "recording");
		generationStore.insertAttempt(attempt);
		inbox.accept("token-hash-unknown", "unconfirmable-job", null, NOW);
		inbox.bindAttempt("token-hash-unknown", attempt.id());
		provider.statusResult(new StatusQueryResult.Unavailable(RecordingVtonProvider.indeterminateFailure()));

		processor.process(lease);

		assertThat(generationStore.findAttempt(attempt.id()).orElseThrow().providerJobId()).isNull();
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isNotEqualTo(GenerationJob.SUBMITTED);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().completedAt()).isNull();
	}


	@Test
	void costCircuitTripTerminalizesWithCostBudgetBlocked() {
		generationStore.insertJob(queuedJob());
		costCircuitBreaker.trip();

		processor.process(lease);

		assertThat(provider.submitCount()).isZero();
		assertThat(costCircuitBreaker.requests()).hasSize(1);
		ProviderExecutionBudgetRequest request = costCircuitBreaker.requests().getFirst();
		assertThat(request.userId()).isEqualTo(USER_ID);
		assertThat(request.providerKey()).isEqualTo("recording");
		assertThat(request.modelRouteAuditIdentity()).isNotBlank();
		assertThat(request.projectedCostCeiling()).isPositive();
		assertThat(request.currency()).isEqualTo("USD");
		assertThat(request.now()).isEqualTo(NOW);
		GenerationJob blocked = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(blocked.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(blocked.terminalReason()).isEqualTo("COST_BUDGET_BLOCKED");
	}

	@Test
	void healthGateClosedTerminalizesWithProviderUnavailable() {
		generationStore.insertJob(queuedJob());
		providerHealthGate.close();

		processor.process(lease);

		assertThat(provider.submitCount()).isZero();
		GenerationJob blocked = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(blocked.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(blocked.terminalReason()).isEqualTo("PROVIDER_UNAVAILABLE");
	}

	@Test
	void missingRetryPolicyRefusesFirstAttemptWithoutProviderCall() {
		generationStore.insertJob(queuedJobWithoutPolicy());

		processor.process(lease);

		assertThat(provider.submitCount()).isZero();
		assertThat(generationStore.listAttempts(JOB_ID))
			.allSatisfy(recorded -> {
				assertThat(recorded.technicalOutcome()).isEqualTo("FAILED");
				assertThat(recorded.failureCode()).isEqualTo("RETRY_POLICY_MISSING");
				assertThat(recorded.transmissionCount()).isZero();
			});
		GenerationJob failed = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(failed.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(failed.terminalReason()).isEqualTo("RETRY_POLICY_MISSING");
		assertThat(queue.deleted()).containsExactly(lease);
	}

	@Test
	void malformedPersistedRetryPolicyFailsClosedWithoutProviderCall() {
		generationStore.insertJob(queuedJob());
		generationStore.overridePersistedRetryPolicy("malformed-retry-policy");

		processor.process(lease);

		assertThat(provider.submitCount()).isZero();
		assertThat(generationStore.listAttempts(JOB_ID))
			.allSatisfy(recorded -> {
				assertThat(recorded.technicalOutcome()).isEqualTo("FAILED");
				assertThat(recorded.failureCode()).isEqualTo("RETRY_POLICY_INVALID");
				assertThat(recorded.transmissionCount()).isZero();
			});
		GenerationJob failed = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(failed.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(failed.terminalReason()).isEqualTo("RETRY_POLICY_INVALID");
	}

	@Test
	void missingRetryPolicyRefusesDueRetryWithoutBudgetExhaustion() {
		generationStore.insertJob(retryingJobWithoutPolicy());
		generationStore.insertAttempt(completedAttempt(1));

		processor.process(lease);

		assertThat(provider.submitCount()).isZero();
		assertThat(generationStore.listAttempts(JOB_ID))
			.allSatisfy(recorded -> {
				assertThat(recorded.technicalOutcome()).isEqualTo("FAILED");
				assertThat(recorded.failureCode()).isEqualTo("RETRY_POLICY_MISSING");
			});
		GenerationJob failed = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(failed.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(failed.terminalReason()).isEqualTo("RETRY_POLICY_MISSING");
		assertThat(failed.retryNotBeforeAt()).isNull();
		assertThat(queue.deleted()).containsExactly(lease);
	}

	@Test
	void malformedPersistedRetryPolicyRefusesDueRetryWithoutBudgetExhaustion() {
		generationStore.insertJob(retryingJob());
		generationStore.insertAttempt(completedAttempt(1));
		generationStore.overridePersistedRetryPolicy("malformed-retry-policy");

		processor.process(lease);

		assertThat(provider.submitCount()).isZero();
		assertThat(generationStore.listAttempts(JOB_ID))
			.allSatisfy(recorded -> {
				assertThat(recorded.technicalOutcome()).isEqualTo("FAILED");
				assertThat(recorded.failureCode()).isEqualTo("RETRY_POLICY_INVALID");
			});
		GenerationJob failed = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(failed.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(failed.terminalReason()).isEqualTo("RETRY_POLICY_INVALID");
		assertThat(failed.retryNotBeforeAt()).isNull();
		assertThat(queue.deleted()).containsExactly(lease);
	}
	@Test
	void unsupportedRetryPolicyVersionFailsClosedWithoutProviderCall() {
		generationStore.insertJob(queuedJob());
		generationStore.overridePersistedRetryPolicy(
			RetryPolicySnapshot.forStepCountV2(1).serialize().replace("version=2", "version=3"));

		processor.process(lease);

		assertThat(provider.submitCount()).isZero();
		assertThat(generationStore.listAttempts(JOB_ID))
			.allSatisfy(recorded -> {
				assertThat(recorded.technicalOutcome()).isEqualTo("FAILED");
				assertThat(recorded.failureCode()).isEqualTo("RETRY_POLICY_UNSUPPORTED_VERSION");
				assertThat(recorded.transmissionCount()).isZero();
			});
		GenerationJob failed = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(failed.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(failed.terminalReason()).isEqualTo("RETRY_POLICY_UNSUPPORTED_VERSION");
	}
	@Test
	void submittedAttemptStillReconcilesWhenPolicyRowBecomesUnreadable() {
		generationStore.insertJob(queuedJob());
		generationStore.insertAttempt(openAttempt(1, false, "provider-job-1"));
		generationStore.overridePersistedRetryPolicy("malformed-retry-policy");

		processor.process(lease);

		assertThat(provider.submitCount()).isZero();
		assertThat(provider.getStatusCount()).isEqualTo(1);
		GenerationJob reconciled = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(reconciled.status()).isEqualTo(GenerationJob.COMPLETED);
		assertThat(reconciled.terminalReason()).isNull();
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
	}

	@Test
	void cancellationOutranksMissingRetryPolicyGate() {
		generationStore.insertJob(queuedJobWithoutPolicy());
		assertThat(generationStore.cancelIfActive(JOB_ID, NOW)).isTrue();

		processor.process(lease);

		assertThat(provider.submitCount()).isZero();
		GenerationJob cancelled = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(cancelled.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(cancelled.terminalReason()).isEqualTo(GenerationJob.CANCELLED_BY_USER);
		assertThat(notificationStore.inserted()).isEmpty();
	}

	@Test
	void evaluatorUnavailableOnceRetriesSameCandidateThenPasses() {
		generationStore.insertJob(queuedJobV2());
		qualityGate.infrastructureFailures = 1;

		processor.process(lease);

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.COMPLETED);
		GenerationAttempt attempt = generationStore.listAttempts(JOB_ID).getFirst();
		assertThat(generationStore.findQualityResult(attempt.id())).isPresent();
		assertThat(generationStore.findQualityResult(attempt.id()).orElseThrow().decision())
			.isEqualTo(QualityDecision.PASS);
	}

	@Test
	void evaluatorUnavailableTwiceTerminalizesWithoutQualityRowOrRegeneration() {
		generationStore.insertJob(queuedJobV2());
		qualityGate.infrastructureFailures = 2;

		processor.process(lease);

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		GenerationJob exhausted = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(exhausted.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(exhausted.terminalReason()).isEqualTo("QUALITY_EVALUATOR_UNAVAILABLE");
		GenerationAttempt attempt = generationStore.listAttempts(JOB_ID).getFirst();
		assertThat(generationStore.findQualityResult(attempt.id())).isEmpty();
	}

	@Test
	void qualityCheckCasTransitionsJobBeforeEvaluation() {
		generationStore.insertJob(queuedJob());
		String[] observedStatus = new String[1];
		qualityGate.beforeEvaluate = () -> observedStatus[0] = generationStore.findJob(JOB_ID).orElseThrow().status();

		processor.process(lease);

		assertThat(observedStatus[0]).isEqualTo(GenerationJob.QUALITY_CHECK);
		assertThat(lookStore.inserted()).hasSize(1);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().status()).isEqualTo(GenerationJob.COMPLETED);
	}

	@Test
	void qualityCheckCasLossDiscardsTempWithoutEvaluationOrLook() {
		generationStore.insertJob(queuedJob());
		generationStore.beforeQualityCheckCas(() -> generationStore.cancelIfActive(JOB_ID, NOW));

		processor.process(lease);

		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(qualityGate.lastRequest()).isNull();
		assertThat(objects.deletedKeys()).anyMatch(key -> key.startsWith("temp/"));
		assertThat(lookStore.inserted()).isEmpty();
		assertThat(notificationStore.inserted()).isEmpty();
		GenerationJob cancelled = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(cancelled.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(cancelled.terminalReason()).isEqualTo(GenerationJob.CANCELLED_BY_USER);
		assertThat(queue.deleted()).containsExactly(lease);
	}

	@Test
	void redeliveryReplayAppliesStoredQualityResultWithoutEvaluating() {
		generationStore.insertJob(queuedJob());
		GenerationAttempt attempt = openAttempt(1, false, "provider-job-1");
		generationStore.insertAttempt(attempt);
		generationStore.saveQualityResult(new QualityResult(
			new QualityResultId(UUID.randomUUID()),
			attempt.id(),
			QualityDecision.PASS,
			null,
			null,
			null,
			null,
			null,
			NOW,
			QualityGate.BASELINE_POLICY_VERSION,
			null));
		AtomicInteger evaluations = new AtomicInteger();
		qualityGate.beforeEvaluate = evaluations::incrementAndGet;

		processor.process(lease);

		assertThat(evaluations).hasValue(0);
		assertThat(qualityGate.lastRequest()).isNull();
		assertThat(lookStore.inserted()).hasSize(1);
		assertThat(generationStore.findJob(JOB_ID).orElseThrow().completedAt()).isEqualTo(NOW);
	}

	@Test
	void qualityInputMediaMissingFailsTerminallyWithoutRegeneration() {
		generationStore.insertJob(queuedJob());
		when(resolver.resolve(any()))
			.thenReturn(resolvedMedia())
			.thenThrow(new UnauthorizedGenerationMediaException("generation media is not authorized"));
		qualityGate.decision = QualityDecision.FAIL;
		qualityGate.reasonCode = "INPUT_MEDIA_MISSING";

		processor.process(lease);

		assertThat(qualityGate.lastRequest()).isNotNull();
		assertThat(qualityGate.lastRequest().subjectBytes()).isNull();
		assertThat(qualityGate.lastRequest().garmentBytes()).isNull();
		assertThat(lookStore.inserted()).isEmpty();
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		GenerationJob failed = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(failed.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(failed.terminalReason()).isEqualTo("QUALITY_REJECTED");
		assertThat(failed.retryNotBeforeAt()).isNull();
		assertThat(notificationStore.inserted()).hasSize(1);
		Notification notification = notificationStore.inserted().getFirst();
		assertThat(notification.type()).isEqualTo("GENERATION_FAILED");
		assertThat(notification.payload()).doesNotContainKey("lookId");
	}

	@Test
	void storageUnavailableDuringCaptureTerminalizesWithStorageUnavailable() {
		generationStore.insertJob(queuedJob());
		objects.failPutBoundedWithStorageUnavailable = 2;

		processor.process(lease);

		assertThat(provider.submitCount()).isEqualTo(1);
		GenerationJob exhausted = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(exhausted.status()).isEqualTo(GenerationJob.FAILED);
		assertThat(exhausted.terminalReason()).isEqualTo("STORAGE_UNAVAILABLE");
	}

	@Test
	void earlyRedeliveryDefersToPersistedRetryNotBeforeAt() {
		generationStore.insertJob(retryingJobNotBefore(NOW.plus(Duration.ofMinutes(10))));
		generationStore.insertAttempt(completedAttempt(1));

		processor.process(lease);

		assertThat(provider.submitCount()).isZero();
		GenerationJob deferred = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(deferred.status()).isEqualTo(GenerationJob.RETRYING);
		assertThat(deferred.retryNotBeforeAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
		assertThat(deferred.terminalReason()).isNull();
	}

	@Test
	void casConflictOnRetryScheduleConsumesNoBudgetAndYields() {
		generationStore.insertJob(queuedJob());
		provider.submitResult(new GenerationSubmission.Rejected(RecordingVtonProvider.retryableReject()));
		generationStore.beforeRetryPersistence(() -> generationStore.cancelIfActive(JOB_ID, NOW));

		processor.process(lease);

		assertThat(provider.submitCount()).isEqualTo(1);
		// The concurrent cancel wins the statusVersion CAS, so the processor
		// yields: no successor attempt, no durable retry, no budget consumed.
		GenerationJob job = generationStore.findJob(JOB_ID).orElseThrow();
		assertThat(generationStore.listAttempts(JOB_ID)).hasSize(1);
		assertThat(job.retryNotBeforeAt()).isNull();
		assertThat(job.terminalReason()).isEqualTo(GenerationJob.CANCELLED_BY_USER);
	}

	private void runProcess(CountDownLatch start, CountDownLatch done, GenerationWorkLease work) {
		try {
			start.await(5, TimeUnit.SECONDS);
			processor.process(work);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		finally {
			done.countDown();
		}
	}

	private GenerationWorkProcessor processor(com.aifashionvault.generation.vton.VtonProvider vtonProvider) {
		return processor(vtonProvider, (ProviderRegistry) null, null);
	}

	private GenerationWorkProcessor processor(
		com.aifashionvault.generation.vton.VtonProvider vtonProvider,
		ProviderRegistry registry
	) {
		return processor(vtonProvider, registry, null);
	}

	private GenerationWorkProcessor processor(
		com.aifashionvault.generation.vton.VtonProvider vtonProvider,
		ProviderRegistry registry,
		WorkOutboxStore workOutboxStore
	) {
		return processor(vtonProvider, registry, workOutboxStore, null);
	}

	private GenerationWorkProcessor processor(
		com.aifashionvault.generation.vton.VtonProvider vtonProvider,
		ProviderRegistry registry,
		WorkOutboxStore workOutboxStore,
		CallbackInboxStore callbackInboxStore
	) {
		lookStore.attach(generationStore);
		if (workOutboxStore instanceof RecordingWorkOutbox recording) {
			recording.attach(generationStore);
		}
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
			registry,
			workOutboxStore,
			callbackInboxStore,
			null,
			null,
			null,
			() -> 0.0,
			costCircuitBreaker,
			providerHealthGate);
	}

	private GenerationWorkProcessor processor(
		com.aifashionvault.generation.vton.VtonProvider vtonProvider,
		WorkOutboxStore workOutboxStore,
		Clock clock
	) {
		lookStore.attach(generationStore);
		if (workOutboxStore instanceof RecordingWorkOutbox recording) {
			recording.attach(generationStore);
		}
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
			clock,
			null,
			workOutboxStore,
			null,
			null,
			null,
			null,
			() -> 0.0,
			costCircuitBreaker,
			providerHealthGate);
	}

	private GenerationWorkProcessor processor(
		com.aifashionvault.generation.vton.VtonProvider vtonProvider,
		ProviderRegistry registry,
		WorkOutboxStore workOutboxStore,
		CallbackInboxStore callbackInboxStore,
		GenerationMetrics metrics
	) {
		lookStore.attach(generationStore);
		if (workOutboxStore instanceof RecordingWorkOutbox recording) {
			recording.attach(generationStore);
		}
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
			registry,
			workOutboxStore,
			callbackInboxStore,
			metrics,
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
	/**
	 * A queued job whose persisted retry-policy row is absent: it must fail
	 * closed before the first provider execution (R9/Q5).
	 */
	private static GenerationJob queuedJobWithoutPolicy() {
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
			NOW.plus(GenerationJob.ORCHESTRATION_DEADLINE),
			null,
			null);
	}

	/**
	 * A queued job carrying a version-2 retry policy so the evaluator-retry
	 * ceiling (one same-candidate re-evaluation) is enabled (Q11/R14).
	 */
	private static GenerationJob queuedJobV2() {
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
			NOW.plus(GenerationJob.ORCHESTRATION_DEADLINE),
			null,
			RetryPolicySnapshot.forStepCountV2(1));
	}
	

	private static GenerationJob retryingJob() {
		return new GenerationJob(
			JOB_ID,
			USER_ID,
			null,
			GenerationJob.RETRYING,
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
			NOW.plus(GenerationJob.ORCHESTRATION_DEADLINE),
			NOW,
			RetryPolicySnapshot.forStepCount(1));
	}

	/**
	 * A retrying job whose persisted retry-policy row is absent: a due retry
	 * claim must fail closed with the policy reason, not budget exhaustion
	 * (R2/R9).
	 */
	private static GenerationJob retryingJobWithoutPolicy() {
		return new GenerationJob(
			JOB_ID,
			USER_ID,
			null,
			GenerationJob.RETRYING,
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
			NOW.plus(GenerationJob.ORCHESTRATION_DEADLINE),
			NOW,
			null);
	}

	private static GenerationJob retryingJobNotBefore(Instant retryNotBeforeAt) {
		return new GenerationJob(
			JOB_ID,
			USER_ID,
			null,
			GenerationJob.RETRYING,
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
			NOW.plus(GenerationJob.ORCHESTRATION_DEADLINE),
			retryNotBeforeAt,
			RetryPolicySnapshot.forStepCount(1));
	}

	private static RenderPlan twoStepPlan() {
		return new RenderPlan(
			1,
			List.of(
				new RenderStep(
					UUID.fromString("77777777-7777-7777-7777-777777777771"),
					1,
					null,
					List.of()),
				new RenderStep(
					UUID.fromString("77777777-7777-7777-7777-777777777772"),
					2,
					null,
					List.of())));
	}
	private static GenerationJob retryingJobForPlan(RenderPlan plan) {
		return new GenerationJob(
			JOB_ID,
			USER_ID,
			null,
			GenerationJob.RETRYING,
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
			1,
			NOW,
			null,
			1,
			plan.steps().size(),
			plan.version(),
			NOW.plus(GenerationJob.ORCHESTRATION_DEADLINE),
			NOW,
			RetryPolicySnapshot.forStepCount(plan.steps().size()));
	}

	private static RenderPlan threeStepPlan() {
		return new RenderPlan(
			1,
			List.of(
				new RenderStep(
					UUID.fromString("77777777-7777-7777-7777-777777777771"),
					1,
					null,
					List.of()),
				new RenderStep(
					UUID.fromString("77777777-7777-7777-7777-777777777772"),
					2,
					null,
					List.of()),
				new RenderStep(
					UUID.fromString("77777777-7777-7777-7777-777777777773"),
					3,
					null,
					List.of())));
	}


	private static GenerationJob jobForPlan(RenderPlan plan) {
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
			plan.steps().size(),
			plan.version(),
			NOW.plus(GenerationJob.ORCHESTRATION_DEADLINE),
			null,
			RetryPolicySnapshot.forStepCount(plan.steps().size()));
	}

	private static GenerationJob completedJob() {
		return new GenerationJob(
			JOB_ID,
			USER_ID,
			null,
			GenerationJob.COMPLETED,
			"try-on",
			null,
			NOW,
			NOW,
			AVATAR_IMAGE_ID,
			List.of(PRODUCT_ID),
			null,
			null);
	}

	private static GenerationJob overdueJob() {
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
			NOW.minusSeconds(1));
	}

	private GenerationAttempt completedAttempt(int number) {
		GenerationAttempt open = openAttempt(number, false, "provider-job-" + number, NOW, "recording");
		return new GenerationAttempt(
			open.id(),
			open.generationJobId(),
			open.provider(),
			open.model(),
			open.providerJobId(),
			open.attemptNumber(),
			open.startedAt(),
			NOW,
			0,
			null,
			null,
			null,
			"QUALITY_REJECTED",
			"SUCCESS",
			open.submissionKey(),
			open.submitOwnedAt(),
			open.submissionIndeterminate(),
			open.tempStorageKey(),
			open.providerOutputRef());
	}
	private GenerationAttempt completedStepAttempt(
		int number,
		UUID renderStepId,
		String technicalOutcome,
		String failureCode
	) {
		GenerationAttempt open = openAttempt(number, false, "provider-job-" + number, NOW, "recording");
		return new GenerationAttempt(
			open.id(),
			open.generationJobId(),
			open.provider(),
			open.model(),
			open.providerJobId(),
			open.attemptNumber(),
			open.startedAt(),
			NOW,
			0,
			null,
			null,
			null,
			failureCode,
			technicalOutcome,
			open.submissionKey(),
			open.submitOwnedAt(),
			open.submissionIndeterminate(),
			open.tempStorageKey(),
			open.providerOutputRef(),
			renderStepId,
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
			null);
	}


	private GenerationAttempt expiredAcceptedAttempt(int number) {
		GenerationAttempt open = openAttempt(number, false, "provider-job-" + number);
		return new GenerationAttempt(
			open.id(),
			open.generationJobId(),
			open.provider(),
			open.model(),
			open.providerJobId(),
			open.attemptNumber(),
			NOW.minusSeconds(30),
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			open.submissionKey(),
			open.submitOwnedAt(),
			open.submissionIndeterminate(),
			null,
			null,
			null,
			0,
			NOW.minusSeconds(20),
			NOW.minusSeconds(1),
			NOW.plusSeconds(60),
			QualityGate.BASELINE_POLICY_VERSION,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			null);
	}

	private GenerationAttempt openAttempt(int number, boolean indeterminate, String providerJobId) {
		return openAttempt(number, indeterminate, providerJobId, null, "recording");
	}

	private GenerationAttempt openAttempt(
		int number,
		boolean indeterminate,
		String providerJobId,
		Instant ownedAt,
		String providerName
	) {
		GenerationAttemptId id = new GenerationAttemptId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa" + number));
		return new GenerationAttempt(
			id,
			JOB_ID,
			providerName,
			"default",
			providerJobId,
			number,
			NOW,
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			SubmissionKey.forAttempt(id),
			ownedAt,
			indeterminate,
			null,
			null);
	}

	private GenerationAttempt openIndeterminateRecoveryAttempt(
		Instant recoveryDeadlineAt,
		boolean indeterminate
	) {
		GenerationAttempt open = openAttempt(1, indeterminate, null, NOW, "recording");
		return new GenerationAttempt(
			open.id(),
			open.generationJobId(),
			open.provider(),
			open.model(),
			open.providerJobId(),
			open.attemptNumber(),
			open.startedAt(),
			null,
			null,
			null,
			null,
			null,
			null,
			null,
			open.submissionKey(),
			open.submitOwnedAt(),
			open.submissionIndeterminate(),
			null,
			null,
			null,
			0,
			null,
			recoveryDeadlineAt.plus(Duration.ofMinutes(10)),
			recoveryDeadlineAt,
			QualityGate.BASELINE_POLICY_VERSION,
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
			0);
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

	private static Path processorSource() {
		return Path.of("src/main/java/com/aifashionvault/platform/generation/GenerationWorkProcessor.java");
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
		private final List<String> order = new CopyOnWriteArrayList<>();

		@Override
		public void insert(Notification notification) {
			inserted.add(notification);
			order.add(notification.type());
		}

		@Override
		public Optional<com.aifashionvault.notification.Notification> findById(NotificationId id) {
			return inserted.stream().filter(notification -> notification.id().equals(id)).findFirst();
		}

		private List<Notification> inserted() {
			return inserted;
		}

		private boolean lookReadyAfterLook() {
			return order.contains("LOOK_READY");
		}
	}

	private static final class InMemoryQueue implements GenerationWorkQueue {
		private final List<GenerationWorkLease> deleted = new CopyOnWriteArrayList<>();
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
			if (throwOnExtend) {
				throw new RuntimeException("visibility extension failed");
			}
		}

		@Override
		public void delete(GenerationWorkLease lease) {
			deleted.add(lease);
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

	private static final class RecordingWorkOutbox implements WorkOutboxStore {
		private final List<Successor> successors = new CopyOnWriteArrayList<>();
		private volatile InMemoryGenerationStore generationStore;
		private void attach(InMemoryGenerationStore store) {
			this.generationStore = store;
		}

		@Override
		public Optional<Claim> claimNext() {
			return Optional.empty();
		}

		@Override
		public void markSent(UUID outboxId) {
		}

		@Override
		public void markPublishFailed(UUID outboxId, String errorCategory) {
		}

		@Override
		public void insertSuccessor(UUID generationJobId, Instant notBefore) {
			successors.add(new Successor(generationJobId, notBefore));
		}
		@Override
		public boolean insertSuccessorIfActive(UUID generationJobId, Instant notBefore) {
			if (generationJobId == null) {
				throw new IllegalArgumentException("generationJobId is required");
			}
			if (notBefore == null) {
				throw new IllegalArgumentException("notBefore is required");
			}
			InMemoryGenerationStore store = generationStore;
			if (store == null) {
				insertSuccessor(generationJobId, notBefore);
				return true;
			}
			synchronized (store) {
				GenerationJob job = store.peekJob(new GenerationJobId(generationJobId)).orElse(null);
				if (!store.retryJobActive(job)) {
					return false;
				}
				successors.add(new Successor(generationJobId, notBefore));
				return true;
			}
		}

		private List<Successor> successors() {
			return successors;
		}

		private record Successor(UUID generationJobId, Instant notBefore) {
		}
	}

	private static final class InMemoryCallbackInbox implements CallbackInboxStore {
		private final Map<String, CallbackInbox> byHash = new ConcurrentHashMap<>();

		@Override
		public void register(com.aifashionvault.shared.GenerationAttemptId attemptId, String tokenHash, Instant receivedAt) {
			byHash.putIfAbsent(
				tokenHash,
				new CallbackInbox(UUID.randomUUID(), tokenHash, attemptId, null, null, receivedAt));
		}

		@Override
		public Optional<CallbackInbox> findByDedupeHash(String dedupeHash) {
			return Optional.ofNullable(byHash.get(dedupeHash));
		}

		@Override
		public List<CallbackInbox> findByAttempt(com.aifashionvault.shared.GenerationAttemptId attemptId) {
			return byHash.values().stream()
				.filter(inbox -> attemptId.equals(inbox.generationAttemptId()))
				.toList();
		}

		@Override
		public boolean accept(String tokenHash, String candidateProviderJobId, String safeTerminalHint, Instant now) {
			CallbackInbox existing = byHash.get(tokenHash);
			if (existing == null) {
				byHash.put(
					tokenHash,
					new CallbackInbox(UUID.randomUUID(), tokenHash, null, candidateProviderJobId, safeTerminalHint, now));
				return true;
			}
			byHash.put(
				tokenHash,
				new CallbackInbox(
					existing.id(),
					existing.dedupeHash(),
					existing.generationAttemptId(),
					existing.candidateProviderJobId() == null ? candidateProviderJobId : existing.candidateProviderJobId(),
					existing.safeTerminalHint() == null ? safeTerminalHint : existing.safeTerminalHint(),
					existing.receivedAt()));
			return true;
		}

		private void bindAttempt(String tokenHash, com.aifashionvault.shared.GenerationAttemptId attemptId) {
			CallbackInbox existing = byHash.get(tokenHash);
			if (existing == null) {
				return;
			}
			byHash.put(
				tokenHash,
				new CallbackInbox(
					existing.id(),
					existing.dedupeHash(),
					attemptId,
					existing.candidateProviderJobId(),
					existing.safeTerminalHint(),
					existing.receivedAt()));
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
