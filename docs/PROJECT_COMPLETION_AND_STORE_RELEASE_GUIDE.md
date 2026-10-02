# AI Fashion Vault — Project Completion and Store Release Guide

**Prepared:** 2026-10-03  
**Source revision inspected:** `85e9da43689d3735ca6d8f6d53336d509b8e3db6`  
**Status:** Implementation guide, not a readiness certificate. No product code was changed, cloud infrastructure deployed, paid image generation invoked, or store submission performed to prepare this document.

## 1. Executive answer

The project should be completed by **finishing the existing user journeys and production wiring**, not by rebuilding the application or implementing another disconnected set of architectural components.

There is substantial reusable code: React Native navigation, Firebase authentication integration, native share handoff, owner-scoped backend APIs, PostgreSQL stores, Flyway migrations, S3 media boundaries, generation orchestration, InferHub integration, image-quality evaluation, and AWS infrastructure definitions. However, several core mobile paths still stop before a usable outcome. A passing unit suite or an earlier agent's completed ticket count does not make this application store-ready.

The required product loop is:

> Create account → consent → create My Model → share or photograph clothing → review and save to Vault → organize collections → compose an outfit → generate on My Model → save/reopen/share the Look → manage or delete personal data.

Every arrow must work in an installed release build against a publicly reachable production-equivalent backend. Public launch also requires safe failure behavior, realistic AI quality, controlled spending, legal disclosures, signed binaries, reviewer access, and operational recovery.

### What this guide covers

- Current implementation gaps, with repository evidence.
- All core application features, including combined top + bottom + footwear generation.
- Full-V1 capabilities that older MVP plans deferred: private export/share, notifications, source links, basic natural-language Studio and minimal product analytics.
- Separate implementation guidance for the longer-term PRD roadmap, without pretending that speculative social/AR/commerce features are already part of the launch-ready product.
- Staging and production deployment using the existing AWS direction.
- Android Google Play submission and an explicit iOS Apple App Store lane.
- Verification scenarios, rollout gates, rollback and post-launch ownership.

The earlier recorded direction was Android-first, with AWS deferred during local development. This guide prepares both store lanes because “app store” can mean either. It does not authorize infrastructure spending, account creation, provider changes, or publication. If both stores are the release target, **both platform gates must pass**; an Android release is not proof of iOS readiness.

### How to use it

1. Read sections 2–5 before assigning implementation.
2. Execute work packages C01–C30 using the dependency schedule in section 7.
3. Attach current evidence to each package rather than recreating already-working code.
4. Use section 8 as the end-to-end release test plan.
5. Follow sections 9–12 for deployment and publication.
6. Keep section 13's extended roadmap explicit. Do not describe the whole future roadmap as finished merely because the core release ships.

---

## 2. Evidence and document precedence

### 2.1 What was actually checked

This guide is based on direct inspection of current source, native project files, CI workflows, infrastructure definitions and product documents. It also uses current official Apple and Google publication sources listed in section 15.

A small, non-network Babel experiment was executed against the installed project tooling. It transformed synthetic source containing `process.env[configKey]` with the current mobile Babel plugin and Metro caller identity. The computed lookup remained unchanged. This supports C02's build-configuration diagnosis. It did not read or print configuration values and is not a complete release-bundle test.

The application was **not** run end to end for this document. Existing unit tests were not rerun. Native binaries, cloud resources, provider quality, store account eligibility and legal compliance are **not verified** by this guide. Three attempted repository research workers could not start because their configured provider key had expired; repository inspection was completed directly instead. That tooling incident says nothing about the product backend.

### 2.2 Do not blindly execute old plans

Relevant existing documents:

- `docs/PRD.md` points to `docs/VTON-PRD.md`.
- `docs/AI_Fashion_Vault_MVP_Production_Readiness_PR_Plan.md` is an earlier proposed PR-01–PR-26 backlog.
- `docs/AI_Fashion_Vault_Technical_Implementation_Plan.md` explicitly identifies itself as historical design input.
- `docs/BASELINE.md` mixes evidence from several older revisions and is not a current green baseline.
- `.scratch/ai-fashion-vault-pro-sessions.md` is an older execution schedule, not the remaining work list for this release.

Some current documentation still conflicts with source. For example, `docs/ARCHITECTURE.md:50–52` describes FASHN as the only provider and its key as mandatory, while current `application.yaml:48–55` makes both FASHN and InferHub keys optional. The architecture also calls import confirmation unmounted, but `SharesController.java:80–88` mounts it.

**Decision / Evidence / Revisit if / Status: SETTLED**

- Preserve React Native, Spring Boot/JDBC, PostgreSQL, S3 and SQS. Existing code already implements those boundaries. Revisit only for a demonstrated release blocker that cannot reasonably be repaired within them.
- Continue with the recorded InferHub model choice, `cb/gemini-3.1-flash-image`, through the existing `VtonProvider` seam. Current code implements it. Revisit only if commercial/privacy terms or measured quality prevent release; do not silently substitute another provider.
- Produce one completion guide rather than another parallel architecture specification. Existing plans remain history; source evidence and the release contracts agreed during C01 determine actual implementation.
- Keep core release and extended roadmap completion distinct. The PRD itself separates future phases. Revisit only when the owner changes the release scope; never hide missing launch features behind “MVP.”

### 2.3 Evidence labels for implementation

Use these labels on every work package:

- **SOURCE-CONFIRMED:** source establishes the implementation or gap.
- **EXECUTED:** a scenario ran against a named revision, environment and artifact.
- **EXTERNAL-DEPENDENCY:** an account, credential, contract, device, service or human approval is needed.
- **PROPOSED:** design or acceptance target introduced by this guide, not an existing wire contract.
- **NOT VERIFIED:** no current evidence establishes the claimed result.

Never turn SOURCE-CONFIRMED into EXECUTED. Do not publish a numerical completion percentage based on file counts or old tickets.

---

## 3. Current state: what to retain and what still blocks release

### 3.1 Authentication exists, but the consumer account lifecycle is incomplete

`mobile/src/screens/SignInScreen.tsx:1–127` implements email/password sign-in. It does not provide account creation or password recovery. The inspected mobile source has no sign-out invocation or account-deletion flow. `ProfileSettings` in `RootNavigator.tsx:77–79` currently renders My Model rather than a full settings surface.

`mobile/src/config/firebase.ts:133–164` initializes `getAuth(app)` without durable React Native persistence and explicitly documents in-memory sessions. Restarting the process therefore requires sign-in again.

**Retain:** Firebase-backed ID tokens, backend external-identity mapping and fail-closed API authentication.  
**Finish:** real release configuration, signup/reset/signout, durable SDK-managed session persistence, expiry handling and deletion.

### 3.2 Build-time Firebase configuration has a concrete mismatch

`mobile/babel.config.js:58–89` substitutes literal `process.env.EXPO_PUBLIC_*` member accesses. `mobile/src/config/firebase.ts:59–64` reads `process.env[key]`, using a computed property. The synthetic Babel probe left that pattern untouched.

The historical statement “environment inlining was added” is therefore insufficient to establish working Firebase configuration in a standalone build. The global fallback in `firebase.ts` is not evidence that release builds populate those globals.

**Finish:** make the existing configuration reader and inliner agree; verify the actual bundled app, with Metro disconnected. Do not introduce another environment library just to solve this small mismatch.

### 3.3 The photo-picker wrapper has no corresponding native module in the inspected project

`mobile/src/features/my-model/photoSource.ts:34–81` requires `NativeModules.PhotoPicker` with `takePhoto` and `choosePhoto`. Searches of the Android and iOS sources found no implementation of that module. Android `MainApplication.kt:16–21` explicitly adds only `ShareHandoffPackage`; the package manifest has no installed photo-picker dependency.

This is not the old “always returns null” defect anymore: the wrapper now throws when the native bridge is missing. The remaining work is the native capability, not another JavaScript wrapper.

### 3.4 Import confirmation exists on the server but is not connected to saving in the app

`SharesController.java:80–88` exposes `POST /share/imports/{importId}/confirm`. `ShareImportService.java:165–170` uses the store confirmation path, returning a Vault item representation with mostly null metadata.

`ShareImportScreen.tsx:197–233` instead replays the resolver and tells the user: “Item is ready. Saving it to your Vault is not available yet.” It does not finish the desired preview/edit/collection/confirm journey.

`ShareImportResponse.java:12` returns status/reason/recovery actions, not preview metadata. A safe preview representation must be deliberately added, not obtained by leaking an object key or guessing an S3 URL.

### 3.5 Vault and collections have backend foundations but thin mobile management

`VaultScreen.tsx:84–98` presents names and category/collection filters, without image thumbnails or edit/delete management; items are disabled outside selection mode. `vaultApi.ts:26–59` exposes list and create-from-photo, not the full management surface.

`VaultItemResponse.java:7–8` has metadata but no display-image capability. `CollectionsController.java` already exposes create/list/get/rename/delete and membership operations.

**Finish:** image-backed browsing, details, metadata corrections, collection management and destructive-action feedback. Reuse those mounted routes.

### 3.6 Studio is still not an end-to-end product feature

`studioApi.ts:15–24` calls `/studio/sessions/**`. No matching Studio controller was found in the current backend source. The domain exists in `backend/src/main/java/com/aifashionvault/studio/`, and migrations already include Studio tables.

`StudioScreen.tsx:76–93` requires a supplied session ID and cannot create a session. `RootNavigator.tsx:68–70` routes Generate without passing an outfit snapshot. The screen itself states that immutable outfit handoff is unavailable.

`GenerationCreationService.java:87–132` requires `productUploadId` and creates a `FrozenSingleItemIntent`. `application.yaml:79–84` has multi-item disabled. A single-item demo is not the required top + bottom + footwear product.

### 3.7 InferHub is integrated, but its output handoff is process-local

`InferHubVtonProvider.java:31–45` keeps up to eight outputs in an in-memory map. `submitValid`, `getStatus` and `openOutput` use that map (`:79–135`). A restart or eviction between submission and output consumption can lose the result.

**Finish:** durable output handoff and honest reconciliation of ambiguous paid submissions. Adding more retries is not a fix for unknown provider outcomes.

### 3.8 Production uploads are deliberately blocked until real scanning exists

`ObjectStoreConfiguration.java:92–125` returns an unavailable malware verdict outside development; development uses an explicitly synthetic clean verdict. Production cannot simply be switched on and expected to accept photos.

