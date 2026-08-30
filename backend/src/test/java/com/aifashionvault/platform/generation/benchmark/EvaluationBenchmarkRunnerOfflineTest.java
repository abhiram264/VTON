package com.aifashionvault.platform.generation.benchmark;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.benchmark.BenchmarkCase;
import com.aifashionvault.generation.benchmark.BenchmarkCaseResult;
import com.aifashionvault.generation.benchmark.BenchmarkMatrixRow;
import com.aifashionvault.generation.benchmark.BenchmarkReport;
import com.aifashionvault.generation.benchmark.BenchmarkRunSpec;
import com.aifashionvault.generation.benchmark.Outcome;
import com.aifashionvault.shared.QualityDecision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Offline runner behavior: ordered selected case execution, fixture
 * validation before provider invocation, quality outcome separation, injected
 * monotonic latency, replay/resume idempotency, deterministic ordering, and
 * privacy-safe failures.
 *
 * <p>Happy-path cases come from the canonical main manifest; per-case
 * fixture-invalid behavior comes from the variant test manifest.
 */
class EvaluationBenchmarkRunnerOfflineTest {

	private static final Path MAIN_MANIFEST = Path.of("src/main/resources/benchmark/manifest.json");
	private static final Path TEST_MANIFEST = Path.of("src/test/resources/benchmark/manifest.json");

	private static BenchmarkFixtureManifest mainManifest() {
		return BenchmarkFixtureManifest.open(MAIN_MANIFEST);
	}

	private static BenchmarkCaseCatalog mainCatalog() {
		return BenchmarkCaseCatalog.fromManifest(mainManifest());
	}

	private static BenchmarkCase mainCase(int index) {
		return mainCatalog().allCasesOrdered().get(index);
	}

	private static BenchmarkRunSpec mainSpec(String runId, String... caseIds) {
		return new BenchmarkRunSpec(runId, "2026.01.0", "fake-benchmark", "try-on", "prompt-v1", "routing-v1",
			List.of(caseIds));
	}

	private static BenchmarkCase variantCaseFor(String avatarFixtureId, String itemFixtureId) {
		BenchmarkCaseCatalog catalog = BenchmarkCaseCatalog.fromManifest(BenchmarkFixtureManifest.open(TEST_MANIFEST));
		return catalog.allCasesOrdered()
			.stream()
			.filter(candidate -> candidate.avatarFixtureId().equals(avatarFixtureId)
				&& candidate.itemFixtureId().equals(itemFixtureId))
			.findFirst()
			.orElseThrow();
	}

	@Test
	void qualityPassMapsSuccessWithFidelityAndArtifactStatus() {
		BenchmarkFakes.ScriptedVtonProvider provider = new BenchmarkFakes.ScriptedVtonProvider();
		provider.usageEnabled(true);
		BenchmarkFakes.ScriptedQualityGate gate = new BenchmarkFakes.ScriptedQualityGate(QualityDecision.PASS);
		OfflineEvaluationBenchmarkRunner runner = new OfflineEvaluationBenchmarkRunner(mainManifest(), provider, gate,
				new BenchmarkFakes.SteppedClock(0, 250), new BenchmarkResultStore());
		BenchmarkCase valid = mainCase(0);
		BenchmarkReport report = runner.run(mainSpec("run-1", valid.caseId()));
		assertThat(report.caseResults()).hasSize(1);
		BenchmarkCaseResult result = report.caseResults().get(0);
		assertThat(result.outcome()).isEqualTo(Outcome.SUCCESS);
		assertThat(result.qualityGateResult()).isEqualTo(QualityDecision.PASS);
		assertThat(result.identityFidelity()).isEqualByComparingTo("0.91");
		assertThat(result.garmentFidelity()).isEqualByComparingTo("0.88");
		assertThat(result.colorFidelity()).isEqualByComparingTo("0.93");
		assertThat(result.artifacts()).isFalse();
		assertThat(result.cost()).isEqualByComparingTo("0.05");
		assertThat(result.currency()).isEqualTo("USD");
		// clock is read at case start and at result capture: one 250ms step
		assertThat(result.latencyMs()).isEqualTo(250);
		assertThat(gate.evaluatedCandidates()).hasSize(1);
	}

