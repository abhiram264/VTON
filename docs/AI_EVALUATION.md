# AI Evaluation & Benchmark Release Gate

**Status:** IMPLEMENTED by slice 52 (release gate shipped).

This document is the canonical home for AI Fashion Vault evaluation of virtual try-on (VTON) quality, cost, and provider behavior.

## Historical note

Status: deferred.

Before slice 52, this document was a reserved repository stub. With slice 52, the offline evaluation framework and `BenchmarkReleaseGate` are fully implemented. Removing `docs/AI_EVALUATION.md` from the `repo-sanity.yml` stubs list is recorded as a follow-up deferred to whichever future slice owns that workflow.

## Binding Release Policy: The No-Demo Rule

Virtual try-on quality cannot be evaluated by anecdotal sampling or visual impressions of selected outputs. Plan §52 establishes the core binding release policy:

> "Do not deploy a model solely because one demo looks better."

A single striking demonstration is not release evidence. No model, provider, prompt, evaluator, or routing change may be promoted to production without passing the systematic benchmark evaluation procedure documented here.

## Mandatory §52 Trigger List

Running the old-vs-new benchmark gate evaluation is mandatory whenever any of the five AI-adjacent components change.

Verbatim from Plan §52:

Whenever:

```text
provider changes
provider version changes
prompt changes
quality evaluator changes
routing changes
```

run old vs new.

No change touching these areas may bypass the release gate. Treating a modification as "just a prompt tweak" or "just a provider minor version bump" is strictly prohibited.

## Fixed Benchmark Set

Evaluation is anchored to a standardized, frozen benchmark dataset:

- **Composition**: 50 synthetic avatars × 100 garment items.
- **Manifest Version**: `2026.01.0` (`backend/src/main/resources/benchmark/manifest.json`).
- **Privacy & Licensing**: All avatar and garment assets are synthetic CC0-1.0 licensed fixtures generated specifically for offline benchmark use. No real person's likeness, personal data, or proprietary garment imagery enters the evaluation pipeline.
- **Category Distribution (7 categories, 100 items total)**:
  - `tops`: 22 items
  - `bottoms`: 18 items
  - `dresses`: 16 items
  - `kurtas`: 12 items
  - `lehenga sets`: 10 items
  - `footwear`: 12 items
  - `headwear`: 10 items

## Execution & Run Semantics

1. **Deterministic CI Execution**: By default in CI and local test builds, evaluation runs against a deterministic offline fake provider (`BenchmarkFakes.ScriptedVtonProvider`), a scripted quality gate (`ScriptedQualityGate`), and a stepped clock. This ensures tests run in seconds without network calls, Docker containers, or cloud provider credentials.
2. **Opt-In Live Provider Execution**: Live provider runs against external AI endpoints are strictly opt-in, executed only in dedicated evaluation environments with explicit credentials. Live provider calls are NEVER executed during standard test commands or `mvnw verify`.
3. **Fixture Manifest Dry-Run Validation**: Before invoking any provider (fake or live), the test harness executes fixture manifest dry-run validation to confirm asset file presence, category counts, and manifest schema compliance.

## Metric Definitions

The gate measures six core dimensions specified in Plan §52:

```text
fidelity
identity
latency
cost
failure rate
quality pass rate
```

- **fidelity**: Mean of per-case averages of identity, garment, and color fidelity over quality-approved successful cases. It measures technical try-on consistency and artifact avoidance—never human attractiveness or subjective styling.
- **identity**: Mean of per-case identity fidelity over quality-approved successful cases, measuring preservation of avatar facial features, skin tone, and body geometry.
- **failure rate (`failRate`)**: Ratio of non-successful attempts to total sample count ($\text{nonSuccessCount} / \text{sampleCount}$) computed over all executed cases.
- **quality pass rate (`qualityPassRate`)**: Ratio of successful cases to cases that reached a quality decision ($\text{successCount} / \text{qualityEvaluatedCount}$). Cases failing transport before reaching quality evaluation do not dilute the quality evaluator denominator.
- **cost per success (`costPerSuccess`)**: Total known attempt cost divided by successful cases ($\text{totalCost} / \text{successCount}$). Yields `null` (never zero) when costs are undefined or span multiple incompatible currencies.
- **latency P50**: Nearest-rank median over successful generation latencies (in milliseconds).
- **latency P95**: Nearest-rank 95th percentile latency computed as $\lceil 0.95 \times n \rceil$ over sorted successful generation latencies (in milliseconds), evaluated directly inside the release gate.

