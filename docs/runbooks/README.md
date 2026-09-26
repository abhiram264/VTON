# Operations Runbooks

Canonical operational runbooks and checklists for AI Fashion Vault post-production operations.

## Runbook Series Index

| Runbook | Cadence | Plan Reference | Status |
|---|---|---|---|
| DAILY_OPERATIONS.md | daily | plan §70 | available |
| WEEKLY_OPERATIONS.md | weekly | plan §71 | available |
| MONTHLY_OPERATIONS.md | monthly | plan §72 | available |

## Prerequisites

Before executing any daily, weekly, or monthly runbook, the on-call operator must verify the following credentials and tooling:

1. **PostgreSQL Read-Only Access:**
   - Active database connection to production replica (preferred) or primary with a read-only role (`SELECT` privilege only).
   - Privileges required on tables accessed by the runbooks: `share_import`, `generation_job`, `generation_attempt`, `quality_result`, `generation_render_step`, `generation_render_step_item`, `vault_item`, `look`, `look_item`, `source_reference`, `studio_session`, `studio_item`, `users`, `generation_provider_performance_daily`.
   - Client: `psql` CLI v15+ or compatible PostgreSQL client.

2. **AWS CLI and CloudWatch Telemetry:**
   - AWS CLI v2 (`aws --version` >= 2.15) authenticated with target production account and region.
   - IAM permissions: `cloudwatch:GetMetricData`, `cloudwatch:GetMetricStatistics`, `cloudwatch:ListMetrics`.
   - Configured SQS target queue name (Terraform variable `generation_queue_name`).

3. **Mobile Store Console Roles:**
   - **Google Play Console:** Operator or Viewer role with access to **Monitor and improve** → **Android vitals** (Crashes and ANRs).
   - **Apple App Store Connect:** Developer or App Manager role with access to **Analytics** → **Crashes**.
   - **Xcode Organizer:** Apple Developer account with access to symbolicated crash logs.

4. **Spring Boot Actuator Metrics:**
   - Read-only access to `/actuator/metrics` or Prometheus/Micrometer dashboard for in-process application gauges and counters.

## Privacy Standing Rule

In accordance with ADR-015, §41, and §54:
- Reporting queries select only aggregates, opaque identifiers, and non-user operational dimensions (such as provider, category, source site, and reason codes); `user_id` may appear only within `COUNT(DISTINCT ...)` in those queries.
- The monthly local deletion test is the narrow exception: use a dedicated synthetic test-account ID only for that test's verification queries, and never copy it into runbooks or incident channels.
- **Never** select, log, copy, or paste raw image bytes, base64 data, signed S3 URLs, storage keys, authentication tokens, or provider secrets into incident channels or runbook records.

## Daily Routine

Every morning, the on-call engineer completes the nine-signal inspection defined in `DAILY_OPERATIONS.md` and records findings using a new copy of `DAILY_OPERATIONS_CHECKLIST.md`.
