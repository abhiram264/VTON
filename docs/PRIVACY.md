# Privacy

Canonical home for AI Fashion Vault privacy documentation. It records which privacy
controls the code in this tree actually implements and which are unfinished. It is not a
compliance certification and makes no legal claim.

**Frozen inventory:** [docs/CONTRACT_INVENTORY.md](CONTRACT_INVENTORY.md) §6 and §8 carry
the source-confirmed authorization and owner-scoping evidence summarized here.
**PR backlog:** [docs/AI_Fashion_Vault_MVP_Production_Readiness_PR_Plan.md](AI_Fashion_Vault_MVP_Production_Readiness_PR_Plan.md)
remains the backlog; PR-17 owns the deletion rework.

## Implemented today

- **Owner isolation.** Every owner-facing persistence statement is scoped by the
  authenticated principal. `JdbcVaultStore` carries `user_id` at :49, :60, :64, :68, :89,
  :94, :105; all of `JdbcLookStore` :143-183 is owner-scoped across its three lookups;
  `JdbcShareImportStore` carries `user_id` at :149, :162, :210, :259, :278, :286, :294,
  :300, :306. Declared exceptions: `sourceExists` (:55-57) probes `source_reference`, which
  has no user column at all, and the in-use guard (:96-98, repeated at :103) runs only
  after the owner-scoped `lockForDelete` (:93-95). The two attempt-token-scoped updates
  (:193, :243) are keyed by a lease token issued for the owner's own attempt.
- **Account deletion request path.** `DELETE /me`
  (`platform/web/MeController.java:33-34`) calls `provisioning.beginDeletion`
  (`platform/web/MeController.java:40`), and the V021 `account_deletion_workflow` table
  exists (`V021__privacy_deletion_workflow.sql:1-13`). The durable workflow is **not**
  wired to this path: no production class implements `AccountDeletionWorkflowStore`
  (`privacy/AccountDeletionWorkflowStore.java:9`), and the only implementations of
  `PrivacyService`/`AccountDeletionWorkflow` are constructed only in test doubles
  (`src/test/java/com/aifashionvault/privacy/PrivacyServiceTest.java:63`,
  `AccountDeletionWorkflowTest.java:113`).
- **Durable deletion workflow logic (present, unmounted).**
  `privacy/DefaultAccountDeletionWorkflow.java:38-69` runs steps in enum order
  (`AccountDeletionStep.java:3-12`: `REQUESTED`, `DELETE_AVATARS`, `DELETE_VAULT`,
  `DELETE_LOOKS`, `DELETE_TEMPORARY_GENERATIONS`, `REVOKE_AUTH`, `REMOVE_PUSH_TOKENS`,
  `ANONYMIZE_OPERATIONAL_DATA`, `COMPLETE`; next-step map at
  `DefaultAccountDeletionWorkflow.java:71-78`) under a 5-minute lease
  (`:15`, `:49`). A step failure lands in `store.fail` with a safe code
  (`:61-65`); per-step failures are **not** suppressed. States are
  `REQUESTED`, `RUNNING`, `RETRYABLE_FAILURE`, `COMPLETE` (`AccountDeletionState.java:3-8`),
  matching the V021 CHECK constraints (`V021__privacy_deletion_workflow.sql:25-30`) and
  its nine allowed `next_step` values (`:14-24`). No `PrivacyDeletionPort` exists for any
  step in production; the constructor rejects a port set that does not cover every step
  (`:32-34`), so the workflow cannot start without all nine.
- **Per-object retention of temporary media.** `vault.retention.temporary-media-max-age:
  PT24H` (application.yaml:22-23) is enforced by an hourly `@Scheduled` sweep
  (`platform/retention/RetentionConfiguration.java:52`) over `StorageDomain.TEMP`, plus a 1-day
  lifecycle expiration installed on the temp bucket by LocalStack
  (`infra/docker/localstack_ready.py:95-107`). S3 lifecycle timing is eventual, not a
  precise 24-hour erasure guarantee.
