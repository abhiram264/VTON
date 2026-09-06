package com.aifashionvault.platform.generation.benchmark;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aifashionvault.generation.benchmark.BenchmarkCaseResult;
import com.aifashionvault.generation.benchmark.Outcome;
import com.aifashionvault.shared.QualityDecision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance and unit tests for {@link BenchmarkReleaseGate} (Plan §52).
 */
class BenchmarkReleaseGateTest {

	private static final String VERSION = "2026.01.0";
	private static final String PROVIDER = "fake-benchmark";
	private static final String MODEL = "try-on";

	private BenchmarkReleaseGate gate;
	private ReleaseGateThresholds thresholds;

	@BeforeEach
	void setUp() {
		gate = new BenchmarkReleaseGate();
		thresholds = ReleaseGateThresholds.defaults();
	}

	private static BenchmarkCaseResult result(String runId, String caseId, String category, long latencyMs,
			Outcome outcome, String cost, String identity, String garment, String color) {
		QualityDecision decision = switch (outcome) {
			case SUCCESS -> QualityDecision.PASS;
			case QUALITY_RETRY -> QualityDecision.RETRY;
			case QUALITY_FAIL -> QualityDecision.FAIL;
			default -> null;
		};
		return new BenchmarkCaseResult(runId, caseId, PROVIDER, MODEL, category, latencyMs, outcome,
				cost == null ? null : new BigDecimal(cost), cost == null ? null : "USD", null,
				identity == null ? null : new BigDecimal(identity),
				garment == null ? null : new BigDecimal(garment),
				color == null ? null : new BigDecimal(color),
				false, decision);
	}

	private static List<BenchmarkCaseResult> buildArm(String runId, int count, String category, long latencyMs,
			Outcome outcome, String cost, String identity, String garment, String color) {
		List<BenchmarkCaseResult> list = new ArrayList<>();
		for (int i = 1; i <= count; i++) {
			list.add(result(runId, "case-" + i, category, latencyMs, outcome, cost, identity, garment, color));
		}
		return list;
	}

	// 1. Normal (PASS): Identical baseline and candidate arms (>=30 matched cases) + defaults
	@Test
	void identicalArmsWithAdequateSamplePassWithZeroFindings() {
		List<BenchmarkCaseResult> baseline = buildArm("baseline", 30, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95",
				"0.95", "0.95");
		List<BenchmarkCaseResult> candidate = buildArm("candidate", 30, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95",
				"0.95", "0.95");

		ReleaseGateDecision decision = gate.decide(baseline, candidate, VERSION, thresholds);

		assertThat(decision.verdict()).isEqualTo(ReleaseGateVerdict.PASS);
		assertThat(decision.findings()).isEmpty();
		assertThat(decision.matchedPairs()).isEqualTo(30L);
		assertThat(decision.benchmarkVersion()).isEqualTo(VERSION);
	}

	// 2. Normal (BLOCK on regression): Candidate overall fidelity regresses beyond 2% tolerance
	@Test
	void overallFidelityRegressionBeyondToleranceBlocks() {
		List<BenchmarkCaseResult> baseline = buildArm("baseline", 30, "tops", 100L, Outcome.SUCCESS, "0.10", "0.90",
				"0.90", "0.90");
		// Fidelity 0.85 vs 0.90 = -5.555556% regression (tolerance 2%)
		List<BenchmarkCaseResult> candidate = buildArm("candidate", 30, "tops", 100L, Outcome.SUCCESS, "0.10", "0.85",
				"0.85", "0.85");

		ReleaseGateDecision decision = gate.decide(baseline, candidate, VERSION, thresholds);

		assertThat(decision.verdict()).isEqualTo(ReleaseGateVerdict.BLOCK);
		GateFinding finding = decision.findings().stream()
				.filter(f -> "fidelity-regression".equals(f.rule()))
				.findFirst()
				.orElseThrow();
		assertThat(finding.severity()).isEqualTo("block");
		assertThat(finding.category()).isEqualTo("ALL");
		assertThat(finding.dimension()).isEqualTo("fidelity");
		assertThat(finding.baselineValue()).isEqualTo("0.900000");
		assertThat(finding.candidateValue()).isEqualTo("0.850000");
	}

