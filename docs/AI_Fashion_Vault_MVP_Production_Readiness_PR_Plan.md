# AI Fashion Vault — MVP Production-Readiness PR Plan

> **Status:** Proposed execution backlog, not a production-readiness certificate. All PRs below start unchecked. This document does not claim their implementation is complete.
> **Audit date:** 2026-09-12. **Audited revision:** `a344887`, plus the existing working tree. Existing changes to `compose.yaml`, `scripts/run_backend.py`, and untracked image experiments were not modified.
> **For implementers:** Execute one numbered PR at a time. Read its cited source and the global contracts, establish the failing behavioral case, repair the real application path, and attach the required evidence. Do not execute the historical 94-section plan automatically.

**Goal:** Let a user save fashion items from shopping-app shares or selected photos, organize them by category and collection, choose an outfit containing multiple items plus their own profile photo, and obtain a private generated Look once an image provider has been selected and validated.

**Architecture:** Repair the existing React Native app and Spring modular monolith. Keep PostgreSQL/JDBC, Flyway, private S3-compatible storage and the existing SQS/outbox machinery. Preserve one provider boundary. Do not rewrite the application or introduce another queue, microservices, Kubernetes, a stylist agent, or a second state-management architecture to finish this MVP.

**Observed stack:** React Native `0.86.2`, React `19.2.3`, React Navigation 7; Spring Boot `4.1.0`, Java 21, Spring JDBC/Modulith; S3 and SQS SDKs. The old plan's JPA/Redis topology is not the current implementation. Versions are observations, not instructions to upgrade everything.

## 1. Executive decision

**The shortest route is to connect and verify the existing product, not implement more slices.** There is useful backend and native work here, but the mounted mobile application does not complete either requested MVP journey. Green unit tests currently coexist with null photo pickers, unconfigured API clients, route-only Studio state, and an import success screen that cannot save an item.

There are two distinct release gates:

- **Gate A — provider-ready internal candidate:** Real login, real photos, real share/import/save, real collections and outfit selection, durable generation orchestration exercised using a clearly isolated test adapter, reliable deletion, deployable staging, and working device journeys. In normal application builds generation is explicitly unavailable while no provider is configured. This is not a public AI-generation launch.
- **Gate B — public MVP:** Everything in Gate A, plus an explicitly selected provider, tested tops + bottoms + footwear generation, acceptable real image quality/cost/latency, processor-specific consent and retention terms, abuse/reporting controls, store approval evidence and rollout readiness. The model decision remains intentionally unmade in this plan.

Do not show a sample image, recycled avatar, collage, or deterministic fixture as a generated Look in a production build. Do not charge, reserve spend, or queue doomed jobs when generation is unavailable. An image-provider placeholder is acceptable at an integration boundary; placeholders for auth, storage, persistence, imports, or deletion are not.

### 1.1 What this replaces

The user's current two-feature MVP overrides the *scope and ordering* of `AI_Fashion_Vault_Technical_Implementation_Plan.md`. That document remains historical design input. Its illustrative requests, old version floors, old slice numbers and claimed gates are not evidence of current behavior. Existing working contracts remain authoritative until a ticket explicitly changes them and migrates all callers.

Three options were considered:

1. **Continue the old slices:** rejected; this expands code before fixing the broken connections between existing slices.
2. **Rewrite mobile/backend:** rejected; it discards real ownership, storage, migration and native handoff work without proving a rewrite is cheaper.
3. **Repair complete user journeys, remove obsolete paths along the way:** selected. Every PR below must finish an observable behavior or prove a concrete release gate.

### 1.2 Settled decisions

**DECISION:** Preserve the stack and repair seams rather than rewrite.
**EVIDENCE:** Existing JDBC stores, native share targets, SQS/outbox code and focused passing tests; source-confirmed disconnected mobile workflows.
**REVISIT_IF:** A bounded repair demonstrates a required invariant cannot be maintained in the existing architecture.
**STATUS: SETTLED**

**DECISION:** Keep the image provider unselected, with honest unavailable behavior outside isolated tests.
**EVIDENCE:** Explicit user instruction that the image model is a placeholder for now.
**REVISIT_IF:** The product owner selects a provider and approves its capability, privacy and cost contract.
**STATUS: SETTLED**

**DECISION:** Preserve Android and iOS scope, but validate each separately; Windows checks do not certify iOS.
**EVIDENCE:** Both native share implementations already exist and the previous product plan targets both platforms.
**REVISIT_IF:** The product owner explicitly chooses a platform-first public launch. Do not silently drop the other platform or block Android-only internal testing while waiting for a Mac.
**STATUS: SETTLED**

## 2. Audit evidence and limitations

### 2.1 Evidence levels

- **SOURCE-CONFIRMED:** The cited implementation directly establishes the behavior. It may still need a runtime reproduction before fixing it.
- **EXECUTED:** A command/scenario was run during this audit; its exact scope and result are recorded in section 8.
- **RISK / NOT VERIFIED:** A plausible failure or missing assurance that still needs the named experiment. It is not a proven production incident.
- **PROPOSED CONTRACT:** A deliberate requirement of this backlog, not a claim about the current API.

This was a targeted source audit across mobile, native share, backend/domain integration, security/privacy, infrastructure, CI and operations, not a line-by-line security certification. Graph metadata was dated August 24 and stale relative to September changes; filesystem source reads were used to verify current paths. No retailer/device compatibility, real-provider output, AWS deployment, signed release, restore drill, or iOS runtime was certified. Secrets and private image contents were not needed for the audit.

### 2.2 Concrete findings

**F01 — Mobile has no real photo acquisition. SOURCE-CONFIRMED.** `mobile/src/features/my-model/photoSource.ts:24-30`: both `takePhoto()` and `choosePhoto()` return `null`. This blocks profile creation and manual import/generation. Preserve the existing `LocalPhoto` boundary, replace the null implementations. PR-05.

**F02 — Mounted API clients are unconfigured. SOURCE-CONFIRMED.** `mobile/src/features/generation/generationApi.ts:125-128`, `mobile/src/features/vault/vaultApi.ts:23`, `mobile/src/features/studio/studioApi.ts:12` default to `baseUrl: ''` and `getAccessToken: () => null`. `AppShell.tsx:17-42` mounts navigation/share handling without supplying a live authenticated service graph. Individual factory tests do not prove the mounted app can call the backend. PR-04.

**F03 — Studio cannot build the requested outfit. SOURCE-CONFIRMED.** `mobile/src/screens/StudioScreen.tsx:16-60` derives readiness from route parameters, does not create/load/mutate a persisted outfit, and disables Add from Vault without a session. `RootNavigator.tsx:58-60` routes Generate without passing an outfit. `GenerateScreen.tsx:173-176` explicitly offers one product photo. PR-12, PR-13, PR-16.

**F04 — A resolved share cannot become a saved item through the screen. SOURCE-CONFIRMED.** `ShareImportScreen.tsx:56-59` discards `importId` on READY; lines 230-232 display only “Shared item ready.” Backend `ShareImportResponse.java:12-29` exposes status, not a confirmable item preview; `VaultController.java:22-27` accepts `uploadId`, not a resolved import reference. Existing resolver success is not Vault-save success. PR-08, PR-11.

**F05 — Import replay checks already-consumed staging before idempotency. SOURCE-CONFIRMED ordering defect; runtime reproduction required.** `platform/web/ShareImportService.java:50-59` calls `requireClaimableUpload` before `store.create` can replay. `platform/objectstore/ObjectStoreImportMediaAccess.java:101-119` consumes staging after a successful claim. A repeated IMAGE request following response loss can fail instead of returning its persisted import. PR-07.

**F06 — Vault is a text list, not an image-selection library. SOURCE-CONFIRMED.** `VaultScreen.tsx:55-57` renders item-name actions, disabled in browse mode. `VaultItemSummary` contains only id/category/name; `VaultController.java:42` does not return an image read capability. Collections are viewed through local filtering, not a complete editing experience. PR-06, PR-11.

**F07 — Generation publication uses a mode string as JSON render-plan data. SOURCE-CONFIRMED cross-layer defect; full worker reproduction required.** `platform/generation/GenerationWorkProcessor.java:1012-1024` builds a Look with `job.requestedMode()` in `renderPlan`, null avatar provenance and empty Look items. `look/JdbcLookStore.java:240-257` casts that string to `jsonb`. A bare value such as `STANDARD` is not valid JSON, and even quoting the word would not preserve the outfit. Fix provenance and publication together, not just the SQL error. PR-14.

**F08 — Baseline image validation is presented as quality PASS. SOURCE-CONFIRMED.** `GenerationWorkProcessor.java:2450-2501` binds new attempts to `QualityGate.BASELINE_POLICY_VERSION`. `ApplicationOwnedQualityGate.java:64-90` passes an image whose header/dimensions pass `ImageInspector`; it does not establish full pixel validity, identity or clothing fidelity. Keep structural validation, stop equating it with semantic quality. PR-02, PR-25.

**F09 — Existing provider categories are inconsistent. SOURCE-CONFIRMED.** `AuthorizedGenerationMediaResolver.java:27,97-98` supplies `garment` for a frozen manual input. `platform/fashn/FashnMapper.java:72-101` declares categories such as `clothing`, `tops`, `bottoms`, and a maximum of one product per mode. This is not proof of the required multi-item capability. Do not fix by pretending unsupported categories are supported or selecting FASHN by default. PR-02, PR-12, PR-24.

**F10 — Firebase verification caches signing keys indefinitely. SOURCE-CONFIRMED.** `ExternalTokenVerifierConfiguration.java:27-46` caches a JWKS document without expiry; `JwtExternalTokenVerifier.java:147-170` caches parsed keys without refresh on rotation. Normal signing-key rotation can make valid users fail authentication until restart. PR-03.

**F11 — ECS secret injection is in the wrong container-definition field. SOURCE-CONFIRMED, corroborated by AWS documentation.** `infra/terraform/ecs.tf:30-45,97-114` mixes `valueFrom` entries into `environment`. ECS secret references belong in `secrets`. Terraform syntax validation cannot establish that containers receive credentials. PR-20.

**F12 — iOS text-share activation uses an undocumented key. SOURCE-CONFIRMED, corroborated by Apple documentation.** `mobile/ios/FashionVaultShare/Info.plist:33-34` uses `NSExtensionActivationSupportsTextWithMaxCount`. The documented text key is Boolean `NSExtensionActivationSupportsText`. URL/image declarations exist; the extension is not absent. PR-10.

**F13 — Test labels overstate integration coverage. SOURCE-CONFIRMED and EXECUTED.** `mobile/integration/first-vertical-slice/firstVerticalSlice.test.ts:54-61,76-146` mocks all fetches and supplies a fake picker/token. It is a useful orchestration test, not a device end-to-end test. All 17 mobile suites / 243 tests passed during this audit despite F01–F04. Focused backend tests also passed; they do not cover real-provider publication through every production adapter. PR-01, PR-21, PR-23.

**F14 — Canonical documentation still describes reserved paths. SOURCE-CONFIRMED.** `docs/ARCHITECTURE.md`, `docs/API_CONTRACTS.md`, `docs/DATA_MODEL.md`, `docs/PRIVACY.md` state “deferred.” `README.md:13-17,117-122` still describes applications as future initialization work. These are onboarding and agent-execution hazards, not reasons to generate another huge architecture document. Update each with the behavior delivered by its owning PR. PR-01 and each feature PR.

**F15 — Default upload completion cannot succeed. SOURCE-CONFIRMED.** `backend/src/main/java/com/aifashionvault/platform/objectstore/ObjectStoreConfiguration.java:67-79` wires a scanner that always returns UNAVAILABLE. `UploadWorkflow.java:96-115` treats non-CLEAN as image rejection and deletes staging. Its convenience constructors at lines 17-26 instead use unconditional CLEAN, masking the production path in tests. Neither behavior is a working security control. PR-05 must implement the real scanning boundary and retryable unavailable behavior before profile acceptance can pass; PR-06/PR-18 prevent weaker claim-path bypasses.

