# Phase 213 — Brush Shading & Drop Shadows (real-world paper elevation)

## Goal
Add per-stroke **soft drop shadows and shading** so strokes lift off the paper like a professional drawing app (Procreate/Fresco feel). Must be zero-regression on low-end devices and API 26+, complement (not replace) existing impasto/wet-edge/paper-grain pipeline.

## Context — verified anchors
- **Current pipeline renders strokes flat** — no per-stroke shadow. Only impasto ridge lighting exists: `ui/components/AgslShaders.kt:231-257` (`uImpasto` highlight `0.28` / shadow `0.16`, lightDir `normalize(1,1)`). Wet-edge (`AgslShaders.kt:261-273`), paper grain (`PaperGrainTileCache.kt:59-65` + shader `141-143`), Vibrancy (`ColorVibrancy.kt`) and `BrushTextureEngine.kt:39-48` textures are all **under** ink. Repo grep `shadow|shade|emboss|BlurMask` → zero stroke shadow.
- **Strokes render in** `ui/components/AnnotationCanvas.kt:4294-5050+` `DrawScope.drawSingleStroke` unrolled per-tool dispatch (PEN/HIGHLIGHTER/PENCIL/AIRBRUSH/OIL_PAINT/WATERCOLOR/CHARCOAL/OIL_PASTEL/INK_WASH/GOUACHE/DRY_BRUSH/PALETTE_KNIFE etc.). Early Ink path `4216-4230` via `CanvasStrokeRenderer`, else `BrushTextureEngine.drawTexturedStrokePath` / `drawBitmapStampSequence` / ribbon quads.
- **Layer compositing** `drawCompositedLayersStrokes` `3652-3949` rasterizes per-layer via `BitmapPool.acquire` + `LayerBitmapLruCache` (`3863` key `"${page}_${layer}_v${vibrancy}"`), blend via `Paint.applyLayerBlend` `3902-3904`. Wet pass `3955-4151` uses `RenderNode("inkflow-wet-mix").setRenderEffect` behind `ShaderCapabilityHelper.isAgslSupported` (API 33+).
- **Stroke model** `data/model/StrokeModels.kt:141-163` + `data/model/Entities.kt:51-74` — per-stroke props today: tool/color/width/points/pressure/tilt/layer/colorMode, **no** shadow offset/radius/color/alpha. Hard rule: no schema churn without approval — first implementation must be render-only/preset-driven.
- **Paper card** `AnnotationCanvas.kt:3280-3337` flat `drawRoundRect` + grain `REPEAT` brush (`3299-3304`) — no elevation.

## Tasks
1. **Create pure-JVM `services/BrushShadowPolicy.kt`** — decision table: `enabled(lowEnd:Boolean):Boolean`, `shadowAlpha(isDarkPaper:Boolean):Float` (~0.20 light / 0.12 dark), `shadowOffset(width:Float):Offset` (`width*0.35, width*0.40` clamped 1..6 dp), `blurRadius(width:Float):Float` (`width*0.6` 2..12 px), `shouldApply(tool:StrokeTool):Boolean` (skip ERASER/LASER/TEXT, reduce for HIGHLIGHTER). Backward-compatible: default OFF maps to zero contribution so old strokes unchanged. Unit-tested (`BrushShadowPolicyTest`).
2. **Wire vector shadow (API 26+ universal):** in `AnnotationCanvas.kt:drawSingleStroke` fallback path, render an offset blurred `Path` **before** main paint: duplicate stroked Path offset by `shadowOffset`, paint `argb(alpha,0,0,0)` (or theme-aware white shadow on dark paper) with `BlurMaskFilter(Blur.NORMAL)` radius. For stamp/ink paths, offset points `points.map{ PointF(x+dx, y+dy) }` similarly. Guard with `shadowEnabled = !lowEnd && shadowPolicy.enabled()` and setting `"Paper elevation"` default ON. Draw cost = ~2× path — covered by existing `LayerBitmapLruCache` hash (shadow included in key).
3. **GPU path (API 31+ polish, optional if feasible):** when hardware canvas + `ShaderCapabilityHelper.blurSupportedFor(sdk)` use `RenderEffect.createBlurEffect` via a cached `RenderNode` carrier (reuse pattern of wet pass) instead of `BlurMaskFilter` allocations. Keep vector fallback for low-end/API <31. One tier table.
4. **Do NOT allocate `Paint/BlurMaskFilter` inside per-segment loop `4296-4312`** — reuse pooled paint; avoid GC churn per sample. Do not recycle `PaperGrainTileCache` bitmaps under a recorded RenderNode.
5. **Theme awareness:** dark paper lifts color (`AnnotationCanvas.kt:4241-4244` `lum<0.2 → 0xFFF8FAFC`) — shadow on dark must be subtle white 0.12 alpha, not black. Light paper = black 0.20. Verified in Paparazzi screenshots.

## Constraints
- No Room schema change, no `.github/workflows/` edits, no new native/heavy deps (RenderEffect/BlurMaskFilter only). AGENTS.md base-APK size hard rule: no new native libs.
- Keep existing AGSL shader bit-exact for zero-pigment passthrough (`AgslShaders.kt:221-228`); shader uniform extension (uShadow*) is **deferred** to a follow-up — this phase is vector+RenderEffect only.
- DoD: `gradle assembleDebug` green, `gradle testDebugUnitTest` green, `visual-qa/screenshots` Paparazzi before/after (light+dark paper) attached in REPORT.md showing 0..1 shadow progression.
