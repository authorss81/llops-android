# Phase 232 — Compile-time guard: source-scan lint test (Detekt-rule equivalent)

## Status
IMPLEMENTED (2026-08-28).

## Goal
Add a **compile-time / static source scan** that flags the nested-scrollable
anti-pattern — the `CheckScrollableContainerConstraints` crash ("Vertically
scrollable component was measured with infinity maximum height constraints").
Since Detekt is NOT configured in this repo, the equivalent is a source-pinning
JUnit test that parses every `app/src/main/kotlin` file for the crash signature:
a `verticalScroll(` appearing BEFORE a `heightIn`/`fillMaxHeight`/`fillMaxSize`/
`weight` bound on the same scrollable chain, and (per the `LazyColumn`/`LazyRow`
assertion) any lazy-nesting under an unbounded `verticalScroll` parent.

## Implementation
`app/src/test/java/com/authorss81/noteflow/Phase232NestedScrollSourceScanTest.kt`
(17 tests) — pure-JVM static scan, no Compose/Robolectric:

- **`NestedScrollSourceScan` scan engine**
  - `CodeState` — char-level lexer tagging every index CODE/STRING/TRIPLE/CHAR/
    LINE_COMMENT/BLOCK_COMMENT so mentions of `verticalScroll(`, `LazyColumn`,
    `heightIn(` etc. inside strings/comments/KDoc are never read as code
    (NestedScrollGuard.kt holds 3 textual mentions; its KDoc + check-message
    strings must be ignored).
  - `chainLineRange` — walk-back to the chain head: continuation lines start
    with `.`, a line mentioning `Modifier` (or starting `modifier`) is the head;
    comment lines are transparent in both directions.
  - `chainElements` / `lineElements` — the `.name(` tokens of a chain, code-only.
  - `boundElementAfterScroll` — the first bound element after `verticalScroll`
    in the chain, if any (the ordering violation).
  - `isInsideBoundProvider` — paren-depth + trailing-lambda tracking of
    `AlertDialog` / `BasicAlertDialog` / `Dialog` / `ModalBottomSheet` /
    `BoxWithConstraints` ancestors; a scroll inside any of them is parent-bounded
    (the documented known-safe exception). A previously-closed dialog must NOT
    shield a later violation (dedicated test).
  - `orderingViolations` — bound-after-scroll sites, minus horizontal
    (`horizontalScroll`) and parent-bounded chains.
  - `lazyNestedViolations` — `LazyColumn` sites whose backward 10-line window
    contains an unbounded `verticalScroll` parent.
  - `codeVerticalScrollCount` / `codeLazyColumnCount` — non-vacuity counters.
  - `scan(text, rel)` — ordering + lazy violations for one file.

- **Whole-tree asserts**
  1. No `verticalScroll` chain places a height bound AFTER the scroll
     (NONE on the post-phase-230 tree — the only bound-after-scroll in the tree
     is `BrushStudioDialog.kt:63-65`, pinned in a dedicated test as the
     documented dialog-bound exception).
  2. No `LazyColumn` nests inside an unbounded `verticalScroll` parent.
  3. Non-vacuous: ≥ 20 real `verticalScroll` code sites and ≥ 5 real `LazyColumn`
     sites must be visited.

- **Matcher proof unit tests** prove the detector really catches a future
  `.verticalScroll().heightIn()` regression (direct `boundElementAfterScroll`
  + whole-scan assertions), the safe ordering, `weight` after scroll, `LazyRow`/
  `horizontalScroll` exclusion, strings/comments exclusion, the dialog-bound
  exception, the `ModalBottomSheet` trailing-lambda shielding, the closed-dialog
  non-shielding case, bounded-lazy safety, and that `lineHasBound` never
  mistakes `fontWeight` for `weight` (`\bweight\b` word boundary).

## Compile-stage fixes found during the run
1. **`LazyColumn` bare-identifier match** — the composable is normally invoked
   with a trailing lambda (`LazyColumn { … }`), so the initial
   `\bLazyColumn\s*\(` regex matched nothing in real code and the lazy guard was
   VACUOUS. The first matcher test ("detects a LazyColumn nested inside an
   unbounded verticalScroll") failed RED and exposed it. Fixed with
   `\bLazyColumn\b` (word boundary keeps `LazyColumnScope` etc. out).
2. **KDoc `[head..end]` bracket pair** tripped the compiler
   (`:141:28 Closing bracket expected`) — reworded the doc comment to plain text.

## Verification
- `gradle :app:testDebugUnitTest --tests "...Phase232NestedScrollSourceScanTest"`
  — 17/17 green.
- `gradle testDebugUnitTest` — **3446 tests / 0 failures / 0 errors** (the
  intermittently-failing `Phase148UiFailureTextScrubTest` UNC-path test passed
  on this run; untouched).
- `gradle assembleDebug` — green.

## DoD
- [x] `Phase232NestedScrollSourceScanTest` green on the current tree
- [x] A unit test proves the matcher detects the bad `.verticalScroll().heightIn()` ordering
- [x] `gradle testDebugUnitTest` green
- [x] `workspace/phase-232/REPORT.md` written
- [x] docs updated (`docs/phase-status.md` + `docs/ARCHITECTURE.md`)

No schema change, no migration, no new deps, `.github/workflows/` untouched,
base-APK-size rule intact.