**F16 — Active account deletion is purge-first, not the durable workflow. SOURCE-CONFIRMED.** `backend/src/main/java/com/aifashionvault/user/JdbcIdentityProvisioning.java:94-115` calls media purges before setting `deleted_at`; `platform/objectstore/S3ObjectStore.java:461-500` suppresses individual object-delete errors. `privacy/DefaultAccountDeletionWorkflow.java` and V021 express a separate durable design that is not connected to this path. Interrupted cleanup can leave an active account or leftover media without reliable completion evidence. Concurrent resurrection is a risk to reproduce, not an incident observed here. PR-17.

**F17 — Multi-step counters are not outfit composition. SOURCE-CONFIRMED audit finding.** `backend/src/main/java/com/aifashionvault/platform/generation/AuthorizedGenerationMediaResolver.java:60-118` resolves the original avatar and all products. `GenerationWorkProcessor.java:600-616` submits that same set, lines 924-948 discard an accepted intermediate before advancing, and lines 1238-1248 supply no previous accepted image to evaluation. Repair one coherent native multi-image route first; disable the invalid implicit sequential path. If a selected provider requires sequential composition, PR-24 must prove it. PR-15/PR-24.

**Path convention:** Short backend package paths in findings/tickets are relative to `backend/src/main/java/com/aifashionvault/`; short mobile feature/screen paths are relative to `mobile/src/`. New controller/store/fixture files are explicitly identified as new work, not asserted present. Existing test class names resolve under `backend/src/test/java/` or the cited mobile test directory.

### 2.3 Keep these investments

Retain and test, rather than rebuild: owner-scoped identifiers and queries; private storage domains and bounded image inspection; native URL/image share handoff; resolver fallbacks; structured Studio domain; PostgreSQL migrations; durable generation jobs, leases and outbox; cancellation/publication arbitration; controlled problem codes; existing secret-log redaction; existing focused behavioral tests. Their presence does not imply all their production wiring works.

Do not delete a provider port merely because it currently has one implementation: provider substitution is an explicit product requirement. Do not remove transaction boundaries, leases or idempotency merely to shorten files. Remove parallel obsolete paths only after the replacement journey and recovery cases pass.

### 2.4 What to simplify while repairing the owning path

- **Remove repeated unconfigured client defaults:** construct all feature APIs from the one live session/configuration boundary in PR-04. Retain the shared HTTP/upload helper rather than introduce feature-specific transports.
- **Replace route parameters pretending to be state:** PR-12/PR-13 make one persisted Studio revision authoritative. Do not keep old `hasCompatibleItem` route heuristics as a fallback.
- **Remove the competing manual-generation route:** PR-15 routes saved single items and multi-item outfits through one frozen-input submission contract. Historical data remains readable; obsolete callers do not remain as aliases.
- **Reduce the worker's responsibilities, not its guarantees:** `GenerationWorkProcessor.java` exceeds 2,700 lines. In PR-14/PR-15 extract only the repaired publication and immutable-input/step execution responsibilities if that makes their tests and ownership clearer. Preserve leases, cancellation, uncertainty and idempotency; do not add a service/interface per method or rewrite the entire worker before a passing vertical path.
- **Keep one active deletion workflow:** PR-17 connects the durable workflow and retires purge-before-disable orchestration after migration and retry proof. Do not keep both entry paths indefinitely.
- **Do not provision unused cache infrastructure:** PR-20 removes/defer-manages the unconsumed Valkey resource after reviewing actual deployed state. No data resource is destroyed merely because source search shows no consumer.
- **Remove misleading optional controls:** unsupported natural-language/HD/public-link actions stay absent from the MVP UI and do not add mandatory secrets/startup dependencies. Keep useful existing domain tests; delete tests that only freeze an intentionally removed fake behavior.

No estimated lines/dependencies saved are claimed: this audit did not measure a safe deletion diff. Each removal must prove that required behavior, security and existing user data survive.

## 3. MVP scope and non-negotiable contracts

These requirements apply to every ticket below. Values stated as launch defaults are product decisions proposed by this backlog, not universal industry standards.

### 3.1 User journeys

- **R1 — Profile:** Sign in, choose a real photo, review usage/consent, upload privately, set/replace the active model photo, and delete it. One active photo is enough; no 3D body model or measurements.
- **R2 — Save an item:** Share URL/text/image from a shopping app or select a photo; see a private preview; correct category/name; select/create a collection; confirm; see the saved item. Failed URL extraction must offer screenshot/photo fallback without losing the source reference.
- **R3 — Organize:** Browse image thumbnails by category and custom collection, rename/edit metadata, add/remove collection membership, and delete items. Categories and collections are different: TOP/BOTTOM/FOOTWEAR describe items; “Weekend” is a user collection.
- **R4 — Outfit:** Select at least TOP + BOTTOM + FOOTWEAR together with the active profile; show each selected image, replace/remove items, and persist the draft. Each occupied core slot contains one item. Existing full-body compatibility rules remain authoritative; do not silently discard conflicting selections.
- **R5 — Generate:** Freeze the profile/image revisions and ordered outfit snapshot, submit one logical request, show durable progress, survive restart/response loss, and display one private Look with the exact source-item provenance. No provider configured means honest unavailability and no lost draft.
- **R6 — History:** Reopen completed Looks, see source items, delete a Look, and rebuild a draft from a past Look where referenced items still exist. A missing/deleted item needs an explicit replacement, never silent substitution.
- **R7 — Privacy:** Owner isolation, consent withdrawal, profile/item/Look/account deletion, local-cache cleanup, bounded temporary retention and third-party deletion behavior must be real.
- **R8 — Operability:** Reproducible builds, deployable staging, mandatory relevant tests, signed store artifacts, backup/restore and actionable alerts must have evidence, not only configuration files.

The required public capability includes the combined top/bottom/footwear outfit, not just selecting three images and rendering one. Other existing categories may remain saveable; advertise their generation support only after provider capability and evaluation evidence. If no candidate can meet the combined outfit requirement, public launch is blocked unless the user explicitly changes the product scope.

### 3.2 Provider placeholder contract

- Use the existing `VtonProvider` boundary; do not create a second provider registry or choose a vendor now.
- Proposed configuration has three explicit operational meanings: disabled/unconfigured; isolated test adapter; real configured provider. Reuse existing config infrastructure and encode these meanings there, rather than creating an independent feature-flag service.
- In ordinary internal/staging/production builds with no provider: imports, profiles, collections and Studio remain usable; Generate explains unavailability. A direct API request is rejected before job creation and quota reservation with a documented unavailable problem response.
- The test adapter exists only in tests or an explicitly isolated test environment. It records ordered input asset IDs/revisions, categories, mode and request identity and can return controlled success/failure. Fixtures must be marked synthetic and excluded from user metrics, quality statistics and production histories.
- Real generation must fail closed when provider/model configuration, consent, category support, input count, budget or mandatory safety control is missing. No fallback provider, automatic model routing or silent lower-fidelity substitution in this MVP.
- A public API/DTO may contain internal capability/category terminology, never provider-specific response classes or secret-bearing URLs.
- Transport/image validity and semantic fidelity are separate results. Do not fabricate identity, garment or anatomy scores. Provider selection and semantic evaluation remain Gate B work.

### 3.3 Persistence, replay and deletion

- PostgreSQL is the source of truth for ownership, drafts, jobs, attempts and deletion state. The queue may redeliver; mobile may lose any response.
- A logical mutation has stable owner-scoped idempotency identity. Same key + same request returns the recorded outcome even after staging is consumed. Same key + different payload fails without mutation. A retry after ambiguous network failure is not a new billable request.
- Studio mutation uses a revision/precondition. Stale writes must not overwrite newer outfits. Generation stores an immutable snapshot, not a pointer whose meaning changes while the worker runs.
- Multi-item order and selected asset revisions are explicit. Profile replacement, category correction, item removal and concurrent deletion must not silently change already-submitted inputs.
- Persist the Look only after its private object exists and the required validation succeeds. Job completion, Look metadata and completion-event publication must converge without duplicate Looks or cross-owner disclosure after worker crash.
- No claim of exactly-once execution across a third-party provider. An uncertain submit needs reconciliation using that provider's supported identity, or a terminal uncertain outcome; never blind retry a potentially charged request.
- Account deletion first blocks further use, then runs durable resumable cleanup. A failed object delete cannot count as completed. Polling, workers, uploads, import confirmation and publication all respect the deletion barrier.
- Initial retention ceiling: temporary inputs, failed intermediates and abandoned import staging become inaccessible and are scheduled for deletion within 24 hours. Document physical object/version/backup expiration separately; S3 lifecycle timing is not a precise 24-hour erasure guarantee.

### 3.4 Security and UX floor

- Verify identity server-side; enforce ownership at every item/profile/collection/job/Look/media operation, not only on the screen.
- Use OS photo pickers, private app storage and narrowly scoped URI grants. No blanket photo-library permission for selecting one image. Normalize orientation; bound byte size and decoded dimensions; reject malformed image content; avoid copying large images through JS base64.
- Do not log photo bytes, tokens, source URLs with sensitive query values, signed URLs, provider bodies or secrets. Image previews are valid inside the owner's UI; a telemetry-redaction rule must not be misapplied to forbid required product previews.
- Resolver network access must defend every hop, DNS result and image fetch against private/link-local/loopback/metadata destinations, IPv6 variants, redirect escapes and oversized responses. No retailer authentication bypass, CAPTCHA evasion, or business-critical scraping.
- All network-bound screens need loading, empty, retryable failure, offline and session-expired behavior. Preserve drafts through retry. Distinguish “item saved” from “generation failed”; never state saved before persistence succeeded.
- Images, selected states, labels and disabled reasons must be understandable with TalkBack/VoiceOver; support text scaling, adequate contrast, safe areas and reachable controls on small phones. This is a functional gate, not a cosmetic final pass.
- Initial launch is capped/free; billing, credits sales and subscriptions are out of scope. Quotas and a global spend kill switch are still required before a real provider is enabled.

### 3.5 Deliberately out of the shipping critical path

Natural-language Studio, stylist recommendations, social/public wardrobes, public share-link infrastructure, affiliate/catalog integrations, price tracking, fit/size/body measurements, HD tiers, multi-provider optimization, training pipelines, large automated benchmark platforms, extensive funnel analytics and microservices are not MVP acceptance criteria. Keep already-working safety-relevant code; stop expanding these features. Remove visible controls and production startup dependencies for unsupported optional features in their owning PR. Push notifications are optional acceleration: durable status/history and foreground resume must work without them. Never let this defer private data deletion or reliable background jobs.

## 4. Execution rules

1. **One PR, one independently reviewable outcome.** Do not merge several tickets into another mega-slice. Do not split a source fix from the regression case that proves it.
2. **Before editing:** read the current cited source, find all callers, and inspect actual request/response records. Line numbers below are audit anchors, not immutable offsets.
3. **Before changing a public shape:** record its exact request, success, error, replay and pagination examples in `docs/API_CONTRACTS.md`, then exercise those examples against the real controller and mobile parser. New shapes below are requirements to specify, not invented existing APIs.
4. **No schema reset:** preserve V001–V023 checksums and existing data. Select the next unused migration at execution time. Use additive changes/backfills and explicit old/new compatibility; never renumber historical migrations.
5. **Evidence:** record commit, command, environment, exit code, passed/failed/skipped count, fixtures and runtime results. Test existence is not a pass. Native source inspection is not a native build. A mock-backed test is not device E2E.
6. **Do not weaken oracles:** do not remove ownership checks, skip Docker tests, broaden fake success responses, suppress errors, or change an expected status solely to get green.
7. **Cleanup in the same owning PR, after proof:** migrate every caller and remove the obsolete path, fake default, duplicate contract or test assumption. Update the relevant short canonical doc. No unrelated format/reorganization sweeps.
8. **Migration/rollback:** every data/config PR defines compatibility and rollback. Rolling back application code must not require deleting user data. A dangerous cleanup waits for the compatibility window, not an optimistic merge.
9. **Readiness labels:** use IMPLEMENTED, VERIFIED-IN-TEST, VERIFIED-ON-DEVICE, VERIFIED-IN-STAGING, EXTERNALLY-BLOCKED and RELEASE-APPROVED precisely. Do not call a source-complete ticket production-ready.
10. **Final decisions are owned by the user:** provider choice, target launch countries/age policy, legal terms, AWS spending and store release approval are explicit gates. Implementers may prepare evidence; they may not fabricate approvals.

