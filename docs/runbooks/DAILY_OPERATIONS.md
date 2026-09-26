# Daily Post-Production Operations Runbook

This runbook defines the daily operational inspection procedure for AI Fashion Vault in production per technical implementation plan §70.

Every day, the on-call engineer inspects nine core production health signals in fixed order. The inspection evaluates telemetry from the preceding UTC calendar day against the §65 beta exit gates, identifies degradation early, and routes actionable failures through the §68 alerting escalation ladder and §66 remote feature-flag kill-switches.

## Inspection Window

The standard inspection window covers the previous completed UTC calendar day:

```text
[date_trunc('day', now()) - interval '1 day', date_trunc('day', now()))
```

When executing SQL queries, bind `:day_start` and `:day_end` to the boundary timestamps:

```bash
# Example psql execution using bind parameters for 2026-09-05 UTC:
psql "$DATABASE_URL" \
    -v day_start="'2026-09-05 00:00:00+00'" \
    -v day_end="'2026-09-06 00:00:00+00'" \
    -v min_samples=20
```

Alternatively, use dynamic SQL expressions `date_trunc('day', now()) - interval '1 day'` and `date_trunc('day', now())` directly.

---

## 1. crashes
Source: AVAILABLE NOW (repo) - Google Play Console (Android vitals) + Apple App Store Connect (Analytics → Crashes) + Xcode Organizer
(Client crash inspection stays manual and console-based because the mobile app ships no third-party crash SDK; Play Console and App Store Connect first-party surfaces are the authoritative daily source; no slice adds automated client crash alerting.)
Command:
Console navigation paths:
1. Android: Google Play Console → Select App → **Monitor and improve** → **Android vitals** → **Overview** and **Crashes and ANRs**. Inspect: Crash rate (daily active users / DAU), User-perceived crash rate, Multiple-crash rate, and leading crash clusters. Freshness: daily batch update; Android 10+ data can precede older OS versions.
2. iOS: Apple App Store Connect → Select App → **Analytics** → **Crashes**. Inspect: Crashes per session, total crash count, unique devices affected. Freshness: daily update, full completeness within five days.
3. iOS Diagnostics: Xcode → **Window** → **Organizer** → **Crashes**. Inspect symbolicated backtraces for current production builds.

Note on SDK architecture: The mobile client intentionally ships no third-party crash SDK (no Firebase Crashlytics, Sentry, or MetricKit in `mobile/`). Rates are drawn from first-party store telemetry with opt-in sampling.
Healthy:
- Target: Crash-free sessions > 99.5% (tied to §65 beta exit gate: "Crash-free sessions > 99.5%").
- Amber band: Crash-free sessions between 99.0% and 99.5% (within 10% relative tolerance of §65 gate), or >2x day-over-day increase in daily crash count or crash rate.
- Red band: Crash-free sessions <= 99.0% (breaching §65 gate), user-perceived crash rate > 1.09% (Play bad behavior threshold), or any crash cluster occurring on app launch or authentication (P0).
Triage:
1. Identify the top 3 crash clusters and determine affected app version and build numbers.
2. Check OS distribution to verify whether crashes are isolated to a new OS release (e.g., Android 15 or iOS 18).
3. Inspect stack traces in Xcode Organizer and Play Console to isolate native module faults vs JavaScript runtime exceptions.
4. Cross-reference crash spike timestamps with recent mobile staged rollout advances or backend releases.
5. Check if crashes concentrate in specific native flows: share-sheet ingestion, camera preflight, or outfit studio render.
Escalation:
- Severity: P0 if app crashes on launch or authentication for all users (§68); P1 if crash-free sessions drop below 99.0% on current release.
- Responding role: Mobile On-Call Engineer + Engineering Lead.
- First mitigation: If crash correlates with a new app version, halt staged rollout in Play Console / App Store Connect immediately; if crash is triggered by an AI studio/share feature, invoke §66 feature flag kill-switch via backend config (`MULTI_ITEM=false`, `SHARE_IMPORT_ANDROID=false`, or `SHARE_IMPORT_IOS=false`).
- Automation note: Host-health alarm definitions are AVAILABLE NOW (repo) in `infra/alerts/alarms-p0.tf`; AWS applied/deployment state is not verified by repository source. Client crash alerting is not automated, so open a manual P0/P1 incident referencing runbook Section 1.

---

