package com.aifashionvault.platform.generation.benchmark;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.aifashionvault.generation.benchmark.BenchmarkCaseResult;
import com.aifashionvault.generation.benchmark.Outcome;

/**
 * Metrics-only release gate evaluation layer around {@link BenchmarkRegressionComparator} (Plan §52).
 *
 * <p>Pure, deterministic function producing a {@link ReleaseGateDecision} with
 * exactly one verdict (PASS, BLOCK, or REVIEW) plus scalar-only {@link GateFinding}s.
 */
public final class BenchmarkReleaseGate {

	private static final int SCALE = BenchmarkMetrics.SCALE;
	private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

	public BenchmarkReleaseGate() {
	}

	public ReleaseGateDecision decide(
			List<BenchmarkCaseResult> baseline,
			List<BenchmarkCaseResult> candidate,
			String benchmarkVersion,
			ReleaseGateThresholds thresholds) {

		if (benchmarkVersion == null || benchmarkVersion.isBlank()) {
			throw new IllegalArgumentException("benchmarkVersion must not be blank");
		}
		if (baseline == null) {
			throw new IllegalArgumentException("baseline must not be null");
		}
		if (candidate == null) {
			throw new IllegalArgumentException("candidate must not be null");
		}
		if (thresholds == null) {
			throw new IllegalArgumentException("thresholds must not be null");
		}

		BenchmarkRegressionComparator.RegressionReport report = BenchmarkRegressionComparator.compare(
				baseline, candidate, benchmarkVersion, benchmarkVersion);

		long matchedPairs = report.caseDeltas().size();
		List<GateFinding> findings = new ArrayList<>();

		// 1. Missing pairs
		if (!report.missingInCandidate().isEmpty() || !report.missingInBaseline().isEmpty()) {
			int missingCandidate = report.missingInCandidate().size();
			int missingBaseline = report.missingInBaseline().size();
			findings.add(new GateFinding(
					"missing-pairs",
					"coverage",
					"ALL",
					"review",
					String.valueOf(missingCandidate),
					String.valueOf(missingBaseline),
					missingCandidate + " case(s) missing in candidate, " + missingBaseline
							+ " case(s) missing in baseline"));
		}

		// 2. Zero matched pairs
		if (matchedPairs == 0) {
			findings.add(new GateFinding(
					"no-matched-pairs",
					"coverage",
					"ALL",
					"review",
					"0",
					"0",
					"zero matched case pairs found between baseline and candidate"));
		}

		// 3. Evaluator / fixture integrity in candidate
		long evaluatorUnavailableCount = candidate.stream()
				.filter(r -> r.outcome() == Outcome.EVALUATOR_UNAVAILABLE)
				.count();
		if (evaluatorUnavailableCount > 0) {
			findings.add(new GateFinding(
					"evaluator-unavailable",
					"integrity",
					"ALL",
					"review",
					"n/a",
					String.valueOf(evaluatorUnavailableCount),
					evaluatorUnavailableCount + " candidate case(s) resulted in EVALUATOR_UNAVAILABLE"));
		}

		long fixtureInvalidCount = candidate.stream()
				.filter(r -> r.outcome() == Outcome.FIXTURE_INVALID)
				.count();
		if (fixtureInvalidCount > 0) {
			findings.add(new GateFinding(
					"fixture-invalid",
					"integrity",
					"ALL",
					"review",
					"n/a",
					String.valueOf(fixtureInvalidCount),
					fixtureInvalidCount + " candidate case(s) resulted in FIXTURE_INVALID"));
		}

		// 4. Statistics
		BenchmarkMetrics.Stats baselineStats = BenchmarkMetrics.of(baseline);
		BenchmarkMetrics.Stats candidateStats = BenchmarkMetrics.of(candidate);

		BigDecimal baselineIdentityMean = identityMean(baseline);
		BigDecimal candidateIdentityMean = identityMean(candidate);

		Long baselineP95 = nearestRankP95(baseline);
		Long candidateP95 = nearestRankP95(candidate);

		// 5. Absolute candidate checks & unknown checks
		if (candidateStats.qualityPassRate() == null) {
			findings.add(new GateFinding(
					"pass-rate-unknown",
					"qualityPassRate",
					"ALL",
					"review",
					baselineStats.qualityPassRate() != null ? baselineStats.qualityPassRate().toPlainString() : "n/a",
					"n/a",
					"candidate quality pass rate is unknown (no quality-evaluated cases)"));
		} else if (candidateStats.qualityPassRate().compareTo(thresholds.qualityPassRateFloor()) < 0) {
			findings.add(new GateFinding(
					"pass-rate-floor",
					"qualityPassRate",
					"ALL",
					"block",
					baselineStats.qualityPassRate() != null ? baselineStats.qualityPassRate().toPlainString() : "n/a",
					candidateStats.qualityPassRate().toPlainString(),
					"candidate quality pass rate " + candidateStats.qualityPassRate().toPlainString()
							+ " is below floor " + thresholds.qualityPassRateFloor().toPlainString()));
		}

		if (candidateStats.failRate() != null
				&& candidateStats.failRate().compareTo(thresholds.failRateCeiling()) > 0) {
			findings.add(new GateFinding(
					"fail-rate-ceiling",
					"failRate",
					"ALL",
					"block",
					baselineStats.failRate() != null ? baselineStats.failRate().toPlainString() : "n/a",
					candidateStats.failRate().toPlainString(),
					"candidate fail rate " + candidateStats.failRate().toPlainString()
							+ " exceeds ceiling " + thresholds.failRateCeiling().toPlainString()));
		}

		if (candidateStats.fidelity() == null) {
			findings.add(new GateFinding(
					"fidelity-unknown",
					"fidelity",
					"ALL",
					"review",
					baselineStats.fidelity() != null ? baselineStats.fidelity().toPlainString() : "n/a",
					"n/a",
					"candidate fidelity is unknown (no successes with complete fidelity measurements)"));
		}

		if (candidateStats.successCount() == 0) {
			findings.add(new GateFinding(
					"latency-unknown",
					"p50",
					"ALL",
					"review",
					baselineStats.successCount() > 0 ? String.valueOf(baselineStats.p50LatencyMs()) : "n/a",
					"n/a",
					"candidate latency is unknown (no successes)"));
			findings.add(new GateFinding(
					"latency-unknown",
					"p95",
					"ALL",
					"review",
					baselineP95 != null ? String.valueOf(baselineP95) : "n/a",
					"n/a",
					"candidate P95 latency is unknown (no successes)"));
		} else {
			if (candidateStats.p50LatencyMs() > thresholds.p50CeilingMs()) {
				findings.add(new GateFinding(
						"p50-ceiling",
						"p50",
						"ALL",
						"block",
						baselineStats.successCount() > 0 ? String.valueOf(baselineStats.p50LatencyMs()) : "n/a",
						String.valueOf(candidateStats.p50LatencyMs()),
						"candidate P50 latency " + candidateStats.p50LatencyMs() + " ms exceeds ceiling "
								+ thresholds.p50CeilingMs() + " ms"));
			}
			if (candidateP95 != null && candidateP95 > thresholds.p95CeilingMs()) {
				findings.add(new GateFinding(
						"p95-ceiling",
						"p95",
						"ALL",
						"block",
						baselineP95 != null ? String.valueOf(baselineP95) : "n/a",
						String.valueOf(candidateP95),
						"candidate P95 latency " + candidateP95 + " ms exceeds ceiling "
								+ thresholds.p95CeilingMs() + " ms"));
			}
		}

		// 6. Cost checks
		if (thresholds.costPerSuccessMaxIncreasePercent() != null) {
			if (baselineStats.costPerSuccess() == null || candidateStats.costPerSuccess() == null) {
				addFindingIfAbsent(findings, new GateFinding(
						"cost-incomparable",
						"costPerSuccess",
						"ALL",
						"review",
						baselineStats.costPerSuccess() != null ? baselineStats.costPerSuccess().toPlainString() : "n/a",
						candidateStats.costPerSuccess() != null ? candidateStats.costPerSuccess().toPlainString() : "n/a",
						"cost per success is incomparable (one or both arms have undefined or multi-currency cost)"));
			} else if (matchedPairs >= thresholds.minSampleSize()) {
				BigDecimal costDeltaPercent = percentDelta(baselineStats.costPerSuccess(), candidateStats.costPerSuccess());
				BigDecimal costTolerancePercent = thresholds.costPerSuccessMaxIncreasePercent().multiply(BigDecimal.valueOf(100));
				if (costDeltaPercent != null && costDeltaPercent.compareTo(costTolerancePercent) > 0) {
					addFindingIfAbsent(findings, new GateFinding(
							"cost-increase",
							"costPerSuccess",
							"ALL",
							"block",
							baselineStats.costPerSuccess().toPlainString(),
							candidateStats.costPerSuccess().toPlainString(),
							"cost per success increased by " + costDeltaPercent.toPlainString() + "% (tolerance "
									+ costTolerancePercent.toPlainString() + "%)"));
				}
			}
		}

		// 7. Regression checks or small sample
		if (matchedPairs < thresholds.minSampleSize()) {
			addFindingIfAbsent(findings, new GateFinding(
					"insufficient-sample",
					"coverage",
					"ALL",
					"review",
					"n/a",
					String.valueOf(matchedPairs),
					matchedPairs + " matched pairs is below minimum sample size " + thresholds.minSampleSize()));
		} else {
			// Overall fidelity regression
			BenchmarkRegressionComparator.DimensionDelta overallFidelityDelta = findDelta(report.dimensionDeltas(), "fidelity", "ALL");
			if (baselineStats.fidelity() == null) {
				addFindingIfAbsent(findings, new GateFinding(
						"fidelity-unknown",
						"fidelity",
						"ALL",
						"review",
						"n/a",
						candidateStats.fidelity() != null ? candidateStats.fidelity().toPlainString() : "n/a",
						"baseline fidelity is unknown"));
			} else if (candidateStats.fidelity() != null && overallFidelityDelta != null && overallFidelityDelta.percent() != null) {
				BigDecimal fidelityTol = thresholds.fidelityMaxRegressionPercent().multiply(BigDecimal.valueOf(100));
				if (overallFidelityDelta.percent().compareTo(fidelityTol.negate()) < 0) {
					addFindingIfAbsent(findings, new GateFinding(
							"fidelity-regression",
							"fidelity",
							"ALL",
							"block",
							baselineStats.fidelity().toPlainString(),
							candidateStats.fidelity().toPlainString(),
							"overall fidelity regressed by " + overallFidelityDelta.percent().toPlainString()
									+ "% (tolerance " + fidelityTol.toPlainString() + "%)"));
				}
			}

			// Overall identity regression
			if (baselineIdentityMean == null || candidateIdentityMean == null) {
				addFindingIfAbsent(findings, new GateFinding(
						"fidelity-unknown",
						"identity",
						"ALL",
						"review",
						baselineIdentityMean != null ? baselineIdentityMean.toPlainString() : "n/a",
						candidateIdentityMean != null ? candidateIdentityMean.toPlainString() : "n/a",
						"identity fidelity is unknown (one or both arms have no complete identity measurements)"));
			} else {
				BigDecimal identityDeltaPercent = percentDelta(baselineIdentityMean, candidateIdentityMean);
				if (identityDeltaPercent != null) {
					BigDecimal identityTol = thresholds.identityMaxRegressionPercent().multiply(BigDecimal.valueOf(100));
					if (identityDeltaPercent.compareTo(identityTol.negate()) < 0) {
						addFindingIfAbsent(findings, new GateFinding(
								"identity-regression",
								"identity",
								"ALL",
								"block",
								baselineIdentityMean.toPlainString(),
								candidateIdentityMean.toPlainString(),
								"identity fidelity regressed by " + identityDeltaPercent.toPlainString()
										+ "% (tolerance " + identityTol.toPlainString() + "%)"));
					}
				}
			}

			// Overall P50 regression
			if (baselineStats.successCount() == 0 || candidateStats.successCount() == 0) {
				addFindingIfAbsent(findings, new GateFinding(
						"latency-unknown",
						"p50",
						"ALL",
						"review",
						baselineStats.successCount() > 0 ? String.valueOf(baselineStats.p50LatencyMs()) : "n/a",
						candidateStats.successCount() > 0 ? String.valueOf(candidateStats.p50LatencyMs()) : "n/a",
						"P50 latency is unknown (no successes in one or both arms)"));
			} else {
				BigDecimal p50DeltaPercent = percentDelta(
						BigDecimal.valueOf(baselineStats.p50LatencyMs()),
						BigDecimal.valueOf(candidateStats.p50LatencyMs()));
				if (p50DeltaPercent != null) {
					BigDecimal p50Tol = thresholds.p50MaxRegressionPercent().multiply(BigDecimal.valueOf(100));
					if (p50DeltaPercent.compareTo(p50Tol) > 0) {
						addFindingIfAbsent(findings, new GateFinding(
								"p50-regression",
								"p50",
								"ALL",
								"block",
								String.valueOf(baselineStats.p50LatencyMs()),
								String.valueOf(candidateStats.p50LatencyMs()),
								"P50 latency increased by " + p50DeltaPercent.toPlainString()
										+ "% (tolerance " + p50Tol.toPlainString() + "%)"));
					}
				}
			}

			// Overall P95 regression
			if (baselineP95 == null || candidateP95 == null) {
				addFindingIfAbsent(findings, new GateFinding(
						"latency-unknown",
						"p95",
						"ALL",
						"review",
						baselineP95 != null ? String.valueOf(baselineP95) : "n/a",
						candidateP95 != null ? String.valueOf(candidateP95) : "n/a",
						"P95 latency is unknown (no successes in one or both arms)"));
			} else {
				BigDecimal p95DeltaPercent = percentDelta(
						BigDecimal.valueOf(baselineP95),
						BigDecimal.valueOf(candidateP95));
				if (p95DeltaPercent != null) {
					BigDecimal p95Tol = thresholds.p95MaxRegressionPercent().multiply(BigDecimal.valueOf(100));
					if (p95DeltaPercent.compareTo(p95Tol) > 0) {
						addFindingIfAbsent(findings, new GateFinding(
								"p95-regression",
								"p95",
								"ALL",
								"block",
								String.valueOf(baselineP95),
								String.valueOf(candidateP95),
								"P95 latency increased by " + p95DeltaPercent.toPlainString()
										+ "% (tolerance " + p95Tol.toPlainString() + "%)"));
					}
				}
			}

			// Overall fail-rate increase
			BenchmarkRegressionComparator.DimensionDelta overallFailRateDelta = findDelta(report.dimensionDeltas(), "failRate", "ALL");
			if (overallFailRateDelta != null && overallFailRateDelta.absolute() != null
					&& overallFailRateDelta.absolute().compareTo(thresholds.failRateMaxIncreaseAbsolute()) > 0) {
				addFindingIfAbsent(findings, new GateFinding(
						"fail-rate-increase",
						"failRate",
						"ALL",
						"block",
						baselineStats.failRate() != null ? baselineStats.failRate().toPlainString() : "n/a",
						candidateStats.failRate() != null ? candidateStats.failRate().toPlainString() : "n/a",
						"fail rate increased by " + overallFailRateDelta.absolute().toPlainString()
								+ " (tolerance " + thresholds.failRateMaxIncreaseAbsolute().toPlainString() + ")"));
			}

			// Per-category regressions
			Map<String, List<BenchmarkCaseResult>> baselineByCategory = groupByCategory(baseline);
			Map<String, List<BenchmarkCaseResult>> candidateByCategory = groupByCategory(candidate);

			for (BenchmarkRegressionComparator.DimensionDelta delta : report.dimensionDeltas()) {
				String category = delta.category();
				if ("ALL".equals(category)) {
					continue;
				}
				String dimension = delta.dimension();
				if ("qualityPassRate".equals(dimension)) {
					continue;
				}

				BenchmarkMetrics.Stats catBaselineStats = BenchmarkMetrics.of(baselineByCategory.getOrDefault(category, List.of()));
				BenchmarkMetrics.Stats catCandidateStats = BenchmarkMetrics.of(candidateByCategory.getOrDefault(category, List.of()));

				if ("fidelity".equals(dimension)) {
					BigDecimal fidelityTol = thresholds.fidelityMaxRegressionPercent().multiply(BigDecimal.valueOf(100));
					if (delta.percent() != null && delta.percent().compareTo(fidelityTol.negate()) < 0) {
						addFindingIfAbsent(findings, new GateFinding(
								"category-regression",
								"fidelity",
								category,
								"review",
								catBaselineStats.fidelity() != null ? catBaselineStats.fidelity().toPlainString() : "n/a",
								catCandidateStats.fidelity() != null ? catCandidateStats.fidelity().toPlainString() : "n/a",
								category + " fidelity regressed by " + delta.percent().toPlainString()
										+ "% (tolerance " + fidelityTol.toPlainString() + "%)"));
					}
				} else if ("p50LatencyMs".equals(dimension)) {
					BigDecimal p50Tol = thresholds.p50MaxRegressionPercent().multiply(BigDecimal.valueOf(100));
					if (delta.percent() != null && delta.percent().compareTo(p50Tol) > 0) {
						addFindingIfAbsent(findings, new GateFinding(
								"category-regression",
								"p50",
								category,
								"review",
								catBaselineStats.successCount() > 0 ? String.valueOf(catBaselineStats.p50LatencyMs()) : "n/a",
								catCandidateStats.successCount() > 0 ? String.valueOf(catCandidateStats.p50LatencyMs()) : "n/a",
								category + " P50 latency increased by " + delta.percent().toPlainString()
										+ "% (tolerance " + p50Tol.toPlainString() + "%)"));
					}
				} else if ("failRate".equals(dimension)) {
					if (delta.absolute() != null && delta.absolute().compareTo(thresholds.failRateMaxIncreaseAbsolute()) > 0) {
						addFindingIfAbsent(findings, new GateFinding(
								"category-regression",
								"failRate",
								category,
								"review",
								catBaselineStats.failRate() != null ? catBaselineStats.failRate().toPlainString() : "n/a",
								catCandidateStats.failRate() != null ? catCandidateStats.failRate().toPlainString() : "n/a",
								category + " fail rate increased by " + delta.absolute().toPlainString()
										+ " (tolerance " + thresholds.failRateMaxIncreaseAbsolute().toPlainString() + ")"));
					}
				} else if ("costPerSuccess".equals(dimension)) {
					if (thresholds.costPerSuccessMaxIncreasePercent() != null && delta.percent() != null) {
						BigDecimal costTol = thresholds.costPerSuccessMaxIncreasePercent().multiply(BigDecimal.valueOf(100));
						if (delta.percent().compareTo(costTol) > 0) {
							addFindingIfAbsent(findings, new GateFinding(
									"category-regression",
									"costPerSuccess",
									category,
									"review",
									catBaselineStats.costPerSuccess() != null ? catBaselineStats.costPerSuccess().toPlainString() : "n/a",
									catCandidateStats.costPerSuccess() != null ? catCandidateStats.costPerSuccess().toPlainString() : "n/a",
									category + " cost per success increased by " + delta.percent().toPlainString()
											+ "% (tolerance " + costTol.toPlainString() + "%)"));
						}
					}
				}
			}
		}

		// 8. Verdict determination
		boolean hasBlock = findings.stream().anyMatch(f -> "block".equals(f.severity()));
		boolean hasReview = findings.stream().anyMatch(f -> "review".equals(f.severity()));

		ReleaseGateVerdict verdict;
		if (hasBlock) {
			verdict = ReleaseGateVerdict.BLOCK;
		} else if (hasReview) {
			verdict = ReleaseGateVerdict.REVIEW;
		} else {
			verdict = ReleaseGateVerdict.PASS;
		}

		return new ReleaseGateDecision(verdict, benchmarkVersion, matchedPairs, findings);
	}

