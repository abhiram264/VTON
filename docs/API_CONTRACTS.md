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

Several mounted contracts cannot complete end-to-end today, and mobile API clients
default to `baseUrl: ''` at eight mounts (F02). These are backlog defects owned by their
tickets — upload completion cannot succeed with the default scanner (F15, PR-05), a
resolved share cannot become a saved item (F04, PR-08/PR-11), import replay is arbitrated
after staging is required (F05, PR-07), and the item response carries no image read
capability (F06, PR-06/PR-11).

## Change process

Record the exact request, success, error, replay, and pagination examples for a shape
here, exercise them against the real controller and the mobile parser, and re-read the
inventory — a route joins this contract only when its controller and wire test land.

## Settled repository-init decisions

- One private monorepo with canonical repository slug `ai-fashion-vault`.
- Generator-owned paths (`mobile/`, `backend/`, `compose.yaml`) are created by later initialization slices, not by Repository Initialization.

Those slices have since landed: `mobile/`, `backend/`, and `compose.yaml` exist in this
tree today.
