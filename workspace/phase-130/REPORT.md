# Phase 130 REPORT — FLAG_SECURE gated on `!BuildConfig.DEBUG` (verify + pin)

**Status:** DONE
**Date:** 2026-08-18
**Type:** user-requested UI/UX feature (pre-seeded `7a4b6e6`).

## Intent / context

AGENTS.md hard rule — "FLAG_SECURE is applied in non-debug builds". Phase-60
(B1-PLAT-4) had made the flag UNCONDITIONAL. Phase-130 reverses that: the flag
is applied **only when `!BuildConfig.DEBUG`**, so debug / cloud-emulator
streaming environments (which mirror the display buffer) render the UI instead
of a pitch-black surface, while release builds keep the screenshot / recording /
recents-thumbnail ban.

## What changed

### 1. Pure-JVM decision table — `services/SecureWindowPolicy.kt` (NEW)
- `object SecureWindowPolicy` with the single decision
  `fun shouldApplySecureFlag(debug: Boolean): Boolean = !debug`
  (`app/src/main/kotlin/com/authorss81/noteflow/services/SecureWindowPolicy.kt:30`).
- KDoc documents the AGENTS.md hard rule + the exact wiring used by
  `MainActivity`.

### 2. MainActivity wiring — `MainActivity.kt:144-146`
The phase-60 unconditional

```kotlin
window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
```

is replaced by

```kotlin
if (com.authorss81.noteflow.services.SecureWindowPolicy.shouldApplySecureFlag(BuildConfig.DEBUG)) {
    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
}
```

- `BuildConfig.DEBUG` resolves to the generated
  `com.authorss81.noteflow.BuildConfig` (same namespace as `MainActivity`, no
  import needed); the `buildConfig` feature is ENABLED
  (`app/build.gradle.kts:89` `buildFeatures { buildConfig = true }`), so
  `BuildConfig.DEBUG` is generated for both variants.
- Debug (`BuildConfig.DEBUG == true`) → `shouldApplySecureFlag(true) == false`
  → flag NOT applied → cloud-emulator streaming renders the UI.
- Release (`BuildConfig.DEBUG == false`) → `== true` → flag applied.

### 3. Repo-wide source grep — no other FLAG_SECURE code path
`rg "FLAG_SECURE" app/src/main -g '*.kt'` yields **only**:
- `MainActivity.kt:138-146` — the comment + the ONE `window.addFlags` call,
  inside the non-debug guard;
- `SecureWindowPolicy.kt` — KDoc prose.

There is no unconditional `addFlags(FLAG_SECURE)` anywhere, no
`clearFlags(...FLAG_SECURE)` carve-out anywhere, and the only other `.addFlags`
in the app is the unrelated `plugins/export/ExportEnginePlugin.kt:70`
(`Intent.FLAG_GRANT_READ_URI_PERMISSION`, an intent flag, not a window flag).

### 4. R8 / minify — release keeps the flag
- `onCreate` is an Activity lifecycle entry point: AGP's default keep rules
  (`proguard-android-optimize.txt` + AGP application keep rules) retain the
  method, so the `window.addFlags(...)` call survives minification inside it.
- `BuildConfig.DEBUG` is a compile-time constant substituted by R8: in the
  release variant it is `false`, so `!debug` folds to `true` and the branch is
  always taken — the flag is applied on every release launch. No
  `proguard-rules.pro` change required (verified by reviewed minimization
  semantics; release build itself is not run in this phase because the
  B1-PLAT-1 fail-closed keystore gate requires the external
  `RELEASE_KEYSTORE_B64`/`KEYSTORE_FILE` environment documented in `docs/RELEASE.md`).

## Tests — `B1Plat04AutoLockTest` (11, was 10)

- NEW `SecureWindowPolicy decision is debug-absent and release-present`:
  pure-JVM decision-table test — `shouldApplySecureFlag(debug = false)` is
  `true` (release applies the flag) and `shouldApplySecureFlag(debug = true)` is
  `false` (debug does not).
- UPDATED `FLAG_SECURE is applied only under the non-debug BuildConfig guard`:
  source-level pin that (a) `window.addFlags` appears EXACTLY ONCE in
  `MainActivity.kt`,
  (b) the guard `if (com.authorss81.noteflow.services.SecureWindowPolicy.shouldApplySecureFlag(BuildConfig.DEBUG))`
  is present, (c) the addFlags call sits INSIDE that guarded block (proof via
  guard-block extraction), and (d) no `clearFlags(...FLAG_SECURE)` carve-out
  exists. This supersedes the phase-60 pin that *forbade* a debug gate.
- Class KDoc updated to document the phase-60 → phase-130 lineage honestly.

## Verification

- `gradle :app:testDebugUnitTest --tests "*B1Plat04AutoLockTest"` — green (11/11).
- `gradle testDebugUnitTest` (all modules) — **1842 tests, 0 failures, 0 errors,
  0 skipped** (app 1792 + `plugins/llm`; `plugin-sdk` has no unit tests).
- `gradle assembleDebug` — **BUILD SUCCESSFUL** (debug APK built; `:app:packageDebug`
  + `:app:assembleDebug` green).

## Constraints honoured

- NO DB schema change, no migration, no new dependencies.
- `.github/workflows/` untouched.
- `allowBackup="false"` untouched. `ClipboardGuard` untouched.
- Nothing logs keys / passwords / decrypted content; the change touches only the
  window-flag decision.

## Doc updates

- `docs/ARCHITECTURE.md` — "Implemented in phase-130" note appended to the
  ViewModel/nav section (supersedes the phase-60 "applies FLAG_SECURE
  unconditionally" bullet).
- `docs/phase-status.md` — phase-130 row flipped `NOT STARTED` → `DONE` with
  evidence.