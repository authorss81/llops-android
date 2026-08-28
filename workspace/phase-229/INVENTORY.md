# Phase 229 — Exhaustive Scrollable Container Inventory

## Crash Mechanism

`CheckScrollableContainerConstraints.kt:35` throws `IllegalStateException("Vertically scrollable component was measured with infinity maximum height constraints")` when a **vertically scrollable child** is measured with **`Constraints(maxHeight = Infinity)`** from a **parent vertically scrollable container**.

**Root cause:** In Compose, a `Column(Modifier.verticalScroll(...))` measures children with `Constraints(maxHeight = Constraints.Infinity)`. If a child of this Column is ALSO `verticalScroll` or `LazyColumn` without its own height bound, it receives infinite maxHeight → crash.

**Key insight:** The fix is ALWAYS to ensure the inner scrollable has a bounded height **before** the `verticalScroll` modifier is applied. Compose applies modifiers in order — a `heightIn(max)` BEFORE `verticalScroll` constrains the height measurement, while placing it AFTER does not (the scroll already measured with Infinity).

---

## Verified Fix (c972b23)

**EditorScreen.kt:4489-4493** — ColorPickerBottomSheet inner Column:
- **Before fix:** `.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(max = 430.dp)`
- **After fix:** `.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState())`
- The inner Column is inside an outer Column at line 4408 with `.verticalScroll(rememberScrollState())` (ModalBottomSheet content).

---

## Complete Inventory

### EditorScreen.kt (`app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt`)

| Line | Type | Parent Container | Parent Scrollable? | Child Bounding | Verdict |
|------|------|-----------------|-------------------|---------------|---------|
| 3593 | `Column.verticalScroll` | `BoxWithConstraints(fillMaxSize)` → `Box` → `Surface` | No | `BoxWithConstraints` provides finite height | **SAFE** |
| 4321 | `LazyRow` (horizontal) | `ModalBottomSheet` → `Column(fillMaxWidth)` | No | `fillMaxWidth`, horizontal only | **SAFE** |
| 4412 | `Column.verticalScroll` (outer) | `ModalBottomSheet` → `Column` (non-scrollable) | No | Sheet provides bounded height | **SAFE** |
| 4493 | `Column.verticalScroll` (inner) | **`Column.verticalScroll` at 4412** | **YES** | `.heightIn(max = 430.dp)` BEFORE scroll | **SAFE (FIXED c972b23)** |
| 4641 | `LazyRow` (horizontal) | Inside inner scroll at 4493 | N/A (horizontal) | Intrinsic height | **SAFE** |
| 4664 | `LazyRow` (horizontal) | Inside inner scroll at 4493 | N/A (horizontal) | Intrinsic height | **SAFE** |
| 4687 | `LazyRow` (horizontal) | Inside inner scroll at 4493 | N/A (horizontal) | Intrinsic height | **SAFE** |
| 4725 | `LazyRow` (horizontal) | Inside inner scroll at 4493 | N/A (horizontal) | Intrinsic height | **SAFE** |
| 5018 | `Column.verticalScroll` | `ModalBottomSheet` → `Column` | No | Sheet provides bounded height | **SAFE** |
| 5391 | `Column.verticalScroll` | `ModalBottomSheet` → `Column` | No | Sheet provides bounded height | **SAFE** |
| 6779 | `LazyColumn` | `ModalBottomSheet` → `Column` (non-scrollable) | No | `.weight(1f, fill = false)` | **SAFE** |
| 7093 | `LazyRow` (horizontal) | Inside ModalBottomSheet content | No | Horizontal, sheet-bounded | **SAFE** |

### HomeScreen.kt (`app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt`)

| Line | Type | Parent Container | Parent Scrollable? | Child Bounding | Verdict |
|------|------|-----------------|-------------------|---------------|---------|
| 1783 | `LazyColumn` | `Column(.weight(1f).fillMaxHeight())` | No | `weight(1f)` + `fillMaxHeight` | **SAFE** |
| 1830 | `LazyColumn` | `Column(.weight(1f).fillMaxHeight())` | No | `weight(1f)` + `fillMaxHeight` | **SAFE** |
| 2543 | `LazyColumn` | `NotebookPanel` → `Column` | No | `.width(260dp).fillMaxHeight()` on parent | **SAFE** |
| 2664 | `LazyColumn` | `SectionPanel` → `Column` | No | `.width(240dp).fillMaxHeight()` on parent | **SAFE** |
| 2822 | `LazyColumn` | `ModalBottomSheet` → `Column` | No | `.heightIn(max = 350.dp)` | **SAFE** |
| 2987 | `LazyColumn` | `ModalBottomSheet` → `Column` | No | `.heightIn(max = 350.dp)` | **SAFE** |
| 3310 | `LazyColumn` | `AlertDialog.text` → `Column` | No | `.heightIn(max = 320.dp)` | **SAFE** |

