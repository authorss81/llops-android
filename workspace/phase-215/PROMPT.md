# Phase 215 — Lasso Stroke Select (SELECT tool becomes real selection)

## Goal
Turn dead `StrokeTool.SELECT` (today just pans — `AnnotationCanvas.kt:1236-1239` + `1325-1327` early return) into a real **lasso + box-marquee** stroke selection with visual overlay. Foundation for copy/move/duplicate/delete.

## Context — verified anchors
- **SELECT is dead:** `data/model/StrokeModels.kt:8,46` defines `SELECT` but `isFreehandTool==false`; `AnnotationCanvas` treats `SELECT||PAN` as single-finger pan (`1236-1239`, `1325-1327`); `onStrokesChanged` guard excludes SELECT (`1418`); `EditorScreen.kt:135-138` `lastDrawingTool` filters out SELECT/PAN.
- **No selection state today:** `grep lasso` 0 hits; `grep select|lClipboard` only hits `selectedStickerId` + `ClipboardGuard` for code-block copy (`services/ClipboardGuard.kt:37-109`, `ui/components/MediaEmbedComponents.kt:353-357`) + OCR SelectionContainer untracked. No `selectedStrokeIds`, no bounding-box, no Path.contains. Eraser highlight `width+10f alpha .25` at `AnnotationCanvas.kt:3070` is closest overlay precedent.
- **Stroke storage:** `StrokeModels.kt:141-163` `Stroke(id,tool,color,width,points,start/end,pdfPage,layerId,colorMode…)` + `PointF(x,y,pressure,tilt,timestampMs)`; `Entities.kt:51-74` per-page encrypted `pointsJson`; `NoteRepository.kt:1055-1130` bounded load/decrypt; undo `EditorScreen.kt:469-483,862-870` pushes full `List<Stroke>` max 30; lock-safe autosave `VaultWriteGate.requireKey` + `EditorFlushPolicy` (`EditorScreen.kt:842-860`).
- **Hit primitives exist:** `StrokeSegmenter.kt:68-96` `hitStrokeAt`, `StrokeSegmenter.coverageRadiusFor`, `EraserGeometryPolicy.TAP_TOLERANCE_PX` / `stampRadius`; canvas paging `getPageFromCanvasY` `516,543,1240` stride `1528+64`; layers `layers/activeLayerId/isLayerLocked` `462-464,1220`.
- **Resize/rotation handles** `AnnotationCanvas.kt:5040-5995` draggable blocks + `services/ResizeHandleVisibilityPolicy.kt:14-49` (`visibleAtRest=false` until drag) — precedent for handle overlay but selection should be **visible at rest**.

## Tasks
1. **Model:** `data class StrokeSelection(ids:Set<String>, bounds:Rect, layerId:String?)` transient only (remember/SnapshotStateList alongside `undoStack` in `EditorScreen.kt`), clears on page switch but survives config change; per-page map if needed. No Room migration.
2. **Hit policy (pure JVM):** new `services/StrokeHitPolicy.kt` + `services/LassoPolicy.kt`
   - Box: `rect.contains(point)` or segment-rect intersection; rule strokes test `start/end` segment.
   - Lasso: winding-number point-in-polygon for each `stroke.points + start/end`, tolerance `coverageRadius = EraserGeometryPolicy.coverageRadius + width*0.5` / `TAP_TOLERANCE_PX`.
   - Layer-aware: skip `locked`/`!visible` layers (honor `isLayerLocked` gate), respect `activeLayerId` default scope.
   - Unit tests: box/lasso hit/miss, tolerance boundary, locked-layer exclusion.
3. **Gesture repurpose:** inside `AnnotationCanvas.kt:1146` drag block add `if (currentTool==SELECT)` branch distinct from PAN two-finger `972` pan/zoom: `onDragStart { lassoPath = mutableListOf(canvasOffset) }`, `onDrag { lassoPath += canvasOffset }`, `onDragEnd { selectedIds = strokes.filter{ lassoContains(it, closedLassoPath) }.map{it.id} }`. Box marquee = rectangle lasso fallback for single straight drag (detect via `direct/pathLen>0.98`).
4. **Rendering:** dashed `Path` overlay (`StrokeStyle dashed`) + per-selected-stroke highlight (reuse eraser highlight alpha but distinguish color) in same draw-scope, no extra `withFrameNanos` pump. Handle like `ResizeHandleVisibilityPolicy` but **visible at rest** for selection (override `visibleAtRest=true`).
5. **Toolbar:** `SELECT` chip in `FloatingToolDock` / tool picker becomes toggle; bottom bar shows count `"3 selected"` + actions `Copy/Duplicate/Delete/Clear` (actions wired in phases 216 but UI stubs with snackbars OK). Undo: selection not undoable, mutations are (via `handleStrokesChange` single push).
6. **A11y/discovery:** TalkBack `"3 strokes selected"` announcement, Escape clears selection.

## Constraints
- No schema, no workflow edits, no new heavy deps. Keep `pointerInteropFilter` pressure/tilt capture intact. Pan remains on `PAN` tool + two-finger gesture.
- DoD: `assembleDebug` + `testDebugUnitTest` green; new `StrokeHitPolicyTest`/`LassoPolicyTest` ≥15 cases; Paparazzi screenshot `SELECT lasso overlay` added; REPORT.md documents repurposed gesture + hit tolerance + layer scoping.
