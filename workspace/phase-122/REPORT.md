# Phase 122 — Rainbow colour support for brushes

**Status:** DONE — 2026-08-17
**Feature request:** SPECTRUM (rainbow) brush mode: selectable, persisted, spectrum-coloured per-point rendering; normal colours unchanged.
**Verify model:** agent-simulated (`testDebugUnitTest` 1707 total green, 0 failures; `assembleDebug` green).

## Summary

The rainbow brush MODE already existed end-to-end since phase-27 (`StrokeColorMode.RAINBOW`, render-time per-point hue derivation, mode chips in the colour picker, per-stroke mode persistence through the stroke payload). This phase closed the three real gaps against the PROMPT and hardened the math:

1. **Persistence across sessions** — the mode was not persisted in `SettingsManager`; it reset to SOLID on every editor open. Added `SettingsManager.brushColorModeKey` routed through a new pure-JVM `services/ColorModePersistencePolicy.kt` decision table, and `EditorScreen.currentColorMode` now restores the persisted mode on open.
2. **Width/quick picker exposure** — the rainbow option was only in the full colour bar, not the quick picker. Added one shared `ColorModeChipsRow` composable and rendered it in BOTH the colour picker and the width quick picker, both wiring the picker handler to persist.
3. **Allocation-free hue math** — `rainbowColorAt` called `argbToHsv` (allocated a `FloatArray`) per point. Replaced with `hueAdvance(seedHueDeg, progress)` (a scalar multiply + add + wrap) plus a private scalar `valueOf(argb)` (bit-arithmetic RGB max). The old per-point allocation is gone.

The hue-advance policy is **per-stroke-length**: normalized progress `[0,1] × 360°`, wrapped via `normalizeHue`, so progress 0 and 1 coincide on a seamless full-spectrum loop. It is fully deterministic for a given seed + point list. Low-end safe: constant O(1) scalar math per point, zero allocation.

## What was implemented (file:line evidence)

### Maths — `app/src/main/kotlin/com/authorss81/noteflow/services/BrushColorModeMath.kt`
- `BrushColorModeMath.kt:145-149` — `hueAdvance(seedHueDeg, progress)` = `normalizeHue(seedHueDeg + progress.coerceIn(0f,1f) * 360f)`. Deterministic; wraps at 360° (progress 0 and 1 map to the same hue → seamless loop).
- `BrushColorModeMath.kt:151-155` — `private fun valueOf(argb)` reads the max RGB channel as a scalar `Float` — replaces the per-point `FloatArray` HSV.
- `BrushColorModeMath.kt:166-176` — `rainbowColorAt(baseArgb, progress, seed)` now uses `seedHueDeg(seed)` + `hueAdvance` + `valueOf` only; **allocation-free per point** (KDoc updated at `:160-165`). Output is byte-identical for the same inputs (test-pinned).
- `BrushColorModeMath.kt:212-222` — `colorForProgress(SOLID, …)` returns `baseArgb` unchanged; RAINBOW routes to `rainbowColorAt`.

### Persistence — NEW `app/src/main/kotlin/com/authorss81/noteflow/services/ColorModePersistencePolicy.kt`
- `ColorModePersistencePolicy.kt:17-30` — pure-JVM decision table: `PREF_KEY_COLOR_MODE = "brush_color_mode_key"`, `DEFAULT_MODE = SOLID`, `prefValue(mode)` = `mode.persistenceKey`, `modeFromPref(value)` = `StrokeColorMode.fromKey(value)` (fail-closed → SOLID on null/unknown, `StrokeModels.kt:137`).

### Settings — `app/src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt`
- `SettingsManager.kt:203-211` — `brushColorModeKey` reads/writes the pref through `ColorModePersistencePolicy` (default = SOLID).

### Shared UI — NEW `app/src/main/kotlin/com/authorss81/noteflow/ui/components/ColorModeChipsRow.kt`
- `ColorModeChipsRow.kt:40-46` — single composable `ColorModeChipsRow(currentColorMode, currentColor, currentGradientToColor, onColorModeChange, onGradientToColorSelect)`: Solid/Rainbow/Gradient/Shimmer chips + gradient-end swatch row.
- `ColorModeChipsRow.kt:82-85` — chip previews rendered with the exact per-mode `Brush` (RAINBOW → `Brush.sweepGradient`, GRADIENT → `Brush.linearGradient`).