## 2. import failures
Source: AVAILABLE NOW (repo) - PostgreSQL share_import table, Micrometer meter product.share.import.success (parts numerator/denominator), SourceResolverMetrics (share.import.resolver.outcome / .duration), ObservationFacade span share.resolve | Alarm definition AVAILABLE NOW (repo): `<prefix>-p2-import-resolver-degraded-<source>`
Command:
```sql
-- 1. Daily share import volume, success rate, and terminal failure breakdown
SELECT
    COUNT(*) AS total_imports,
    COUNT(*) FILTER (WHERE resolver_state = 'READY') AS successful_imports,
    COUNT(*) FILTER (WHERE resolver_state = 'NEEDS_IMAGE') AS needs_image_imports,
    COUNT(*) FILTER (WHERE resolver_state = 'RESOLVING') AS in_flight_imports,
    COUNT(*) FILTER (WHERE resolver_state = 'FAILED_RETRYABLE') AS retryable_failures,
    COUNT(*) FILTER (WHERE resolver_state = 'FAILED_TERMINAL') AS terminal_failures,
    ROUND(100.0 * COUNT(*) FILTER (WHERE resolver_state = 'READY') / NULLIF(COUNT(*), 0), 2) AS success_rate_pct,
    ROUND(100.0 * COUNT(*) FILTER (WHERE resolver_state = 'FAILED_TERMINAL') / NULLIF(COUNT(*), 0), 2) AS terminal_failure_pct
FROM share_import
WHERE created_at >= :day_start AND created_at < :day_end;

-- 2. Terminal failures grouped by source_kind and terminal_reason_code
SELECT
    source_kind,
    COALESCE(terminal_reason_code, 'UNSPECIFIED') AS terminal_reason_code,
    COUNT(*) AS failure_count,
    ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (), 2) AS pct_of_terminal_failures
FROM share_import
WHERE created_at >= :day_start AND created_at < :day_end
  AND resolver_state = 'FAILED_TERMINAL'
GROUP BY source_kind, terminal_reason_code
ORDER BY failure_count DESC;
```
Vocabulary note: Valid `resolver_state` values are `RESOLVING`, `READY`, `NEEDS_IMAGE`, `FAILED_RETRYABLE`, `FAILED_TERMINAL`, `DELETED`. Valid `terminal_reason_code` values are `URL_RESOLUTION_FAILED`, `DEPENDENCY_UNAVAILABLE`, `RESOLUTION_TIMEOUT`, `INVALID_SOURCE`.
Healthy:
- Target: Import success rate > 90% (tied to §65 beta exit gate: "Import success > 90%").
- Amber band: Success rate between 85% and 90% (within 10% relative tolerance of §65 gate), or >2x day-over-day surge in `DEPENDENCY_UNAVAILABLE` or `RESOLUTION_TIMEOUT`.
- Red band: Import success rate < 85% overall, or specific major source domain failure rate > 30% with volume >= 20 (§68 P2 condition: "one retailer/source import degraded"), or total failure of share ingestion.
Triage:
1. Check whether failures are concentrated in `URL` imports vs `IMAGE` or `IMAGE_URL` uploads.
2. If `DEPENDENCY_UNAVAILABLE` or `RESOLUTION_TIMEOUT` spikes, verify outbound network egress and resolver DNS/proxy health.
3. If `URL_RESOLUTION_FAILED` dominates, review Section 9 for specific retailer domain bot-blocking or page schema changes.
4. Check `FAILED_RETRYABLE` count to determine if worker retries are backlogged or exhausting attempts without resolving.
5. Cross-reference with Micrometer meter `product.share.import.success` tags for numerator/denominator drift.
Escalation:
- Severity: P2 if single source or retailer is degraded (§68); P1 if global import success drops below 85%; P0 if all share imports fail.
- Responding role: Backend On-Call Engineer.
- First mitigation: If failure is platform-specific, remotely disable share ingestion via §66 feature flag `SHARE_IMPORT_ANDROID=false` or `SHARE_IMPORT_IOS=false` to route users to direct in-app photo upload.
- Automation note: Alarm definition is AVAILABLE NOW (repo) in `infra/alerts/alarms-p2.tf`; AWS applied/deployment state is not verified by repository source. Open a manual P2 incident if the deployed alarm is absent or inactive.

---