	private static void addFindingIfAbsent(List<GateFinding> findings, GateFinding candidateFinding) {
		for (GateFinding f : findings) {
			if (f.rule().equals(candidateFinding.rule())
					&& f.dimension().equals(candidateFinding.dimension())
					&& f.category().equals(candidateFinding.category())) {
				return;
			}
		}
		findings.add(candidateFinding);
	}

	private static BenchmarkRegressionComparator.DimensionDelta findDelta(
			List<BenchmarkRegressionComparator.DimensionDelta> deltas, String dimension, String category) {
		for (BenchmarkRegressionComparator.DimensionDelta delta : deltas) {
			if (delta.dimension().equals(dimension) && delta.category().equals(category)) {
				return delta;
			}
		}
		return null;
	}

	private static BigDecimal identityMean(List<BenchmarkCaseResult> results) {
		List<BigDecimal> identities = new ArrayList<>();
		for (BenchmarkCaseResult result : results) {
			if (result.outcome() == Outcome.SUCCESS
					&& result.identityFidelity() != null
					&& result.garmentFidelity() != null
					&& result.colorFidelity() != null) {
				identities.add(result.identityFidelity());
			}
		}
		if (identities.isEmpty()) {
			return null;
		}
		BigDecimal sum = BigDecimal.ZERO;
		for (BigDecimal val : identities) {
			sum = sum.add(val);
		}
		return sum.divide(BigDecimal.valueOf(identities.size()), SCALE, ROUNDING);
	}

