# Phase 19: Dual erasers + vibrant colors & a beautiful palette

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app. Today the **Eraser** (`StrokeTool.ERASER`) has ONE behavior: it removes a
whole stroke when the pointer touches any of its points
(`AnnotationCanvas.kt` → `erasesStroke` → `strokeContainsPoint`, threshold
`stroke.width + 18f`). This phase adds a SECOND eraser that removes ONLY the
covered part of a stroke, plus makes colors more vibrant and the color palette
beautiful. All three must be real, reachable, and unit-tested.

## 1. Dual erasers (whole-stroke AND partial)

Two distinct, user-selectable eraser modes:
- **Stroke eraser** (existing behavior): drag → any touched stroke is removed
  entirely. Keep this working, but give it a clear name/setting.
- **Partial eraser** (NEW): drag → only the covered portion of each touched
  stroke is removed; the rest of the stroke remains as one or more segments.

Implementation notes (partial eraser):
- Strokes are polylines (`Stroke.points: List<PointF>`). Implement a pure-Kotlin
  **stroke segmenter**: given a stroke and an eraser path (a set of sampled
  erase points with a radius = eraser width), split the stroke's point list into
  contiguous runs whose points are NOT within the erase radius; drop runs
  shorter than a small epsilon; produce new `Stroke` objects (new ids, same
  `tool/color/width/filled/layer/pdfPage/timestamp`) for the surviving runs.
- Pure JVM + fully unit-tested: single-point split, eraser crossing a stroke in
  2+ places → multiple segments, eraser covering an entire stroke → stroke
  removed, erase on start/end → trimmed stroke, empty-result handling, radius
  edge cases.
- Wire into the canvas gesture path where `erasesStroke` runs today: partial
  mode replaces the hit stroke list with the segmented result instead of
  removing strokes wholesale. Reuse existing persistence (`onStrokesChanged`);
  segments persist across restart like normal strokes.
- Mode selection: a small eraser sub-menu/toggle (persisted in
  `SettingsManager`, no DB schema change) reachable from the tool picker when
  ERASER is active — NOT dead UI.
- Honesty: if partial-erase on wet/AGSL strokes needs the shader path to
  re-render segments, do that correctly; never claim partial-erase works on a
  stroke type it doesn't.

## 2. More vibrant colors

Improve color rendering quality so colors look richer and more alive:
- Add an optional **vibrancy/saturation boost** that applies at render time.
  Choose the honest implementation (e.g. a small saturation lift in the vector
  render path and/or an AGSL uniform in the wet shader). It must be OFF by
  default and toggleable in settings (`SettingsManager`) so existing notes look
  unchanged unless the user opts in.
- Ensure wet-mixing preserves/channels this so OIL_PAINT/WATERCOLOR keep their
  pigment character while gaining vibrancy.
- Keep saved color values exactly as picked — vibrancy is a render-time effect,
  NOT a mutation of stored `colorInt`.
- Pure JVM test for the vibrancy math (e.g. HSL saturation function: input color
  → boosted color stays in gamut, hue preserved, boost clamped).

## 3. A beautiful, organized palette

Redesign `ColorPickerBottomSheet` (EditorScreen.kt) into a genuinely nicer
picker:
- **Organized color families**: group swatches into named sections (Reds,
  Oranges, Yellows, Greens, Blues, Purples, Pinks, Browns, Neutrals/Ink) with a
  richer curated set of vibrant swatches than the current 13-color
  `customPalette` default.
- **Recent colors row**: show recently used colors (from `customPalette` /
  eyedropper additions) prominently.
- **Saved swatches** preserved (existing `PaletteItemEntity` SWATCH flow stays).
- Keep the HSV sliders (advanced mode) but make the layout cleaner and larger
  swatches, with a live preview.
- Everything reachable and functional; existing callbacks preserved
  (`onColorSelect`, `onSaveSwatch`, `onDeleteSwatch`, `onDismiss`).
- No new dependencies — pure Compose. Unit-test any palette-organization pure
  logic (grouping a color list into families by hue; dedup; gamut-safe list).

## Definition of done
- `gradle assembleDebug` succeeds.
- `gradle testDebugUnitTest` passes with new tests for:
  - partial-erase segmenter (the matrix above),
  - vibrancy/HSL boost math (hue preserved, clamped, in-gamut),
  - palette family grouping + dedup logic.
- Both eraser modes work on canvas, persist their setting, and survive restart.
- Partial erase persists segments as real strokes (round-trip test: erase → save
  → load → segments present).
- Vibrancy toggle changes rendering and does NOT mutate stored colors.
- New palette is visually organized, reachable, and keeps saved swatches.
- `docs/` note documenting eraser modes + vibrancy behavior.

## Constraints
- NO new third-party dependencies. NO new permissions. NO `INTERNET`.
- Do NOT change the DB schema (eraser mode + vibrancy go in `SettingsManager`).
- Do NOT edit `.github/workflows/`.
- Respect API 26+ / low-end: partial-erase math is cheap; vibrancy must work on
  vector fallback (API < 33) too, not only AGSL.
- Never bypass `ClipboardGuard` if any copy action is involved (unlikely here).
- Be honest: if partial-erase can't handle some stroke type, gate it clearly and
  document it — never ship a fake "partial" that actually removes the whole
  stroke.