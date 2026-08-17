# Phase 120 — Scrollable overflow menus (Home ⋮ + Canvas ⋮ + all app dropdowns)

**Status:** DONE
**Dates:** implemented 2026-08-17
**Build:** `gradle testDebugUnitTest` → 1683 tests, 0 failures, 0 errors (14 new `OverflowMenuPolicyTest`) · `gradle assembleDebug` → BUILD SUCCESSFUL (forced `--rerun-tasks` run, 57 executed; the first plain invocation had a transient daemon/network flake that did not reproduce).

## Goal (from PROMPT)
Every overflow (⋮) dropdown menu must be scrollable with a height cap so lower entries stay reachable on small screens, landscape, and large font scales. No menu contents/ordering/behavior changes — only scrollability + max-height.

## Why it was broken
Material 3 `DropdownMenu` only scrolls when given an explicit `scrollState`, and its internal `rememberDropdownMenuPosition` caps the popup at a **fixed 288 dp** — which is larger than the usable height on small/landscape screens and large font scales, so bottom rows (e.g. the 16-row Home maintenance menu ≈ 768 dp of content, the 17-row Editor ⋮ ≈ 816 dp) were unreachable.

## Implementation

### New pure-JVM decision table — `app/src/main/kotlin/com/authorss81/noteflow/services/OverflowMenuPolicy.kt`
- `MAX_MENU_FRACTION_OF_SCREEN = 0.60f` — cap = 60 % of the current screen height.
- `ABSOLUTE_MAX_MENU_HEIGHT_DP = 560f`, `MIN_MENU_HEIGHT_DP = 120f` — hard bounds (degens ≤ 0 → 120 dp).
- `MATERIAL_DROPDOWN_MAX_HEIGHT_DP = 288f` — documents M3's fixed internal cap (KDoc).
- `maxMenuHeightDp(screenHeightDp)`, `contentOverflows(content, max)`, `estimatedContentHeightDp(items, row=48f)`, `visibleItemCount(items, max, row=48f)`.
- On roomy portrait phone screens (e.g. 800 dp) the derived cap ≥ 288, so the effective viewport stays M3's 288 dp — **no visual change on big screens**; the cap only shrinks below 288 on small/landscape screens where 288 overflows.

### Shared composables — `app/src/main/kotlin/com/authorss81/noteflow/ui/components/OverflowMenuSupport.kt`
- `overflowMenuScrollState()` → one `rememberScrollState()` per menu.
- `overflowMenuScrollModifier()` → `Modifier.heightIn(max = maxMenuHeightDp(LocalConfiguration.current.screenHeightDp).dp)`.

### Wiring — every real dropdown in the app now passes `scrollState` + `modifier` (1:1 parity grep-verified):
| File | Menus (`DropdownMenu(`/`ExposedDropdownMenu(`) | Cap + scroll |
|---|---|---|
| `ui/screens/HomeScreen.kt` (:1886, :1983, :2183, :2348, :2485, :2530, :2580) | 7 | 7 |
| `ui/screens/EditorScreen.kt` (:1058 embed, :1125 overflow, :4701 blend) | 3 | 3 |
| `ui/screens/MarkdownPreviewScreen.kt` (:313 plugin menu) | 1 | 1 |
| `ui/components/UnifiedSidebar.kt` | 3 | 3 |
| `ui/components/MediaEmbedComponents.kt` | 1 | 1 |
| `ui/components/SpreadsheetTableView.kt` | 1 | 1 |
| `ui/components/Dialogs.kt` | 1 | 1 |
| `ui/components/KanbanBoardView.kt` | 1 | 1 |
| `ui/components/Phase16PluginDialogs.kt` | 1 | 1 |
| `ui/components/LocalSendSendDialog.kt` | 2 | 2 |

Uses M3's built-in `scrollState` column mechanism, so the internal popup scrolls with the Material thin-scrollbar affordance and keyboard-arrow navigation keeps working. No dependency changes, no schema change, no `.github/workflows/` edits; low-end fallback = same M3 path, no new hardware requirements.

## Tests — `app/src/test/java/com/authorss81/noteflow/OverflowMenuPolicyTest.kt` (14)
- Pure-JVM math: per-screen caps (800→480, 360→216, 1280→560 ceiling, degens ≤ 0 → 120), strict overflow decision, row estimates, `visibleItemCount` bounds.
- Real-menu scenarios: 16-row Home maintenance menu and 17-row Editor ⋮ overflow on 360/640/800 dp screens and hide N rows reachable only by scroll; the 14-row plugin menu fits the cap on 800 dp (10 visible / 4 hidden).
- M3-default pin: `MATERIAL_DROPDOWN_MAX_HEIGHT_DP == 288` and derived caps ≤ 288 on 440/480 dp screens.
- Source pin: reads the 10 touched files + KnowledgeGraphScreen, and asserts `menus == cap-modifiers == scroll-states` (every dropdown has both a cap and a scroll, no double-wiring).

## Verification
- `gradle testDebugUnitTest` → **BUILD SUCCESSFUL**, 1683 tests, 0 failures / 0 errors / 0 skipped (summed from `app/build/test-results/testDebugUnitTest/TEST-*.xml`).
- `gradle assembleDebug` → **BUILD SUCCESSFUL** on a forced full `--rerun-tasks` run (57/57 executed) → `app/build/outputs/apk/debug/app-debug.apk`. (First plain invocation reported `BUILD FAILED in 2m 36s` with a transient daemon/network flake; the immediate re-run was fully `UP-TO-DATE`-green and the forced rebuild was green, so it is non-reproducible.)