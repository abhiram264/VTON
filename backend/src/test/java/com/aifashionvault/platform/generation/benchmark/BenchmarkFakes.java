package com.aifashionvault.platform.generation.benchmark;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Set;

import com.aifashionvault.generation.QualityEvaluationOutcome;
import com.aifashionvault.generation.QualityEvaluationRequest;
import com.aifashionvault.generation.QualityGate;
import com.aifashionvault.generation.QualityResult;
import com.aifashionvault.generation.vton.GenerationStatus;
import com.aifashionvault.generation.vton.GenerationSubmission;
import com.aifashionvault.generation.vton.InputSizeLimit;
import com.aifashionvault.generation.vton.InputTransferMethod;
import com.aifashionvault.generation.vton.ItemCategory;
import com.aifashionvault.generation.vton.ModeCapabilities;
import com.aifashionvault.generation.vton.OutputDeliveryMethod;
import com.aifashionvault.generation.vton.ProviderCapabilities;
import com.aifashionvault.generation.vton.ProviderFailure;
import com.aifashionvault.generation.vton.ProviderFailureCategory;
import com.aifashionvault.generation.vton.ProviderJobId;
import com.aifashionvault.generation.vton.ProviderKey;
import com.aifashionvault.generation.vton.ProviderMediaSource;
import com.aifashionvault.generation.vton.ProviderOutputRef;
import com.aifashionvault.generation.vton.ProviderUsage;
import com.aifashionvault.generation.vton.StatusQueryResult;
import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.generation.vton.SubmissionReplaySafety;
import com.aifashionvault.generation.vton.VtonMode;
import com.aifashionvault.generation.vton.VtonProvider;
import com.aifashionvault.generation.vton.VtonRequest;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.QualityDecision;
import com.aifashionvault.shared.QualityResultId;

/**
 * Deterministic offline benchmark fakes: a scripted provider port and a
 * scripted quality gate, plus a stepped elapsed clock. No network, no
 * credentials, no host services.
 */
final class BenchmarkFakes {

	static final byte[] PNG_OUTPUT = new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
	static final byte[] MALFORMED_OUTPUT = new byte[] {'n', 'o', 't', '-', 'a', 'n', '-', 'i', 'm', 'a', 'g', 'e'};
	static final ProviderUsage USAGE = new ProviderUsage(new java.math.BigDecimal("1"), new java.math.BigDecimal(
		"0.05"), "USD");

	private BenchmarkFakes() {
	}

	/** Stepped monotonic clock; each read advances the clock by one step. */
	static final class SteppedClock implements OfflineEvaluationBenchmarkRunner.ElapsedClock {

		private long now;
		private final long stepMs;

		SteppedClock(long startMs, long stepMs) {
			this.now = startMs;
			this.stepMs = stepMs;
		}

		@Override
		public long elapsedMs() {
			long current = now;
			now += stepMs;
			return current;
		}

		/** Advances the clock without a read (simulating quality-evaluation time). */
		void advance() {
			now += stepMs;
		}
	}

	/** Scripted quality gate: maps case-ordered scripted decisions; can be made unavailable. */
	static final class ScriptedQualityGate implements QualityGate {

		private final Queue<QualityDecision> decisions = new ArrayDeque<>();
		private final List<byte[]> evaluatedCandidates = new ArrayList<>();
		private boolean unavailable;
		private boolean throwing;

		ScriptedQualityGate(QualityDecision... scriptedDecisions) {
			for (QualityDecision decision : scriptedDecisions) {
				decisions.add(decision);
			}
		}

		void unavailable(boolean value) {
			this.unavailable = value;
		}

		void throwing(boolean value) {
			this.throwing = value;
		}

		List<byte[]> evaluatedCandidates() {
			return List.copyOf(evaluatedCandidates);
		}

		@Override
		public QualityResult evaluate(GenerationAttemptId attemptId, byte[] applicationOwnedImage) {
			throw new UnsupportedOperationException("benchmark uses the request overload");
		}

		@Override
		public QualityEvaluationOutcome evaluate(QualityEvaluationRequest request) {
			evaluatedCandidates.add(request.candidateBytes());
			if (throwing) {
				throw new IllegalStateException("provider secret body must not leak: deadbeef");
			}
			if (unavailable || decisions.isEmpty()) {
				return new QualityEvaluationOutcome.Unavailable("EVALUATOR_UNAVAILABLE");
			}
			QualityDecision decision = decisions.poll();
			return new QualityEvaluationOutcome.Completed(new QualityResult(
				new QualityResultId(request.attemptId().value()),
				request.attemptId(),
				decision,
				new java.math.BigDecimal("0.91"),
				new java.math.BigDecimal("0.88"),
				new java.math.BigDecimal("0.93"),
				Boolean.TRUE,
				decision == QualityDecision.FAIL,
				java.time.Instant.EPOCH,
				BASELINE_POLICY_VERSION));
		}
	}

