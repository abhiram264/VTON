# Release Completion Ledger (C01)

**Ticket:** C01 — Freeze the release inventory and repair documentation authority.
**Guide:** [docs/PROJECT_COMPLETION_AND_STORE_RELEASE_GUIDE.md](PROJECT_COMPLETION_AND_STORE_RELEASE_GUIDE.md)
**Baseline inspected:** commit `1f13d349eec2a7c5e119779de012ca23509b2579` on branch `friday-guide-c01-c30`.
**Written:** 2026-10-03.

This ledger is the single inventory the guide asks C01 to publish: it labels each launch
capability by its real controller / client / screen, maps every feature to a C-package,
and records the externally blocked packages with the specific missing dependency.
It **summarizes** source; where it disagrees with a dated baseline document, this ledger
plus current source win, and the disagreement is recorded in §6.

Truth labels used throughout, per guide §2.3:

- **SOURCE-CONFIRMED** — read from source at the baseline commit above.
- **EXECUTED** — a command was run and its output observed (none in this document; see §7).
- **EXTERNALLY-DEPENDENT** — requires an account, credential, device, store console or
  paid service that is not available in this run.

> C01 is an inventory. It makes **no** functional claims: no route is "working" because
> it is mounted, and no package is "done" because a file exists. Status here means
> *where the code stands relative to the package's acceptance*, not "accepted".

---

## 1. Method and evidence

Everything below was read from source in this checkout. Route counts were produced with
a literal annotation count, not from a plan document:

```text
grep -rhE '@(Get|Post|Put|Patch|Delete)Mapping' \
  backend/src/main/java/com/aifashionvault/platform/web/*Controller.java \
  backend/src/main/java/com/aifashionvault/featureflag/FeatureFlagController.java | wc -l
→ 37
```

Mobile surfaces were read from `mobile/src/navigation/RootNavigator.tsx` and the screen
and client modules it imports.

---

## 2. Source-confirmed route census (backend)

**Ten `@RestController` classes expose 37 request mappings** (SOURCE-CONFIRMED).
Nine live under `backend/src/main/java/com/aifashionvault/platform/web/` and the tenth is
`backend/src/main/java/com/aifashionvault/featureflag/FeatureFlagController.java:7`.

| Controller | Base path | Mappings |
|---|---|---|
| `AvatarsController` | `/avatars` | 5 |
| `UploadsController` | `/uploads` | 3 |
| `SharesController` | `/share/imports` | 5 |
| `VaultController` | `/vault/items` | 5 |
| `CollectionsController` | `/collections` | 7 |
| `GenerationsController` | `/generations` | 3 |
| `GenerationCallbackController` | `/generation-callbacks/{token}` | 1 |
| `LooksController` | `/looks` | 5 |
| `MeController` | `/me` | 2 |
| `FeatureFlagController` | `/feature-flags` | 1 |

Notable mounted-but-special surfaces:

- `POST /looks/{id}/share-link` is **mounted** (`LooksController.java:113`) but the mobile
  client deliberately does not call it (`mobile/src/features/looks/looksApi.ts:71-76`,
  "an exfiltration capability with no call site is still a capability"). C16 must not
  resurrect it.
- `POST /share/imports/{importId}/confirm` is **mounted** (`SharesController.java:80`).
  The mobile import path does **not** call it (see C07).
- **No `/studio/**` handler exists** anywhere under `backend/src/main/java` (grep `/studio`
  → no matches). The Studio *domain* and its tables exist; only HTTP is missing (C10).

## 3. Source-confirmed mobile surface census

**Stack routes** (`RootNavigator.tsx:25-31`): `MainTabs`, `ProfileSettings`, `MyModel`,
`Generate`, `Look`, `ShareImport`.
**Tabs** (`RootNavigator.tsx:18-23,34-39`): `HomeTab`, `VaultTab`, `StudioTab`, `LooksTab`.

