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
- **Account deletion workflow pieces.** `DELETE /me`
  (`platform/web/MeController.java:33`) calls `provisioning.beginDeletion` (:40), and the
  V021 `account_deletion_workflow` schema and its orchestrator exist.
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

- **F16: the mounted deletion path is purge-first, not the durable workflow.** It purges
  media before marking the user deleted and suppresses per-object delete failures; the
  durable resumable workflow is not what runs. PR-17 owns this.
- Deletion completeness is therefore **not** established today: nothing in this tree
  proves complete erasure, and the passing deletion tests describe the current ordering.
- **F10**: Firebase verification caches signing keys indefinitely (PR-03).

## Not claimed

- No compliance certification (GDPR, CCPA, or any other) is claimed or implied here, or
  by the existence of the controls above.
- No claim that temporary-media deletion occurs at exactly 24 hours.
- No claim that account deletion is durable, resumable, or complete today.

## Settled repository-init decisions

- One private monorepo with canonical repository slug `ai-fashion-vault`.
- Generator-owned paths (`mobile/`, `backend/`, `compose.yaml`) are created by later initialization slices, not by Repository Initialization.

Those slices have since landed: `mobile/`, `backend/`, and `compose.yaml` exist in this
tree today.
