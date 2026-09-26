# Monthly Post-Production Operations Runbook

This runbook defines the monthly operational review and audit procedure for AI Fashion Vault in production per technical implementation plan §72 ("Monthly Operations").

Status: active (docs-only runbook; per-step execution classes below)  
Owner: Operations  
Cadence: first working day of each month, all seven sections, results logged per section  

Where `docs/runbooks/DAILY_OPERATIONS.md` governs day-to-day incident inspection and `docs/runbooks/WEEKLY_OPERATIONS.md` monitors week-over-week trends and tactical release gates, this runbook governs monthly provider benchmarking, unit-economic cost optimization, dependency lifecycle management, formal privacy and security audit checklists, monthly data deletion verification, and disaster-recovery backup restore drills.

---

## Blocked Execution Register

Every command and procedure in this runbook is classified into one of three execution categories to maintain operational honesty:

- **LOCAL:** Runnable today against this repository's local compose stack (`docker compose up -d postgres localstack`) and local development tooling using Windows-honest commands (`cmd.exe /c mvnw.cmd …` from `backend/`, `npm …` from `mobile/`) and POSIX equivalents (`./mvnw …`).
- **REQUIRES-CREDENTIALS:** The test harness, script, or CLI command exists in the repository, but execution requires operator-supplied credentials or environment secrets (e.g., live provider API keys like `FASHN_API_KEY`, or configured AWS CLI credentials for production telemetry).
- **BLOCKED-EXTERNAL:** The procedure and exact CLI commands are fully documented and procedure-ready, but real execution is blocked on named external infrastructure prerequisites not present in this repository. It must **NEVER** be marked as completed locally.

### Currently Registered Blocked Items

| Section | Procedure | Classification | Named Blockers | Operational Constraint |
|---|---|---|---|---|
| **Section 7** | Real Staging RDS Backup Restore Drill | **BLOCKED-EXTERNAL** | slice-58 (staging deployment), slice-76 (disaster recovery) | Real AWS RDS PostgreSQL instance and staging cluster do not exist yet. Commands are procedure-ready for deployment day. The local container restore fallback is practice material only and must never be recorded as drill completion. |
| **Section 3** | Automated Dependabot Version PRs | **OPERATOR ACTION (Outside Scope)** | `.github/dependabot.yml` not created | The exact Dependabot YAML is documented in Section 3, but creating `.github/dependabot.yml` is an operator action outside `docs/runbooks/**`. Manual fallback commands (`versions:display-dependency-updates`, `npm outdated`) run locally today. |
| **Section 6** | V021 Nine-Step Workflow Drive | **NOT-YET-WIRED / BLOCKED-EXTERNAL** | Production bean, `AccountDeletionWorkflowStore` impl, and REST wiring absent | The V021 DDL schema exists, but `DefaultPrivacyService` has no Spring bean, `AccountDeletionWorkflowStore` has no production impl, and nothing in `src/main` writes `account_deletion_workflow`. Monthly deletion drill locally exercises the active runtime path (`DELETE /me` → `IdentityProvisioning.beginDeletion` → `AccountMediaPurge` + `users.deleted_at`). |

---

## 1. Provider Benchmark

- **Execution Class:** REQUIRES-CREDENTIALS (requires active provider credentials, e.g., `FASHN_API_KEY`).

### 1.1 Purpose & Seam Contract
The monthly provider benchmark evaluates AI virtual try-on (VTON) models and routing variants across the fixed benchmark dataset. It executes the offline evaluation harness to measure quality pass rates, identity fidelity, garment preservation, latency, cost per success, and category-level failure rates.

The benchmark harness is invoked via its frozen repository interface:

```java
public interface EvaluationBenchmarkRunner {
    BenchmarkReport run(BenchmarkRunSpec spec);
}
```

The `BenchmarkRunSpec` record requires all seven fields to be non-blank:

```java
BenchmarkRunSpec(
    String runId,             // Unique monthly run identifier (e.g. "benchmark-2026-09-fashn-max")
    String benchmarkVersion,   // Benchmark manifest version: "2026.01.0"
    String providerKey,        // Provider identifier (e.g. "fashn")
    String modelOrMode,        // Model or routing mode (e.g. "tryon-v1.6", "tryon-max")
    String promptVersion,      // Prompt specification version (e.g. "v1.2.0")
    String routingVersion,     // Routing policy version (e.g. "v1.0.0")
    List<String> caseIds       // Unique case IDs selected from manifest.json
);
```

### 1.2 Fixture Manifest & Coverage
Every monthly benchmark run must draw its test cases from the frozen manifest at `backend/src/main/resources/benchmark/manifest.json` (version `2026.01.0`). The manifest contains exactly 100 fashion items and 50 representative avatars across seven garment categories:
- `tops` (22 items)
- `bottoms` (18 items)
- `dresses` (16 items)
- `kurtas` (12 items)
- `lehenga sets` (10 items)
- `footwear` (12 items)
- `headwear` (10 items)

### 1.3 Execution Procedure
1. Execute one full benchmark run per provider and model route currently configured in the registry.
2. Load last month's stored baseline report from `docs/AI_EVALUATION.md` or the benchmark result archive.
3. Execute matched-case regression comparison using `BenchmarkRegressionComparator`. Deltas are calculated as candidate minus baseline (`delta = candidate - baseline`):
   - Quality pass rate delta
   - Identity preservation delta
   - Garment fidelity delta
   - Latency P50 and P95 delta
   - Cost per success delta
4. Record category-level performance into the provider performance store surface owned by slice-73 (`generation_provider_performance_daily` V023):
   - Metrics tracked: quality, latency, cost, failure rate, and category breakdown.
5. Synthesize findings to feed §74 provider routing policies:
   - Simple tops → fast provider route
   - Ethnic garments (kurtas, lehengas) → high-fidelity provider route
   - Footwear / Headwear → specialized category routes