## 5. Sequential PR checklist

The list is the default merge order. Dependencies identify hard prerequisites if scheduling later changes. Do not start public-provider work just to bypass an unfinished core ticket. PR-24 is intentionally externally blocked; all earlier tickets can be completed without selecting a model.

- [ ] **PR-01:** Establish a truthful baseline and contract inventory.
- [ ] **PR-02:** Make provider-unconfigured behavior explicit and safe.
- [ ] **PR-03:** Repair server authentication and signing-key rotation.
- [ ] **PR-04:** Wire real mobile authentication and API composition.
- [ ] **PR-05:** Finish real photo selection and profile lifecycle.
- [ ] **PR-06:** Expose verified private library media and bounded queries.
- [ ] **PR-07:** Repair import retry and consumed-upload replay.
- [ ] **PR-08:** Complete resolved-import confirmation into Vault.
- [ ] **PR-09:** Make Android share handoff durable through lifecycle changes.
- [ ] **PR-10:** Make iOS share handoff correct and independently verified.
- [ ] **PR-11:** Finish visual Vault, collections and import confirmation UI.
- [ ] **PR-12:** Persist authorized Studio outfits and canonical slot rules.
- [ ] **PR-13:** Wire multi-item selection to durable Studio state.
- [ ] **PR-14:** Publish coherent Looks through the real database path.
- [ ] **PR-15:** Connect outfit submission, queue recovery and bounded execution.
- [ ] **PR-16:** Finish restart-safe generation status and Look history UX.
- [ ] **PR-17:** Connect durable privacy, deletion and retention controls.
- [ ] **PR-18:** Close ingestion, authorization and network trust boundaries.
- [ ] **PR-19:** Make quotas and spend enforcement durable across replicas.
- [ ] **PR-20:** Make the existing deployment actually runnable in staging.
- [ ] **PR-21:** Make CI and release artifacts enforce real integration gates.
- [ ] **PR-22:** Prove observability, recovery and backup restoration.
- [ ] **PR-23:** Pass provider-ready real-device acceptance.
- [ ] **PR-24:** Integrate the explicitly selected provider after owner approval.
- [ ] **PR-25:** Validate actual output quality, safety and processor privacy.
- [ ] **PR-26:** Approve store release and staged public rollout.

### Hard dependency map

PR-01 precedes all work. PR-02 precedes generation changes. PR-03 → PR-04 → PR-05. PR-06 and PR-07 → PR-08. PR-04/PR-07 → PR-09/PR-10. PR-05/PR-06/PR-08 → PR-11. PR-06 → PR-12; PR-11/PR-12 → PR-13. PR-02/PR-12 → PR-14; PR-12/PR-14 → PR-15; PR-13/PR-15 → PR-16. PR-17 consumes the actual media/job paths introduced by PR-05–PR-16. PR-18 and PR-19 harden those paths. PR-20/PR-21/PR-22 prepare deployment evidence; PR-23 requires all of PR-01–PR-22 for both-platform Gate A. PR-24 requires Gate A plus provider approval. PR-25 requires PR-24. PR-26 requires all public-launch gates.

This ordering is not permission to expose an unfinished account-deletion or security path to public users. Early builds are isolated engineering environments with synthetic or explicitly consented test data.

## 6. Ticket specifications

Each ticket inherits section 3. **Acceptance** items are merge requirements, not optional suggestions. **Proof** states what must be exercised. Where source evidence already meets a requirement, attach current proof rather than rewrite it. Every ticket updates its relevant contract/runbook and removes obsolete callers after proof.

### PR-01 — Truthful baseline and contract inventory

**Priority:** P0. **Depends on:** none. **Requirements:** R1–R8. **Scope:** `README.md`, the four deferred canonical docs, existing mobile/backend tests and CI; no product redesign.

**Why:** F13/F14. Slice completion and test names currently conceal which boundaries are real.

**Work:**
- [ ] Replace stale initialization language with current startup, environment and validation commands. State PostgreSQL/JDBC + SQS, not the historical Redis/JPA plan.
- [ ] Inventory actual mounted routes, production beans and native targets. For every MVP path identify controller/request/response, persistent store, mobile caller, runtime configuration and current missing connection.
- [ ] Freeze one compact contract inventory: profile, verified upload, import state/confirmation, item/collection, Studio, generation, Look and deletion. Mark new routes as proposed until delivered by their owning ticket; do not claim they exist.
- [ ] Rename/reclassify the mock-based first-vertical-slice test as integration/orchestration coverage; retain its valuable assertions. Record exact reproducible probes for the unconfigured mounted API/picker boundaries as baseline failure evidence. PR-04/PR-05 add and close the corresponding regression tests with their source fixes; do not merge permanently failing required tests in this documentation/baseline PR.
- [ ] Capture the full baseline once in the target CI environments and classify skips; do not inflate this audit's focused runs into a full-suite pass.

**Acceptance:** A new engineer can reproduce the current startup and failures and distinguish enabled, disabled and unfinished paths. The contract inventory maps every R1–R8 requirement to a ticket. Existing failures are recorded with owning tickets, not repaired outside this PR's scope or represented as pass.

**Proof:** Clean-checkout documented startup; actual app root mounted; mobile full gate; backend `verify`; native build statuses explicit per platform. Preserve failing-boundary evidence before later tickets fix it.

**Rollback/guard:** Documentation and test classification only; no deleting working code, resetting data, or blanket dependency upgrades.

### PR-02 — Honest provider placeholder and capability boundary

**Priority:** P0. **Depends on:** PR-01. **Requirements:** R5/R8. **Scope:** existing `generation/vton/`, `platform/fashn/`, `platform/generation/`, generation admission/configuration, `application.yaml`, mobile generation availability.

**Why:** F08/F09 and runtime provider-specific configuration conflict with the intentionally unselected model.

**Work:**
- [ ] Make vendor-specific bean construction and secrets conditional on explicit selection. Preserve the existing generic provider interface and migrate provider resolution to that interface, without a parallel registry.
- [ ] Define and test disabled, isolated-test and real-configured behavior. Boot the application without a provider key; existing non-generation beans/routes must remain available. Later profile/import/Studio tickets must preserve this property; this PR does not claim to finish their already-missing workflows.
- [ ] Enforce unavailable generation before reservation, image transfer, job/outbox insertion or provider call. Expose the same capability to the mobile app; direct API bypass must also fail safely.
- [ ] Create a deterministic test adapter at the external-provider boundary only. It must inspect all input references/categories/order and provide controllable completion, rejection, rate-limit, timeout and uncertain-submission outcomes.
- [ ] Label structural image validation honestly; do not enable the existing semantic evaluator or invent quality scores as a substitute for a model decision.

**Acceptance:** No real external inference occurs in the default environment. No real user sees fixture output. Missing provider configuration is not a backend startup crash and cannot consume quota. Existing incomplete provider integrations do not define product capability.

**Proof:** Actual application context with provider unset; authenticated generation denial leaves job/outbox/reservation counts unchanged; existing non-generation route/bean availability does not regress. Re-run successful profile/import saves after PR-05/PR-08 and include them in Gate A. Test-only success is blocked in a production configuration.

**Rollback/guard:** Roll back to generation disabled, never to an implicitly selected provider. No vendor selection, API-key acquisition or paid calls in this PR.

### PR-03 — Server identity verification and key rotation

**Priority:** P0. **Depends on:** PR-01. **Requirements:** R1/R7. **Scope:** `platform/security/ExternalTokenVerifierConfiguration.java`, `JwtExternalTokenVerifier.java`, existing identity provisioning and security tests.

**Why:** F10. A permanently cached signing-key set is not a production identity verifier.

**Work:**
- [ ] Replace bespoke token/key lifecycle with the supported Firebase Admin verification path behind the existing `ExternalTokenVerifier` seam. Keep internal user mapping and owner authorization. One narrowly scoped SDK dependency is justified here; do not hand-roll another JWT implementation.
- [ ] Verify issuer, audience/project, signature, expiry, subject and relevant time claims. Token revocation/account disabling must participate in account deletion and sensitive operations; document the latency/cache policy.
- [ ] Bound verification/key-fetch failure and classify infrastructure unavailability separately from invalid credentials without leaking token content. Unknown key rotation cannot require server restart.
- [ ] Keep local identity/testing explicit and impossible to activate through request headers or a production misconfiguration. Do not ship debug users or generated default-password authentication as a bypass.

**Acceptance:** A valid token signed after a legitimate key rotation works; wrong-project/expired/tampered/revoked tokens do not provision active users. Deletion blocks re-entry and cannot recreate the same identity accidentally.

**Proof:** Existing `ExternalTokenVerificationTest`, real wiring test, controlled old/new-key and key-fetch-failure cases, signed-in/out smoke against a dedicated Firebase test project. Record revocation behavior rather than claiming it from signature verification alone.

**Rollback/guard:** Fail closed if verification fails. Preserve user UUIDs and identity mappings; do not migrate ownership by email address.

### PR-04 — Real mobile session and configured API graph

**Priority:** P0. **Depends on:** PR-03. **Requirements:** R1–R7. **Scope:** `mobile/src/app/AppShell.tsx`, `navigation/RootNavigator.tsx`, `features/my-model/http.ts`, all existing API clients, native auth configuration.

**Why:** F02. Factory functions with useful logic exist, but their mounted singleton defaults are unusable.

**Work:**
- [ ] Add reachable sign-in, hydration, signed-out, signed-in, reauthentication and logout states using real Firebase client authentication. Android Google sign-in and the appropriate iOS sign-in choices must meet current store rules; no token-paste production UI.
- [ ] Build one app-owned configuration/session boundary supplying an absolute environment-specific HTTPS API origin and current ID token to every existing client factory. No separate per-feature null-token fallbacks.
- [ ] Handle expiration once centrally: bounded refresh and replay only when safe; mutating requests preserve their idempotency identity. Cancel/scope in-flight requests on logout and clear user-scoped state so another user never sees the previous user's media.
- [ ] Queue incoming share/Look destinations until auth and navigation are ready. Coordinate with PR-09/PR-10 acknowledgement rather than dropping callbacks.
- [ ] Load actual feature/capability state. Keep developer HTTP/LAN exceptions out of release builds.

**Acceptance:** Fresh install can sign in and fetch `/me` plus private owner data through the mounted app, restart without token-paste setup, sign out, and sign in as another account with no stale data. Every protected API receives the real token from the same boundary.

**Proof:** Mounted-app tests with real composition and substituted external auth only; device sign-in/network trace with secrets redacted; expiry, offline startup and account-switch scenarios. Remove empty-origin/null-token production singletons after callers migrate.

**Rollback/guard:** Do not persist raw ID/refresh tokens in generic JS storage or logs; use SDK/platform credential storage. No whole-app state framework migration.

### PR-05 — Real photo selection and profile lifecycle

