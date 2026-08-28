# Phase 230 — Fix the one RISK + verify EditorScreen fix (defensive hardening)

## Goal
Harden the single remaining RISK scrollable (`TutorialDemos.kt:293`) and defensively verify the already-fixed `EditorScreen.kt:4493` pattern is consistently applied across all nested-scroll sites.

## Context (from phase-229 research)

The exhaustive inventory found **zero remaining CONFIRMED CRASH** sites — the only confirmed crash (nested `verticalScroll` in ColorPickerBottomSheet) was fixed in `c972b23` by moving `heightIn(max=430.dp)` BEFORE `verticalScroll()`.

**One RISK remains:**

| File:Line | Type | Issue | Verdict |
|-----------|------|-------|---------|
| `TutorialDemos.kt:293` | `Column.verticalScroll` inside `Surface(fillMaxWidth)` | Surface wraps content; no explicit height bound. Column measures at intrinsic content height → scrollState never activates (dead scroll). No crash, but ineffective. | **RISK (low)** |

The `InteractiveTutorial.kt:170` sibling is properly bounded (`.heightIn(max=620.dp)`), so the tutorial demos should match.

## File:Line fixes to apply

### 1. TutorialDemos.kt:291-295 — add explicit height bound
The demo panels inside `InteractiveTutorial` (a `Card` with `heightIn(max=620.dp)` at InteractiveTutorial.kt:170) are small, but the layer demo can grow when layers are added. Add an explicit height cap so scroll is meaningful and becomes bounded whether or not the parent provides one.

```kotlin
Column(
    modifier = Modifier
        .fillMaxWidth()
        .heightIn(max = 420.dp)   // bounded BEFORE scroll
        .verticalScroll(rememberScrollState())
        .padding(12.dp)
)
```

### 2. Verify the EditorScreen fix is consistent
Read `EditorScreen.kt:4485-4495` and confirm the ordering is `heightIn(max=430.dp)` BEFORE `verticalScroll()`. Add a defensive source-pinning test (see below).

## Fix pattern code template
```kotlin
// ALWAYS: height bound BEFORE verticalScroll
Modifier
    .fillMaxWidth()
    .heightIn(max = N.dp)   // BEFORE
    .verticalScroll(state)  // AFTER
```

## Verification
- `gradle testDebugUnitTest` green (must remain green — the 1 pre-existing `Phase148UiFailureTextScrubTest` UNC-path failure is untouched)
- Add a source-pinning JUnit test `Phase230NestedScrollFixTest` that:
  - Reads `app/src/main/kotlin/.../TutorialDemos.kt` and asserts `heightIn(max` appears on a line BEFORE `verticalScroll` in the layer demo panel (line ~292-294)
  - Reads `EditorScreen.kt` and asserts the ColorPicker inner Column has `heightIn(max = 430.dp)` BEFORE `.verticalScroll` at the lines 4490-4493 range
- No tablet-specific preview required (pure modifier reorder + bound addition, identical on mobile/tablet)
- **Zero mobile regression:** the added `heightIn` only caps a panel that previously had no bound — mobile layout is unchanged (panel was already small).

## DoD
- `TutorialDemos.kt:293` now has `.heightIn(max=420.dp).verticalScroll(...)` (bound before scroll)
- `Phase230NestedScrollFixTest` green
- `gradle testDebugUnitTest` green
- `workspace/phase-230/REPORT.md` written with before/after file:line evidence
- Update `docs/ARCHITECTURE.md` + `docs/phase-status.md` with "Implemented in Phase 230" note

## Timeout
180 minutes
