# Phase 244 — Fix Minimap Off-Screen + Ink Bar Blocks Drawing + General Visual Layout

## Summary
Fixed the three visual/layout issues from the screenshots:
1. **Bug 1 — Minimap off-screen** — the minimap HUD was anchored to the whole-device
   `LocalConfiguration` dimensions instead of the actual canvas box, so on devices where the
   canvas is smaller than the physical display (app bar / bottom bar / system bars / cutout) the
   bottom-right widget slid off the visible area. Now anchored to the real canvas box.
2. **Bug 2 — Ink bar at the top blocks drawing** — a bar dragged into the TOP half of the canvas
   sat directly over the strip the user draws on; its buttons/drag gesture swallowed the touch, so
   a stroke could not begin beneath it (and the bar drags instead of drawing). While a drawing tool
   is active the bar now yields that top area back to the canvas.
3. **Bug 3 — Minimap text overflow** — the Zoom/Layers/View HUD lines could balloon the box height
   or spill past a narrow map for a long layer name / far-panned coordinate. Now single-line ellipsis.

## Changes (file:line evidence)

### Bug 1 — Minimap off-screen
- `AnnotationCanvas.kt:2416-2421` — capture the canvas box's real pixel bounds in the
  `BoxWithConstraints` scope:
  - `canvasBoxW = with(LocalDensity.current) { maxWidth.toPx() }`
  - `canvasBoxH = viewHeightPx`
- `AnnotationCanvas.kt:3364-3375` — the minimap HUD now uses those captured box bounds
  (`screenW = canvasBoxW`, `screenH = canvasBoxH`) instead of
  `configuration.screenWidthDp.dp.toPx() / configuration.screenHeightDp.dp.toPx()`.
  `defaultAnchorBottomEnd` (bottom-right) and the drag `constrainWithinSafeArea` now operate on the
  actual canvas box, so the minimap is always inside the visible canvas on phone portrait / phone
  landscape / tablet portrait / tablet landscape.

### Bug 2 — Ink bar at the top blocks drawing
- New pure-JVM `services/InkBarDrawingPolicy.kt`:
  - `isDrawingTool(drawingToolActive)` (`:20`)
  - `shouldYieldDrawingArea(drawingToolActive, barTopY, availableHeight)` (`:30`) — true only when a
    drawing tool is active AND the bar's resting top edge is in the top half; a bottom-resting bar is
    never nudged; degenerate/zero height never yields.
- `EditorScreen.kt:3474-3494` — `FloatingToolDock` computes `activeDrawingTool`
  (`currentTool.isFreehandTool || currentTool.isShapeTool || currentTool == ERASER`) and, when the
  horizontal (portrait) posture + the policy say to yield, overrides `restingPos` to the default
  bottom anchor. The persisted dragged offset (`onChangeDraggedOffset`) is untouched; switching back
  to pan/select restores the user's own position automatically because only the RESTING position is
  overridden. The existing `LaunchedEffect(restingPos...)` springs the bar to the new resting position.
- Default behavior unchanged: draggable defaults OFF and the default anchor is bottom-center
  (`barTopY` > half ⇒ no yield).

### Bug 3 — Minimap text overflow
- `AnnotationCanvas.kt:3510-3547` — the Zoom / Layers / View HUD lines now use
  `maxLines = 1`, `TextOverflow.Ellipsis`, and `Modifier.fillMaxWidth()` so they fit the (possibly
  narrow) map header without ballooning height or spilling.

## Constraints honored
- No schema change / no migration.
- No new dependencies (only a new pure-JVM source file, `InkBarDrawingPolicy.kt`).
- No `.github/workflows/` edits.
- No canvas drawing-logic change (only minimap anchoring + ink-bar resting position + text styling).
- base-APK-size rule intact.

## Tests
- New `app/src/test/java/com/authorss81/noteflow/Phase244InkBarDrawingPolicyTest.kt` (6 tests):
  non-drawing tool never yields; drawing tool + top-half bar yields; bottom-half bar never nudged;
  degenerate zero-height never yields; boundary at midpoint.

## Verification
- `gradle :app:testDebugUnitTest` — **3562 tests / 0 failures / 0 errors** (includes existing
  `Phase129InkBarMinimapPolicyTest` green).
- `gradle :app:assembleDebug` — green.
- `gradle :app:assembleRelease` (R8 + shrinkResources + signed) — green.
- `gradle :app:lintDebug` — 0 errors.
