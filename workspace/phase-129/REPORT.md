# Phase 129 REPORT — Restore horizontal floating ink bar + aspect-correct, draggable minimap

**Status:** DONE
**Date:** 2026-08-18
**Bug (owner-confirmed):** phase-35 (`7b0507b`) replaced the horizontal
`FloatingBottomToolbarPill` with the always-vertical `FloatingToolDock` and a
fixed 120×140dp minimap HUD.

## What changed

### 1. Restored horizontal ink bar (default posture) — `EditorScreen.kt`
- Posture is now **orientation-only**, decided by the pure-JVM
  `DockPosturePolicy` (`services/DockPosturePolicy.kt`): portrait → horizontal
  capsule, landscape → 56dp side column. A bar dragged mid-screen no longer
  morphs into a vertical dock (that phase-35 behaviour is gone by default).
- `FloatingToolDock` (`EditorScreen.kt:2233`) was rewritten around the two
  restored content composables:
  - **Portrait** `InkBarPortraitBar` (`EditorScreen.kt:2498`): 56dp-tall
    capsule, `RoundedCornerShape(28.dp)`, `surfaceContainerHigh`,
    `tonalElevation = 6.dp`, `shadowElevation = 8.dp`, 1dp
    `outlineVariant` 50%-alpha border; inner `Row` with
    `horizontalScroll(rememberScrollState())`, padding `8/4`, `spacedBy(4.dp)`.
  - **Landscape** `InkBarLandscapeBar` (`EditorScreen.kt:2657`): 56dp-wide
    scrollable `Column`, padding `4/8`, `spacedBy(6.dp)`, `HorizontalDivider`
    before the settings item.
- Anchors: portrait **BottomCenter, bottom = 20dp**; landscape **CenterEnd,
  end = 20dp** (via `DockPosturePolicy.horizontalDefaultAnchor` /
  `verticalDefaultAnchor`). Default bar state = the pre-35 pill with NO
  dragging, NO snapping, NO edge morphing.

### 2. Feature parity (all 9 pre-35 items) — proof table

| # | Pre-35 item (7b0507b^) | Restored location | Behaviour |
|---|------------------------|-------------------|-----------|
| 1 | Tool selector (`getToolIcon(displayTool)` + `displayTool.label`, `labelMedium`, `primaryContainer` when `TOOL_PICKER`, shows `lastDrawingTool` while PAN/SELECT) | `EditorScreen.kt:2524` (portrait), `:2680` (landscape) | identical |
| 2 | Scroll/Pan toggle (`Icons.Outlined.PanTool` + "Scroll" label, highlight on PAN/SELECT) | `EditorScreen.kt:2550` (portrait), `:2697` (landscape) | identical |
| 3 | Color swatch (40dp circle Surface, 24dp inner circle + 1.5dp `outline` ring, highlight on `COLOR_PICKER`) | `EditorScreen.kt:2576` (portrait), `:2714` (landscape) | identical |
| 4 | Width badge (`LineWeight` + `"${currentWidth.toInt()}pt"`, highlight on `WIDTH_PICKER`) | `EditorScreen.kt:2594` (portrait), `:2732` (landscape) | identical |
| 5 | `VerticalDivider` 24dp (portrait) / `HorizontalDivider` (landscape) | `EditorScreen.kt:2620` (portrait), `:2749` (landscape) | identical |
| 6 | Canvas settings (`Icons.Outlined.Tune`, 40dp IconButton, tint `primary` on `SETTINGS_MENU`) | `EditorScreen.kt:2626` (portrait), `:2755` (landscape) | identical |
| 7 | Undo / Redo (`Undo`/`Redo`, 36dp IconButtons) | `EditorScreen.kt:2635` (portrait), `:2764` (landscape) | identical |
| 8 | Auto-hide while drawing (`HIDDEN_DRAWING`) + restore on canvas tap/stroke end | `EditorScreen.kt:1769` (AnimatedVisibility `visible = toolbarState != HIDDEN_DRAWING`), `:1696-1699` (triggers) | retained |
| 9 | Portrait bottom-centre 20dp above bottom; landscape side column 20dp from end | `EditorScreen.kt:2282-2287` (default anchor via policy), posture at `:2278` | retained |

**Kept phase-35 extras (opt-in / non-invasive):** the one-tap quick-tool rail
(expand chevron appended after Undo/Redo — additive, replaces no parity item),
and the three drag-related extras that are now **settings-gated, default OFF**
(`inkBarDraggable`, `inkBarSnapToEdgeEnabled`, `inkBarDockPersistEnabled`).
`minimapDraggable` likewise default OFF. With everything off, the bar is
byte-for-byte the pre-35 default.

