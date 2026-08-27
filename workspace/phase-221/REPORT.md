# Phase 221 — Fill Bucket + Gradient Drag

## Summary
Implemented the first non-brush paint tools: one-tap **flood fill** and drag **gradient**, both tolerance-aware and layer-respecting.

## Changes

### 1. `StrokeTool` enum — `StrokeModels.kt:9`
Added `FILL` and `GRADIENT` to the enum. Neither is `isFreehandTool` nor `isShapeTool`; both fall into a new implicit "paint tool" category. Labels: "Fill Bucket" / "Gradient Drag".

### 2. `FloodFillEngine.kt` — new file, `services/FloodFillEngine.kt`
Pure-JVM flood fill core operating on raw ARGB `IntArray` pixels:
- **4-way iterative stack** (`ArrayDeque<Int>`) — no recursion, no allocation inside the loop.
- **Linear RGB tolerance** — channel bytes converted to linear light via `srgbChannelToLinear` (mirrors `WetMixingMath.srgbToLinear`); tolerance = max-channel deviation in linear RGB. Default 12% matches the PROMPT spec.
- **Bounds guard** — returns empty when `w × h > MAX_POINTS_PER_PAGE` (200K).
- **Bitmap adapter** — `floodFillBitmap()` does ONE `Bitmap.copy`, extracts pixels, fills, recycles the copy.

### 3. AnnotationCanvas wiring — `AnnotationCanvas.kt`
- **FILL tap** (line ~1237): in the `detectTapGestures` `onTap` handler, after SELECT. Checks `!isLayerLocked`, samples the seed pixel via `EyedropperSamplingMath.canvasToPagePixel`, runs `FloodFillEngine.floodFillBitmap`, computes the bounding box from the filled pixel list, emits a `Stroke(tool=FILL, points=filledPixels)` through `CanvasCommitListPolicy.emittedList`.
- **GRADIENT drag** (lines ~1494, ~1626, ~1841): in `onDragStart`, records `gradientDragStart`; in `onDrag`, updates `gradientDragCurrent`; in `onDragEnd`, commits a `Stroke(tool=GRADIENT, colorMode=GRADIENT, points=[start, end])` covering the full page bounds. State cleanup in `onDragCancel`.
- **Layer lock guard** — both tools blocked when `isLayerLocked` (FILL checked in tap handler, GRADIENT blocked by the existing `isLayerLocked` guard in `onDragStart`).
- **State variables** — `gradientDragStart` / `gradientDragCurrent` (`mutableStateOf<Offset?>`), initialized null, cleaned up on cancel/end.

### 4. Tool icons — `EditorScreen.kt:6086-6087`
- `FILL` → `Icons.Outlined.FormatColorFill`
- `GRADIENT` → `Icons.Outlined.Gradient`
Both from `material-icons-extended` (already a dependency).

### 5. Tests — `FillToleranceTest.kt` (12 tests)
Pure JVM, no Android framework:
- **Tolerance boundary**: exact-match full fill, zero-tolerance one-byte rejection, 12%-bridges-gap, 12%-stops-at-large-gap.
- **4-way connectivity**: cross pattern (seed + 4 edge neighbours fill, 4 diagonal corners untouched).
- **Edge cases**: out-of-bounds seed, zero-size bitmap, MAX_POINTS_PER_PAGE budget.
- **Gradient interpolation**: `BrushColorModeMath.gradientColorAt` at progress 0/0.5/1, alpha preservation.

## Constraints satisfied
- No schema change, no new native deps.
- One `Bitmap.copy` per fill (recycled after).
- No allocation inside the flood fill loop (stack + visited array pre-allocated).
- MAX_POINTS_PER_PAGE (200K) budget enforced.

## Verification
- `gradle assembleDebug` — GREEN
- `gradle testDebugUnitTest` — FillToleranceTest 12/12 GREEN
- Full suite: 3304 completed / 5 pre-existing failures (Phase148 UNC-path, B2Ui2Clipboard ×2, Paparazzi ×2) — 0 new failures.