### 1.4 Observability Metrics Reference
Verify provider benchmark telemetry against frozen observability metric constants:
- `generation.quality` (tags: `policy_version`, `decision`, `reason_code`)
- `generation.quality.evaluator.unavailable`
- `generation.terminal`
- `product.generation.latency`
- `product.generation.success`
- `product.generation.quality_rejection`

### 1.5 Healthy Targets & Decision Thresholds
- **Quality Pass Rate Floor:** $\ge 90.0\%$
- **Identity Fidelity Regression:** $\le 1.0\%$ relative drop
- **Overall Garment Fidelity Regression:** $\le 2.0\%$ relative drop
- **Failure Rate Ceiling:** $\le 10.0\%$ overall candidate failure rate (`failRateCeiling: <= 10.0% overall candidate failure rate`)
- **Per-Category Failure-Rate Increase:** $\le 2.0$ percentage points over baseline (`failRateMaxIncreaseAbsolute <= 2.0 percentage points increase`)
- **Latency Target:** P50 $< 15\text{s}$, P95 $< 30\text{s}$

### 1.6 Section 1 Sign-Off
```text
[ ] Section 1 Sign-Off: Provider Benchmark (REQUIRES-CREDENTIALS)
    Monthly Run ID:               ____________________________________________________
    Benchmark Manifest Version:   2026.01.0 (100 items, 50 avatars, 7 categories)
    Provider / Model Evaluated:   ____________________________________________________
    Quality Pass Rate:            ______% (Threshold: >= 90.0%)
    Identity Fidelity Delta:      ______% (Ceiling: <= 1.0% regression)
    Garment Fidelity Delta:       ______% (Ceiling: <= 2.0% regression)
    Overall Failure Rate:         ______% (Ceiling: <= 10.0% candidate failure rate)
    P50 / P95 Latency:            ______s / ______s
    Category Weaknesses Noted:    ____________________________________________________
    Routing Recommendation (§74): ____________________________________________________
    Performance Store V023 Saved: [YES / NO]
    Verdict:                      [PASS / AMBER / BLOCK / NO_CREDENTIALS]
    Operator: ___________________________ Date: ________________________
```

---

## 2. Cost Optimization

- **Execution Class:** LOCAL (telemetry review + configuration tuning).

### 2.1 Purpose & Primary Metric
The monthly cost optimization review audits virtual try-on unit economics, monitors monthly aggregate spend, and tunes admission policies and spend circuit-breakers.

Per technical implementation plan §43:
> **The most important AI cost metric is: Cost per quality-approved Look**

Every monthly cost audit must center on `costPerQualityApprovedLook`. A low cost per provider request is meaningless if quality rejections force repeated regenerations.

### 2.2 Durable Persisted Cost Audit
`CostAggregate` is backed by a process-local `HashMap`; it is a diagnostic seam only and is unavailable as a full-month source of truth across restarts, deployment replacement, or the API/worker process split. Use persisted attempt, Look, job, and quality facts for the monthly operational audit, then reconcile provider invoices separately.

```sql
WITH month_attempts AS (
    SELECT
        cost_status,
        currency,
        CASE
            WHEN cost_status = 'FINAL' AND actual_cost IS NOT NULL AND currency IS NOT NULL THEN actual_cost
            WHEN cost_status = 'ESTIMATED' AND estimated_cost IS NOT NULL AND currency IS NOT NULL THEN estimated_cost
            ELSE NULL
        END AS cost
    FROM generation_attempt
    WHERE started_at >= :month_start AND started_at < :month_end
),
month_cost AS (
    SELECT
        COUNT(*) AS total_attempts,
        CASE
            WHEN COUNT(*) FILTER (WHERE cost_status = 'UNKNOWN') > 0
              OR COUNT(*) FILTER (WHERE cost_status IS NULL) > 0
              OR COUNT(DISTINCT currency) FILTER (WHERE cost_status IN ('FINAL', 'ESTIMATED')) > 1
              OR COUNT(cost) <> COUNT(*) FILTER (WHERE cost_status IN ('FINAL', 'ESTIMATED'))
            ELSE SUM(cost)
        END AS total_spend,
        CASE
            WHEN COUNT(*) FILTER (WHERE cost_status = 'UNKNOWN') > 0
              OR COUNT(*) FILTER (WHERE cost_status IS NULL) > 0
              OR COUNT(DISTINCT currency) FILTER (WHERE cost_status IN ('FINAL', 'ESTIMATED')) > 1
            THEN NULL
            ELSE MIN(currency)
        END AS reporting_currency,
        COUNT(*) FILTER (WHERE cost_status = 'UNKNOWN') AS unknown_cost_attempts
    FROM month_attempts
),
approved_looks AS (
    SELECT COUNT(DISTINCT l.id) AS quality_approved_looks_count
    FROM look l
    JOIN generation_job gj ON gj.id = l.generation_job_id AND gj.user_id = l.user_id
    JOIN generation_attempt ga
      ON ga.generation_job_id = gj.id
     AND split_part(l.storage_key, '/', 4) LIKE ga.id::text || '.%'
    JOIN quality_result qr ON qr.generation_attempt_id = ga.id
    WHERE l.created_at >= :month_start AND l.created_at < :month_end
      AND l.deleted_at IS NULL
      AND l.quality_status = 'PASS'
      AND gj.status = 'COMPLETED'
      AND ga.technical_outcome = 'SUCCESS'
      AND qr.decision = 'PASS'
)
SELECT
    m.total_attempts,
    ROUND(m.total_spend::NUMERIC, 4) AS total_monthly_spend,
    m.reporting_currency,
    a.quality_approved_looks_count,
    ROUND((m.total_spend / NULLIF(a.quality_approved_looks_count, 0))::NUMERIC, 4)
        AS cost_per_quality_approved_look,
    ROUND(100.0 * m.unknown_cost_attempts / NULLIF(m.total_attempts, 0), 2)
        AS unknown_cost_status_pct
FROM month_cost m CROSS JOIN approved_looks a;
```