| Screen | File | Backend client(s) it calls |
|---|---|---|
| Sign in | `screens/SignInScreen.tsx` | Firebase `signInWithEmailAndPassword` only |
| Home | `screens/HomeScreen.tsx` | `looksApi` |
| Vault | `screens/VaultScreen.tsx` | `vaultApi`, `collectionApi` |
| Studio | `screens/StudioScreen.tsx` | `studioApi` (unmounted routes), `vaultApi` |
| Looks | `screens/LooksScreen.tsx` | `looksApi`, `generationApi` |
| Look detail | `screens/LookScreen.tsx` | `generationApi` |
| Generate | `screens/GenerateScreen.tsx` | `generationApi`, `productUploader`, `vaultApi`, `vaultFallback`, `choosePhoto` |
| My Model | `screens/MyModelScreen.tsx` | `avatarApi`, `choosePhoto`/`takePhoto` |
| Share import | `screens/ShareImportScreen.tsx` | `importClient`, `recoveryClient` |
| Profile & settings | `RootNavigator.tsx:77-79` (`ProfileSettings`) | renders `MyModelScreen`; no settings surface |

**Client route map (SOURCE-CONFIRMED):**

- `/avatars` GET/POST, `/avatars/{id}` PATCH/DELETE — `my-model/avatarApi.ts`
- `/uploads` POST, `/uploads/{id}/complete` POST — `my-model/http.ts:121-147`
- `/share/imports` POST (+ poll) — `share/importClient.ts:52,196`; **no `/confirm` call**
- `/vault/items` GET/POST — `vault/vaultApi.ts`
- `/collections` GET only — `vault/collectionApi.ts:22`
- `/generations` POST, `/generations/{id}` GET — `generation/generationApi.ts:101-113`
- `/looks` GET, `/looks/{id}` GET, `/looks/{id}/regenerate` POST — `looks/looksApi.ts`
- `/studio/sessions/{id}` GET, `.../commands` POST, `.../natural-language-command` POST
  — `studio/studioApi.ts:16-24` (**all three routes are unmounted**)
- No mobile call to `/me`, `/feature-flags`, `/uploads/{id}` DELETE, `/collections` mutations,
  `/looks/{id}` DELETE, or `/looks/{id}/share-link`.

## 4. Per-package status ledger

Status key: **CONNECTED** (client + mounted backend + screen exist and are wired),
**INCOMPLETE** (some layer missing), **UNMOUNTED** (client or domain exists, no HTTP/UI),
**EXTERNALLY-BLOCKED** (cannot progress without an external dependency).