- **Media-transfer boundaries** (ADR-010, ADR-015): provider transfers are ephemeral, and
  import responses return no source URL, object key, or media data.
- **Authentication boundary** (ADR-007): bearer-only and fail-closed, with the default
  verifier rejecting rather than trusting; the actor is never read from a body or header.

## Pending — not implemented

- **F16: the mounted deletion path is not the durable workflow, and does not purge at
  all.** `user/JdbcIdentityProvisioning.beginDeletion`
  (`:92-114`) only loads the user (`:100`), marks them deleted (`:107`) and issues one
  `UPDATE users SET deleted_at` (`:108-112`). The `AccountMediaPurge` list it accepts
  (`:51-64`) is never stored or invoked — the constructor null-checks the argument
  (`:58-60`) and discards it, so **no media is purged on the mounted path at all**, and
  deletion is mark-only, not purge-first. The passing tests
  `AccountDeletionOrchestratorTest.beginDeletionPurgesMediaBeforeMarkingDeleted`
  (`src/test/java/com/aifashionvault/platform/AccountDeletionOrchestratorTest.java:75-89`)
  and `factoryBeanBeginDeletionInvokesRegisteredPurgeBeforeDeletedAt` (`:140-158`) assert
  a purge that production does not perform; those assertions would fail against
  `JdbcIdentityProvisioning` as written and are evidence of the defect, not of the
  behaviour. PR-17 owns this.
- **Export is not mounted.** `PrivacyService.exportUserData`
  (`DefaultPrivacyService.java:48-63`) is unreachable in production; there is no
  production `PrivacyResourcePort` and no export endpoint. No user can obtain an export.
- **Consent is schema-only.** `V010__create_consents.sql:1-8` defines `consent`
  (`id`, `user_id`, `purpose`, `version`, `granted_at`, `revoked_at`), and
  `privacy/Consent.java:6` models it, but **no code writes or reads the table** — the
  only `consent` references in main source are the migration and the record. The
  captured value is enforced nowhere. Consent must still be captured and enforced
  before processing; that requirement stands.
- Deletion completeness is therefore **not** established today: nothing in this tree
  proves complete erasure.
- Per-object delete suppression remains real for the object-store sweep itself:
  `platform/objectstore/S3ObjectStore.purgePrefix` (`:472-490`) calls `deleteQuietly`
  (`:485`), which swallows every `RuntimeException` (`:505-512`, comment
  "Per-object delete failures must not stop the sweep"). On the mounted path this is
  currently unreachable because no purge runs.
- **F10 is stale.** The JWKS document is now bounded: `LazyJwksDocument` re-fetches after
  a 1-hour interval (`platform/security/ExternalTokenVerifierConfiguration.java:31`,
  `:58`, `:63`), and `JwtExternalTokenVerifier` refreshes parsed keys after 1 hour and
  force-refreshes on an unknown `kid`
  (`platform/security/JwtExternalTokenVerifier.java:27`, `:174-181`). Signing keys are no
  longer cached indefinitely. (This is the readiness plan's identity F10, at
  `AI_Fashion_Vault_MVP_Production_Readiness_PR_Plan.md:82`; the plan's F16 at `:94` is the
  deletion finding above.)

## Not claimed

- No compliance certification (GDPR, CCPA, or any other) is claimed or implied here, or
  by the existence of the controls above.
- No claim that temporary-media deletion occurs at exactly 24 hours.
- No claim that account deletion is durable, resumable, or complete today.
- No claim that the mounted `DELETE /me` path purges any media: it sets `deleted_at` only
  (`JdbcIdentityProvisioning.java:108-112`).
- No claim that a user can obtain a data export or that consent is recorded or enforced:
  neither has a production writer.

## Settled repository-init decisions

- One private monorepo with canonical repository slug `ai-fashion-vault`.
- Generator-owned paths (`mobile/`, `backend/`, `compose.yaml`) are created by later initialization slices, not by Repository Initialization.

Those slices have since landed: `mobile/`, `backend/`, and `compose.yaml` exist in this
tree today.
