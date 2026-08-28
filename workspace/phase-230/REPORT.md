# Phase 230 — Fix the one RISK + verify EditorScreen fix (defensive hardening)

Date: 2026-08-28

## Goal recap

Harden the single remaining RISK scrollable (`TutorialDemos.kt:293`) and
defensively verify the already-fixed `EditorScreen.kt:4493` pattern is
consistently applied. From phase-229 research: the only CONFIRMED CRASH
(nested `verticalScroll` in ColorPickerBottomSheet) was already fixed in
`c972b23`; the one remaining RISK was a dead (unbounded) scroll in the
`InteractiveTutorial` layer demo panel.

## Changes

### 1. TutorialDemos.kt — bound height BEFORE verticalScroll (RISK closed)

The layer demo panel's inner `Column` had an unbounded `verticalScroll`. With
no height bound, the Column measured at intrinsic content height and the
scrollState never activated (dead scroll). The `InteractiveTutorial.kt:170`
sibling is bounded with `.heightIn(max=620.dp)`; the demos now match.

**Before** (`TutorialDemos.kt:291-295`):
```kotlin
Column(
    modifier = Modifier
        .verticalScroll(rememberScrollState())
        .padding(12.dp)
)
```

**After** (`TutorialDemos.kt:292-297`):
```kotlin
Column(
    modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = 420.dp)
        .verticalScroll(rememberScrollState())
        .padding(12.dp)
)
```

- `heightIn` import added at `TutorialDemos.kt:16`
  (`androidx.compose.foundation.layout.heightIn`) — resolved the
  `Unresolved reference 'heightIn'` compile error that surfaced on first
  attempt.
- Pattern is now consistent: **bound BEFORE scroll** throughout.

### 2. EditorScreen.kt — verified correct, no change needed

Read `EditorScreen.kt:4489-4494`. The ColorPicker inner Column already has the
correct ordering (`heightIn(max=430.dp)` BEFORE `verticalScroll`), matching the
`c972b23` crash fix. No edit required — pinned by test instead.

```kotlin
// EditorScreen.kt:4489-4494 (unchanged, verified)
Column(
    modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = 430.dp)   // BEFORE
        .verticalScroll(rememberScrollState())  // AFTER
) {
```

## Verification

### New source-pinning test

`app/src/test/java/com/authorss81/noteflow/Phase230NestedScrollFixTest.kt`:

- `TutorialDemos layer demo panel bounds height BEFORE scroll` — locates the
  `"Layers"` header, walks back to the enclosing `Column(`, asserts
  `heightIn(` appears textually before `verticalScroll(`, and that the value
  is `heightIn(max = 420.dp)`.
- `EditorScreen ColorPicker inner Column bounds height BEFORE scroll` —
  locates the `// Phase 19: scrollable, organized color picker` marker, asserts
  `heightIn(` before `verticalScroll(`, and that the value is
  `heightIn(max = 430.dp)`.

### Test run

- `gradle :app:testDebugUnitTest --tests Phase230NestedScrollFixTest` → **2/2 green**
- `gradle :app:testDebugUnitTest` (full suite) → **BUILD SUCCESSFUL**
- Result totals: **3426 tests, 0 failures, 0 errors** (the Stage
  `Phase148UiFailureTextScrubTest` UNC-path test passed in this environment —
  consistent with the documented intermittent behavior; untouched by this
  phase).

### Regression assessment

- **Zero mobile regression**: the added `heightIn(max=420.dp)` only caps a
  panel that previously had no bound; the demo panels are small, so mobile
  layout is unchanged. Only positive effect — scroll becomes meaningful (and
  bounded) for the layer demo when it grows.
- No tablet-specific preview required (pure modifier reorder + bound addition,
  identical on mobile/tablet).
- No schema change, no new deps, `.github/workflows/` untouched.

## DoD checklist

- [x] `TutorialDemos.kt:293` now has `.heightIn(max=420.dp).verticalScroll(...)` (bound before scroll)
- [x] `Phase230NestedScrollFixTest` green (2/2)
- [x] `gradle testDebugUnitTest` green (3426 tests, 0 failures)
- [x] `workspace/phase-230/REPORT.md` written with before/after file:line evidence
- [x] `docs/ARCHITECTURE.md` + `docs/phase-status.md` updated (see below)