	@Test
	void qualityRetryAndFailAreDistinctFromSuccessAndKeepCostAttributable() {
		BenchmarkCase first = mainCase(0);
		BenchmarkCase second = mainCase(1);
		BenchmarkFakes.ScriptedVtonProvider provider = new BenchmarkFakes.ScriptedVtonProvider();
		provider.usageEnabled(true);
		BenchmarkFakes.ScriptedQualityGate gate = new BenchmarkFakes.ScriptedQualityGate(QualityDecision.RETRY,
			QualityDecision.FAIL);
		OfflineEvaluationBenchmarkRunner runner = new OfflineEvaluationBenchmarkRunner(mainManifest(), provider, gate,
			new BenchmarkFakes.SteppedClock(0, 100), new BenchmarkResultStore());
		BenchmarkReport report = runner.run(mainSpec("run-2", first.caseId(), second.caseId()));
		assertThat(report.caseResults()).extracting(BenchmarkCaseResult::outcome)
			.containsExactly(Outcome.QUALITY_RETRY, Outcome.QUALITY_FAIL);
		assertThat(report.caseResults()).extracting(BenchmarkCaseResult::qualityGateResult)
			.containsExactly(QualityDecision.RETRY, QualityDecision.FAIL);
		// Failed/retried attempt cost remains attributable, never zeroed.
		assertThat(report.caseResults()).allSatisfy(result -> {
			assertThat(result.cost()).isEqualByComparingTo("0.05");
			assertThat(result.currency()).isEqualTo("USD");
		});
	}

