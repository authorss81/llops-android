# Phase 231 — Add the runtime nested-scroll guard (debug-only diagnostic)

## Goal
Implement the runtime guard sketched in `workspace/phase-229/DYNAMIC_GUARD.kt` into the app, wired at the root composition, so future nested-scrollable regressions are caught in debug builds with a clear `Log.w` (or assertion) before they ship and crash on tablets.

## Context (from phase-229 research)
All 48 scrollable sites are currently bounded. The ONE confirmed crash (EditorScreen.kt:4493) was fixed. To prevent regression, add a **debug-only** runtime guard that detects a vertical scrollable being composed while already inside an unbounded-height vertical scroll parent.

`BuildConfig.DEBUG` is already enabled (`app/build.gradle.kts:139 buildConfig = true`).

## Implementation

### 1. Create `app/src/main/kotlin/com/authorss81/noteflow/utils/NestedScrollGuard.kt`
Port the sketch (trimmed, compileable, release-no-op):
- `NestedScrollGuardConfig.enabled = BuildConfig.DEBUG`
- `enterUnboundedScroll()/exitUnboundedScroll()` — ThreadLocal depth counter; on depth>1 in debug, `check()` throws with a guidance message; in release, no-op entirely.
- `LocalNestedScrollGuard` CompositionLocal + `NestedScrollGuardProvider`.

**CRITICAL: release-no-op.** Because `enabled` is `BuildConfig.DEBUG` (a `static final boolean`), the JIT/AGP will dead-code-eliminate the entire guard body in release. Zero release cost, zero release risk.

### 2. Wire provider at root
In `MainActivity.kt`, wrap the root composition inside `NoteflowTheme { ... }` so the guard is active for every screen. Find where `NoteflowTheme` is invoked in `MainActivity.kt` (search `NoteflowTheme(`) and wrap with `NestedScrollGuardProvider { ... }`.

### 3. Add a unit test
`Phase231NestedScrollGuardTest` (pure-JVM, Robolectric not needed — the guard's ThreadLocal logic is testable):
- Assert `NestedScrollGuardConfig.enabled == false` when simulating release (can't change BuildConfig easily; instead test the logic by temporarily toggling `NestedScrollGuardConfig.enabled` since it's a var)
- Test that nesting >1 depth throws / logs
- Test that balanced enter/exit resets depth to 0

## Verification
- `gradle testDebugUnitTest` green (Phase231NestedScrollGuardTest green; 1 pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure untouched)
- `gradle assembleDebug` green
- Guard is a no-op in release (verify `NestedScrollGuardConfig.enabled = BuildConfig.DEBUG` and the guard body is gated on it)

## DoD
- `NestedScrollGuard.kt` created + wired at root in MainActivity
- `Phase231NestedScrollGuardTest` green
- `gradle testDebugUnitTest` green + `gradle assembleDebug` green
- `workspace/phase-231/REPORT.md` written
- Update docs as appropriate

## Timeout
180 minutes