**Priority:** P0. **Depends on:** PR-04. **Requirements:** R1/R7. **Scope:** `features/my-model/photoSource.ts`, `avatarApi.ts`, `MyModelScreen.tsx`, native photo integration, avatar workflow/storage, `platform/objectstore/ObjectStoreConfiguration.java`, `UploadWorkflow.java` and scanner integration.

**Why:** F01/F15. Both the null picker and default unavailable upload scanner prevent a real profile upload.

**Work:**
- [ ] Replace the null photo-source implementations with real system picker/camera integration. Preserve cancellation as a no-op and permission denial as a recoverable state. Support real Android content URIs and iOS picker files without relying on filename suffixes.
- [ ] Normalize supported formats/orientation and metadata safely; communicate unsupported formats, oversized/too-small images and preflight failures. Avoid multiple full-size JS copies/base64 payloads.
- [ ] Implement the existing malware-scanner port with a real maintained scanning engine/service in the controlled backend environment, with bounded requests and a health probe. Freeze its deployment/API choice in this PR before coding the adapter. Keep byte/decoded-image validation independently mandatory. Remove always-CLEAN production-capable constructors; tests may inject explicit scanner fixtures. Scanner unavailability is a retryable service problem and retains quarantined input for bounded retry; infected/invalid input is rejected and cleaned up. Never repair the default UNAVAILABLE stub by returning CLEAN or bypassing the scanner.
- [ ] Wire private upload completion, profile create/replace using existing revision semantics, signed preview, and deletion. Retain the old active photo if replacement fails before commit.
- [ ] Refresh on foreground and recover expired preview capabilities. Distinguish initial load failure from “no profile exists.” Include explicit body-photo use disclosure and a real consent record before uploading/using it.

**Acceptance:** Selected pixels reach private storage and render as the active profile after restart. Cancel sends no upload; offline/denied/invalid cases preserve existing profile; replacement conflicts are visible; deletion invalidates future use.

**Proof:** Default-bean upload integration with a real scanner, benign image, safe scanner test signature, scanner outage/restart and bypassed completion; then real device gallery/camera image paths, limited photo access, rotated JPEG, supported iOS source format, interrupted upload/retry and replace/delete race with a stale client. Existing ONNX tests are retained but do not replace consenting-user photo trials.

**Rollback/guard:** No 3D/measurement feature. Old media cleanup follows durable reference/retention rules, not delete-before-replacement success.

### PR-06 — Verified private library assets and bounded queries

**Priority:** P0. **Depends on:** PR-05. **Requirements:** R2/R3/R7. **Scope:** `platform/web/VaultController.java`, `VaultService.java`, `VaultItemResponse.java`, `vault/JdbcVaultStore.java`, collection API and object-store read capabilities.

**Why:** F06 and inconsistent create-path media validation. The library needs actual images, not names alone.

**Work:**
- [ ] Require an owner-scoped, successfully verified upload or confirmed import asset; a caller must not bypass byte validation by skipping upload completion. Reuse the existing bounded image-validation/claim policy.
- [ ] Expose authenticated private preview/read capabilities for items, with expiry and refresh behavior. Persist asset IDs/keys internally, never signed URLs as identity. Produce appropriately bounded thumbnails or preview variants without original-image downloads for every grid cell.
- [ ] Add stable bounded pagination to item/collection reads while preserving current filters; proposed default page size 30, maximum 100. Freeze cursor/order/error shapes and migrate every existing parser/caller in this PR. PR-11 adds the fuller browsing UX against that already-consistent contract; do not leave array/envelope callers broken between merges.
- [ ] Define category tokens once across backend/mobile/provider mapping. Preserve existing categories; fix singular/plural mismatches such as ACCESSORIES versus the domain token without creating aliases indefinitely.
- [ ] Ensure source references attached to an item are allowed for that owner; existence of a globally known UUID is not authorization. Do not accept arbitrary object keys or signed URL hosts from clients.

**Acceptance:** The owner can retrieve item metadata plus a private image; another owner cannot. Large libraries are bounded and deterministically paged, with filters applied server-side. Invalid/bypassed uploads cannot become items.

**Proof:** `VaultControllerTest`, `JdbcVaultStoreTest`, collection tests, expired read capability, cross-owner asset/source IDs, cursor boundary and concurrent insertion cases; image decode failure cannot be promoted.

**Rollback/guard:** Additive media metadata/cursor changes first; preserve existing asset ownership and migration checksums. No public buckets or new image CDN dependency by default.

### PR-07 — Replay-safe import and recovery

**Priority:** P0. **Depends on:** PR-06. **Requirements:** R2/R7. **Scope:** `platform/web/ShareImportService.java`, `importer/JdbcShareImportStore.java`, `ObjectStoreImportMediaAccess.java`, `mobile/src/features/share/importClient.ts`, `screens/share-import/recoveryClient.ts`.

**Why:** F05. Retry must succeed after a response is lost, not require a now-deleted file or already-consumed upload.

**Work:**
- [ ] Arbitrate owner/key/fingerprint replay before requiring staging for an already-accepted request; still fully validate genuinely new work and never expose another owner's result.
- [ ] Preserve local staging until the server durably owns a recoverable upload/import, or the user explicitly abandons it. Do not release the only input after transient upload failure while showing Try Again.
- [ ] Persist the recovery key + upload binding across ambiguous attach responses, or reconcile the canonical import before uploading again. Same key must never be reused with a newly generated upload ID.
- [ ] Show recovery actions legal for the current server state. `NEEDS_IMAGE` permits image attachment; infrastructure retry is not permission to bypass the state machine.
- [ ] Preserve bounded polling/cancellation and durable operation identity through remount/restart. Clean up abandoned staging through TTL ownership, not a fragile component unmount callback.

**Acceptance:** Lost create/attach responses and temporary upload failure each recover to the same import without duplicate assets or invalid key reuse. Replays succeed after consumed staging. Different payload under the same key remains a conflict.

**Proof:** Real JDBC plus an object-store adapter that actually consumes staging; a file-backed local test that physically deletes/releases files; controlled first-response loss; app restart; same/different key payload cases. Existing mocks must not override consumption semantics.

**Rollback/guard:** Preserve persisted idempotency records; never solve replay by weakening owner or fingerprint checks.

### PR-08 — Confirm a resolved import into a saved item

**Priority:** P0. **Depends on:** PR-06/PR-07. **Requirements:** R2/R3. **Scope:** importer resolution/preview, `platform/web/ShareImportService.java`, Vault/collection application services and existing stores; one minimal new confirmation endpoint if absent.

**Why:** F04. READY means resolution finished, not that the user owns a saved library item.

**Work:**
- [ ] Specify an owner-only preview/confirmation contract carrying `importId`, a short-lived private image preview, editable metadata/category, source reference and optional collection membership. Explicitly distinguish unresolved, ready-to-confirm and saved states.
- [ ] Confirm using already-owned resolved media; URL imports must not require a nonexistent original upload ID. Reuse item creation rules without another upload/download round-trip.
- [ ] Commit item identity and requested collection membership consistently. Repeat confirmation returns the same saved item. Concurrent confirmation cannot create two items or lose source provenance.
- [ ] Reconcile object promotion failure and DB failure without an orphaned success response. Abandoned resolution remains under bounded retention; confirmation transfers ownership to the persistent library lifecycle.
- [ ] Expose enough saved identity for immediate item detail and Studio selection. Never call a failed or merely resolved import “saved.”

**Acceptance:** URL, image and screenshot fallback each yield one visible saved item with the correct source/category/collection. Restart and repeat confirmation produce the same result.

**Proof:** `ShareImportHttpTest` and actual importer→Vault→collection integration; duplicate concurrent confirmation, source-owner violation, deleted import, failed storage promotion and DB rollback; final query/read capability must work after reconnect.

**Rollback/guard:** Preserve resolved assets until durable confirmation or documented expiry. Do not insert raw untrusted URL metadata directly into logs or bypass the safe fetcher.

### PR-09 — Android share handoff that survives app lifecycle

**Priority:** P0. **Depends on:** PR-04/PR-07/PR-08. **Requirements:** R2/R7. **Scope:** existing Android share receiver, stager, pending-share store and native bridge; JS `features/share/`, `AppShell.tsx`.

**Work:**
- [ ] Replace destructive consume-before-route semantics with a bounded durable claim/ack flow. Acknowledge only when authenticated app state durably owns the resumable import. Navigation-not-ready cannot lose it.
- [ ] Preserve URL/text/image and source context safely; copy content URI data while permission is valid. Persist validated media type with the staged asset instead of guessing from an extensionless filename.
- [ ] Handle cold/warm start, receiver recreation, login continuation, duplicate intent delivery, process kill after copy and process kill during upload. Keep one logical import per explicit share; a later intentional second share is allowed.
- [ ] Reap abandoned metadata and files, including orphan files after record consumption. Keep export/URI permissions minimal. Explicitly reject unsupported multi-attachment payloads rather than silently saving only one.

**Acceptance:** An Android shopping-app share survives login and restart and hands a durable import ID to the host flow. Use the already-delivered PR-08 HTTP confirmation contract to prove final persistence in this ticket; the interactive confirmation screen is delivered by PR-11. No content is leaked into a deep link, and no file grant is assumed permanent.

**Proof:** Existing native share JVM tests plus an installed Android build receiving actual text, URL and image SEND intents; slow startup, denied source grant, killed receiver, duplicate delivery, failed first upload and expiry. Capture app/OS version and final backend item ID.

**Rollback/guard:** Migrate/expire old handoff records safely; preserve existing pending user input during upgrade. No background retailer crawling.

### PR-10 — iOS share extension correctness and build evidence

**Priority:** P0 for iOS. **Depends on:** PR-04/PR-07/PR-08. **Requirements:** R2/R7/R8. **Scope:** `mobile/ios/FashionVaultShare/`, App Group handoff/bridge, host target, existing iOS tests.

**Work:**
- [ ] Replace the undocumented text activation key with `NSExtensionActivationSupportsText` Boolean. Retain valid URL/image count declarations and narrow supported types.
- [ ] Preserve the requested action across extension→host. Prefer a single Save item action for this MVP; remove or disable Try now while generation is unavailable. Do not present two actions that become identical silently.
- [ ] Apply the same durable handoff/ack semantics as Android using existing App Group storage. The extension may stage input and ask the user to open the host app; do not depend on unsupported forced host-launch APIs.
- [ ] Align validated MIME/format metadata, file lifetime, confinement, expiry and cancellation. No full React Native runtime or provider calls inside the extension.
- [ ] Build/embed/sign the host and extension with matching entitlements and provisioning. Run the existing native tests on macOS and actual share-sheet scenarios on iOS.

**Acceptance:** Text-only shopping shares show the extension; URL/image shares reach a durable host import after activation/login without data loss. Exercise PR-08 confirmation through its HTTP contract here; PR-11 supplies the interactive confirmation UI. Both binaries install and share the correct container. A source-only Windows pass cannot close the ticket.

**Proof:** Xcode build/test logs, signed archive validation and device recordings for text/URL/image, canceled extension, process termination and expired handoff. Confirm no extension-unsafe API warnings.

**Rollback/guard:** Keep previous handoff records readable within their TTL; do not change App Group identifiers without a migration. Missing Apple credentials/Mac evidence is EXTERNALLY-BLOCKED, not PASS.

### PR-11 — Visual item library, collections and save confirmation

**Priority:** P0. **Depends on:** PR-05/PR-06/PR-08/PR-09/PR-10. **Requirements:** R2/R3. **Scope:** `VaultScreen.tsx`, `ShareImportScreen.tsx`, Home import entry, existing Vault/collection clients and navigation.

