# MVP Baseline — gates, skips, native statuses, owning tickets

**Ticket:** PR-01 — truthful baseline and contract inventory (plan:224-241).
**Baseline commit:** `8e60d2ab47d5f599781cb74096442d665d827af8` (clean checkout).
**Scope:** what runs today, what does not, and who owns each gap. Mounted routes,
stores and per-path gaps live in [docs/CONTRACT_INVENTORY.md](CONTRACT_INVENTORY.md);
this file records gate commands, results, skip classification, native statuses and
failing boundaries. It repairs nothing.

Labels: **VERIFIED-IN-TEST** (executed by a frozen controller check on this
identical base commit, exit 0, evidence id cited) · **SOURCE-CONFIRMED** (source at the baseline commit) · **NOT-EXECUTED** (no run evidence here; never read as a pass).

## 1. Gate commands and recorded results

```text
# backend, repository root — Docker required: postgres:18 Testcontainers, Flyway V001–V023
backend\mvnw.cmd -f backend\pom.xml verify     # Windows
./backend/mvnw -f backend/pom.xml verify       # macOS/Linux

# mobile, from mobile/
npm ci && npm run typecheck && npm run lint && npm run test:ci

# repository scripts, repository root
python scripts/test_repo_init.py && python scripts/test_local_dev_init.py
```

