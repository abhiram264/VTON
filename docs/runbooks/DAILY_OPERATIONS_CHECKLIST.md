# Daily Operations Checklist Template

This checklist is the copy-per-day template for recording daily post-production health inspections per `DAILY_OPERATIONS.md`.

> **Usage:** Do not edit this template directly. Copy this file to `docs/runbooks/inspections/YYYY-MM-DD.md` or paste its contents into the daily operations ticket/channel before beginning inspection.

---

## Inspection Metadata

- **Date (UTC):** `YYYY-MM-DD`
- **Inspector / On-Call Engineer:** `[Name / Handle]`
- **Inspection Window:** `[YYYY-MM-DD 00:00:00 UTC to YYYY-MM-DD 00:00:00 UTC]`
- **Active Rollout Tier:** `[Internal | 1% | 5% | 10% | 25% | 50% | 100%]`
- **Overall Verdict:** `[ ] GREEN  [ ] AMBER  [ ] RED`

---

## Standing Privacy Reminder

> **CRITICAL PRIVACY RULE (ADR-015):**
> Never paste raw user photos, avatar images, signed S3 URLs, base64 payloads, storage keys, authentication tokens, or provider secrets into this checklist, incident tickets, or operational chat channels. Reference only opaque UUIDs (`job_id`, `attempt_id`, `import_id`).

---

## Signal Checklist

Complete all nine checks in order. Mark each signal as `[x]` when inspected.

### [ ] 1. crashes
- **Runbook Reference:** `DAILY_OPERATIONS.md#1-crashes`
- **Healthy Gate:** Crash-free sessions > 99.5% (§65); Amber <= 99.5%; Red <= 99.0% or launch/auth crash cluster.
- **Measured Android Vitals Crash Rate:** `____%` (User-perceived: `____%`)
- **Measured iOS App Store Connect Crash Rate:** `____%` (Total crashes: `____`)
- **Signal Status:** `[ ] GREEN  [ ] AMBER  [ ] RED`
- **Findings / Top Clusters:**
- **Escalation / Action Taken:**

### [ ] 2. import failures
- **Runbook Reference:** `DAILY_OPERATIONS.md#2-import-failures`
- **Healthy Gate:** Import success rate > 90% (§65); Amber 85%–90%; Red < 85% or single retailer failure > 30%.
- **Measured Success Rate:** `____%` (Total: `____`, Ready: `____`, Needs Image: `____`, Terminal: `____`)
- **Top Terminal Reason:** `[URL_RESOLUTION_FAILED | DEPENDENCY_UNAVAILABLE | RESOLUTION_TIMEOUT | INVALID_SOURCE]`
- **Signal Status:** `[ ] GREEN  [ ] AMBER  [ ] RED`
- **Findings / Notes:**
- **Escalation / Action Taken:**

### [ ] 3. generation failures
- **Runbook Reference:** `DAILY_OPERATIONS.md#3-generation-failures`
- **Healthy Gate:** Technical generation success > 95% (§65); Amber 90%–95%; Red < 90% or persistent rate limits.
- **Measured Technical Success Rate:** `____%` (Total: `____`, Completed: `____`, Failed: `____`)
- **Primary Attempt Failure Code:** `[RATE_LIMIT | CONCURRENCY_LIMIT | PROVIDER_INTERNAL | PROVIDER_TIMEOUT | PROVIDER_RATE_LIMIT_EXHAUSTED | OTHER: ____]`
- **Signal Status:** `[ ] GREEN  [ ] AMBER  [ ] RED`
- **Findings / Notes:**
- **Escalation / Action Taken:**

### [ ] 4. quality rejects
- **Runbook Reference:** `DAILY_OPERATIONS.md#4-quality-rejects`
- **Healthy Gate:** Quality rejection rate < 10% (§65); Amber 10%–15%; Red >= 15% or §69 AI Quality Collapse.
- **Measured Overall Rejection Rate:** `____%` (Evaluated: `____`, Pass: `____`, Retry: `____`, Fail: `____`)
- **§69 Check (Tech Success Normal ∧ Quality Collapsed?):** `[ ] NO (Normal)  [ ] YES (COLLAPSE - ESCALATE P1)`
- **Top Rejection Reason Code:** `[PERSON_NOT_PRESERVED | GARMENT_FIDELITY_LOW | ANATOMY_INVALID | OTHER: ____]`
- **Signal Status:** `[ ] GREEN  [ ] AMBER  [ ] RED`
- **Findings / Notes:**
- **Escalation / Action Taken:**

