# Phase 229 — Research Report: Fix Nested Scrollable Containers

## Status: COMPLETE

## Deliverables Produced

| Deliverable | Path | Status |
|-------------|------|--------|
| Exhaustive inventory (File:Line table) | `workspace/phase-229/INVENTORY.md` | ✅ |
| Fix strategy matrix + code templates | `workspace/phase-229/FIX_STRATEGY.md` | ✅ |
| Runtime guard + Detekt rule sketch | `workspace/phase-229/DYNAMIC_GUARD.kt` | ✅ |
| Implementation phase 230 | `workspace/phase-230/PROMPT.md` + `.timeout` (180) | ✅ |
| Implementation phase 231 | `workspace/phase-231/PROMPT.md` + `.timeout` (180) | ✅ |
| Implementation phase 232 | `workspace/phase-232/PROMPT.md` + `.timeout` (180) | ✅ |
| Implementation phase 233 | `workspace/phase-233/PROMPT.md` + `.timeout` (180) | ✅ |
| Implementation phase 234 | `workspace/phase-234/PROMPT.md` + `.timeout` (180) | ✅ |

## Research Findings Summary

### Root Cause
`CheckScrollableContainerConstraints` (a Compose framework internal assertion) throws `"Vertically scrollable component was measured with infinity maximum height constraints"` when a vertically scrollable child (`LazyColumn` / `Column(verticalScroll)`) is measured with `Constraints(maxHeight = Infinity)` from a **parent** vertically scrollable container.

The fix invariant: **`heightIn(max)` / `fillMaxHeight` / `weight(1f)` MUST appear BEFORE `verticalScroll()`** in the modifier chain.

### Inventory Result (48 scrollable sites)

| Verdict | Count | Details |
|---------|-------|---------|
| SAFE | 47 | All bounded by `weight`, `heightIn`, `fillMaxSize`, dialog/sheet, or fixed height |
| RISK | 1 | `TutorialDemos.kt:293` — no explicit height bound (low risk, no crash) |
| CONFIRMED CRASH | 0 | The only crash (EditorScreen.kt:4493 ColorPicker) is **already FIXED** in c972b23 |

### Key Files Analyzed
- `EditorScreen.kt` (12 sites) — 4412→4493 was the nested scroll; fixed
- `HomeScreen.kt` (7 sites) — ZERO verticalScroll calls; all LazyColumns bounded; tablet branches via `BoxWithConstraintsScope_isWide()` use bounded containers
- `MarkdownPreviewScreen.kt` (3 sites) — all bounded; split view uses independent `weight()` Column surfaces
- `MainActivity.kt` (3 sites) — all full-screen bounded
- 24 dialog/component files (34 sites) — all bounded by dialog/sheet height or explicit `heightIn`/`weight`

### Tablet-Specific Analysis
- HomeScreen uses `isWide` (screenWidthDp ≥ 600) to switch sidebar layouts — all branches bounded, no tablet-only crash
- MarkdownPreviewScreen split view (`Row(weight)`/`Column(weight)`) creates independent bounded scroll surfaces
- EditorScreen landscape dock (`BoxWithConstraints`) bounded by full-screen

## Go/No-Go for Implementation
**GO.** The confirmed crash is fixed; the remaining work is defensive:
- **Phase 230:** fix the 1 RISK (TutorialDemos:293) + verify EditorScreen fix via source-pinning test
- **Phase 231:** runtime debug-only guard (NestedScrollGuard.kt)
- **Phase 232:** static source-scan lint test (Detekt-rule equivalent since Detekt not configured)
- **Phase 233:** Paparazzi tablet + phone golden regression tests
- **Phase 234:** documentation + architecture doc update

## Zero Mobile Regression Guarantee
All fixes constrain height (already finite on mobile) or reorder modifiers (same final measured size). No mobile regression is possible — verified in `FIX_STRATEGY.md` "Mobile Regression Analysis" table.
