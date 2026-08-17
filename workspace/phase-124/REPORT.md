# Phase 124 — Two eraser types: whole-stroke delete & smooth shall partial erase

Status: **DONE** · verified `2026-08-17`

## Summary

The two eraser **modes already existed** (Phase 19: `EraserMode.STROKE` /
`EraserMode.PARTIAL`, picker chips, persistence, undoable, split-based partial
erase). Phase 124 closed the real gaps against the prompt's definition of done:

1. **Pressure-aware, round, smooth PARTIAL edge.** Every erase-path sample now
   carries the touch pressure captured at that instant
   (`EraseSample(pos, pressure)`), and the round mask radius is derived from
   `currentWidth` + pressure via a pure-JVM decision table
   (`EraserGeometryPolicy.stampRadius`) — heavier press = wider, rounder swath,
   no sharp steps. The cut is smooth because coverage swallows the nib
   half-width, so surviving-run boundary points are always outside the mask.
2. **Cursor preview for the active mode.** A non-consuming pointer tracker
   mirrors the pointer into world coords; the canvas draws a live round mask
   (PARTIAL) at the current-width coverage radius, or highlights every stroke
   the classic hit-test (incl. the symmetry mirror) predicts as deleted
   (STROKE).
3. **Hit-testing hardens.** `strokeContainsPoint` missed the `end` anchor, so a
   tap on the far tip of a shape stroke (rect/arrow/ellipse — geometry lives in
   `start`/`end`, no polyline points) could miss. Whole-stroke hit now covers
   both anchors; a pure-JVM `StrokeSegmenter.hitStrokeAt` mirror implements the
   same rule headlessly (topmost-stroke, symmetry-aware).
4. **Tests** pin the new geometry + hit rules (17 new tests) and the 16
   pre-existing segmenter tests stay green. `gradle testDebugUnitTest` (full app
   suite) + `gradle assembleDebug` pass.

No DB schema change, no migration, no new dependencies, allocation-free
per-point math, `.github/workflows/` untouched.

## Pre-existing vs Phase 124

Already live (Phase 19), reused:
- `EraserMode` enum + `fromSettingKey` — `StrokeSegmenter.kt:14-22`.
- Mode persisted in `SettingsManager.eraserModeKey` — `SettingsManager.kt:193`.
- Tool-picker chips + persistence callback — `EditorScreen.kt:401`,
  `EditorScreen.kt:1813-1820`.
- Eraser handling in the shared gesture:
  - `eraseSamples` accumulation + `applyEraser` — `AnnotationCanvas.kt:240`
    (`eraseSamples`), `AnnotationCanvas.kt:699-717` (`applyEraser`).
  - PARTIAL gating (`isFreehandTool && points > 1 && != LASER`) + segment fallback.
- **Undo for both erasers:** every erase change is delivered via
  `onStrokesChanged` → `EditorScreen.handleStrokesChange` (`EditorScreen.kt:588-596`),
  which captures the PRE-erase `strokes` snapshot on `undoStack` (cap 30), then
  swaps the list and `triggerAutoSave`s; `handleUndo` (`EditorScreen.kt:909`)
  restores it (persistence via the existing repository save path — no new
  schema). Wiring pin: `EditorScreen.kt:1615-1617`.
- `StrokeSegmenter.segment` run-splitting (fresh-`id` survivor `Stroke`s) —
  `StrokeSegmenter.kt:110-171`.

## Changes

### New: `app/src/main/kotlin/com/authorss81/noteflow/services/EraserGeometryPolicy.kt`
Pure-JVM decision table (allocation-free):
- `MIN_ERASE_WIDTH_PX=6`, `MAX_ERASE_WIDTH_PX=48`, `LIGHT_PRESSURE_SCALE=0.5`,
  `TAP_TOLERANCE_PX=4`, `WHOLE_STROKE_EXTRA_RADIUS=18`
  (`EraserGeometryPolicy.kt:26-38`).
- `stampRadius(baseWidth, pressure)` — linear light→full-pressure scale, clamped
  pressure, bounded width, +tap tolerance (`:48-52`).
- `coverageRadius(stamp, strokeWidth)` = stamp + half nib → surviving boundary is
  guaranteed outside the round mask (`:59-60`).
- `previewRadius(baseWidth, strokeWidth)` — cursor circle = full-pressure
  coverage of the current width, clamped `[MIN, 2*MAX]` (`:68-72`).
- `legacyRadius(strokeWidth, extraRadius)` — byte-compatible fallback
  (`:78-79`).