## 3. generation failures
Source: AVAILABLE NOW (repo) - PostgreSQL generation_job and generation_attempt tables, Micrometer meters generation.terminal (category tags), generation.transition, provider.error (provider, error_code tags) | Alarm definitions AVAILABLE NOW (repo): `<prefix>-p0-generation-failure-ratio`, `<prefix>-p1-provider-errors`
Command:
```sql
-- 1. Daily generation job terminal status and technical success rate
SELECT
    COUNT(*) AS total_jobs,
    COUNT(*) FILTER (WHERE status = 'COMPLETED') AS completed_jobs,
    COUNT(*) FILTER (WHERE status = 'FAILED') AS failed_jobs,
    COUNT(*) FILTER (WHERE status NOT IN ('COMPLETED', 'FAILED')) AS active_or_queued_jobs,
    ROUND(100.0 * COUNT(*) FILTER (WHERE status = 'COMPLETED') / NULLIF(COUNT(*) FILTER (WHERE status IN ('COMPLETED', 'FAILED')), 0), 2) AS technical_success_rate_pct
FROM generation_job
WHERE created_at >= :day_start AND created_at < :day_end;

-- 2. Attempt-level failure code breakdown across providers
SELECT
    provider,
    COALESCE(failure_code, 'NONE_SUCCESS') AS failure_code,
    COUNT(*) AS attempt_count,
    ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (PARTITION BY provider), 2) AS pct_within_provider
FROM generation_attempt
WHERE started_at >= :day_start AND started_at < :day_end
GROUP BY provider, failure_code
ORDER BY provider, attempt_count DESC;
```
Vocabulary note: Persisted `generation_attempt.failure_code` values fall into two real families: provider safeCode literals (`RATE_LIMIT`, `CONCURRENCY_LIMIT`, `PROVIDER_INTERNAL`, `PROVIDER_TIMEOUT`, `PROVIDER_UNAVAILABLE`, `QUOTA_BILLING`, `AUTH_CONFIGURATION`, `CONTENT_POLICY`, `CONNECTION_FAILED`, `UNKNOWN_PROVIDER_FAILURE`, etc.) recorded during attempt execution, and TerminalReason names (`PROVIDER_RATE_LIMIT_EXHAUSTED`, `PROVIDER_TIMEOUT_EXHAUSTED`, `PROVIDER_QUOTA_EXHAUSTED`, `RETRY_BUDGET_EXHAUSTED`, `COST_BUDGET_BLOCKED`, etc.) recorded on terminal failure attribution. (The five-category vocabulary `PROVIDER_TIMEOUT`, `PROVIDER_RATE_LIMIT`, `PROVIDER_INTERNAL_ERROR`, `PROVIDER_NON_RETRYABLE`, `UNKNOWN` is the `generation.retry.provider.outcomes` METER category set in `GenerationMetrics`, not the persisted `failure_code` column; query the column for the safeCode/TerminalReason values above, or use the meter for the five-category rollup.) Terminal job categories on `generation.terminal`: `COMPLETED`, `FAILED`, `CANCELLED`, `SUBJECT_DELETED`, `BUDGET_EXHAUSTED`, `DEADLINE_EXPIRED`, `UNKNOWN`.
Healthy:
- Target: Technical generation success > 95% (tied to §65 beta exit gate: "Technical generation > 95%").
- Amber band: Technical success between 90% and 95% (within 10% relative tolerance of §65 gate), or >2x day-over-day increase in attempt failures.
- Red band: Technical success < 90%, or attempt `failure_code` showing persistent throttling (`RATE_LIMIT` / `CONCURRENCY_LIMIT` / `PROVIDER_RATE_LIMIT_EXHAUSTED`) or timeouts (`PROVIDER_TIMEOUT` / `PROVIDER_TIMEOUT_EXHAUSTED`) exceeding 10% of attempts, or all generations failing (P0).
Triage:
1. Separate job-level failure (`FAILED` status) from attempt-level transient retries (`failure_code`).
2. If `RATE_LIMIT`, `CONCURRENCY_LIMIT`, or `PROVIDER_RATE_LIMIT_EXHAUSTED` spikes, verify current API concurrency against contracted provider tier limits.
3. If `PROVIDER_TIMEOUT` dominates, verify provider API status page and check Section 5 provider latency trends.
4. If `PROVIDER_INTERNAL` or `PROVIDER_UNAVAILABLE` spikes, inspect sample provider response error structures in application logs.
5. Check whether terminal category is `BUDGET_EXHAUSTED` or `DEADLINE_EXPIRED` to identify execution budget starvation.
Escalation:
- Severity: P0 if all generations are failing (§68: "all generations failing"); P1 if provider failure rate exceeds threshold (§68: "provider failures > threshold"); P2 if isolated to non-default provider.
- Responding role: Backend On-Call Engineer + AI Platform Lead.
- First mitigation: If failure is tied to high-cost or multi-garment features, flip §66 flags `TRYON_MAX=false` (fallback to v1.6 fast path), `HD_GENERATION=false`, or `MULTI_ITEM=false`.
- Automation note: Alarm definitions are AVAILABLE NOW (repo) in `infra/alerts/`; AWS applied/deployment state is not verified by repository source. Open a manual P0/P1 incident if a deployed alarm is absent or inactive.

---

## 4. quality rejects
Source: AVAILABLE NOW (repo) - PostgreSQL quality_result table, Micrometer meter generation.quality (policy_version, decision, reason_code tags), product.generation.quality_rejection | Alarm definitions AVAILABLE NOW (repo): `<prefix>-p1-quality-reject-ratio`, `<prefix>-ai-quality-collapse`
Command:
```sql
-- 1. Daily quality gate decisions and rejection rate
SELECT
    COUNT(*) AS total_evaluated,
    COUNT(*) FILTER (WHERE decision = 'PASS') AS pass_count,
    COUNT(*) FILTER (WHERE decision = 'RETRY') AS retry_count,
    COUNT(*) FILTER (WHERE decision = 'FAIL') AS fail_count,
    ROUND(100.0 * COUNT(*) FILTER (WHERE decision IN ('FAIL', 'RETRY')) / NULLIF(COUNT(*), 0), 2) AS overall_reject_rate_pct,
    ROUND(100.0 * COUNT(*) FILTER (WHERE decision = 'FAIL') / NULLIF(COUNT(*), 0), 2) AS terminal_fail_rate_pct
FROM quality_result
WHERE created_at >= :day_start AND created_at < :day_end;

-- 2. Quality rejections grouped by quality_policy_version, decision, and reason_code
SELECT
    COALESCE(quality_policy_version, 'LEGACY') AS policy_version,
    decision,
    COALESCE(reason_code, 'none') AS reason_code,
    COUNT(*) AS rejection_count,
    ROUND(100.0 * COUNT(*) / SUM(COUNT(*)) OVER (PARTITION BY quality_policy_version), 2) AS pct_within_version
FROM quality_result
WHERE created_at >= :day_start AND created_at < :day_end
  AND decision IN ('FAIL', 'RETRY')
GROUP BY quality_policy_version, decision, reason_code
ORDER BY policy_version, rejection_count DESC;
```
Vocabulary note: Decision values: `PASS`, `RETRY`, `FAIL`. Canonical `reason_code` values: `IMAGE_INVALID`, `IMAGE_TOO_SMALL`, `PERSON_NOT_PRESERVED`, `BODY_NOT_PRESERVED`, `GARMENT_FIDELITY_LOW`, `COLOR_FIDELITY_LOW`, `ANATOMY_INVALID`, `IDENTITY_NOT_PRESERVED`, `PREVIOUS_ITEMS_NOT_PRESERVED`, `ARTIFACT_DETECTED`, `INPUT_MEDIA_MISSING`.
Healthy:
- Target: Quality rejection rate < 10% (tied to §65 beta exit gate: "Quality rejection < 10%").
- Amber band: Quality rejection rate between 10% and 15% (within relative 10% to 50% drift of gate), or >2x day-over-day surge in a specific reason code.
- Red band: Quality rejection rate >= 15%, or §69 AI Quality Collapse: HTTP generation success is normal (>95%) BUT quality pass rate collapses (<70% pass / >30% reject).
Triage:
1. Check for §69 AI Quality Collapse: compare Section 3 technical success against Section 4 pass rate.
2. Check whether reject spike aligns with a recent change in `quality_policy_version`.
3. Identify dominant `reason_code` (e.g., `PERSON_NOT_PRESERVED` vs `GARMENT_FIDELITY_LOW` vs `ANATOMY_INVALID`).
4. Cross-reference with Section 8 to verify whether rejections are isolated to specific complex garment categories (e.g., `SAREE` or `LEHENGA`).
5. Verify evaluator infrastructure health via meter `generation.quality.evaluator.unavailable` (`EVALUATOR_UNAVAILABLE` or `QUALITY_POLICY_UNSUPPORTED`).
Escalation:
- Severity: P1 per §68 ("quality failures spike"); Critical AI Alert per §69 if technical success is green but quality passes collapse.
- Responding role: AI Evaluation / ML Engineer + Backend On-Call Engineer.
- First mitigation: If reject surge is triggered by a newly deployed evaluator policy, revert via §66 feature flag `NEW_QUALITY_GATE=false`; if multi-item layering fails, disable `MULTI_ITEM=false`.
- Automation note: Alarm definitions are AVAILABLE NOW (repo) in `infra/alerts/alarms-p1.tf` and `infra/alerts/ai-quality-collapse.tf`; AWS applied/deployment state is not verified by repository source.