	/** Scripted provider port exercising accept, reject, status failure, malformed output, and media read failure. */
	static final class ScriptedVtonProvider implements VtonProvider {

		enum Behavior {
			ACCEPT,
			REJECT,
			STATUS_FAILED,
			STATUS_UNAVAILABLE,
			THROW_ON_SUBMIT
		}

		private final ProviderKey providerKey = new ProviderKey("fake-benchmark");
		private final List<SubmissionKey> submittedKeys = new ArrayList<>();
		private final Map<SubmissionKey, VtonRequest> submittedRequests = new LinkedHashMap<>();
		private final Queue<Behavior> behaviors = new ArrayDeque<>();
		private byte[] outputBytes = PNG_OUTPUT;
		private boolean throwOnOpenOutput;
		private boolean emptyOutput;
		private boolean usageEnabled;

		ScriptedVtonProvider(Behavior... scriptedBehaviors) {
			for (Behavior behavior : scriptedBehaviors) {
				behaviors.add(behavior);
			}
		}

		void outputBytes(byte[] bytes) {
			this.outputBytes = bytes;
		}

		void throwOnOpenOutput(boolean value) {
			this.throwOnOpenOutput = value;
		}

		void emptyOutput(boolean value) {
			this.emptyOutput = value;
		}

		void usageEnabled(boolean value) {
			this.usageEnabled = value;
		}

		int submitCount() {
			return submittedKeys.size();
		}

		List<SubmissionKey> submittedKeys() {
			return List.copyOf(submittedKeys);
		}

		VtonRequest lastRequest() {
			return submittedRequests.get(submittedKeys.get(submittedKeys.size() - 1));
		}

		private Behavior nextBehavior() {
			return behaviors.isEmpty() ? Behavior.ACCEPT : behaviors.poll();
		}

		@Override
		public ProviderKey providerKey() {
			return providerKey;
		}

		@Override
		public ProviderCapabilities capabilities() {
			return new ProviderCapabilities(
				Map.of(new VtonMode("try-on"), new ModeCapabilities(
					Set.of(new ItemCategory("tops"), new ItemCategory("bottoms")),
					1,
					false,
					InputSizeLimit.unknown())),
				Set.of(InputTransferMethod.BOUNDED_INLINE_DATA),
				Set.of(OutputDeliveryMethod.BOUNDED_INLINE_DATA),
				SubmissionReplaySafety.UNSAFE);
		}

		@Override
		public GenerationSubmission submit(VtonRequest request, SubmissionKey submissionKey) {
			submittedKeys.add(submissionKey);
			submittedRequests.put(submissionKey, request);
			return switch (nextBehavior()) {
				case ACCEPT -> new GenerationSubmission.Accepted(new ProviderJobId("bench-job-1"), usage());
				case REJECT -> new GenerationSubmission.Rejected(new ProviderFailure(
					ProviderFailureCategory.CONTENT_POLICY_REJECTION, false, "PROVIDER_REJECTED"));
				case THROW_ON_SUBMIT -> throw new IllegalStateException("provider stack trace must not leak");
				default -> new GenerationSubmission.Accepted(new ProviderJobId("bench-job-1"), usage());
			};
		}

		@Override
		public StatusQueryResult getStatus(ProviderJobId providerJobId) {
			return switch (nextBehavior()) {
				case STATUS_FAILED -> new StatusQueryResult.Observed(new GenerationStatus.Failed(new ProviderFailure(
					ProviderFailureCategory.REMOTE_EXECUTION_NOT_FOUND, false, "PROVIDER_JOB_FAILED"), usage()));
				case STATUS_UNAVAILABLE -> new StatusQueryResult.Unavailable(new ProviderFailure(
					ProviderFailureCategory.TEMPORARY_UNAVAILABLE, true, "PROVIDER_STATUS_UNAVAILABLE"));
				default -> new StatusQueryResult.Observed(new GenerationStatus.Succeeded(
					List.of(new ProviderOutputRef("bench-output-1")), usage()));
			};
		}

		@Override
		public ProviderMediaSource openOutput(ProviderOutputRef outputRef) {
			if (throwOnOpenOutput) {
				throw new IllegalStateException("openOutput secret must not leak");
			}
			byte[] payload = emptyOutput ? new byte[0] : outputBytes;
			return ProviderMediaSource.of(
				new ByteArrayInputStream(payload),
				OptionalLong.of(payload.length),
				1024L * 1024L,
				"image/png");
		}

		private Optional<ProviderUsage> usage() {
			return usageEnabled ? Optional.of(USAGE) : Optional.empty();
		}
	}
}