	@Test
	void providerFailureEvaluatorUnavailableAndMalformedOutputAreDistinctOutcomes() {
		BenchmarkCase first = mainCase(0);
		BenchmarkCase second = mainCase(1);
		BenchmarkCase third = mainCase(2);
		BenchmarkCase fourth = mainCase(3);

		BenchmarkFakes.ScriptedVtonProvider provider = new BenchmarkFakes.ScriptedVtonProvider(
			BenchmarkFakes.ScriptedVtonProvider.Behavior.REJECT);
		OfflineEvaluationBenchmarkRunner runner = new OfflineEvaluationBenchmarkRunner(mainManifest(), provider,
			new BenchmarkFakes.ScriptedQualityGate(QualityDecision.PASS),
			new BenchmarkFakes.SteppedClock(0, 100), new BenchmarkResultStore());
		BenchmarkCaseResult rejected = runner.run(mainSpec("run-reject", first.caseId())).caseResults().get(0);
		assertThat(rejected.outcome()).isEqualTo(Outcome.PROVIDER_FAILURE);
		assertThat(rejected.qualityGateResult()).isNull();
		assertThat(rejected.cost()).isNull();

		// evaluator unavailable
		BenchmarkFakes.ScriptedQualityGate unavailableGate = new BenchmarkFakes.ScriptedQualityGate();
		unavailableGate.unavailable(true);
		OfflineEvaluationBenchmarkRunner unavailableRunner = new OfflineEvaluationBenchmarkRunner(mainManifest(),
				new BenchmarkFakes.ScriptedVtonProvider(), unavailableGate,
				new BenchmarkFakes.SteppedClock(0, 100), new BenchmarkResultStore());
		assertThat(unavailableRunner.run(mainSpec("run-evaluator", first.caseId())).caseResults().get(0).outcome())
			.isEqualTo(Outcome.EVALUATOR_UNAVAILABLE);

		// malformed output
		BenchmarkFakes.ScriptedVtonProvider malformedProvider = new BenchmarkFakes.ScriptedVtonProvider();
		malformedProvider.outputBytes(BenchmarkFakes.MALFORMED_OUTPUT);
		malformedProvider.usageEnabled(true);
		OfflineEvaluationBenchmarkRunner malformedRunner = new OfflineEvaluationBenchmarkRunner(mainManifest(),
				malformedProvider, new BenchmarkFakes.ScriptedQualityGate(QualityDecision.PASS),
				new BenchmarkFakes.SteppedClock(0, 100), new BenchmarkResultStore());
		BenchmarkCaseResult malformed = malformedRunner.run(mainSpec("run-malformed", second.caseId()))
			.caseResults()
			.get(0);
		assertThat(malformed.outcome()).isEqualTo(Outcome.OUTPUT_INVALID);
		assertThat(malformed.qualityGateResult()).isNull();
		assertThat(malformed.cost()).isEqualByComparingTo("0.05");

		// provider throwing on submit is still a provider failure with no leaked body
		BenchmarkFakes.ScriptedVtonProvider throwingProvider = new BenchmarkFakes.ScriptedVtonProvider(
			BenchmarkFakes.ScriptedVtonProvider.Behavior.THROW_ON_SUBMIT);
		OfflineEvaluationBenchmarkRunner throwingRunner = new OfflineEvaluationBenchmarkRunner(mainManifest(),
				throwingProvider, new BenchmarkFakes.ScriptedQualityGate(QualityDecision.PASS),
				new BenchmarkFakes.SteppedClock(0, 100), new BenchmarkResultStore());
		BenchmarkCaseResult thrown = throwingRunner.run(mainSpec("run-throw", third.caseId())).caseResults().get(0);
		assertThat(thrown.outcome()).isEqualTo(Outcome.PROVIDER_FAILURE);
		assertThat(thrown.toString()).doesNotContain("stack trace");

		// status failure
		BenchmarkFakes.ScriptedVtonProvider statusFailedProvider = new BenchmarkFakes.ScriptedVtonProvider(
			BenchmarkFakes.ScriptedVtonProvider.Behavior.ACCEPT,
			BenchmarkFakes.ScriptedVtonProvider.Behavior.STATUS_FAILED);
		OfflineEvaluationBenchmarkRunner statusFailedRunner = new OfflineEvaluationBenchmarkRunner(mainManifest(),
				statusFailedProvider, new BenchmarkFakes.ScriptedQualityGate(QualityDecision.PASS),
				new BenchmarkFakes.SteppedClock(0, 100), new BenchmarkResultStore());
		assertThat(statusFailedRunner.run(mainSpec("run-status", fourth.caseId())).caseResults().get(0).outcome())
			.isEqualTo(Outcome.PROVIDER_FAILURE);
	}

	@Test
	void invalidFixtureShortCircuitsBeforeProviderInvocation() {
		BenchmarkFakes.ScriptedVtonProvider provider = new BenchmarkFakes.ScriptedVtonProvider();
		BenchmarkFakes.ScriptedQualityGate gate = new BenchmarkFakes.ScriptedQualityGate(QualityDecision.PASS);
		OfflineEvaluationBenchmarkRunner runner = new OfflineEvaluationBenchmarkRunner(
			BenchmarkFixtureManifest.open(TEST_MANIFEST), provider, gate,
			new BenchmarkFakes.SteppedClock(0, 100), new BenchmarkResultStore());
		// retired avatar fixture and checksum-mismatched item fixture
		BenchmarkCase retiredAvatar = variantCaseFor("fx-avatar-retired", "fx-item-001");
		BenchmarkReport report = runner.run(variantSpec("run-fixture", retiredAvatar.caseId()));
		assertThat(report.caseResults()).hasSize(1);
		assertThat(report.caseResults().get(0).outcome()).isEqualTo(Outcome.FIXTURE_INVALID);
		assertThat(report.caseResults().get(0).qualityGateResult()).isNull();
		assertThat(report.caseResults().get(0).cost()).isNull();
		assertThat(provider.submitCount()).isZero();
		assertThat(gate.evaluatedCandidates()).isEmpty();
		BenchmarkCase checksumItem = variantCaseFor("fx-avatar-001", "fx-item-checksum");
		BenchmarkReport checksumReport = runner.run(variantSpec("run-fixture", checksumItem.caseId()));
		assertThat(checksumReport.caseResults().get(0).outcome()).isEqualTo(Outcome.FIXTURE_INVALID);
		assertThat(provider.submitCount()).isZero();
	}

