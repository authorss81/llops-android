# Phase 226 — Selection Transform (scale / rotate selected strokes)

## Goal
With lasso selection (215) and copy/move (216), add **scale + rotate** handles so any selected strokes/shapes can be transformed — completing the selection suite.

## Context — verified anchors
- **Selection base:** `StrokeSelection` + `StrokeHitPolicy` / `LassoPolicy` from 215; copy/duplicate `216` uses `CanvasCommitListPolicy.emittedList`.
- **Existing handles:** embed/sticky `DraggableMediaEmbedCard:5474` 4 corners `minW 120..2000` + `RotationHandle:5930` via `services/CanvasItemRotationMath.kt:102-126` `rotationFromHandleDrag` normalized `(-180..180]`; `ResizeHandleVisibilityPolicy.kt:14-49` now dim-visible `0.45` after 217.
- **Strokes:** `PointF(x,y)` world coords, `start/end` shape anchors `StrokeModels.kt:141`; no `rotationDegrees` field yet — transform must be baked into points.

## Tasks
1. Overlay on `selectedBounds` rect (from 215): 4 corner handles (scale) + top rotation handle (reuse `DraggableMediaEmbedCard` composables). Handles **visible at rest** (`visibleAtRest=true`, alpha 1) for selection.
2. **Scale:** on corner drag `scale = newSize/oldSize` per axis (or uniform if locked via modifier); transform `points.map{ p -> center + (p-center)*scale }` + `start/end` same; clamp `minW 20` / `maxW 2000` like embeds.
3. **Rotate:** on handle drag `angle = CanvasItemRotationMath.rotationFromHandleDrag(drag, center)`; transform points via rotation matrix `x' = cx + (x-cx)*cos - (y-cy)*sin`. Bake into points, not a new field — no schema migration (per major-arch-change rule).
4. Single `handleStrokesChange` per gesture end → one undo entry, one `emittedList`, one autosave. Mid-drag preview via `DrawScope` dashed outline only (no commit).
5. Tests: `SelectionTransformPolicyTest` scale/rotate math (90° square → swapped coords), clamp, center preservation.

## Constraints
- No schema migration this phase (baked points only); if `Stroke.rotationDegrees` desired, defer to approved migration.
- No extra `withFrameNanos` pump; reuse main DrawScope.
- DoD: `assembleDebug` + `testDebugUnitTest` green; Paparazzi `selected 3 strokes scaled 1.5× + rotated 45°` PNG.

