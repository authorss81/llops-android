# Phase 227 — Paper Deckled Edge + Texture Strength + Export Layers (PSD/PNG)

## Goal
Paper feels **real** (deckled edge + tunable tooth) and export preserves **layers** — two polish items sharing the paper/canvas pipeline.

## Context — verified anchors
- **Paper:** `AnnotationCanvas.kt:3280-3337` `drawPaperCard` flat `RoundRect 8` + grain `REPEAT 192×192` `PaperGrainTileCache:59` LRU 4, `PaperGrainPolicy:24` `MAX_ALPHA 0.05/0.07`; border `2dp`; text from `AGENTS.md` "paper textures" already via `HarmonicContrastMath` but edge is still perfect rectangle.
- **Texture strength:** `BrushStudioParams.paperGrain 0..1` → shader `uPaperGrain` `AgslShaders:141` + `PaperGrainPolicy.enabled(!lowEnd)` — strength not user-tunable per paper.
- **Export layers:** `PsdExportService.kt` already writes PSD with layers? check; `LayerEntity:76` `zOrder/opacity/blendMode/visible/locked` + `MAX_LIVE_LAYER_COUNT`; export currently single flattened PNG? `SaFExporter.kt:161` `pendingRequest!!` guarded, `ExportDestinationPolicy`. No deckled clip.

## Tasks
1. **Deckled edge:** in `drawPaperCard`, when `paperEdge = DECKLED` (new `PaperEdgePolicy` enum `RECT / ROUNDED / DECKLED` persisted `SettingsManager.paperEdgeKey`), clip `Path` with wavy border `sin(x*0.08+seed)*2 + sin(x*0.15)*1` amplitude `2-3dp` (pure `Path.cubicTo`, no bitmap). Draw shadow from 213 under deckle via same `BrushShadowPolicy` offset (reuses 213 policy).
2. **Texture strength slider:** `SettingsManager.paperTextureStrength 0..100` (default 50) → `alpha = 0.02 + strength/100 *0.05` lerp for `grainBrush`, plus shader `uPaperGrain = strength/100`. Slider in `TemplateLibraryDialog` next to opacity. Pure `PaperTextureStrengthPolicy` + tests.
3. **Export layers:** `PsdExportService` ensure `PSD` path writes one `Layer` per `LayerEntity` sorted `zOrder` with `opacity` + `blendMode` (`NORMAL/MULTIPLY…` reuse `applyLayerBlend`) — verify `LayerBitmapLruCache` raster per layer stitched; flat `PNG` export keeps `TRANSPARENT background` toggle (reuse `isDarkPaper` branch `PaperCard` color vs transparent). No new native.

## Constraints
- No schema beyond settings keys; no workflow edits; `BitmapPool` recycled, `PaperGrainTileCache` not recycled under `RenderNode`. Keep `R8`/`baselineprofile` intact.
- DoD: `assembleDebug` + `testDebugUnitTest` green; Paparazzi `deckled edge + texture 80` + `PSD layer count == live count`; REPORT.md edge math + texture strength table.