**Work:**
- [ ] Render private thumbnails with selection state, category and accessible item labels. Browse opens item detail instead of a disabled text button. Implement edit/category correction/delete/source-link actions.
- [ ] Complete the import screen: retain import ID, show preview, confirm name/category and collection, save via PR-08, and navigate to the persisted item. Loading/error/unknown-category states must be distinct.
- [ ] Support collection create/rename/delete and membership add/remove using existing many-to-many backend semantics. Deleting a collection never deletes its items; removing membership never deletes an image.
- [ ] Use the bounded server queries from PR-06, not a fetch-all client intersection. Refresh after save/delete and on relevant foreground return; avoid stale filter responses replacing newer selections.
- [ ] Make screenshot/photo fallback discoverable when a retailer blocks metadata extraction. Preserve source link and user corrections.

**Acceptance:** From a normal fresh app, the user saves a top, bottom and footwear item into categories and a named collection, sees their actual thumbnails after restart, and edits membership. The library exposes their stable item IDs for the later Studio selector; a working Studio is not a prerequisite for this ticket.

**Proof:** Real share/photo→confirm→save→reopen scenario; empty/failed page, two overlapping filter loads, expired preview, offline retry, deletion conflict; TalkBack/VoiceOver and large text on the actual screens.

**Rollback/guard:** Reuse React Navigation and existing visual primitives; no design-system rewrite or gallery framework unless a measured need justifies it.

### PR-12 — Authorized persistent Studio and immutable outfit contract

**Priority:** P0. **Depends on:** PR-06. **Requirements:** R4/R5/R7. **Scope:** `studio/StudioSession.java`, existing Studio schema, `generation/RenderPlan.java`, new minimal Studio store/controller where the inventory confirms absence, mobile Studio DTO contract.

**Why:** Domain rules exist, but mobile routes are not evidence of corresponding server endpoints.

**Work:**
- [ ] Implement create/get/mutate for an owner-scoped Studio using the existing domain methods and tables. Freeze exact request/response/revision shapes in the API doc before client wiring. Do not implement natural-language commands.
- [ ] Bind one active profile and explicit slot→item references. Validate item owner, live media, category-slot compatibility, duplicates and full-body conflicts. TOP + BOTTOM + FOOTWEAR must coexist.
- [ ] Use transactional revision checks; stale clients get a conflict and current state, not last-write-wins data loss. Collection membership is only a discovery filter, not the identity of selected media.
- [ ] Define a versioned immutable generation snapshot with profile/image revision, ordered items/asset revisions/categories/slots and requested mode. Reuse `RenderPlan` where suitable; remove incompatible duplicate item-order representations rather than sorting categories separately from IDs.
- [ ] Define how deleted/replaced inputs affect drafts versus submitted jobs. Replacing a profile in a draft is explicit; frozen jobs are not reinterpreted.

**Acceptance:** Create/reload/edit a three-item outfit through HTTP with no test-injected session ID. Another owner cannot inspect or mutate it. Stale updates do not overwrite newer state.

**Proof:** Real PostgreSQL API tests for each mutation, duplicate/conflicting slot, missing media, cross-owner IDs and concurrent revisions; serialized snapshot round-trip retains order and category association.

**Rollback/guard:** Additive schema changes only; do not edit V006 or earlier migrations. No JSON-only unvalidated state blob replacing domain invariants.

### PR-13 — Real multi-item Studio UI

**Priority:** P0. **Depends on:** PR-11/PR-12. **Requirements:** R4/R5. **Scope:** `StudioScreen.tsx`, `features/studio/studioApi.ts`, Vault selection mode, RootNavigator.

**Work:**
- [ ] Create/load the persisted session from normal navigation; remove readiness booleans based solely on route parameters. Show the actual active profile, occupied slots and selected item images.
- [ ] Let users select top, bottom and footwear from categories or collections in one session; persist each accepted change, replace/remove items and resolve compatibility conflicts explicitly.
- [ ] Handle stale revisions, deleted inputs, network failures and foreground refresh without losing the local user's intent or silently overwriting the server. Persist only identifiers/draft state, not signed URLs.
- [ ] Generate acts on the visible snapshot. While provider-disabled, it explains why generation cannot run and preserves the complete outfit; it must not open the historical one-photo picker.
- [ ] Modify Look creates/loads a real draft from provenance once PR-16 connects history; until then do not expose a fake loading sentence as implemented behavior.

**Acceptance:** Fresh app navigation reaches a valid session without injected props. Three selected items remain after app restart. The displayed selection and server snapshot agree exactly.

**Proof:** Device screen recording and API assertions for add/replace/remove, collection selection, conflict, restart, profile change and disabled generation. Component tests must not invent an otherwise unreachable session.

**Rollback/guard:** Migrate route types and all callers together. Delete the legacy route-only readiness path; do not keep two competing Studio state stores.

### PR-14 — Correct Look publication and provenance

**Priority:** P0. **Depends on:** PR-02/PR-12. **Requirements:** R5/R6/R7. **Scope:** `GenerationWorkProcessor.java`, `look/JdbcLookStore.java`, Look/LookItem records, existing publication concurrency tests.

**Why:** F07. A correct SQL type is necessary but not sufficient: the Look must record what was actually generated.

**Work:**
- [ ] Reproduce the real worker→JDBC publication with the test adapter and a complete snapshot, not a manually fabricated Look whose renderPlan is null.
- [ ] Serialize the actual snapshot into the JSON column; persist avatar/profile provenance and constituent Look items. Do not quote `STANDARD`, null the column or drop constraints to hide the error.
- [ ] Make private media promotion, terminal job/attempt state, Look insertion and completion event converge under retry. A failed DB publication leaves recoverable cleanup state; it must not expose an orphan success image.
- [ ] Preserve cancellation/deletion arbitration. A stale worker cannot publish after losing ownership or after the account/input deletion barrier wins.

**Acceptance:** A valid deterministic candidate publishes exactly one readable Look with correct profile and all selected items. Duplicate completion replays it. Cancellation wins or completion wins coherently; no half-success.

**Proof:** Actual `GenerationWorkProcessor` + `JdbcGenerationStore` + `JdbcLookStore`, real Postgres and object-store protocol; fail immediately before/after promotion and DB insert; existing `GenerationPublicationConcurrencyTest` plus full snapshot fixtures.

**Rollback/guard:** Preserve old Looks; characterize/backfill missing historical provenance explicitly. Never infer a missing outfit from the user's current draft.

### PR-15 — Outfit submission and durable bounded execution

**Priority:** P0. **Depends on:** PR-12/PR-14. **Requirements:** R4/R5/R8. **Scope:** `GenerationRequest.java`, `GenerationCreationService.java`, generation stores/outbox/worker, media resolver and mobile generation API contract.

**Work:**
- [ ] Replace the MVP's manual `productUploadId`-only submission with the PR-12 authorized snapshot/session-revision contract. Migrate Quick Try to save/select an item and use the same flow, or remove that shortcut. Preserve polling/resource identity; no second generation subsystem.
- [ ] Atomically establish accepted job, immutable inputs, outbox and reservation ownership. Compensate only work that did not become durable. A later reservation-commit exception must not delete an already-accepted job's input; concurrent same-key losers must release their own resources.
- [ ] For provider-native multi-item capability, pass the entire frozen ordered set once. Do not keep the broken sequential path as an implicit fallback. Preserve its data safely but disable/remove it from execution until a selected provider demonstrably requires it.
- [ ] If PR-24 requires sequential rendering, implement it in that explicit ticket: step-specific product inputs, previous accepted output as next subject, retained private intermediate, per-step limits, prior-item preservation checks and only one final Look. Never send all items repeatedly to a one-item adapter.
- [ ] Prove real outbox relay, SQS receive/heartbeat, recovery scanner and retry limits are enabled in their intended process roles. Reconcile unknown submission before retry; bound total time and attempts; cancel safely.

**Acceptance:** Three selected item IDs/revisions reach the test adapter exactly as frozen. Response loss/restart/redelivery does not duplicate jobs or delete accepted inputs. Queued work cannot remain silently stranded with a healthy API.

**Proof:** Real HTTP→Postgres/outbox→SQS-compatible queue→worker→private storage→Look smoke, external provider replaced only by the deterministic adapter. Controlled same-key concurrency, reservation exception after commit, process kill after enqueue/submit, repeated delivery, deadline and cancellation cases.

**Rollback/guard:** Drain or safely fail old incompatible jobs before removing old input paths. Do not claim exactly-once paid provider execution. One process role may be enough in dev; production worker ownership must be explicit.

### PR-16 — Restart-safe status, Looks and recovery UX

**Priority:** P0. **Depends on:** PR-13/PR-15. **Requirements:** R5/R6. **Scope:** generation `activeGeneration`/polling/create flow, `GenerateScreen.tsx`, `LooksScreen.tsx`, `LookScreen.tsx`, navigation/deep links.

**Work:**
- [ ] Persist stable operation/job identity scoped to the signed-in user; reconcile after ambiguous POST or poll failure. Restart resumes the same job, not a fresh upload/key.
- [ ] Poll with cancellation, foreground/background behavior, backoff and a user-visible retry path. Backend deadlines terminate work; mobile never busy-loops indefinitely or sets state after unmount.
- [ ] Show durable queued/running/failed/completed states and explicit cancellation. A retry of a terminal failure is a new intentional logical request; a transport retry is not.
- [ ] Render bounded Look history/detail with signed image refresh/error handling, open/delete, source-item provenance and Modify→Studio. Fix one-shot action guards so repeated Regenerate either works or is visibly unavailable.
- [ ] Make deep links wait for auth/navigation and reauthorize Look ownership. Push is only a hint; a missed or duplicate notification cannot lose or duplicate a Look. Remove public share-link controls from MVP unless fully supported; native user-initiated image sharing may be added only with temporary-file cleanup and explicit disclosure.

**Acceptance:** Kill/reopen during every generation phase and recover the same outcome. A completed Look survives logout/login for its owner only. Missing/deleted items cannot silently reappear during Modify.

**Proof:** Device lifecycle scenarios; 401/expired image/temporary GET failure; two intentional regenerations; duplicate and later repeated Look links; no push permission; another user's job/Look ID.

**Rollback/guard:** Persist IDs and revision metadata, never credentials or signed URLs. Keep old pending-operation records readable long enough to reconcile them.

### PR-17 — Durable privacy, deletion and retention

**Priority:** P0 before external users. **Depends on:** PR-05–PR-16. **Requirements:** R7. **Scope:** `user/JdbcIdentityProvisioning.java`, `platform/web/MeController.java`, `privacy/DefaultAccountDeletionWorkflow.java`, `DefaultPrivacyService.java`, `AccountDeletionWorkflowStore.java`, V021 schema, media-purge implementations, mobile settings.

**Source evidence:** `JdbcIdentityProvisioning.java:94-115` purges before marking the user deleted. `S3ObjectStore.java:461-500` suppresses per-object delete failures. The existing durable privacy workflow/schema is not the mounted deletion path; `docs/runbooks/MONTHLY_OPERATIONS.md` also records that gap. The test class named `AccountDeletionOrchestratorTest` is not a production orchestrator implementation.

**Work:**
- [ ] Connect the existing durable deletion design to real persistence and the real account API; do not add a parallel third workflow. In one DB transaction establish deletion intent and block new owner work before external cleanup starts.
- [ ] Implement resumable, idempotent checkpoints for avatar/Vault/Look/import/temp media, jobs/attempts, profile/item/collection data, push tokens, identity revocation/deletion and permitted minimal operational records. Capture failures instead of acknowledging unperformed work.
- [ ] Reconcile concurrent uploads, import publication and Look publication against the deletion barrier. Stale workers cannot recreate media or data after cleanup. Delete requests survive process restart and dependency outage.
- [ ] Repair item deletion/reference races: decide logical deletion and exclude new references transactionally, then perform retryable media cleanup. Do not delete the object before a later reference check can return IN_USE. Apply equivalent reconciliation to import-delete/publication races.
- [ ] Implement retention for abandoned imports and intermediate assets, not only the TEMP bucket. Inventory bucket versions, backups and signed capability expiry; distinguish immediate access revocation from eventual physical erasure.
- [ ] Add real settings: logout, delete profile, delete account with reauthentication/confirmation and durable status, consent view/withdrawal, privacy link and export request where promised. Clear user-scoped caches/staged files. Provide a working authenticated web account-deletion path for Play; never a public delete-by-email form.
- [ ] Replace deferred privacy docs with actual data classes, purposes, retention, subprocessors, no-training terms where contractually true, backup treatment and support procedure. Do not claim legal compliance without jurisdiction review.