**Finish:** a real scanner adapter and operational scanner service. Do not replace the fail-closed branch with unconditional CLEAN.

### 3.9 Quality code exists; launch-quality evidence does not

`GenerationProcessorConfiguration.java:113–126` constructs `ApplicationOwnedQualityGate` with `OnnxCpuQualityVision`. The latter names bundled YOLOX and YuNet models and checksums.

That is useful engineering, but detecting a person or face is not proof of preserved identity, correct footwear, texture fidelity or acceptable multi-step output. The actual selected model and prompt need a representative evaluation with human judgment and measured failures.

### 3.10 Deletion is not yet a complete consumer erasure service

`MeController.java:33–40` calls `IdentityProvisioning.beginDeletion`. `JdbcIdentityProvisioning.java:109–117` purges media before committing `deleted_at`.

A separate resumable deletion domain exists (`privacy/DefaultAccountDeletionWorkflow.java`, V021), but the mounted `/me` path does not call it. The inspected source does not establish production assembly of its complete store/ports/scheduler. The mobile UI also does not expose account deletion.

**Finish:** block new work first, then perform durable cleanup, including identity-provider and local-device data where applicable. Preserve truthful partial-failure reporting.

### 3.11 Notifications are not a finished native-to-cloud path

The backend has event stores, a delivery worker and FCM client. `NotificationDeliveryConfiguration.java:12–17` defaults to blank project/token; `NotificationConfiguration.java:44–62` constructs the client from a configured token supplier.

No installed native messaging SDK is declared in `mobile/package.json`. Existing deep-link routing alone does not establish token registration, permission prompts, token rotation or actual background delivery. A fixed configured access token is not a durable production credential lifecycle.

### 3.12 Cloud definitions exist, but they do not yet deploy the chosen complete product

- `infra/terraform/ecs.tf:44–48,115–119` injects FASHN credentials, not `VTON_IMAGE_API_KEY` for InferHub.
- API and worker tasks use the same application image. `GenerationProcessorConfiguration.java:308–318` starts a worker whenever prerequisites exist, without an API/worker role condition. API IAM grants queue send, not consume (`iam.tf:127–132`). Explicit process-role wiring is needed before trusting the split topology.
- CloudWatch application metric export defaults off in `application.yaml:23`; the inspected ECS environment does not enable it.
- `infra/terraform/versions.tf` has provider/version requirements but no remote-state backend declaration.
- RDS is private/encrypted with deletion protection, but single-AZ (`database.tf:23–28`). That is a cost/availability decision, not high availability.
- No Dockerfile was found in the main project search. The backend CI verifies Maven; it does not publish an immutable production image or run a deploy/migration workflow.
- Terraform defaults to `us-east-1`, while the PRD talks about India residency. Actual product/privacy decisions must resolve that mismatch.

### 3.13 Signing scaffolding is present, release proof is not

Android release signing already requires `VTON_RELEASE_*` inputs and fails closed if missing (`mobile/android/app/build.gradle:74–97`). Do not implement signing from scratch. `mobile/android/build.gradle:3–8` already targets API 36.

CI builds Android Debug and an unsigned iOS Debug configuration; iOS is labelled best-effort in `.github/workflows/mobile-init.yml:68–88`. Neither proves a signed store artifact. iOS has a host app, share extension, App Groups entitlements and privacy manifest, but needs real macOS builds and device validation.

---

## 4. Define “all features working” before release

### 4.1 Core launch acceptance

These are mandatory for a complete release of the currently established product:

- A new person can create an account, sign in, recover access, sign out and delete the account.
- My Model can acquire, preview, upload, replace and delete a real photo.
- Consent is understandable, recorded and enforced before body-image processing or transfer.
- Android URL/image share and manual photo import work; iOS share works if iOS is in the release.
- An imported item has a preview, editable name/category, collection selection and a real saved result.
- Vault supports thumbnails, categories, item details, edits and deletion.
- Custom collections support creation, rename, deletion and many-to-many membership.
- Studio creates/restores an owned draft with avatar and compatible slots.
- Single-item and combined top + bottom + footwear generation work with real inputs.
- Jobs survive app restarts and ordinary worker restarts without losing accepted intent or duplicating published Looks.
- Looks expose the actual generated image and source-item provenance, reopen after restart, can be deleted and can seed another draft.
- Missing photos, deleted source items, expired sessions, denied permissions, offline devices and provider failures have actionable outcomes.
- Security, retention, quotas, monitoring, backup/restore, production configuration and signed release builds are exercised.

### 4.2 Full-V1 completion beyond the narrower historical MVP

Do not silently omit these when claiming the complete V1 described by the product documents:

- User-directed local image export and native sharing, with explicit consent.
- Original product source links with safe external navigation.
- Generation-complete notifications, device registration, preference and token lifecycle.
- Basic natural-language Studio changes using existing deterministic domain commands.
- Minimal aggregate product analytics and accurate generation cost accounting.
- Supported additional categories, with capability-specific quality proof rather than a blanket “try on anything” claim.

The older MVP roadmap explicitly deferred several of these, and public share links were deliberately removed from the mobile client. This guide schedules them separately; it does not treat a broad completion request as permission to weaken that privacy boundary. Prefer explicit local export to resurrecting bearer URL sharing. If a smaller first release is chosen, record that scope decision and do not call full V1 complete.

### 4.3 Future PRD features

Style recommendations, variations, affiliate integrations, social feeds, multi-avatar UX, personalization, retailer catalogs and AR are covered in section 13. They are separate product increments, not hidden requirements to cram into the first store submission. “All roadmap features complete” requires those increments too.

### 4.4 Decisions and external inputs to obtain early

The guide can be implemented up to the relevant gates without asking all of these at once. Resolve each before its dependent task:

1. Legal publisher identity, support contact, launch territories and adult/minor audience policy.
2. Android-only first release or simultaneous Android/iOS release.
3. Actual cloud account, approved region, domain and monthly spending ceiling.
4. Separate development/staging/production Firebase and storage boundaries.
5. InferHub commercial image-processing permission, subprocessors, retention, deletion and no-training terms. A model name or inline Base64 request does not establish those terms.
6. Scanner deployment choice and ownership. Default recommendation: use the existing synchronous scanner seam with a private maintained scanning service; do not bolt on a second upload architecture without need.
7. Free capped launch versus paid digital entitlements. Historical launch scope was free/capped; do not introduce subscriptions by accident.
8. Store developer enrollment and macOS/iPhone access for iOS.
9. Consented evaluation photographs and permission to use any screenshots in store listings.
10. Approved quality, availability, recovery and spending thresholds.

---

## 5. Architecture to finish, not replace

### 5.1 Target runtime

```text
Android / iOS release application
  ├─ Firebase SDK authentication
  ├─ Native photo acquisition and share handoff
  └─ HTTPS application API
       ├─ PostgreSQL: users, consent, items, drafts, jobs, provenance, erasure
       ├─ Private S3: avatars, Vault images, Looks, bounded temporary objects
       ├─ Upload validation and real malware scanning
       ├─ Transactional outbox → SQS → generation worker
       │    └─ approved provider → durable output → quality gate → Look
       ├─ Notification events → FCM/APNs delivery
       └─ Retention/deletion workers + logs/metrics/alerts
```

Keep one Spring Boot codebase. Separate API and worker processes only through explicit role configuration where the existing ECS topology needs it. No Kubernetes, Redis, Kafka, custom identity service or new event-sourcing system is required to finish this product.

### 5.2 Authority and consistency rules

- The backend derives ownership from the verified principal, never from a user ID supplied by mobile.
- PostgreSQL is authoritative for consent, saved records, draft revisions, jobs and deletion status.
- S3 contains bytes, not authorization policy. Signed reads are short-lived bearer capabilities; never put them into analytics or treat them as permanent identifiers.
- Mutable Studio state and immutable generation input are different things.
- A generation freezes exact avatar/item image revisions, categories, order, mode and policy versions.
- A request that lost its response reuses its original identity. It must not become an additional paid generation.
- Queue redelivery must not create a duplicate Look. Do not promise exactly-once execution at an external provider.
- Deletion blocks new work before cleanup starts. Late uploads, callbacks and quality completion must respect that barrier.
- Published mobile versions continue to exist. Backend compatibility must survive rollout and rollback; a repository-wide clean cutover alone is insufficient once clients are in stores.

### 5.3 Contract work before parallel implementation

C01 should freeze the following seams using the repository's existing DTO/error conventions:

- Import preview and confirmation, including metadata edits and optional collection selection.
- Vault image display capability and source-link representation.
- Studio session creation/read/mutation, revision conflicts and clone-from-Look.
- Generation input discriminant: manual single-item upload versus owned Studio revision.
- Generation recovery/history lookup and terminal/uncertain outcome semantics.
- Look provenance and deletion behavior.
- Consent grant/withdrawal and account-deletion request/status.
- Device-token registration/preferences and content-report submission.

These are **contracts to design**, not claims that endpoints already exist. Reuse mounted endpoints where they already implement the needed outcome. Specify field types, optional versus null, IDs, status codes, error shapes, preconditions, idempotency and limits before splitting backend and mobile work.

---

## 6. Detailed implementation work packages

Each package below has a concrete outcome, likely ownership, dependencies and acceptance evidence. New file names are intentionally not invented where the existing architecture should determine them. Use the named directories and existing symbols as starting points.

### C01 — Freeze the release inventory and repair documentation authority

**Depends on:** none. **Owns:** product/API documentation and the implementation ledger.

1. Walk the mounted navigation and controller routes; label each core and full-V1 feature as connected, incomplete, unmounted or externally blocked.
2. Reconcile source conflicts in README, architecture, contract inventory and old baselines. Preserve dated historical results rather than overwriting them as if rerun.
3. Freeze the seams in section 5.3 and map every feature to a package below.
4. Define the launch platform/category/capability set; explicitly list full-V1 and extended-roadmap gates.
5. Record fresh baseline commands and failures when implementation begins. Never fix an old alleged failure solely because a report named it.

**Acceptance:** one engineer can identify the real controller, client and screen for every launch capability; each missing seam has an owner. No old slice marked DONE substitutes for functional evidence.

### C02 — Make standalone mobile configuration reliable

**Depends on:** C01. **Owns:** `mobile/babel.config.js`, `mobile/src/config/firebase.ts`, `apiConfig.ts`, release CI inputs.