---

## 5. provider latency
Source: AVAILABLE NOW (repo) - PostgreSQL generation_attempt table (latency_ms), Micrometer meter generation.provider_latency (tag provider), provider.attempts
Command:
```sql
-- Provider generation attempt latency percentiles (P50, P90, P95, P99) in milliseconds
SELECT
    provider,
    COUNT(*) AS completed_attempts,
    ROUND(percentile_cont(0.50) WITHIN GROUP (ORDER BY latency_ms)::numeric, 0) AS p50_latency_ms,
    ROUND(percentile_cont(0.90) WITHIN GROUP (ORDER BY latency_ms)::numeric, 0) AS p90_latency_ms,
    ROUND(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_ms)::numeric, 0) AS p95_latency_ms,
    ROUND(percentile_cont(0.99) WITHIN GROUP (ORDER BY latency_ms)::numeric, 0) AS p99_latency_ms,
    MAX(latency_ms) AS max_latency_ms
FROM generation_attempt
WHERE started_at >= :day_start AND started_at < :day_end
  AND latency_ms IS NOT NULL
  AND completed_at IS NOT NULL
GROUP BY provider
ORDER BY provider;
```
Healthy:
- Target: P95 provider generation latency < 30,000 ms (30 s) (tied to §65 beta exit gate: "P95 generation < 30s").
- Amber band: P95 latency between 25,000 ms and 30,000 ms, or P50 latency > 15,000 ms (15 s), or >50% day-over-day latency increase.
- Red band: P95 latency >= 30,000 ms (breaching §65 gate), or any sustained P95 > 45,000 ms.
Triage:
1. Compare P50 vs P95: stable P50 with elevated P95 points to tail outliers; elevated P50 indicates widespread provider saturation.
2. Compare across providers: check whether latency increase is provider-specific or systemic.
3. Check whether latency correlates with requested resolution or model (HD / TryOn Max vs standard v1.6).
4. Inspect provider HTTP response times and check whether attempts are approaching `PROVIDER_TIMEOUT` thresholds.
5. Cross-reference with Section 6 to see if worker queue wait times are inflating end-to-end user latency.
Escalation:
- Severity: P1 if provider latency breaches 30 s and causes cascading timeouts; P2 if isolated to non-primary provider.
- Responding role: Backend On-Call Engineer.
- First mitigation: If HD rendering causes the latency spike, disable via §66 feature flag `HD_GENERATION=false`; if TryOn Max is degraded, toggle `TRYON_MAX=false` to fallback to v1.6 fast path.
- Automation note: App-level meter `generation.provider_latency` records per-attempt timer; no automated latency alarm exists (plan §68 defines no latency condition); the generation.provider_latency meter is AVAILABLE NOW, so the daily P50/P95 check stays manual. Open manual P1/P2 incident referencing runbook Section 5.

---

## 6. queue latency
Source: AVAILABLE NOW (repo) - AWS CloudWatch AWS/SQS ApproximateAgeOfOldestMessage and ApproximateNumberOfMessagesVisible, Micrometer gauges generation.outbox.lag + queue.depth + queue.oldest_age (QueueObservability), Micrometer timer generation.queue_wait | Alarm definitions AVAILABLE NOW (repo): `<prefix>-p1-queue-oldest-age`, `<prefix>-p1-sqs-backlog`
Command:
AWS CLI v2 commands (substitute `<queue-name>` with deployed queue name, e.g., from Terraform `var.generation_queue_name`):
```bash
# 1. Oldest message age in seconds (Maximum over 5-minute periods for the past 24h)
aws cloudwatch get-metric-statistics \
    --namespace AWS/SQS \
    --metric-name ApproximateAgeOfOldestMessage \
    --dimensions Name=QueueName,Value=<queue-name> \
    --start-time "$(date -u -d '24 hours ago' +%FT%TZ)" \
    --end-time "$(date -u +%FT%TZ)" \
    --period 300 \
    --statistics Maximum

# 2. Number of visible messages (Queue depth backlog, Average over 5-minute periods)
aws cloudwatch get-metric-statistics \
    --namespace AWS/SQS \
    --metric-name ApproximateNumberOfMessagesVisible \
    --dimensions Name=QueueName,Value=<queue-name> \
    --start-time "$(date -u -d '24 hours ago' +%FT%TZ)" \
    --end-time "$(date -u +%FT%TZ)" \
    --period 300 \
    --statistics Average
```