**Acceptance:** Deletion disables new work immediately, eventually removes all in-scope live data, exposes honest pending/failed status, resumes after restart, and never reports completion while known object deletion failed. Another user's data survives. Export is owner-only and expires.

**Proof:** Real JDBC + storage fault injection, deny one object deletion then restart/resume, concurrent new write/publication, expired token, restored backup tombstone replay, app cache inspection, Firebase test-account revocation. Existing fake-store workflow tests alone cannot close this ticket.

**Rollback/guard:** Never re-enable a deleting account on application rollback. Preserve durable tombstones/cleanup records until completed and within declared policy; no permanent retry ledger containing unnecessary personal data.

### PR-18 — Trust-boundary hardening without another framework

**Priority:** P0 before external users. **Depends on:** PR-06–PR-17. **Requirements:** R2/R5/R7. **Scope:** existing upload/claim paths, safe importer HTTP client, authorization controllers/stores, media transfer, native input confinement and logging.

**Work:**
- [ ] Audit every real claim path against the verified-media policy introduced in PR-05/PR-06. Tests cover skipped completion, stale object version, MIME spoof, malformed/truncated body, excessive decoded pixels, EXIF/private metadata and decompression/resource exhaustion.
- [ ] Keep the existing DNS/IP-pinned SSRF defenses. Add total wall-clock bounds across DNS, connection, TLS, redirects, headers and body; validate redirected page/image destinations and IPv4/IPv6/private/metadata forms. Use egress restrictions as a second layer, not a replacement for validation.
- [ ] Prove owner checks for direct IDs, nested collection membership, source references, uploaded media, Studio/session, generation cancellation/retry, Looks and read capabilities. Enumerating another user's UUID must never leak metadata.
- [ ] Bound URL length, redirect count, upload bytes/pixels, input count, request rate, collection/item list sizes, worker concurrency and response sizes using existing policy/config, with explicit errors.
- [ ] Exercise redaction with seeded non-secret marker values through failures, crash reports, logs/traces and any analytics. Extend the narrow repository secret scan using an established scanner; no claim that two regexes detect all credentials.
- [ ] Inventory dependencies/native SDKs and production-only debug settings; patch exploitable/high-impact known issues with compatibility tests. A version-only automated score is not a security review.

**Acceptance:** No bypass from a weaker item/import/generation path into persistent media; cross-owner operations fail; unsafe URLs never connect to a prohibited destination; bounded failures leave retryable or terminal state deliberately.

**Proof:** Targeted hostile-input corpus plus actual HTTP/DNS redirect harness; object-store protocol integration; two-user black-box authorization suite; secret-marker telemetry scan. Record scope and unresolved risks, not “OWASP compliant” by assertion.

**Rollback/guard:** Never weaken validation to restore availability. Keep a source import kill switch and safe manual-photo fallback.

### PR-19 — Durable admission, quotas and cost safety

**Priority:** P0 before real inference. **Depends on:** PR-15/PR-17. **Requirements:** R5/R8. **Scope:** existing admission/JDBC reservation/circuit-breaker components, `GenerationMetrics`, worker costing and provider configuration.

**Source evidence:** Generation audit found a global breaker based on process-local metrics, and a fixed worker cost estimate. Metrics are reporting, not an authoritative spend ledger.

**Work:**
- [ ] Use persisted reservation/attempt/cost records to enforce per-user and global limits across replicas/restarts. Reuse existing schema/admission code; no separate billing service.
- [ ] Reserve worst-case authorized execution cost/concurrency atomically before acceptance, including allowed retries and any sequential steps. Settle/release once on terminal outcome or safe reconciliation; uncertain charged submissions retain appropriate liability.
- [ ] Remove universal fabricated costs. Before model pricing exists, record cost as unknown/test-only and leave public generation disabled. After PR-24, distinguish quoted, reserved, estimated and settled amounts with currency and pricing revision.
- [ ] Provide a global inference kill switch that blocks new work without destroying Vault/Studio access; define running-job behavior. New work must not pile up forever during a provider outage.
- [ ] Launch default: free capped access, proposed maximum five logical generations per user/day plus a user concurrency limit of one and an operator-set global cap. Validate limits against measured model cost before enabling inference; client/device/IP signals supplement authenticated limits, never replace them.
- [ ] Decide attestation enforcement for high-cost endpoints using Play Integrity/App Attest evidence and failure UX. Do not claim verification because flags or interfaces exist. Never trust arbitrary client device IDs as an abuse boundary.

**Acceptance:** Two replicas and a restart cannot reset the budget or double-reserve a replay. Invalid/unavailable requests consume nothing. A breached cap stops new inference predictably; tests do not invent real-provider pricing.

**Proof:** Controlled concurrent requests against real PostgreSQL; restart ledger check; UTC/day-boundary test; duplicate terminal handling; timeout-after-submit; budget exhaustion and operator kill-switch smoke.

**Rollback/guard:** Preserve reservation history and conservative cap behavior through rollback. No purchases, subscription infrastructure or credit marketplace in this PR.

### PR-20 — Runnable staging topology and secure deployment

**Priority:** P0. **Depends on:** PR-02/PR-15/PR-17/PR-18/PR-19. **Requirements:** R8. **Scope:** `infra/terraform/ecs.tf`, `alb.tf`, `iam.tf`, `database.tf`, `storage.tf`, existing queue definitions, backend runtime-role configuration and container build.

**Work:**
- [ ] Move secret references into ECS `secrets`; keep non-secret values in `environment`. Test generated task-definition JSON and actual secret injection without printing values.
- [ ] Build one immutable non-root backend image with health/readiness, graceful shutdown and required model assets. Make API and worker roles explicit: API does not accidentally poll queues with send-only IAM; worker runs consumer/relay/recovery deliberately.
- [ ] Require public HTTPS and redirect HTTP; validate origin/certificate/DNS. Keep DB, queue administration and internal observability private; enforce least-privilege S3/SQS/Secrets Manager access.
- [ ] Remove unused provisioned Valkey from the intended MVP topology after checking deployed ownership/state; current runtime uses SQS and has no V1 cache consumer. Do not apply destructive infra changes without a reviewed plan and approval.
- [ ] Set managed Postgres backup/PITR, object privacy/encryption/lifecycle and environment isolation. Define explicit runtime defaults: no accidental provider, no production dev auth, no test adapter, bounded queues/connections/concurrency.
- [ ] Produce a reviewed Terraform plan and deploy a dedicated staging environment with budget approval. Keep one modular application, not a replatforming project. Run a provider-disabled smoke and an isolated test-adapter integration environment separately.

**Acceptance:** The exact container starts with real secrets, passes DB/storage/role readiness, serves only protected public APIs over HTTPS and processes an isolated test job through the actual queue. A health UP response alone is insufficient.

**Proof:** Terraform fmt/validate + plan; ECS task registration/startup; HTTPS/HTTP redirect check; API-worker permission negatives; DB migration from previous schema; graceful worker termination/redelivery. Record image digest and effective non-secret configuration.

**Rollback/guard:** Promote immutable images; roll back code without undoing additive schema. Keep operator approval for AWS apply/costs; missing credentials are an explicit external gate, not permission to fabricate deployment evidence.

### PR-21 — CI and signed release artifacts that enforce the product

**Priority:** P0. **Depends on:** PR-09/PR-10/PR-16/PR-18/PR-20. **Requirements:** R8. **Scope:** existing `.github/workflows/`, build/release configuration, native test targets, contract fixtures and image build pipeline.

**Work:**
- [ ] Keep backend `verify`, mobile typecheck/lint/tests and formatting checks, but run contract tests through actual mounted wiring. Relevant Postgres/storage/queue tests must not silently skip when infrastructure is absent.
- [ ] Add the missing container build/scan/publish, infrastructure validation, migration compatibility and staging smoke stages. Test the same image digest that is promoted; production requires approval.
- [ ] Treat Android unit/debug build, Android instrumented journey, iOS unit/build and signed release validation as different checks. Require macOS evidence for iOS changes; document required branch protections, not just workflow comments.
- [ ] Replace debug release signing with proper protected Android upload signing and Play App Signing. Build AAB and test the installed release variant; check release network security and native 16 KB alignment. Configure iOS distribution signing, extension provisioning and privacy manifests.
- [ ] Pin action/toolchain/image inputs sufficiently for repeatable builds, use least-privilege job tokens/OIDC and protected release environments, scan dependencies/secrets/container output, and store provenance/SBOM plus native crash symbols privately.
- [ ] Add one minimal real-device automation lane for the core save/compose flow; do not relabel a fetch-mocked Jest test as E2E. Keep fast unit tests for feedback.

**Acceptance:** A deliberately disconnected API client, missing worker bean, wrong wire shape or missing native share target fails a relevant gate. Release artifacts use production origin/signing, exclude test adapters and are reproducibly identified.

**Proof:** Clean runner executions and intentionally broken-boundary test; signed AAB/IPA archive inspection; install/launch/share smoke; restore last green image. Required checks cannot be bypassed by a focused-only test report.

**Rollback/guard:** Do not put signing keys into source/history/artifacts. Preserve a tested rollback image and migration-compatible previous app release.

### PR-22 — Operational telemetry and restore drill

**Priority:** P0 before release approval. **Depends on:** PR-17/PR-19/PR-20/PR-21. **Requirements:** R7/R8. **Scope:** existing Micrometer/OTel configuration, `infra/alerts/`, runbooks, backup/retention configuration.

**Work:**
- [ ] Prove actual metric/span export from application to the configured backend. An ADOT sidecar plus a custom endpoint property is not evidence that the Java application exports anything.
- [ ] Instrument only the MVP operational signals: authenticated API error/latency, import outcome, queue age, active/stuck jobs, generation outcome, unknown submission, deletion backlog, storage failures, quota/spend state and worker availability. Use bounded labels; no per-user/image URL cardinality.
- [ ] Trigger and receive one real alert for broken import/worker/deletion and one for budget/kill switch. Define an owner, severity, action and recovery verification for each. Do not alert on unevaluated semantic-quality scores as though meaningful.
- [ ] Restore an isolated staging DB from backup/PITR and verify referenced private media; restore the application and run the core read/save flow. Reapply deletion tombstones/retention so backup recovery cannot resurrect deleted accounts/media.
- [ ] Proposed initial recovery objectives: RPO at most 24 hours and RTO at most 4 hours for the capped MVP. Measure actual results and explicitly approve stricter targets if needed; these are planning objectives, not existing guarantees.
- [ ] Write short executable runbooks for deploy/rollback, auth key outage, queue redrive, provider disabled/outage, deletion failure, compromised secret rotation and restore. Remove obsolete slice-specific instructions that contradict the real environment.

**Acceptance:** Operators can see and stop stuck/costly work, detect failed deletion and restore service without returning deleted data to users. Signals reach an actual destination and alerts reach an actual responder.

**Proof:** Timestamped fault drill, redacted metric/span sample and alert receipt; backup ID, isolated restore timings, data integrity checks and deletion replay. A Terraform backup flag or written runbook does not close the ticket.

**Rollback/guard:** Never restore over the user's current database for a test. Do not disable redaction to make telemetry easier to debug.

### PR-23 — Provider-ready acceptance on real devices

