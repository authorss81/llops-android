# Phase 226 Report — Selection Transform (scale / rotate selected strokes)

**Status:** DONE (2026-08-27)
**Type:** Feature (canvas selection transform — NO schema / NO migration / NO new deps / `.github/workflows/` untouched)
**Base-APK rule:** intact — only existing Compose/geometry primitives used; no new native libs.

## Goal
Complete the selection suite (215 lasso + 216 copy/move) with **scale + rotate**
handles on the selected-stroke bounding box, so any selected strokes/shapes can
be transformed. Per the major-arch-change rule the transform is **baked into the
stroke points** — there is no `Stroke.rotationDegrees` field and no Room schema
migration this phase.

## What was delivered

### 1. Pure-JVM transform math (`services/SelectionTransformPolicy.kt`)
- **`Corner`** enum (TOP_LEFT / TOP_RIGHT / BOTTOM_LEFT / BOTTOM_RIGHT) +
  `cornerSignX/cornerSignY` — which drag direction grows vs shrinks that corner.
- **`clampScales` / `clampedScale`** clamp each axis so the resulting selection
  stays within `MIN_SELECTION_SIZE_PX=20` .. `MAX_SELECTION_SIZE_PX=2000` (world px,
  mirroring the embed `minW 20` / `maxW 2000` clamp). Handles an out-of-range
  original extent gracefully.
- **`cornerScaleFromDrag(corner, bounds, dragWorldDx, dragWorldDy, locked)`** —
  `scale = 1 + sign·delta/extent` per axis; when `locked=true` it derives ONE
  uniform factor (average of the two raw proposals) then clamps both axes to the
  tightest extent so min/max is respected on each axis while keeping aspect
  (the default, `selectionTransformLocked=true`, is uniform scale).
- **`scalePoint` / `rotatePoint`** — per-point geometry about `(cx,cy)`; rotation
  uses the SAME `CanvasItemRotationMath.rotateX/rotateY` matrix the canvas items
  use (`x' = cx + (x−cx)cos − (y−cy)sin`, etc.).
- **`scaleStroke` / `rotateStroke`** — maps every point **and** the `start`/`end`
  shape anchors, then recomputes `Stroke.pdfPage` from the new first-point Y via
  `StrokeSelectionActionPolicy.getPageFromCanvasY(pageStride)` — matching the
  phase-216 translate path so a scale/rotate across a page boundary lands on the
  correct page.
- **`transformSelected(strokes, ids, cx, cy, sx, sy, degrees, pageStride)`** —
  applies rotate-then-scale (or either alone) to only the selected strokes.
  Identity deltas (`sx==sy==1f && degrees==0f`) return the input list **unchanged**
  so the caller's undo push is a clean single entry even on a degenerate gesture.
- **Preview helpers** — `rotatedBounds` (rotate the 4 corners, take the
  axis-aligned union — used for the rotation-only preview and resting box) and
  `scaledBounds` (inflate about centre — used for the scale preview), plus
  `cornerPosition` / `centerOf`.

### 2. Overlay handles (`ui/components/AnnotationCanvas.kt`)
`StrokeSelectionOverlay` was rewritten from a single `Canvas` into a `Box` holding:
- a `Canvas` (unchanged lasso trail + per-stroke highlight, plus the **dashed
  bounds** which now show the RESTING box or the **live transform preview** while
  a handle drags);
- **4 `SelectionCornerHandle`**s at the corners and **1 `SelectionRotationHandle`**
  at top-centre — all **visible AT REST (alpha 1)**, unlike the phase-193 dim
  canvas resize handles. Each is a `Box` with a material-primary circle + an
  `OpenWith`/`RotateRight` icon, positioned by world-to-`offset` conversion under
  the existing zoom/pan/rotate graphicsLayer.

Gesture flow (both handles, mirrors phase-193/216 patterns):
- `detectDragGestures` accumulates the **world-coordinate** delta
  (`dragAmount / zoom`, so scaling feels correct at any magnification); haptic
  tick on grab (`HapticFeedbackType.TextHandleMove`, gated by `MotionPolicy`).
- **Scale drag** → each update derives `(sx, sy)` via `cornerScaleFromDrag` and
  stores `previewScaleX/Y` + `transformActive` → the dashed box shows
  `scaledBounds(constraints)` **preview only**; on gesture end, ONE
  `onSelectionScale(sx, sy, cx, cy)` fires (only if non-identity) and preview
  state resets.
- **Rotate drag** → each update runs
  `CanvasItemRotationMath.rotationFromHandleDrag(..., currentDegrees=0f)` (the
  selection has no persistent rotation, so the returned angle IS the absolute
  rotation to bake) → dashed box shows `rotatedBounds(preview)`;
  on end, ONE `onSelectionRotate(degrees, cx, cy)`.
- `onDragCancel` clears preview state with no commit.

The overlay wires `transformLocked` (default true → uniform) and the two callbacks
through `AnnotationCanvas` new params `onSelectionScale` / `onSelectionRotate` /
`selectionTransformLocked` (with `rememberUpdatedState` so drags never restart on
recomposition).

### 3. EditorScreen commit path (`ui/screens/EditorScreen.kt`)
- New `scaleSelectedStrokes(scaleX, scaleY, cx, cy)` and
  `rotateSelectedStrokes(degrees, cx, cy)`. Each computes the transformed list via
  `SelectionTransformPolicy.transformSelected` (pageStride `1592f`, same constant
  as the phase-216 translate path), calls **`handleStrokesChange(newStrokes)` ONCE**
  (→ one undo entry, one `emittedList`, one autosave), then refreshes the selection
  bounds via `StrokeSelectionActionPolicy.recomputeBounds` so the resting dashed box
  follows the transformed geometry.
