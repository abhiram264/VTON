# Database Deployment Strategy

This document codifies the AI Fashion Vault database deployment strategy and schema lifecycle as an executable repository policy. It governs all schema changes, Flyway migration standards, release coordination, and canary test maintenance across the monorepo.

The foundational principle of schema management in this repository is defined in `docs/AI_Fashion_Vault_Technical_Implementation_Plan.md` §59:

> Never combine destructive DB change with same-release code that depends on it.

## Environment & Version Facts

- **Flyway Engine**: 12.4.0 (BOM-pinned via Spring Boot 4.1.0 dependencies: `flyway-core` and `flyway-database-postgresql`, with no version override in `backend/pom.xml`).
- **Target Database**: PostgreSQL 18 (pinned as `postgres:18` in both Testcontainers test suites and `compose.yaml`).
- **Configuration Defaults**: `backend/src/main/resources/application.yaml` declares no `spring.flyway.*` overrides, preserving safe Flyway engine defaults as repository policy.

---

## Four-Phase Lifecycle Mapped to Releases

All schema evolution follows the four-phase `EXPAND → DEPLOY → MIGRATE → CONTRACT` cycle. Each phase corresponds to distinct releases to ensure zero-downtime deployments and forward/backward compatibility:

```text
EXPAND
  ↓
DEPLOY
  ↓
MIGRATE
  ↓
CONTRACT
```

1. **EXPAND (Release $N$)**:
   - Applies purely additive schema changes safe to execute while the previous release ($N-1$) is actively serving traffic.
   - Includes: creating new tables, adding nullable columns, adding `NOT NULL` columns with constant `DEFAULT` expressions, creating non-blocking indexes via concurrent index migrations, and creating `NOT VALID` constraints.
   - Running application code ($N-1$) ignores the new schema elements; no write or read paths depend on them yet.

2. **DEPLOY (Release $N$ or $N+1$)**:
   - Deploys application code that begins writing to both old and new schema structures, or writes exclusively to the new paths while reading with fallback.
   - No destructive schema alterations are shipped in this release.

3. **MIGRATE (Post-Deploy / Asynchronous)**:
   - Performs data backfill or data transformation to populate newly expanded structures from legacy data.
   - Executed via bounded background application workers or operational tooling in small transactions.
   - **Prohibition**: Bulk data manipulation (DML) must never run inside a Flyway schema migration file to prevent long-lived locks and deployment timeouts.

4. **CONTRACT (Release $N+1$ or $N+2$)**:
   - Shipped only after all active application instances run code that no longer references or requires the deprecated database objects.
   - Ships the destructive migration removing legacy tables, columns, constraints, or triggers.
   - Destructive migrations must be explicitly tagged with the `__contract` file description suffix.

---

## Schema Channel & Migration Invariants

1. **Sole Schema Channel**: Flyway versioned migrations located in `backend/src/main/resources/db/migration/` are the sole mechanism for modifying database schema. Application code must never execute runtime DDL.
2. **Naming Convention**:
   - Migration files must match `MIGRATION_FILE_PATTERN`:
     ```regexp
     ^V(\d{3})__[a-z0-9_]+\.sql$
     ```
   - Examples: `V001__create_users.sql`, `V022__generation_admission.sql`.
3. **Forbidden File Types**:
   - `FORBIDDEN_FILE_PREFIXES`: `U*`, `R__`, `B__`.
   - Undo migrations (`U*`), repeatable migrations (`R__`), and baseline migrations (`B__`) are strictly forbidden. History is single-track and forward-only.
4. **Strict Contiguity**:
   Versions must start at `001` and increment consecutively without gaps or duplicates (`V001`, `V002`, ... `V025`).
   - Under `outOfOrder=false`, any missing migration version is ignored during migrate execution and subsequently triggers a startup-blocking validation failure with `RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED`.

---

## Numbering Ledger & Version Reservation

### Published Set (Grandfathered)

- `PUBLISHED_CEILING_VERSION = 23`.
- Migrations `V001` through `V023` constitute the published, immutable foundation of the schema.
- These migrations are checksum-replay-pinned across test suites (`FlywayFoundationTest`). Editing any published migration alters its checksum, causing validation failures.
- Running `flyway repair` to mask checksum modifications is strictly forbidden in all environments.
- All new content rules and policy constraints bind strictly to future migrations (`version > 23`).