| Package | Status | Real controller / client / screen | Evidence anchor |
|---|---|---|---|
| C01 | IN PROGRESS (this doc) | `docs/RELEASE_COMPLETION_LEDGER.md` | — |
| C02 | INCOMPLETE | `mobile/babel.config.js`; `mobile/src/config/firebase.ts`; `mobile/src/config/apiConfig.ts` | `firebase.ts:57-62` computed `process.env[key]`; inliner only substitutes member expressions |
| C03 | INCOMPLETE | `SignInScreen.tsx`; `config/firebase.ts` | only `signInWithEmailAndPassword`; no signup/reset/signout/persistence (`grep` → no matches); `ProfileSettings` renders My Model |
| C04 | UNMOUNTED (native) | `features/my-model/photoSource.ts` | `NativeModules.PhotoPicker` required; `MainApplication.kt:20` adds only `ShareHandoffPackage` |
| C05 | EXTERNALLY-BLOCKED | `platform/objectstore`; `ObjectStoreConfiguration.java:92-125` | dev uses synthetic clean verdict; production returns unavailable |
| C06 | INCOMPLETE | `AvatarsController`; `avatar/`; `privacy/Consent.java` | avatar CRUD mounted; no consent surface in mobile (`grep consent` → none) |
| C07 | INCOMPLETE | `SharesController.confirm` (`:80`); `share/importClient.ts` | confirm mounted; client resolves + polls but never confirms |
| C08 | INCOMPLETE | `VaultController`; `vault/vaultApi.ts`; `VaultScreen.tsx` | list/create-from-photo only; no thumbnail/edit/delete UI |
| C09 | INCOMPLETE | `CollectionsController` (7 mappings); `vault/collectionApi.ts`; `VaultScreen.tsx` | client exposes `list()` only; no create/rename/delete/membership UI |
| C10 | UNMOUNTED | `studio/StudioSession.java`, `studio/StudioItem.java`; V006 tables | no `/studio` handler; `studioApi.ts` calls unmounted routes |
| C11 | INCOMPLETE | `StudioScreen.tsx` | depends on unmounted C10 routes |
| C12 | INCOMPLETE | `GenerationCreationService`; `platform/web/GenerationRequest.java` | manual single-item path exists (`V018`); multi-item intent not frozen |
| C13 | INCOMPLETE | `platform/inferhub/InferHubVtonProvider.java:32-33` | 8-entry in-memory output map (`MAX_CACHED_OUTPUTS = 8`) |
| C14 | INCOMPLETE | `GenerateScreen.tsx`; `generation/generationApi.ts` | polling exists; no server-backed accepted-id recovery |
| C15 | INCOMPLETE | `LooksController` (incl. DELETE `:177`); `looks/looksApi.ts`; `LooksScreen.tsx` | list/regenerate exist; no delete UI; share-link deliberately unused |
| C16 | INCOMPLETE | `LooksScreen.tsx`; `share/looksApi.ts` | no OS share/export path; `share-link` intentionally removed |
| C17 | INCOMPLETE | `MeController` DELETE (`:33`); `privacy/DefaultAccountDeletionWorkflow`; V021 | `/me` delete mounted; no mobile deletion UI; barrier work pending |
| C18 | EXTERNALLY-BLOCKED (evidence) | `platform/generation/GenerationProcessorConfiguration.java:113-126` | quality gate code exists; launch-quality evaluation is paid/external |
| C19 | INCOMPLETE | `admission/` package; `GenerationAdmissionFacade` | caps/admission exist; config exposure + pause semantics pending |
| C20 | INCOMPLETE | `notification/` package | FCM client/store exist; no device-token registration endpoint (`grep` → none); delivery defaults blank |
| C21 | UNMOUNTED | `studio/agent/StudioAgent.java:20-24` | ten-tool allowlist exists; no command endpoint |
| C22 | INCOMPLETE | `analytics/` package; `mobile/src/config/featureFlags.ts` | analytics exist; `DEFAULT_FEATURE_FLAGS` ship most capabilities **ON** (contra C22) |
| C23 | INCOMPLETE | all screens | depends on completed feature screens |
| C24–C30 | EXTERNALLY-BLOCKED | `infra/terraform`, `.github/workflows` | see §5 |
| OFFLINE-ECHO | IN PROGRESS (peers) | `generation/vton/VtonProvider.java` seam | claimed on the board by worker-02/worker-03 |
| EXT-BLOCKED | see §5 | — | — |

## 5. Externally blocked packages and their specific missing dependency

Nothing in this section may be attempted in this run (no accounts, spending, deployments
or store consoles). Each row names the exact missing external input.

| Package | Missing external dependency |
|---|---|
| C05 | A real malware-scanner service/runtime reachable from the backend, plus staging S3. Dev uses a synthetic clean verdict (`ObjectStoreConfiguration.java:92-125`). |
| C18 (paid eval half) | A paid AI quality-evaluation provider + curated evaluation set with consented data. The quality-gate *code* is present. |
| C24 | Container registry / CI runner credentials to publish an immutable backend image. |
| C25 | Approved AWS account, region, budget, DNS and validated ACM certificate. |
| C26 | Deployment identity + remote Terraform state backend. |
| C27 | Staging environment (from C25/C26) for alarm/restore exercises. |
| C28 | Google Play Console account, signing keys (`VTON_RELEASE_*`) and store review. |
| C29 | Apple Developer account, App Store Connect access, `FashionVaultShare` + App Group identifiers and signing. |
| C30 | Both store consoles (publication). |

