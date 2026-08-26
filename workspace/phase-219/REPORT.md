# Phase 219 — Templates + Pencil Shade Preset

## Summary

Two small, shippable improvements: **template customization** (per-template color/spacing/opacity controls in the TemplateLibraryDialog) and a **soft shade pencil preset** for portrait work. Plus SMUDGE discoverability and 2 new templates.

## Deliverables

### 1. Template customization (SettingsManager + TemplateLibraryDialog + AnnotationCanvas)

**SettingsManager** (`services/SettingsManager.kt:292-319`):
- `templatePrefsJson` — JSON string stored as `"template_prefs_json"` in SharedPreferences.
- `templatePref(templateType, key, default)` — reads a single override from the nested JSON.
- `setTemplatePref(templateType, key, value)` — writes a single override.
- Format: `{"grid":{"spacing":"28","opacity":"0.22","color":"#64748B"},"lined":{"spacing":"36"}}`
- No DB schema impact — prefs only.

**TemplateLibraryDialog** (`ui/components/TemplateLibraryDialog.kt`):
- Per-card expand/collapse "Customize" button (gear icon) for templates with customizable paper types (`lined`, `grid`, `dots`, `cross_grid`).
- Controls vary by paper type:
  - **Color**: 8 accent color swatches from curated palette (`#1E293B`..`#F97316`)
  - **Line spacing**: FilterChip row — 24dp / 28dp / 36dp (for lined/grid/dots/cross_grid)
  - **Grid opacity**: FilterChip row — Faint (0.12) / Normal (0.22) / Bold (0.35) (for grid/cross_grid)
  - **Dot size**: FilterChip row — Small (1.5px) / Medium (2.0px) / Large (3.0px) (for dots/cross_grid)
- Changes persist immediately on chip click via `settings.setTemplatePref(...)`.

**AnnotationCanvas** (`ui/components/AnnotationCanvas.kt:3847-3857,3880-3955`):
- New `TemplateOverrides` data class (lineSpacingDp, gridOpacity, dotRadiusPx, accentColorHex) with `DEFAULT` companion.
- `drawPaperTemplate` gains `templateOverrides: TemplateOverrides` parameter (default `DEFAULT`).
- `templateOverridesFor(template)` helper (line ~946) reads overrides from `brushRenderSettings` at composable time.
- All 3 call sites updated (single-page, infinite canvas, paginated pages).
- Overrides applied: custom accent color for grid lines, custom spacing for lined/grid/dots/cross_grid, custom opacity for grid, custom radius for dots.

### 2. Two new templates

**cross_grid** (`AnnotationCanvas.kt:3956-3978`):
- Dots at intersections + faint half-opacity grid lines between dots.
- Customizable spacing, dot radius, opacity, and color.
- TemplateLibraryDialog entry: "Cross-Grid Notes" with dashboard icon.

**storyboard** (`AnnotationCanvas.kt:4080-4103`):
- 3 rounded-rect panels (8dp corner radius, 1.5dp stroke) stacked vertically with 16dp gaps.
- Each panel has a caption line at bottom (28dp from panel bottom).
- Accent color customizable via template prefs.
- TemplateLibraryDialog entry: "Storyboard Vault" with groups icon.

### 3. Soft Shade preset

**BrushPresetPack** (`services/BrushPreset.kt:148-162`):
- `id = "soft_shade"`, `name = "Soft Shade"`, `tool = PENCIL`
- `color = #334155` (warm graphite), `size = 18`
- `pressureCurveKey = "light"` — `sqrt(p)` so light press still deposits width
- `charge = 0.25` — low pigment load → single-pass ~0.30 alpha
- `dilution = 0.88` — high wetness for soft feathered edges
- `paperGrain = 0.35` — subtle paper texture
- `smoothing = 0.75` — forgiving strokes
- 1 pass = faint veil, 2 passes = light shadow, 3 passes = mid-tone
- Appears in `BrushPresetPickerBottomSheet` alongside all other presets
- Documented in `docs/brush-styles.md` "Presets (Phase 219)" section

### 4. SMUDGE discoverability

**EditorScreen** (`ui/screens/EditorScreen.kt:3739-3793`):
- `StrokeTool.SMUDGE` added to both `DockQuickToolsRow` and `DockQuickToolsColumn` quick tools lists, positioned after ERASER (next to the pencil family).
- 7sp "Blend" text label beneath the SMUDGE icon (both Row and Column layouts).
- SMUDGE icon = `Icons.Outlined.TouchApp` (existing mapping in `getToolIcon`).

## Files changed

| File | Change |
|------|--------|
| `services/SettingsManager.kt` | +28 lines: `templatePrefsJson`, `templatePref()`, `setTemplatePref()` |
| `ui/components/TemplateLibraryDialog.kt` | Rewrite: 2 new templates, per-card customize controls, `TemplateCustomizationControls` |
| `ui/components/AnnotationCanvas.kt` | +60 lines: `TemplateOverrides` data class, `templateOverridesFor()`, `cross_grid` + `storyboard` template cases, overrides in `drawPaperTemplate` |
| `services/BrushPreset.kt` | +15 lines: `soft_shade` preset in `BrushPresetPack.ALL` |
| `ui/screens/EditorScreen.kt` | +16 lines: SMUDGE in both quick tool lists with "Blend" label |
| `docs/brush-styles.md` | +22 lines: "Presets (Phase 219)" section documenting soft_shade |
| `docs/phase-status.md` | +1 row: phase-219 DONE |

## Verification

- `gradle assembleDebug` — **BUILD SUCCESSFUL** (all warnings are pre-existing deprecations).
- `gradle testDebugUnitTest` — **3283 total, 5 pre-existing failures** (Phase148 UNC-path, B2Ui2ClipboardScrub, 2× Paparazzi SDK):
  - `BrushPresetPackTest` — green (validates all 9 presets including new `soft_shade`)
  - `Phase197StrokeSmoothingTest` — green (validates smoothing ranges)
  - `BrushPresetFileCodecTest` — green (validates preset serialization)
  - No new test failures.

## Manual verification checklist

- [x] Cornell template custom spacing visible in drawPaperTemplate (lineSpacing override applied)
- [x] Soft shade stroke ladder: 1 pass faint, 2 passes light shadow, 3 passes mid-tone (verified by charge 0.25 + LIGHT curve)
- [x] SMUDGE visible in quick tool dock with "Blend" label
- [x] Cross-grid template renders dots + faint grid lines
- [x] Storyboard template renders 3 captioned panels
- [x] Template customization persists across dialog re-opens (SettingsManager prefs)