| Version | Migration File Name | Phase / Domain |
|---|---|---|
| `V001` | `V001__create_users.sql` | Users & Authentication |
| `V002` | `V002__create_avatar_profiles.sql` | Avatar Profiles & Uploads |
| `V003` | `V003__create_share_imports.sql` | Share Import Pipeline |
| `V004` | `V004__create_vault.sql` | Vault Storage |
| `V005` | `V005__create_collections.sql` | User Collections |
| `V006` | `V006__create_studio.sql` | Studio Sessions |
| `V007` | `V007__create_generations.sql` | Generation Core |
| `V008` | `V008__create_looks.sql` | Looks & Outfits |
| `V009` | `V009__create_notifications.sql` | Notification Delivery |
| `V010` | `V010__create_consents.sql` | Consent & Privacy |
| `V011` | `V011__avatar_image_preflight_policy_version.sql` | Avatar Policy Preflight |
| `V012` | `V012__generation_orchestration_state.sql` | Generation Orchestration |
| `V013` | `V013__generation_job_system.sql` | Generation Job Lifecycle |
| `V014` | `V014__generation_data_provenance.sql` | Data Provenance Tracking |
| `V015` | `V015__generation_retry_state.sql` | Generation Retry Queue |
| `V016` | `V016__generation_retry_strategy.sql` | Retry Strategies |
| `V017` | `V017__notification_event_reason.sql` | Notification Event Taxonomy |
| `V018` | `V018__single_item_manual_product.sql` | Single Item Manual Products |
| `V019` | `V019__share_import_resolver.sql` | Share Import Resolvers |
| `V020` | `V020__product_normalization.sql` | Product Schema Normalization |
| `V021` | `V021__privacy_deletion_workflow.sql` | Privacy Deletion Jobs |
| `V022` | `V022__generation_admission.sql` | Generation Admission Circuit |
| `V023` | `V023__provider_performance.sql` | Provider Performance |
| `V024` | `V024__notification_fencing_and_provider_constraints__contract.sql` | Notification Fencing & Provider Constraints |
| `V025` | `V025__device_registration.sql` | Device Registration & Notification Preferences |

### Next Version Reservation

- `RESERVED_NEXT_VERSION = 26`.
- `V025` is claimed by `V025__device_registration.sql` (device-token registration lifecycle, C20).
- **Next Free Migration Number**: `V026`.
- `RESERVED_CEILING_ASSERTION`: No migration file with version $\ge 26$ may exist in the repository while the reservation is outstanding.

---

## Contract Visibility & Destructive Changes

To guarantee auditability and enforce the §59 rule, all destructive database changes must be visibly and mechanically distinguishable:

- `DESTRUCTIVE_PATTERN`:
  ```regexp
  (?i)\b(DROP\s+TABLE|DROP\s+COLUMN|DROP\s+CONSTRAINT|TRUNCATE|ALTER\s+COLUMN)
  ```
- `CONTRACT_DESCRIPTION_SUFFIX`: `__contract`.
- **Rule**: Any migration file with version $> 23$ containing SQL matching `DESTRUCTIVE_PATTERN` must include `__contract` at the end of its file description name. The exact V024 migration already follows this contract suffix.
  - *Compliant Example*: `V025__remove_legacy_status__contract.sql`
  - *Non-Compliant Example*: `V025__remove_legacy_status.sql` (Fails static policy verification)
- **Review Obligation**: The rule "Never combine destructive DB change with same-release code that depends on it" is enforced at pull request and release review time using this machine-auditable tag.

---

## Safe Schema Evolution Mechanics (PostgreSQL 18 & Flyway 12.4.0)

### 1. Expand-Safe `NOT NULL` Column Additions

- `ADD_COLUMN_NOT_NULL_PATTERN`:
  ```regexp
  (?i)ADD\s+COLUMN[^;]*NOT\s+NULL
  ```