## 6. Source-vs-docs conflicts reconciled

1. **Provider claim.** `docs/ARCHITECTURE.md` stated "FASHN is the only constructed VTON
   provider (ADR-008), and `FASHN_API_KEY` is a mandatory startup property today."
   Source contradicts this: `platform/fashn/FashnConfig.java:40` and
   `platform/inferhub/InferHubConfig.java:52` both create providers, each gated on a
   non-blank key, and `generation/vton/ProviderRegistryComposition.java:70` prefers
   `inferhub` then `fashn`. `application.yaml:49-55` documents both keys as **optional**
   at boot. Corrected in `docs/ARCHITECTURE.md`.
2. **Route count.** `docs/CONTRACT_INVENTORY.md` §2 and `docs/ARCHITECTURE.md` state
   "ten controllers / **36** mappings". A literal annotation count yields **37**
   (§1). The dated PR-01 baseline is preserved; a reconciliation note is added to
   `docs/CONTRACT_INVENTORY.md` rather than rewriting its historical numbers.
3. **Mobile "baseUrl: ''" matrix.** `docs/CONTRACT_INVENTORY.md` §4 (PR-01 baseline)
   records mobile clients constructed with `baseUrl: ''` and a null token. Current source
   resolves through a single `productionApiConfig` seam (`mobile/src/config/apiConfig.ts`)
   whose `baseUrl` **throws** when unset and whose `getAccessToken` throws when
   unavailable/signed-out. Recorded as a dated delta, not an overwrite.
4. **Studio.** `ARCHITECTURE.md` already states correctly that no `/studio` handler exists;
   `studioApi.ts` still calls those unmounted routes (C10/C11).
5. **Feature flags.** `mobile/src/config/featureFlags.ts` ships `MULTI_ITEM`,
   `NATURAL_LANGUAGE_STUDIO`, `TRYON_MAX`, `HD_GENERATION` and both share-import flags
   **ON** by default. C22 requires unsupported capabilities to default **OFF** in release
   builds; recorded as an open conflict for C22, not fixed here.

## 7. Seams to freeze (guide §5.3) and their current state

These are **contracts to design**, not existing endpoints. Current state (SOURCE-CONFIRMED):

| Seam | Current state |
|---|---|
| Import preview + confirmation (metadata edit, optional collection) | `POST /share/imports/{id}/confirm` mounted; preview/metadata contract not frozen (C07) |
| Vault image display capability + source link | Not present; `VaultController` returns metadata only (C08) |
| Studio session create/read/mutate, revision conflicts, clone-from-Look | Domain + tables exist; no HTTP (C10) |
| Generation input discriminant (manual vs Studio revision) | `GenerationRequest` supports manual single-item (`V018`); Studio discriminant missing (C12) |
| Generation recovery/history lookup + terminal/uncertain semantics | Polling only; no accepted-id persistence (C14) |
| Look provenance + deletion | `LooksController` list/get/delete mounted; provenance representation partial (C15) |
| Consent grant/withdrawal + account-deletion request/status | `Consent.java`, `DefaultAccountDeletionWorkflow` exist; no HTTP consent/deletion-status contract (C06/C17) |
| Device-token registration/preferences + content report | No endpoint exists (C20) |

## 8. Baseline commands (recorded, not executed by C01)

Per guide §8. These are the commands to run at integration/release time. **C01 did not run
them** — they are recorded so later work compares against a named baseline and does not
weaken an assertion to match an old report.

```text
backend:  .\backend\mvnw.cmd -f backend\pom.xml verify        (needs Docker for Testcontainers)
mobile:   (from mobile/) npm ci; npm run typecheck; npm run lint; npm run test:ci; npm run format:check
scripts:  python scripts/test_repo_init.py
          python scripts/test_local_dev_init.py
          python scripts/test_environment_contract.py
          python scripts/secret_content_scan.py
```

`VTON_IMAGE_API_KEY` must remain **absent** from offline backend test runs so
`InferHubLiveGenerationTest` stays inert; `scripts/test_localstack_resources.py` runs only
with its real LocalStack prerequisites.