### 2.3 Accounting Rules
- Any UNKNOWN attempt, NULL `cost_status`, malformed known-cost row, or more than one known currency makes spend and unit cost `NULL`/UNKNOWN, never zero.
- The Look denominator uses only undeleted PASS Looks from completed jobs and binds approval to the publishing attempt encoded in the Look storage key.
- The proportion of generations with `costStatus = UNKNOWN` must remain $< 2.0\%$. A share $> 5.0\%$ indicates a broken provider webhook or billing parser and represents a P2 defect.

### 2.4 Tuning Levers & Controls
When cost trends exceed projections, the operator adjusts three primary system controls:
1. **Admission Policy Tuning (`AdmissionPolicy`):**
   - Repository default: `acceptedJobsPer24Hours = 5`, `maxActiveJobs = 2`, `maxProductsPerJob = 6`.
   - On cost pressure: Reduce `acceptedJobsPer24Hours` for unverified or free-tier users.
2. **Spend Circuit-Breakers (slice-75 surface in `backend/**/admission/**`):**
   - Daily provider spend limit
   - Per-user daily spend ceiling
   - Per-device daily generation cap
   - Breach mitigations: Disable HD generation, restrict multi-item composition, switch default routing to lower-cost provider models, or queue requests.
3. **Provider Routing (§74):**
   - Shift single-item standard preview jobs to `tryon-v1.6` fast path. Reserve `tryon-max` for complex outfits and ethnic categories.

### 2.5 Provider Credit Balance Check
Inspect provider accounts (e.g. FASHN dashboard) to confirm active balance and credit runway:
- **Healthy Runway:** $\ge 30$ days of projected monthly spend.
- **Amber Warning:** 7–30 days of credit runway remaining.
- **Red Alert (P1):** $< 7$ days of projected credit runway (escalate immediately to avoid provider cut-off).

### 2.6 Observability Metrics Reference
Verify cost telemetry against frozen observability metric constants:
- `generation.cost` (tag: `status`)
- `generation.cost.attempt` (tags: `provider`, `model`, `technical_outcome`)
- `product.look.cost.success`
- `product.look.cost.quality_approved`
- `product.retention`
- `product.core.quality_approved_looks_per_activated_user_week`

### 2.7 Section 2 Sign-Off
```text
[ ] Section 2 Sign-Off: Cost Optimization (LOCAL SQL)
    Total Monthly Attempts:              __________
    Total Monthly Spend:                 __________ [currency / BLOCKED]
    Quality-Approved Looks:              __________
    Cost per Quality-Approved Look:      __________ [currency / BLOCKED]
    Cost Status UNKNOWN Share:           ______% (Ceiling: < 2.0%)
    Provider Invoice Reconciled:         [YES / NO / BLOCKED-CREDENTIALS]
    Admission Policy Adjustments Made:   [NONE / TUNED: ______________________________]
    Circuit Breaker Thresholds Verified: [YES / ADJUSTED / BLOCKED]
    Verdict:                             [PASS / AMBER / RED / BLOCKED]
    Operator: ___________________________ Date: ________________________
```

---

## 3. Dependency Updates

- **Execution Class:** LOCAL (review and verification).

### 3.1 Purpose
Dependency updates prevent security drift, keep language runtimes current, and ensure framework compatibility across backend (Spring Boot / Java 21) and mobile (React Native 0.86 / TypeScript).

### 3.2 Automated Dependabot Configuration (Reference)
The canonical Dependabot configuration for this monorepo is documented below:

```yaml
version: 2
updates:
  - package-ecosystem: "maven"
    directory: "/backend"
    schedule:
      interval: "monthly"
  - package-ecosystem: "npm"
    directory: "/mobile"
    schedule:
      interval: "monthly"
```

*Operational Note:* **This configuration file (`.github/dependabot.yml`) is NOT created by this slice.** Enabling automated GitHub Dependabot PRs is an operator action outside the `docs/runbooks/**` path boundary.

### 3.3 Manual Dependency Review Commands (Available Today)
Until automated Dependabot PRs are activated, the operator executes the following manual inspection commands:

#### Backend (Maven / Java 21)
From the `backend/` directory:

```bash
cmd.exe /c mvnw.cmd versions:display-dependency-updates
```

*(On POSIX environments: `./mvnw versions:display-dependency-updates`)*

#### Mobile (React Native / npm)
From the `mobile/` directory:

```bash
npm outdated
```

### 3.4 Update Classification & Verification Policy
Dependencies are reviewed and updated under two distinct policies:

1. **Security-Relevant Platforms (High Risk):**
   - Targets: Spring Boot parent, Spring Security, Spring Modulith, React Native core, AWS SDK BOM, Jackson, PostgreSQL driver, and ONNX Runtime (or jsoup).
   - Policy: Must undergo full monorepo verification before merging:
     - Backend: `cmd.exe /c mvnw.cmd verify` (POSIX: `./mvnw verify`) (executed from `backend/`)
     - Mobile: `npm run typecheck` and `npm test -- --runInBand` (executed from `mobile/`)
2. **Routine Utility Libraries (Low Risk):**
   - Targets: Build plugins, test helpers, documentation tools.
   - Policy: Verified via package-scoped test runs (`cmd.exe /c mvnw.cmd test` executed from `backend/`, `npm test` executed from `mobile/`).

### 3.5 Section 3 Sign-Off
```text
[ ] Section 3 Sign-Off: Dependency Updates (LOCAL)
    Backend Outdated Dependencies Found: ______
    Mobile Outdated Dependencies Found:  ______
    Platform Upgrades Evaluated:         _____________________________________________
    Backend Verification (`mvnw verify`): [PASSED / SKIPPED / FAILED]
    Mobile Verification (`typecheck/test`):[PASSED / SKIPPED / FAILED]
    PRs Created or Scheduled:            _____________________________________________
    Verdict:                             [PASS / AMBER / RED]
    Operator: ___________________________ Date: ________________________
```

