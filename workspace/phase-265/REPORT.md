# Phase 265 — Perf: frame pump guard + profile pipeline + budgets — REPORT

## 1. Claim / reality / status / evidence

| # | Claim (PROMPT) | Reality at HEAD | Status | Evidence (file:line) |
|---|---|---|---|---|
| H1 | `WetBrushFramePump.start()` early-returns below TIRAMISU → API 26-32 never arms the pump, `recordFrameTime`/`updateTierAndFallback` dead, EMA stuck 16.6ms, thermal never degrades | Confirmed pre-fix: `if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return` as the first statement of `start()` | FIXED | `ui/components/WetBrushFramePump.kt:81-83` (pre-fix); post-fix `start()` has no `SDK_INT`/`TIRAMISU` reference at all (`:81-99`), unused `android.os.Build` import removed (`:1-3`) |
| M1 | Toolchain wired but empty: no committed `baselineProfiles/*.txt` → `compile*ArtProfile` always disabled; `android.yml` skips llops-bot so signed fullMode+shrinkResources never proven on CI | Confirmed: zero `.txt` under `app/src/main/baseline-prof.txt` or `app/src/main/baselineProfiles/`; `.github/workflows/` untouched per constraint (no CI edit made — documented instead) | DOCUMENTED (guard kept) | `app/build.gradle.kts:250-263` guard intact; runbook comment `:265-284`; `baselineProfile { from(project(":baselineprofile")) }` `:221-223`; `isMinifyEnabled/isShrinkResources = true` `:110/:120` |
| M2 | Single 16-layer page ≈166MB resident vs 64MB budget by design (protected-page no-evict) + pool 64 + corpus 32 → ~230MB peak | Confirmed by design: protected page survives unconditionally, budget applies to everything else | KEPT + PINNED (threshold change is phase-269's job, not this phase's) | `services/LayerRenderBudgetPolicy.kt:66-78` (two-tier KDoc + 166MB arithmetic), `:149-169` `resolveProtectedEviction`; `ui/components/LayerBitmapLruCache.kt:91-110`; `MainActivity.kt:1351-1361` trim/low clears; `NoteflowViewModel.kt:5068` lock clear |
| L1 | `gradle.properties:22 configuration-cache=false` vs `build.gradle.kts:218` comment claiming true (doc drift) | NO DRIFT at HEAD: `gradle.properties` already sets `=true`; the build comment claiming true is correct | VERIFIED, clarifying comment added | `gradle.properties:22-26` (value + phase-265 note); `app/build.gradle.kts:217` `--no-configuration-cache` producer-only exception |
| L2 | `DecryptedPageCache` 32MB chars heavy on 256MB heap | Bounded LRU (1024 entries / 32M chars), graceful eviction, cleared on lock/re-key — kept as designed | KEPT (no change) | `data/repository/DecryptedPageCache.kt:43-46,98-105,107-112` |

## 2. Changes

1. **`ui/components/WetBrushFramePump.kt`** — removed the `SDK < TIRAMISU` early-return
   from `start()` + the now-unused `android.os.Build` import; KDoc records why
   (Choreographer since API 16, minSdk 26; no API-33-only call on this path).
   Active-gate re-post, stale-first-delta reset, and ≤1 Hz thermal sampling untouched.
2. **`app/build.gradle.kts`** — generation runbook + keystore-less proof comment
   (`:265-284`): `generateBaselineProfile --no-configuration-cache` (device-only),
   commit profile + rebuild after; `minifyReleaseWithR8`/`lintRelease` are outside
   `RELEASE_SIGNING_TASK_NAMES` so they prove R8 fullMode + shrinkResources
   (`mapping.txt`) with no keystore. Guard logic itself untouched.
3. **`gradle.properties`** — comment-only: `configuration-cache=true` confirmed
   correct, producer-only `--no-configuration-cache` exception documented.
4. **`app/src/test/java/com/authorss81/noteflow/Phase265PerfTest.kt`** (new, 4 tests):
   pump-no-guard pin; guarded-pipeline + runbook + minify/shrink + fail-closed-signing
   pins; trim/low/lock pool-clear + two-tier + 16-layer pins; config-cache no-drift pin.
5. **`Phase206EventDrivenTimersTest`** — the `start() must no-op below API 33` pin
   enshrined the H1 bug as correct; flipped to `assertFalse(TIRAMISU)` with a
   phase-265 note. All other phase-206 pins (unregister, gated re-post, ≤1 Hz,
   DisposableEffect ownership, remember keys) untouched and green.
6. **Docs** — `docs/ARCHITECTURE.md` phase-265 note + gotcha-7 refresh (guarded,
   not "disabled"); `docs/phase-status.md` phase-265 row.

## 3. Verification (DoD)

- `gradle :app:assembleDebug` — BUILD SUCCESSFUL.
- `gradle :app:testDebugUnitTest` — **3799 unique testcases / 0 failures / 0 errors**
  (phase-264 baseline 3794 + 4 new `Phase265PerfTest`; the residual +1 vs the
  reported baseline is count-method variance on this runner — the total above is
  a de-duplicated `(classname, name)` count over the result XMLs, all green
  including the previously-flaky-on-Windows `Phase148UiFailureTextScrubTest`).
- `gradle :app:lintDebug` — BUILD SUCCESSFUL, **0 errors** in `lint-results-debug.xml`.
- Constraints: no Room schema change, no new dependencies (pure Kotlin/JVM test
  only), no `.github/workflows/` edits, `allowBackup="false"` untouched,
  `verification-metadata.xml` untouched, fail-closed release signing intact.

## 4. What was deliberately NOT done (hand-off)

- **No `onTrimMemory` threshold change.** Phase-269's PROMPT owns the
  RUNNING_LOW/MODERATE gap (`MainActivity.kt:1353` still gates on
  `TRIM_MEMORY_BACKGROUND`/`RUNNING_CRITICAL`); this phase only pins the
  existing clears. Do not "fix" it here — that would steal phase-269's scope.
- **No CI workflow edit.** The unsigned `assembleRelease`/mapping check the PROMPT
  sketches would need `.github/workflows/` + a bot token with `workflows`
  permission; per constraint it is documented as a local keystore-less proof
  (`minifyReleaseWithR8` → `mapping.txt`) instead.
- **No profile generated.** Needs a connected device/emulator; the guarded
  disable stays until a maintainer lands one via the runbook.
