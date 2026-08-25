# Phase 225 — Eyedropper from Reference Image + Paper Sampling

## Goal
**Eyedropper** samples color from anywhere — including the dimmed **reference image** underlay — so palette building from a photo works.

## Context — verified anchors
- **Eyedropper today:** `AnnotationCanvas.kt:1022` `detectTapGestures` for `EYEDROPPER`, but no sampling math; existing `PhotoEmbedCard` decode `rawW/rawH` not sampled; `drawReferenceImage:3543` dim underlay `alpha=clampOpacity` between template and bitmap/ink `224-230` — rendered but not sampled.
- **Color pipeline:** `PaletteCatalog.kt:12-280` curated swatches + `ColorHarmonyHelper:21` + `BrushColorModeMath`, `EditorScreen.kt:1022` color picker already.
- **Reference image:** `CanvasMediaEmbedType.REFERENCE_IMAGE` one per page `EditorScreen.kt:196` `textContent=ReferenceImagePolicy.encodeConfig(opacity)` `730-757` `fitForPage` centered fit; path-confined.

## Tasks
1. **Sampling:** on `EYEDROPPER` tap, read pixel from composited view: first test `referenceImage` bounds `referenceImageX/Y/Width/Height` — if hit, decode `BitmapFactory.decodeFile(path)` sampled `1:1` region `getPixel((x-refX)/scale, (y-refY)/scale)` → `Color(argb)`. Else sample `LayerBitmapLruCache` raster at tap `Offset` (read `cache.bitmap.getPixel`). Fallback `paperColor`.
2. **Apply:** `onColorSelected(sampled)` updates `currentColor` + `viewModel.settings.lastColorHex` same as palette pick; show `Snackbar "Picked #RRGGBB"` + dismiss eyedropper to `lastDrawingTool`.
3. **Reference discoverability:** ensure reference image is drawn **before** grain `3299` so sampled color is dim-aware or raw — choose raw (undimmed) for fidelity and document; add `contentDescription "Reference image, tap eyedropper to sample"`.
4. **Tests:** `EyedropperSamplingTest` pure JVM (offset→bitmap coord, OOB fallback, reference hit vs layer hit priority).

## Constraints
- No new deps, no schema, decode `Bitmap` sampled and recycled immediately (no leak via `BitmapPool`). Keep `EYEDROPPER` distinct from `SELECT/PAN` pan gates `1236`.
- DoD: `assembleDebug` + `testDebugUnitTest` green; Paparazzi eyedropper sampled swatch; REPORT.md sampled pixel trace.