App-side outbox lag check (in-process fallback):
Inspect Micrometer gauge `generation.outbox.lag` via `/actuator/metrics/generation.outbox.lag` (baseUnit: milliseconds).
Note on SQS metric publication: SQS stops emitting CloudWatch metrics after approximately 6 hours of queue inactivity. Dual-sourcing with `generation.outbox.lag` and `generation.queue_wait` ensures visibility even during low-traffic windows.
Healthy:
- Target: `ApproximateAgeOfOldestMessage` < 30 seconds; `ApproximateNumberOfMessagesVisible` near 0 during steady state (initial operational default).
- Amber band: Oldest message age between 30 and 60 seconds, or visible queue backlog consistently > 20 messages for > 15 minutes.
- Red band: Oldest message age > 120 seconds (sustaining above execution deadline band), or visible backlog grows monotonically across 3 consecutive 5-minute periods (§68 P1 condition: "queue delay too high").
Triage:
1. Verify ECS Fargate generation worker tasks are running and healthy (`<prefix>-p0-api-live-tasks`).
2. Check worker thread pool saturation and concurrency settings in Spring Actuator.
3. Compare message arrival rate against worker dequeue rate to determine if backlog is demand-driven or worker-stalled.
4. Check whether downstream provider latency (Section 5) is holding worker concurrency slots open.
5. Inspect the SQS Dead Letter Queue (DLQ) for poisoned messages causing worker crash loops.
Escalation:
- Severity: P1 per §68 ("queue delay too high").
- Responding role: Infrastructure / Backend On-Call Engineer.
- First mitigation: Scale out ECS Fargate generation worker task count; if backlog is driven by runaway batch submissions, throttle generation ingress or flip `MULTI_ITEM=false`.
- Automation note: Alarm definitions are AVAILABLE NOW (repo) in `infra/alerts/alarms-p1.tf`; AWS applied/deployment state is not verified by repository source.

---