1. Align Firebase's computed environment reads with the existing inliner's supported syntax. Prefer explicit named public values over a more powerful custom compiler plugin.
2. Define a deterministic release source for public Firebase configuration and HTTPS API URL. Today the plugin reads `mobile/.env`; merely exporting CI variables is not evidence that they are used.
3. Prevent production builds with missing, placeholder, localhost, emulator or LAN API settings.
4. Keep image-provider keys, service-account credentials and signing secrets out of public mobile configuration.
5. Remove stale comments claiming inlining does not exist, once the actual bundle is proven.

**Acceptance:** install a release build with Metro stopped; Firebase initializes, `/me` authenticates against the correct environment, and changing staging configuration cannot accidentally target production. Inspect bundle content without printing secrets. The computed-lookup regression must fail before the repair and pass after it.

### C03 — Complete authentication and account access

**Depends on:** C02. **Owns:** Firebase session boundary, sign-in screen, navigation and a real settings/account surface.

1. Add signup and password-reset flows using Firebase's supported SDK methods; retain neutral credential errors.
2. Decide whether verified email is required for expensive generation; if required, enforce it server-side, not just by hiding a button.
3. Configure explicit SDK-managed React Native persistence. Prefer secure native credential storage for refresh credentials; if an SDK adapter is used, review its at-rest storage properties. Do not serialize raw ID tokens into application state.
4. Add signout and clear account-bound draft/cache/history pointers. Pending shares must not be silently saved under a different newly signed-in account.
5. Handle expired/revoked sessions, loading restoration, password changes and repeated submissions.
6. Apply authentication gates consistently to stack routes and deep links, not only the main tab screen.

**Acceptance:** a fresh user signs up, recovers access, restarts the installed app without an unnecessary login, signs out and signs into a second account without seeing the first account's images or draft. Backend rejects invalid/wrong-project tokens.

### C04 — Deliver real camera and library acquisition on each platform

**Depends on:** C02; authenticated upload proof needs C03/C05. **Owns:** `photoSource.ts`, native Android/iOS integration and photo handling.

1. Implement the existing PhotoPicker interface natively, or deliberately replace it with a maintained RN-compatible native dependency and migrate every caller. A dependency is justified here if it replaces substantial platform lifecycle code; do not keep two competing pickers.
2. Use the Android system Photo Picker and the appropriate iOS system picker for selection. Request camera permission only when using a capture API that requires it.
3. Return cancellation, permission denial and real failure distinctly.
4. Normalize orientation and supported output formats; convert HEIC/other device formats when necessary rather than merely labelling them JPEG.
5. Bound bytes and decoded dimensions, retain private URI access long enough to upload, and clean temporary files after success/cancel.
6. Verify `content://` and `file://` handling on real devices. Avoid unbounded image copies or Base64 in ordinary JS state.

**Acceptance:** gallery and camera work on Android and iOS devices; denial/revocation/cancel/rotation/large-image/HEIC scenarios do not crash or fabricate a selected photo. Native release builds contain the actual bridge.

### C05 — Enable secure production uploads and scanning

**Depends on:** C01; can run alongside C02–C04. **Owns:** `platform/objectstore`, scanner runtime and deployment configuration.

1. Register a real `MalwareScanner` implementation through the existing seam.
2. Operate the scanner privately with bounded requests, timeouts, health checks and signature/model update ownership.
3. Preserve independent file-signature, decode, dimension and size checks; malware scanning is not image normalization or content moderation.
4. Keep uploads quarantined until all checks pass. Unavailable scanning must reject or remain pending truthfully, never become CLEAN.
5. Bind upload capabilities to owner, expected content and one-time claim semantics. Include fetched retailer images in the appropriate validation path.
6. Test permissions and object cleanup with actual staging S3, not only LocalStack.

**Acceptance:** valid images can complete uploads in staging; safe scanner test fixtures are rejected; scanner outage fails closed and recovers without orphan buildup. A user cannot claim another user's upload or bypass inspection by changing MIME headers.

### C06 — Complete My Model and consent enforcement

**Depends on:** C03–C05. **Owns:** My Model screen/API, avatar domain and consent boundary.

1. Retain existing avatar upload, revision/precondition, warning and signed-preview behavior.
2. Add a clear consent surface naming body-image processing, third-party AI transfer, retention and withdrawal. Record policy version and purpose, not just a local boolean.
3. Enforce consent before provider transfer; uploading a photo is not blanket consent for unrelated processing.
4. Make active-avatar selection explicit. Initially one active model is sufficient, but replacement must not silently mutate already-submitted jobs.
5. Provide retake, replace and delete with progress, stale-revision recovery and preview refresh.

**Acceptance:** a new user sees their actual photo after restart; stale edits cannot overwrite a newer revision; withdrawal prevents new processing; deletion and replacement do not resurrect old previews or alter frozen jobs.

### C07 — Finish import preview, metadata and confirmation

**Depends on:** C03/C05 and C01's import contract. **Owns:** share-import screen/client, SharesController, ShareImportService and store.

1. Preserve native handoff and the existing resolver/recovery flow.
2. Provide an owner-scoped preview with signed image access, editable name/category and safe source metadata. Extend the contract intentionally; the current status-only response is insufficient.
3. Call the existing confirmation endpoint rather than replaying import resolution as “confirmation.”
4. Extend confirmation to save corrected metadata and collection membership consistently. Use existing normalization/category types.
5. Return the saved Vault item and navigate to it or begin Studio; never show “saved” before persistence succeeds.
6. Make repeated confirmation idempotent. Reproduce replay after an upload was consumed: current create replay revalidates staging (`ShareImportService:64–69`), so a historical “fixed” label is not adequate evidence.
7. Handle process death, duplicate OS delivery, two consecutive shares, expired staging, extraction failure, login during handoff and cancellation.

**Acceptance:** share a supported URL; preview and correct it; save once; retry after deliberately losing the response; exactly one owned item remains. A blocked/unsupported retailer URL still reaches screenshot fallback without discarding the source reference. Another user's import ID is inaccessible.

### C08 — Complete image-backed Vault CRUD

**Depends on:** C05/C07 and C01's display contract. **Owns:** `VaultScreen`, `vaultApi`, VaultController/Service and image representation.

1. Add owner-authorized thumbnail/full-image capabilities to the relevant representation; refresh expired capabilities.
2. Implement item detail, metadata edit and delete using mounted backend operations.
3. Keep system category separate from collection membership. Show unclassified items honestly and let users correct them.
4. Refresh lists after saves, edits and deletes; bound/paginate large lists before loading entire libraries becomes a device-memory problem.
5. Preserve source references and show safe “Open original product” behavior. Never turn source URLs into server-side unrestricted fetch permission.
6. Explain effects on drafts and old Looks when an item is removed.

**Acceptance:** create, view image, edit category/name, filter, reopen after restart and delete. A deleted source item is not silently substituted into a future generation. Broken or expired previews are recoverable.

### C09 — Complete custom collection management

**Depends on:** C08. **Owns:** collection client/UI and existing `CollectionsController`/store.

1. Expose create, rename, delete and item membership operations already mounted on the backend.
2. Add collection selection/creation during import, with a recoverable path if item save succeeds but collection update fails.
3. Support one item in multiple collections; removing membership must not delete the garment.
4. Define duplicate/blank names, maximum lengths and ownership errors using existing validation conventions.
5. Make deleting a collection explicitly preserve its garments unless a separate destructive operation is requested.

**Acceptance:** an item can appear in two collections, be removed from one and remain in the other; collection rename/delete persists after restart; foreign-owner IDs cannot be added.

### C10 — Mount persistent Studio sessions

**Depends on:** C06/C08 and frozen Studio contract. **Owns:** existing Studio domain, JDBC persistence/controller and `studioApi`.

1. Reuse `StudioSession`, `StudioItem` and existing Studio schema rather than creating a second draft model.
2. Mount create/read/update operations matching the agreed client contract.
3. Persist active avatar, ordered slots/items, revisions and update time. Enforce owner scope for every referenced record.
4. Use optimistic concurrency/preconditions so stale devices do not overwrite newer drafts.
5. Implement explicit add/replace/remove and existing full-body conflict rules. Return a conflict or ask for replacement; never silently discard a garment.
6. Support restore of the active draft and cloning a Look with explicit missing-item resolution.

**Acceptance:** compose on one session, restart app/backend, reload the same draft; two stale concurrent edits cannot silently win; cross-owner avatar/item/session references fail.

### C11 — Finish the actual Studio mobile experience

**Depends on:** C10. **Owns:** StudioScreen, Vault selection and RootNavigator.

1. Create/load the active draft when entering Studio; route params are navigation hints, not authoritative state.
2. Display avatar and selected garment images, not raw item UUID lists.
3. Make top/bottom/footwear slots addable, replaceable and removable; show compatibility constraints and unresolved items.
4. Return selections from Vault to the correct session and clear consumed navigation parameters.
5. Hand the owned session/revision to Generate. Do not navigate to the old manual-photo generator and discard the outfit.
6. Remove contradictory notices once the corresponding operation really succeeds.

**Acceptance:** from a fresh install and empty draft, a user can construct and edit top + bottom + footwear, leave the screen, restart and recover it, then submit exactly that outfit.

### C12 — Freeze multi-item generation intent through the existing pipeline

**Depends on:** C10 and C01's generation contract. **Owns:** GenerationCreationService, generation store/intent and worker input resolution.

1. Add an explicit Studio-based create form alongside the supported manual single-item path. Do not overload `productUploadId` to mean a Vault item or session.
2. Freeze the avatar/image revision, selected item/image revisions, categories, slot order, generation mode, policy and request identity in one durable accepted intent.
3. Validate owner, consent, deletion state, compatibility and provider capabilities before reserving spend or enqueueing work.
4. Reuse transaction/outbox and existing immutable orchestration records.
5. Ensure edits after submission do not change its inputs; deleting/withdrawing consent must still prevent prohibited future work/publication according to the frozen privacy rule.
6. Publish accurate source-item provenance with the final Look.

**Acceptance:** submit three distinct garments, then edit the draft; the accepted job still has the original three revisions. Duplicate create requests produce one logical job. The final image visibly contains all three selected garments, not only the first.

### C13 — Make provider execution and results durable

**Depends on:** C05; multi-item proof also needs C12. **Owns:** InferHub adapter and generation worker/media persistence.

