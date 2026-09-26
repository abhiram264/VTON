# Data Model

Canonical home for the AI Fashion Vault persistence and domain data model. It states the
persistence that exists in this tree today.

**Current migration truth: PostgreSQL 18 accessed through Spring JDBC (`spring-jdbc`, no
JPA, no Redis), with Flyway migrations `V001`–`V024` in
`backend/src/main/resources/db/migration/` as the sole schema channel.** No runtime DDL.
History is forward-only — undo, repeatable, and baseline migrations are forbidden.
Migration standards, the file-name pattern, and the EXPAND/DEPLOY/MIGRATE/CONTRACT
release lifecycle are governed by
[docs/DATABASE_DEPLOYMENT_STRATEGY.md](DATABASE_DEPLOYMENT_STRATEGY.md).

**Route inventory:** [docs/CONTRACT_INVENTORY.md](CONTRACT_INVENTORY.md) is the frozen
*route* and *bean-wiring* inventory, not a per-table reference: it names 13 of the 33
tables below. Tables it never names are `users`, `identity_mapping`, `avatar_image`,
`studio_session`, `generation_job`, `generation_attempt`, `quality_result`,
`generation_job_product`, `generation_render_step`, `generation_render_step_item`,
`generation_work_outbox`, `generation_callback_inbox`,
`generation_notification_event`, `generation_notification_delivery`,
`generation_quota_reservation`, `generation_provider_performance_daily`,
`notification`, `consent`, `product_correction_event` and
`account_deletion_workflow`. The list below is read from the migrations themselves, and
this document states no column-level detail.

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
`V022__generation_admission.sql`, `V023__provider_performance.sql`,
`V024__notification_fencing_and_provider_constraints__contract.sql`.

`V024` is the highest migration present in this tree; `V025` is reserved under
[docs/DATABASE_DEPLOYMENT_STRATEGY.md](DATABASE_DEPLOYMENT_STRATEGY.md) and must be
claimed or renamed through the owning policy change before use. Existing migrations are
never renumbered or reset.

## Tables in the schema

- Identity and profile: `users` (V001:1), `identity_mapping` (V001:8),
  `avatar_profile` (V002:1), `avatar_image` (V002:12), `avatar_consumed_upload`
  (V011:27).
- Import: `share_import` (V003:1, extended by V019).
- Vault and organization: `vault_item` (V004:9) with `source_reference` (V004:1);
  `collection` and `collection_item` (V005:1, :11; `collection_item` gains a NOT NULL
  `user_id` in V024:11, :63).
- Studio: `studio_session` (V006:1) and `studio_item` (V006:11) — both schema-only.
  `studio_item` is read by the vault item-deletion in-use guard
  (JdbcVaultStore.java:97,:103) and `studio_session` by the look store
  (JdbcLookStore.java:227); neither is ever written.
- Generation: the generation store (V007, V013), `generation_job_product` (V012:6),
  `generation_render_step` and `generation_render_step_item` (V013:123, :133),
  `generation_work_outbox` (V013:259), `generation_callback_inbox` (V013:277),
  `generation_notification_event` and `generation_notification_delivery`
  (V013:287, :299), `generation_quota_reservation` (V013:311),
  `generation_provider_capacity_slot` (V013:324), `generation_admission_counter`
  (V022:1), `generation_admission_reservation` (V022:11),
  `generation_provider_performance_daily` (V023:1).
- Looks: `look` and `look_item` (V008:1, :17).
- Deletion: `account_deletion_workflow` (V021:1).
- Notifications and consent: `notification` (V009:1) and `consent` (V010:1). `consent`
  has no JDBC store and no application read or write.
- Product normalization: `product_correction_event` (V020:4).
- Derived: view `generation_legacy_submit_owned_without_provider_job` (V013:337).

## Access rules

- Table access lives in JDBC store classes: `JdbcVaultStore`, `JdbcLookStore`,
  `JdbcShareImportStore`, `JdbcAvatarStore`, `JdbcCollectionStore`, `JdbcGenerationStore`,
  `JdbcIdentityProvisioning`, `JdbcWorkOutboxStore`, `JdbcCallbackInboxStore`,
  `JdbcGenerationCapacityLeaseStore`, `JdbcGenerationRateLimiter`,
  `JdbcNotificationStore`, `JdbcNotificationEventStore`, `JdbcProductCorrectionStore`,
  `JdbcProviderPerformanceStore`.
- Every owner-facing statement filters on `user_id`. The declared exceptions are the
  `source_reference` existence probe (that table has no user column) and the in-use guard,
  which runs only after the owner-scoped lock. See [docs/PRIVACY.md](PRIVACY.md).

## Settled repository-init decisions

- One private monorepo with canonical repository slug `ai-fashion-vault`.
- Generator-owned paths (`mobile/`, `backend/`, `compose.yaml`) are created by later initialization slices, not by Repository Initialization.

Those slices have since landed: `mobile/`, `backend/`, and `compose.yaml` exist in this
tree today.
