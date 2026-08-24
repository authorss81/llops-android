# Phase 200: Linear Color Mixing + Paper Texture + Eraser AA Parity [PERF 3.2+3.3+3.5]

**Goal:** Fix muddy wet-mix + premium paper feel + eraser edge quality.

**Context:** `services/WetMixingMath.kt` mixes in sRGB (muddy), `services/InkCardPaperPolicy.kt` flat tint, `services/EraserGeometryPolicy.kt` `PorterDuff.CLEAR` aliased.

**Steps:**
1. `WetMixingMath.kt` → `ColorSpaces.LinearSrgb` for wet-mix math via `ColorSpace` param.
2. Paper texture: tileable noise `BitmapShader` drawn once under ink, cached static `LayerBitmapLruCache`, near-zero cost.
3. Eraser AA parity: match brush AA quality, soft edge same as ink `CanvasStrokeRenderer`.

**DoD:** Visual before/after screenshots, `gradle assembleDebug` green, no blending regression.