1. Replace the eight-entry process-local output dependency with the existing private temporary object store plus durable attempt metadata. Record ownership/checksum/state before treating the output as recoverable.
2. Keep synchronous provider calls inside workers, not mobile requests or long-held HTTP transactions.
3. For sequential composition, each next step consumes the actual accepted prior output and only its intended next garment. Preserve exact step order and durable intermediate references.
4. Classify network failures before dispatch, known rejection, known success and unknown charged outcome. Use provider reconciliation/idempotency only if actually supported.
5. Do not automatically retry an ambiguous paid submit merely because a TCP timeout occurred. Surface an honest terminal/recoverable uncertain outcome if reconciliation is unavailable.
6. Align heartbeat, visibility extension, attempt deadline and overall multi-step deadline with measured provider latency. A three-step outfit can take much longer than one call.
7. Clean abandoned intermediate objects without deleting media still referenced by active work.

**Acceptance:** controlled worker termination after response receipt, after temp persistence and before final publication converges to one Look or an honest explicit failure. More than eight completed submissions cannot evict required results. Queue replay does not duplicate publication. Record paid-call counts separately from logical job counts.

### C14 — Complete progress, cancellation and restart recovery

**Depends on:** C12/C13. **Owns:** GenerateScreen, generation API/session persistence and history lookup.

1. Persist accepted generation IDs and request identities using the established account-scoped persistence layer.
2. Recover active/recent jobs from a server-backed list or recovery contract; do not rely solely on an in-memory last-generation variable.
3. Show queue/preparation/generation/quality phases without invented percentages or guaranteed completion times.
4. Distinguish cancel requested from provider physically stopped. Prevent publication after an accepted cancellation according to the backend state machine.
5. Pause foreground polling appropriately and refresh on resume. Notifications are supplementary, not authoritative job state.
6. Keep the outfit when generation fails or is unavailable; retry choices must explain whether they create a new chargeable request.

**Acceptance:** kill/reopen the app in each phase, switch networks and reopen a completed result without creating another job. Cancellation racing completion resolves deterministically. A long provider call never looks like a frozen button with no explanation.

### C15 — Complete Looks, provenance and reuse

**Depends on:** C12–C14 and C08. **Owns:** Look/Looks screens, APIs and existing LooksController/store.

1. Display signed previews with expiry refresh, timestamp, outcome and the exact source garments.
2. Define save semantics clearly. If successful jobs already create persisted Looks automatically, present that truth instead of adding a fake Save operation.
3. Add real delete UI against the mounted delete route, with confirmation and physical cleanup behavior.
4. Make “Modify” create a draft from provenance rather than only passing a sourceLookId into an unloaded Studio.
5. Handle source-item/avatar deletion with explicit unavailable references and replacement steps.
6. Avoid revealing an owner-only image through unauthenticated deep links.

**Acceptance:** generate, restart, reopen, inspect provenance, clone/edit, delete and revisit a stale deep link. The result cannot be viewed by a second account. Deleting a Look does not unexpectedly delete reusable Vault garments.

### C16 — Implement user-directed export/share and source navigation

**Depends on:** C15 and consent/share contract. **Owns:** Looks native export, source-link UX and temporary file lifecycle.

1. Prefer downloading the authorized result into a bounded private temporary file and invoking the OS share sheet.
2. Tell the user that exporting sends a copy outside the app's deletion/control boundary; require a deliberate action.
3. Use narrow Android URI grants and supported iOS activity sharing. Clean private export staging after use/expiry, without deleting the user's explicitly saved copy.
4. Support save-to-photos with the minimum platform permissions and correct denial handling.
5. Open original product links through safe http/https external navigation. Do not invent affiliate attribution or a guaranteed price.
6. Do not reintroduce the removed mobile share-link client as a shortcut. If public links are approved later, design revocation, expiry, access and deletion separately.

**Acceptance:** the received export is the actual Look, not a signed URL that expires unexpectedly; cancel/permission denial are harmless; temporary grants expire; source navigation works without leaking credentials.

### C17 — Complete durable privacy, account deletion and data requests

**Depends on:** C03/C05 and the actual media/job paths. **Owns:** privacy workflow, `/me`, identity provisioning, settings and cleanup workers.

1. Connect the existing V021 workflow/domain to real persistence, resource ports and scheduled execution; reuse it rather than adding another deletion system.
2. Atomically establish a deletion barrier before external cleanup. All create/claim/generation/publication operations must respect it, including already-authenticated in-flight requests.
3. Track retryable steps for active jobs, objects, metadata, caches, notifications/device registrations and external identity deletion. Authenticate deletion before removing the credential needed to request it.
4. Define retained minimal audit/consent records and their legal purpose/retention separately from photos; do not promise deletion of data intentionally retained by law or policy.
5. Coordinate Firebase account deletion/revocation with backend cleanup. Do not falsely declare success if identity-provider deletion is still pending.
6. Provide in-app request/status and a public deletion-request route/page suitable for Google Play, with identity verification and no account-existence leakage.
7. Ensure backup restore re-applies deletion tombstones before restored data is exposed.
8. Support access/export/correction requests through an authenticated workflow or documented verified support process, as appropriate to the launch jurisdiction.

**Acceptance:** inject a failed S3 delete and restart the worker; the account remains blocked and cleanup resumes truthfully. Race deletion against upload and final Look publication: nothing becomes newly visible after the barrier. Export/local caches and Firebase lifecycle follow the documented policy.

### C18 — Evaluate AI quality and implement content safety/reporting

**Depends on:** C06/C12/C13. **Owns:** quality gate, evaluation dataset/report and moderation/report flow.

1. Verify ONNX resources are present, licensed and loadable inside the actual production image; local Java tests do not prove Linux native-runtime packaging.
2. Build a consented, representative evaluation set: tops, bottoms, footwear, one-pieces/ethnic categories if advertised, varied skin tones/body shapes, lighting, garment patterns and partial occlusion.
3. Evaluate single-item and combined outfits separately. Score identity preservation, garment presence, color/pattern fidelity, anatomy, pose and severe failure; face detection alone is not the identity oracle.
4. Record model/prompt/policy version, latency distribution, cost, failure rates and human ratings. Use a held-out set after tuning; do not count repeated tuned samples as independent proof.
5. Proposed initial gate for owner approval: at least 90% acceptable outputs on each advertised core scenario, no unresolved severe safety defects, and all three garments present in accepted multi-item examples. Report sample size and uncertainty; this is a product threshold, not a statistical guarantee.
6. Reject prohibited sexualized/non-consensual or otherwise unsafe use; apply safety checks to inputs and outputs. Malware scanning does not serve this purpose.
7. Add in-app reporting for harmful AI results and a staffed review/removal process. Avoid retaining reported personal images longer than needed without appropriate disclosure.
8. State clearly that the app is visual approximation, not fit, size or body-measurement prediction.

**Acceptance:** evidence demonstrates all advertised categories with real chosen-provider results. Rejected/unsafe output never silently enters Looks. Reports reach a responsible operator. If footwear or identity fidelity fails, launch of the promised combined outfit is blocked—not relabelled successful.

### C19 — Finish quotas, spending controls and integrity posture

**Depends on:** C12/C13. **Owns:** admission/generation policies, operational configuration and quota UX.

1. Expose approved caps through existing configuration rather than shipping unexplained hard-coded daily amounts. Current processor configuration defines USD 500 global / 25 per user / 250 per provider ceilings; those are source defaults, not an approved launch budget.
2. Verify every generation entry point, including regeneration and natural-language initiation, uses the same admission policy.
3. Reserve conservatively for the full outfit/retry budget, record actual attempts, settle/release correctly and avoid double-counting replay.
4. Implement per-account abuse protection and server-side global pause. Do not trust arbitrary device/IP headers as authenticated identity.
5. Exercise the no-provider path: no quota reservation, provider transfer or misleading accepted job.
6. If device attestation is a launch requirement, complete real platform verification, nonce/replay binding and signed release registration before enabling the existing flags. `application.yaml:63–84` explicitly says current strict flags would reject all covered requests. Never present disabled attestation as an active control.

**Acceptance:** simultaneous requests cannot overspend the approved limit; rejected/duplicate/failed requests account correctly; pause blocks new admission while durable history and deletion remain usable. An operator can explain provider bill versus application accounting.

### C20 — Complete notifications and deep links

**Depends on:** C03/C14/C15/C17. **Owns:** notification backend, device registration and native messaging.

1. Add an authenticated owner-bound device-token registration/removal lifecycle and preferences through the current notification domain.
2. Install/configure native messaging on Android and iOS; request notifications at a sensible point, not as an unexplained first-launch wall.
3. Use refreshable server credentials for FCM HTTP v1. Do not operate indefinitely on a pasted short-lived access token.
4. Configure APNs credentials/entitlements for iOS through the chosen FCM path; keep auth Firebase setup distinct from push delivery setup.
5. Preserve existing event fencing/deduplication, prune invalid tokens and stop delivery after account deletion/signout where appropriate.
6. Deliver opaque Look identifiers, not photo URLs or private source details, in notifications.
7. Resolve the existing `fashionvault://looks/<id>` deep link only after authentication/ownership validation, preserving pending navigation through login.

**Acceptance:** real Android/iPhone receive a result notification when backgrounded, tap opens the correct owned Look, denied permission still allows recovery through Looks, rotated tokens work, and another signed-in account cannot open the result.

### C21 — Finish basic natural-language Studio without a second state model

**Depends on:** C10–C12/C19; full-V1 gate, not a prerequisite for manual Studio. **Owns:** `studio/agent`, command endpoint and Studio text controls.

1. Route text into the existing allowlisted `StudioAgent` tools and the same owned revisioned commands used by manual controls.
2. Define a narrow supported vocabulary: find an owned item, add/replace/remove, clear a slot and explain a conflict. Resolve ambiguous names through explicit selection.
3. If using an LLM, obtain approved text-model/privacy/cost configuration. Do not assume the image model is a text-agent deployment.
4. Treat item names, source descriptions and provider text as untrusted. No arbitrary network, SQL, storage key or cross-owner tool access.
5. Preview intended mutations when ambiguity matters. Never trigger a chargeable generation from an ambiguous phrase without the agreed user confirmation.
6. Retain manual controls during text-model outage. Disabled unsupported controls are a temporary development state, not full-V1 completion.

**Acceptance:** “replace the top with my blue shirt” changes the correct owned draft revision; duplicates require disambiguation; injected text cannot call outside the allowlist; failure leaves the draft intact.

