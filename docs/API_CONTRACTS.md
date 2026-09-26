# API Contracts

Canonical home for AI Fashion Vault HTTP contracts.

**Current endpoint truth lives in [docs/CONTRACT_INVENTORY.md](CONTRACT_INVENTORY.md).**
That inventory is the frozen, source-confirmed census for this baseline: ten
`@RestController` classes exposing 36 mounted mappings, each with its controller anchor
and its exact request/response wire shapes. This document does not restate the route
table; if the two disagree, the inventory wins.

**New routes are PROPOSED until delivered.** A route named only by a later ticket, or
called only by a mobile client, is not part of this contract: it is not mounted, MUST NOT
be integrated against, and becomes contract only when a controller in this tree maps it.
PROPOSED today: `/studio/sessions/**` (three calls at
`mobile/src/features/studio/studioApi.ts:16,:19,:22`, no backend handler) and the minimal
import-confirmation endpoint (PR-08).

**PR backlog:** [docs/AI_Fashion_Vault_MVP_Production_Readiness_PR_Plan.md](AI_Fashion_Vault_MVP_Production_Readiness_PR_Plan.md)
remains the PR backlog; its route examples are requirements to specify, not existing APIs.

## Standing rules (source-confirmed)

- **Bearer-only, fail-closed authentication.** Every request requires authentication
  except `GET /actuator/health`; sessions are stateless; a missing, malformed, or
  unverifiable credential answers 401 and never falls through unauthenticated.
- **Actor identity comes only from the authenticated principal**
  (`@AuthenticationPrincipal UserId`), never from a request body or header. Owner-scoped
  statements filter on `user_id`.
- **Actuator** exposes `health` and `metrics` only, with health details `never`.
- **Runtime attestation gates.** `POST /generations` requires a valid `AppIntegrity`
  attestation when `vault.generation.attestation-required` is enabled; `POST`/`PATCH
  /avatars` when `vault.avatar.attestation-required` is enabled. Both answer
  `403 APP_INTEGRITY_REQUIRED` when the gate rejects. `vault.integrity.required` is a
  separate verifier strictness flag (DENIED vs INDETERMINATE), not a route gate.
- **Idempotency.** `POST /share/imports`, `PUT /share/imports/{importId}/image`, and
  `POST /generations` honour an optional `Idempotency-Key` header.
- **Object access is time-boxed presigned URLs**, not public application routes — a
  5-minute capability for look images (`ReadCapability`). No public share endpoint exists.
- **One route is infrastructure-conditional**: `POST /generation-callbacks/{token}`
  requires a `CallbackInboxStore` bean, and the callback processing beans require both a
  `DataSource` and an `ObjectStore`.

## Known gaps (not contract changes)

Several mounted contracts cannot complete end-to-end today, and eight sites in `mobile/src`
set `baseUrl: ''` with a null or throwing access token (F02):
`features/generation/generationApi.ts:126`, `features/generation/productUpload.ts:83`,
`features/looks/looksApi.ts:68`, `features/my-model/avatarApi.ts:150`,
`features/studio/studioApi.ts:12`, `features/vault/collectionApi.ts:21`,
`features/vault/vaultApi.ts:23`, and `screens/share-import/apiConfig.ts:8`, whose
`getAccessToken` throws `Share import API is not configured` (`:9-10`) instead of
returning a null token — the one site of the eight that fails closed at the point of use,
throwing before the URL is ever built. The seven null-token sites omit the `Authorization`
header (`mobile/src/features/my-model/http.ts:48`) and then send the bare relative path
that the empty base collapses to (`mobile/src/features/my-model/http.ts:52`), so they read
as indistinguishable from being offline rather than as an authentication failure.
These are backlog defects owned by their tickets — upload completion cannot succeed with
the default `MalwareScanner` bean outside development: it is fail-closed, returns
`MalwareVerdict.UNAVAILABLE` with a named reason, and the completion gate admits only
`MalwareVerdict.CLEAN`, so every staging and production upload is rejected (F15, PR-05;
`backend/src/main/java/com/aifashionvault/platform/objectstore/ObjectStoreConfiguration.java:104-126`,
`backend/src/main/java/com/aifashionvault/platform/objectstore/UploadWorkflow.java:114-125`);
only the development profile passes that gate, and then only against a scanner that
inspects nothing and names itself synthetic in every result; a resolved share cannot
become a saved item, because the share import contract stops at an import id and no mobile
call turns one into a vault item (F04, PR-08/PR-11;
`mobile/src/features/vault/vaultFallback.ts:42`,
`backend/src/main/java/com/aifashionvault/platform/web/SharesController.java:39-72`); import
replay is arbitrated before the verified-upload requirement is applied (F05, PR-07;
`backend/src/main/java/com/aifashionvault/platform/web/ShareImportService.java:55-59`); and
the item response carries no image read capability (F06, PR-06/PR-11;
`backend/src/main/java/com/aifashionvault/platform/web/VaultItemResponse.java:7-8`,
`mobile/src/features/vault/vaultApi.ts:5`).

## Change process

Record the exact request, success, error, replay, and pagination examples for a shape
here, exercise them against the real controller and the mobile parser, and re-read the
inventory — a route joins this contract only when its controller and wire test land.

## Settled repository-init decisions

- One private monorepo with canonical repository slug `ai-fashion-vault`.
- Generator-owned paths (`mobile/`, `backend/`, `compose.yaml`) are created by later initialization slices, not by Repository Initialization.

Those slices have since landed: `mobile/`, `backend/`, and `compose.yaml` exist in this
tree today.