**Priority:** Gate A. **Depends on:** PR-01–PR-22. **Requirements:** R1–R8. **Scope:** test evidence/fixtures, small fixes discovered through these scenarios, no model selection.

**Work:**
- [ ] Complete the entire fresh-install path on Android and iOS: sign in → profile → shopping share/photo fallback → confirmation → categorized item/custom collection → select top+bottom+footwear → persistent Studio → honest unavailable Generate.
- [ ] In a separate isolated test build/environment, run the same path through the deterministic provider adapter, real API/DB/queue/storage and one private Look; label synthetic evidence. Normal provider-unconfigured builds must never show that synthetic Look as generated.
- [ ] Test Myntra, AJIO, Amazon, Chrome/Safari and Gallery/Photos as the initial source list. For each record app/OS version, actual payload class, cold/warm/logged-out behavior, automatic-resolution outcome, fallback outcome and final saved-item identity. Add Instagram only if actually advertised/supported; do not assume its payload matches a retailer.
- [ ] Require every declared supported source to reach a saved item either by valid resolution or the explicit photo fallback. Record automatic extraction and fallback-assisted save rates separately; do not claim “90% imports” from a denominator that excludes all failures.
- [ ] Exercise airplane mode, response loss, background/kill/restart, duplicate share, expired image capability, account switch/deletion, limited photo access and large text/screen readers. Test a representative low-memory Android device and supported older iOS version as well as current devices.
- [ ] File only reproducible failures with source/steps/evidence; fix in the owning ticket area and rerun the affected full journey. Add regression tests only where they defend the failure, not screenshot noise.

**Acceptance:** All R1–R8 provider-independent flows have real device/staging evidence. No unresolved P0/P1 issue. Provider remains disabled in ordinary builds, with no misleading image-quality claims. Record Gate A approval separately for each platform.

**Proof:** Redacted videos/screenshots, app build/digest, environment, source matrix, backend records, precise failed/passed/blocked counts. No store/public-AI launch claim at this gate.

**Rollback/guard:** Never delete or reuse real personal photos as public fixtures without consent. A failed source can be labeled fallback-only, but cannot be advertised as automatic import.

### PR-24 — Selected-provider integration (externally blocked now)

**Priority:** Gate B prerequisite. **Depends on:** PR-23 and explicit provider approval. **Requirements:** R4/R5. **Scope:** existing provider adapter boundary and conditional configuration; no automatic vendor selection by an implementation agent.

**Entry evidence required from owner:** Chosen provider/model/version; authorized API access; region and subprocessors; retention/deletion/no-training terms; supported input count and categories including footwear; timeout/replay/cancellation semantics; output transfer method; pricing/currency/budget; permitted-use and safety policy. Absence of any required evidence blocks enabling real inference, not the already-complete provider-independent work.

**Work:**
- [ ] Implement one real adapter using primary provider documentation and captured contract fixtures. Map internal categories explicitly; report unsupported capabilities before admission. Never change domain categories merely to match one vendor.
- [ ] Prefer native multi-image generation if it actually preserves all selected items. If sequential composition is necessary, implement and verify the explicit step semantics from PR-15, including retention and retry of intermediates and all prior garments.
- [ ] Bind immutable model/pricing/policy revisions to attempts. Implement bounded retries, rate limits, uncertain-submit reconciliation, private transfer and immediate private output ingestion; enforce SSRF/size controls on output URLs too.
- [ ] Validate tops+bottoms+footwear together before advertising the feature. An attractive single-shirt demo cannot satisfy R4/R5. Measure true cost per accepted combined outfit.
- [ ] Keep test adapter inaccessible in production and provider kill switch effective. Do not route failures into an unapproved second vendor.

**Acceptance:** Real selected-provider requests produce one coherent combined outfit using the owner's approved profile/items; all failure classes reconcile without unbounded cost or duplicate public results. Actual output acceptance belongs to PR-25.

**Proof:** Provider sandbox/live controlled calls, redacted contract/timeout/replay evidence and private transfer audit. Store IDs/costs/timings, not raw secrets or personal photos in logs. Gate B remains closed until PR-25 and PR-26.

**Rollback/guard:** Disable new inference and reconcile in-flight work. Never claim a placeholder adapter is a completed implementation of this ticket.

### PR-25 — Real quality, AI safety and processor privacy

**Priority:** P0 before public generation. **Depends on:** PR-24. **Requirements:** R5/R7. **Scope:** existing quality boundary and `docs/AI_EVALUATION.md`, consent/settings, Look feedback/reporting, safety enforcement and provider terms.

**Work:**
- [ ] Separate technical validity, safety and subjective fidelity. Retain full image validation; remove perfect-score heuristics and misleading semantic PASS labels. Face count or whole-image histogram similarity does not establish identity/garment preservation.
- [ ] Create a consented representative evaluation set for the actual launch categories/profile diversity. Proposed initial gate: at least 100 independent combined-outfit cases, with at least 30 including footwear and meaningful variation in body types, skin tones, lighting and garment styles; record overlap and category denominators. Include wrong person, missing shoes, wrong pattern/color, missing earlier garment, malformed/blank output and unsafe content negatives.
- [ ] Use a written human-review rubric with explicit acceptable/unacceptable examples and independent review of a subset. Proposed floor: at least 90% acceptable outputs overall and in every advertised category stratum, zero severe identity/safety incidents in the evaluated set, technical success at least 95% across valid admitted cases. Report uncertainty and sample limitations; these are minimum project gates, not guarantees or industry statistics.
- [ ] Calibrate any automatic semantic evaluator against human labels, reporting false-accept and false-reject behavior. If no reliable evaluator exists, do not fabricate one; define a bounded human-reviewed beta and keep general public generation blocked until the approved safety/quality policy is defensible.
- [ ] Implement in-app offensive-output reporting, a real monitored moderation/support workflow and minimal evidence retention. Publish that generated previews are not fit/size guarantees. Do not add social feeds or collect biometric templates to satisfy a quality rubric.
- [ ] Obtain explicit processor-specific third-party AI consent before any profile transfer, with withdrawal. Complete retention/deletion/no-training and country/age-policy review; content prevention and reporting must meet current store policies. Unknown legal/model terms remain release blockers.
- [ ] Measure latency and cost at representative concurrency. Define a finite job deadline and honest UI expectations from evidence; do not copy the historical 30-second target as a claim of achieved performance. Any latency target change requires owner approval before launch, not silent relaxation after a failed test.

**Acceptance:** Human and automated evidence supports the advertised combined-outfit capability; safety reports reach an operator; users know where their images go; costs/latency are measured. No public claim is backed only by structural-image checks or synthetic fixtures.

**Proof:** Versioned evaluation manifest/results, human rubric/review records, adversarial cases, consent/revocation/deletion live checks, reporting drill and load/cost report. Do not commit personal evaluation images to this repository.

**Rollback/guard:** Version model/prompt/evaluator together; canary new versions and retain approved rollback configuration. A quality/safety regression disables inference without disabling the user's library.

### PR-26 — Store approval and staged public rollout

**Priority:** Final Gate B. **Depends on:** PR-24/PR-25 and all earlier public-release gates. **Requirements:** R1–R8. **Scope:** store configuration/assets, release evidence, published policies, rollout controls and support ownership.

**Work:**
- [ ] Re-read the authoritative platform sources in section 9 immediately before submission; verify target SDK/native 16 KB support, current Apple build SDK, privacy manifests/labels, Play Data safety/deletion URL, sign-in policy, consent, age rating, generated-content reporting and reviewer access.
- [ ] Submit fully functional signed builds with real HTTPS configuration and review instructions. Do not submit an advertised Generate feature backed by an unconfigured provider, demo fixture or hidden test-only switch.
- [ ] Complete internal/closed testing with supported-source matrix and explicit crash/network/privacy evidence. Proposed launch floor: at least 99.5% crash-free sessions over at least 1,000 beta sessions; report device distribution and uncertainty. No open P0/P1 defects; all severe privacy/security findings closed with evidence.
- [ ] Reconfirm cost cap, alert responder, backup restore, deletion completion and rollback. Proposed normal API p95 budget is 500 ms under the documented expected launch load, excluding uploads/inference; name endpoint mix/concurrency/dataset and test environment, not just an average.
- [ ] Release gradually through actual store-supported staged/phased controls, with a server-side provider kill switch. Inspect crash/error rates, queue age, quality complaints, spend and deletion backlog before expanding. Small early cohorts need incident review, not statistically meaningless percentage claims.
- [ ] Assign named human owners for incidents, abuse reports, privacy/deletion requests and store policy changes. Capture the actual release decision with build/model/config identifiers.

**Acceptance:** Both required platforms have signed, reviewed, runtime-verified release artifacts and independently approved rollout status. The app delivers the requested multi-item-generation and shopping-save journeys. All external blockers are resolved, not relabeled deferred.

**Proof:** Store submission/approval records, staged release metrics, release manifest, rollback drill, current compliance links, beta evidence and signed release checklist. If the owner approves platform-first release, record the narrowed release scope explicitly without calling the other platform complete.

**Rollback/guard:** Stop rollout and disable new inference on a severe leak, identity/safety incident, unexplained spend, authentication outage or stuck deletion. Preserve data/history and reconcile in-flight jobs. Do not wipe user collections to recover a release.

## 7. Release gates and requirement coverage

### Gate A — provider-ready internal candidate

- [ ] R1: Real session/photo/profile on both platforms — PR-03/PR-04/PR-05.
- [ ] R2: Real shopping share, fallback and confirmed persistent item — PR-06–PR-11.
- [ ] R3: Image library and custom collection editing — PR-06/PR-11.
- [ ] R4: Persistent top+bottom+footwear outfit using active profile — PR-12/PR-13.
- [ ] R5: Honest no-provider behavior plus isolated real infrastructure orchestration — PR-02/PR-14/PR-15/PR-16.
- [ ] R6: Private history/reopen/modify/delete using synthetic test-only results in isolated environment — PR-14/PR-16.
- [ ] R7: Real ownership/consent/deletion/retention — PR-03/PR-05/PR-17/PR-18.
- [ ] R8: Deploy, CI, telemetry and restore evidence — PR-19–PR-23.

**Gate A explicitly does not prove image synthesis, fidelity, real-provider latency, real-provider price, store approval or public launch.** It is useful engineering completion while the model remains undecided.

### Gate B — public MVP

- [ ] Provider chosen and explicitly approved; real adapter validated — PR-24.
- [ ] Actual combined-outfit quality/safety and consent/retention verified — PR-25.
- [ ] Live budgets, limits, outage/unknown-submit reconciliation verified — PR-19/PR-24/PR-25.
- [ ] Source-app compatibility and core device workflows rerun using the release build — PR-23/PR-26.
- [ ] Signed store artifacts, disclosures, review access and approval complete — PR-21/PR-26.
- [ ] Backup/restore, deletion, incident response and rollout approved — PR-17/PR-22/PR-26.

**No-go conditions:** Unconfigured model; only single-item capability when combined outfit is advertised; fake/structural-only semantic PASS; login/photo/import-save dead ends; cross-owner exposure; known incomplete deletion reported complete; duplicate uncertain paid submissions; unbounded cost; unverified release signing; missing real iOS evidence for an iOS launch; credentials/legal/store approvals still absent.

### Definition of done for every PR

- [ ] Request and contract are clear; no unapproved scope reduction.
- [ ] Existing pattern reused, or departure explained with evidence.
- [ ] Failure reproduced or explicitly identified as an unexecuted risk before repair.
- [ ] Real application callers migrated; obsolete fake defaults/paths removed.
- [ ] Behavioral tests cover the new observable contract and a plausible failure.
- [ ] Actual affected surface exercised; device/cloud limitations stated.
- [ ] Relevant full validation gates run after the final source edit, with counts and skips.
- [ ] Migration/rollback/data-retention consequences documented and tested where applicable.
- [ ] Canonical documentation updated to delivered behavior; no “implemented” claim from test naming alone.
- [ ] Independent review of cumulative diff and evidence; no unresolved P0/P1 issue in that scope.