## Old-vs-New Gate Procedure

1. **Pin Version**: Both arms (baseline and candidate) are pinned to the exact same frozen `benchmarkVersion`. The underlying comparator structurally enforces version parity and fails closed (`IllegalArgumentException`) on any mismatch.
2. **Execute Baseline Arm**: Generate or load `List<BenchmarkCaseResult> baseline` representing the current production configuration.
3. **Execute Candidate Arm**: Generate `List<BenchmarkCaseResult> candidate` representing the proposed change.
4. **Evaluate Gate**: Call `BenchmarkReleaseGate.decide(baseline, candidate, benchmarkVersion, thresholds)`.
5. **Inspect Verdict & Findings**: The gate returns a deterministic `ReleaseGateDecision` containing the final verdict (`PASS`, `BLOCK`, or `REVIEW`), matched pair count, and an ordered list of scalar `GateFinding` records.
6. **Human Promotion Authority**: A `PASS` verdict provides necessary empirical evidence for release, but is NEVER an auto-deploy mechanism. Promotion remains a human decision made by the release engineer and product owner after reviewing findings.

## Verdict Vocabulary & Semantics

The gate emits exactly one tri-state verdict:

- **`PASS`**: All checks satisfied. Zero block findings, zero review findings, and at least one matched pair ($\ge 1$). Provides evidence supporting human promotion.
- **`BLOCK`**: Hard block. The candidate violates an absolute quality/reliability floor or latency ceiling, or exhibits an overall regression beyond tolerance with an adequate sample. Deployment is halted.
- **`REVIEW`**: Human adjudication required. Triggered by missing case pairs, zero matched pairs, sample size below minimum threshold, evaluator unavailable outcomes, fixture invalid outcomes, unknown/incomparable metrics, or per-category regressions.
- **Precedence**: `BLOCK` takes strict precedence over `REVIEW`. If any block-severity finding is present, the verdict is `BLOCK` regardless of the number of review-severity findings.

## Release Gate Thresholds

Thresholds are passed explicitly to the gate via `ReleaseGateThresholds`. Starting values (`ReleaseGateThresholds.defaults()`) are anchored to the Plan §23 vertical slice targets:

- Candidate quality pass rate floor `0.90` (anchored to §23: Generation technical success > 90% and Quality-approved results > 85%).
- Candidate fail rate ceiling `0.10` (the 10% complement of the 90% technical success floor).
- Candidate P50 ceiling `15,000 ms` (anchored to §23: P50 successful Look < 15 sec).
- Candidate P95 ceiling `30,000 ms` (anchored to §23: P95 successful Look < 30 sec).

### Threshold Reference Table

| Field | Type | Starting Value | Unit / Range | Anchoring & Meaning |
|---|---|---|---|---|
| `fidelityMaxRegressionPercent` | `BigDecimal` | `0.02` | $[0, 1]$ (2%) | Max tolerated relative regression in overall fidelity |
| `identityMaxRegressionPercent` | `BigDecimal` | `0.01` | $[0, 1]$ (1%) | Tighter tolerance for identity regression (wrong person is critical failure) |
| `p50MaxRegressionPercent` | `BigDecimal` | `0.05` | $[0, 1]$ (5%) | Max tolerated relative increase in P50 latency |
| `p95MaxRegressionPercent` | `BigDecimal` | `0.10` | $[0, 1]$ (10%) | Max tolerated relative increase in P95 latency |
| `failRateCeiling` | `BigDecimal` | `0.10` | $[0, 1]$ (10%) | Absolute candidate overall fail-rate ceiling (Plan §23 complement) |
| `failRateMaxIncreaseAbsolute` | `BigDecimal` | `0.02` | $[0, 1]$ (2%) | Max tolerated absolute increase in failure rate |
| `qualityPassRateFloor` | `BigDecimal` | `0.90` | $[0, 1]$ (90%) | Absolute candidate quality pass-rate floor (Plan §23) |
| `costPerSuccessMaxIncreasePercent` | `BigDecimal` | `0.10` | $[0, 1]$ (10%) | Max tolerated relative cost-per-success increase (nullable; null disables rule) |
| `p50CeilingMs` | `long` | `15000` | Milliseconds (> 0) | Absolute candidate P50 latency ceiling (Plan §23 < 15 s) |
| `p95CeilingMs` | `long` | `30000` | Milliseconds (> 0) | Absolute candidate P95 latency ceiling (Plan §23 < 30 s) |
| `minSampleSize` | `long` | `30` | Count ($\ge 1$) | Minimum matched pairs required to evaluate overall regressions for BLOCK |