---

## 4. Privacy Audit

- **Execution Class:** LOCAL (checklist walk against repository controls).

### 4.1 Purpose & Authority
The monthly privacy audit verifies compliance with technical implementation plan §78 ("Privacy Review Checklist"), ADR-015 ("Raw images excluded from telemetry"), ADR-010 ("Ephemeral provider image transfer"), and statutory user-rights frameworks (GDPR / CCPA / DPDP).

### 4.2 §78 Privacy Review Checklist Walk
The operator walks all eleven checklist items from plan §78 verbatim. Each item is verified against its named repository control:

```text
[ ] explicit avatar-use consent — BLOCKED / NOT WIRED
    Repository evidence: V010__create_consents.sql provides only a generic schema. No production
    consent store or fail-closed check is mounted at avatar ingestion, so zero-consent ingestion
    is not blocked. This item must not be marked PASS from schema inspection.

[ ] third-party inference disclosure — BLOCKED / NOT WIRED
    Repository evidence: V010__create_consents.sql provides only a generic schema. No production
    consent persistence or fail-closed check is mounted before generation dispatch, so a job can
    proceed without a disclosure record. This item must not be marked PASS.

[ ] no training by default
    Repository Control: No-training commitments and non-retention policies are tracked via
    generic consent records (purpose, version, granted_at/revoked_at) in the V010 consent table;
    ephemeral provider image transfer is governed by PrivacyTransferPolicy / InputTransferMethod
    (ADR-010); the provider no-training commitment is a contractual statement documented in
    docs/PRD.md ('No Training on User Data', line 203) (FashnClient sets standard Authorization
    Bearer and Content-Type headers). Note that docs/PRIVACY.md is a reserved deferred stub.

[ ] deletion available
    Repository Control: Self-service deletion is exposed via authenticated REST endpoints:
    DELETE /vault/items/{id} (VaultController), DELETE /looks/{id} (LooksController),
    DELETE /avatars/{id} (AvatarsController), and DELETE /me (MeController). The domain-level
    PrivacyService interface exists in code but has no production bean/impl and is not yet wired
    to a controller or mobile UI.

[ ] user can delete My Model
    Repository Control: Operative control is DELETE /avatars/{id} → AvatarWorkflow.delete → mediaAccess.deletePersistent(storageKey)
    (wired AvatarWorkflow.delete/mediaAccess.deletePersistent), which removes avatar records and image assets from StorageDomain.AVATAR.
    Note: PrivacyService.deleteAvatar is the plan-§47 target contract with no production bean/impl yet.

[ ] user can delete Vault item
    Repository Control: Operative control is DELETE /vault/items/{id} → VaultService.delete → media.delete(ObjectRef(StorageDomain.VAULT, key))
    (wired VaultService.delete), which removes catalog items and purges media from StorageDomain.VAULT.
    Note: PrivacyService.deleteVaultItem is the plan-§47 target contract with no production bean/impl yet.

[ ] user can delete Look
    Repository Control: Operative control is DELETE /looks/{id} → LooksController.delete → objectStore().delete(ObjectRef(StorageDomain.LOOK, key))
    (wired LooksController.delete/objectStore().delete), which removes Look records and rendered assets from StorageDomain.LOOK.
    Note: PrivacyService.deleteLook is the plan-§47 target contract with no production bean/impl yet.

[ ] account deletion supported — BLOCKED / NOT WIRED
    Repository evidence: DELETE /me exposes a synchronous purge-first best-effort path, but the V021
    durable workflow is not mounted. A purge failure can leave the account active without a resumable
    checkpoint, so this item must not be marked PASS.

[ ] temp image retention enforced
    Repository Control: RetentionPolicy.DEFAULT_MAXIMUM_AGE = 24h configured via application
    property vault.retention.temporary-media-max-age: PT24H. Hourly RetentionCleanupJob purges
    all intermediate inputs, failed jobs, and provider outputs from StorageDomain.TEMP.

[ ] provider privacy documented
    Repository Control: ADR-010 documents ephemeral provider transfer and short-lived signed URLs;
    ADR-015 / LoggingPolicy documents payload sanitization; StorageDomain / plan §48 defines data
    isolation; and docs/PRD.md ('No Training on User Data') documents provider no-training commitments.
    docs/PRIVACY.md is a reserved deferred stub ('Status: deferred') and not an implemented privacy control.

[ ] analytics excludes raw media
    Repository Control: ADR-015, plan §41, and plan §54 strictly prohibit image bytes, base64 data,
    signed URLs, and S3 keys in logs, traces, or analytics. Validated via MicrometerProductMetrics.
```

### 4.3 Temporary Media Retention Inspection
Verify that `RetentionCleanupJob` is active and purging orphaned temporary objects:
- Verify configuration: `vault.retention.temporary-media-max-age = PT24H`.
- Query `StorageDomain.TEMP` bucket (`ai-fashion-vault-local-generation-temp` locally or S3 in production) to confirm zero objects linger beyond 24 hours.

### 4.4 Section 4 Sign-Off
```text
[ ] Section 4 Sign-Off: Privacy Audit (LOCAL)
    §78 Checklist Walked (11 of 11 items): [COMPLETE / DEFICIENCIES NOTED]
    Consent Enforcement at Processing Boundaries: [BLOCKED / NOT WIRED]
    Active REST Deletion Endpoints Live:   [PASS / FAIL]
    Temp Media Max Age (24h) Enforced:     [PASS / FAIL]
    RetentionCleanupJob Running Hourly:    [PASS / FAIL]
    ADR-015 Telemetry Media Exclusion:    [PASS / FAIL]
    Deficiencies / Corrective Actions:     _____________________________________________
    Verdict:                               [AMBER / RED / BLOCKED]
    Operator: ___________________________ Date: ________________________
```