## 8. Commands, observed evidence and test strategy

### 8.1 Executed during this audit

These commands were observation only; product source was not repaired. Successful commands exited 0.

- **Mobile:** working directory `mobile/`; `npm run test:ci -- --silent` — **17 suites, 243 tests passed**, no skipped tests reported, 54.046 seconds Jest time. This includes mocked picker/API composition and is not native E2E evidence.
- **Backend core:** working directory `backend/`; `cmd.exe /c mvnw.cmd -Dtest=GenerationPublicationConcurrencyTest,GenerationHttpContractTest,OnnxCpuSemanticInspectorTest test` — **24 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. Real isolated PostgreSQL 18.6 containers applied all **23 migrations through V023**. Provider/media substitutions and focused scope limit what this proves.
- **Backend identity/deletion:** working directory `backend/`; `cmd.exe /c mvnw.cmd -Dtest=ExternalTokenVerificationTest,AccountDeletionOrchestratorTest,MeApiTest test` — **31 tests, 0 failures, 0 errors, 0 skipped; BUILD SUCCESS**. Some expectations describe the current unsafe deletion ordering; passing them is not proof of complete erasure.
- **Existing backend health:** HTTP read of `http://127.0.0.1:8081/actuator/health` returned `status: UP` with liveness/readiness groups. The first probe at 8080 returned 404; startup logs established the actual port before retry. No outage claim follows from that wrong-port probe.
- **Docker:** `docker info --format "{{.ServerVersion}}"` returned `29.6.1`.

Not executed here: full backend `verify`, native builds/signing/device journeys, live authenticated generation/import mutation, provider calls, AWS apply/deploy, complete security testing, real deletion or restore. The audit does not claim these passed. The existing backend was not restarted or reconfigured and user data/volumes were not reset.

### 8.2 Standard implementation gates

Run commands from the stated directory; Windows uses `cmd.exe /c` for Maven/Gradle wrappers in this tool environment. On Linux/macOS use the corresponding executable shell wrapper. Do not run two Maven suites concurrently against the same `target/` directory.

From `mobile/`:

```text
npm ci
npm run typecheck
npm run lint
npm run format:check
npm run test:ci
```

From `backend/`, with Docker available for mandatory integration cases:

```text
cmd.exe /c mvnw.cmd verify
cmd.exe /c mvnw.cmd -Dtest=ShareImportHttpTest,ShareImportIntegrationTest,JdbcShareImportStoreTest,ObjectStoreImportMediaAccessTest,VaultControllerTest,JdbcVaultStoreTest,CollectionsControllerTest,JdbcCollectionStoreTest test
cmd.exe /c mvnw.cmd -Dtest=SafeImportHttpClientTest,GenericOpenGraphResolverTest,ImageInspectorTest test
cmd.exe /c mvnw.cmd -Dtest=ObjectStoreConfigurationTest,UploadWorkflowTest,UploadsApiTest test
```

From `mobile/android/`:

```text
cmd.exe /c gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

That command is neither an installed-device share test nor a signed-release/AAB gate. PR-21 adds the actual release commands/configuration and device runner appropriate to the chosen signing environment.

On macOS, from `mobile/ios/`, resolve CocoaPods dependencies using the project's pinned Ruby/CocoaPods setup, then from the repository root discover an available destination before running native tests:

```text
xcodebuild -workspace mobile/ios/AIFashionVault.xcworkspace -scheme AIFashionVault -showdestinations
```

Use the returned destination ID for build/test; archive/distribution validation is separate. A workspace generated by CocoaPods must exist first. Do not invent a simulator UUID or present Windows plist parsing as an iOS runtime pass.

Infrastructure after dependencies are initialized in an isolated validation environment:

```text
terraform -chdir=infra/terraform fmt -check
terraform -chdir=infra/terraform validate
terraform -chdir=infra/alerts validate
```

Terraform validation is necessary, not sufficient: PR-20 must prove generated ECS JSON, task startup, secret injection, IAM roles and HTTPS. Apply requires explicit environment/cost approval.

### 8.3 High-value regression matrix

Use the repository's existing test runners. Add tests around observable behavior, not string searches over implementation code.

1. **Default composition:** actual mounted App requests configured absolute API URLs with current auth; null picker/default fake scanner cannot be substituted away.
2. **Upload authority:** valid synthetic image accepted through production wiring; skipped completion/wrong owner/changed object/malformed pixels rejected; scanner unavailable remains a retryable infrastructure failure without fake CLEAN.
3. **Import replay:** response lost after accepted image claim; original staging consumed; exact replay still returns the original import.
4. **Recovery replay:** response lost after image attach; retry uses the same binding or canonical GET and creates no extra item/media.
5. **Native handoff:** intent before navigation/login ready, process death after copy/before ack, duplicate delivery and expired grant; one saved item or explicit recoverable state.
6. **Outfit snapshot:** simultaneous draft mutation vs Generate; accepted job retains precisely the revision shown at submit; later changes cannot rewrite it.
7. **Publication:** genuine frozen snapshot through real worker/JDBC; valid JSON/provenance; one final Look; process kill before/after each persistence boundary.
8. **Provider semantics:** native multi-image set is complete; if sequential is selected later, next input is previous accepted output and only current step's item, with no intermediate publication.
9. **Budget conservation:** concurrent same-key acceptance, exception after durable insert, restart and duplicate terminal event; no lost accepted input, leaked reservation or overspend.
10. **Deletion race:** item gets referenced during delete; account deletion races upload/worker; storage denies one delete. No broken surviving reference, no resurrection, no false completed erasure.
11. **Identity rotation:** old/new signing keys in one running process; revoked account cannot re-provision after deletion.
12. **Deployment:** same release image in API/worker roles; intended beans present; real queue advances; metric/span reaches backend; failure alert reaches owner.

Concurrency checks require controlled barriers/fault points and real database semantics. Sequential repeat calls do not establish race safety. Record each failure schedule and final invariant. Do not assert that every hypothetical race was reproduced during this audit.

## 9. Current platform guidance and the 2027 bar

There is no single “2027 industry standard” that makes an app production-ready. Use published platform requirements, security baselines, measurable product acceptance and actual operations evidence. Sources below were consulted on **2026-09-12**; future policies may change. Revalidate immediately before submission.

- **Apple review/privacy/deletion/AI disclosure:** [App Review Guidelines](https://developer.apple.com/app-store/review/guidelines/) and [Offering account deletion](https://developer.apple.com/support/offering-account-deletion-in-your-app/). Account creation requires in-app deletion; deactivation alone is insufficient. Disclose and obtain permission before sharing personal data with third-party AI; accurately describe retention and privacy practices. Apply social/UGC moderation rules only if such functionality is actually introduced, not as justification for building a social platform.
- **Google Play user data/deletion:** [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311) and [Account deletion requirements](https://support.google.com/googleplay/android-developer/answer/13327111). Implement in-app deletion plus a functioning web deletion resource and accurate Data safety disclosures. Developer responsibility includes third-party SDK/processor behavior.
- **Generated-content safety:** [Google Play AI-generated content policy](https://support.google.com/googleplay/android-developer/answer/13985936). Image-to-image generation requires prevention of restricted content and an in-app offensive-content reporting/flagging mechanism. These obligations apply when actual generation launches; they cannot be satisfied by a dead report button.
- **Least-privilege image selection:** [Android Photo Picker](https://developer.android.com/training/data-storage/shared/photo-picker) and [Apple privacy-oriented Photos guidance](https://developer.apple.com/documentation/photokit/delivering-an-enhanced-privacy-experience-in-your-photos-app). Prefer selected-photo access rather than broad library permission.
- **iOS extension activation:** [NSExtensionActivationRule](https://developer.apple.com/documentation/bundleresources/information-property-list/nsextension/nsextensionattributes/nsextensionactivationrule) and [Apple extension common scenarios](https://developer.apple.com/library/archive/documentation/General/Conceptual/ExtensibilityPG/ExtensionScenarios.html). Text support uses the Boolean `NSExtensionActivationSupportsText`; image/web URL support uses documented count keys. Keep extension-safe APIs and App Group handoff.
- **Android target API:** [Target API requirements](https://developer.android.com/google/play/requirements/target-sdk). The accessed page specifies API 36 for new apps/updates from August 31, 2026. The repo already targets 36; the remaining gate is a compatible release binary and current Play Console acceptance, not merely changing a number.
- **Android native compatibility:** [16 KB page-size guidance](https://developer.android.com/guide/practices/page-sizes). The accessed page requires support for affected 64-bit apps and states updates lacking support cannot release from February 1, 2027. Check both ELF/RELRO alignment and APK packaging, then run on a 16 KB environment; React Native dependencies contain native binaries. Do not postpone device compatibility until the deadline.
- **Apple published 2027 SDK floor:** [September 9, 2026 submission announcement](https://developer.apple.com/news/?id=k1mtkt1k) states that from April 2027 uploads need the iOS/iPadOS 27 SDK or later. It does not specify a day in April and does not make the build SDK the minimum supported device OS. Select the appropriate stable submission toolchain at release, not an untested upgrade solely for novelty.
- **Apple SDK/privacy inventory:** [Privacy manifest files](https://developer.apple.com/documentation/bundleresources/privacy-manifest-files) and [Third-party SDK requirements](https://developer.apple.com/support/third-party-SDK-requirements/). Verify the actual main-app/extension/native dependency manifests and required-reason API declarations.
- **Security baselines:** [OWASP MASVS](https://mas.owasp.org/MASVS/), [ASVS](https://owasp.org/www-project-application-security-verification-standard/), [API Security Top 10](https://owasp.org/API-Security/editions/2023/en/0x11-t10/) and [SSRF Prevention Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Server_Side_Request_Forgery_Prevention_Cheat_Sheet.html). Use them to derive specific tests for storage/auth/network/platform/privacy and third-party input boundaries, not a compliance badge.
- **Firebase server verification:** [Verify ID tokens](https://firebase.google.com/docs/auth/admin/verify-id-tokens) and [Manage sessions](https://firebase.google.com/docs/auth/admin/manage-sessions). Official Java API is `FirebaseAuth.getInstance().verifyIdToken(idToken)` with the revocation-check variant where required. Manual certificate verification must honor cache expiry/rotation; a permanent JWKS cache is insufficient.
- **ECS secret injection:** [Secrets Manager environment injection](https://docs.aws.amazon.com/AmazonECS/latest/developerguide/secrets-envvar-secrets-manager.html). Use container `secrets` entries with `name`/`valueFrom`; rotating a secret requires new tasks to receive it. This directly grounds F11.

Store policy is not legal advice. Before public launch, determine actual target countries, age eligibility, photo consent/rights, processor agreements, data residency and deletion obligations with qualified review. Do not invent universal biometric, AI or privacy-law rules for 2027. User-initiated retailer sharing does not grant catalog-scraping rights or prove a partnership.

## 10. How to execute this backlog without repeating the previous failure

Start with **PR-01**, not the image provider, dashboard polish or more historical slices. Copy the ticket's scope plus sections 3/4 into the implementation request. Give the implementer its exact acceptance cases and forbid unrelated work. Require a reviewer to inspect the actual application path and evidence, not the implementer's narrative.

Suggested ticket closing record:

```text
Ticket: PR-xx
Commit/build/environment:
Behavior changed:
Contracts/callers migrated:
Regression reproduced before repair:
Commands + exit codes + pass/fail/skip counts:
Actual device/staging scenario and result:
Data migration and rollback result:
Known limitations / external blockers:
Reviewer decision:
```

Only mark a checkbox complete when its acceptance and proof are satisfied. An externally blocked release ticket stays unchecked. The model placeholder is intentional; the rest of the product must be real.
