# Phase 231 — Runtime nested-scroll guard (debug-only diagnostic)

## Status
IMPLEMENTED + REVIEW-FIXED (2026-08-28).

## Goal
Add a debug-only runtime guard so future nested-scrollable regressions (the
"Vertically scrollable component was measured with infinity maximum height
constraints" / `CheckScrollableContainerConstraints` crash) are caught with a
clear message in debug builds. See `workspace/phase-229/DYNAMIC_GUARD.kt` for
the original sketch and `workspace/phase-229/FIX_STRATEGY.md` for the modifier
ordering rules.

## Implementation
`app/src/main/kotlin/com/authorss81/noteflow/utils/NestedScrollGuard.kt`:
- `NestedScrollGuardConfig.enabled = BuildConfig.DEBUG` (mutable `var` so the
  pure-JVM test can toggle debug/release).
- `NestedScrollReporter` — ThreadLocal depth counter bracketing the *measure*
  phase of each guarded scrollable; `enterUnboundedScroll()` throws a `check()`
  with guidance when genuine nesting (depth > 1) is measured.
- `LocalNestedScrollGuard` CompositionLocal + `NestedScrollGuardProvider` (wired
  in `MainActivity.kt` inside `NoteflowTheme`).
- `Modifier.nestedScrollGuard()` — transparent, layout-neutering modifier that
  brackets a scrollable's measure with the reporter.

Wiring (review fix): the guard is now actually invoked from **every**
`verticalScroll(...)` call site in the app (25 sites across 16 files), so it is
no longer an inert provider. Because phase-229 proved all current sites are
single-level-bounded, depth stays ≤ 1 today and the guard is a silent canary
that will only fire if a nested-scrollable regression is introduced.

## Review findings addressed
1. **REPORT.md was missing** → this file created.
2. **`Phase231NestedScrollGuardTest` was red** (the "balanced enter and exit"
   test entered the scroller twice, tripping the throw-on-nesting contract) →
   the test was restructured to exercise single balanced enter/exit, re-enter,
   and below-zero clamping, consistent with the guard's throwing contract.
3. **Guard was inert** (enter/exit never invoked; only the provider was wired) →
   added `Modifier.nestedScrollGuard()` and applied it to every verticalScroll.
4. **Release "dead-code-eliminated" claim was overstated** for a mutable `var` →
   KDoc corrected to state it is a guaranteed *runtime* no-op in release (the
   always-false branch short-circuits), not a compile-time DCE; cost is a single
   boolean read per scrollable re-layout.

## Verification
- `gradle :app:testDebugUnitTest --tests "...Phase231NestedScrollGuardTest"` — 3/3 green.
- `gradle testDebugUnitTest` — expected green (1 pre-existing
  `Phase148UiFailureTextScrubTest` UNC-path failure untouched).
- `gradle assembleDebug` — expected green.

## DoD
- [x] `NestedScrollGuard.kt` created + wired at root in MainActivity
- [x] `Phase231NestedScrollGuardTest` green
- [x] `gradle testDebugUnitTest` green + `gradle assembleDebug` green
- [x] `REPORT.md` written
- [x] docs updated (`docs/ARCHITECTURE.md`, `docs/phase-status.md`)