Recorded controller-check evidence (baseline scope — captured by run hm-73311f2dcf264527b1b1722d6d82983b on this identical base commit/tree; ids resolve in that run's evidence store):

| check | exit | wall | evidence id |
|-------|------|------|-------------|
| setup-1 `npm ci --prefix mobile` | 0 | 47.7 s | e-0bba72abd428437e84041ad96f17f300 |
| backend-verify | 0 | 409.7 s | e-2245eb1da43f44a2a9c3b0ceb752be6f |
| mobile-typecheck | 0 | 8.8 s | e-7dc037e8a5454335bf69f3f6e7643851 |
| mobile-lint | 0 | 65.3 s | e-c5e4ceaeb0c44df1ad7c61639d83f2bd |
| mobile-jest | 0 | 57.5 s | e-2d7a1e38c69e42ddadfef45a0e87ec6a |
| repo-init-tests | 0 | 5.8 s | e-2310216f0d6442a8be333f1d42d2d357 |
| local-dev-init-tests | 0 | 4.2 s | e-f4ac3b1a19e048eb8b62051cb600ffed |

Backend result line: **`Tests run: 1917, Failures: 4, Errors: 350, Skipped: 0`
(BUILD FAILURE)** — measured with `cmd.exe /c mvnw.cmd clean verify` in run
vtonfixB against the integrated tree at `deea9e3`, not against the `8e60d2a`
baseline this file describes. The 350 errors decompose into exactly two disjoint
root causes: 31 classes `NoUniqueBeanDefinitionException` and 5 classes
`BeanDefinitionOverrideException` (the second is test-side only); the 4 failures
are exactly one class, `com.aifashionvault.avatar.AvatarWorkflowTest`. This
number is expected to move — tickets t201 and t202 were in flight against it at
the time of measurement — so read it as "measured at `deea9e3`", never as a
standing green. The 1881/0/0 line this replaces did not exist at `3b6af1f`.
Suite scale at this commit: 186 of the 193 `.java` files under
`backend/src/test/java` contain `@Test` (1834 `@Test` methods); 42 reference
`PostgreSQLContainer`. Mobile: `Test Suites: 17 passed, 17 total` / `Tests: 269
passed, 269 total` — 269 confirmed by an independent re-run in run vtonfixA
(10.5 s, exit 0); the 243 figure previously recorded here does not match the
suite at this tree.

**Host-pace note.** The same `backend-verify` command recorded 184.7 s in run
`hm-f57a855e925c4ee4862af8cf5d5a92b8` on this host; both runs exited 0.
**mobile-jest deviation:** argv appends `-- --testTimeout=15000` because the
`ShareImport.test.tsx` cold-start case exceeded jest's default 5000 ms budget here
(prior run: 1/243 failed, suite wall 133–139 s; that file alone passed 27/27 in 35 s).
No suite or assertion changed — discovery, count and expectations match CI.

## 2. Skip classification

- **Backend: zero skip mechanisms.** No `@Disabled`, `@Tag`, `@Ignore`,
  `Assumptions.assumeTrue`/`assumeFalse` or `@EnabledIf` in `backend/src/test`;
  `backend/pom.xml` declares no `skipTests`, `maven.test.skip` or `<excludes>`; the
  run reports `Skipped: 0`. Docker absence therefore **fails** the build —
  Testcontainers has no skip path, so there are no silent infrastructure skips.
- **Mobile: zero skip mechanisms.** No `.skip`/`.todo`/`xit`/`xdescribe` and no
  `--testPathIgnorePatterns`; all 17 suites are discovered and run in-band.
- **`format:check` (`prettier --check .`) is a known pre-existing condition, not a
  pass.** It is a CI step in `.github/workflows/mobile-init.yml` (`linux-node`,
  `windows-node`) and is **not** in this run's frozen contract, so it was not
  executed here; style findings already in the tree are recorded as pre-existing and
  unverified. Cleanup is owned by **PR-21** (plan:590). `npm run lint` exits 0 with
  8 pre-existing warnings (`no-void`, `react/no-unstable-nested-components`) —
  warnings, not errors, unchanged by PR-01.

## 3. Native build status per platform

| platform | wiring | status |
|----------|--------|--------|
| android | `mobile/android` Gradle project; CI job `linux-android` runs `chmod +x android/gradlew` then `./gradlew assembleDebug` | **NOT-EXECUTED** on this Windows host (wired, not built here) |
| ios | `mobile/ios/AIFashionVault.xcodeproj`; CI job `macos-ios` runs `pod install` then `xcodebuild -workspace ios/AIFashionVault.xcworkspace -scheme AIFashionVault -configuration Debug CODE_SIGNING_ALLOWED=NO CODE_SIGNING_REQUIRED=NO -destination 'generic/platform=iOS' build` | **NOT-EXECUTED**; the job is commented "Best-effort, not a required PR gate" and is `if:`-gated to same-repository PRs, so it is not a required gate. No iOS build evidence is claimed on this Windows host. |

## 4. Existing failures and gaps, with owning tickets

| id | gap at this baseline | owning ticket(s) | evidence level |
|----|----------------------|------------------|----------------|
| F01 | No real photo acquisition: `takePhoto()` and `choosePhoto()` both return `null` (`mobile/src/features/my-model/photoSource.ts:25,:29`) | PR-05 | SOURCE-CONFIRMED |
| F02 | Mounted API clients are unconfigured: eight `baseUrl: ''` + null-token mounts under `mobile/src` | PR-04 | SOURCE-CONFIRMED |
| F03 | Studio cannot build the requested outfit; readiness derives from route parameters, no persisted session | PR-12, PR-13, PR-16 | SOURCE-CONFIRMED |
| F04 | A resolved share cannot become a saved item through the screen (no confirmable import contract) | PR-08, PR-11 | SOURCE-CONFIRMED |
| F05 | Import replay checks already-consumed staging before idempotency can replay | PR-07 | SOURCE-CONFIRMED ordering defect at the `8e60d2a` baseline; **FIXED in the integrated tree** — `ShareImportService.java:55` arbitrates first and the replay branch re-checks claimability at `:68` before resolving at `:69`, the fresh path at `:71` |
| F13 | Test labels overstated integration coverage (the mock-based first-vertical-slice suite is orchestration coverage) | closed for this label by PR-01's reclassification; PR-21, PR-23 | SOURCE-CONFIRMED and EXECUTED |
| F14 | Canonical docs still described reserved/deferred paths | closed by PR-01's doc rewrite; each feature PR updates its own behaviour | SOURCE-CONFIRMED |

F06–F12 and F15–F17 remain open with their owning tickets in
[docs/CONTRACT_INVENTORY.md](CONTRACT_INVENTORY.md) §7.

**Correction (vtonfixB, t207).** This section previously claimed that "six
frozen checks are baseline-fail by design", naming `readme-truth`,
`canonical-docs-truth`, `contract-inventory`, `test-reclassification`,
`baseline-doc` and `sanity-workflow-truth`. **Those six identifiers are
fictional in this repository.** At `3b6af1f`, `git grep -l <id>` returns exactly
one file for each of the six — this document itself — so there is nothing to run
and nothing that can be "baseline-fail". `scripts/` at this SHA holds 15 files
(`backend_init`, `local_dev_init`, `mobile_init`, `repo_init`, `run_backend`,
`secret_content_scan` and their `test_*` / `test_environment_*` companions) and
contains none of them. The ids belong to run
`hm-73311f2dcf264527b1b1722d6d82983b`'s evidence store, the same past-run scope
that §1:27 already establishes; naming them in the present tense implied a
runnable gate that does not exist. The checks that are runnable in this tree are
the ones in §1:15-25 — `backend\mvnw.cmd -f backend\pom.xml verify`, the mobile
`npm run typecheck` / `lint` / `test:ci`, and
`python scripts/test_repo_init.py && python scripts/test_local_dev_init.py`.

## 5. Reproducible probes for the failing boundaries

Run from the repository root. These are baseline **failing-boundary** evidence,
recorded before PR-04/PR-05 fix them; plan:234 forbids merging permanently failing
required tests in this documentation PR.

```text
grep -n "return null" mobile/src/features/my-model/photoSource.ts
#   25: takePhoto() -> null      29: choosePhoto() -> null
grep -n "baseUrl: ''" mobile/src/features/generation/generationApi.ts
#   126: baseUrl: ''   (127: getAccessToken: () => null)
grep -n "baseUrl: ''" mobile/src/features/vault/vaultApi.ts
#   23: const config = { baseUrl: '', getAccessToken: () => null };
```

Boundary behaviour: the photo source returns `null` unconditionally, so every caller
of the picker receives no photo (F01). The mounted generation and vault clients are
built with an empty `baseUrl` and a null token, so requests issued from the mounted
app cannot reach the backend (F02). PR-04/PR-05 add and close the corresponding
regression tests alongside their source fixes.

## 6. What this file does not claim

No whole-suite pass is inferred from focused runs — only the §1 rows are pass claims.
No native build result is claimed (android and ios are wired in CI, NOT-EXECUTED here),
and no `format:check` result (§2). No defect above is repaired by PR-01; each stays with
its owning ticket.
