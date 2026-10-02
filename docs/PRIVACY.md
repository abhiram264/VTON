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

- **F16: the mounted deletion path is not the durable workflow, but it is now
  BARRIER-FIRST.** `user/JdbcIdentityProvisioning.beginDeletion`
  loads the user, returns unchanged if already deleted, then **commits
  `UPDATE users SET deleted_at ... WHERE id = ? AND deleted_at IS NULL` in its own
  transaction BEFORE running any registered `AccountMediaPurge.purgeForUser`.**
  The ordering is deliberate and is the C17 requirement: everything that gates new
  work reads the account's `deleted_at`, so the barrier must be durable before any
  irreversible cleanup, or a purge failure could leave media destroyed while the
  account still looks active. The conditional `deleted_at IS NULL` makes concurrent
  deleters converge on one winner, and a loser purges nothing. The purge list is
  stored, not discarded: it is resolved from the injected `ObjectProvider` by
  `orderedPurges`, and supplied in production by
  `IdentityProvisioningFactoryBean.getObject`
  (`user/IdentityProvisioningConfiguration.java`), which registers three purges —
  `avatar/AvatarConfiguration.java`,
  `importer/ImporterConfiguration.java` and
  `platform/generation/GenerationProcessorConfiguration.java`.
  Consequences, stated honestly:
  (a) a failing purge **propagates** (the caller can retry cleanup) while the account
  stays deleted — `AccountDeletionOrchestratorTest.beginDeletionWithThrowingPurgeKeepsTheBarrierSet`
  and `IdentityProvisioningTest.beginDeletionBarrierSurvivesMediaPurgeFailure` assert
  exactly that; the earlier documented behaviour ("a failing purge leaves the account
  undeleted") was the superseded pre-barrier design and no longer holds;
  (b) the ordering is asserted from inside the purge callback by
  `AccountDeletionOrchestratorTest.beginDeletionCommitsTheBarrierBeforePurgingMedia`
  and `factoryBeanBeginDeletionCommitsTheBarrierBeforePurging`, which observe
  `deleted_at` ALREADY set at the moment the purge runs;
  (c) **open question for the owner, not a verified claim:** because `beginDeletion`
  returns early for an already-deleted user, a purge that failed is not re-run by a
  later `beginDeletion` call. Whether cleanup retries come from the V021 workflow
  store or are missing entirely is not established here.
  Deletion completeness is **still not** established: a purge that returns normally is
  trusted, and a crash after the barrier leaves the account deleted with media not yet
  erased.
- **Export is not mounted.** `PrivacyService.exportUserData`
  (`DefaultPrivacyService.java:48-63`) is unreachable in production; there is no
  production `PrivacyResourcePort` and no export endpoint. No user can obtain an export.
- **Consent is now stored, read and enforced** (this replaces an earlier
  "schema-only" note that the merged C06 work superseded).
  `V010__create_consents.sql` defines `consent`
  (`id`, `user_id`, `purpose`, `version`, `granted_at`, `revoked_at`);
  `privacy/ConsentService` + `privacy/JdbcConsentStore` now write, read and withdraw
  rows over that table through a closed `ConsentPurpose` vocabulary
  (`body_image_processing`, `third_party_ai_transfer`), `platform/web/ConsentsController`
  exposes it owner-scoped over HTTP (`GET /consents`, `POST|DELETE /consents/{purpose}`),
  and the provider-transfer boundary consults it before any media lookup
  (`platform/generation/AuthorizedGenerationMediaResolver` + `ConsentGate`, refusal
  mapped to the `CONSENT_REQUIRED` terminal reason). Withdrawal therefore prevents new
  processing rather than being recorded and ignored. Whether such a row would be
  deleted on account closure is still a product-owner decision rather than an
  implementation gap: rows are retained, and the reasoning is under
  "Consent record retention" below.
- Deletion completeness is therefore **not** established today: nothing in this tree
  proves complete erasure.
- **Per-object delete suppression now applies only to the temp-retention sweep.** The
  account-erasure path has its own delete: `purgePrefix`
  (`platform/objectstore/S3ObjectStore.java:476-494`) calls `deleteForErasure` (`:489`),
  which logs an `error` naming the undeleted bucket and key
  (`platform/objectstore/S3ObjectStore.java:509-518`, message at `:514`) and then throws
  `ObjectStoreUnavailableException` (`:515-516`). The temp sweep remains best-effort:
  `reconcile` (`:496-507`) calls `deleteQuietly` (`:505`), which logs and swallows every
  `RuntimeException` (`:520-529`) because a later sweep retries the object. A single
  object's delete can therefore still fail — **erasure is not infallible** — but on the
  erasure path that failure is now reported rather than hidden, and it aborts
  `beginDeletion` before the account is marked deleted.
- **F10 is stale.** The JWKS document is now bounded: `LazyJwksDocument` re-fetches after
  a 1-hour interval (`platform/security/ExternalTokenVerifierConfiguration.java:31`,
  `:58`, `:63`), and `JwtExternalTokenVerifier` refreshes parsed keys after 1 hour and
  force-refreshes on an unknown `kid`
  (`platform/security/JwtExternalTokenVerifier.java:27`, `:174-181`). Signing keys are no
  longer cached indefinitely. (This is the readiness plan's identity F10, at
  `AI_Fashion_Vault_MVP_Production_Readiness_PR_Plan.md:82`; the plan's F16 at `:94` is the
  deletion finding above.)

## Consent record retention

- **Consent records are kept, not deleted.** The product owner decided on
  2026-09-26 that a consent row stays in the database after the account it belongs
  to is closed. The database enforces the other half of this rather than leaving
  it to convention: every row carries a `user_id` pointing at the user with
  `ON DELETE RESTRICT` (`V010__create_consents.sql:3`), so the database refuses a
  hard delete of the user row while the consent row exists. That is a rule about
  rows in the database, not about your account — closing your account marks it
  deleted (`users.deleted_at`) and takes it out of use, because a marked-deleted
  account can no longer be authenticated
  (`platform/security/ExternalBearerAuthenticationFilter.java:90`) — and the
  consent row is what stays behind, as the audit trail. Nothing since has changed
  this (no later migration alters the `consent` table: no `ALTER TABLE consent`
  exists anywhere in `V001`–`V024`), and no code in this tree deletes a consent
  row.
- **What a retained row actually holds** (`V010__create_consents.sql:2-7`): a row
  id, the `user_id` of the person who agreed, a `purpose` (free-form text — the
  schema defines no fixed set of purposes), the `version` of the terms they agreed
  to, and two timestamps — `granted_at`, plus `revoked_at` if they later withdrew.
  A consent row holds no photo, no video or other media, no model input, and
  nothing sent to or received from an AI provider. The only personal information in
  it is the internal `user_id`, a system-generated identifier rather than a name,
  email address, or phone number.
- **How long: indefinitely, per that 2026-09-26 decision.** There is no
  expiry period, time-to-live, cleanup sweep, or scheduled job that removes consent
  rows. The one retention scheduler in the tree
  (`platform/retention/RetentionConfiguration.java:52`) sweeps temporary media in
  object storage, not database rows.
- **Why the row is kept.** A consent row is the only lasting evidence of *what a
  person agreed to, which version of the terms they agreed to, and from what
  date*. Keeping it after the account is closed is what makes the question "was
  this processing allowed, and under which terms?" answerable long after the
  account itself is gone. Deleting the row on account closure would delete the
  proof that the agreement ever existed.
- **Withdrawing consent does not delete the record.** `revoked_at` is written onto
  the same row (`V010__create_consents.sql:7`); the row itself stays. The only rule
  the code enforces about it is that a withdrawal cannot be dated before the grant
  (`privacy/Consent.java:24-26`). A withdrawn consent therefore keeps its original
  `granted_at` and stays readable, leaving the row as the record of both the grant
  and the withdrawal.
- **What this means for someone asking to be forgotten.** The only identifier kept
  is the internal `user_id`. Deleting an account here sets `users.deleted_at`
  rather than removing the user row (`user/JdbcIdentityProvisioning.java:113-117`),
  so that identifier stays live and a retained consent row can still be tied back
  to a soft-deleted account. The trade-off that decision accepts: the consent
  record is kept, and because your internal account ID stays in it, that record
  could still be linked back to your closed account. The account-deletion
  workflow that would otherwise erase such records exists but is not wired in
  (`docs/PRIVACY.md:25-30`, `:31-43`); its `ANONYMIZE_OPERATIONAL_DATA` step
  (`privacy/AccountDeletionStep.java:11`) is written down but never switched on,
  so no code runs it today. What survives is a uuid, two text fields, and two
  timestamps — no image and no media.
- **What is not true today.** No production code writes or reads the `consent`
  table (`docs/DATA_MODEL.md:73-74`), so in this tree no consent is actually being
  recorded or enforced. The retention described above is what the schema is
  *designed* to do, not a description of a live audit trail — there is nothing to
  retain yet. Consent must still be captured and enforced before processing; that
  requirement stands.

## Not claimed

- No compliance certification (GDPR, CCPA, or any other) is claimed or implied here, or
  by the existence of the controls above.
- No claim that temporary-media deletion occurs at exactly 24 hours.
- No claim that account deletion is durable, resumable, or complete today.
- No claim that erasure is infallible: an individual object delete can fail. On the
  erasure path that failure is logged and rethrown, leaving the account undeleted; on the
  temp-retention sweep it is logged and swallowed until a later sweep retries.
- No claim that a user can obtain a data export or that consent is recorded or enforced:
  neither has a production writer. Whether a retained consent row is ever deleted is a
  separate matter, answered under "Consent record retention" above.

## Settled repository-init decisions

- One private monorepo with canonical repository slug `ai-fashion-vault`.
- Generator-owned paths (`mobile/`, `backend/`, `compose.yaml`) are created by later initialization slices, not by Repository Initialization.

Those slices have since landed: `mobile/`, `backend/`, and `compose.yaml` exist in this
tree today.
