# Contract Inventory — MVP Baseline

**Ticket:** PR-01 — Truthful baseline and contract inventory.
**Baseline:** commit `8e60d2a`, clean checkout of this PR-01 tree.
**Status vocabulary used throughout:** SOURCE-CONFIRMED (read from the current
source in this checkout) · PROPOSED (specified by a later ticket and **not
mounted today**) · EXTERNALLY-BLOCKED (needs a decision or credential the
implementer cannot supply).
**Path convention:** short backend paths are relative to
`backend/src/main/java/com/aifashionvault/`; short mobile paths are relative to
`mobile/src/`. New files named as later-ticket work are explicitly PROPOSED.

## 1. Method and evidence

Everything below was read from source in this checkout. No route, bean or table
is asserted from a plan document alone; where a plan line is cited it is cited as
the *owning ticket*, not as evidence of current behavior.

Counting method for the suite-scale line in §3: `find backend/src/test/java -name
"*.java" | wc -l`, `grep -rho "@Test\b" --include=*.java`, `grep -rho
"@TestConfiguration" --include=*.java`, `grep -rl "PostgreSQLContainer"
--include=*.java`. These are static counts, not a test run.

## 2. Frozen contract inventory — eight areas

**Route census (SOURCE-CONFIRMED):** ten `@RestController` classes exposing
**36 request mappings** in total (anchors are line numbers in each controller's
own source file). The only other HTTP surface is actuator `health` and `metrics`
(application.yaml:11), so nothing outside this table is mounted.

| controller | mappings (anchor) |
|------------|-------------------|
| `VaultController` | `POST /vault/items` :24 · `GET /vault/items` :29 · `GET /vault/items/{id}` :30 · `PATCH /vault/items/{id}` :31 · `DELETE /vault/items/{id}` :32 |
| `UploadsController` | `POST /uploads` :42 · `POST /uploads/{id}/complete` :60 · `DELETE /uploads/{id}` :75 |
| `LooksController` | `GET /looks` :78 · `GET /looks/{id}` :83 · `POST /looks/{id}/share-link` :88 · `POST /looks/{id}/regenerate` :93 · `DELETE /looks/{id}` :133 |
| `SharesController` | `POST /share/imports` :39 · `GET /share/imports/{importId}` :51 · `PUT /share/imports/{importId}/image` :59 · `DELETE /share/imports/{importId}` :72 |
| `CollectionsController` | `POST /collections` :35 · `GET /collections` :42 · `GET /collections/{id}` :43 · `PATCH /collections/{id}` :45 · `DELETE /collections/{id}` :51 · `POST /collections/{id}/items` :56 · `DELETE /collections/{id}/items/{vaultItemId}` :69 |
| `AvatarsController` | `POST /avatars` :86 · `GET /avatars` :97 · `GET /avatars/{id}` :102 · `PATCH /avatars/{id}` :113 · `DELETE /avatars/{id}` :127 |
| `MeController` | `GET /me` :28 · `DELETE /me` :33 |
| `GenerationsController` | `POST /generations` :67 · `GET /generations/{generationId}` :83 · `POST /generations/{generationId}/cancel` :91 |
| `GenerationCallbackController` | `POST /generation-callbacks/{token}` :47 |
| `FeatureFlagController` | `GET /feature-flags` :17 |

Counts at the pinned baseline 3b6af1f: 5 + 3 + 5 + 4 + 7 + 5 + 2 + 3 + 1 + 1 = **36**
(10 `@RestController` classes; the tenth, `FeatureFlagController`, sits outside
`platform/web`, so a `platform/web`-only glob finds nine). `GET /me` returns
`MeResponse(String id)` — the wire field is `id`, not `userId` (MeResponse.java:3); `GET /feature-flags` is an operational snapshot surface
with a mobile caller at `mobile/src/config/featureFlags.ts:91`. One mapping is
conditional on infrastructure rather than on a feature flag, but on the store
at request time rather than on bean definition: `POST /generation-callbacks/{token}`
(GenerationCallbackController.java:47) is mounted unconditionally — the
controller carries no `@ConditionalOnBean` and resolves the store through an
`ObjectProvider` constructor (GenerationCallbackController.java:42-45), answering
503 when the store is absent (GenerationCallbackController.java:52-54). The
store itself is produced by a late-binding `FactoryBean<CallbackInboxStore>`
(GenerationProcessorConfiguration.java:163) that resolves to no bean
(`null`) when the `DataSource` is absent
(GenerationProcessorConfiguration.java:168-171); the generation processing
graph is registered the same way, so both the database and the object store
must be present for processing, not the database alone.

### 2.1 profile

- Mounted: `POST /avatars` (AvatarsController.java:86), `GET /avatars` (:97),
  `GET /avatars/{id}` (:102), `PATCH /avatars/{id}` (:113),
  `DELETE /avatars/{id}` (:127).
- Request `AvatarUploadRequest(uploadId)` (AvatarUploadRequest.java:3); response
  `AvatarResourceResponse(id, imageId, revision, width, height, mediaType,
  displayUrl, displayExpiresAt, warnings)` (AvatarResourceResponse.java:6-16)
  with `ETag: "<revision>"` and `If-Match` preconditions
  (AvatarsController.java:113-125, :127-141). `AVATAR_STALE_REVISION` → 412,
  `AVATAR_NOT_FOUND` → 404.
- Persistent store: `avatar/JdbcAvatarStore.java` over `avatar_profile` (V002)
  and `avatar_consumed_upload` (V011:27).
- Mobile caller: `features/my-model/avatarApi.ts:66,:81,:96,:107`.
- Current missing connection: the photo source returns `null` for both camera
  and picker (F01), and the default upload scanner is unavailable (F15). Owning
  tickets PR-05 (F01/F15) and PR-03 (F10, identity verification).

