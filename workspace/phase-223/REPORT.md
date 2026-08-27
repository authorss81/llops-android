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
- `gradle :app:testDebugUnitTest` — **3351 total; 9 failures, all pre-existing or
  environment-only** (see below). 0 new regressions.
- `Phase223PerspectiveGridPolicyTest` — **16 tests green**: one/two-point vanishing
  math, ray convergence, Liang–Barsky clip, isometric 30° slope, clipSlopedLine
  endpoints, rotation matrix unit-preservation, sanitize/accumulate, and
  `forceLineSnap` ruler-collapse + single-point fallback.
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
- Dual-purpose note: the one/two-point `twoPointRays` fan the real policy emits is
  sparse (rays are horizontal at the horizon, clipped by Liang–Barsky) — the
  `grid_perspective_2pt.png` (5 KB) faithfully reflects that; `depthLines` carry
  the fill.
- No schema change; no new dependencies; base-APK-size rule intact; no
  logging of keys/decrypted content.