## 7. cost per Look
Source: AVAILABLE NOW (repo) - PostgreSQL generation_attempt table (actual_cost, estimated_cost, cost_status, credits_used), Micrometer meters generation.cost (recordCostStatus), generation.cost.amount, generation.cost.attempt, provider.credit_balance | Alarm definitions AVAILABLE NOW (repo): `<prefix>-p1-generation-cost-hourly`, `<prefix>-p1-provider-credits-low`
Command:
```sql
-- 1. Headline: Cost per Quality-Approved Look (ADR-014 / §43) with coverage integrity
WITH attempt_costs AS (
    SELECT
        ga.id AS attempt_id,
        ga.generation_job_id,
        ga.provider,
        ga.cost_status,
        CASE
            WHEN ga.cost_status = 'FINAL' AND ga.actual_cost IS NOT NULL AND ga.currency IS NOT NULL THEN ga.actual_cost
            WHEN ga.cost_status = 'ESTIMATED' AND ga.estimated_cost IS NOT NULL AND ga.currency IS NOT NULL THEN ga.estimated_cost
            ELSE NULL
        END AS cost,
        ga.currency,
        ga.credits_used
    FROM generation_attempt ga
    WHERE ga.started_at >= :day_start AND ga.started_at < :day_end
),
cost_totals AS (
    SELECT
        COUNT(*) AS total_attempts,
        CASE
            WHEN COUNT(*) FILTER (WHERE cost_status = 'UNKNOWN') > 0
              OR COUNT(*) FILTER (WHERE cost_status IS NULL) > 0
              OR COUNT(DISTINCT currency) FILTER (WHERE cost_status IN ('FINAL', 'ESTIMATED')) > 1
              OR COUNT(cost) <> COUNT(*) FILTER (WHERE cost_status IN ('FINAL', 'ESTIMATED'))
            THEN NULL
            ELSE SUM(cost)
        END AS total_cost,
        SUM(credits_used) AS total_credits_used,
        COUNT(*) FILTER (WHERE cost_status IN ('FINAL', 'ESTIMATED')) AS known_cost_attempts,
        COUNT(*) FILTER (WHERE cost_status = 'UNKNOWN') AS unknown_cost_attempts,
        ROUND(100.0 * COUNT(*) FILTER (WHERE cost_status IN ('FINAL', 'ESTIMATED')) / NULLIF(COUNT(*), 0), 2) AS known_cost_coverage_pct,
        ROUND(100.0 * COUNT(*) FILTER (WHERE cost_status = 'UNKNOWN') / NULLIF(COUNT(*), 0), 2) AS unknown_cost_pct
    FROM attempt_costs
),
approved_looks AS (
    SELECT COUNT(DISTINCT l.id) AS quality_approved_looks_count
    FROM look l
    JOIN generation_job gj ON gj.id = l.generation_job_id AND gj.user_id = l.user_id
    JOIN generation_attempt ga
      ON ga.generation_job_id = gj.id
     AND split_part(l.storage_key, '/', 4) LIKE ga.id::text || '.%'
    JOIN quality_result qr ON qr.generation_attempt_id = ga.id
    WHERE l.created_at >= :day_start AND l.created_at < :day_end
      AND l.deleted_at IS NULL
      AND l.quality_status = 'PASS'
      AND gj.status = 'COMPLETED'
      AND ga.technical_outcome = 'SUCCESS'
      AND qr.decision = 'PASS'
),
job_totals AS (
    SELECT COUNT(*) FILTER (WHERE status = 'COMPLETED') AS total_completed_jobs
    FROM generation_job
    WHERE created_at >= :day_start AND created_at < :day_end
)
SELECT
    ct.total_attempts,
    ROUND(ct.total_cost::numeric, 4) AS total_cost,
    ct.total_credits_used,
    al.quality_approved_looks_count AS total_quality_approved_looks,
    ROUND((ct.total_cost / NULLIF(al.quality_approved_looks_count, 0))::numeric, 4) AS cost_per_quality_approved_look,
    ROUND((ct.total_cost / NULLIF(jt.total_completed_jobs, 0))::numeric, 4) AS cost_per_completed_job,
    ct.known_cost_attempts,
    ct.unknown_cost_attempts,
    ct.known_cost_coverage_pct,
    ct.unknown_cost_pct
FROM cost_totals ct
CROSS JOIN job_totals jt
CROSS JOIN approved_looks al;

-- 2. Provider-level cost and credit consumption split
SELECT
    provider,
    currency,
    cost_status,
    COUNT(*) AS attempts,
    ROUND(SUM(
        CASE
            WHEN cost_status = 'FINAL' AND actual_cost IS NOT NULL AND currency IS NOT NULL THEN actual_cost
            WHEN cost_status = 'ESTIMATED' AND estimated_cost IS NOT NULL AND currency IS NOT NULL THEN estimated_cost
        END
    )::numeric, 4) AS provider_cost,
    SUM(credits_used) AS provider_credits_used,
    ROUND(AVG(
        CASE
            WHEN cost_status = 'FINAL' AND actual_cost IS NOT NULL AND currency IS NOT NULL THEN actual_cost
            WHEN cost_status = 'ESTIMATED' AND estimated_cost IS NOT NULL AND currency IS NOT NULL THEN estimated_cost
        END
    )::numeric, 4) AS avg_cost_per_attempt
WHERE started_at >= :day_start AND started_at < :day_end
GROUP BY provider, currency, cost_status
ORDER BY provider, currency, cost_status;
```
Accounting gate: if any included attempt is UNKNOWN, has a NULL `cost_status`, has a malformed known-cost row, or known attempts use more than one currency, monetary totals and unit costs are `NULL`/UNKNOWN, never zero.
Healthy:
- Target: Cost per quality-approved Look within modeled unit economics ($0.04–$0.08 initial baseline); known-cost coverage >= 95%; UNKNOWN cost status < 5% (initial operational default per ADR-014).
- Amber band: Day-over-day increase between 15% and 30% with stable traffic volume, or UNKNOWN cost status share between 5% and 10%.
- Red band: >30% day-over-day increase in cost per quality-approved Look without traffic surge, or UNKNOWN cost status share > 10% (cost tracking audit breach), or provider credits critically low (§68 P1: `<prefix>-p1-provider-credits-low`).
Triage:
1. Check whether cost increase is driven by unit pricing changes or by attempt retry inflation (check attempts per completed Look).
2. Check provider split to identify if one provider increased unit charges or billed unexpected credits.
3. Check render mode mix: verify if users shifted disproportionately to HD / 2K or TryOn Max.
4. If UNKNOWN cost status exceeds 5%, inspect attempts for missing provider pricing callbacks or unmapped model names.
5. Check gauge `provider.credit_balance` to ensure prepaid provider credits are not nearing exhaustion.
Escalation:
- Severity: P1 per §68 ("generation cost spike" or "provider credits low").
- Responding role: Engineering Lead + Product Owner / FinOps.
- First mitigation: If HD or Max renders drive cost spike, disable via §66 feature flags `HD_GENERATION=false` or `TRYON_MAX=false`; if per-user quota runaway, enforce tighter rate limits.
- Automation note: Alarm definitions are AVAILABLE NOW (repo) in `infra/alerts/alarms-p1.tf`; AWS applied/deployment state is not verified by repository source.

---