### C22 — Complete minimal analytics and feature capability consistency

**Depends on:** actual connected feature paths; C19 for cost events. **Owns:** existing analytics/metrics and feature flags.

1. Record only useful lifecycle events: import started/saved/failed, generation admitted/completed/failed, Look reopened/exported, deletion requested/completed and actual cost.
2. Keep photos, prompts, tokens, signed URLs and sensitive source query strings out of event payloads.
3. Separate operational metrics from optional product analytics and apply the appropriate consent/retention rules.
4. Default unsupported capabilities off in release builds; current mobile defaults enable multi-item/NL/HD flags even though some backing paths are incomplete.
5. Make backend capability/admission authoritative. Remote flags may disable a feature but may not bypass authorization, safety or review obligations.
6. Clear account-specific overrides on session changes and prove the operational kill switch reaches actual admission.

**Acceptance:** event counts reflect saved outcomes rather than button taps; no private image data appears in logs/events; advertised controls match working server capabilities; offline flag fetch cannot enable an unsafe feature.

### C23 — Make every screen release-quality and accessible

**Depends on:** completed feature screens. **Owns:** mobile presentation and native interaction.

1. Run the full app on small and large phones with text scaling, keyboard, safe-area insets, rotation where supported and TalkBack/VoiceOver.
2. Replace debug-like identifiers and unavailable notices with clear product states where functionality has been implemented.
3. Check image aspect ratios, loading skeletons/placeholders, selected slot semantics, destructive confirmations and reachable controls.
4. Differentiate empty, offline, unauthorized, retryable dependency failure and permanently deleted records.
5. Ensure import forms and Studio do not lose edits on back navigation or transient network errors.
6. Use actual release screenshots for visual review; do not accept mocked component snapshots as UI proof.

**Acceptance:** a person unfamiliar with the code can complete the core loop without guidance; screen readers identify controls and selected items; large text does not hide Generate/Delete/Confirm actions; no visible dead buttons remain in the declared release scope.

### C24 — Build an immutable production backend artifact

**Depends on:** C05/C13/C17; may begin earlier with a disabled-provider image. **Owns:** backend packaging and CI.

1. Choose Spring Boot buildpacks or a small maintained container build. Buildpacks are the smallest existing-stack option if they package the required native libraries correctly; use a Dockerfile only for demonstrated packaging needs.
2. Pin Java/runtime/base image behavior and produce one immutable digest for promotion from staging to production.
3. Include ONNX models/native runtime and trust stores; run as non-root with writable locations limited to bounded temporary needs.
4. Verify secrets are runtime inputs, never build arguments/layers. Emit a dependency/SBOM and scan the final image.
5. Provide startup/readiness behavior that distinguishes a healthy API from unavailable required dependencies without disclosing internals.
6. Add a controlled migration-only execution path and explicit runtime process roles. These are new capabilities to implement, not commands that currently exist.

**Acceptance:** the built Linux image runs against staging PostgreSQL/S3/SQS, loads image models, uploads/scans, generates and deletes correctly; its digest and runtime configuration are recorded. No developer laptop filesystem or LocalStack endpoint is required.

### C25 — Repair and provision the AWS staging topology

**Depends on:** C24 and approved cloud account/region/budget. **Owns:** `infra/terraform` and deployment runbook.

1. Configure encrypted remote Terraform state with locking, least-privilege access and a reviewed state bootstrap. Protect state/plan files as sensitive artifacts.
2. Resolve region/residency, AZs, account naming, domain and ACM certificate. Verify PostgreSQL 18 and instance availability in the chosen region before provisioning.
3. Wire InferHub secret selection and IAM into API/worker as actually needed; never fill a FASHN-only secret to make ECS start when InferHub is the selected provider.
4. Use separate application DB and migration credentials rather than the RDS master user for ordinary requests.
5. Add explicit API/worker/scheduled-work role controls; avoid API queue polling under send-only IAM. Assign retention/deletion/notification work deliberately.
6. Configure scanner access, private service networking, outbound access for Firebase JWKS/provider/FCM, and HTTPS-only public API/media access.
7. Enable actual metric export, pin collector images and confirm alarms subscribe to real operators.
8. Review cost drivers: RDS, NAT gateways, Fargate tasks/sidecars, ALB, storage, logs and image-provider calls. Single-AZ staging may be reasonable; production availability is a separate approved choice.
9. Deploy to isolated staging first. Do not copy personal local photos or development accounts into production.

**Acceptance:** a phone on cellular internet reaches staging HTTPS; private DB/buckets are not publicly accessible; actual ECS roles can do required work and are denied unrelated work; upload/generation/deletion/notification smoke succeeds without development overrides.

### C26 — Add release pipelines, migrations and rollback

**Depends on:** C24/C25. **Owns:** `.github/workflows` and deployment automation.

1. Keep fast PR checks, but add image/artifact publication and environment-protected staging/production promotion.
2. Use short-lived cloud federation where supported, protected release credentials and explicit approval before production.
3. Run Flyway from the controlled migration step; prevent every replica from attempting privileged schema management.
4. Preserve published migration checksums and the existing expand/deploy/migrate/contract discipline. Reconcile the V025 reservation with current branch state before adding migrations.
5. Test old app/new schema and old mobile/new API compatibility. Never contract fields/tables still required by released mobile clients.
6. Deploy the same tested image digest, record schema version, task definitions and mobile build IDs.
7. Roll back compatible application images without rolling back data destructively. For irreversible schema failure, restore/recover through a rehearsed plan, not `flyway repair` or manual table drops.

**Acceptance:** a staging release deploys, fails a controlled health check and rolls back; a forward migration works with the previous app version; no secret is printed in CI; approvals and artifact provenance are retained.

### C27 — Prove operations, security and disaster recovery

**Depends on:** C17/C19/C25/C26. **Owns:** runbooks, alarm exercises and release evidence.

1. Exercise database backup/PITR restoration to an isolated environment and verify user-owned media/provenance consistency.
2. Re-apply deletion records before reopening restored data; define object backup/version-retention behavior explicitly.
3. Monitor queue age/DLQ depth, failed/stuck jobs, scanner health, auth failures, deletion backlog, provider cost, latency, quality rejections and actual API availability.
4. Trigger each critical alarm safely and verify a human receives and can act on it. A Terraform alarm definition is not delivery proof.
5. Run owner-isolation/SSRF/malformed-media/rate-limit/expired-link probes and review log redaction.
6. Record proposed recovery objectives for owner approval—for example, RPO 24 hours and RTO 4 hours for an initial service—then measure the restore exercise against them. Do not advertise these as achieved before measurement.
7. Establish support ownership, incident escalation, provider outage procedure, secret rotation and release rollback responsibilities.

**Acceptance:** restore and deletion non-resurrection are demonstrated; security blockers are closed; a named operator can pause generation and recover service from the documented runbook.

### C28 — Produce and verify the Android store artifact

**Depends on:** C02–C23 for declared Android scope and C25–C27. **Owns:** Android release configuration and Play Console preparation.

1. Keep the existing application ID unless the owner explicitly changes product identity before the first publication.
2. Create/protect the upload key and use the existing `VTON_RELEASE_*` signing inputs. Configure Play App Signing and secure key recovery access.
3. Set a monotonically increasing versionCode and deliberate public versionName; reconcile npm/native versions.
4. Build `bundleRelease`, validate the AAB and install APK splits generated from that exact bundle.
5. Inspect packaged target SDK, cleartext/debug settings, permissions, public configuration and every native library's 16 KB compatibility.
6. Test without Metro on real devices, including share intents, camera/library, slow generation, notifications, account deletion and upgrades preserving data.
7. Upload to internal/closed testing, complete required tester access and pre-launch findings, then prepare production submission.

**Acceptance:** the Play-distributed build completes section 8 against the intended backend. A debug APK or locally installed differently signed build is not the release evidence.

### C29 — Produce and verify the iOS App Store artifact

**Depends on:** C02–C23 for iOS scope and C25–C27. **Owns:** iOS project, extension, signing, native bridges and App Store Connect.

1. Use a Mac/current supported Xcode toolchain; Windows source inspection cannot validate signing or a native archive.
2. Register the host and share-extension bundle IDs, matching App Group, capabilities and distribution profiles under the publishing team.
3. Build required native photo/messaging bridges, complete purpose strings and verify host/extension entitlements and privacy manifests in the archive.
4. Preserve the supported share architecture: extension stages a bounded handoff, main app imports after authentication. Do not force-launch the host through unsupported extension tricks or claim a generation starts while only staging occurred.
5. Set release version/build, icons/screenshots, signing, export compliance and production API configuration.
6. Produce a signed archive, validate/upload it and test the TestFlight-distributed binary on physical iPhones; test iPad compatibility if the supported device family includes it.
7. Verify share extension with host terminated, permission denial, interrupted staging, iCloud photos, background notification delivery and IPv6-only networking.

**Acceptance:** a real TestFlight install and its embedded share extension complete section 8. macOS CI unsigned Debug success alone does not close this package.

### C30 — Publish, monitor and close the release honestly

**Depends on:** C27 and C28/C29 for each target store; full-V1 claims also require C16/C20–C22.

1. Complete privacy/data safety, age/content ratings, support/deletion URLs, review credentials and accurate listing assets.
2. Verify the live backend can sustain reviewer/testing load and has generation quota reserved for review without a hidden fake mode.
3. Resolve store review feedback against real behavior, not by hiding the broken path only for review.
4. Use the store's available controlled release mechanism; for a new listing, use test tracks/limited initial territory or manual publication as appropriate. Do not assume percentage rollout is available for every first release.
5. Watch crash/ANR rates, provider spending, stuck jobs, deletion backlog and user support after publication; pause new generation or stop rollout on pre-agreed thresholds.
6. Keep older supported clients operational. Publish a signed corrective update when needed; disabling new installs does not remove installed binaries.
7. Close the release only after store approval, production installation and monitored user journeys have evidence.

**Acceptance:** users can install from the actual target store and complete the advertised product. The release ledger links binary/build IDs, backend digest, schema, configuration, evaluations, privacy review and operational evidence. Remaining roadmap work is named, not hidden behind a blanket COMPLETE.

---

## 7. Dependency-ordered execution schedule

This is a work schedule, not a requirement to run a particular agent framework. Parallelize only genuinely separate ownership; serialize shared contracts, migrations and integration gates.

### Stage A — Establish the release boundary

