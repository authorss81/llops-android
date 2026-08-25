# Phase 219 — Templates + Pencil Shade Preset (soft graphite for portrait shading)

## Goal
Templates become **customizable** and pencil gains a true **soft shade** for portrait work (shade below eye etc.). Two small, shippable improvements in one phase.

## Context — verified anchors
- **Templates:** `AnnotationCanvas.kt:3280-3531` `drawPaperCard` flat fill + grain `REPEAT` + `drawPaperTemplate` `when(template)` — `blank/lined(36dp)/grid(28dp)/dots(28dp radius2)/cornell/meeting/todo/kanban/music` (`3371-3529`) + `paperTexture` tiled `3354-3367` under grid + `drawReferenceImage:3543` dim underlay. Picker `TemplateLibraryDialog.kt:37` fixed presets, no color/spacing/opacity controls. Paper card border `2dp`, label chip `x28,y38`. Settings `SettingsManager` paper prefs `198-213` limited.
- **Shade question:** user asks "shade below eye when sketching" — i.e. soft graphite skin-tone build-up under eye (portrait). Current `PENCIL` `BrushTextureEngine: PENCIL_GRAPHITE 64×64` `4394` + `CHARCOAL_GRAIN` `4481` + `SMUDGE` `isWetRenderedTool:113` exist, pressure → `bristleSpread 0.75-1` + `pigment 0.55-1` (`BrushStrokeMath:87-96`) + `wetness/pigmentLoad` per preset. **What’s missing:** no low-opacity “soft shade” preset for repeated light passes; smudge as shade-blender not surfaced as primary tool.
- **Phase 213** adds per-stroke drop shadow (paper elevation) — complementary to shading, not the shade itself. Portrait shade is **graphite density**, not cast shadow. Needs 0.25-0.45 alpha build-up, large soft falloff.

## Tasks
1. **Template polish:**
   - Add per-template controls in `TemplateLibraryDialog`: color picker (reuse `PaletteCatalog.curated`), line spacing `24/28/36dp` (lined/grid/dots), grid opacity `0.12/0.22/0.35` slider, dots radius `1.5/2/3`. Persist per-template in `SettingsManager.templatePrefsKey` (JSON, like `BrushPreset`); apply in `drawPaperTemplate` `gridColor` alpha + `lineSpacing` param. No DB migration — settings only.
   - Add 2 new templates: `cross-grid` (dots + faint grid) and `storyboard` (3 boxes with captions) reuse existing `drawLine/drawRoundRect` primitives. Preview thumbnails regenerated.
   - Keep grain `PaperGrainPolicy` & `192×192` `REPEAT` `PaperGrainTileCache:59` intact.

2. **Soft shade preset:**
   - Add `BrushPreset` `soft_shade` (PENCIL, `color 0xFF334155` warm graphite, `size 18`, `smoothing 0.75`, `paperGrain 0.35`, `opacity base 0.30` via `CHARCOAL_GRAIN 0.55→0.25α` two-pass? reuse `drawCharcoalStroke` `0.42w` offset passes `BrushTextureEngine:234`) — curated so single light stroke is faint, 3 passes = mid-tone, `SMUDGE` adjacent for blending. Alternatively derive from `PENCIL` with `pressureCurve LIGHT sqrt(p)` (`PressureCurveHelper:36`) so light press 0.3 still deposits `sqrt→0.54`.
   - Document in `docs/brush-styles.md` as style 11? keep `StyleIds` `0-10` stable — shade is a preset, not a new AGSL style. No new shader uniform.
   - Smudge discoverability: ensure `SMUDGE` appears in tool dock next to pencil (order in `FloatingToolDock`), with one-line hint “Blend shade”.

## Constraints
- No schema, no workflow edits, no heavy native deps. Reuse existing textures/shaders; new preset is JSON in `BrushPresetPack.ALL`.
- DoD: `assembleDebug` + `testDebugUnitTest` green; Paparazzi `template gallery light+dark` + `soft_shade 1-pass vs 3-pass` swatch added; REPORT.md shows cornell custom spacing + soft shade stroke ladder. Manual check: shade below eye achievable in 2-3 light PENCIL passes blended with SMUDGE.