## 8. problematic categories
Source: AVAILABLE NOW (repo) - PostgreSQL quality_result, generation_attempt, generation_job, generation_render_step, generation_render_step_item, vault_item tables | Alarm definition AVAILABLE NOW (repo): `<prefix>-p2-category-quality-degraded-<category>`
Command:
```sql
-- Garment category quality reject rates ranked by reject percentage
-- Filtered by minimum sample size (:min_samples = 20) to prevent single-sample false alarms
SELECT
    COALESCE(grsi.category, vi.category, 'UNKNOWN') AS garment_category,
    COUNT(DISTINCT qr.id) AS total_evaluations,
    COUNT(DISTINCT qr.id) FILTER (WHERE qr.decision = 'PASS') AS pass_count,
    COUNT(DISTINCT qr.id) FILTER (WHERE qr.decision IN ('FAIL', 'RETRY')) AS reject_count,
    ROUND(100.0 * COUNT(DISTINCT qr.id) FILTER (WHERE qr.decision IN ('FAIL', 'RETRY')) / NULLIF(COUNT(DISTINCT qr.id), 0), 2) AS reject_rate_pct,
    MODE() WITHIN GROUP (ORDER BY qr.reason_code) AS top_rejection_reason
FROM quality_result qr
JOIN generation_attempt ga ON qr.generation_attempt_id = ga.id
JOIN generation_job gj ON ga.generation_job_id = gj.id
JOIN generation_render_step grs ON grs.id = ga.render_step_id
JOIN generation_render_step_item grsi ON grsi.render_step_id = grs.id
LEFT JOIN vault_item vi ON grsi.vault_item_id = vi.id
WHERE qr.created_at >= :day_start AND qr.created_at < :day_end
GROUP BY COALESCE(grsi.category, vi.category, 'UNKNOWN')
HAVING COUNT(DISTINCT qr.id) >= :min_samples
ORDER BY reject_rate_pct DESC, total_evaluations DESC;
```
Vocabulary note: Canonical categories from `ProductCategory` enum: `TOP`, `BOTTOM`, `ONE_PIECE`, `ETHNIC_FULL_BODY`, `SAREE`, `LEHENGA`, `KURTA`, `SHERWANI`, `FOOTWEAR`, `HEADWEAR`, `BAG`, `JEWELLERY`, `ACCESSORY`, `UNKNOWN`. Category comes from `generation_render_step_item.category` (V013), falling back to `vault_item.category` (V004) when null; uncategorized remainder aggregates under `UNKNOWN`. A future normalization fallback would join `product_correction_event.corrected_category` (V020), deferred until attribution accuracy matters.
Healthy:
- Target: Rejection rate < 10% across all categories with >= 20 samples (tied to §65 beta exit gate: "Quality rejection < 10%").
- Amber band: Any category rejection rate between 10% and 20% with >= 20 samples, or >15% adverse day-over-day shift.
- Red band: Any category rejection rate > 20% with >= 20 samples (§68 P2 condition: "one garment category quality degraded").
Triage:
1. Check `top_rejection_reason` for the failing category (e.g., `GARMENT_FIDELITY_LOW` on `LEHENGA` vs `ANATOMY_INVALID` on `FOOTWEAR`).
2. Verify provider capabilities: confirm provider model actually supports the garment category natively (ADR-008).
3. Inspect sample items for misclassification in normalizer (e.g., `SAREE` wrongly classified as `ONE_PIECE`).
4. Compare multi-provider quality benchmark for this category to evaluate routing adjustment (§74 Provider Routing).
5. Check if multi-item layering conflicts are driving failures (e.g., `ETHNIC_FULL_BODY` conflicting with `BOTTOM`).
Escalation:
- Severity: P2 per §68 ("one garment category quality degraded").
- Responding role: AI Evaluation / ML Engineer.
- First mitigation: Adjust category routing rules in provider adapter; temporarily warn users or constrain multi-item composition for degraded category; if whole category causes failure, restrict via feature flag.
- Automation note: Alarm definition is AVAILABLE NOW (repo) in `infra/alerts/alarms-p2.tf`; AWS applied/deployment state is not verified by repository source.

---

## 9. problematic source apps
Source: AVAILABLE NOW (repo) - PostgreSQL share_import table (source_site_name, source_url, source_kind, terminal_reason_code) | Alarm definition AVAILABLE NOW (repo): `<prefix>-p2-import-resolver-degraded-<source>`
Command:
```sql
-- Share import failure rates ranked by retailer domain / source site
-- Note on attribution: share_import persists source_site_name and source_url, NOT originating app package ID.
-- Attribution is by retailer domain. Native app package compatibility is tracked via external share matrix (§62).
SELECT
    COALESCE(
        NULLIF(lower(split_part(split_part(source_url, '://', 2), '/', 1)), ''),
        NULLIF(lower(source_site_name), ''),
        'direct_upload_or_unknown'
    ) AS source_domain,
    source_kind,
    COUNT(*) AS total_shares,
    COUNT(*) FILTER (WHERE resolver_state = 'READY') AS successful_shares,
    COUNT(*) FILTER (WHERE resolver_state = 'FAILED_TERMINAL') AS terminal_failures,
    COUNT(*) FILTER (WHERE resolver_state = 'FAILED_RETRYABLE') AS retryable_failures,
    ROUND(100.0 * COUNT(*) FILTER (WHERE resolver_state = 'FAILED_TERMINAL') / NULLIF(COUNT(*), 0), 2) AS failure_rate_pct,
    MODE() WITHIN GROUP (ORDER BY terminal_reason_code) AS top_terminal_reason
FROM share_import
WHERE created_at >= :day_start AND created_at < :day_end
GROUP BY
    COALESCE(
        NULLIF(lower(split_part(split_part(source_url, '://', 2), '/', 1)), ''),
        NULLIF(lower(source_site_name), ''),
        'direct_upload_or_unknown'
    ),
    source_kind
HAVING COUNT(*) >= 10
ORDER BY failure_rate_pct DESC, total_shares DESC;
```
Healthy:
- Target: Failure rate < 10% for every major source domain with >= 10 shares (tied to §65 beta exit gate: "Import success > 90%").
- Amber band: Domain failure rate between 10% and 25% with >= 10 shares, or sudden >2x drop in volume from a primary retailer domain (Myntra, AJIO, Amazon).
- Red band: Source domain failure rate > 25% with >= 10 shares (§68 P2 condition: "one retailer/source import degraded").
Triage:
1. Identify dominant `top_terminal_reason`: `URL_RESOLUTION_FAILED` (bot blocking / anti-scraping / page structure change) vs `RESOLUTION_TIMEOUT` (slow retailer CDN) vs `INVALID_SOURCE` (unsupported URL format).
2. Check if retailer introduced new bot-protection challenges (e.g., Cloudflare Turnstile, Akamai) blocking OG metadata scrapers.
3. Verify whether user fallback (`NEEDS_IMAGE` / screenshot upload) successfully recovered the flow for affected users.
4. Manually test a sample failing URL with curl to inspect returned HTTP status and headers.
5. Check whether failures correlate with Android vs iOS share sheet handoff differences (consult §62 compatibility matrix).
Escalation:
- Severity: P2 per §68 ("one retailer/source import degraded").
- Responding role: Backend / Integrations On-Call Engineer.
- First mitigation: If scraper is blocked by retailer, update scraper User-Agent / headers or prompt mobile client to recommend screenshot share; if whole platform share target is broken, disable via §66 feature flag `SHARE_IMPORT_ANDROID=false` or `SHARE_IMPORT_IOS=false`.
- Automation note: Alarm definition is AVAILABLE NOW (repo) in `infra/alerts/alarms-p2.tf`; AWS applied/deployment state is not verified by repository source.