### Tuning Guidance

- **Empirical Evolution**: These starting thresholds represent initial engineering policy anchored to Plan §23. They must be tuned over time based on observed gate-run history across production releases.
- **Identity Priority**: Always tighten identity tolerance first. Delivering try-on output featuring the wrong face or distorted body geometry is the most damaging user-facing failure mode in a personal-avatar platform.
- **No Floor Weakening**: Never lower pass rate floors or raise latency ceilings simply to push a lagging model candidate through. If a candidate model fails starting thresholds, improve prompt conditioning, adjust routing, or reject the candidate.

## Rule ID Catalog

### Block-Severity Rules (`severity: "block"`)

1. `pass-rate-floor`: Candidate quality pass rate is below `qualityPassRateFloor`.
2. `fail-rate-ceiling`: Candidate fail rate exceeds `failRateCeiling`.
3. `p50-ceiling`: Candidate P50 latency exceeds `p50CeilingMs`.
4. `p95-ceiling`: Candidate P95 latency exceeds `p95CeilingMs`.
5. `fidelity-regression`: Candidate overall fidelity regressed by more than `fidelityMaxRegressionPercent` relative to baseline.
6. `identity-regression`: Candidate identity fidelity regressed by more than `identityMaxRegressionPercent` relative to baseline.
7. `p50-regression`: Candidate P50 latency increased by more than `p50MaxRegressionPercent` relative to baseline.
8. `p95-regression`: Candidate P95 latency increased by more than `p95MaxRegressionPercent` relative to baseline.
9. `fail-rate-increase`: Candidate fail rate increased by more than `failRateMaxIncreaseAbsolute` absolute delta compared to baseline.
10. `cost-increase`: Candidate cost per success increased by more than `costPerSuccessMaxIncreasePercent` relative to baseline.

### Review-Severity Rules (`severity: "review"`)

1. `category-regression`: Relative regression or latency increase exceeded tolerance in a specific benchmark category (e.g. footwear, headwear); routed to review to prevent small per-category sample sizes from hard-blocking releases on statistical noise.
2. `missing-pairs`: Cases exist in baseline that are missing from candidate, or vice versa, indicating an incomplete run.
3. `no-matched-pairs`: Zero matched case pairs found between baseline and candidate arms.
4. `insufficient-sample`: Total matched pairs is less than `minSampleSize`, preventing confident regression evaluation.
5. `evaluator-unavailable`: Candidate cases resulted in `EVALUATOR_UNAVAILABLE` outcomes, indicating evaluation infrastructure failure rather than candidate quality issues.
6. `fixture-invalid`: Candidate cases resulted in `FIXTURE_INVALID` outcomes, indicating fixture corruption or benchmark harness configuration errors.
7. `cost-incomparable`: Cost comparison cannot be performed because one or both arms have undefined costs or multi-currency records.
8. `fidelity-unknown`: Fidelity or identity measurements are unknown for candidate or baseline (e.g. no quality-approved successes).
9. `pass-rate-unknown`: Candidate quality pass rate is unknown because no cases reached a quality evaluation decision.
10. `latency-unknown`: Latency measurements are unknown because candidate or baseline produced zero successful cases.

## Small-Sample Statistics Guidance for Humans Adjudicating REVIEW

The gate code intentionally executes no automated significance tests; comparisons with small samples route to `REVIEW` so a human engineer can evaluate context. Adjudicators should apply the following standard statistical methods:

### 1. Wilson Score Interval for Small-$n$ Pass Rates

Standard normal approximation confidence intervals ($\hat{p} \pm z \sqrt{\hat{p}(1-\hat{p})/n}$) break down when $n$ is small or $p$ is near 0 or 1. Use the Wilson score interval:

$$\text{Center} = \frac{p + \frac{z^2}{2n}}{1 + \frac{z^2}{n}}, \quad \text{Half-width} = \frac{z \sqrt{\frac{p(1-p)}{n} + \frac{z^2}{4n^2}}}{1 + \frac{z^2}{n}}$$

For a 95% two-sided confidence interval, use $z = 1.96$. If the candidate pass rate's lower Wilson bound remains above the acceptable product floor, the review finding can be safely resolved.

### 2. One-Sided Non-Inferiority Framing

