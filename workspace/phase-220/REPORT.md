# Phase 220 — Blender Strength + Dual-Brush Scatter (Pro Brush Controls)

**Date:** 2026-08-19  
**Status:** COMPLETE — `assembleDebug` BUILD SUCCESSFUL; `testDebugUnitTest` 3341 green / 5 pre-existing failures only

---

## What shipped

Two new per-preset sliders (0–100%) surfaced in the Canvas Settings bottom sheet:

| Slider | Scope | Engine mapping | Default |
|--------|-------|----------------|---------|
| **Blender Strength** | SMUDGE tool only (at shader level) | `blenderStrengthPercent / 100f` → AGSL `uMixStrength` | 85% (matches pre-220 `ToolPreset.SMUDGE.mixStrength=0.85`) |
| **Scatter Amount** | SPLATTER + SMUDGE bitmap stamps | Lerp over `spacingFactor`/`scatterFactor` | 0% (preserves pre-220 hardcoded values exactly) |

No new dependencies. No schema changes. No workflow edits.

---

## File:line anchors

### Engine params + validation

- **`BrushStudioParams`** extended with `blenderStrength: Float = 0.85f` + `scatterAmount: Float = 0f` — `WetCanvasEngine.kt:20,24`
- **`BrushPreset.validateParams()`** range-checks both fields in `[0,1]` — `BrushPreset.kt:184-185`
- **`BrushPresetFileCodec`** encode — writes both to JSON — `BrushPresetFileCodec.kt:91-92`
- **`BrushPresetFileCodec`** decode — optional for backward compat, defaults `0.85f` / `0f` — `BrushPresetFileCodec.kt:217-218`

### Settings persistence

- **`SettingsManager`** — `blenderStrengthPercent: Int` (default 85) + `scatterAmountPercent: Int` (default 0), coerceIn(0,100) — `SettingsManager.kt:259,263`

### Shader wiring

- **`AnnotationCanvas`** `drawWetLayerPass` — SMUDGE passes `blenderStrengthPercent.coerceIn(0,100)/100f` as `mixStrength`; all other tools keep `preset.mixStrength` — `AnnotationCanvas.kt:4647-4653`

### Scatter lerp

- **`AnnotationCanvas`** `drawSingleStroke` SPLATTER/SMUDGE branch — `scatter = scatterAmountPercent/100f`; `spacingFactor = lerp(0.45,0.12,scatter)`, `scatterFactor = lerp(0.15,0.55,scatter)` — `AnnotationCanvas.kt:5108-5119`
  - At scatter=0: `spacingFactor=0.45`, `scatterFactor=0.15` → exact pre-220 hardcoded values
  - At scatter=1: `spacingFactor=0.12`, `scatterFactor=0.55` → wide scattered droplets

### Composable param threading

- **`AnnotationCanvas`** signature — `blenderStrengthPercent: Int = 85`, `scatterAmountPercent: Int = 0` — `AnnotationCanvas.kt:210-211`
- Threaded through: `drawCompositedLayersStrokes` (`:4278-4279`), `drawWetLayerPass` (`:4587-4588`), `drawSingleStroke` (`:4834-4835`), `drawBitmapStampSequence` (`:3375-3376`)
- All nested calls updated to pass params — `:2448-2449`, `:2502-2503`, `:2649-2650`, `:2745`, `:3405`, `:3427`, `:4288`, `:4294`, `:4317`, `:4460-4461`, `:4688`, `:4691`, `:4760`, `:4763`

### EditorScreen + UI

- **`EditorScreen`** state declarations — `blenderStrengthPercent`, `scatterAmountPercent` from `SettingsManager` — `EditorScreen.kt:573-574`
- Passed to `AnnotationCanvas` call — `EditorScreen.kt:2447-2450`
- **`CanvasSettingsBottomSheet`** sliders — Blender Strength (`:5876-5917`) + Scatter Amount (`:5919-5955`); both coerce 0–100 and persist via `onBlenderStrengthChange`/`onScatterAmountChange` callbacks — `EditorScreen.kt:5055-5059` (signature), `:2905-2914` (wiring)

### String resources

- `canvas_blender_strength_label`, `canvas_blender_strength_desc`, `canvas_blender_strength_affected`, `canvas_scatter_amount_label`, `canvas_scatter_amount_desc`, `canvas_scatter_amount_detail` — `strings.xml`

---

## Tests

**`BlenderScatterPolicyTest`** — 14 tests (`BlenderScatterPolicyTest.kt`):
- `lerp_preservesLegacyDefaults_atZero` — scatter=0 → spacing=0.45, scatter=0.15
- `lerp_appliesFullSpread_atHundred` — scatter=100 → spacing=0.12, scatter=0.55
- `lerp_linearAtMidpoint` — scatter=50 → midpoint of lerp
- `blenderStrength_zeroMapsToZero` — blenderStrength=0 → mixStrength=0.0
- `blenderStrength_hundredMapsToOne` — blenderStrength=100 → mixStrength=1.0
- `blenderStrength_defaultPreservesLegacy` — blenderStrength=85 → mixStrength=0.85
- `codec_roundTripPreservesBlenderAndScatter` — encode→decode identity
- `codec_backwardCompatOldPresetDefaultsCorrect` — JSON without fields → 0.85f/0f
- `codec_zeroValuesDecodeCorrectly` — explicit 0.0 → 0.0f
- `validation_rejectsNegativeBlenderStrength` — negative → error
- `validation_rejectsOverOneBlenderStrength` — >1.0 → error
- `validation_rejectsNegativeScatterAmount` — negative → error
- `validation_rejectsOverOneScatterAmount` — >1.0 → error
- `presetValidation_rejectsOutOfRangeValues` — BrushPreset with out-of-range → error

---

## Test results

```
assembleDebug: BUILD SUCCESSFUL
testDebugUnitTest: 3341 passed / 5 failed (all pre-existing)
```

Pre-existing failures (NOT caused by Phase 220):
| Test | Root cause |
|------|------------|
| `B2Ui2ClipboardScrubTest` (2) | Clipboard guard not available in CI |
| `Phase148UiFailureTextScrubTest` | UNC-path issue on Linux CI |
| `PaparazziSmokeTest` (2) | Rendering environment unavailable in CI |

---

## Backward compatibility

- **Presets without new fields** decode with defaults (blenderStrength=0.85, scatterAmount=0.0) — identical to pre-220 behavior
- **scatterAmount=0** produces exact pre-220 `spacingFactor`/`scatterFactor` values for SPLATTER and SMUDGE
- **blenderStrength=85** produces `mixStrength=0.85` for SMUDGE, matching pre-220 `ToolPreset.SMUDGE(0.4,0,0.85,0.1,0.5,3)`
- All other tools unaffected — blenderStrength override is SMUDGE-only at shader level