---

## 5. Security Audit

- **Execution Class:** LOCAL (checklist walk + automated vulnerability scans).

### 5.1 Purpose & Authority
The monthly security audit verifies compliance with technical implementation plan §77 ("Security Review Checklist"), exercises automated dependency vulnerability scanners, and ensures defense-in-depth isolation across networks, credentials, and data stores.

### 5.2 §77 Security Review Checklist Walk
The operator walks all thirteen checklist items from plan §77 verbatim. Each item is verified against its named repository control:

```text
[ ] auth tokens verified server-side
    Repository Control: Spring Security filter chain ExternalBearerAuthenticationFilter (with
    JwtExternalTokenVerifier) in platform/security validates Firebase ID tokens cryptographically
    on every authenticated request; maps UID to internal UUID.

[ ] authorization on all user-owned entities
    Repository Control: Application services enforce ownership predicates (user_id matching
    SecurityContext principal) across Avatar, Vault, Studio, Generation, and Look domains.

[ ] signed upload URLs expire quickly
    Repository Control: S3 presigned URLs generated by S3ObjectStore (platform/objectstore) enforce
    strict short-lived expiration windows capped by S3ObjectStore.PUT_TTL_CAP (10 minutes / 600 seconds)
    and GET_TTL_CAP (5 minutes / 300 seconds); presignStagingPut hardcodes 10 minutes.

[ ] upload validation enabled
    Repository Control: AvatarPreflight / AvatarPreflightPolicy (avatar package) validate avatar photos;
    importer and general media validation runs through ImageMediaPolicy / ImageInspector (platform/objectstore)
    to validate MIME types, magic byte signatures, image dimensions, and byte size limits before accepting uploads.

[ ] storage buckets private
    Repository Control: Terraform provisions public-access blocks for all four S3 buckets. AWS
    applied state and production bucket settings are BLOCKED-EXTERNAL until verified in the target account.

[ ] DB not publicly exposed
    Repository Control: Terraform provisions private networking and a non-public RDS instance. AWS
    applied state and production DB ingress are BLOCKED-EXTERNAL until verified in the target account.

[ ] Redis not publicly exposed
    Repository Control: No Redis or ElastiCache is configured in the current V1 application or compose
    stack; the environment validator rejects REDIS_URL. Treat this item as N/A for this repository.

[ ] provider key in secret manager
    Repository Control: Terraform provisions an AWS Secrets Manager secret and injects it into both
    ECS task definitions. Applied state, secret population, and runtime resolution are
    BLOCKED-EXTERNAL until verified in the target account.

[ ] secrets excluded from logs
    Repository Control: Logback structured JSON logging pattern filters authorization headers,
    bearer tokens, and provider secrets. Verified via log sanitation test suite.

[ ] generation rate limiting
    Repository Control: AdmissionPolicy and V022 generation admission tables enforce per-user
    daily quota limits (default 5 generations/24h) and concurrency caps.

[ ] abuse detection
    Repository Control: Platform integrity adapters (§46) supporting Google Play Integrity
    and Apple App Attest on high-cost generation and avatar endpoints.

[ ] account deletion tested
    Repository Control: Monthly data deletion drill (Section 6) tests active deletion path
    (DELETE /me -> IdentityProvisioning.beginDeletion -> AccountMediaPurge) and verifies V021 target contract.

[ ] raw images excluded from telemetry
    Repository Control: ADR-015 and ObservationFacade span sanitizers ensure tracing spans,
    error messages, and Micrometer metrics never carry image bytes, base64, or presigned URLs.
```

### 5.3 Automated Vulnerability Scans

#### Backend: OWASP Dependency-Check
From the `backend/` directory:

Run OWASP dependency-check via direct plugin-goal invocation (requires no `pom.xml` edit):

```bash
cmd.exe /c mvnw.cmd org.owasp:dependency-check-maven:13.0.0:check -DnvdApiKeyEnvironmentVariable=NVD_API_KEY
```

*(On POSIX environments: `./mvnw org.owasp:dependency-check-maven:13.0.0:check -DnvdApiKeyEnvironmentVariable=NVD_API_KEY`)*

*Operational Note:* Verify the current plugin version during the Section 3 dependency-updates step; this command requires no modification to `backend/pom.xml`. If no NVD API key is set, the scan proceeds with public rate limits.

#### Mobile: npm Audit
From the `mobile/` directory:

```bash
npm audit --audit-level=high
```

- **Checklist Compliance:** 13 of 13 items verified PASS or explicitly N/A/BLOCKED according to the current deployment state.
- **CVE Threshold:** 0 Critical or High severity vulnerabilities in production dependencies.
- **Escalation:** Any Critical CVE without an immediate patch requires mitigation via feature flag (§66) or dependency exclusion.

### 5.5 Section 5 Sign-Off
```text
[ ] Section 5 Sign-Off: Security Audit (LOCAL)
    §77 Checklist Walked (13 of 13 items): [COMPLETE / DEFICIENCIES NOTED]
    Backend OWASP Dependency Scan:        [0 HIGH/CRITICAL / CVEs FOUND: ____________]
    Mobile npm Audit:                     [0 HIGH/CRITICAL / CVEs FOUND: ____________]
    Secret Isolation & Logging Verified:  [PASS / FAIL]
    VPC & Bucket Privacy Applied State:   [BLOCKED-EXTERNAL / PASS / FAIL]
    Corrective Actions Opened:            _____________________________________________
    Verdict:                              [PASS / AMBER / RED]
    Operator: ___________________________ Date: ________________________
```

---

## 6. Data Deletion Test

- **Execution Class:** **LOCAL / PARTIAL / BEST-EFFORT** (active synchronous path; diagnostic only) / **BLOCKED-EXTERNAL** (V021 durable workflow not mounted).