- C01 first.
- Begin external account/provider/privacy/cloud prerequisites immediately; they can take longer than coding.
- Freeze shared DTOs and migration ownership before separate backend/mobile implementation.

### Stage B — Make real people and real photos work

- C02 → C03.
- C04 can proceed alongside C05 after configuration/contract agreement.
- C03 + C04 + C05 → C06.
- C03 + C05 → C07.
- C07 → C08 → C09.

**Gate A:** a fresh user can sign up, create My Model, share/import and manage an image-backed Vault through the real app. No provider is needed to prove this gate.

### Stage C — Complete the product's differentiating capability

- C06 + C08 → C10 → C11.
- C10 → C12.
- C13 can begin alongside Studio work; integrate with C12 before full-outfit proof.
- C12 + C13 → C14 → C15.
- C18 and C19 validate the integrated generation path.

**Gate B:** exact top + bottom + footwear input produces one acceptable, persisted private Look with provenance and restart recovery. If this fails, the project's main promise is not complete.

### Stage D — Complete privacy and full-V1 features

- C17 starts after the real resource inventory is stable and may proceed alongside generation work with an agreed deletion barrier.
- C15 → C16.
- C03 + C14 + C15 + C17 → C20.
- C10–C12 + C19 → C21.
- C22 consumes finished event/capability paths; C23 reviews actual screens.

**Gate C:** privacy is complete, all declared full-V1 controls work, and no user-facing dead ends remain.

### Stage E — Production-equivalent deployment

- C24 → C25 → C26 → C27.
- Container/infra work can begin earlier against stable seams, but staging acceptance waits for required feature packages.

**Gate D:** signed mobile candidates complete the product against remote staging, with real scanner, provider and cleanup; recovery and alerts are demonstrated.

### Stage F — Store release

- C28 and C29 can run independently once platform-independent gates are green.
- C30 follows the gate for each store being launched.

**Gate E:** actual store installations work against production and are monitored.

### Integration rules

- Do not assign separate workers to overlapping navigation files, one DTO or the same migration version without an integration owner.
- Reuse one frozen request/response extract for frontend/backend work.
- Each feature package includes its meaningful regression proof; do not create test-only “coverage” tickets detached from observable behavior.
- Small local checks during implementation; broader integrated checks at the gates. Do not repeatedly run the entire backend suite under concurrent mobile tests on this host.
- When a prerequisite fails, retain unrelated finished work; do not reset the whole repository or replay old mega-plans.

---

## 8. Acceptance scenarios and evidence to retain

Run these using release candidates, real UI interactions and a staging service. Test doubles are useful for deterministic failure scheduling but do not replace device/provider/cloud acceptance.

### Journey J01 — Fresh account and durable access

Fresh install → signup → required verification/consent → signout → sign-in → kill/reopen → recover password → sign-in with changed password. Confirm no token/image data appears in logs and a second account sees none of the first account's state.

### Journey J02 — My Model lifecycle

Select JPEG/PNG and device-native HEIC where applicable; take a photo; cancel/deny/revoke permissions; replace from another session; retry a stale revision; wait for preview expiry; delete. Confirm actual private objects and revisions match the UI.

### Journey J03 — Shopping share and fallback

Share URLs from representative intended shopping apps/browser; include a URL with tracking text, unavailable extraction and a screenshot. Preview, correct category/name, create a collection and confirm. Repeat the same handoff after losing a response. Confirm one item, not duplicates. No CAPTCHA or retailer-login bypass is part of acceptance.

### Journey J04 — Vault and collections

Import at least one top, bottom and footwear item; browse image grids; edit metadata; add each item to multiple collections; rename/delete a collection; delete an item. Test enough records to exercise list boundaries. Verify source links and ownership.

### Journey J05 — Persistent outfit and conflict handling

Create top + bottom + footwear with My Model. Replace/remove a slot, reopen the draft, clone a Look, then attempt a conflicting full-body item. Change the same draft from two sessions: one stale revision must not silently overwrite the other.

### Journey J06 — Real combined generation

Submit the three-item outfit. Inspect the accepted immutable input references and actual resulting image. Change the live draft while processing. Verify frozen provenance remains correct and all three garments are present at the agreed quality. Repeat on the held-out evaluation set, not one cherry-picked screenshot.

### Journey J07 — Ambiguous submission and worker recovery

Lose the create response, resend the same request identity, restart a worker at controlled checkpoints and redeliver queue work. Oracle: one accepted logical intent and no duplicate published Look. If provider dispatch outcome is unknown, it is explicitly recorded; no blind re-charge is disguised as recovery.

### Journey J08 — Cancellation and dependency failure

Cancel during queue/preparation/provider/quality stages; simulate scanner failure, S3 failure, provider rate limits and a slow provider. Confirm clear status, preserved draft, bounded retry and accurate spending. Unsupported provider/category must be refused before transferring images.

### Journey J09 — History, export, notification and deep links

Close the app during generation; receive completion notification; reopen after login; view provenance; export/share; clone; delete. A stale or foreign Look deep link must not disclose an image. Denied notification permission must not prevent history recovery.

### Journey J10 — Deletion races and restore

Start generation and an upload, request account deletion, inject one cleanup failure, restart the worker and allow late provider completion. Confirm the deletion barrier holds. Restore a backup into isolation and reapply deletion records before access; previously deleted personal data must not reappear to users.

### Journey J11 — Security boundaries

Try a second user's item/avatar/import/draft/job/Look IDs; malformed images; mismatched MIME; oversized dimensions; expired signed URLs; private/loopback/link-local/metadata URLs and redirect/DNS variants in importer fixtures. Verify consistent non-disclosing errors, no side effects and no leaked secrets in diagnostic output.

### Journey J12 — Release platform quality

Install store-distributed candidates without Metro; test startup, updates, offline/online transitions, text scaling, TalkBack/VoiceOver, small screens, OS back behavior, Android 16 KB devices and iOS extension/IPv6 behavior. Preserve screenshots/videos of actual app state, not mock UI.

### Evidence record for each run

Record:

- source commit and whether there were uncommitted product changes;
- APK/AAB/IPA build/version, checksum and installed distribution source;
- backend container digest, schema version and environment identifier;
- device/OS, test account alias and scenario;
- exact commands, exit codes, test totals and skipped cases where commands were run;
- expected versus observed result;
- redacted logs, request/job IDs and screenshots;
- provider model/prompt version and paid-call/cost count for AI scenarios;
- defect and retest links.

Do not include credentials, raw private photos without consent, signed URLs or provider bodies in general-purpose evidence artifacts.

### Existing verification entry points

From repository root, with Docker available for PostgreSQL Testcontainers:

```powershell
.\backend\mvnw.cmd -f backend\pom.xml verify
```

From `mobile/`:

```text
npm ci
npm run typecheck
npm run lint
npm run test:ci
npm run format:check
```

Existing repository checks include:

```text
python scripts/test_repo_init.py
python scripts/test_local_dev_init.py
python scripts/test_environment_contract.py
python scripts/secret_content_scan.py
```

Run `scripts/test_localstack_resources.py` only with its real LocalStack prerequisites. Keep `VTON_IMAGE_API_KEY` absent from ordinary offline backend test runs: README documents that `InferHubLiveGenerationTest` becomes a paid live call when it is present. Invoke live provider proof deliberately with approved spending and consented data.

These are commands to execute during implementation/release; this guide does not claim they currently pass. Investigate current failures and compare against the named baseline; never weaken an assertion merely to match an old report.

---

## 9. Staging and production deployment procedure

### 9.1 Prerequisites

- Approved cloud account, region and budget.
- DNS ownership, public API hostname and validated regional ACM certificate.
- Firebase project aligned between mobile and backend; auth provider enabled and production account policies configured.
- Approved InferHub processing terms/key and credit limit.
- Real scanner service and network path.
- FCM/APNs credentials if notifications are in release scope.
- Protected Terraform state, deployment identity and production approval mechanism.
- Database recovery policy, retention policy and support ownership.

Do not deploy the existing Terraform unchanged just to “see what happens.” C25 repairs concrete configuration gaps first. Existing file headers say prior slices were code-only; this document is not an instruction to apply infrastructure without approval.

### 9.2 Configuration inventory

Backend current configuration includes these exact environment names in `application.yaml`:

```text
APP_ENV
DB_URL
DB_USERNAME
DB_PASSWORD
AWS_REGION
SQS_GENERATION_QUEUE_URL
S3_AVATAR_BUCKET
S3_VAULT_BUCKET
S3_LOOK_BUCKET
S3_TEMP_BUCKET
FIREBASE_PROJECT_ID
VTON_IMAGE_API_KEY
FASHN_API_KEY
OTEL_SERVICE_NAME
OTEL_RESOURCE_ATTRIBUTES
OTEL_EXPORTER_OTLP_ENDPOINT
OTEL_EXPORTER_OTLP_HEADERS
CLOUDWATCH_METRICS_EXPORT
CLOUDWATCH_METRICS_NAMESPACE
```

Only configure the approved provider. Keep LocalStack endpoint/static-credential overrides out of production; use the AWS task role. Scanner, process-role, refreshable push credentials and consent/deletion wiring require the implementation packages above; do not assume arbitrary new environment names are already consumed.

Public mobile build inputs currently use:

```text
EXPO_PUBLIC_API_URL
EXPO_PUBLIC_FIREBASE_API_KEY
EXPO_PUBLIC_FIREBASE_AUTH_DOMAIN
EXPO_PUBLIC_FIREBASE_PROJECT_ID
EXPO_PUBLIC_FIREBASE_APP_ID
```

Despite those names, this is **bare React Native**, not an Expo/EAS project. The custom Babel inliner owns these values. Do not prescribe `eas build` without a separate deliberate migration, which is unnecessary here.

### 9.3 Safe bootstrap and deployment order

1. Establish remote state and deployment identity outside the app's first service launch dependency loop.
2. Validate region-specific RDS/version/instance availability and provider/toolchain compatibility.
3. Provision approved networking, ECR, buckets, queue/DLQ, RDS, IAM and secret containers with application service counts held at zero where necessary.
4. Build/push the immutable backend image; populate secrets through protected secret tooling, not committed tfvars or CI output.
5. Create least-privilege runtime database credentials and perform the controlled schema migration.
6. Launch required scanner and worker-side dependencies, then API and workers with explicit role settings.
7. Verify TLS, DNS, readiness, database connectivity, S3 capabilities, queue/outbox work, model loading and metrics.
8. Run the full staging product scenario from a phone off the development LAN.
9. Run backup restore, deletion failure recovery and alert exercises.
10. Promote the identical image digest to production with approved environment-specific configuration.
11. Build mobile production candidates targeting the production URL, install through store testing and repeat critical smoke journeys.
12. Submit only after the backend is live, healthy and adequately funded for review.