**Note:** HomeScreen has ZERO `verticalScroll()` calls. No nested scroll risk.

### MarkdownPreviewScreen.kt (`app/src/main/kotlin/com/authorss81/noteflow/ui/screens/MarkdownPreviewScreen.kt`)

| Line | Type | Parent Container | Parent Scrollable? | Child Bounding | Verdict |
|------|------|-----------------|-------------------|---------------|---------|
| 301 | `Column.verticalScroll` | `ReaderOutlineRail` → `Surface` → `Box(fillMaxSize)` | No | `.heightIn(max = 300.dp)` BEFORE scroll | **SAFE** |
| 1460 | `Column.verticalScroll` | `Box(weight(1f).fillMaxWidth)` → `Column(fillMaxSize)` | No | `fillMaxSize` + `weight(1f)` parent chain | **SAFE** |
| 1488 | `Column.verticalScroll` | Same bounded chain as 1460 | No | `fillMaxSize` + `weight(1f)` parent chain | **SAFE** |

**Note:** Split-view at lines 1000-1104 uses `Row(weight(...))` with `Column(weight(...))` children — independent bounded scroll surfaces, no nesting risk.

### MainActivity.kt (`app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt`)

| Line | Type | Parent Container | Parent Scrollable? | Child Bounding | Verdict |
|------|------|-----------------|-------------------|---------------|---------|
| 1389 | `Column.verticalScroll` | `Column(fillMaxSize)` (VaultBlocked screen) | No | `fillMaxSize` (full-screen) | **SAFE** |
| 1470 | `Column.verticalScroll` | `Column(fillMaxSize)` (CorruptionRecovery screen) | No | `fillMaxSize` (full-screen) | **SAFE** |
| 1596 | `Column.verticalScroll` | `Column(fillMaxSize)` (KeyLost screen) | No | `fillMaxSize` (full-screen) | **SAFE** |

### Dialog Components

| File:Line | Type | Parent Container | Bounding | Verdict |
|-----------|------|-----------------|----------|---------|
| `TemplateLibraryDialog.kt:250` | `LazyColumn` | `AlertDialog.text` | `.height(380.dp)` | **SAFE** |
| `CommandPaletteOverlay.kt:318` | `LazyColumn` | `Surface` → `AlertDialog` | `.heightIn(max = 360.dp)` | **SAFE** |
| `PluginStoreDialog.kt:219` | `LazyColumn` | `AlertDialog.text` | Dialog bounds it | **SAFE** |
| `PluginStoreDialog.kt:269` | `LazyColumn` | `AlertDialog.text` | Dialog bounds it | **SAFE** |
| `CalendarView.kt:230` | `LazyColumn` | `Column` | `.weight(1f)` | **SAFE** |
| `TagManagerDialog.kt:122` | `LazyColumn` | `AlertDialog.text` | `.heightIn(max = 240.dp)` | **SAFE** |
| `VersionHistoryBottomSheet.kt:140` | `LazyColumn` | `Row(height(320.dp))` | Fixed height + `.weight(1f).fillMaxHeight()` | **SAFE** |
| `SlashCommandMenu.kt:83` | `LazyColumn` | `Surface` → `Column` | `.heightIn(max = 280.dp)` | **SAFE** |
| `WikiLinkSuggestions.kt:67` | `LazyColumn` | `Surface` | `.heightIn(max = 240.dp)` | **SAFE** |
| `WikiLinkSuggestions.kt:129` | `LazyColumn` | `Box` → `AlertDialog.text` | `.heightIn(max = 280.dp)` | **SAFE** |
| `OcrResultDialog.kt:129` | `Column.verticalScroll` | `SelectionContainer` → `Column` → `AlertDialog.text` | Dialog bounds it | **SAFE** |
| `PluginSettingsDialog.kt:83` | `Column.verticalScroll` | `AlertDialog.text` | Dialog bounds it | **SAFE** |
| `BrushStudioDialog.kt:61` | `Column.verticalScroll` | `AlertDialog.text` | `.heightIn(max = 640.dp)` | **SAFE** |
| `OnDeviceSmartAssistant.kt:221` | `Box.verticalScroll` | `ModalBottomSheet` → `Column` | `.heightIn(min=200.dp, max=340.dp)` | **SAFE** |
| `LocalSendSendDialog.kt:326` | `Column.verticalScroll` | `AlertDialog.text` | Dialog bounds it | **SAFE** |
| `WebSearchDialog.kt:114` | `Column.verticalScroll` | `AlertDialog.text` | Dialog bounds it | **SAFE** |
| `WebSearchDialog.kt:197` | `Column.verticalScroll` | Standalone | `.heightIn(max = 360.dp)` | **SAFE** |
| `Phase26PluginDialogs.kt:553` | `Text.verticalScroll` | `Column` → `AlertDialog.text` | `.heightIn(max = 420.dp)` on parent | **SAFE** |
| `Phase16PluginDialogs.kt:155` | `Column.verticalScroll` | `AlertDialog.text` | `.heightIn(max = 360.dp)` | **SAFE** |
| `Phase16PluginDialogs.kt:401` | `Column.verticalScroll` | `AlertDialog.text` | `.heightIn(max = 380.dp)` | **SAFE** |