### 6.1 Purpose, Runtime Wiring Reality & Seam Architecture
The monthly deletion test documents current deletion behavior; it cannot certify compliant account deletion until a durable disable-first workflow is mounted.

#### Current Wiring Reality vs Target Contract
Operational honesty requires documenting the architecture that executes today versus the planned future orchestrator:
- **Active Runtime Path (LOCAL, PARTIAL/BEST-EFFORT):** Account deletion executes via `DELETE /me` (`MeController`) and `IdentityProvisioning.beginDeletion(userId, deletedAt)`. It synchronously runs registered purge components (`AvatarAccountMediaPurge`, `ShareImportAccountMediaPurge`, and `GenerationAccountMediaPurge`) before setting `users.deleted_at`. A purge exception therefore leaves the user undeleted, with no resumable workflow checkpoint. Individual entity deletions remain available through the per-entity endpoints.
- **V021 Nine-Step Workflow Drive (NOT-YET-WIRED / BLOCKED-EXTERNAL):** Flyway migration `V021__privacy_deletion_workflow.sql` created the `account_deletion_workflow` table and defined the target nine-step deletion lifecycle (`REQUESTED` $\rightarrow$ `DELETE_AVATARS` $\rightarrow$ `DELETE_VAULT` $\rightarrow$ `DELETE_LOOKS` $\rightarrow$ `DELETE_TEMPORARY_GENERATIONS` $\rightarrow$ `REVOKE_AUTH` $\rightarrow$ `REMOVE_PUSH_TOKENS` $\rightarrow$ `ANONYMIZE_OPERATIONAL_DATA` $\rightarrow$ `COMPLETE`). However, `DefaultPrivacyService` has no Spring `@Bean` definition, `AccountDeletionWorkflowStore` has no production implementation in `src/main`, and `PrivacyService` is injected into no production controller. Nothing in `src/main` currently writes or transitions rows in `account_deletion_workflow`.
- **Harness Prior Art:** The nine-step state machine and lease mechanics exist as verified test harness implementations in `AccountDeletionOrchestratorTest` and `AccountDeletionWorkflowTest`. These serve as prior art for when production workflow storage and controllers are wired.

### 6.2 Test Environment & Staging
- **Local:** Execute against the local compose stack:
  ```bash
  docker compose up -d postgres localstack
  ```
  LocalStack S3 endpoint: `http://127.0.0.1:4566`.
  Local storage buckets across all four `StorageDomain` boundaries:
  - `ai-fashion-vault-local-avatar-private` (`StorageDomain.AVATAR`)
  - `ai-fashion-vault-local-vault-assets` (`StorageDomain.VAULT`)
  - `ai-fashion-vault-local-look-private` (`StorageDomain.LOOK`)
  - `ai-fashion-vault-local-generation-temp` (`StorageDomain.TEMP`)
- **Staging:** When staging environment is available (post slice-58).

### 6.3 Active Path Diagnostic (LOCAL; NOT A COMPLIANCE PASS)
This procedure may expose happy-path behavior, but it must never be recorded as compliant deletion evidence. The current path is purge-first, synchronous, and has no durable retry ledger or failure-injection/restart proof.

1. **Provision Test Fixture:**
   - Create a dedicated test account with a known synthetic UUID (for example, `00000000-0000-0000-0000-000000000001`).
   - Create My Model avatar in `StorageDomain.AVATAR`.
   - Create two Vault items in `StorageDomain.VAULT`.
   - Create one completed Look in `StorageDomain.LOOK`.
   - Stage one temporary generation job in `StorageDomain.TEMP`.
2. **Exercise Per-Entity REST Deletion Surface:**
   - Delete one Vault item: `DELETE /vault/items/{id}` (verifies `VaultController` item deletion).
   - Delete Look: `DELETE /looks/{id}` (verifies `LooksController` look deletion).
   - Delete avatar profile: `DELETE /avatars/{id}` (verifies `AvatarsController` avatar deletion).
3. **Execute Full Account Deletion (`DELETE /me`):**
   - Issue authenticated `DELETE /me` for the test user.
   - Under the hood, `MeController` invokes `IdentityProvisioning.beginDeletion(userId, deletedAt)`.
   - `IdentityProvisioning` dispatches `AccountMediaPurge.purgeForUser(userId)` across all registered purge components (`AvatarAccountMediaPurge`, `ShareImportAccountMediaPurge`, `GenerationAccountMediaPurge`).
4. **Verify Object Removal Across All Four Storage Domains:**
   Query LocalStack S3 to confirm total media removal:
   - `StorageDomain.AVATAR` $\rightarrow$ 0 objects remaining for test user.
   - `StorageDomain.VAULT` $\rightarrow$ 0 objects remaining for test user.
   - `StorageDomain.LOOK` $\rightarrow$ 0 objects remaining for test user.
   - `StorageDomain.TEMP` $\rightarrow$ 0 objects remaining for test user.
5. **Verify Database Records & Hard Deletion:**
   - Query `users`: confirm `users.deleted_at` is set (non-null timestamp), marking the user account deleted.
   - Query `generation_job` and `generation_attempt`: confirm records for the test user are **HARD-DELETED** (zero rows returned from `SELECT COUNT(*) FROM generation_job WHERE user_id = ?`). `JdbcGenerationStore.deleteUserLinkedGenerationData` executes a hard delete across generation jobs, attempts, quality results, render steps, and notification events; generation records are not anonymized with scalars intact.
   - Confirm subsequent authenticated requests for the deleted user fail or reject access.

### 6.4 Target V021 Nine-Step Workflow Status (NOT-YET-WIRED / BLOCKED-EXTERNAL)
Flyway migration `V021__privacy_deletion_workflow.sql` defines the target state machine table `account_deletion_workflow`.
However, because `DefaultPrivacyService`, `DefaultAccountDeletionWorkflow`, and `AccountDeletionWorkflowStore` have no production bean or runtime wiring in `src/main`, **driving this nine-step workflow end-to-end cannot be executed locally today** and is classified as **NOT-YET-WIRED / BLOCKED-EXTERNAL**. It must never be recorded as completed in local operations until production wiring lands.