	// 3. Edge (identity tighter): Candidate identity mean regresses 1.5% (within 2% fidelity, beyond 1% identity)
	@Test
	void identityMeanRegressionTighterToleranceBlocks() {
		// Baseline identity 0.90, garment 0.90, color 0.90 -> fidelity 0.90
		List<BenchmarkCaseResult> baseline = buildArm("baseline", 30, "tops", 100L, Outcome.SUCCESS, "0.10", "0.90",
				"0.90", "0.90");
		// Candidate identity 0.8865 (-1.5% regression, beyond 1% tolerance), garment 0.90, color 0.90
		// Overall fidelity = 0.8955 (-0.5% regression, within 2% tolerance)
		List<BenchmarkCaseResult> candidate = buildArm("candidate", 30, "tops", 100L, Outcome.SUCCESS, "0.10", "0.8865",
				"0.90", "0.90");

		ReleaseGateDecision decision = gate.decide(baseline, candidate, VERSION, thresholds);

		assertThat(decision.verdict()).isEqualTo(ReleaseGateVerdict.BLOCK);
		assertThat(decision.findings())
				.anyMatch(f -> "identity-regression".equals(f.rule()) && "block".equals(f.severity()));
		assertThat(decision.findings())
				.noneMatch(f -> "fidelity-regression".equals(f.rule()));
	}