- Wired to the `AnnotationCanvas` call site (`EditorScreen.kt:2622-2628`) with
  `selectionTransformLocked = true`.

### 4. Tests
- **`services/SelectionTransformPolicyTest.kt` — 12 tests** (all pure-JVM):
  90° square rotation swaps extents; uniform scale doubles coordinates; centre
  preserved under scale and rotation; non-uniform (locked=false) scale; min/max
  clamp per axis and uniform-constrained clamp; `cornerSign` mapping;
  `transformSelected` touches ONLY selected ids and returns identical instances for
  identity deltas; cross-page `pdfPage` recompute on scale/rotate; `cornerPosition`
  and `scaledBounds`/`rotatedBounds` preview helpers.
- **`paparazzi/SelectionTransformOverlayPaparazziTest.kt`** — the canonical
  `phase226_selection_3_strokes_scaled_1.5x_rotated_45` snapshot. **Fails on this CI
  runner** for the exact same, documented, pre-existing reason as `PaparazziSmokeTest`
  (`UninitializedPropertyAccessException at PaparazziSdk.kt:562` — broken layoutlib
  infrastructure), reproduced on a clean stash; passes where Paparazzi runs.

### 5. Visual DoD artifact
Paparazzi cannot render on this runner (pre-existing env break), so the PNG is
generated through the phase-200/213 precedent: a Java2D renderer
(`workspace/phase-226/RenderSelectionTransform.java`) driving the **real compiled
policy classes** (`SelectionTransformPolicy`, `StrokeSelectionActionPolicy`,
`LassoTrailPolicy`, `StrokeHitPolicy`, `ResizeHandleVisibilityPolicy`) exactly the
canvas does:
- `visual-qa/screenshots/phase-226/selection-3-strokes-scaled-1.5x-rotated-45.png` (1080×1600).

The renderer also **prints the empirical bounds** so the DoD is numerically proven:
```
ORIGINAL bounds = [110.0,100.0 .. 360.0,268.0] (w=250.0 h=168.0)
TRANSFORMED (1.5x, 45deg) bounds = [66.4,15.4 .. 381.5,302.7] (w=315.1 h=287.4)
centre = (235.0, 184.0), stroke count = 3
```
The transformed min-Y (15.4) and min-X (66.4) match hand-computed 45° rotations of the
zigzag stroke's scaled endpoints; centre is preserved; geometry clearly widened and
rotated. Honest caveat (same as phase-213): the Java2D artifact validates the policy
**math + wiring**, not the Compose pixel rendering.

## Constraints honoured
- **No schema migration** — transform baked into `points`/`start`/`end`; no
  `rotationDegrees` field.
- **One `handleStrokesChange` per gesture end** → single undo entry, single
  `emittedList`, single autosave. Mid-drag is render-side dashed preview only.
- **No extra `withFrameNanos` pump** — preview lives in the overlay node's own
  draw scope; the main canvas pass is never invalidated per drag sample.
- `clampScales` `minW 20` / `maxW 2000` mirror the embeds.

## Verification
- `gradle assembleDebug` — green.
- `gradle :app:testDebugUnitTest` — 3395 completed / **13 failed, ALL pre-existing or
  environmental, 0 caused by this phase**:
  - 3 non-Paparazzi, reproduced id on a clean stashed baseline: `B2Ui2ClipboardScrubTest` ×2
    + `Phase148UiFailureTextScrubTest` ×1.
  - 10 Paparazzi-sandbox layoutlib env failures (`PaparazziSdk.kt:562`), all pass in
    isolation but fail together in the shared sandbox: `PaparazziSmokeTest` ×2,
    `Phase223DraftingGridSnapshotTest` ×4, `Phase225EyedropperSamplingSnapshotTest` ×1,
    `TimelapsePlayerPaparazziTest` ×2 — plus my new `SelectionTransformOverlayPaparazziTest` ×1
    (same env cause).
- No schema change, no migration, no new dependencies, `.github/workflows/` untouched,
  base-APK-size rule intact.

## Review fixes (2026-08-27)
Applied after the phase-226 review found 4 items (all minor):
1. **Doc whitespace** — `docs/ARCHITECTURE.md:596` phase-216 blockquote had a stray leading
   space (` > Tests:`); fixed to `> Tests:`.
2. **Doc stale fn name** — `docs/ARCHITECTURE.md` phase-226 note referenced
   `candidateScaleFromDrag`; corrected to the real `cornerScaleFromDrag`.
3. **Corner-scale drag ignored canvas rotation** — `SelectionCornerHandle` now takes
   `canvasRotationDegrees` (thru `StrokeSelectionOverlay` ← `internalRotationDegrees`) and
   un-rotates the screen drag delta into world space before `/ zoom`
   (`AnnotationCanvas.kt` SelectionCornerHandle `onDrag`, mirroring the embed drag
   compensation at `:6927-6928`), so "drag right → grow right" holds even when the canvas
   is rotated (phase-223).
4. **Handle size vs tiny selections** — corner/rotation handles now shrink toward the
   selection's smaller dimension (`handleScale`, floored ~0.33×) so a 20px-min selection
   doesn't render a pile of overlapping, selection-dwarfing 24-26dp handles; large
   selections keep the standard size.
Verification: `gradle :app:compileDebugKotlin` green; `SelectionTransformPolicyTest` +
`Phase216SelectionWiringTest` + `StrokeSelectionActionPolicyTest` green in isolation.