### Modified: `app/src/main/kotlin/com/authorss81/noteflow/services/StrokeSegmenter.kt`
- `ErasePoint` now carries a nullable `radius` (stamp radius for that sample;
  `null` = legacy rule) — `StrokeSegmenter.kt:32`.
- `coverageRadiusFor(stroke, sample, extraRadius)` — explicit stamp radius wins
  (`coverage(stamp, width)`), else legacy `width + extraRadius`
  (`:52-59`).
- `segment()` uses per-sample, per-point coverage (`:134`), so a single drag with
  varying pressure still produces one smooth merged cut.
- `strokeTouchedBy` (empty-points fallback) is radius-aware (`:179-195`).
- `hitStrokeAt(strokes, x, y, extraRadius, symmetryMode, cx, cy)` — pure-JVM
  whole-stroke hit-test; returns the LAST (topmost) touched stroke; checks
  `points` + `start` + `end`; honors the same symmetry mirror the canvas applies
  (`:68-96`).

### Modified: `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt`
- `data class EraseSample(pos, pressure)` — `:85`.
- `eraserCursorCanvas` world-coords state — `:246`.
- Erase drag now captures `EraseSample(canvasOffset, lastPressure)` on start and
  each move (`:798`, `:845`) and `applyEraser` (`:699`) builds
  `ErasePoint(x, y, EraserGeometryPolicy.stampRadius(currentWidth, pressure))`
  — `:705`.
- Non-consuming `pointerInput(currentTool)` cursor tracker (before the gesture
  detectors; never consumes, so zoom/tap/drag are unaffected); clears when no
  pointer is pressed — `:594-615`.
- In-canvas cursor preview (drawn after strokes, so it sits on top):
  - PARTIAL: filled + stroked circle at `previewRadius(currentWidth, currentWidth)`
    — `:1595-1603`.
  - STROKE: translucent path overlay over every stroke `strokeContainsPoint`
    predicts (with the symmetry mirror) — `:1604-1625`.
- `strokeContainsPoint` now also tests `stroke.end` (fixes the far-tip miss for
  point-less shape strokes) — `:3753-3756`.

### New test: `app/src/test/java/com/authorss81/noteflow/Phase124EraserTest.kt`
17 pure-JVM tests:
- Policy: monotonic ± bounded pressure/width behavior, coverage = stamp + half
  nib, preview window, legacy rule, null-radius vs explicit-radius behavior
  (`null radius means legacy, explicit radius wins`).
- Smooth round cut: `partial carve leaves only points outside the round mask`
  (strict `dist > coverage` for every survivor);
  `heavy pressure carves a wider swath than light pressure`;
  overlapping stamps merge into one continuous run split.
- STROKE hit: `hitStrokeAt returns topmost stroke` (last wins);
  `honors end anchors without points` (shape tips now hit);
  `honors the symmetry mirror`; `strokeTouchedBy covers start and end anchors`.
- Wiring pins: `canvas pins radius onto erase samples`
  (radius-carrying sample actually affects coverage/result);
  `partial and stroke modes map to distinct counts` (same touch deletes whole vs
  trims middle); symmetry double-apply round-trip; mode enum round-trip.

## Verification

- `gradle :app:testDebugUnitTest --tests Phase124EraserTest --tests StrokeSegmenterTest` — green.
- `gradle :app:testDebugUnitTest` (full app suite) — **BUILD SUCCESSFUL**, no
  failures/errors reported.
- `gradle :app:assembleDebug` — **BUILD SUCCESSFUL** (main source compiles with
  the new sample/radius plumbing + cursor draw code).

## Constraints checklist
- NO `.github/workflows/` edits. NO new dependencies. NO DB schema / migration.
- Base-APK size unaffected (pure Kotlin; no native/ML/network additions).
- No key/decrypted-content logging; `allowBackup=false` and data-extraction rules
  untouched.
- Low-end safe: per-point math is allocation-free; the shared `seg` loop keeps
  the same O(points × samples) shape as Phase 19.

## Notes / honest caveats
- The STROKE-mode "highlight" uses the same in-canvas predicate as the delete
  (not `hitStrokeAt`), because that predicate carries the canvas's `symmetryCenterFor`
  axis — the headless `hitStrokeAt` is the identical rule and remains the tested
  pure-JVM mirror of it.
- The cursor tracker clears when no pointer is pressed; if a dialog opens mid-gesture
  the preview may freeze until the next press/lift (cosmetic only, no delete happens).
- STROKE-mode partial symmetry erasing keeps Phase-19 behavior (mirror is applied
  by `erasesStroke`); the PARTIAL segment path still uses the real drag samples,
  unchanged by this phase.