	// 4. Edge (P95 tail): Candidate with unchanged P50 but P95 beyond tolerance
	@Test
	void p95RegressionBlocksEvenWhenP50Unchanged() {
		List<BenchmarkCaseResult> baseline = new ArrayList<>();
		List<BenchmarkCaseResult> candidate = new ArrayList<>();
		for (int i = 1; i <= 30; i++) {
			baseline.add(result("baseline", "case-" + i, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95", "0.95", "0.95"));
			// Cases 1-28: 100ms; Cases 29-30: 200ms (+100% P95 increase, tolerance 10%)
			long candidateLatency = i <= 28 ? 100L : 200L;
			candidate.add(result("candidate", "case-" + i, "tops", candidateLatency, Outcome.SUCCESS, "0.10", "0.95",
					"0.95", "0.95"));
		}

		ReleaseGateDecision decision = gate.decide(baseline, candidate, VERSION, thresholds);

		assertThat(decision.verdict()).isEqualTo(ReleaseGateVerdict.BLOCK);
		assertThat(decision.findings())
				.anyMatch(f -> "p95-regression".equals(f.rule()) && "block".equals(f.severity()));
		assertThat(decision.findings())
				.noneMatch(f -> "p50-regression".equals(f.rule()));
	}

	// 5. Edge (floors): Absolute floor/ceiling breaches block even when baseline is equally bad
	@Test
	void absoluteFloorsAndCeilingsBlockEvenWhenBaselineIsEquallyBad() {
		// Pass-rate floor breach (0.50 vs floor 0.90)
		List<BenchmarkCaseResult> baselineLowPass = new ArrayList<>();
		List<BenchmarkCaseResult> candidateLowPass = new ArrayList<>();
		for (int i = 1; i <= 30; i++) {
			Outcome outcome = i <= 15 ? Outcome.SUCCESS : Outcome.QUALITY_FAIL;
			baselineLowPass.add(result("baseline", "case-" + i, "tops", 100L, outcome, "0.10", "0.95", "0.95", "0.95"));
			candidateLowPass.add(result("candidate", "case-" + i, "tops", 100L, outcome, "0.10", "0.95", "0.95", "0.95"));
		}
		ReleaseGateDecision passRateDecision = gate.decide(baselineLowPass, candidateLowPass, VERSION, thresholds);
		assertThat(passRateDecision.verdict()).isEqualTo(ReleaseGateVerdict.BLOCK);
		assertThat(passRateDecision.findings())
				.anyMatch(f -> "pass-rate-floor".equals(f.rule()) && "block".equals(f.severity()));

		// Fail-rate ceiling breach (0.50 vs ceiling 0.10)
		assertThat(passRateDecision.findings())
				.anyMatch(f -> "fail-rate-ceiling".equals(f.rule()) && "block".equals(f.severity()));

		// P50 ceiling breach (20,000ms vs ceiling 15,000ms)
		List<BenchmarkCaseResult> baselineP50Ceiling = buildArm("baseline", 30, "tops", 20000L, Outcome.SUCCESS, "0.10",
				"0.95", "0.95", "0.95");
		List<BenchmarkCaseResult> candidateP50Ceiling = buildArm("candidate", 30, "tops", 20000L, Outcome.SUCCESS, "0.10",
				"0.95", "0.95", "0.95");
		ReleaseGateDecision p50CeilingDecision = gate.decide(baselineP50Ceiling, candidateP50Ceiling, VERSION, thresholds);
		assertThat(p50CeilingDecision.verdict()).isEqualTo(ReleaseGateVerdict.BLOCK);
		assertThat(p50CeilingDecision.findings())
				.anyMatch(f -> "p50-ceiling".equals(f.rule()) && "block".equals(f.severity()));

		// P95 ceiling breach (35,000ms vs ceiling 30,000ms)
		List<BenchmarkCaseResult> baselineP95Ceiling = buildArm("baseline", 30, "tops", 35000L, Outcome.SUCCESS, "0.10",
				"0.95", "0.95", "0.95");
		List<BenchmarkCaseResult> candidateP95Ceiling = buildArm("candidate", 30, "tops", 35000L, Outcome.SUCCESS, "0.10",
				"0.95", "0.95", "0.95");
		ReleaseGateDecision p95CeilingDecision = gate.decide(baselineP95Ceiling, candidateP95Ceiling, VERSION, thresholds);
		assertThat(p95CeilingDecision.verdict()).isEqualTo(ReleaseGateVerdict.BLOCK);
		assertThat(p95CeilingDecision.findings())
				.anyMatch(f -> "p95-ceiling".equals(f.rule()) && "block".equals(f.severity()));
	}

	// 6. Edge (small sample): Matched pairs < minSampleSize, no absolute violation -> REVIEW + insufficient-sample
	@Test
	void smallSampleRoutesToReviewWithInsufficientSample() {
		List<BenchmarkCaseResult> baseline = buildArm("baseline", 10, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95",
				"0.95", "0.95");
		List<BenchmarkCaseResult> candidate = buildArm("candidate", 10, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95",
				"0.95", "0.95");

		ReleaseGateDecision decision = gate.decide(baseline, candidate, VERSION, thresholds);

		assertThat(decision.verdict()).isEqualTo(ReleaseGateVerdict.REVIEW);
		GateFinding finding = decision.findings().stream()
				.filter(f -> "insufficient-sample".equals(f.rule()))
				.findFirst()
				.orElseThrow();
		assertThat(finding.severity()).isEqualTo("review");
		assertThat(finding.dimension()).isEqualTo("coverage");
		assertThat(finding.category()).isEqualTo("ALL");
		assertThat(finding.candidateValue()).isEqualTo("10");
	}

	// 7. Edge (missing pairs): Candidate missing some baseline cases -> REVIEW + missing-pairs naming count
	@Test
	void missingPairsInCandidateProducesReviewFindingNamingCount() {
		List<BenchmarkCaseResult> baseline = buildArm("baseline", 30, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95",
				"0.95", "0.95");
		List<BenchmarkCaseResult> candidate = buildArm("candidate", 28, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95",
				"0.95", "0.95");

		ReleaseGateDecision decision = gate.decide(baseline, candidate, VERSION, thresholds);

		assertThat(decision.verdict()).isEqualTo(ReleaseGateVerdict.REVIEW);
		GateFinding finding = decision.findings().stream()
				.filter(f -> "missing-pairs".equals(f.rule()))
				.findFirst()
				.orElseThrow();
		assertThat(finding.severity()).isEqualTo("review");
		assertThat(finding.dimension()).isEqualTo("coverage");
		assertThat(finding.detail()).contains("2 case(s) missing in candidate");
	}

	// 8. Edge (no matched pairs): Zero shared case ids -> REVIEW + no-matched-pairs (never PASS)
	@Test
	void zeroMatchedPairsRoutesToReviewWithNoMatchedPairs() {
		List<BenchmarkCaseResult> baseline = new ArrayList<>();
		List<BenchmarkCaseResult> candidate = new ArrayList<>();
		for (int i = 1; i <= 30; i++) {
			baseline.add(result("baseline", "base-" + i, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95", "0.95", "0.95"));
			candidate.add(result("candidate", "cand-" + i, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95", "0.95", "0.95"));
		}

		ReleaseGateDecision decision = gate.decide(baseline, candidate, VERSION, thresholds);

		assertThat(decision.verdict()).isEqualTo(ReleaseGateVerdict.REVIEW);
		assertThat(decision.matchedPairs()).isEqualTo(0L);
		assertThat(decision.findings())
				.anyMatch(f -> "no-matched-pairs".equals(f.rule()) && "review".equals(f.severity()));
	}

	// 9. Edge (unknown metrics): Candidate arm with no quality-evaluated cases -> REVIEW + pass-rate-unknown
	@Test
	void noQualityEvaluatedCasesRoutesToReviewWithPassRateUnknown() {
		List<BenchmarkCaseResult> baseline = buildArm("baseline", 30, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95",
				"0.95", "0.95");
		// PROVIDER_FAILURE has null qualityGateResult in result(...) helper
		List<BenchmarkCaseResult> candidate = buildArm("candidate", 30, "tops", 100L, Outcome.PROVIDER_FAILURE, "0.10",
				null, null, null);

		ReleaseGateDecision decision = gate.decide(baseline, candidate, VERSION, thresholds);

		assertThat(decision.findings())
				.anyMatch(f -> "pass-rate-unknown".equals(f.rule()) && "review".equals(f.severity()));
		assertThat(decision.findings())
				.noneMatch(f -> "pass-rate-floor".equals(f.rule()));
	}

	// 10. Edge (zero successes, evaluated): Candidate with evaluated cases and zero successes -> BLOCK via pass-rate-floor
	@Test
	void zeroSuccessesWithEvaluationsBlocksViaPassRateFloor() {
		List<BenchmarkCaseResult> baseline = buildArm("baseline", 30, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95",
				"0.95", "0.95");
		List<BenchmarkCaseResult> candidate = buildArm("candidate", 30, "tops", 100L, Outcome.QUALITY_FAIL, "0.10",
				"0.50", "0.50", "0.50");

		ReleaseGateDecision decision = gate.decide(baseline, candidate, VERSION, thresholds);

		assertThat(decision.verdict()).isEqualTo(ReleaseGateVerdict.BLOCK);
		GateFinding finding = decision.findings().stream()
				.filter(f -> "pass-rate-floor".equals(f.rule()))
				.findFirst()
				.orElseThrow();
		assertThat(finding.severity()).isEqualTo("block");
		assertThat(finding.candidateValue()).isEqualTo("0.000000");
	}

	// 11. Failure (evaluator unavailable): Candidate EVALUATOR_UNAVAILABLE outcomes -> REVIEW + evaluator-unavailable
	@Test
	void candidateEvaluatorUnavailableRoutesToReview() {
		List<BenchmarkCaseResult> baseline = buildArm("baseline", 60, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95",
				"0.95", "0.95");
		List<BenchmarkCaseResult> candidate = buildArm("candidate", 59, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95",
				"0.95", "0.95");
		candidate.add(result("candidate", "case-60", "tops", 100L, Outcome.EVALUATOR_UNAVAILABLE, "0.10", null, null, null));

		ReleaseGateDecision decision = gate.decide(baseline, candidate, VERSION, thresholds);

		assertThat(decision.verdict()).isEqualTo(ReleaseGateVerdict.REVIEW);
		GateFinding finding = decision.findings().stream()
				.filter(f -> "evaluator-unavailable".equals(f.rule()))
				.findFirst()
				.orElseThrow();
		assertThat(finding.severity()).isEqualTo("review");
		assertThat(finding.dimension()).isEqualTo("integrity");
		assertThat(finding.candidateValue()).isEqualTo("1");
	}

	// 12. Failure (fixture invalid): Candidate FIXTURE_INVALID outcomes -> REVIEW + fixture-invalid
	@Test
	void candidateFixtureInvalidRoutesToReview() {
		List<BenchmarkCaseResult> baseline = buildArm("baseline", 60, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95",
				"0.95", "0.95");
		List<BenchmarkCaseResult> candidate = buildArm("candidate", 59, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95",
				"0.95", "0.95");
		candidate.add(result("candidate", "case-60", "tops", 100L, Outcome.FIXTURE_INVALID, "0.10", null, null, null));

		ReleaseGateDecision decision = gate.decide(baseline, candidate, VERSION, thresholds);

		assertThat(decision.verdict()).isEqualTo(ReleaseGateVerdict.REVIEW);
		GateFinding finding = decision.findings().stream()
				.filter(f -> "fixture-invalid".equals(f.rule()))
				.findFirst()
				.orElseThrow();
		assertThat(finding.severity()).isEqualTo("review");
		assertThat(finding.dimension()).isEqualTo("integrity");
		assertThat(finding.candidateValue()).isEqualTo("1");
	}

	// 13. Failure (cost incomparable): Cost rule enabled, either arm costPerSuccess null -> cost-incomparable review finding
	@Test
	void costIncomparableRoutesToReviewWhenCostNull() {
		List<BenchmarkCaseResult> baseline = buildArm("baseline", 30, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95",
				"0.95", "0.95");
		// Candidate has null cost
		List<BenchmarkCaseResult> candidate = buildArm("candidate", 30, "tops", 100L, Outcome.SUCCESS, null, "0.95",
				"0.95", "0.95");

		ReleaseGateDecision decision = gate.decide(baseline, candidate, VERSION, thresholds);

		assertThat(decision.verdict()).isEqualTo(ReleaseGateVerdict.REVIEW);
		GateFinding finding = decision.findings().stream()
				.filter(f -> "cost-incomparable".equals(f.rule()))
				.findFirst()
				.orElseThrow();
		assertThat(finding.severity()).isEqualTo("review");
		assertThat(finding.dimension()).isEqualTo("costPerSuccess");
		assertThat(finding.candidateValue()).isEqualTo("n/a");
	}

	// Cost increase blocks when cost exceeds tolerance
	@Test
	void costIncreaseBeyondToleranceBlocks() {
		List<BenchmarkCaseResult> baseline = buildArm("baseline", 30, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95",
				"0.95", "0.95");
		// 0.15 vs 0.10 = +50% increase (tolerance 10%)
		List<BenchmarkCaseResult> candidate = buildArm("candidate", 30, "tops", 100L, Outcome.SUCCESS, "0.15", "0.95",
				"0.95", "0.95");

		ReleaseGateDecision decision = gate.decide(baseline, candidate, VERSION, thresholds);

		assertThat(decision.verdict()).isEqualTo(ReleaseGateVerdict.BLOCK);
		assertThat(decision.findings())
				.anyMatch(f -> "cost-increase".equals(f.rule()) && "block".equals(f.severity()));
	}

	// Fail rate increase blocks when delta exceeds tolerance
	@Test
	void failRateIncreaseBeyondToleranceBlocks() {
		List<BenchmarkCaseResult> baseline = buildArm("baseline", 50, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95",
				"0.95", "0.95");
		List<BenchmarkCaseResult> candidate = new ArrayList<>();
		for (int i = 1; i <= 50; i++) {
			// 3 failures out of 50 = fail rate 0.06 vs baseline 0.00 -> delta 0.06 > tolerance 0.02 (and fail rate 0.06 <= ceiling 0.10)
			Outcome outcome = i <= 3 ? Outcome.QUALITY_FAIL : Outcome.SUCCESS;
			candidate.add(result("candidate", "case-" + i, "tops", 100L, outcome, "0.10", "0.95", "0.95", "0.95"));
		}

		ReleaseGateDecision decision = gate.decide(baseline, candidate, VERSION, thresholds);

		assertThat(decision.verdict()).isEqualTo(ReleaseGateVerdict.BLOCK);
		assertThat(decision.findings())
				.anyMatch(f -> "fail-rate-increase".equals(f.rule()) && "block".equals(f.severity()));
	}

	// 15. Invalid (category mismatch): Matched case ids with differing categories between arms -> comparator exception propagates
	@Test
	void categoryMismatchBetweenArmsThrowsIllegalArgumentException() {
		List<BenchmarkCaseResult> baseline = List.of(
				result("baseline", "case-1", "tops", 100L, Outcome.SUCCESS, "0.10", "0.95", "0.95", "0.95"));
		List<BenchmarkCaseResult> candidate = List.of(
				result("candidate", "case-1", "bottoms", 100L, Outcome.SUCCESS, "0.10", "0.95", "0.95", "0.95"));

		assertThatThrownBy(() -> gate.decide(baseline, candidate, VERSION, thresholds))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("category mismatch");
	}

	// 16. Invalid (blank version / null arms / null thresholds): Throws IllegalArgumentException before comparison
	@Test
	void nullOrBlankInputsThrowIllegalArgumentException() {
		List<BenchmarkCaseResult> validArm = buildArm("arm", 5, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95", "0.95",
				"0.95");

		assertThatThrownBy(() -> gate.decide(null, validArm, VERSION, thresholds))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("baseline must not be null");

		assertThatThrownBy(() -> gate.decide(validArm, null, VERSION, thresholds))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("candidate must not be null");

		assertThatThrownBy(() -> gate.decide(validArm, validArm, null, thresholds))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("benchmarkVersion must not be blank");

		assertThatThrownBy(() -> gate.decide(validArm, validArm, "   ", thresholds))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("benchmarkVersion must not be blank");

		assertThatThrownBy(() -> gate.decide(validArm, validArm, VERSION, null))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("thresholds must not be null");
	}

	// 17. Per-category routing: Within overall tolerances but one category beyond tolerance -> REVIEW + category-regression
	@Test
	void perCategoryRegressionRoutesToReviewWithCategoryName() {
		List<BenchmarkCaseResult> baseline = new ArrayList<>();
		List<BenchmarkCaseResult> candidate = new ArrayList<>();

		// 25 tops cases: fidelity 0.95 vs 0.95 (0% delta)
		for (int i = 1; i <= 25; i++) {
			baseline.add(result("baseline", "case-" + i, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95", "0.95", "0.95"));
			candidate.add(result("candidate", "case-" + i, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95", "0.95", "0.95"));
		}
		// 5 bottoms cases: fidelity 0.95 vs 0.85 (-10.5% delta in bottoms; overall delta is -1.75% within 2% tolerance)
		// Keep identity at 0.95 to stay within identity 1% tolerance, regressing garment/color to 0.80
		for (int i = 26; i <= 30; i++) {
			baseline.add(result("baseline", "case-" + i, "bottoms", 100L, Outcome.SUCCESS, "0.10", "0.95", "0.95", "0.95"));
			candidate.add(result("candidate", "case-" + i, "bottoms", 100L, Outcome.SUCCESS, "0.10", "0.95", "0.80", "0.80"));
		}

		ReleaseGateDecision decision = gate.decide(baseline, candidate, VERSION, thresholds);

		assertThat(decision.verdict()).isEqualTo(ReleaseGateVerdict.REVIEW);
		GateFinding finding = decision.findings().stream()
				.filter(f -> "category-regression".equals(f.rule()) && "bottoms".equals(f.category()))
				.findFirst()
				.orElseThrow();
		assertThat(finding.severity()).isEqualTo("review");
		assertThat(finding.dimension()).isEqualTo("fidelity");
		assertThat(decision.findings())
				.noneMatch(f -> "fidelity-regression".equals(f.rule()));
	}

	// 18. Precedence: Arms triggering both block-severity and review-severity findings -> verdict BLOCK
	@Test
	void blockPrecedenceOverReview() {
		// Missing pairs (review) + P50 ceiling breach (block)
		List<BenchmarkCaseResult> baseline = buildArm("baseline", 30, "tops", 20000L, Outcome.SUCCESS, "0.10", "0.95",
				"0.95", "0.95");
		List<BenchmarkCaseResult> candidate = buildArm("candidate", 29, "tops", 20000L, Outcome.SUCCESS, "0.10", "0.95",
				"0.95", "0.95");

		ReleaseGateDecision decision = gate.decide(baseline, candidate, VERSION, thresholds);

		assertThat(decision.verdict()).isEqualTo(ReleaseGateVerdict.BLOCK);
		assertThat(decision.findings())
				.anyMatch(f -> "missing-pairs".equals(f.rule()) && "review".equals(f.severity()));
		assertThat(decision.findings())
				.anyMatch(f -> "p50-ceiling".equals(f.rule()) && "block".equals(f.severity()));
	}

	// 19. Determinism/replay: Same inputs produce equal decisions with identically ordered findings
	@Test
	void decideTwiceOnSameInputsIsDeterministicAndPure() {
		List<BenchmarkCaseResult> baseline = buildArm("baseline", 30, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95",
				"0.95", "0.95");
		List<BenchmarkCaseResult> candidate = buildArm("candidate", 30, "tops", 100L, Outcome.SUCCESS, "0.10", "0.95",
				"0.95", "0.95");

		ReleaseGateDecision decision1 = gate.decide(baseline, candidate, VERSION, thresholds);
		ReleaseGateDecision decision2 = gate.decide(baseline, candidate, VERSION, thresholds);

		assertThat(decision1).isEqualTo(decision2);
		assertThat(decision1.findings()).isEqualTo(decision2.findings());
	}

	// Support custom minSampleSize 1
	@Test
	void customThresholdsWithMinSampleSizeOneEvaluatesRegressionOnSmallSample() {
		ReleaseGateThresholds custom = new ReleaseGateThresholds(
				new BigDecimal("0.02"),
				new BigDecimal("0.01"),
				new BigDecimal("0.05"),
				new BigDecimal("0.10"),
				new BigDecimal("0.10"),
				new BigDecimal("0.02"),
				new BigDecimal("0.90"),
				new BigDecimal("0.10"),
				15000L,
				30000L,
				1L);

		List<BenchmarkCaseResult> baseline = buildArm("baseline", 1, "tops", 100L, Outcome.SUCCESS, "0.10", "0.90",
				"0.90", "0.90");
		List<BenchmarkCaseResult> candidate = buildArm("candidate", 1, "tops", 100L, Outcome.SUCCESS, "0.10", "0.85",
				"0.85", "0.85");

		ReleaseGateDecision decision = gate.decide(baseline, candidate, VERSION, custom);

		assertThat(decision.verdict()).isEqualTo(ReleaseGateVerdict.BLOCK);
		assertThat(decision.findings())
				.anyMatch(f -> "fidelity-regression".equals(f.rule()) && "block".equals(f.severity()));
		assertThat(decision.findings())
				.noneMatch(f -> "insufficient-sample".equals(f.rule()));
	}

	// 22. Unauthorized (skip, with evidence): The gate is an offline library with no authenticated surface
	@Test
	void unauthorizedAccessIsSkippedWithEvidence() {
		// Testing Decision 22: The release gate is an offline library with no authenticated surface
		// and no tenant context (per the benchmark privacy contract ADR-015); no authorization path exists to test.
		assertThat(gate).isNotNull();
	}
}