The documented target contract for future runtime wiring defines nine sequential steps:
1. `REQUESTED` $\rightarrow$ Initial deletion request received; account disabled.
2. `DELETE_AVATARS` $\rightarrow$ Avatar profiles and media purged from `StorageDomain.AVATAR`.
3. `DELETE_VAULT` $\rightarrow$ Vault items, collection associations, and media purged from `StorageDomain.VAULT`.
4. `DELETE_LOOKS` $\rightarrow$ Looks, constituent items, and rendered assets purged from `StorageDomain.LOOK`.
5. `DELETE_TEMPORARY_GENERATIONS` $\rightarrow$ Intermediate inputs and provider outputs purged from `StorageDomain.TEMP`.
6. `REVOKE_AUTH` $\rightarrow$ External identity tokens revoked.
7. `REMOVE_PUSH_TOKENS` $\rightarrow$ Push notification device tokens removed.
8. `ANONYMIZE_OPERATIONAL_DATA` $\rightarrow$ Target step for operational telemetry sanitation.
9. `COMPLETE` $\rightarrow$ Terminal workflow state reached.

*Offline Harness Prior Art:* For testing and development of this workflow before production wiring, refer to offline integration tests:
- `AccountDeletionOrchestratorTest` (executes against PostgreSQL Testcontainers)
- `AccountDeletionWorkflowTest`

### 6.5 Section 6 Sign-Off
```text
[ ] Section 6 Sign-Off: Data Deletion Test
    Execution Mode:                      [LOCAL (PARTIAL/BEST-EFFORT) / BLOCKED-EXTERNAL]
    Active REST Endpoints Exercised:     DELETE /vault/items/{id}, DELETE /looks/{id},
                                         DELETE /avatars/{id}, DELETE /me
    Happy-Path Observations:             _____________________________________________
    Failure Injection / Restart Proof:   [NOT AVAILABLE / BLOCKED]
    V021 Nine-Step Drive Status:         BLOCKED / NOT WIRED (schema only)
    Durable Disable-First Workflow:      BLOCKED / NOT WIRED
    Compliance Verdict:                  [BLOCKED / FAIL]
    Operator: ___________________________ Date: ________________________
```

---

## 7. Backup Restore Drill

- **Execution Class:** **BLOCKED-EXTERNAL** (Real staging drill) / **LOCAL** (Container sanity fallback).

### 7.1 Status: BLOCKED-EXTERNAL
The real post-production disaster recovery drill — restoring a production/staging RDS PostgreSQL database from automated backups, booting the backend application against it, running smoke tests, and verifying data integrity per technical implementation plan §76 — is **BLOCKED-EXTERNAL**.

**Named Blockers:**
1. **slice-58 (staging deployment):** The staging ECS cluster, VPC, and deployment pipelines are not yet provisioned.
2. **slice-76 (disaster recovery):** The production RDS automated backup lifecycle and cross-region DR infrastructure are pending delivery.

*Operational Honesty Constraint:* Because this repository contains neither an active AWS account nor a running RDS instance, the real drill cannot be run today. **The drill must NEVER be marked as completed in local development.**

### 7.2 Documented Staging Restore Procedure (Procedure-Ready)
When staging infrastructure arrives (post slice-58 and slice-76), the operator executes this exact AWS CLI procedure:

```bash
# Step 1: Inspect target instance and read LatestRestorableTime
aws rds describe-db-instances \
    --db-instance-identifier fashion-vault-staging-pg

# Step 2: Query automated backup window (EarliestTime and LatestTime)
aws rds describe-db-instance-automated-backups \
    --db-instance-identifier fashion-vault-staging-pg

# Step 3: Execute Point-in-Time Recovery (PITR) to a fresh target instance
# Note: Supply exactly one of --use-latest-restorable-time OR --restore-time <UTC>
aws rds restore-db-instance-to-point-in-time \
    --source-db-instance-identifier fashion-vault-staging-pg \
    --target-db-instance-identifier fashion-vault-staging-pg-drill \
    --use-latest-restorable-time

# Alternatively, restore to a specific timestamp within the backup window:
# aws rds restore-db-instance-to-point-in-time \
#     --source-db-instance-identifier fashion-vault-staging-pg \
#     --target-db-instance-identifier fashion-vault-staging-pg-drill \
#     --restore-time "2026-09-01T00:00:00Z"

# Step 4: Boot backend application pointing to the restored drill instance:
# SPRING_DATASOURCE_URL=jdbc:postgresql://<new-target-endpoint>:5432/postgres

# Step 5: Execute smoke test suite and verify Actuator health:
# curl -f http://<app-host>:8080/actuator/health
```

---

### 7.3 Local Sanity Fallback (Local Muscle-Memory Practice Only)
To maintain operational muscle memory and ensure PostgreSQL dump/restore tools and Flyway migrations execute without syntax errors, operators may execute this local sanity fallback against Docker Compose.

> **CRITICAL DISCLAIMER:**  
> **THIS IS NOT THE REAL RESTORE DRILL.**  
> Restoring a local Docker container does **NOT** test AWS RDS automated backups, does **NOT** test point-in-time recovery windows, and does **NOT** test cloud disaster recovery. It is local syntax practice only and must **NEVER** be recorded as fulfilling the monthly disaster recovery drill requirement.

#### Local Practice Steps (LOCAL)
1. **Ensure Local PostgreSQL is Running:**
   ```bash
   docker compose up -d postgres
   ```
2. **Create a Binary Database Dump:**
   ```bash
   docker exec -t ai-fashion-vault-postgres-1 pg_dump -U postgres -d postgres -F c -b -v -f /tmp/backup_drill.dump
   ```
