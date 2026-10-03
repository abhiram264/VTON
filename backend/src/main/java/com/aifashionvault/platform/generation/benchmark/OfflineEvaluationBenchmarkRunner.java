package com.aifashionvault.platform.generation.benchmark;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

import com.aifashionvault.generation.QualityEvaluationOutcome;
import com.aifashionvault.generation.QualityEvaluationRequest;
import com.aifashionvault.generation.QualityGate;
import com.aifashionvault.generation.QualityResult;
import com.aifashionvault.generation.benchmark.BenchmarkCase;
import com.aifashionvault.generation.benchmark.BenchmarkCaseResult;
import com.aifashionvault.generation.benchmark.BenchmarkReport;
import com.aifashionvault.generation.benchmark.BenchmarkRunSpec;
import com.aifashionvault.generation.benchmark.EvaluationBenchmarkRunner;
import com.aifashionvault.generation.benchmark.Outcome;
import com.aifashionvault.generation.vton.GenerationStatus;
import com.aifashionvault.generation.vton.GenerationSubmission;
import com.aifashionvault.generation.vton.ItemCategory;
import com.aifashionvault.generation.vton.PrivateMediaRef;
import com.aifashionvault.generation.vton.ProviderMediaSource;
import com.aifashionvault.generation.vton.ProviderOutputRef;
import com.aifashionvault.generation.vton.ProviderUsage;
import com.aifashionvault.generation.vton.StatusQueryResult;
import com.aifashionvault.generation.vton.SubmissionKey;
import com.aifashionvault.generation.vton.VtonMode;
import com.aifashionvault.generation.vton.VtonProductAsset;
import com.aifashionvault.generation.vton.VtonProvider;
import com.aifashionvault.generation.vton.VtonRequest;
import com.aifashionvault.shared.GenerationAttemptId;
import com.aifashionvault.shared.StorageKey;

/**
 * Offline benchmark runner: executes ordered selected benchmark cases through
 * the injected provider-neutral {@link VtonProvider} port, validates fixtures
 * before any provider invocation, evaluates usable outputs with the existing
 * product-owned {@link QualityGate}, and returns per-case results in
 * deterministic case-id order. Sequential and deterministic by default; no
 * tenant identity, no production storage, no network.
 *
 * <p>Privacy: provider exception bodies and media never enter results,
 * exceptions, or messages; failures carry application-owned outcome codes
 * only (ADR-015).
 */
public final class OfflineEvaluationBenchmarkRunner implements EvaluationBenchmarkRunner {

	/** Monotonic elapsed-time clock in milliseconds; inject a deterministic fake in CI. */
	public interface ElapsedClock {

		long elapsedMs();
	}

	private final BenchmarkFixtureManifest manifest;
	private final BenchmarkCaseCatalog catalog;
	private final VtonProvider provider;
	private final QualityGate qualityGate;
	private final ElapsedClock clock;
	private final BenchmarkResultStore resultStore;

	public OfflineEvaluationBenchmarkRunner(BenchmarkFixtureManifest manifest, VtonProvider provider,
			QualityGate qualityGate, ElapsedClock clock, BenchmarkResultStore resultStore) {
		this.manifest = manifest;
		this.catalog = BenchmarkCaseCatalog.fromManifest(manifest);
		this.provider = provider;
		this.qualityGate = qualityGate;
		this.clock = clock;
		this.resultStore = resultStore;
	}

	/** Dry-run gate: canonical manifest validation without any provider call. */
	public void dryRun() {
		manifest.requireCanonical();
	}

	@Override
	public BenchmarkReport run(BenchmarkRunSpec spec) {
		if (!manifest.benchmarkVersion().equals(spec.benchmarkVersion())) {
			throw new IllegalArgumentException(
				"benchmark version mismatch: run spec " + spec.benchmarkVersion() + " vs manifest "
					+ manifest.benchmarkVersion());
		}
		String fingerprint = configurationFingerprint(spec);
		List<BenchmarkCase> orderedCases = catalog.casesForIds(spec.caseIds());
		for (BenchmarkCase benchmarkCase : orderedCases) {
			if (resultStore.isCompleted(spec.runId(), benchmarkCase.caseId(), fingerprint)) {
				continue;
			}
			resultStore.record(spec.runId(), benchmarkCase.caseId(), fingerprint,
				executeCase(spec, benchmarkCase, fingerprint));
		}
		List<BenchmarkCaseResult> ordered = resultStore.completedResultsOrdered(spec.caseIds(), spec.runId())
			.stream()
			.sorted(java.util.Comparator.comparing(BenchmarkCaseResult::caseId))
			.toList();
		return new BenchmarkReport(ordered, BenchmarkMatrixGenerator.rows(ordered));
	}

