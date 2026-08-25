# Phase 222 — Alpha-Lock + Clipping Mask + Tilt Shading

## Goal
Layer-level **alpha-lock** (paint only where ink exists) + **clipping mask** (layer clips to layer below) + **tilt shading** (stylus angle → width/opacity) — three pro masking/angle features sharing the compositing path.

## Context — verified anchors
- **Compositing:** `AnnotationCanvas.kt:3790-3949` groups by `layerId` `3790`, `applyLayerBlend` `3902` (`NORMAL/MULTIPLY/SCREEN… 3641`), `opacity` per `LayerEntity:76-89`. No alpha-lock/clipping. `isLayerLocked` gate `462`.
- **Tilt captured unused:** `pointerInteropFilter:918-919` `getAxisValue(AXIS_TILT)` → `lastTilt` degrees, passed as `PointF.tilt` but never maps to width/opacity (only `pressure` via `PressureCurveHelper` + `velocityWidthFactor 0.55-1` `BrushStrokeMath:55`).
- **Texture:** `BrushTextureEngine` already has `pressure 0.4+0.6p` alpha — tilt can parallel it.

## Tasks
1. **Alpha-lock:** per-layer `alphaLock:Boolean` (transient `SettingsManager` / `LayerEntity` extra without migration? use `layerId+"_alphaLock"` settings key v1, settings-only, no DB migration). In `drawCompositedLayersStrokes`, when enabled, clip new strokes to `dstIn` of existing layer bitmap: `saveLayer { draw existing bitmap as alpha mask (SRC_IN) ; draw strokes }` via `PorterDuff.Mode.DST_IN`.
2. **Clipping mask:** per-layer `clippingMask:Boolean` → layer `n` content clipped to `n-1` bitmap alpha same `DST_IN` technique, reuse mask bitmap (no new alloc).
3. **Tilt shading:** map `tilt 0-90°` → `widthFactor = 1 + 0.4*sin(tilt)`, `alphaFactor = 0.6+0.4*cos(tilt)` in `drawSingleStroke` before `drawPath`; gate `if(tilt>0)` else 1. `CHARCOAL/PENCIL` most visible. Pure function `TiltShadingPolicy` + tests.
4. UI: layer row toggles (lock icon row `EditorScreen.kt:890`) add alpha-lock (🔒 checker) + clipping (↓ clip) chips; tilt toggle in brush settings.

## Constraints
- No schema migration — settings keys only; no workflow edits; reuse existing bitmaps, no per-layer new `Bitmap`.
- DoD: `assembleDebug` + `testDebugUnitTest` green; `TiltShadingPolicyTest` + `AlphaLockClippingTest` (mask math); Paparazzi alpha-lock vs normal PNG.

