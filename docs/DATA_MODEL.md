# Data Model

Canonical home for the AI Fashion Vault persistence and domain data model. It states the
persistence that exists in this tree today.

**Current migration truth: PostgreSQL 18 accessed through Spring JDBC (`spring-jdbc`, no
JPA, no Redis), with Flyway migrations `V001`–`V023` in
`backend/src/main/resources/db/migration/` as the sole schema channel.** No runtime DDL.
History is forward-only — undo, repeatable, and baseline migrations are forbidden.
Migration standards, the file-name pattern, and the EXPAND/DEPLOY/MIGRATE/CONTRACT
release lifecycle are governed by
[docs/DATABASE_DEPLOYMENT_STRATEGY.md](DATABASE_DEPLOYMENT_STRATEGY.md).

**Frozen inventory:** [docs/CONTRACT_INVENTORY.md](CONTRACT_INVENTORY.md) maps each table
to its migration, its owning store class, and the endpoint that reaches it. Per-table
column detail lives there, not here.

**PR backlog:** [docs/AI_Fashion_Vault_MVP_Production_Readiness_PR_Plan.md](AI_Fashion_Vault_MVP_Production_Readiness_PR_Plan.md)
remains the PR backlog; any table or column it names is PROPOSED until its migration is
delivered.

## Migrations present in this tree

`V001__create_users.sql`, `V002__create_avatar_profiles.sql`,
`V003__create_share_imports.sql`, `V004__create_vault.sql`, `V005__create_collections.sql`,
`V006__create_studio.sql`, `V007__create_generations.sql`, `V008__create_looks.sql`,
`V009__create_notifications.sql`, `V010__create_consents.sql`,
`V011__avatar_image_preflight_policy_version.sql`,
`V012__generation_orchestration_state.sql`, `V013__generation_job_system.sql`,
`V014__generation_data_provenance.sql`, `V015__generation_retry_state.sql`,
`V016__generation_retry_strategy.sql`, `V017__notification_event_reason.sql`,
`V018__single_item_manual_product.sql`, `V019__share_import_resolver.sql`,
`V020__product_normalization.sql`, `V021__privacy_deletion_workflow.sql`,
`V022__generation_admission.sql`, `V023__provider_performance.sql`.

`V023` is the highest published version; nothing above it exists in this tree. Existing
migrations are never renumbered or reset — the ticket that adds a schema change selects
the next unused version at execution time.

## Tables confirmed by the inventory

- Identity and profile: `users` (V001), `avatar_profile` (V002), `avatar_consumed_upload`
  (V011:27).
- Import: `share_import` (V003:1, extended by V019).
- Vault and organization: `vault_item` (V004:9) with `source_reference` (V004:1);
  `collection` and `collection_item` (V005:1, :11).
- Studio: `studio_item` (V006:11) — schema only; read by the vault item-deletion in-use
  guard, never written.
- Generation: the generation store (V007, V013), work outbox and callback inbox,
  `generation_provider_capacity_slot` (V013:324), `generation_admission_counter` (V022:1),
  `generation_admission_reservation` (V022:11).
- Looks: `look` and `look_item` (V008:1).
- Deletion: `account_deletion_workflow` (V021).

## Access rules

- Table access lives in JDBC store classes: `JdbcVaultStore`, `JdbcLookStore`,
  `JdbcShareImportStore`, `JdbcAvatarStore`, `JdbcCollectionStore`, `JdbcGenerationStore`,
  `JdbcIdentityProvisioning`, `JdbcWorkOutboxStore`, `JdbcCallbackInboxStore`,
  `JdbcGenerationCapacityLeaseStore`, `JdbcGenerationRateLimiter`.
- Every owner-facing statement filters on `user_id`. The declared exceptions are the
  `source_reference` existence probe (that table has no user column) and the in-use guard,
  which runs only after the owner-scoped lock. See [docs/PRIVACY.md](PRIVACY.md).

## Settled repository-init decisions

- One private monorepo with canonical repository slug `ai-fashion-vault`.
- Generator-owned paths (`mobile/`, `backend/`, `compose.yaml`) are created by later initialization slices, not by Repository Initialization.

Those slices have since landed: `mobile/`, `backend/`, and `compose.yaml` exist in this
tree today.