	static String configurationFingerprint(BenchmarkRunSpec spec) {
		String material = String.join("|", List.of(
				spec.benchmarkVersion(),
				spec.providerKey(),
				spec.modelOrMode(),
				spec.promptVersion(),
				spec.routingVersion()).stream()
			.map(value -> value.length() + ":" + value)
			.toList());
		return BenchmarkFixtureManifest.fingerprint(material);
	}

	private BenchmarkCaseResult executeCase(BenchmarkRunSpec spec, BenchmarkCase benchmarkCase, String fingerprint) {
		long startedAt = clock.elapsedMs();
		String fixtureViolation = manifest.validateForCase(
			benchmarkCase.avatarFixtureId(), benchmarkCase.itemFixtureId(), benchmarkCase.category());
		if (fixtureViolation != null) {
			// FIXTURE_INVALID before any provider invocation; no cost, no quality decision.
			return caseResult(spec, benchmarkCase, startedAt, Outcome.FIXTURE_INVALID, null, null, null, null);
		}
		SubmissionKey submissionKey = deterministicSubmissionKey(spec.runId(), benchmarkCase.caseId(), fingerprint);
		GenerationSubmission submission;
		try {
			submission = provider.submit(vtonRequest(spec, benchmarkCase), submissionKey);
		}
		catch (RuntimeException ex) {
			return caseResult(spec, benchmarkCase, startedAt, Outcome.PROVIDER_FAILURE, null, null, null, null);
		}
		// Rejected/Indeterminate carry no usage in the neutral types: cost unknown, stays null.
		if (!(submission instanceof GenerationSubmission.Accepted accepted)) {
			return caseResult(spec, benchmarkCase, startedAt, Outcome.PROVIDER_FAILURE, null, null, null, null);
		}
		StatusQueryResult status;
		try {
			status = provider.getStatus(accepted.providerJobId());
		}
		catch (RuntimeException ex) {
			// Acceptance usage remains attributable when the status probe blows up.
			return caseResult(spec, benchmarkCase, startedAt, Outcome.PROVIDER_FAILURE, null, cost(accepted.usage()),
					currency(accepted.usage()), null);
		}
		if (!(status instanceof StatusQueryResult.Observed observed)) {
			return caseResult(spec, benchmarkCase, startedAt, Outcome.PROVIDER_FAILURE, null, cost(accepted.usage()),
					currency(accepted.usage()), null);
		}
		if (observed.status() instanceof GenerationStatus.Failed failed) {
			// Billed remote failure: determinable spend survives the failure.
			return caseResult(spec, benchmarkCase, startedAt, Outcome.PROVIDER_FAILURE, null, cost(failed.usage()),
					currency(failed.usage()), null);
		}
		if (!(observed.status() instanceof GenerationStatus.Succeeded succeeded)) {
			// Non-terminal observed status: acceptance spend stays attributable.
			return caseResult(spec, benchmarkCase, startedAt, Outcome.PROVIDER_FAILURE, null, cost(accepted.usage()),
					currency(accepted.usage()), null);
		}
		BigDecimal cost = cost(succeeded.usage());
		String currency = currency(succeeded.usage());
		byte[] outputBytes = captureOutput(succeeded.outputs().get(0));
		if (outputBytes == null || outputBytes.length == 0 || !looksLikeImage(outputBytes)) {
			return caseResult(spec, benchmarkCase, startedAt, Outcome.OUTPUT_INVALID, null, cost, currency, null);
		}
		GenerationAttemptId attemptId = deterministicAttemptId(spec.runId(), benchmarkCase.caseId(), fingerprint);
		QualityEvaluationOutcome evaluation;
		try {
			evaluation = qualityGate.evaluate(new QualityEvaluationRequest(attemptId, outputBytes, null, null, null,
				QualityGate.BASELINE_POLICY_VERSION, null));
		}
		catch (RuntimeException ex) {
			return caseResult(spec, benchmarkCase, startedAt, Outcome.EVALUATOR_UNAVAILABLE, null, cost, currency,
				null);
		}
		if (!(evaluation instanceof QualityEvaluationOutcome.Completed completed)) {
			return caseResult(spec, benchmarkCase, startedAt, Outcome.EVALUATOR_UNAVAILABLE, null, cost, currency,
				null);
		}
		QualityResult quality = completed.result();
		Outcome outcome = switch (quality.decision()) {
			case PASS -> Outcome.SUCCESS;
			case RETRY -> Outcome.QUALITY_RETRY;
			case FAIL -> Outcome.QUALITY_FAIL;
		};
		return caseResult(spec, benchmarkCase, startedAt, outcome, quality, cost, currency,
			quality.artifactDetected());
	}