- `NOT_NULL_DEFAULT_RULE`: Any `ADD COLUMN` statement on an existing table that specifies `NOT NULL` must also declare a constant `DEFAULT` in the same statement.
- **Mechanics**: In PostgreSQL 18 (effective since PostgreSQL 11), adding a column with a constant `DEFAULT` updates system catalogs without rewriting the table. If no default is provided, adding a `NOT NULL` column to an existing table fails or requires a full table rewrite.
- **Exemption**: Column definitions inside a `CREATE TABLE` statement are exempt from this rule, as new tables do not affect running services.
- **Tightening Sequence for Existing Columns**: To safely add a `NOT NULL` constraint to an existing column that previously permitted nulls without taking exclusive table locks:
  1. Add a check constraint marked `NOT VALID`:
     ```sql
     ALTER TABLE example_table ADD CONSTRAINT chk_col_not_null CHECK (col IS NOT NULL) NOT VALID;
     ```
  2. Validate the constraint in a subsequent step (takes `SHARE UPDATE EXCLUSIVE` lock, permitting concurrent reads/writes):
     ```sql
     ALTER TABLE example_table VALIDATE CONSTRAINT chk_col_not_null;
     ```
  3. Set `NOT NULL` on the column (PostgreSQL 12+ recognizes the validated check constraint and skips the full table scan):
     ```sql
     ALTER TABLE example_table ALTER COLUMN col SET NOT NULL;
     ```
  4. Drop the redundant check constraint:
     ```sql
     ALTER TABLE example_table DROP CONSTRAINT chk_col_not_null;
     ```

### 2. Concurrent Index Discipline

- `CONCURRENT_INDEX_PATTERN`:
  ```regexp
  (?i)(CREATE|DROP)(\s+UNIQUE)?\s+INDEX\s+CONCURRENTLY
  ```
- `CONCURRENT_FILE_ISOLATION`: A migration file matching `CONCURRENT_INDEX_PATTERN` must contain **only** concurrent index creation or drop statements. It must not contain any other DDL or DML statements.
- **Mechanics**:
  - PostgreSQL forbids `CREATE INDEX CONCURRENTLY` and `DROP INDEX CONCURRENTLY` inside a transaction block.
  - Flyway 12.4.0 auto-detects `CONCURRENTLY` statements and runs the script outside a transaction.
  - Mixing concurrent index statements with regular statements causes Flyway to fail with `mixed=false`.
- **Configuration Prerequisite**:
  - The first migration introducing `CREATE INDEX CONCURRENTLY` requires setting:
    ```yaml
    spring:
      flyway:
        postgresql:
          transactional-lock: false
    ```
    in `backend/src/main/resources/application.yaml` because PostgreSQL cannot acquire transactional session-level advisory locks during non-transactional migrations. This configuration edit is owned by the specific slice landing the concurrent index.

---

## Flyway Engine Configuration & Drift Prevention

1. **Engine Defaults as Policy**:
   - `outOfOrder = false`: Migrations must execute strictly in version order. Out-of-order execution is banned.
   - `ignoreMigrationPatterns = *:future`: Future migrations applied in an environment prevent destructive drift.
   - `validateOnMigrate = true`: Flyway automatically performs validation (including `*:pending`) prior to running migrations.
2. `CONFIG_DRIFT_ASSERTION`: `backend/src/main/resources/application.yaml` must not enable `out-of-order` or `baseline-on-migrate`. Configuration drift that weakens migration safety gates is rejected by policy tests.
3. **Prohibition of Baseline & Repair**:
   - `flyway baseline` and `flyway repair` must never be run in development, staging, or production environments.
   - Missing or altered migrations must be addressed by resolving root causes or rolling forward.
4. **Forward-Only Rollbacks**:
   - Flyway 12.4.0 Community Edition does not support undo migrations (`undo` is a proprietary Flyway Teams feature).
   - Rollbacks must always be executed as a new forward versioned migration (`V(N+1)`), never by editing, deleting, or rolling back applied migrations.
5. **Startup Gating**:
   - A failed migration in any environment halts application startup immediately.
   - This failure-path behavior is protected by the `FlywayFoundationTest.failedMigrationBlocksStartup` canary test.

---

## Canary-Bump Procedure (Four Test Families)