	@Test
	void replayOfCompletedCaseDoesNotReinvokeProviderAndConflictingConfigurationFailsClosed() {
		BenchmarkFakes.ScriptedVtonProvider provider = new BenchmarkFakes.ScriptedVtonProvider();
		BenchmarkResultStore store = new BenchmarkResultStore();
		OfflineEvaluationBenchmarkRunner runner = new OfflineEvaluationBenchmarkRunner(mainManifest(), provider,
			new BenchmarkFakes.ScriptedQualityGate(QualityDecision.PASS),
			new BenchmarkFakes.SteppedClock(0, 100), store);
		BenchmarkCase valid = mainCase(0);
		BenchmarkReport first = runner.run(mainSpec("run-replay", valid.caseId()));
		assertThat(provider.submitCount()).isEqualTo(1);
		BenchmarkReport replay = runner.run(mainSpec("run-replay", valid.caseId()));
		assertThat(provider.submitCount()).isEqualTo(1);
		assertThat(replay.caseResults()).isEqualTo(first.caseResults());
		// conflicting configuration fingerprint under the same run/case key fails closed
		BenchmarkRunSpec conflicting = new BenchmarkRunSpec("run-replay", "2026.01.0", "fake-benchmark", "try-on",
			"prompt-v2", "routing-v1", List.of(valid.caseId()));
		assertThatThrownBy(() -> runner.run(conflicting))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("benchmark result conflict");
	}

	@Test
	void resumeExecutesOnlyUnfinishedCasesAndOutputIsSortedByCaseId() {
		BenchmarkFakes.ScriptedVtonProvider provider = new BenchmarkFakes.ScriptedVtonProvider();
		BenchmarkResultStore store = new BenchmarkResultStore();
		OfflineEvaluationBenchmarkRunner runner = new OfflineEvaluationBenchmarkRunner(mainManifest(), provider,
			new BenchmarkFakes.ScriptedQualityGate(QualityDecision.PASS),
			new BenchmarkFakes.SteppedClock(0, 100), store);
		BenchmarkCase first = mainCase(1);
		BenchmarkCase second = mainCase(0);
		// "interrupted" run completes only the first selected case
		runner.run(mainSpec("run-resume", first.caseId()));
		assertThat(provider.submitCount()).isEqualTo(1);
		// resume with both cases: only the unfinished second case invokes the provider
		runner.run(mainSpec("run-resume", first.caseId(), second.caseId()));
		assertThat(provider.submitCount()).isEqualTo(2);
		// selected ordered case ids are honored; report is sorted by case id regardless of completion order
		BenchmarkReport reversed = runner.run(mainSpec("run-resume", second.caseId(), first.caseId()));
		assertThat(reversed.caseResults())
			.extracting(BenchmarkCaseResult::caseId)
			.isSorted()
			.containsExactlyInAnyOrder(first.caseId(), second.caseId());
	}

	@Test
	void latencyClockIsMonotonicUnderInjectedClockAndIncludesQualityDecision() {
		BenchmarkFakes.ScriptedQualityGate gate = new BenchmarkFakes.ScriptedQualityGate(QualityDecision.PASS);
		OfflineEvaluationBenchmarkRunner runner = new OfflineEvaluationBenchmarkRunner(mainManifest(),
				new BenchmarkFakes.ScriptedVtonProvider(), gate,
				new BenchmarkFakes.SteppedClock(10_000, 250), new BenchmarkResultStore());
		BenchmarkCaseResult result = runner.run(mainSpec("run-latency", mainCase(0).caseId())).caseResults().get(0);
		// clock read at submission start and again after output capture + quality decision
		assertThat(result.latencyMs()).isEqualTo(250);
		// fixture-invalid case still measures elapsed time deterministically
		BenchmarkCase retired = variantCaseFor("fx-avatar-retired", "fx-item-001");
		OfflineEvaluationBenchmarkRunner variantRunner = new OfflineEvaluationBenchmarkRunner(
			BenchmarkFixtureManifest.open(TEST_MANIFEST), new BenchmarkFakes.ScriptedVtonProvider(),
			new BenchmarkFakes.ScriptedQualityGate(QualityDecision.PASS),
			new BenchmarkFakes.SteppedClock(5_000, 100), new BenchmarkResultStore());
		assertThat(variantRunner.run(variantSpec("run-latency-2", retired.caseId()))
			.caseResults()
			.get(0)
			.latencyMs()).isEqualTo(100);
	}

