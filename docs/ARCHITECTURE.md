# Architecture

Canonical home for AI Fashion Vault system architecture. It describes the system that
exists in this tree today; later-ticket work is referenced, never described as built.

**Frozen inventory:** [docs/CONTRACT_INVENTORY.md](CONTRACT_INVENTORY.md) — the
source-confirmed route, store, and configuration census (ten controllers / 36 mounted
mappings, tables mapped to migrations, runtime config keys, trust boundaries). This
document summarizes; the inventory is the authority for counts, names, and line anchors.

**PR backlog:** [docs/AI_Fashion_Vault_MVP_Production_Readiness_PR_Plan.md](AI_Fashion_Vault_MVP_Production_Readiness_PR_Plan.md)
remains the PR backlog (PR-01 … PR-26) for undelivered work. It states intent; where it
and this document disagree about current behavior, this document and the inventory win.

## Current shape

- **One private monorepo**, package-by-feature backend, one React Native app.
- **Modular monolith** (ADR-001): one Spring Boot service with feature packages under
  `backend/src/main/java/com/aifashionvault/` — `vault`, `avatar`, `collection`,
  `importer`, `look`, `studio`, `generation`, `admission`, `privacy`, `retention`,
  `notification`, `featureflag`, `integrity`, `normalization`, `analytics`, `user`,
  `platform`, plus the cross-cutting `shared` value-type kernel
  (`backend/src/main/java/com/aifashionvault/shared/UserId.java:1`; 27 files), which is
  a Modulith module in its own right — `platform`'s `allowedDependencies` lists it at
  `backend/src/main/java/com/aifashionvault/platform/package-info.java:5`.
  Boundaries are enforced, not conventional: spring-modulith 2.1.0
  (`backend/pom.xml:31`) checks them in `ModulithVerificationTest.java:10`, with
  `platform`'s `allowedDependencies` at
  `backend/src/main/java/com/aifashionvault/platform/package-info.java:1-19` and the
  named interfaces `generation :: vton`
  (`backend/src/main/java/com/aifashionvault/generation/vton/package-info.java:1`) and
  `generation :: benchmark`
  (`backend/src/main/java/com/aifashionvault/generation/benchmark/package-info.java:1`).
  `importer` and `generation` exist both as top-level modules and as `platform/<name>`
  adapter directories that hold the web/worker wiring.
- **Backend stack** (ADR-003): Spring Boot 4.1.0 on Java 21, Spring MVC, Spring JDBC —
  no JPA, no Redis.
- **Persistence** (ADR-004): PostgreSQL 18 is the source of truth and Flyway is the sole
  schema channel. See [docs/DATA_MODEL.md](DATA_MODEL.md).
- **Queue and object storage** (ADR-005, ADR-006): AWS SQS and S3 through AWS SDK v2;
  locally both are emulated by LocalStack (`s3,sqs`, ADR-016).
- **Runtime topology (local)**: `compose.yaml` runs PostgreSQL and LocalStack only. The
  backend and the mobile app run on host toolchains (`python scripts/run_backend.py`;
  `mobile/` npm scripts).
- **HTTP surface**: ten `@RestController` classes, 36 mappings — nine live under
  `backend/src/main/java/com/aifashionvault/platform/web/` and the tenth is
  `backend/src/main/java/com/aifashionvault/featureflag/FeatureFlagController.java:7`,
  so a `platform/web`-only scan undercounts. Bearer-only fail-closed authentication,
  actuator `health`+`metrics` only. See [docs/API_CONTRACTS.md](API_CONTRACTS.md).
- **Generation path**: `POST /generations` → durable SQS work queue → worker → callback
  inbox. FASHN is the only constructed VTON provider (ADR-008), and `FASHN_API_KEY` is a
  mandatory startup property today.
- **Mobile** (ADR-002): React Native 0.86.2 / React 19.2.3 with Android and iOS
  share-import hosts. Native builds are not executed or verified on this host.
- **Identity** (ADR-007): external bearer verification mapped to an internal `UserId`;
  the authenticated principal is the only actor identity.
- **Attestation**: `vault.generation.attestation-required` gates `POST /generations`,
  `vault.avatar.attestation-required` gates `POST`/`PATCH /avatars`, and
  `vault.integrity.required` is verifier strictness only.
- **Deletion and retention**: `DELETE /me` starts account deletion; temporary media is
  swept hourly at PT24H plus a LocalStack 1-day lifecycle. See
  [docs/PRIVACY.md](PRIVACY.md).

## Not current architecture

Routes and components named only by the backlog are PROPOSED and unmounted — currently
`/studio/sessions/**` and the minimal import-confirmation endpoint; no `/studio` handler
exists in this tree. The studio *domain* is nonetheless built here and only unexposed
over HTTP: `backend/src/main/java/com/aifashionvault/studio/agent/StudioAgent.java:20`
declares the ten-tool `ALLOWED` set, capped at ten calls per execution at
`backend/src/main/java/com/aifashionvault/studio/agent/StudioAgent.java:24`, alongside
`backend/src/main/java/com/aifashionvault/studio/StudioSession.java:1` and
`backend/src/main/java/com/aifashionvault/studio/StudioItem.java:1`. Provider selection,
no-provider generation behavior, and share-link removal are PR-02/PR-16 work.

## Settled repository-init decisions

- One private monorepo with canonical repository slug `ai-fashion-vault`.
- Generator-owned paths (`mobile/`, `backend/`, `compose.yaml`) are created by later initialization slices, not by Repository Initialization.

Those slices have since landed: `mobile/`, `backend/`, and `compose.yaml` exist in this
tree today, and the decisions stand as the ownership boundary that keeps Repository
Initialization out of them.
