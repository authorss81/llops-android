# Phase 220 — Blender Strength + Dual-Brush Scatter (pro brush controls)

## Goal
Make SMUDGE/blender and texture scattering **tunable like a pro app** — two per-preset 0-100 sliders reusing existing engine fields, no new deps.

## Context — verified anchors
- **SMUDGE:** `AgslShaders.kt:294-405` `ToolPreset SMUDGE(0.4,0,0.85,0.1,0.5,3)` fixed `wetness/mixStrength` — no UI. `WetBrushEngine.kt:13-140` / `WetCanvasEngine.kt:28` `BrushStudioParams(dilution,charge,pull,impasto,paperGrain,splatterSpread)` already has `splatterSpread` but blender strength is buried.
- **Scatter:** `BrushTextureEngine.kt:169-227` `drawBitmapStampSequence(spacing 0.2-0.45, scatter 0.35, rotation)` fixed per-tool constants (`AIRBRUSH_SPRAY 0.35`, etc.); `115-162` `drawTexturedStrokePath` `BitmapShader` + `PorterDuffColorFilter`.
- **Preset:** `services/BrushPreset.kt:12-196` `BrushPresetPack.ALL` 8 curated, `BrushPresetFileCodec.kt:47` persistence already.

## Tasks
1. Extend `BrushStudioParams` / `BrushPreset` with `blenderStrength 0..1` (maps to `mixStrength`) and `scatterAmount 0..1` (maps to `scatter+spacing` lerp) — default preserves legacy (`SMUDGE 0.85`, others as today).
2. Wire `blenderStrength` into `WetMixingEffect.update(uMixStrength)` `AgslShaders.kt:454` path and into `BrushPresetFileCodec` round-trip; `scatterAmount` into `BrushTextureEngine` stamp `spacing = lerp(0.45,0.12,scatter)` `scatter = lerp(0.15,0.55,scatter)`.
3. UI: two sliders in `CanvasSettingsBottomSheet` / `BrushSettingsDialog` next to `smoothing` slider — `blender 0-100` visible when `SMUDGE` active else `scatter 0-100`. Persist via `SettingsManager` per-preset or global override (reuse `stabilizerStrengthPercent` pattern).
4. No schema, no workflow edits.

## DoD
`assembleDebug` + `testDebugUnitTest` green; new `BlenderScatterPolicyTest` asserts lerp caps + round-trip codec; Paparazzi `SMUDGE 20 vs 80` blend swatch; REPORT.md with file:line.