Do not frame release evaluation as a two-sided test of difference ("is candidate different from baseline?"). The release gate evaluates one-sided non-inferiority:

$$\text{Hypothesis: } H_0: \mu_{\text{candidate}} - \mu_{\text{baseline}} < -\delta \quad \text{vs.} \quad H_1: \mu_{\text{candidate}} - \mu_{\text{baseline}} \ge -\delta$$

where $\delta$ is the maximum tolerated regression threshold. A candidate is acceptable if the lower bound of the confidence interval for the difference exceeds $-\delta$.

### 3. Right-Skewed Latencies and P95 Tail

In generative AI inference pipelines, latencies follow heavy-tailed, right-skewed distributions due to queue waiting times, GPU contention, and retries. P50 (median) can remain completely flat while P95 degrades by tens of seconds. Adjudicators must never dismiss P95 latency review findings based on a healthy P50.

### 4. Mann–Whitney U Procedure for Latency Comparison

When comparing latency distributions between baseline ($n_1$) and candidate ($n_2$) for near-miss REVIEW findings, do not assume normal distribution. Use the non-parametric Mann–Whitney U test:

1. Pool all latency observations from both arms and rank them in ascending order from $1$ to $N = n_1 + n_2$ (assigning average ranks to tied values).
2. Calculate the rank sums $R_1$ (baseline) and $R_2$ (candidate).
3. Compute $U$ statistics:
   $$U_1 = n_1 n_2 + \frac{n_1(n_1 + 1)}{2} - R_1, \quad U_2 = n_1 n_2 + \frac{n_2(n_2 + 1)}{2} - R_2$$
4. For $n_1, n_2 > 20$, use the normal approximation:
   $$\mu_U = \frac{n_1 n_2}{2}, \quad \sigma_U = \sqrt{\frac{n_1 n_2 (n_1 + n_2 + 1)}{12}}, \quad Z = \frac{U_2 - \mu_U}{\sigma_U}$$
   Evaluate $Z$ against standard normal critical values to determine whether latency degradation is statistically significant.

## Benchmark-Set Rotation Policy

- **Contamination Mitigation**: Fixed evaluation sets suffer from risk of contamination when providers or prompts gradually overfit to specific test avatars and garments.
- **Version Bump Mechanics**: To rotate or refresh the set, publish an updated manifest with an incremented `benchmarkVersion` (e.g. `2026.02.0`).
- **Fail-Closed Enforcement**: `BenchmarkRegressionComparator` immediately fails closed with `IllegalArgumentException` if baseline and candidate versions do not match. A version bump structurally prevents comparisons across disparate fixture sets.
- **Set Privacy**: Fixture assets and prompt pairings must remain strictly internal and never be published to public endpoints or external providers.
- **Re-Baselining Requirement**: Whenever the benchmark set rotates, a baseline run must be executed on the new version before candidate comparisons can resume.

## Privacy Constraints (ADR-015)

In compliance with ADR-015 (Raw images excluded from telemetry):

- Release gate decisions, findings, and JSON reports contain scalar values, opaque IDs, categories, and rule names only.
- No gate report or serialized record may EVER contain:
  - Raw image bytes or base64 encoded media
  - Public or presigned image URLs
  - Prompt text or generation prompt bodies
  - Exception stack traces or provider error response bodies
  - User IDs, account IDs, or tenant identities
  - Provider API keys, credentials, or secrets
- The evaluation subsystem operates strictly offline with zero user tenant context.

## Serialized Release Record

Release decisions are serialized to archivable, privacy-safe JSON using `BenchmarkReportJson.serialize(ReleaseGateDecision)`. The resulting payload records the exact release outcome without leaking media or credentials:

```json
{
  "verdict": "PASS",
  "benchmarkVersion": "2026.01.0",
  "matchedPairs": 100,
  "findings": []
}
```

When findings are present, each entry contains deterministic scalar fields:

```json
{
  "verdict": "BLOCK",
  "benchmarkVersion": "2026.01.0",
  "matchedPairs": 100,
  "findings": [
    {
      "rule": "identity-regression",
      "dimension": "identity",
      "category": "ALL",
      "severity": "block",
      "baselineValue": "0.940000",
      "candidateValue": "0.920000",
      "detail": "identity fidelity regressed by 2.127660% (tolerance 1.00%)"
    }
  ]
}
```

This JSON record constitutes the permanent, auditable verification artifact for production release approvals.