---

## Annex: Rollout Mode

During staged rollout per plan §67 (percentages: 1% → 5% → 10% → 25% → 50% → 100%), the daily inspection serves as the formal gate for advancing to the next rollout tier.

### Rollout Signal Mapping

| §67 Staged Rollout Signal | Runbook Inspection Section | Gating Condition for Rollout Advance |
|---|---|---|
| crashes | Section 1 (crashes) | Crash-free sessions > 99.5%; zero launch/auth crash clusters |
| generation failures | Section 3 (generation failures) | Technical generation success > 95% |
| provider errors | Section 3 (generation failures) & Section 5 (provider latency) | Attempt failure rate < 5%; zero provider rate limit stalls |
| quality rejects | Section 4 (quality rejects) & Section 8 (problematic categories) | Quality rejection < 10%; zero §69 quality collapses |
| latency | Section 5 (provider latency) & Section 6 (queue latency) | P95 generation < 30 s; oldest queue message < 30 s |
| queue depth | Section 6 (queue latency) | Visible backlog stable near 0; no monotonically growing queue |
| cost/user | Section 7 (cost per Look) | Cost per quality-approved Look within modeled budget ($0.04–$0.08) |

### Rollout Decision Rule
- **Advance Step:** Advance to the next rollout percentage only if all seven mapped signals are **GREEN** across the full 24-hour observation window.
- **Hold Step:** If any signal is **AMBER**, hold the rollout at the current percentage for an additional 24 hours of observation.
- **Rollback / Mitigation:** If any signal is **RED**, immediately halt the rollout or revert to the prior tier in Google Play Console / App Store Connect, and invoke the appropriate §66 feature flag kill-switch.

---

## Sources Last Verified

Every data source, schema column, metric, and console path cited in this runbook was verified against repository source code, database migrations (V001–V023), and platform documentation on 2026-09-06.

| §70 Signal | Verification Date | Repo / Platform Reference | Status |
|---|---|---|---|
| 1. crashes | 2026-09-06 | Google Play Console Android vitals + App Store Connect Analytics (no SDK in `mobile/`) | AVAILABLE NOW (repo) |
| 2. import failures | 2026-09-06 | `share_import` (V003, V019), `product.share.import.success` (`MicrometerProductMetrics`), `SourceResolverMetrics`/`ObservationFacade.share.resolve` | AVAILABLE NOW (repo) |
| 3. generation failures | 2026-09-06 | `generation_job`, `generation_attempt` (V007, V013, V014), `generation.terminal` (`GenerationMetrics`) | AVAILABLE NOW (repo) |
| 4. quality rejects | 2026-09-06 | `quality_result` (V007, V014, V016), `generation.quality` (`GenerationMetrics`) | AVAILABLE NOW (repo) |
| 5. provider latency | 2026-09-06 | `generation_attempt.latency_ms` (V007), `generation.provider_latency` (`GenerationMetrics`) | AVAILABLE NOW (repo) |
| 6. queue latency | 2026-09-06 | AWS CloudWatch `AWS/SQS`, `generation.outbox.lag` (`GenerationMetrics.OUTBOX_LAG`), `QueueObservability (queue.depth, queue.oldest_age)` | AVAILABLE NOW (repo) |
| 7. cost per Look | 2026-09-06 | `generation_attempt` cost columns (V014), ADR-014, `generation.cost.amount` (`GenerationMetrics`), `GenerationMetrics.recordCostStatus (generation.cost)` | AVAILABLE NOW (repo) |
| 8. problematic categories | 2026-09-06 | `generation_render_step_item.category` (V013), `vault_item.category` (V004), `ProductCategory` | AVAILABLE NOW (repo) |
| 9. problematic source apps | 2026-09-06 | `share_import.source_site_name`, `source_url` (V019) | AVAILABLE NOW (repo) |

### Threshold Tuning Location
All healthy bands, amber thresholds, and red boundaries defined in this document represent initial post-production defaults derived from plan §65 and §68. As real production traffic establishes empirical baselines, threshold adjustments must be made directly within this runbook via code review and pull request.