### 2.2 verified upload

- Mounted: `POST /uploads` (UploadsController.java:42), `POST
  /uploads/{id}/complete` (:60), `DELETE /uploads/{id}` (:75). `POST /uploads`
  accepts an optional `?purpose=avatar|generic` query parameter
  (UploadsController.java:45, :124-129).
- Request `CreateUploadRequest(contentType, contentLength)` —
  CreateUploadRequest.java:5, validated through `ImageMediaPolicy`.
- **Response wire shape (exact):** `CreateUploadResponse` =
  `id`, `method`, `url`, `headers`, `expiresAt` — CreateUploadResponse.java:8-13.
  This is asserted on the wire by
  `UploadDtoJsonTest.java:78-79` (component names) and `:92` (serialized
  property names), and consumed by mobile as
  `UploadTicket { id, method, url, headers }` —
  `features/my-model/http.ts:3-8`. The names `uri` and `signedHeaders` are
  **internal** `UploadCapability` accessors
  (`platform/objectstore/UploadCapability.java:11-16`) mapped onto the wire
  fields inside the controller (UploadsController.java:53-57); they are not
  response field names.
- `POST /uploads/{id}/complete` → `CompleteUploadResponse(id, status, mediaType,
  contentLength, width, height)` (UploadDtoJsonTest.java:157-158).
- Persistent store: upload staging lives in the object store behind
  `UploadWorkflow`/`ObjectStore`; the one upload-related database table is
  `avatar_consumed_upload` (V011:27, columns user_id/upload_id/consumed_at),
  which `avatar/JdbcAvatarStore.java` writes at :456-457 and reads at :137-138
  to mark an upload consumed by the avatar flow.
- Mobile caller: `features/my-model/http.ts:91` (create) and `:112` (complete).
- Current missing connection: completion is wired to a scanner that always
  returns UNAVAILABLE (F15), so the default path cannot succeed. Owning ticket
  PR-05.

### 2.3 import

- Mounted: `POST /share/imports` (SharesController.java:39), `GET
  /share/imports/{importId}` (:51), `PUT /share/imports/{importId}/image`
  (:59), `DELETE /share/imports/{importId}` (:72). Create and attach are
  idempotent through an optional `Idempotency-Key` header (:42, :63).
- Response `ShareImportResponse(importId, status, reason, recoveryActions)` —
  ShareImportResponse.java:12; absent fields are omitted and no source URL,
  object key or media data is returned (ADR-015).
- Persistent store: `importer/JdbcShareImportStore.java`, which touches only the
  `share_import` table (V003:1, extended by V019). It reads/writes no other
  table.
- Mobile callers: `features/share/importClient.ts:185,:207` and
  `screens/share-import/recoveryClient.ts:76`.
- Current missing connection: replay is arbitrated before the staging
  requirement is checked (F05, closed — `store.arbitrateCreate(command)` at
  ShareImportService.java:55, re-checked at :68 before resolving at :69), and
  `READY` is not a confirmable saved item (F04). Owning tickets:
  F05 -> PR-07 (closed); F04 -> PR-08 and PR-11 (plan:70). The minimal
  import-confirmation endpoint is **PROPOSED** (PR-08).

### 2.4 item / collection

- Mounted items: `POST /vault/items` (VaultController.java:24), `GET
  /vault/items` (:29), `GET /vault/items/{id}` (:30), `PATCH
  /vault/items/{id}` (:31), `DELETE /vault/items/{id}` (:32). Create rejects
  unknown properties (:22, :25).
- Mounted collections: `POST /collections` (CollectionsController.java:35),
  `GET /collections` (:42), `GET /collections/{id}` (:43), `PATCH
  /collections/{id}` (:45), `DELETE /collections/{id}` (:51), `POST
  /collections/{id}/items` (:56), `DELETE /collections/{id}/items/{vaultItemId}`
  (:69).
- Responses: `VaultItemResponse`; `CollectionResponse(id, name, createdAt,
  updatedAt, items)` and `CollectionItemResponse(vaultItemId, createdAt)`
  (CollectionsController.java:98-99).
- Persistent stores: `vault/JdbcVaultStore.java` over `vault_item` (V004:9) with
  `source_reference` (V004:1, read at JdbcVaultStore.java:56 and joined at :68);
  `collection/JdbcCollectionStore.java` over `collection`/`collection_item`
  (V005:1, :11).
- Mobile callers: `features/vault/vaultApi.ts:28,:51` and
  `features/vault/collectionApi.ts:21`.
- Current missing connection: `VaultItemResponse` carries no image read
  capability, so the library is a text list (F06). Owning tickets PR-06, PR-11.

### 2.5 studio

- Mounted: **none.** No controller in this repository maps `/studio/**`
  (grep for `/studio` across `backend/src/main/java` returns nothing), and no
  code inserts into `studio_item`.
- Schema exists: `studio_item` (V006:11); it is only ever *read*, by
  `JdbcVaultStore.java:97` and `:103` (in-use guard for item deletion).
- Mobile caller exists but targets unmounted routes:
  `features/studio/studioApi.ts:16` (`GET /studio/sessions/{id}`), `:19`
  (`POST /studio/sessions/{id}/commands`), `:22`
  (`POST /studio/sessions/{id}/natural-language-command`).
- Status: **PROPOSED** — the persisted Studio session/controller contract is
  PR-12/PR-13/PR-16 work (PR-12 "new minimal Studio store/controller where the
  inventory confirms absence", plan:427; plan:68 lists F03 owners as PR-12,
  PR-13, PR-16). The three mobile calls above are not
  mounted and MUST NOT be read as existing endpoints.
