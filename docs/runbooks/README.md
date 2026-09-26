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


## Local Run: InferHub Generation

How to run the backend locally with InferHub as the image provider. Placeholders
only — never paste a real key, token, signed URL, or storage key into this file
(see Privacy Standing Rule).

### Environment variables

| Variable | Backing property | Default | Role |
|---|---|---|---|
| `VTON_IMAGE_API_KEY` | `vault.inferhub.api-key` | empty | InferHub provider key (primary local path) |
| `FASHN_API_KEY` | `vault.fashn.api-key` | empty | Optional second provider |

- Both resolve to an EMPTY default (`backend/src/main/resources/application.yaml:53,55`),
  which is what makes the no-key posture below work. Do not add either key to
  `VaultEnvironmentValidator`.
- `.env.example:9-10` ships both empty, so a fresh clone cannot inherit a key.

### No-key posture (default)

With no provider key set, or either key set blank:

- The app **boots** (booting unbootable was rejected; see `VaultEnvironmentValidator.java:26-28`).
- No `ProviderRegistry` bean exists at all — the composition returns a null FactoryBean
  (`ProviderRegistryComposition.java:24-30,44-46`).
- `POST /generations` is refused with **503 `GENERATION_PROVIDER_UNAVAILABLE`**
  (`GenerationCreationService.java:92`), raised before any reservation, image transfer,
  outbox row, or provider call (same file, lines 111-133 are downstream of line 92).
- The admission predicate is **provider-agnostic**: `FashnConfig.anyProviderConfigured`
  (`FashnConfig.java:66-70`), passed in from `GenerationsController.java:67`. The
  FASHN-only `providerConfigured` is deliberately not used for admission
  (`FashnConfig.java:62-64`) — an InferHub-only setup must not 503.

### Provider selection

With **both** keys set, InferHub is selected by default. Order
(`ProviderRegistryComposition.java:16-21,65-80`), evaluated against registered
providers only:

1. `vault.generation.routing.forced-provider` — when it names a registered provider.
2. `inferhub`
3. `fashn`
4. Alphabetically-first registered key (third-party providers only).

- Selection is deterministic and **never** bean construction order
  (`ProviderRegistryComposition.java:20-21`).
- `forced-provider` is unset/blank by default (`ProviderRoutingProperties.java:37,169-172`),
  so it never pins by accident. A non-blank value must match `[a-z0-9][a-z0-9._-]{0,63}`
  or the app fails fast at startup (`ProviderRoutingProperties.java:130-135`).

### Live E2E test — paid-call hazard

- `InferHubLiveGenerationTest` is gated by
  `@EnabledIfEnvironmentVariable(named = "VTON_IMAGE_API_KEY", matches = ".+")`: it
  SELF-SKIPS when the variable is absent, and fires a **real, paid** InferHub generation
  when it is present, retrying up to `MAX_ATTEMPTS = 3` with a 10 s backoff
  (`InferHubLiveGenerationTest.java`, the class annotation and the `MAX_ATTEMPTS` constant).
- **Operator rule: run the suite with `VTON_IMAGE_API_KEY` UNSET unless you intend a paid
  live generation.** Merely exporting the key to run the app locally also arms the test on
  the next `mvn test`.
- Port caveat: that test hard-codes `http://127.0.0.1:4566` for LocalStack, so it ignores
  `LOCALSTACK_PORT` and only works against the default port.

### Latency — do not cancel a slow generation

- The adapter's read timeout is **300 s** (`InferHubConfig.java:32`); connect timeout 10 s
  (`InferHubConfig.java:27`). Owner-verified worst case for one generation is **~170 s**
  (`InferHubConfig.java:29-30`).
- The durable queue is sized to cover a multi-minute in-flight job: visibility timeout
  120 s, heartbeat 45 s (`SqsGenerationWorkQueue.java:27-28` — `VISIBILITY_TIMEOUT_SECONDS`,
  `HEARTBEAT_INTERVAL_SECONDS`). The `vault.sqs.queue.visibility-timeout-seconds` /
  `heartbeat-seconds` block in `application.yaml` is NOT read by any code (no Java reader
  exists) and is slated for deletion — do not cite it.
- **Operator rule: do not cancel or restart a generation that appears slow.** A slow
  response is inside the sized envelope, not a hang.
- Observed figure, for contrast: the live E2E recorded **18.8 s and 20.6 s** synchronous
  submit round trips. For this adapter that round trip IS the complete generation — the
  response carries the finished image inline as a base64 PNG, with no async job to poll
  (unlike FASHN). Artifact: `_vtonfix-ceo2/e2e-inferhub-evidence.md` (section 3, t228/U10).
  One fast sample neither confirms nor refutes the ~170 s worst case; the 300 s sizing
  stands either way.

### Where the output lands

- Bucket: the TEMP bucket, `S3_TEMP_BUCKET` (`application.yaml:47`).
- Key shape: `temp/<userId>/<generationJobId>/<objectId>.<ext>`
  (`StorageKeys.java:59`), written by `writePrivateTemp` through the bounded put
  (`ObjectStoreGenerationOutputStore.java:79-92`).
- It lands in the **TEMP** boundary, not the look/vault boundary. A later claim step
  repositions it to `/looks/` (`GenerationClaimCommand.java:33`).

### Known limitation

The live E2E covers the **adapter seam**, not the authenticated `POST /generations`
HTTP leg. No Firebase ID token can be minted on this PC, so sign-in → upload →
generation → look was **owner-blocked** and is not claimed
(`_vtonfix-ceo2/e2e-inferhub-evidence.md` section 5). Read that artifact as proof the
provider call, inline-PNG decode, and persistence work — not as a full sign-in round trip.

## Privacy Standing Rule

In accordance with ADR-015, §41, and §54:
- Reporting queries select only aggregates, opaque identifiers, and non-user operational dimensions (such as provider, category, source site, and reason codes); `user_id` may appear only within `COUNT(DISTINCT ...)` in those queries.
- The monthly local deletion test is the narrow exception: use a dedicated synthetic test-account ID only for that test's verification queries, and never copy it into runbooks or incident channels.
- **Never** select, log, copy, or paste raw image bytes, base64 data, signed S3 URLs, storage keys, authentication tokens, or provider secrets into incident channels or runbook records.

## Daily Routine

Every morning, the on-call engineer completes the nine-signal inspection defined in `DAILY_OPERATIONS.md` and records findings using a new copy of `DAILY_OPERATIONS_CHECKLIST.md`.
