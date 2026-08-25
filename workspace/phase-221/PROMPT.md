# Phase 221 — Fill Bucket + Gradient Drag

## Goal
One-tap **fill** (flood) and drag **gradient** — first non-brush paint tools, tolerance-aware and layer-respecting.

## Context — verified anchors
- **Layers bitmap available:** `ui/components/AnnotationCanvas.kt:3863` `LayerBitmapLruCache` key `"${page}_${layer}_v${vibrancy}"` → `BitmapPool.acquire(pw,ph)` `3879` already rasterizes layer for wet pass (`3955-4151`). No flood-fill exists (grep 0).
- **Color:** `BrushColorModeMath.kt:23-250` `RAINBOW/GRADIENT/SHIMMER` + `ColorVibrancy.kt` + `Stroke.colorInt`; `StrokeTool` enum `StrokeModels.kt:7` has no FILL/GRADIENT tool.
- **Hit/undo:** `services/CanvasCommitListPolicy.kt:33` single commit source; undo `EditorScreen.kt:469` full-list stack max30.

## Tasks
1. Add `StrokeTool.FILL` + `GRADIENT` to `StrokeTool` enum (keep `isFreehandTool` false for fill, or handle separately). `FILL` tap → flood-fill scan from `canvasOffset` on **active layer bitmap** copy, tolerance `12%` in linear RGB (reuse `AgslShaders srgbToLinear`), 4-way scan with stack `ArrayDeque`, bounds `MAX_POINTS_PER_PAGE` guard `StrokeGeometryPolicy:45`.
2. Fill result = **one closed Stroke** with `points` = outer contour (approx via `Path` `addRect` of filled region, or keep as raster? choose vector contour via `RamerDouglasPeucker` `epsilon 1.3` to keep stroke model intact). Persist as normal `Stroke` (no new entity) — single `handleStrokesChange`.
3. `GRADIENT`: drag `start→end` → `Brush.linearGradient` `Shader` along vector, `colorInt` → `gradientToColorInt` already in `StrokeModels.kt:141` — reuse `derivedColorAt` path. Draw as `Path` rect fill of active layer bounds clipped to drag vector.
4. Respect `isLayerLocked` `462` and active `layerId`; fill never leaks across layer. Respect `paperTexture` underlay, not over.
5. Tests: `FillToleranceTest` pure JVM (seed bitmap 3×3, tolerance boundary), gradient vector → colorAt 0/0.5/1.

## Constraints
- No schema, no new native deps, max one `Bitmap.copy` per fill (recycle after). Do not allocate inside loop without pool.
- DoD: `assembleDebug` + `testDebugUnitTest` green; Paparazzi fill `tolerance 12` + gradient `left→right` PNG; REPORT.md.

