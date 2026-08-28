# Phase 229 — Research: Fix Nested Scrollable Containers (Tablet Crash) with Dynamic Guards + Zero Mobile Regression

## Goal
Research the **best fix** for `Vertically scrollable component measured with infinity maximum height constraints` crashes on tablets, then implement **systematic fix** across all screens **without any mobile regression**.

## Context — Verified Root Cause
`CheckScrollableContainerConstraints.kt:35` throws when a **vertically scrollable child** (`LazyColumn` / `Column(Modifier.verticalScroll())`) is measured with **`Constraints(maxHeight = Infinity)`** from a **parent vertically scrollable container** (`Column(Modifier.verticalScroll())` / `LazyColumn` with unbounded height).

**Confirmed offender:** `EditorScreen.kt:4489` — inner `Column(verticalScroll).heightIn(max=430.dp)` **after** `verticalScroll` inside outer `Column(verticalScroll)` (fixed in `c972b23` by moving `heightIn` **before** `verticalScroll`). But **more exist** — tablets expose them via two-pane layouts, dialogs, `BoxWithConstraints` branches.

## Research Tasks (File:Line Anchors Required)

### 1. Exhaustive Inventory (File:Line Evidence)
- **`grep -rn "verticalScroll\|LazyColumn" app/src/main/kotlin --include="*.kt" | grep -v test | grep -v workspace`** → map every `verticalScroll()` + `LazyColumn` usage
- For each `verticalScroll()`:
  - Parent container type (`Column`, `Box`, `LazyColumn`, `BoxWithConstraints`)
  - Parent modifiers (`verticalScroll`, `fillMaxSize`, `fillMaxHeight`, `weight`, `heightIn`, `fillMaxSize`)
  - Child scrollable type (`LazyColumn`, `Column(verticalScroll)`, `LazyRow` with vertical overflow)
  - Bounding modifiers on child (`weight(1f)`, `heightIn`, `fillMaxHeight`, `fillMaxSize`, `fillMaxSize().verticalScroll()`)
- **Categorize each pair** as:
  - **SAFE** — child bounded (`weight(1f)`, `fillMaxHeight`, `heightIn(max)` **outer** to `verticalScroll`, `fillMaxSize` from parent `weight/fillMaxSize`)
  - **RISK** — child unbounded, parent `verticalScroll`/`fillMaxSize` without `weight`
  - **CONFIRMED CRASH** — reproduces `CheckScrollableContainerConstraints` on tablet

### 2. Tablet-Specific Layout Branches
- Find all `BoxWithConstraints` / `calculateWindowSizeClass()` / `LocalConfiguration` branches that change layout on tablet
- Identify screens where **two-pane** layout nests `LazyColumn` inside `Column(verticalScroll)` only on tablet
- `HomeScreen.kt` sidebar `LazyColumn` (1783/1830/2543/2664) — check if tablet wraps in `Column(verticalScroll)`
- `MarkdownPreviewScreen.kt` reader mode split pane
- Dialogs/BottomSheets with nested scroll

### 3. Fix Strategy Matrix (Zero Mobile Regression)
For each **Risk/Confirmed** pattern, define fix with **mobile parity**:

| Pattern | Mobile Safe Fix | Tablet Fix | Constraint Order |
|---------|----------------|------------|------------------|
| `LazyColumn` in `Column(verticalScroll)` | `Modifier.weight(1f)` on `LazyColumn` | same | `.weight(1f).verticalScroll()` or `.heightIn(max).verticalScroll()` |
| `Column(verticalScroll)` in `Column(verticalScroll)` | Move `heightIn(max)` **before** `verticalScroll` on inner | same | `.heightIn(max).verticalScroll()` |
| `LazyColumn` in `BoxWithConstraints` tablet branch | `.heightIn(max = constraints.maxHeight).verticalScroll()` | same | `BoxWithConstraints { c -> LazyColumn(Modifier.heightIn(max=c.maxHeight).verticalScroll()) }` |
| Nested `verticalScroll` in dialogs/bottom sheets | Remove inner scroll or `heightIn(max)` **before** `verticalScroll` | same | `.heightIn(max).verticalScroll()` |

### 4. Dynamic Guard (Runtime + Compile-Time)
- **Runtime guard (debug only):** `DebugOnly { checkScrollableConstraints() }` in `CompositionLocal` provider at root — logs warning in debug, no-op release
- **Compile-time:** Detekt rule `NestedScrollableDetector` (custom) — flags `LazyColumn`/`verticalScroll` inside `verticalScroll` parent without bounding modifier
- **Test:** Compose UI test `@Preview(device = Devices.TABLET)` for each fixed screen + `UiMode.NIGHT_NO` + `Locale` variants

### 5. Mobile Regression Guard
- `@Preview(device = Devices.PHONE, uiMode = UI_MODE_NIGHT_YES)` for each fixed screen
- Golden screenshot diff (Paparazzi) mobile vs tablet for each fixed screen
- Assert `Modifier.verticalScroll` count unchanged on phone layouts

## Deliverables
1. **`workspace/phase-229/INVENTORY.md`** — exhaustive table: File:Line | Parent | Child | Bounding | Verdict | Fix Plan
2. **`workspace/phase-229/FIX_STRATEGY.md`** — pattern → fix mapping with code templates
3. **`workspace/phase-229/DYNAMIC_GUARD.kt`** — runtime guard + Detekt rule sketch
4. **`workspace/phase-229/PHASES.md`** — **CREATE the implementation phases** as `workspace/phase-230/PROMPT.md`, `workspace/phase-231/PROMPT.md`, etc. based on research findings
5. **CRITICAL:** The research phase MUST create the implementation phases itself. Do not stop at research only.

## Phase Creation Instructions
Based on research, create implementation phases. Use this template for each phase:
```bash
# Create directories
mkdir -p workspace/phase-230
mkdir -p workspace/phase-231
# ... etc
```

For each implementation phase, create `workspace/phase-NNN/PROMPT.md` with:
- Goal
- Context (from research inventory)
- File:Line fixes to apply
- Fix pattern code template
- Verification (zero mobile regression, Paparazzi screenshots)
- DoD (gradle green, tests pass, REPORT.md)
- **Timeout: 180 minutes** for implementation phases (can be more if needed)

**Example phases to create (adjust based on research):**
- `phase-230` — Fix EditorScreen.kt + HomeScreen.kt + MarkdownPreviewScreen.kt
- `phase-231` — Fix dialogs/bottom sheets + version history + tag manager
- `phase-232` — Add runtime guard + Detekt rule
- `phase-233` — Add Paparazzi tablet golden tests + CI integration
- `phase-234` — Documentation + architecture doc update

If research finds more files need fixing, create additional phases (230-239).

**Add .timeout files:** For each phase, add `workspace/phase-NNN/.timeout` with `180` or higher.

## Constraints
- **No mobile regression** — every fix must pass mobile `@Preview` + Paparazzi golden diff
- **No schema changes** — pure Compose modifier reordering
- **No workflow edits** — `.github/workflows/` untouched
- **Timeout:** 240 min for research, 180 min per implementation phase

## DoD
- `workspace/phase-229/INVENTORY.md` complete with File:Line table
- `workspace/phase-229/FIX_STRATEGY.md` with code templates
- `workspace/phase-229/DYNAMIC_GUARD.kt` compileable sketch
- **Phase list (230, 231, ...) with PROMPT.md and .timeout files** — implementation phases ready to execute
- All phases pushed, cron will execute sequentially
- `gradle testDebugUnitTest` green after each phase

## Timeout
240 minutes (research phase may need deep grep + manual review of 50+ files)