3. **Spin Up a Fresh Scratch Container:**
   ```bash
   docker run --name postgres-restore-drill -e POSTGRES_PASSWORD=drill_pass -e POSTGRES_DB=postgres -p 5439:5432 -d postgres:18
   ```
4. **Copy and Restore the Dump into Scratch Container:**
   ```bash
   docker cp ai-fashion-vault-postgres-1:/tmp/backup_drill.dump ./backup_drill.dump
   docker cp ./backup_drill.dump postgres-restore-drill:/tmp/backup_drill.dump
   docker exec -t postgres-restore-drill pg_restore -U postgres -d postgres -v /tmp/backup_drill.dump
   ```
5. **Validate Migrations Against Restored Database:**
   From the `backend/` directory:
   ```bash
   cmd.exe /c mvnw.cmd -Dflyway.url=jdbc:postgresql://localhost:5439/postgres -Dflyway.user=postgres -Dflyway.password=drill_pass org.flywaydb:flyway-maven-plugin:12.4.0:validate
   ```
   *(On POSIX environments: `./mvnw -Dflyway.url=jdbc:postgresql://localhost:5439/postgres -Dflyway.user=postgres -Dflyway.password=drill_pass org.flywaydb:flyway-maven-plugin:12.4.0:validate`)*

   *Operational Note:* Verify the managed Flyway plugin version during the Section 3 dependency-updates step; this command uses direct plugin-goal invocation and requires no modification to `backend/pom.xml`.
6. **Teardown Scratch Container:**
   ```bash
   docker stop postgres-restore-drill && docker rm postgres-restore-drill
   ```

### 7.4 Section 7 Sign-Off
```text
[ ] Section 7 Sign-Off: Backup Restore Drill
    Real Staging RDS Restore Drill:       BLOCKED-EXTERNAL
    Blocker Justification:               slice-58 (staging deployment) & slice-76 (DR infra)
    Procedure Ready for Staging:          YES (AWS CLI PITR commands documented)
    Local Sanity Fallback Executed:      [YES / SKIPPED]
    Local Fallback Disclaimer Honored:   CONFIRMED (Local fallback NOT recorded as real drill)
    Verdict:                             BLOCKED-EXTERNAL
    Operator: ___________________________ Date: ________________________
```

---

## 8. Monthly Sign-Off & Auditable Record

### 8.1 Monthly Operations Summary Table
The on-call operator compiles the findings across all seven monthly review sections into this auditable record:

| # | Review Item | Execution Class | Primary Surface | Verdict (PASS / AMBER / RED / BLOCKED) | Operator Initials |
|---|---|---|---|---|---|
| 1 | Provider Benchmark | REQUIRES-CREDENTIALS | `EvaluationBenchmarkRunner` / Manifest `2026.01.0` | ____________________ | ________ |
| 2 | Cost Optimization | LOCAL SQL | Persisted `generation_attempt` + `look` / `quality_result` SQL; provider invoice reconciliation | ____________________ | ________ |
| 3 | Dependency Updates | LOCAL | `mvnw versions` / `npm outdated` | ____________________ | ________ |
| 4 | Privacy Audit | LOCAL | §78 Checklist; consent and durable account-deletion compliance BLOCKED / NOT WIRED | ____________________ | ________ |
| 5 | Security Audit | LOCAL | §77 Checklist (13 items) / OWASP / npm audit | ____________________ | ________ |
| 6 | Data Deletion Test | LOCAL (PARTIAL/BEST-EFFORT) / BLOCKED (V021) | DELETE /me & per-entity diagnostics; compliant verdict BLOCKED until durable workflow exists | ____________________ | ________ |
| 7 | Backup Restore Drill | BLOCKED-EXTERNAL | AWS RDS PITR / Slices 58 & 76 Blocked | BLOCKED-EXTERNAL | ________ |

---

### 8.2 Monthly Operational Sign-Off
```text
================================================================================
MONTHLY POST-PRODUCTION OPERATIONS AUDIT SIGN-OFF
================================================================================
Audit Month & Year: ____________________  Execution Date: ______________________

Monthly Review Verdict: [APPROVED / ACTION REQUIRED / ESCALATED]

Operational Summary & Critical Findings:
________________________________________________________________________________
________________________________________________________________________________
________________________________________________________________________________

AI Operations Lead:  __________________________ Date: __________________________
AI Platform Lead:    __________________________ Date: __________________________
Security / Privacy:  __________________________ Date: __________________________
Release Engineer:    __________________________ Date: __________________________
================================================================================
```

---

## Cross-References

- **Daily Operations Runbook:** `docs/runbooks/DAILY_OPERATIONS.md` (Governs daily incident triage, crash monitoring, and immediate alerting escalations).
- **Weekly Operations Runbook:** `docs/runbooks/WEEKLY_OPERATIONS.md` (Governs weekly AI regression benchmarking, WoW trend analysis, and tactical release gates).
- **AI Evaluation Methodology:** `docs/AI_EVALUATION.md` (Defines offline benchmark harnesses, non-inferiority metrics, and regression comparison math).
- **Privacy Architecture:** `docs/PRIVACY.md` (Reserved deferred stub, 'Status: deferred'; operative privacy architecture is documented in `ADR-010`, `ADR-015`, and `docs/PRD.md` 'No Training on User Data').
- **Technical Implementation Plan:**
  - §72: "Monthly Operations" (Authoritative monthly cadence definition)
  - §76: "Disaster Recovery" (Automated backup restore and PITR procedures)
  - §77: "Security Review Checklist" (Thirteen launch security controls)
  - §78: "Privacy Review Checklist" (Eleven launch privacy controls)
  - §43: "Cost Metrics" (Defines cost per quality-approved Look as primary metric)
- **Architecture Decision Records:**
  - `ADR-004`: PostgreSQL as source of truth
  - `ADR-010`: Ephemeral provider image transfer
  - `ADR-014`: Provider cost stored per generation
  - `ADR-015`: Raw images excluded from telemetry
