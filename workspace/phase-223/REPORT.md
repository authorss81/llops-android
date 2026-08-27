# Phase 223 Report — Perspective Grid + Canvas Rotate + Straight-Line Ruler

Drafting aids: perspective/isometric grids, two-finger canvas rotate, and an
explicit straight-line ruler. All reuse existing template/zoom math. No schema
change, no new base-APK deps, `.github/workflows/` untouched.

## Tasks delivered

### 1. Perspective / isometric paper templates
- New pure-JVM `services/PerspectiveGridPolicy.kt` centralises the vanishing-point
  and isometric line math shared by the renderer, the picker preview, and the tests:
  - `onePoint()` — horizon at `h*0.35`, vanishing point at `pageWidth/2`.
  - `depthLines()` — horizon-parallel lines spaced `0.06*width`.
  - `onePointRays()` — bottom-of-page → VP fan (`0.045*width` sampling).
  - `twoPoint()` / `twoPointRays()` — two VPs 55% of width outside each edge,
    rays clipped to the page rect via Liang–Barsky (`clipRay`).
  - `isometric()` / `isometricDiagonals()` / `clipSlopedLine()` — 30° lattice
    (left/right diagonals + verticals).
- `AnnotationCanvas.drawPaperTemplate` extended with `perspective_1pt`,
  `perspective_2pt`, `isometric` cases using the policy + `gridColor`, with the
  horizon emphasised.
- Editor paper-template picker (EditorScreen third-row chips) and `TemplateLibraryDialog`
  customisable-paper gating (`hasCustomizablePaper`, spacing/opacity lists) extended with
  the three drafting templates.
- New `PaperTemplatePreview` composable renders the real `PerspectiveGridPolicy`
  geometry as the 40dp picker thumbnail (not a hand-drawn fake).

### 2. Canvas rotate (two-finger twist)
- `services/CanvasRotationPolicy.kt` (pure JVM): `sanitize`, `rotatePoint`,
  `accumulate` (bounded ±360 with near-360→0 clamp).
- `AnnotationCanvas` gained `rotationDegrees`/`onRotationDegreesChanged`/`canvasTwistEnabled`
  params; `internalRotationDegrees` state + `rememberUpdatedState` holders.
- Two-finger twist folded into the existing manual pinch/pan handler (diff of
  `calculateRotation()` via `pastRotation`), gated by `currentCanvasTwistEnabled` —
  single-finger drawing is untouched.
- `rotationZ = internalRotationDegrees` applied to all three world-transform
  `graphicsLayer` blocks (main canvas / live-preview / selection overlay).
- Per-page persistence via `SettingsManager.canvasRotationDegreesForPage` /
  `setPageCanvasRotationDegrees` (`canvas_rotation_<page>`, no schema change), plus
  `rulerEnabled` (default off) and `canvasTwistEnabled` (default on) prefs.
- EditorScreen: rotation state keyed on `page.id`; rotation-reset + ruler/twist
  toggles in `CanvasSettingsBottomSheet`; `onResetZoomPan` also resets rotation.

### 3. Straight-line ruler
- `ShapeRecognitionHelper.forceLineSnap` — collapses ANY freehand drag to an exact
  start→end `LINE`, bypassing the `perpendicularDeviation` fit gate used by
  auto-snap.
- Ruler wired in `AnnotationCanvas.onDragEnd`: `rulerForcingLine` produces a
  `SnappedShape(LINE, forceLineSnap(...))` that takes precedence over
  `shapeAutoSnapEnabled`; the existing long-press haptic fires via the shared
  `snappedShape != null` path.
- Live ruler preview: while `rulerEnabled`, a straight `start→current` segment is
  drawn over the freehand preview via `LiveStrokePreview`.
- `rulerEnabled` added to the drag `pointerInput(...)` key list so the closure
  captures the live toggle (this required updating the Phase197 source-pinning
  test, which pins that exact key list).

## Verification
- `gradle :app:assembleDebug` — **BUILD SUCCESSFUL**.
- `gradle :app:testDebugUnitTest` — **3354 total; 9 failures, all pre-existing or
  environment-only** (see below). 0 new regressions.
- `Phase223PerspectiveGridPolicyTest` — **20 tests green** (16 original + 5 review-fix
  regression pins): one/two-point vanishing math, ray convergence, bottom-edge two-point
  fan receding to the off-page VPs, Liang–Barsky clip, isometric 30° slope,
  clipSlopedLine endpoints, rotation matrix unit-preservation, sanitize/accumulate
  (incl. the clamp-not-fold-back + stepFactor pins), and `forceLineSnap` ruler-collapse +
  single-point fallback + `rulerLineEligible`.
- Paparazzi suite `Phase223DraftingGridSnapshotTest` (perspective_1pt/2pt,
  isometric, rotated-canvas) delivered for CI.

### Remaining 9 failures (all pre-existing / environment)
1. `Phase223DraftingGridSnapshotTest` (4) + `PaparazziSmokeTest` (2): Paparazzi
   cannot run in this sandbox (no native layoutlib — `UninitializedPropertyAccessException`
   at `PaparazziSdk.kt:562` / `NoSuchElementException` at `Renderer.kt:215`). The
   suite is source-compiled and runs in CI where Paparazzi works.