- Current missing connection: readiness is derived from route parameters rather
  than a persisted session (F03). Owning tickets PR-12, PR-13, PR-16.

### 2.6 generation

- Mounted: `POST /generations` (GenerationsController.java:66), `GET
  /generations/{generationId}` (:82), `POST /generations/{generationId}/cancel`
  (:90), and `POST /generation-callbacks/{token}`
  (GenerationCallbackController.java:44).
- Request `GenerationRequest(UUID productUploadId)` (GenerationRequest.java:5) —
  a single manual product upload, not an outfit snapshot. Create returns 202
  with `Location: /generations/{id}` and honours `Idempotency-Key`
  (GenerationsController.java:66-80).
- Persistent stores: `generation/JdbcGenerationStore.java` (V007/V013),
  `platform/queue/JdbcWorkOutboxStore.java`, `generation/JdbcCallbackInboxStore.java`
  (wired at GenerationProcessorConfiguration.java:104-107),
  `platform/generation/JdbcGenerationCapacityLeaseStore.java` (wired at :118-119,
  table `generation_provider_capacity_slot`, V013:324),
  `admission/JdbcGenerationRateLimiter.java` (tables
  `generation_admission_counter` V022:1 and `generation_admission_reservation`
  V022:11).
- Mobile caller: `features/generation/generationApi.ts:100` (create), `:111`
  (poll), plus `features/generation/productUpload.ts:83`.
- **Current provider state (SOURCE-CONFIRMED):** the FASHN provider is
  constructed unconditionally. `FashnConfig.java:41-42` declares the
  `FashnVtonProvider` bean and `:46-47` builds a `DefaultProviderRegistry` with
  FASHN as the selected provider — there is no `@ConditionalOnProperty` or any
  other selection gate. `FASHN_API_KEY` is a **mandatory startup property**:
  `VaultEnvironmentValidator.java:25-26` rejects a blank key with
  `FASHN_API_KEY is required`, and that validator runs from the configuration
  constructor (VaultEnvironmentConfiguration.java:15). The application therefore
  cannot start without a FASHN key today.
- **Honest no-provider behavior is not current state.** It is the desired R5
  target (plan:128) delivered by PR-02 (plan:245), whose work includes booting
  without a provider key and enforcing unavailability before reservation
  (plan:252). Owning tickets for this row: PR-02/PR-14/PR-15/PR-16.

### 2.7 look

- Mounted: `GET /looks` (LooksController.java:78), `GET /looks/{id}` (:83),
  `POST /looks/{id}/share-link` (:88), `POST /looks/{id}/regenerate` (:93),
  `DELETE /looks/{id}` (:133).
- Response `LookResource` (id, avatarId, generationId, studioSessionId,
  renderPlan, provider, providerModel, qualityStatus, createdAt, items, image)
  — LookResource.java:11-22 — with a 5-minute presigned image capability
  (LooksController.java:53, :198-206).
- Persistent store: `look/JdbcLookStore.java` over `look`/`look_item` (V008:1).
- Mobile caller: `features/looks/looksApi.ts:17` (list), `:22` (regenerate) —
   mount at `looksApi.ts:13`: UNCONFIGURED mount (baseUrl '' + null token, F02);
  `:25` (share-link).
- Current missing connection: Look publication records a mode string instead of
  real provenance (F07, PR-14). The mobile share-link control
  (`features/looks/looksApi.ts:25`) is a public-share surface that the MVP does
  not support: its removal is **PR-16** work (plan:505, inside the PR-16 spec at
  plan:496-513; R6 coverage is PR-14/PR-16 at plan:709). PR-24 is the
  selected-provider integration ticket (plan:644-646, Requirements R4/R5) and
  does not own this removal. Owning tickets for this row: PR-14/PR-16.

### 2.8 deletion

- Mounted: `DELETE /me` (MeController.java:33) → `IdentityProvisioning.beginDeletion`
  (:40); `DELETE /looks/{id}` (LooksController.java:133) and
  `DELETE /vault/items/{id}` (VaultController.java:32) are the item-scoped
  deletions.
- Persistent stores: `user/JdbcIdentityProvisioning.java`; the durable privacy
  workflow and V021 schema exist separately.
- Mobile caller: **none** in `mobile/src` — grep for `/me` and `deleteAccount`
  returns no matches (the closest hit, `features/my-model/avatarApi.ts:107`, is
  `DELETE /avatars/{id}`, not an account-deletion call).
- Current missing connection: the mounted path purges media before marking the
  user deleted and suppresses per-object delete failures (F16, plan:517); the
  durable resumable workflow is not the mounted path. Owning ticket PR-17.

## 3. Bean wiring, persistence and native targets

**Production bean wiring (SOURCE-CONFIRMED):**

- `ProviderRegistry` → `DefaultProviderRegistry(List.of(fashnVtonProvider),
  fashn.providerKey(), Map.of())` — FashnConfig.java:46-47. Selected provider is
  FASHN; there is no second registry.
- `CallbackInboxStore` → `JdbcCallbackInboxStore` — GenerationProcessorConfiguration.java:104-107,
  gated only by `@ConditionalOnBean(DataSource.class)`.
- `JdbcGenerationCapacityLeaseStore` — GenerationProcessorConfiguration.java:118-119;
  table `generation_provider_capacity_slot` (V013:324).
- `JdbcGenerationRateLimiter` — constructed **inline**, not as a Spring bean:
  `new JdbcGenerationRateLimiter(dataSource, Clock.systemUTC())` at
  GenerationsController.java:62, inside the `GenerationCreationService`
  construction (:60-63). Tables: `generation_admission_counter` (V022:1),
  `generation_admission_reservation` (V022:11).
- `JdbcGenerationStore` is the fallback store when no `GenerationStore` bean is
  present (GenerationsController.java:57-59).