## 9. Launch boundary (guide §4)

- **Launch platform/category:** Android first (Android system Photo Picker, Play release),
  iOS scope per C29; private consumer utility app.
- **Full-V1 gates:** C16 (export/share), C20 (notifications), C21 (basic NL Studio),
  C22 (analytics/flags) must all have evidence before any "all V1 features" claim.
- **Extended roadmap (R01–R09):** out of scope for the first store release; kept in
  guide §13 so "whole project" has an explicit boundary.

## 10. Acceptance clauses verified UNMET at integration `0800318` (2026-10-03)

This is a point-in-time record, not a defect list. Each clause below was checked by a
command against the **integrated branch** (`friday-guide-c01-c30` at `0800318`). Work that
a peer is actively implementing is labelled **in flight**, not broken. Re-verify before
acceptance; do not carry this list forward as current.

| Requirement | Clause | Evidence (at `0800318`) | State |
|---|---|---|---|
| C08 | item 1: "Vault **list** shows owner-authorized thumbnails/full images" | `git show friday-guide-c01-c30:mobile/src/screens/VaultScreen.tsx` renders the signed preview only in the item **detail** view (lines ~144-234); the list rows are text-only | **UNMET** — the detail half works; the list half is not implemented (worker-31 recorded this tradeoff explicitly: `GET /vault/items` embeds no capability, so list thumbnails would be N presign round-trips with no pagination) |
| C08 | image capability route reachable | `git ls-tree friday-guide-c01-c30 -- .../platform/web` lists no `VaultImageController.java`; the route exists only on `swarm/fr_640bdd25a4/worker-06` (`a004579`) | **IN FLIGHT** (worker-06) — until merged, a live preview call 404s and the UI correctly shows "gone" |
| C03 | signup, password reset, signout, durable RN persistence | `git grep -n "signOut\|createUserWithEmailAndPassword\|sendPasswordResetEmail\|getReactNativePersistence" friday-guide-c01-c30 -- mobile/src` → only a comment in `config/firebase.ts`; `mobile/src/features/auth` is empty | **IN FLIGHT** (workers 08/17/26) — C03 is materially incomplete on the branch |
| C12 | Studio multi-item create mounted | `git grep -n "generations/multi" friday-guide-c01-c30 -- backend/src/main/java` → no matches | **IN FLIGHT** (worker-05) — see CONTRACT_INVENTORY §9.1 (frozen seam) |

Cross-checks that PASSED at `0800318` (no action needed): C09 `collectionsApi` matches
`CollectionsController` exactly; C19 spend caps wired via `@ConfigurationProperties` with
500/25/250 USD/1d defaults preserved; C17 commits the `deleted_at` barrier before media
purge; OFFLINE-ECHO is dev-only and fail-closed; the C08 mobile image client and worker-06's
unmerged route agree on `GET /vault/items/{id}/image` → `{url, expiresAt}`.

### 10.1 Cross-checks at the next integration head `d2e3478`

Checked after worker-08 merged (C04 native picker + C03 account-session isolation):

- **C04 (PASS):** the merged native module's `getName()` returns `"PhotoPicker"` and it
  exposes `@ReactMethod choosePhoto`/`takePhoto`, registered in `MainApplication.kt` —
  exactly the `NativeModules.PhotoPicker` shape `mobile/src/features/my-model/photoSource.ts`
  requires. Native bridge presence confirmed in source; on-device capture proof remains
  EXTERNALLY-DEPENDENT.
- **C07 (PASS):** the mobile confirmer now calls `POST /share/imports/{importId}/confirm`
  (`features/share/importClient.ts:283-298`) and `ShareImportScreen` presents the saved
  state only from that response; the backend route is mounted (`SharesController.java:80`).
  Both sides agree.
- **Corroboration:** peer worker-03 independently reproduced the C01 route census
  (37 mappings, 10 controllers) and verified the C22 analytics tests at `ae358d7`.