	@Test
	void failuresExposeNoProviderBodiesOrMedia() {
		OfflineEvaluationBenchmarkRunner runner = new OfflineEvaluationBenchmarkRunner(mainManifest(),
			new BenchmarkFakes.ScriptedVtonProvider(), new BenchmarkFakes.ScriptedQualityGate(QualityDecision.PASS),
			new BenchmarkFakes.SteppedClock(0, 100), new BenchmarkResultStore());
		for (int i = 0; i < 4; i++) {
			BenchmarkCase benchmarkCase = mainCase(i);
			BenchmarkReport report = runner.run(mainSpec("run-privacy-" + i, benchmarkCase.caseId()));
			for (BenchmarkCaseResult result : report.caseResults()) {
				String rendered = result.toString();
				assertThat(rendered).doesNotContain("deadbeef").doesNotContain("stack trace")
					.doesNotContain("http").doesNotContain("base64");
			}
		}
	}

	@Test
	void runSpecBenchmarkVersionMustMatchManifest() {
		OfflineEvaluationBenchmarkRunner runner = new OfflineEvaluationBenchmarkRunner(mainManifest(),
			new BenchmarkFakes.ScriptedVtonProvider(), new BenchmarkFakes.ScriptedQualityGate(QualityDecision.PASS),
			new BenchmarkFakes.SteppedClock(0, 100), new BenchmarkResultStore());
		BenchmarkRunSpec wrongVersion = new BenchmarkRunSpec("run-x", "2025.12.0", "fake-benchmark", "try-on",
			"prompt-v1", "routing-v1", List.of(mainCase(0).caseId()));
		assertThatThrownBy(() -> runner.run(wrongVersion))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("benchmark version mismatch");
	}

	@Test
	void runPopulatesDeterministicMatrixRowsWithCategoryAndProviderTotal() {
		BenchmarkFakes.ScriptedVtonProvider provider = new BenchmarkFakes.ScriptedVtonProvider();
		provider.usageEnabled(true);
		OfflineEvaluationBenchmarkRunner runner = new OfflineEvaluationBenchmarkRunner(mainManifest(), provider,
				new BenchmarkFakes.ScriptedQualityGate(QualityDecision.PASS),
				new BenchmarkFakes.SteppedClock(0, 100), new BenchmarkResultStore());
		BenchmarkReport report = runner.run(mainSpec("run-matrix", mainCase(0).caseId(), mainCase(1).caseId()));
		// matrix rows mirror BenchmarkMatrixGenerator over the ordered case results
		assertThat(report.matrixRows()).isEqualTo(BenchmarkMatrixGenerator.rows(report.caseResults()));
		assertThat(report.matrixRows()).extracting(BenchmarkMatrixRow::category)
			.containsExactly("tops", BenchmarkMatrixGenerator.PROVIDER_TOTAL_CATEGORY);
		assertThat(report.matrixRows())
			.allSatisfy(row -> assertThat(row.sampleCount()).isEqualTo(2));
	}