### 9.4 Cost model before launch

Do not quote old provider comparison prices as current InferHub prices. Measure:

```text
Monthly total = fixed cloud infrastructure
              + stored GB and requests
              + network egress
              + logs/metrics/support services
              + actual paid AI attempts
              + store/developer services

AI cost per usable Look = total paid attempt cost / accepted usable Looks
```

Three sequential garments can require three paid generations plus approved retries. Report cost per **usable outfit**, not cost per successful HTTP call. Include billed failed-quality attempts in total paid cost (the numerator); only accepted usable Looks belong in the denominator. Track uncertain billed outcomes separately until reconciled, and report an estimated cost range if needed. If no usable Looks were produced, report that fact rather than dividing by zero. Set daily spending alerts and a server-side admission pause before granting public access.

### 9.5 Rollback and incident action

- Provider outage/overspend: pause new generation; keep login, Vault, history and deletion available.
- Bad backend image: revert to the previous tested digest only if schema/API compatibility permits.
- Bad mobile release: stop further rollout where supported and ship a signed corrective version; installed copies still exist.
- Database corruption: restore to an isolated target, validate consistency, apply erasure tombstones, then perform an approved cutover.
- Secret exposure: revoke/rotate at the actual provider and redeploy affected services; editing a local env file alone is not revocation.
- Do not delete S3 buckets, reset production volumes, rewrite Flyway history or force Terraform destruction as a rollback shortcut.

---

## 10. Google Play release checklist

### 10.1 Current technical requirements

Official sources retrieved on 2026-10-03 state:

- New apps and updates must target **Android 16 / API 36 or higher from August 31, 2026** [S1]. The project currently sets targetSdkVersion 36; verify the packaged artifact, not just this source constant.
- Apps targeting Android 15+ need 16 KB page-size support on applicable 64-bit devices. The current Android documentation states that updates lacking support cannot be released starting **February 1, 2027** [S2]. This differs from older guidance; do not repeat an obsolete deadline or infer that new noncompliant apps are permitted until then. Test compatibility now.
- Use an Android App Bundle for the new Play listing and Play App Signing [S3]. An AAB is uploaded to Play; generated APK splits are what run on devices.
- Personal developer accounts created after **November 13, 2023** must complete a closed test with at least **12 testers opted in continuously for 14 days**, then apply for production access [S4]. Verify the publisher account's exact eligibility. Completing the test period does not automatically grant production access.

### 10.2 Build and artifact checks

After configuring protected signing inputs, from `mobile/android` on Windows:

```powershell
.\gradlew.bat bundleRelease
```

Expected default artifact location:

```text
mobile/android/app/build/outputs/bundle/release/app-release.aab
```

Validate this path after the actual build; flavors can change it. Use current official bundletool tooling to validate/build APKs from the AAB and install the resulting splits. Inspect:

- release signature and application ID;
- versionCode/versionName;
- API target and supported ABIs;
- all bundled native libraries, including React Native/Hermes and SDK dependencies;
- ELF LOAD and applicable RELRO alignment, ZIP alignment and actual 16 KB runtime behavior;
- no debug/cleartext/LAN configuration in release;
- no provider/signing/service-account secrets in the bundle;
- photo/notification permissions appropriate to real functionality.

### 10.3 Console and policy requirements

Complete:

1. Publisher identity/verification and account requirements.
2. Store listing, icon, real device screenshots, category and accurate description.
3. Privacy policy on an active public, non-geofenced, non-PDF URL, linked within the app; identify the app/developer, contact, processing/recipients, security and retention/deletion practices [S17].
4. Data Safety covering actual app and third-party collection, sharing, purposes, security and deletion [S5].
5. In-app account deletion plus the required external deletion-request resource for apps supporting account creation [S6].
6. Content rating, target audience, ads declaration and app-access/reviewer instructions.
7. Photo/video permission compliance: occasional user selection should use a system picker, not broad media access [S7].
8. Generative AI content safety and in-app reporting [S8]. Google's stated examples include images generated from image prompts, so image-to-image virtual try-on is covered. Users must be able to report offensive output to the developer without leaving the app; an external email link alone does not satisfy that requirement. Verify receipt and operator handling.
9. Internal testing, required closed testing/production access, pre-launch report findings and signed-device validation.
10. Real review account and consented sample assets. No fake provider fallback, missing server or development-only scanner.

### 10.4 Billing, if added later

For a free capped release, omit paid entitlements rather than implementing an unused billing system. If selling AI credits, premium generation or subscriptions, treat them as digital functionality and implement applicable Play Billing policy, server-side purchase verification, replay-safe entitlement grants, refunds/revocations and restoration before advertising them [S9]. Region/program exceptions require their own current review; do not assume a web checkout is universally permitted.

---

## 11. Apple App Store release checklist

### 11.1 Current toolchain requirements

Official Apple requirements retrieved on 2026-10-03 state:

- Since **April 28, 2026**, uploads to App Store Connect must be built with **Xcode 26 or later using the iOS 26 SDK or later** [S10].
- Since **September 9, 2026**, iOS/iPadOS apps uploaded to App Store Connect must target iOS 13 or later [S10]. This is a minimum deployment floor, not a reason to lower React Native's own supported minimum.
- Current age-rating questions must be completed; requirements changed in 2026 [S10].

Pin and record the actual Xcode/macOS combination used. A floating `macos-latest` label is not a reproducible toolchain contract.

### 11.2 Native project and signing

1. Enroll/verify the publishing Apple Developer account and create App Store Connect access with least privilege.
2. Register the main app and `FashionVaultShare` extension identifiers and a matching App Group.
3. Configure distribution signing and provisioning for both targets; validate the embedded extension's entitlements in the archive.
4. Complete supported photo/camera permissions, push capabilities and APNs setup where used.
5. Verify the new photo picker and session persistence work on physical devices.
6. Ensure share extension handoff records are private, bounded, cleaned and processed exactly once logically; the extension must not claim server completion when it only staged data.
7. Review `PrivacyInfo.xcprivacy`: its existing empty collected-data array is not an audit of actual Firebase/photo/backend data practices. Reconcile required-reason APIs and applicable SDK manifests with the archive and App Store Connect disclosures [S11]. Audit actual dependency versions, including Hermes and any listed Firebase SDKs; verify signatures for applicable binary SDK dependencies and generate the Xcode privacy report [S18]. A manifest does not replace privacy labels or the policy.

The existing unsigned command is useful only as a compilation check:

```text
xcodebuild -workspace ios/AIFashionVault.xcworkspace -scheme AIFashionVault -configuration Debug CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO -destination 'generic/platform=iOS' build
```

A signed Release archive/export/upload must be configured separately. Do not invent an ExportOptions file or team ID in a copy-paste script before inspecting the publishing account and Xcode version.

### 11.3 Privacy, consent and review

Apple's current review guidelines require complete, functioning apps with live backend services and reviewer access [S12]. For this project:

- Explain that photographs are processed by a third-party AI service and obtain explicit permission before that sharing. Apple's section 5.1.2(i) specifically addresses sharing with third parties including third-party AI [S12].
- Provide an accessible privacy policy with collection/use/retention/deletion information and contact details.
- Support initiating account deletion inside the app [S13]; deactivation alone is not equivalent.
- Complete App Privacy details for actual data practices and third-party SDKs [S14].
- Do not request App Tracking Transparency permission merely because analytics exists. ATT applies when the actual tracking behavior meets Apple's definition; avoid tracking if it is not needed [S15].
- Email/password-only Firebase authentication does not itself force Sign in with Apple. If qualifying third-party/social login is added, review guideline 4.8 and its exceptions before choosing the login set [S12].
- Digital paid features require the applicable purchase rules; physical retailer purchases are a different category. Implement StoreKit/verification/restoration only if paid digital functionality is in scope [S12].
- Use truthful metadata: no universal retailer support, no fit/size guarantee, no claimed privacy property not established for InferHub and its subprocessors.
- Test IPv6-only networks, permission denial, extension behavior, backgrounding and iPad compatibility as applicable.

### 11.4 TestFlight and submission

1. Upload the signed archive and resolve processing/validation warnings.
2. Install the distributed build via TestFlight; external testing may require beta review [S16].
3. Run the core and full-V1 scenarios applicable to iOS.
4. Provide review credentials, sample garment/photo instructions, explanation of asynchronous generation, third-party processing and how to exercise account deletion.
5. Submit accurate screenshots, privacy/support URLs, age rating and export-compliance responses.
6. Keep the real backend and provider quota available throughout review.
7. Release manually or through the available approved mechanism; phased release availability differs for initial publication versus later updates.

Apple approval cannot be guaranteed by this guide. The deliverable is a technically complete, policy-aligned submission with evidence, followed by honest handling of review findings.

---

## 12. Release gates: conditions that block publication

### Gate A — Core data loop

All of signup, My Model, import confirmation, Vault CRUD and collections work through a release build. Production scanning is real. No required photo or save action is a missing bridge, mock or placeholder.

### Gate B — Genuine outfit generation

The exact chosen top/bottom/footwear outfit is frozen, processed and visible in an acceptable real result. Results survive ordinary restart/replay; provenance and cost are correct. A single garment result does not pass.

### Gate C — Privacy and user trust

Ownership, consent, withdrawal, account deletion, retention, local cleanup and provider terms are resolved. Harmful output can be reported and handled. No retained-data claims contradict the implementation.

### Gate D — Production service

HTTPS public service, private data stores, controlled migration, actual scanner/provider, bounded cost, delivery credentials, actionable alerts, restore and rollback have current evidence.

### Gate E — Platform release

The exact signed distribution artifact is tested on the target platform. Google Play/Apple requirements and console declarations are complete. Reviewers can use the real app.

### Gate F — Full-V1 claim

All declared V1 features—including C16 export/share, C20 notifications, C21 basic natural-language control and C22 analytics—have evidence if “all V1 features working” is the claim. A consciously narrower first release must be labelled accordingly.

### Gate G — Published product