2. `Phase148UiFailureTextScrubTest` (1): the known pre-existing UNC-path failure
   documented in AGENTS.md (untouched).
3. `B2Ui2ClipboardScrubTest` (2): reproduced on a clean stash (without any
   phase-223 change) — pre-existing, not caused by this phase.

### Phase-223 PNG deliverable
Paparazzi snapshots ship as the CI Paparazzi suite. Because Paparazzi cannot render
in this sandbox, four viewable PNGs were additionally generated from the SAME
(unit-tested) `PerspectiveGridPolicy` line families via an ImageIO renderer:
`grid_perspective_1pt.png`, `grid_perspective_2pt.png`, `grid_isometric.png`,
`grid_rotated_canvas.png` (20° rotationZ through the graphicsLayer mechanism).

## Notes
- Compose `toRadians` (Float/Double extension) is not available in this Kotlin
  version — `java.lang.Math.toRadians(double)` used in both services and tests.
- `calculateRotation` required an explicit import.
- No schema change; no new dependencies; base-APK-size rule intact; no
  logging of keys/decrypted content.

## Review fixes (2026-08-27)
Applied after the phase-internal review of commit `e23b27b`; the stale "sparse
fan" note above (the `twoPointRays` fan being horizontal at the horizon) was the
symptom of finding 3 and is superseded here.

1. **Telescoping rotation** — removed the `pastRotation` differential in the
   two-finger handler; `val rotationChange = event.calculateRotation()` is a
   per-event delta in Compose 1.7.6 (`TransformGestureDetector.kt:107-115`), so
   accumulating it directly is exact and never telescopes.
2. **Rotation re-sync** — added `LaunchedEffect(rotationDegrees)` that mirrors
   `internalRotationDegrees = sanitize(rotationDegrees)` (same pattern as the
   existing zoom/pan sync), so page-switch/Reset never leaves the canvas angle
   stale vs. the persisted pref.
3. **Degenerate two-point fan (REAL BUG)** — `twoPointRays` used to emit rays
   with both endpoints on the horizon, which Liang–Barsky collapsed to
   zero-length lines (the old 5 KB `grid_perspective_2pt.png` was nearly empty).
   Rays now start on the page bottom edge (`clipRay(x, g.height, vp, horizonY,
   …)`) and recede toward the off-page VPs, clipped to the rect — matching the
   one-point fan convention.
4. **Line-spacing override was a no-op for drafting templates** — `depthLines`,
   `onePointRays`, `twoPointRays`, `isometricDiagonals` now take a `stepFactor`
   (default `1f`); `drawPaperTemplate` passes
   `stepFactor = (templateOverrides.lineSpacingDp ?: 28f) / 28f` so the
   phase-219 spacing chips (24/28/36dp) genuinely change grid density.
5. **Ruler preview/commit mismatch** — the live ruler guide is now suppressed
   through the SAME exclusion set the commit path uses (LASER, wet-rendered,
   DOTTED/NEON/CHARCOAL/OIL_PASTEL/DRY_BRUSH/PALETTE_KNIFE) so a straight guide
   is never shown over a stroke that gets committed freehand.
6. **Dead picker thumbnail** — `TemplateLibraryDialog` had three drafting
   entries whose thumbnail surface always rendered the previous template.
   Added 3 drafting `WorkspaceTemplate` entries
   (`perspective_1pt_notes`, `perspective_2pt_notes`, `isometric_notes`),
   gated the thumbnail to drafting templates only, and re-used the new
   `draftingPaperTemplates` list for the inline spacing/opacity gates.
7. **Degenerate zero-length ruler LINE** — `rulerForcingLine` now also requires
   `ShapeRecognitionHelper.rulerLineEligible(candidateStroke)` (min drag
   distance `MIN_RULER_LINE_DISTANCE_PX = 15f`), so taps/hairlines never commit
   a zero-length LINE.
8. **±360 discontinuity** — `CanvasRotationPolicy.sanitize` no longer snaps
   359.5° → 0° (a mid-gesture visual jump); it only clamps to the ±360 bounds.
   `rotatePoint` doc corrected (positive rotationZ = clockwise in y-down screen
   space).

Regression pins added to `Phase223PerspectiveGridPolicyTest` (now 20 tests):
`sanitize_boundsWithoutDiscontinuousSnap`, `accumulate_sumsIntoSanitizedTotal`
(clamp, no fold-back), `stepFactor_scalesLineFamiliesWithoutBreakingGeometry`,
`twoPoint_raysRecedeFromBottomAndConvergeOnTheVanishingPoints` (bottom-edge
start, upward recede, VP collinearity), `rulerLineEligible_acceptsRealDragsRejectsTapsAndHairlines`.

Verification after fixes: `gradle :app:assembleDebug` green; full suite
**3354 total / 9 failures — the same pre-existing or environment-only set
(4×Paparazzi drafting snapshots + 2×PaparazziSmoke + 1×Phase148 UNC + 2×
B2Ui2Clipboard)**, 0 regressions. `grid_perspective_2pt.png` regenerated from
the corrected geometry (148 KB vs the degenerate 5 KB).
