# Phase 223 — Perspective Grid + Canvas Rotate + Straight-Line Ruler

## Goal
Drafting aids: **perspective/isometric grid**, **canvas rotate** (2-finger twist), **ruler/straight-line** snap — all reuse existing template/zoom math.

## Context — verified anchors
- **Grid:** `AnnotationCanvas.kt:3339-3531` `drawPaperTemplate` `grid 28dp 1f`, `dots 28dp r2`, `cornell` etc.; `TemplateLibraryDialog.kt:37` picker. `drawPaperCard 3280` flat fill.
- **Zoom/pan:** `AnnotationCanvas.kt:972` pinch `calculateZoom/calculatePan`, `924` `getToolType`, `1146` drag keys include `stabilizerStrengthPercent`. No rotate.
- **Line snap:** `ShapeRecognitionHelper.kt:13-222` `LINE direct/path>0.82` snap on `onDragEnd 1488` — freehand → crisp line; ruler is live preview variant.

## Tasks
1. **Perspective grid:** extend `drawPaperTemplate` with `perspective_1pt/2pt` + `isometric` options (vanishing point(s) at `pageWidth/2` horizon `h*0.35`, `isometric 30°` lines). Reuse `drawLine 1f` + `gridColor 0x94A3B8 0.22`. Settings per template as in 219 (color/spacing/opacity). Preview thumb in picker.
2. **Canvas rotate:** add `rotationDegrees:Float` state in `AnnotationCanvas` alongside `zoom/pan`; gesture `detectTransformGestures` `rotation` at `972` handler (keep `calculateZoom/Pan`); apply via `Modifier.graphicsLayer{ rotationZ = rotationDegrees }` already used for `graphicsLayer` param. Persist per page or session (settings `canvas_rotation_<page>`). Two-finger rotate only, single-finger still draws.
3. **Ruler:** when `RulerEnabled` (toggle next to `symmetryMode` `EditorScreen.kt:2582`), live preview draws straight segment `start→current` with `drawLine`; on lift `candidateStroke` `1488` snaps to exact `LINE` (bypass `perpendicularDeviation` gate). Haptic on snap.

## Constraints
- No schema, no heavy deps; rotate via existing `graphicsLayer`, no new `RenderNode`. Grid reuses `drawLine` only.
- DoD: `assembleDebug` + `testDebugUnitTest` green; `PerspectiveGridPolicyTest` (vanishing math) + rotation matrix test; Paparazzi perspective + isometric + rotated canvas PNG.