The app is approved and installable from the target store(s), production smoke works, operators are monitoring and there are no known blocking defects. Uploading an AAB/IPA is not publication, and publication is not proof of future-roadmap completion.

---

## 13. Completing the extended PRD roadmap

These are implementation outlines for the PRD's later phases, not permission to declare them built or requirements to rewrite the completed core. Keep them in the completion ledger so “whole project” has an explicit boundary.

### R01 — Better previews and generation variations

Reuse durable generation jobs and source snapshots. Represent each variation as a separate output linked to the same approved input intent, with explicit cost and selection semantics. Validate identity/garment consistency per variant. Do not simulate intermediate visual previews when the provider only returns a final image. Add real intermediate previews only when a durable, privacy-safe result exists.

**Completion:** users request/select variants, understand cost, reopen them and delete them; failed variants do not corrupt the original Look.

### R02 — Recommendations and styling assistant

Start with explicit user preferences and owned Vault metadata; add matching suggestions through the same constrained Studio commands. Obtain consent for persistent preference learning. Let users inspect/reset preferences. Avoid attractiveness scoring, sensitive-trait inference and presenting fashion suggestions as objective truth.

**Completion:** suggestions reference available owned items, respect exclusions and can be rejected without mutating the draft. Measure usefulness with users before adding more model infrastructure.

### R03 — Multi-avatar and alternate poses

The current launch only needs one active model. Extend profile selection deliberately: named poses, active profile, per-image consent/revision and generation snapshot binding. Never change old Look provenance when the active avatar changes.

**Completion:** users manage several consented photos/poses and reliably select which one generated a Look; deletion and storage limits cover every pose.

### R04 — Affiliate links and price-aware commerce

Obtain retailer/affiliate agreements and allowed data sources. Keep original source URLs separate from generated affiliate URLs and disclose commercial relationships. Price/stock data needs freshness timestamps and “unavailable” behavior; do not scrape around authentication or promise checkout inventory you cannot verify.

**Completion:** legitimate attributed click-through works, disclosures are visible, stale prices are not presented as guaranteed and failures preserve the saved garment.

### R05 — Social sharing, feeds and public wardrobes

Treat this as a new privacy/moderation boundary, not a flag on private S3. Add explicit publish/unpublish, audience choices, blocking/reporting, content moderation, takedown operations and cache/CDN revocation. Design deletion across original posts, cached copies and social metadata. Revisit minor safety, age rating and store UGC rules.

**Completion:** private items cannot leak into feeds, users can block/report/unpublish, operators can handle abuse and deletion works across public projections. A native share sheet alone does not implement a social network.

### R06 — Retailer partnerships and in-app catalogs

Use authorized feeds/APIs, stable product identity, synchronization, deduplication, rights management, deletion and stock/price freshness. Keep catalog records separate from a user's saved media and personalized Looks. No retailer-specific authentication bypass or catalog clone is required for the current share-first product.

**Completion:** imported catalog data has a known source/license, updates safely, and stale/unavailable products are represented honestly.

### R07 — Personalized style Q&A

Build on R02 and constrained owned-context retrieval. Define what history is stored, how it is erased and which text provider receives it. Separate conversational suggestions from mutation and spending. Do not claim health/body/fit expertise.

**Completion:** answers use real available wardrobe context, explain missing information, respect privacy settings and never execute unapproved changes.

### R08 — AR try-on

This is not a small extension to still-image generation. First run a device prototype measuring tracking, occlusion, frame rate, thermal load and realistic garment representation. Select supported devices/assets only after evidence; camera-frame privacy and retention need a new review.

**Completion:** actual supported devices meet agreed visual/performance targets and the feature is honestly labelled. A static generated image over a camera preview is not an AR try-on implementation.

### R09 — Paid tiers, credits and subscriptions, if selected

Implement store products, server verification, replay-safe entitlement ledger, renewal/refund/revocation handling, restoration, support and deletion interactions. Bound AI costs per entitlement. Keep physical product shopping separate from digital generation purchases.

**Completion:** sandbox and production purchase paths grant exactly the intended access, duplicate notifications cannot double-credit, refunds revoke correctly and users can restore eligible purchases.

### Extended-roadmap scheduling

- R01/R03 can follow stable generation/provenance.
- R02 precedes R07.
- R04 requires approved commerce partnerships; R06 builds on explicit catalog rights/contracts.
- R05 requires a dedicated privacy/moderation release gate.
- R08 begins with a feasibility decision, not a promised deadline.
- R09 only enters the implementation plan when monetization is approved.

If the owner means literally every future PRD idea by “whole project completed,” those external contracts and feasibility gates are part of completion. No honest engineering guide can certify undeveloped partnerships or unproven AR as ordinary remaining UI work.

---

## 14. Suggested first implementation milestone

The first milestone should not be “upload a build to the store.” It should be:

> A new user installs a standalone Android build, creates an account, chooses a real photo, creates My Model, imports a garment, previews and saves it, creates a collection, and sees the garment image after restarting the app.

Implement C01–C09 to prove this. In parallel, prepare cloud/provider/privacy prerequisites and begin durable generation work where interfaces are stable.

The next milestone is top + bottom + footwear end to end (C10–C15/C18/C19), then privacy/full-V1 closure and production/store release. This sequence exposes product-breaking seams early instead of spending the first week polishing screenshots of disconnected screens.

Do not start by:

- rerunning every historical slice;
- replacing Spring Boot or React Native;
- adding more provider abstractions;
- enabling all feature flags;
- using development's synthetic scanner in production;
- buying cloud capacity before reviewing current Terraform wiring and cost;
- creating store screenshots before the real journeys are complete;
- declaring success because an agent ledger has no open tickets.

---

## 15. Official publication and platform sources

Retrieved/reviewed for this guide on 2026-10-03. Store policy changes over time; recheck the official pages and the actual account console immediately before submission. Account-specific requirements and jurisdiction-specific legal conclusions remain external review items.

- **S1 — Google Play target API:** https://support.google.com/googleplay/android-developer/answer/11926878?hl=en
- **S2 — Android 16 KB page support:** https://developer.android.com/guide/practices/page-sizes
- **S3 — Android App Bundle / signing:** https://developer.android.com/guide/app-bundle and https://support.google.com/googleplay/android-developer/answer/9844279?hl=en
- **S4 — Google personal-account testing:** https://support.google.com/googleplay/android-developer/answer/14151465?hl=en
- **S5 — Google Data Safety:** https://support.google.com/googleplay/android-developer/answer/10787469?hl=en
- **S6 — Google account deletion:** https://support.google.com/googleplay/android-developer/answer/13327111?hl=en
- **S7 — Google photo/video permissions:** https://support.google.com/googleplay/android-developer/answer/16935362?hl=en
- **S8 — Google AI-generated content:** https://support.google.com/googleplay/android-developer/answer/13985936?hl=en
- **S9 — Google payments policy:** https://support.google.com/googleplay/android-developer/answer/9858738?hl=en
- **S10 — Apple upcoming/current requirements:** https://developer.apple.com/news/upcoming-requirements/
- **S11 — Apple privacy manifests:** https://developer.apple.com/documentation/bundleresources/privacy-manifest-files
- **S12 — Apple App Review Guidelines:** https://developer.apple.com/app-store/review/guidelines/
- **S13 — Apple in-app account deletion:** https://developer.apple.com/support/offering-account-deletion-in-your-app/
- **S14 — Apple App Privacy details:** https://developer.apple.com/app-store/app-privacy-details/
- **S15 — Apple user privacy / tracking:** https://developer.apple.com/app-store/user-privacy-and-data-use/
- **S16 — TestFlight:** https://developer.apple.com/testflight/
- **S17 — Google user-data and privacy policy:** https://support.google.com/googleplay/android-developer/answer/10144311?hl=en
- **S18 — Apple third-party SDK requirements:** https://developer.apple.com/support/third-party-SDK-requirements/

### Legal and provider limitations

The older PRD contains statements about DPDP, India-only storage, provider retention and no-training commitments. Do not copy them into a public privacy policy without current legal/provider verification. Indian privacy obligations, effective dates, children's-data rules, cross-border restrictions and grievance processes must be reviewed against current official law and the actual target audience. Neither storing data in India nor sending Base64 instead of a URL independently proves legal compliance or zero retention.

InferHub is the currently chosen integration, but this guide did not verify a customer data-processing agreement or its downstream provider chain. That is a release dependency, not a reason to quietly switch vendors.

---

## 16. Final completion checklist

- [ ] C01: Current contracts, scope and baseline are truthful.
- [ ] C02: Standalone build configuration is verified.
- [ ] C03: Signup, login, recovery, persistence and signout work.
- [ ] C04: Real native camera/library acquisition works on target platforms.
- [ ] C05: Production scanning and safe uploads work.
- [ ] C06: My Model and enforced consent work.
- [ ] C07: Share/import preview and actual confirmation save work.
- [ ] C08: Image-backed Vault CRUD works.
- [ ] C09: Collection CRUD and membership work.
- [ ] C10: Owned revisioned Studio sessions persist.
- [ ] C11: Mobile Studio edits and restores actual outfits.
- [ ] C12: Multi-item immutable generation intent is connected.
- [ ] C13: Provider/intermediate results survive restart safely.
- [ ] C14: Progress, cancellation and restart recovery work.
- [ ] C15: Looks, provenance, reuse and deletion work.
- [ ] C16: Explicit export/share and source links work for full V1.
- [ ] C17: Durable privacy/deletion and data-request handling work.
- [ ] C18: AI quality, safety and reporting gates pass.
- [ ] C19: Quotas, cost accounting and admission controls work.
- [ ] C20: Real device notifications and deep links work for full V1.
- [ ] C21: Basic constrained natural-language Studio works for full V1.
- [ ] C22: Analytics/capabilities reflect actual behavior.
- [ ] C23: Actual-device usability and accessibility pass.
- [ ] C24: Immutable production backend image is verified.
- [ ] C25: Isolated AWS staging is operational.
- [ ] C26: Controlled deployment/migration/rollback works.
- [ ] C27: Security, alerts and restore exercises pass.
- [ ] C28: Google Play release artifact and testing gates pass.
- [ ] C29: Apple release artifact and testing gates pass if iOS is targeted.
- [ ] C30: Publication and monitored production installation are verified.
- [ ] Extended roadmap R01–R09 is explicitly tracked; unimplemented items are not labelled complete.

**Completion means observable user outcomes on the distributed app and operating backend—not just code present in the repository.**