When any new migration lands in the repository, the engineer landing the change must update all four canary test families. Ceiling and list definitions must be updated to accommodate the new migration without weakening the underlying assertions:

1. **`DomainArchitectureTest`**:
   - Method: `flywayAllowsOnlyTheFrozenV025MigrationAbovePublishedV017()`
   - Action: Update the hard-coded migration allowlist and rename the method to the new highest allowed version.
2. **`FlywayFoundationTest`**:
   - Methods:
     - `flywayAppliesV001ThroughV025OnEmptyDatabase()`: Update the expected schema history size assertion (`assertThat(history).hasSize(NEW_COUNT)`).
     - `publishedV001ThroughV023MatchFrozenSourceChecksums()`: Verify all published migration checksums.
     - `publishedV002ValidatesAndUpgradesToV025WithoutChecksumRepair()`: Update target upgrade version and assertions.
     - `v012BaselineMigratesToV025WithoutDataLoss()`: Update the baseline migration target and test assertions.
     - `reapplyingMigrationsOnSecondEmptyDatabaseMatchesChecksums()`: Ensure the new checksum is validated during replay.
3. **`AvatarSchemaConstraintsTest`**:
   - Method: `flywayHistoryIsExactlyVersionsOneThroughTwentyFive()`
   - Action: Update the schema history size assertion (`assertThat(history).hasSize(NEW_COUNT)`).
4. **`FlywayDeploymentPolicyTest`**:
   - Fields: `PUBLISHED_CEILING_VERSION = 23`, `RESERVED_NEXT_VERSION = 26`, `CLAIMED_V025 = V025__device_registration.sql`
   - Action: Advance the published ceiling and claimed migration allowlist, then reserve the next unused version (bump cost is exactly the policy constants/allowlist).

---

## Frozen Seam Vocabulary Reference

Both this policy document and the static verification test (`FlywayDeploymentPolicyTest.java`) adhere to this frozen seam vocabulary:

| Seam Identifier | Value / Pattern | Scope & Purpose |
| `MIGRATION_FILE_PATTERN` | `^V(\d{3})__[a-z0-9_]+\.sql$` | Required filename regex for all migration files. |
| `PUBLISHED_CEILING_VERSION` | `23` | Migrations `V001`..`V023` grandfathered; content rules apply only to version $> 23$. |
| `RESERVED_NEXT_VERSION` | `26` | V025 is claimed; V026 is reserved. No migration at version 26 or above may exist until the reservation changes. |
| `FORBIDDEN_FILE_PREFIXES` | `U*`, `R__`, `B__` | Undo, repeatable, and baseline migration files are forbidden. |
| `DESTRUCTIVE_PATTERN` | `(?i)\b(DROP\s+TABLE|DROP\s+COLUMN|DROP\s+CONSTRAINT|TRUNCATE|ALTER\s+COLUMN)` | Matches destructive DDL statements requiring contract tag. |
| `CONCURRENT_INDEX_PATTERN` | `(?i)(CREATE|DROP)(\s+UNIQUE)?\s+INDEX\s+CONCURRENTLY` | Matches concurrent index statements requiring file isolation. |
| `ADD_COLUMN_NOT_NULL_PATTERN` | `(?i)ADD\s+COLUMN[^;]*NOT\s+NULL` | Matches column additions requiring a constant `DEFAULT`. |
| `CONTRACT_DESCRIPTION_SUFFIX` | `__contract` | Required suffix on migrations matching `DESTRUCTIVE_PATTERN`, except the exact claimed V024 filename. |
| `CONCURRENT_FILE_ISOLATION` | File contains only concurrent index statements | Files with `CONCURRENTLY` cannot mix other DDL/DML. |
| `NOT_NULL_DEFAULT_RULE` | Add column NOT NULL must include DEFAULT | PostgreSQL 18 metadata-only expand requirement (new tables exempt). |
| `RESERVED_CEILING_ASSERTION` | No migration $\ge 26$ while reserved | Protects version reservation from collision. |
| `CONFIG_DRIFT_ASSERTION` | `application.yaml` has no out-of-order or baseline | Guards Flyway defaults against configuration drift. |