### [ ] 5. provider latency
- **Runbook Reference:** `DAILY_OPERATIONS.md#5-provider-latency`
- **Healthy Gate:** P95 generation latency < 30,000 ms (30 s) (§65); Amber 25–30 s or P50 > 15 s; Red >= 30 s.
- **Measured Latency (ms):** P50: `____ ms`, P90: `____ ms`, P95: `____ ms`, P99: `____ ms`
- **Per-Provider Split:** `[FASHN: P95 ____ ms | Other: P95 ____ ms]`
- **Signal Status:** `[ ] GREEN  [ ] AMBER  [ ] RED`
- **Findings / Notes:**
- **Escalation / Action Taken:**

### [ ] 6. queue latency
- **Runbook Reference:** `DAILY_OPERATIONS.md#6-queue-latency`
- **Healthy Gate:** Oldest message age < 30 s; visible depth near 0; Amber age 30–60 s; Red age > 120 s or backlog growth.
- **Measured ApproximateAgeOfOldestMessage:** Max: `____ s`
- **Measured ApproximateNumberOfMessagesVisible:** Avg: `____`, Max: `____`
- **App Outbox Lag Gauge (`generation.outbox.lag`):** `____ ms`
- **Signal Status:** `[ ] GREEN  [ ] AMBER  [ ] RED`
- **Findings / Notes:**
- **Escalation / Action Taken:**

### [ ] 7. cost per Look
- **Runbook Reference:** `DAILY_OPERATIONS.md#7-cost-per-look`
- **Healthy Gate:** Cost per quality-approved Look within modeled unit economics ($0.04–$0.08); known cost coverage >= 95%; UNKNOWN < 5%; Amber +15%–30% DoD; Red >30% DoD or UNKNOWN > 10%.
- **Measured Cost per Quality-Approved Look:** `$____`
- **Measured Cost per Completed Job:** `$____`
- **Cost Status Coverage:** Known: `____%`, UNKNOWN: `____%` (Total Cost: `$____`, Credits: `____`)
- **Signal Status:** `[ ] GREEN  [ ] AMBER  [ ] RED`
- **Findings / Notes:**
- **Escalation / Action Taken:**

### [ ] 8. problematic categories
- **Runbook Reference:** `DAILY_OPERATIONS.md#8-problematic-categories`
- **Healthy Gate:** Category reject rate < 10% (min 20 samples) (§65); Amber 10%–20%; Red > 20% (§68 P2).
- **Highest Reject Category:** `[Category: ____, Volume: ____, Rejection Rate: ____%]`
- **Top Rejection Reason for Category:** `____`
- **Signal Status:** `[ ] GREEN  [ ] AMBER  [ ] RED`
- **Findings / Notes:**
- **Escalation / Action Taken:**

### [ ] 9. problematic source apps
- **Runbook Reference:** `DAILY_OPERATIONS.md#9-problematic-source-apps`
- **Healthy Gate:** Failure rate < 10% per major retailer domain (min 10 shares) (§65); Amber 10%–25%; Red > 25% (§68 P2).
- **Highest Failure Domain:** `[Domain: ____, Volume: ____, Failure Rate: ____%]`
- **Primary Terminal Reason:** `[URL_RESOLUTION_FAILED | RESOLUTION_TIMEOUT | INVALID_SOURCE]`
- **Signal Status:** `[ ] GREEN  [ ] AMBER  [ ] RED`
- **Findings / Notes:**
- **Escalation / Action Taken:**

---

## Staged Rollout Advance Assessment (if applicable)

- All 7 Rollout Signals Green? `[ ] YES (Approved to Advance)  [ ] NO (Hold at current %)  [ ] RED (Halt/Rollback)`
- Recommendation:

---

## Action Items & Escalations

| Item # | Signal | Severity | Assigned To | Jira / Incident Ticket | Description / Action Taken |
|---|---|---|---|---|---|
| 1 | | | | | |
| 2 | | | | | |

- **Sign-off:** `[Name / Timestamp]`