	private static Long nearestRankP95(List<BenchmarkCaseResult> results) {
		List<Long> latencies = new ArrayList<>();
		for (BenchmarkCaseResult result : results) {
			if (result.outcome() == Outcome.SUCCESS) {
				latencies.add(result.latencyMs());
			}
		}
		if (latencies.isEmpty()) {
			return null;
		}
		latencies.sort(Long::compareTo);
		int n = latencies.size();
		int rank = (int) Math.ceil(0.95 * n);
		if (rank < 1) {
			rank = 1;
		}
		if (rank > n) {
			rank = n;
		}
		return latencies.get(rank - 1);
	}

	private static BigDecimal percentDelta(BigDecimal baselineValue, BigDecimal candidateValue) {
		if (baselineValue == null || candidateValue == null || baselineValue.signum() == 0) {
			return null;
		}
		return candidateValue.subtract(baselineValue)
				.divide(baselineValue, SCALE + 2, ROUNDING)
				.multiply(BigDecimal.valueOf(100))
				.setScale(SCALE, ROUNDING);
	}

	private static Map<String, List<BenchmarkCaseResult>> groupByCategory(List<BenchmarkCaseResult> results) {
		Map<String, List<BenchmarkCaseResult>> grouped = new TreeMap<>();
		for (BenchmarkCaseResult result : results) {
			grouped.computeIfAbsent(result.category(), ignored -> new ArrayList<>()).add(result);
		}
		return grouped;
	}
}