	private BenchmarkCaseResult caseResult(
		BenchmarkRunSpec spec,
		BenchmarkCase benchmarkCase,
		long startedAt,
		Outcome outcome,
		QualityResult quality,
		BigDecimal cost,
		String currency,
		Boolean artifactDetected) {
		return new BenchmarkCaseResult(
			spec.runId(),
			benchmarkCase.caseId(),
			spec.providerKey(),
			spec.modelOrMode(),
			benchmarkCase.category(),
			Math.max(0L, clock.elapsedMs() - startedAt),
			outcome,
			cost,
			currency,
			null,
			quality == null ? null : quality.identityPreservation(),
			quality == null ? null : quality.garmentFidelity(),
			quality == null ? null : quality.colorFidelity(),
			Boolean.TRUE.equals(artifactDetected),
			quality == null ? null : quality.decision());
	}

	private VtonRequest vtonRequest(BenchmarkRunSpec spec, BenchmarkCase benchmarkCase) {
		return new VtonRequest(
			new PrivateMediaRef(new StorageKey(benchmarkCase.avatarFixtureId()), OptionalLong.empty()),
			List.of(new VtonProductAsset(
				new PrivateMediaRef(new StorageKey(benchmarkCase.itemFixtureId()), OptionalLong.empty()),
				new ItemCategory(benchmarkCase.category().replace(' ', '-')))),
			Optional.empty(),
			vtonMode(spec.modelOrMode()));
	}

	private static VtonMode vtonMode(String modelOrMode) {
		try {
			return new VtonMode(modelOrMode);
		}
		catch (IllegalArgumentException ex) {
			return new VtonMode("try-on");
		}
	}

	private byte[] captureOutput(ProviderOutputRef outputRef) {
		try (ProviderMediaSource source = provider.openOutput(outputRef);
			InputStream stream = source.stream()) {
			return stream.readAllBytes();
		}
		catch (IOException | RuntimeException ex) {
			return null;
		}
	}

	private static boolean looksLikeImage(byte[] bytes) {
		if (bytes.length < 4) {
			return false;
		}
		boolean jpeg = (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8;
		boolean png = (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G';
		return jpeg || png;
	}

	private static BigDecimal cost(Optional<ProviderUsage> usage) {
		return usage.map(ProviderUsage::estimatedCost).orElse(null);
	}

	private static String currency(Optional<ProviderUsage> usage) {
		return usage.map(ProviderUsage::currency).orElse(null);
	}

	private static SubmissionKey deterministicSubmissionKey(String runId, String caseId, String fingerprint) {
		return new SubmissionKey(UUID.nameUUIDFromBytes(("benchmark:" + runId + ":" + caseId + ":" + fingerprint)
			.getBytes(StandardCharsets.UTF_8)));
	}

	private static GenerationAttemptId deterministicAttemptId(String runId, String caseId, String fingerprint) {
		return new GenerationAttemptId(UUID.nameUUIDFromBytes(("benchmark-attempt:" + runId + ":" + caseId + ":"
			+ fingerprint).getBytes(StandardCharsets.UTF_8)));
	}
}