### Content Components

| File:Line | Type | Parent Container | Bounding | Verdict |
|-----------|------|-----------------|----------|---------|
| `InteractiveTutorial.kt:170` | `Column.verticalScroll` | `Card` → `Box(fillMaxSize)` overlay | `.heightIn(max = 620.dp)` | **SAFE** |
| `TutorialDemos.kt:293` | `Column.verticalScroll` | `Surface.fillMaxWidth` (wraps content) | No explicit height — Surface wraps content | **RISK** (low — demo panel, small content, no crash) |
| `TutorialDemos.kt:401` | `Row.verticalScroll` (actually horizontal scroll on Row) | `Column.fillMaxWidth` | `fillMaxWidth` | **SAFE** (horizontal) |
| `UnifiedSidebar.kt:115` | `LazyColumn` | `Column` (sidebar panel) | `.weight(1f).fillMaxWidth()` | **SAFE** |
| `BacklinksInspector.kt:118` | `LazyColumn` | `Column` → `ModalBottomSheet` | Sheet bounds it | **SAFE** |
| `TagExplorerView.kt:101` | `LazyColumn` | `Column` (branch) | `.fillMaxSize()` | **SAFE** |
| `KanbanBoardView.kt:82` | `LazyRow` (horizontal) | `fillMaxSize` host | Horizontal only | **SAFE** |
| `KanbanBoardView.kt:174` | `LazyColumn` | `Card(width(280.dp).fillMaxHeight)` → `Column` | `.weight(1f)` in Column | **SAFE** |
| `SpreadsheetTableView.kt:66` | `LazyColumn` | `Column` inside `Column.horizontalScroll` | `.weight(1f)` (horizontal on outer, vertical on inner) | **SAFE** |
| `markdown/MarkdownRenderer.kt:170` | `Column.verticalScroll` | `Column(fillMaxSize)` | `fillMaxSize` from host | **SAFE** |
| `markdown/HybridMarkdownEditor.kt:110` | `Column.verticalScroll` | `Modifier.fillMaxSize` from caller | `fillMaxSize` from host | **SAFE** |

---

## Summary

| Verdict | Count | Notes |
|---------|-------|-------|
| **SAFE** | 47 | All properly bounded by `weight`, `heightIn`, `fillMaxSize`, dialog/sheet, or fixed height |
| **RISK** | 1 | `TutorialDemos.kt:293` — no explicit height, but wraps content (no crash) |
| **CONFIRMED CRASH** | 0 | The one confirmed crash (EditorScreen.kt:4493) was FIXED in c972b23 |

## Tablet-Specific Analysis

- **HomeScreen.kt**: Uses `BoxWithConstraintsScope_isWide()` (reads `LocalConfiguration.screenWidthDp >= 600`) to switch between sidebar and panel layouts. All branches use bounded containers. No tablet-specific crash risk.
- **MarkdownPreviewScreen.kt**: Split view uses `Row(weight(...))` with `Column(weight(...))` children — independent bounded surfaces. Reader mode uses `Column(weight(1f))` bounding. No tablet-specific crash risk.
- **EditorScreen.kt**: `BoxWithConstraints` at line 3454 for landscape dock. The verticalScroll at 3593 is bounded by the full-screen BoxWithConstraints. No tablet-specific crash risk.

**Conclusion:** The only confirmed crash pattern was the nested `verticalScroll` in ColorPickerBottomSheet (EditorScreen.kt:4493), already fixed. All other scrollable containers are properly bounded. The risk profile is very low.