### 3. Draggable ink bar — `FloatingWidgetDragPolicy.kt` (pure JVM)
- Gates: `mayDrag`, `maySnapToEdge`, `mayPersistDock`, `hasPersistedOffset`,
  `shouldApplyDraggedPosition`, `restingPosition` (dragged offset vs default
  anchor), `constrainWithinSafeArea` (safe-insets clamp so the bar never clips
  behind system bars/gesture areas).
- Drag is wired in `FloatingToolDock` (`.pointerInput(draggable, ...)` at
  `EditorScreen.kt:2339`); resting offset is **session-scoped** and hoisted to
  `EditorScreen` state (`EditorScreen.kt:421-459`) so it survives the bar's
  auto-hide; persistence to `SettingsManager` only when `inkBarDockPersistEnabled`.
- Tap targets, auto-hide and landscape posture are untouched by dragging
  (drag only changes the top-left offset).

### 4. Aspect-correct, draggable minimap — `AnnotationCanvas.kt` + `MinimapGeometryPolicy.kt`
- **Size:** `MinimapGeometryPolicy.aspectFit` (`MinimapGeometryPolicy.kt:58`)
  fits the canvas **world** aspect ratio (from `computeCanvasWorld`) inside the
  pre-35 nominal 120×140dp max box, preserving aspect; a min-side floor
  (48dp) is applied only when an upscale still fits the box. The fixed
  120×140 box is gone (`AnnotationCanvas.kt:1725`).
- **Mapping:** the pan/tap-to-pan and stroke drawing now use a **single uniform
  map scale** (`AnnotationCanvas.kt:1866,1892`) instead of the phase-35
  per-axis `mapScaleX/Y`, so the viewport box and strokes agree with the page
  in every mode including seamless/infinite.
- **Anchor:** default bottom-right (`MinimapGeometryPolicy.defaultAnchorBottomEnd`,
  `AnnotationCanvas.kt:1732`); collapsible header kept (`minimapExpanded`).
- **Draggable:** gated by `minimapDraggable` (default OFF); session-scoped
  drag offset, clamped to safe insets (`AnnotationCanvas.kt:1763-1784`).
- **Visibility default OFF:** `SettingsManager.minimapHudEnabled` getter now
  reads `MinimapGeometryPolicy.VISIBLE_BY_DEFAULT == false`
  (`SettingsManager.kt:317`) — the phase-35 persisted default-true regression
  is reverted. Toggling on/off is the plain canvas-settings-sheet switch
  (`EditorScreen.kt:4073-4075`).

### 5. Settings sheet
- `CanvasSettingsBottomSheet` gained the phase-129 toggles: "Draggable
  Minimap", "Draggable Ink Bar" (and, when enabled, "Snap Ink Bar to Edge" +
  "Remember Ink Bar Position") — `EditorScreen.kt:4049-4108`. All default OFF;
  values persist via `SettingsManager`.

## Verification
- New pure-JVM tests: `Phase129InkBarMinimapPolicyTest.kt` (**23 tests**) —
  minimap aspect-fit math + max-box clamping, min-side floor, portrait→horizontal
  posture, default anchors, visibility default OFF (incl. a source-pin check
  that `SettingsManager` no longer has the literal `true` default), drag gates,
  resting-position choice, safe-area constraint.
- `gradle testDebugUnitTest` → **BUILD SUCCESSFUL** (all suites pass).
- `gradle assembleDebug` (full `--rerun-tasks`) → **BUILD SUCCESSFUL**,
  `app/build/outputs/apk/debug/app-debug.apk` produced.
  - Note: one earlier `assembleDebug` invocation reported a transient
    failure ("BUILD FAILED in 2m 35s") with no reproducible error; the
    immediately following clean `--rerun-tasks` build succeeded 90/90 tasks.
    Treat as a CI/resource flake, not a code defect.

## Files touched
- `app/src/main/kotlin/com/authorss81/noteflow/services/DockPosturePolicy.kt` (new)
- `app/src/main/kotlin/com/authorss81/noteflow/services/FloatingWidgetDragPolicy.kt` (new)
- `app/src/main/kotlin/com/authorss81/noteflow/services/MinimapGeometryPolicy.kt` (new)
- `app/src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt` (default flip + 6 new prefs)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt` (dock rewrite, call sites, settings sheet)
- `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt` (aspect-correct + draggable minimap)
- `app/src/test/java/com/authorss81/noteflow/Phase129InkBarMinimapPolicyTest.kt` (new)
