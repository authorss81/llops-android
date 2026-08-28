# Phase 232 — Compile-time guard: source-scan lint test (Detekt-rule equivalent)

## Goal
Add a **compile-time / static source scan** that flags the nested-scrollable anti-pattern. Since Detekt is NOT configured in this repo, implement the equivalent as a source-pinning JUnit test that parses every `app/src/main/kotlin` file for the crash signature: a `verticalScroll(` appearing BEFORE a `heightIn`/`fillMaxHeight`/`fillMaxSize`/`weight` bound on the same scrollable chain.

## Context (from phase-229 research + FIX_STRATEGY.md)
The crash requires: `...verticalScroll(...)...heightIn(...)` (bound AFTER scroll) OR a nested scrollable without any bound. The fixed and safe patterns always place the bound BEFORE `verticalScroll`.

## Implementation

### 1. Create `Phase232NestedScrollSourceScanTest`
Pure-JVM static scan (no Compose, no Robolectric — reads source text):
- Walk `app/src/main/kotlin` `.kt` files
- Regex/line-based heuristic: for each line containing `verticalScroll(`, check whether the SAME modifier chain (the immediately preceding `Modifier` chain ending at that line) contains a `heightIn(`/`fillMaxHeight`/`fillMaxSize`/`weight(` that comes AFTER `verticalScroll`.
- Assert: no line has `verticalScroll` in a chain whose height bound appears after it.
- Also assert: within a 10-line window, no `LazyColumn`/`LazyRow`(vertical-only concern) appears directly nested inside a `verticalScroll` parent without a bound.

This catches regressions (e.g., someone re-introduces `.verticalScroll().heightIn()`).

Handle known-safe exceptions explicitly:
- Horizontal `LazyRow` / `horizontalScroll` are never the vertical-Infinity concern — exclude them.
- `BoxWithConstraints`-bound and dialog/sheet-bound cases are fine — the scan only flags the explicit bad ordering.

### 2. Ensure the scan passes on current code
Run it against the current tree (post phase-230) — the only bound/modifier order violations should be NONE (EditorScreen was fixed; TutorialDemos got its bound in phase-230).

## Verification
- `gradle testDebugUnitTest` green (Phase232NestedScrollSourceScanTest green; 1 pre-existing UNC-path failure untouched)
- The test FAILS if someone later writes `.verticalScroll(...).heightIn(...)` — prove this by temporarily adding such a line to a scratch copy (do NOT commit) OR by unit-testing the scan's matching function directly.

## DoD
- `Phase232NestedScrollSourceScanTest` green on current tree
- A unit test proves the matcher detects the bad `.verticalScroll().heightIn()` ordering
- `gradle testDebugUnitTest` green
- `workspace/phase-232/REPORT.md` written
- Update docs

## Timeout
180 minutes