### Editor — `app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt`
- `EditorScreen.kt:135-140` — `currentColorMode` restored from `ColorModePersistencePolicy.modeFromPref(viewModel.settings.brushColorModeKey)` on editor open (was hard-coded SOLID).
- `EditorScreen.kt:3133-3137` — colour picker chip block replaced with the shared `ColorModeChipsRow`.
- `EditorScreen.kt:3451-3455` — width/quick picker (`WidthPickerBottomSheet`) now renders the SAME `ColorModeChipsRow` → rainbow reachable without opening the full colour bar.
- `EditorScreen.kt:1829` + `:1878` — both `onColorModeChange` handlers persist `viewModel.settings.brushColorModeKey = mode.persistenceKey` (colour picker + width picker).
- `EditorScreen.kt:1845` + `:1894` — `onGradientToColorSelect` persists GRADIENT.
- `EditorScreen.kt:1821` — `onColorSelect` persists SOLID (picking a concrete swatch exits the mode).
- `EditorScreen.kt:1608-1611` — eyedropper `onColorSampled` persists SOLID (sampled pixel → concrete colour) with a non-alarming snackbar.
- `EditorScreen.kt:1617-1620` — per-stroke seed refresh on `onDrawingStart` (existing phase-27 behaviour, unchanged).

### Canvas — `app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt` (no change required)
- `AnnotationCanvas.kt:861-880` — stroke commit records `colorMode`/`colorSeed`/`gradientToColorInt` per stroke.
- `AnnotationCanvas.kt:1269-1273` — wet-brush effect reads `currentColorMode.isMultiColor` + seed.
- `AnnotationCanvas.kt:1316-1318` / `:1372-1374` / `:1491-1493` — every stroke creation stamps mode + seed + gradient end.
- `AnnotationCanvas.kt:2896-2906` — render derives per-point colour via `BrushColorModeMath.colorForProgress(mode, …)` with `!isMultiColor` short-circuit for SOLID — "never stored per point". No render change needed; only the pure-JVM math was refactored (output-identical).

## Verification

- New `app/src/test/java/com/authorss81/noteflow/Phase122RainbowColorTest.kt` (15 pure-JVM tests):
  - Determinism (`hueAdvance` same seed+progress → same hue);
  - 360° wrap (progress 0 ≡ progress 1; mid = 180° offset; outputs always in `[0,360)`);
  - progress clamping into `[0,1]`;
  - `rainbowColorAt` agrees with the standalone `hueAdvance` policy (no render/math drift) and keeps the documented value-lift (dark base → bright 0.5 value floor; bright base keeps its value);
  - persistence round-trip for every mode through `ColorModePersistencePolicy`;
  - missing/unknown/whitespace-prefixed prefs fail closed to SOLID;
  - RAINBOW stored value is distinct from SOLID and restores to RAINBOW;
  - pre-stored stroke serialization unchanged (`colorForProgress(SOLID)` = identity);
  - non-rainbow strokes byte-identical under the new math;
  - degenerate rainbow stroke stays in gamut (full saturation, alpha 0xFF);
  - source pins: editor restores+persists via `SettingsManager.brushColorModeKey`; settings routes through the policy keys; the shared chips row appears in BOTH bottom sheets; width sheet forwards live mode/gradient state; canvas records mode+seed per stroke and never stores per point; gradient contact preserved (`commitColorMode`).
- `gradle testDebugUnitTest` — **1707 tests, 0 failures, 0 errors, 0 skipped** (only pre-existing upstream warnings in `plugins/llm`).
- `gradle assembleDebug` — **BUILD SUCCESSFUL** (90 tasks, 57 run).

## Design decisions (recorded per AGENTS.md determinism rule)

- **Hue advance = per-stroke-length**, not per-time: `progress` ∈ `[0,1]` along the stroke × 360° (full seamless spectrum per stroke, both ends meet). Deterministic for the same point list; a fixed FPS/time-based sweep would make the same drawn shape render differently on replay/export. Policy documented in the `hueAdvance` KDoc.
- **Mode persists via SharedPreferences** (`SettingsManager`), never the DB schema (prompt constraint). Per-stroke mode/seed already round-trips inside the stroke payload (phase-27), so reopened notes keep their rainbow strokes without any schema change.
- Persistent state only stores the MODE; the per-stroke seed stays random-per-stroke at `onDrawingStart` — reopening never rewrites the seed of already-drawn strokes.
- Textured tools (pencil/airbrush/watercolor) keep the pre-existing phase-27 documented limitation (derived colour sampled at mid-progress on the single-color `BitmapShader`); untouched, documented in phase-27 docs.

## Honesty: pre-existing behaviour vs this phase

The PROMPT's headline feature (rainbow modes, chips, per-point render, per-stroke payload) shipped in phase-27 and is unchanged in behaviour. This phase adds exactly the missing **persistence**, the **quick-picker exposure**, and the **allocation-free** optimisation, plus the required pure-JVM determinism/persistence/unchanged-non-rainbow tests. No hidden work was fabricated: the width-sheet lambda `:1878` and the `:1894` gradient handler existed as screens-only handlers before and now persist — grep-verified against the reviewed diff.

## Constraints honoured

- NO DB schema change, no migration, no new dependencies, `.github/workflows/` untouched, no `INTERNET` change, `allowBackup=false`/`ClipboardGuard`/FLAG_SECURE intact, no keys/log content logged.