# Erasers, Vibrancy & the Organized Palette — Phase 19

Phase 19 ships three real, reachable, unit-tested canvas features: two eraser
modes (whole-stroke + partial trim), a render-time vibrancy/saturation boost,
and a curated, organized color palette. No new dependencies, no new
permissions, no `INTERNET`, no DB schema change (all settings live in
SharedPreferences via `SettingsManager`).

## 1. Dual erasers (`EraserMode`)

- **Whole Stroke** (default, unchanged behaviour): anything the eraser touches
  is removed entirely — this is the classic eraser.
- **Partial**: only the covered portion of each touched stroke is removed; the
  rest survives as one or more new segments. The erase path accumulates for the
  whole drag, so crossing a stroke twice in one drag trims both crossings.

### Behaviour details

- The hit threshold is identical to the classic eraser:
  `stroke.width + 18f` (`StrokeSegmenter.DEFAULT_EXTRA_RADIUS`).
- Segmentation is **honest**: only freehand polylines with ≥ 2 points are
  trimmed (`isFreehandTool && points.size > 1 && tool != LASER`). Text and
  shape strokes (which have no meaningful polyline geometry) are removed
  whole — matching the classic eraser, never faked as a "partial".
- Segments are real `Stroke` objects with fresh ids; tool, color, width,
  filled, layer, `pdfPage` and timestamp are copied unchanged, and `start`/`end`
  are the segment's own first/last points, so segments round-trip through
  persistence and export.
- Erasing a stroke whose every point is covered removes it (empty result).

### Where

- `services/StrokeSegmenter.kt` — pure-JVM `segment()` + `EraserMode`.
- `ui/components/AnnotationCanvas.kt` — `applyEraser()` routes both modes;
  partial-mode strokes are persisted via the existing `onStrokesChanged` path.
- Mode is persisted in `SettingsManager.eraserModeKey` and switchable from the
  tool-picker's eraser sub-menu (the sheet stays open when ERASER is selected
  so the choice is immediately visible).

## 2. Render-time vibrancy (`ColorVibrancy`)

A saturation "lift" applied **only at render time**. Stored `colorInt` values
are never mutated, so saved notes and exports stay true.

- OFF by default; `SettingsManager.vibrancyEnabled` + `vibrancyBoostLevel`
  (0..1, default 0.4) persist the choice.
- The math is pure JVM (`services/ColorVibrancy.kt`): RGB → HSV, gradient of
  saturation toward 1, HSV → RGB. Hue and value are preserved, so the result is
  always in gamut and a fully saturated color is unchanged. `amount == 0` is the
  identity transform.
- Applies everywhere a stroke is drawn: the vector fallback (API < 33),
  the AGSL wet shader (`uVibrancy` uniform — saturation lift on deposited
  pigment before mixing; `uVibrancy == 0` is bit-identical to classic),
  highlighter alpha blend, TEXT paint, and advanced-ink (`convertToInkStroke`).
- Layer bitmap caches are keyed with the boost value, so toggling the slider
  instantly re-renders without stale pixels.

### Where

- `services/ColorVibrancy.kt` — pure-JVM saturation math.
- `ui/components/AgslShaders.kt` — `uVibrancy` uniform + `update(vibrancy = ...)`.
- `ui/components/AnnotationCanvas.kt` — boost threaded through every draw path.
- `EditorScreen.kt` → canvas settings sheet ("Vibrancy" toggle + boost slider).

## 3. Organized color palette (`PaletteCatalog`)

The old 13-color default is replaced by a curated, organized 81-swatch palette
grouped into nine `ColorFamily` sections — Reds, Oranges, Yellows, Greens,
Blues, Purples, Pinks, Browns, Neutrals / Ink.

- `services/PaletteCatalog.kt` — pure-JVM catalog + `PaletteMath` (hue-based
  `familyFor`, `dedup`, `gamutSafe`, `groupByFamily`, `hexString`).
- `ColorPickerBottomSheet` now shows: a live preview + hex label header, an HSV
  panel (advanced-brush mode only), a **Recent Colors** row (eyedropper/user
  additions, newest first), the curated family sections, and saved swatches.
- Families are static and curated; the Browns section is *labeled* honestly —
  those warm medium-value colors read as brown even though a strict hue/value
  classifier would bucket the lighter ones under Oranges.
- The default palette is `remember`-only, so existing users immediately see the
  new palette; eyedropper samples still append into the recent-colors row.

## Tests (JVM, no device)

- `StrokeSegmenterTest` — run-splitting, multiple crossings, full-coverage
  removal, start/end trims, radius boundaries, attribute fidelity, whole-stroke
  fallback for empty-point strokes, mode round-trip.
- `ColorVibrancyTest` — identity at 0, hue preservation, in-gamut, monotonic
  saturation, grey invariance, alpha preservation.
- `PaletteCatalogTest` — hue-bucket classification, neutrals/browns rules,
  dedup, grouping order, catalog invariants (fully deduped, all families,
  full alpha, curated-brown honesty).

## Verification

```
gradle testDebugUnitTest
gradle assembleDebug
```