- Actuator exposure: `health,metrics` only (application.yaml:11), health details
  `never` (:14). There is no other management endpoint.

**Suite scale (SOURCE-CONFIRMED static count):** 193 backend test files under
`backend/src/test/java`; 1834 `@Test` methods (1850 `@Test`+`@TestConfiguration`
occurrences minus 16 `@TestConfiguration`); 42 files referencing
`PostgreSQLContainer`. This is a count of source, not a passing run.

**Native targets (present, not built here):** Android Gradle project
`mobile/android` (`settings.gradle`, `gradle.properties`) with app module
`mobile/android/app` (`build.gradle`) and the
share receiver `ShareImportActivity` (exported; ACTION_SEND intent-filters for
text/plain and image/* at AndroidManifest.xml:14-30) with the deep-link filter
`aifashionvault://share-import` declared on `MainActivity` (AndroidManifest.xml
:42-47, inside the :31-48 activity element); staging via the Android package
`com.aifashionvault.app.share` at
`mobile/android/app/src/main/java/com/com.aifashionvault.app/share/`
(PendingShareStore, ShareHandoffInterop, ShareImageStager, ShareRuntime and
related support files — this path is under the Android app module, not the
`mobile/src/` convention used elsewhere in this document), the Android entry
point of the import MVP path;
iOS host
app `mobile/ios/AIFashionVault` (`AIFashionVault.xcodeproj`, `AppDelegate.swift`,
`ShareHandoff.swift`, `PrivacyInfo.xcprivacy`); iOS share extension
`mobile/ios/FashionVaultShare` (`ShareViewController.swift`, `Info.plist`,
entitlements) with `FashionVaultShareTests` and `AIFashionVaultTests`. Native
builds were **not executed** on this Windows host; their status is unverified
here and must not be reported as a pass.

## 4. Per-MVP-path matrix

All mounted-contract paths marked UNCONFIGURED mount have mobile clients
constructed with `baseUrl: ''` and a null token (F02) — the full 8-site census
is in §7 (F02).

| # | path | mounted contract | persistent store | mobile caller | runtime config key (application.yaml) | current missing connection | owning ticket |
|---|------|------------------|------------------|---------------|------------------------------|----------------------------|---------------|
| 1 | profile | `POST/GET/GET{id}/PATCH/DELETE /avatars` (AvatarsController.java:86,:97,:102,:113,:127) → `AvatarResourceResponse` | `avatar_profile` V002 + `avatar_consumed_upload` V011:27 via `avatar/JdbcAvatarStore.java` | `features/my-model/avatarApi.ts:66,:81,:96,:107` (mount at :117 — UNCONFIGURED mount (baseUrl '' + null token, F02)) | `vault.avatar.*` buckets (`S3_AVATAR_BUCKET`, application.yaml:33), `vault.avatar.attestation-required` (:48-49) | null photo source (F01); unavailable scanner (F15) | PR-05; identity PR-03 |
| 2 | verified upload | `POST /uploads`, `POST /uploads/{id}/complete`, `DELETE /uploads/{id}` (UploadsController.java:42,:60,:75) → `CreateUploadResponse(id, method, url, headers, expiresAt)` | object-store staging; `avatar_consumed_upload` V011:27 (JdbcAvatarStore.java:456-457,:137-138) records avatar-consumed uploads | `features/my-model/http.ts:91,:112` | `vault.s3.temp-bucket` (`S3_TEMP_BUCKET`, application.yaml:36) | completion cannot succeed with the default scanner (F15) | PR-05 |
| 3 | import | `POST/GET/PUT/DELETE /share/imports…` (SharesController.java:39,:51,:59,:72) → `ShareImportResponse(importId, status, reason, recoveryActions)` | `share_import` V003:1/V019 via `importer/JdbcShareImportStore.java` | `features/share/importClient.ts:185,:207`; `screens/share-import/recoveryClient.ts:76` (via apiConfig.ts:8 — UNCONFIGURED mount (baseUrl '' + null token, F02)) | `vault.s3.vault-bucket` (`S3_VAULT_BUCKET`, application.yaml:34) | replay arbitrated before the staging check, which it revalidates (F05, closed; ShareImportService.java:55,:68); no confirm-to-item contract (F04) | F05 -> PR-07 (closed); F04 -> PR-08, PR-11 |
| 4 | item / collection | `/vault/items` ×5 (VaultController.java:24-32); `/collections` ×7 (CollectionsController.java:35,:42,:43,:45,:51,:56,:69) | `vault_item` V004:9 + `source_reference` V004:1 (`vault/JdbcVaultStore.java:56,:68`); `collection` V005:1 (`collection/JdbcCollectionStore.java`) | `features/vault/vaultApi.ts:28,:51` (mount at :23 — UNCONFIGURED mount (baseUrl '' + null token, F02)); `features/vault/collectionApi.ts:21` (same) | `vault.s3.vault-bucket` (`S3_VAULT_BUCKET`, application.yaml:34) | no image read capability in the item response (F06) | PR-06, PR-11 |
| 5 | studio | **none mounted** (PROPOSED: PR-12/PR-13); mobile targets `/studio/sessions/**` (studioApi.ts:16,:19,:22) | `studio_item` V006:11 exists, read-only in-use guard (`JdbcVaultStore.java:97,:103`), no writer | `features/studio/studioApi.ts:16,:19,:22` (mount at :12 — UNCONFIGURED mount (baseUrl '' + null token, F02)) | none mounted; `vault.env` (`APP_ENV`, application.yaml:17) | no persisted session; readiness from route params (F03) | PR-12, PR-13, PR-16 |
| 6 | generation | `POST /generations`, `GET /generations/{id}`, `POST /generations/{id}/cancel` (GenerationsController.java:66,:82,:90) + `POST /generation-callbacks/{token}` (GenerationCallbackController.java:44) | `JdbcGenerationStore`, outbox, capacity lease (V013:324), rate limiter (V022:1,:11), callback inbox | `features/generation/generationApi.ts:100,:111` (mount at :126 — UNCONFIGURED mount (baseUrl '' + null token, F02)); `productUpload.ts:83` (same) | `vault.sqs.generation-queue-url` (`SQS_GENERATION_QUEUE_URL`, application.yaml:31), `vault.s3.look-bucket` (:35), `vault.generation.multi-item-enabled`/`admission-paused` | FASHN provider built unconditionally and `FASHN_API_KEY` is a mandatory startup property (FashnConfig.java:41-42,:46-47; VaultEnvironmentValidator.java:25-26) — honest no-provider behavior is not implemented yet | PR-02/PR-14/PR-15/PR-16 |
| 7 | look | `GET /looks`, `GET /looks/{id}`, `POST /looks/{id}/share-link`, `POST /looks/{id}/regenerate`, `DELETE /looks/{id}` (LooksController.java:78,:83,:88,:93,:133) | `look`/`look_item` V008:1 via `look/JdbcLookStore.java` | `features/looks/looksApi.ts:17,:22,:25` (mount at :13 — UNCONFIGURED mount (baseUrl '' + null token, F02)) | `vault.s3.look-bucket` (`S3_LOOK_BUCKET`, application.yaml:35) | mode string stored as render plan (F07); unsupported public share-link control still mounted and called | PR-14; share-link removal PR-16 |
| 8 | deletion | `DELETE /me` (MeController.java:33) plus item/Look deletes | `user/JdbcIdentityProvisioning.java`; durable privacy workflow + V021 | none in `mobile/src` | `vault.retention.temporary-media-max-age` (application.yaml:23), `vault.integrity.required` (:46-47) | purge-first path, not the durable workflow (F16) | PR-17 |

## 5. R1–R8 → owning tickets

Plan-faithful rows (plan:704-711):

- **R1 profile:** PR-03/PR-04/PR-05.
- **R2 save an item:** PR-06–PR-11.
- **R3 organize:** PR-06/PR-11.
- **R4 outfit:** PR-12/PR-13.
- **R5 generation:** PR-02/PR-14/PR-15/PR-16.
- **R6 history:** PR-14/PR-16.
- **R7 privacy:** PR-03/PR-05/PR-17/PR-18.
- **R8 operability:** PR-19–PR-23.

The R5 row above is the honest target (plan:708): PR-16 is part of R5 and also
part of R6 (PR-16 spec "Requirements: R5/R6", plan:498). PR-24 is the
selected-provider integration ticket (Requirements R4/R5, plan:646) and is
EXTERNALLY-BLOCKED; it owns no share-link removal.

## 6. Trust and authorization boundaries

- **Authentication is bearer-only and fail-closed.** `SecurityConfiguration.java:44`
  permits only `GET /actuator/health`; `:45` requires authentication for every
  other request; CSRF is disabled (:39); sessions are STATELESS (:40); the entry
  point is a bare 401 (:42). `ExternalBearerAuthenticationFilter` runs before
  `AuthorizationFilter` (:47), skips filtering only for the health GET
  (`shouldNotFilter`), and returns 401 for a missing header, a malformed
  `Bearer ` prefix, a rejected identity, or a verifier exception — it never
  falls through unauthenticated.
- **Default verifier rejects rather than trusts.** With no
  `ExternalTokenVerifier` bean, the fallback throws
  `TokenVerificationException(UNVERIFIABLE)` at **SecurityConfiguration.java:32**
  (statement at :31-33). Lines :34-35 are the `MeterRegistry` lookup and
  `AuthMetrics` construction and :36 is the `ExternalBearerAuthenticationFilter`
  construction — none of them is the verifier.
- **Owner scoping for looks uses three distinct lookups, not one.** `GET /looks`
  calls `store().listForUser(ownerId)` (LooksController.java:79-81 →
  `JdbcLookStore.java:154-161`, `WHERE user_id = ?` at :159). `GET /looks/{id}`,
  `POST /looks/{id}/share-link` and `POST /looks/{id}/regenerate` go through
  `owned()` (LooksController.java:174-180 → `findByIdForUser`,
  `JdbcLookStore.java:143-146`, `WHERE id = ? AND user_id = ?`). `DELETE
  /looks/{id}` uses `findByIdForCleanup(lookId, ownerId)`
  (LooksController.java:137 → `JdbcLookStore.java:168-175`) followed by
  `logicallyDeleteForUser` (`JdbcLookStore.java:179-183`, `WHERE id = ? AND
  user_id = ?` at :182). Every one of the three is owner-scoped.
- **Vault and import ownership is enforced in the store.** `VaultController`
  passes the authenticated `UserId` into `VaultService` and every owner-scoped
  `JdbcVaultStore` statement carries `user_id` (:60, :64, :89, :94, :105). Two
  statements are intentionally not owner-scoped: `sourceExists` (:55-57), an
  existence probe on `source_reference` (which has no user column at all,
  V004:1-7), and the in-use guard (:96-98, and the same probe at :103 inside
  `deleteForUser`), reachable only after the owner-scoped `lockForDelete`
  (:93-95).
  `SharesController` passes `userId` into every `ShareImportService` call, and
  `JdbcShareImportStore` filters on `user_id` in every owner-facing statement
  (:149, :162, :210, :259, :278, :286, :294, :300, :306). The two
  attempt-token-scoped updates (:193, :243) are keyed by the lease token issued
  for the owner's own attempt, so they cannot cross owners either.
- **Share-link is a bearer-gated route returning a time-boxed object URL.**
  `POST /looks/{id}/share-link` is mounted behind `anyRequest().authenticated()`
  and returns `ReadCapability(url, expiresAt)` (ReadCapability.java:6) built from
  a presigned GET with a 5-minute TTL (LooksController.java:53, :198-206). The
  returned URL is an S3 presigned URL: it is not bearer-gated, and it is not a
  public application route — no public share endpoint exists. The mobile control
  that calls it (`features/looks/looksApi.ts:25`) is to be removed by PR-16
  (plan:505).
- **Generation callbacks are dual-gated.** `POST /generation-callbacks/{token}`
  requires the bearer credential like every other route, *and* a token of at
  least 64 hex characters (GenerationCallbackController.java:44-58, :60-74);
  the inbox dedupe key is the SHA-256 hex of that token (:76-88). A short,
  non-hex or unknown token returns 404.
- **Actor identity never comes from a request body or header.** In every
  owner-scoped controller the only owner source is the authenticated principal
  (`@AuthenticationPrincipal UserId`); `GenerationCallbackController` and
  `FeatureFlagController` (GET /feature-flags,
  FeatureFlagController.java:7-8,:17) are the two controllers with no
  principal parameter; the callback route is additionally gated by the
  unguessable callback token. Beyond authentication, a runtime attestation
  gate protects generation and avatar writes. `POST /generations` requires a
  valid `AppIntegrity` attestation when `vault.generation.attestation-required`
  is enabled (application.yaml:53; GenerationsController.java:50 constructs its
  boundary from GenerationApiProperties, prefix `vault.generation`), and
  `POST/PATCH /avatars` when `vault.avatar.attestation-required` is enabled
  (application.yaml:48-49; AvatarsController.java:83). Both return
  `403 APP_INTEGRITY_REQUIRED` when the gate rejects
  (AppIntegrityBoundary.java:24-25 short-circuit, :43 403 throw). `vault.integrity.required`
  (application.yaml:46-47) is a separate verifier strictness flag (DENIED vs
  INDETERMINATE on a failed provider check); it does not enable either route
  gate.

## 7. Findings baseline F01–F17

All seventeen findings are recorded, with their owning tickets (plan:64-97):

| id | finding (one line) | owning ticket |
|----|--------------------|---------------|
| F01 | Mobile has no real photo acquisition (null picker) | PR-05 |
| F02 | Mounted API clients default to `baseUrl: ''` | PR-04 |
| F03 | Studio cannot build the requested outfit | PR-12, PR-13, PR-16 |
| F04 | A resolved share cannot become a saved item | PR-08, PR-11 |
| F05 | **CLOSED** — import replay revalidates staging before it resolves: `store.arbitrateCreate(command)` runs first (ShareImportService.java:55) and the replay branch re-checks claimability (ShareImportService.java:68) before resolving at :69; the fresh path re-checks at :71 | PR-07 (closed) |
| F06 | Vault is a text list, not an image library | PR-06, PR-11 |
| F07 | Publication stores a mode string as render-plan JSON | PR-14 |
| F08 | Baseline image validation is presented as quality PASS | PR-02, PR-25 |
| F09 | Existing provider categories are inconsistent | PR-02, PR-12, PR-24 |
| F10 | Firebase verification caches signing keys indefinitely | PR-03 |
| F11 | ECS secret injection is in the wrong container field | PR-20 |
| F12 | iOS text-share activation uses an undocumented key | PR-10 |
| F13 | Test labels overstate integration coverage | PR-01, PR-21, PR-23 |
| F14 | Canonical documentation still describes reserved paths | PR-01 and each feature PR |
| F15 | Default upload completion cannot succeed | PR-05 |
| F16 | Account deletion is purge-first, not the durable workflow | PR-17 |
| F17 | Multi-step counters are not outfit composition | PR-15, PR-24 |

The F02 count is source-verified: eight `baseUrl: ''` mounts exist under
`mobile/src` (`features/generation/generationApi.ts:126`,
`features/generation/productUpload.ts:83`, `features/looks/looksApi.ts:68`,
`features/my-model/avatarApi.ts:150`, `features/studio/studioApi.ts:12`,
`features/vault/collectionApi.ts:21`, `features/vault/vaultApi.ts:23`,
`screens/share-import/apiConfig.ts:8`).

## 8. What this document does not claim

- It does not claim any PROPOSED route is mounted: the only unmounted-but-called
  surface is `/studio/sessions/**` (§2.5), and no `/studio` handler exists.
- It does not claim a test-suite pass; §3 counts source only.
- It does not claim native builds pass; they were not executed here.
- It does not claim provider selection, no-provider generation behavior, or
  share-link removal are done — those are PR-02 and PR-16 work.

## 9. C01 reconciliation — 2026-10-03 (baseline `1f13d34`)

This section is **appended**, not a rewrite. §2 and §4 above are the dated PR-01 baseline
(commit `8e60d2a`) and are preserved as historical results. Current source differs:

- **Route count:** §2 states 36 mappings; a literal annotation count at `1f13d34` yields
  **37** (`CollectionsController` 7, `VaultController`/`SharesController`/`LooksController`/
  `AvatarsController` 5 each, `UploadsController`/`GenerationsController` 3 each,
  `MeController` 2, `GenerationCallbackController`/`FeatureFlagController` 1 each).
- **Mobile client construction:** §4's "eight `baseUrl: ''` mounts" is historical. Current
  mobile resolves through the single `productionApiConfig` seam
  (`mobile/src/config/apiConfig.ts`), which throws on an unset base URL and on a missing
  token.
- **Provider selection / no-provider behavior:** §3's "`FASHN_API_KEY` is a **mandatory
  startup property**" and "FASHN as the selected provider" are historical. Now implemented:
  two gated provider beans (`FashnConfig.java:40`, `InferHubConfig.java:52`), selection
  preferring `inferhub` then `fashn` in `ProviderRegistryComposition.java:70`, both keys
  optional at boot (`application.yaml:49-55`), and no-provider admission refusal
  (`VaultEnvironmentValidator.java:25-28`).
- **Share-link removal:** confirmed done — `POST /looks/{id}/share-link` is mounted but the
  mobile client does not call it (`features/looks/looksApi.ts:71-76`).

The full current inventory, per-package status, and externally blocked packages are in
[docs/RELEASE_COMPLETION_LEDGER.md](RELEASE_COMPLETION_LEDGER.md).

### 9.1 C12 multi-item generation (`POST /generations/multi`)

**Status: MOUNTED at integration `daddbcb`** (verified against `GenerationsController.java:110`).
The contract below was frozen first so backend/mobile work could proceed in parallel
(guide §5.3); the landed implementation matched it, so the fields are unchanged and only
the status has moved from "seam" to "mounted".

| Aspect | Contract |
|---|---|
| Method/path | `POST /generations/multi` (sibling of the manual single-item `POST /generations`) |
| Request | `GenerationMultiRequest(studioSessionId: UUID \| null, productVaultItemIds: UUID[] ordered, mode: String \| null, default "standard")` |
| Required header | `Idempotency-Key` — missing → `400 IDEMPOTENCY_KEY_REQUIRED` |
| Success | `202` + `Location: /generations/{id}`, body identical to `POST /generations` (`GenerationResponse`) |
| Feature gate | `vault.generation.multi-item-enabled` (default **false**) → `503 MULTI_ITEM_DISABLED` |
| Invalid input | empty / >64 / duplicate / null items, bad mode → `400 MULTI_ITEM_INVALID` |
| Ownership | foreign or unknown vault item, or foreign `studioSessionId` → `404 UNAUTHORIZED_OR_NOT_FOUND` |
| Idempotency | same key + different fingerprint (avatar revision + mode + item **order**) → `409 IDEMPOTENCY_KEY_REUSED` |
| Admission | `429` with the admission reason (`PRODUCT_LIMIT`/`ROLLING_LIMIT`/`ACTIVE_LIMIT`) |
| Global gates | `503 ADMISSION_PAUSED` / `503 GENERATION_PROVIDER_UNAVAILABLE` |
| Persistence | one accepted job via `GenerationStore.createAccepted`: render plan one step per product in submitted order, one outbox row, quota reservation |

The request discriminant is **not** `productUploadId` (C12 item 1): the manual path keeps
`GenerationRequest(UUID productUploadId)` unchanged, and multi-item intent carries ordered
Vault item ids plus an optional Studio session.

### 9.2 Frozen seam — C07 share-import preview (`GET /share/imports/{importId}/preview`)

**Status: FROZEN SEAM, implementation in flight (worker-13).** Additive sibling of the
existing `GET /share/imports/{importId}` status route; the status body
(`ShareImportResponse`) is unchanged. Records the contract so the mobile share client
(worker-04/worker-01) can adopt it without waiting for the merge.

| Aspect | Contract |
|---|---|
| Method/path | `GET /share/imports/{importId}/preview` |
| Success | `200` + `ShareImportPreviewResponse{importId, previewUrl, previewExpiresAt, sourceTitle?, sourceSiteName?}` |
| `previewUrl` | Short-lived **signed read capability** for the resolved import object (VAULT boundary, 5-minute TTL). Never the object key or a permanent URL; re-issue by calling the route again. |
| Safe metadata | `sourceTitle`/`sourceSiteName` only — no source query string, object key, or media bytes (ADR-015). |
| Ownership | foreign or unknown import → `404 IMPORT_NOT_FOUND` (indistinguishable from missing) |
| State | not `READY` / no resolved object → `409 IMPORT_STATE_CONFLICT` |
| No capability | media seam cannot mint a signed read → `503 DEPENDENCY_UNAVAILABLE` (never a fabricated preview) |

The route is additive: a client that ignores it keeps the current status-only behavior.

### 9.3 DTO change — vault item read carries `sourceUrl` (C16 item 5)

**Status: MOUNTED at integration `91cbb6b` (worker-06).** Additive, nullable field on the
existing owner-scoped vault item representation; recorded here because it changes a shared
DTO that the mobile client parses.

| Aspect | Contract |
|---|---|
| Field | `sourceUrl`, type `string \| null`, on `VaultItemResponse` |
| Where | BOTH `GET /vault/items` (list) and `GET /vault/items/{id}` (detail) — one response mapper serves both |
| Value | `source_reference.url` verbatim: the retailer product URL captured at import. **Untrusted**; the client must route it through its http/https-only external-navigation helper rather than handing it to the OS directly |
| Nullability | `null` for a photo-created item that has no source, so the field is null rather than absent |
| Not exposed | `raw_metadata` stays server-side. `sourceReferenceId` is unchanged (still the opaque id) |
| Ownership | unchanged: foreign/unknown/malformed id → `404 VAULT_ITEM_NOT_FOUND` (opaque), so no other owner's URL is ever disclosed |

Consumers that ignore the field are unaffected. Two shape oracles were updated deliberately,
not weakened: `VaultControllerTest`'s strict `containsExactly(...)` property list now includes
`sourceUrl` and additionally forbids `rawMetadata`/`raw_metadata`/`x-amz-`; `VaultItemTest`'s
record-component name and type lists include `sourceUrl:String`.

This is what makes the guide's "original product source links with safe external navigation"
reachable: `source_reference.url` was already persisted by the import confirm path
(`JdbcShareImportStore`), but the read exposed only the opaque `sourceReferenceId`, so the
merged mobile `openSourceLink` had no data source.

## 10. C20 device-token contract — 2026-10-03 (frozen, EXECUTED)

Frozen DTO seam for device-token registration and preferences (guide §5.3). All routes are
under `/me` and require a bearer credential; the owner is the authenticated principal and is
never taken from the body. Implemented in `DeviceRegistrationController` /
`JdbcDeviceRegistrationStore` over migration `V025__device_registration.sql`.

| Method | Route | Request | Response | Notes |
|---|---|---|---|---|
| POST | `/me/devices` | `{deviceToken: string(1..4096, trimmed), platform: "ANDROID"\|"IOS"}` | `200 {id, deviceToken, platform, createdAt, updatedAt, lastSeenAt}` | Idempotent upsert keyed on `deviceToken` (globally unique). Re-registering the same token from a second account **moves** it to that account. |
| GET | `/me/devices` | — | `200 [DeviceResponse]` | Caller-scoped only. |
| DELETE | `/me/devices` | `{deviceToken}` | `204` always | Non-disclosing: deletes only when the caller owns the token; another account's delete is a no-op and indistinguishable. |
| GET | `/me/notification-preferences` | — | `200 {pushEnabled, updatedAt\|null}` | Absent row means `pushEnabled: true`. |
| PUT | `/me/notification-preferences` | `{pushEnabled: boolean}` | `200 {pushEnabled, updatedAt}` | Owner-scoped upsert. |

Errors: `400 {"code":"INVALID_DEVICE_REGISTRATION"}` for an unsupported `platform`, a blank
or oversized `deviceToken`, a non-boolean `pushEnabled`, or an unreadable body. Unauthenticated
requests are `401` like every other route.

Pruning: `FcmSendResult.unregistered()` reports HTTP 404 from FCM HTTP v1, and
`FcmDeliveryWorker` invokes the token pruner (`DeviceRegistrationStore::prune`) before marking
the delivery terminally failed, so a dead token is not retried ten times. Physical push
delivery is **EXTERNALLY-DEPENDENT** (no FCM credentials/device in this run); the lifecycle
above is **EXECUTED** by `DeviceRegistrationApiTest` (7 HTTP cases, real Postgres).

Known gap (SOURCE-CONFIRMED): `FcmDeliveryWorker.enqueue` has no production caller, so no
generation completion currently creates a notification event. Delivery remains unmounted
until a generation-publication owner wires it.

## 11. C06 consent contract — 2026-10-03 (frozen, EXECUTED)

Frozen DTO seam for the body-image-processing and third-party-AI-transfer consents (guide
§5.3). Implemented in `platform/web/ConsentsController` over the existing `V006`-era `consent`
table (migration `V010__create_consents.sql` — no new migration). The mobile client
(`mobile/src/features/consent/consentApi.ts`) was written against this shape and agrees with
it field for field.

Every route is authenticated; the owner is the authenticated principal and never appears in a
body or path, so consent is always scoped to the caller. **Both supported purposes are always
enumerated**, so a client never has to tell "absent" apart from "not granted".

Purpose literals are the **snake_case stored values**, not the enum names:

| Enum | Wire literal |
|---|---|
| `ConsentPurpose.BODY_IMAGE_PROCESSING` | `body_image_processing` |
| `ConsentPurpose.THIRD_PARTY_AI_TRANSFER` | `third_party_ai_transfer` |

| Method | Route | Request | Response |
|---|---|---|---|
| GET | `/consents` | — | `200 {policyVersion, purposes:[ConsentState]}` |
| POST | `/consents/{purpose}` | no body (the **server** picks the policy version) | `200 ConsentState` |
| DELETE | `/consents/{purpose}` | — | `200 ConsentState` (idempotent) |

`ConsentState` — every field always present (nulls included), so a client can parse strictly:

| Field | Type | Meaning |
|---|---|---|
| `purpose` | string | one of the two literals above |
| `active` | boolean | whether the owner currently holds this consent |
| `version` | string \| null | policy version the **active** grant was recorded under; null when `active` is false |
| `grantedAt` | ISO-8601 UTC \| null | grant time of the active consent; null when `active` is false |
| `policyVersion` | string | the version the server would record for a **new** grant, so `version != policyVersion` tells the client an active grant predates the current policy |

Semantics:

- `active` is answered by `ConsentService.isActive` — the same primitive the server-side
  provider-transfer gate consults — so this surface can never report a purpose as granted
  while the enforcement gate would refuse it.
- A repeated `POST` for the same purpose is idempotent: one active record, same state.
- `DELETE` is idempotent: repeating it stays `200` with `active:false`, never `404`. The
  withdrawn record is kept (evidence), only `revoked_at` is set.
- The recorded policy version comes from `vault.privacy.consent-policy-version`
  (default `2026-10-03`), so publishing a new consent text is a visible deployment change
  rather than a code edit.

Errors: `400 {"code":"CONSENT_PURPOSE_UNKNOWN"}` for a purpose outside the two literals;
`503 {"code":"CONSENT_UNAVAILABLE"}` when the server has no consent store configured — an
unanswerable question is **never** answered "granted". Unauthenticated requests are `401`
like every other route.

Enforcement (EXECUTED): `PrivacyApprovedInputTransfer.prepare(..., owner)` checks the
owner's active `third_party_ai_transfer` consent **before** the object is read or a signed
read is minted, so a withdrawal stops the next transfer rather than the next upload. A job
without consent fails terminally with `TerminalReason.CONSENT_REQUIRED` and publishes no Look.
`GenerationProcessorConfiguration` builds the transfer component only when **both** an
`ObjectStore` and a `ConsentService` are present, so a deployment missing the consent service
builds no generation processor at all (fail closed) instead of transferring unchecked.