	@Test
	void billedProviderFailureCostIsAttributableAndIncludedInCostPerSuccessDenominator() {
		BenchmarkCase successCase = mainCase(0);
		BenchmarkCase failedCase = mainCase(1);
		BenchmarkFakes.ScriptedVtonProvider provider = new BenchmarkFakes.ScriptedVtonProvider(
				BenchmarkFakes.ScriptedVtonProvider.Behavior.ACCEPT,
				BenchmarkFakes.ScriptedVtonProvider.Behavior.STATUS_FAILED);
		provider.usageEnabled(true);
		OfflineEvaluationBenchmarkRunner runner = new OfflineEvaluationBenchmarkRunner(mainManifest(), provider,
				new BenchmarkFakes.ScriptedQualityGate(QualityDecision.PASS),
				new BenchmarkFakes.SteppedClock(0, 100), new BenchmarkResultStore());
		BenchmarkReport report = runner.run(mainSpec("run-mixed", failedCase.caseId(), successCase.caseId()));
		// report is sorted by case id: success case (index 0) before failed case (index 1)
		assertThat(report.caseResults()).extracting(BenchmarkCaseResult::caseId)
			.containsExactly(successCase.caseId(), failedCase.caseId());
		assertThat(report.caseResults()).extracting(BenchmarkCaseResult::outcome)
			.containsExactly(Outcome.SUCCESS, Outcome.PROVIDER_FAILURE);
		BenchmarkCaseResult failure = report.caseResults().get(1);
		assertThat(failure.cost()).isEqualByComparingTo("0.05");
		assertThat(failure.currency()).isEqualTo("USD");
		BenchmarkMatrixRow total = report.matrixRows()
			.stream()
			.filter(row -> row.category().equals(BenchmarkMatrixGenerator.PROVIDER_TOTAL_CATEGORY))
			.findFirst()
			.orElseThrow();
		assertThat(total.costPerSuccess()).isEqualByComparingTo("0.10");
	}

	@Test
	void rejectionAndThrowingSubmitExposeNoInventedCost() {
		// Rejected carries no usage in the neutral types: cost unknown, stays null.
		BenchmarkFakes.ScriptedVtonProvider rejectProvider = new BenchmarkFakes.ScriptedVtonProvider(
				BenchmarkFakes.ScriptedVtonProvider.Behavior.REJECT);
		rejectProvider.usageEnabled(true);
		OfflineEvaluationBenchmarkRunner rejectRunner = new OfflineEvaluationBenchmarkRunner(mainManifest(),
				rejectProvider, new BenchmarkFakes.ScriptedQualityGate(QualityDecision.PASS),
				new BenchmarkFakes.SteppedClock(0, 100), new BenchmarkResultStore());
		BenchmarkCaseResult rejected = rejectRunner.run(mainSpec("run-reject-null", mainCase(0).caseId()))
			.caseResults()
			.get(0);
		assertThat(rejected.outcome()).isEqualTo(Outcome.PROVIDER_FAILURE);
		assertThat(rejected.cost()).isNull();
		assertThat(rejected.currency()).isNull();

		// submit throwing carries no usage either: unknown cost stays null, never invented
		BenchmarkFakes.ScriptedVtonProvider throwProvider = new BenchmarkFakes.ScriptedVtonProvider(
				BenchmarkFakes.ScriptedVtonProvider.Behavior.THROW_ON_SUBMIT);
		throwProvider.usageEnabled(true);
		OfflineEvaluationBenchmarkRunner throwRunner = new OfflineEvaluationBenchmarkRunner(mainManifest(),
				throwProvider, new BenchmarkFakes.ScriptedQualityGate(QualityDecision.PASS),
				new BenchmarkFakes.SteppedClock(0, 100), new BenchmarkResultStore());
		BenchmarkCaseResult thrown = throwRunner.run(mainSpec("run-throw-null", mainCase(1).caseId()))
			.caseResults()
			.get(0);
		assertThat(thrown.outcome()).isEqualTo(Outcome.PROVIDER_FAILURE);
		assertThat(thrown.cost()).isNull();
		assertThat(thrown.currency()).isNull();
	}

	private static BenchmarkRunSpec variantSpec(String runId, String... caseIds) {
		return new BenchmarkRunSpec(runId, "2026.01.0-variants", "fake-benchmark", "try-on", "prompt-v1", "routing-v1",
			List.of(caseIds));
	}
}
