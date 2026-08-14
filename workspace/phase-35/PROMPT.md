# Phase 35: Canvas & Scribe Ergonomics [NOT STARTED]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app. The canvas (`ui/components/AnnotationCanvas.kt`) and editor
(`ui/screens/EditorScreen.kt`) already have a rich tool set: brushes (Phase 27),
`PenNibVisualPreview` (`ui/components/PenNibVisualPreview.kt`), a color picker
(`ColorPickerBottomSheet` in EditorScreen, Phase 23 fixes), rainbow colors, and a
Glass theme (Phase 24). **Read `docs/phase-status.md` first** — do not regress
prior brush/canvas/color work.

**THE GOAL:** make the canvas feel like a pro scribe tool — a floating minimal
tool dock, live brush/nib previews, a spatial minimap HUD, and a curated
swatch studio — all real, performant, and low-end safe (AGENTS.md hardware rule).

## 1. Floating Minimalist Tool Dock
- Replace dense tool rows with an **expandable, pill-shaped floating palette**
  that docks smoothly to any edge of the screen (snap-to-edge with spring
  animation) and **automatically tucks away when pen input is detected**
  (freehand drawing begins → dock auto-hides; tap → dock returns).
- Reachable ≤2 taps for every tool; keep all existing tools (brushes, erasers,
  shape, colors, stickers, undo/redo) accessible — no orphaned tools.
- Low-end: animation reduce-motion respected (existing `reduce-motion` setting).

## 2. Live Brush & Nib Previews
- Enhance `PenNibVisualPreview`: **real-time pressure response, tilt-angle
  indicators, and pigment wetness simulation** right inside the palette
  selector (live preview updates as the user drags pressure/tilt/size sliders).
- Pure-JVM testable: preview parameter math (pressure→width, tilt→opacity,
  wetness→blur/bleed coefficients).

## 3. Canvas Minimap & Spatial HUD
- Add a **semi-transparent minimap HUD** in a canvas corner showing: current
  viewport bounds, active layers, and **zoom level %**, with smooth
  **pinch-to-zoom spring animations**.
- Interactive: tap the minimap to jump the viewport; respects reduce-motion and
  low-RAM (HUD can be toggled off in settings).
- Do not interfere with stroke capture / pressure input on the main canvas.

## 4. Color Palette & Swatch Studio
- Introduce **curated designer palettes** (Nordic, Botanical, Cyberpunk, Warm
  Terracotta) alongside the existing vibrant palette (Phase 19) — each palette is
  a real curated swatch set (extend `PaletteCatalog`, preserve its
  family-classification invariant test).
- **Harmonic contrast checking** (suggest complementary/analogous colors from the
  current swatch) and an **eyedropper loupe magnification** (magnified zoom ring
  while picking from the canvas).
- Pure-JVM tests: palette swatches still classify into their families; contrast
  suggestions are mathematically valid.

## Definition of done
- Floating pill dock with edge-snap + auto-tuck-on-pen works in the canvas; every
  existing tool reachable ≤2 taps.
- `PenNibVisualPreview` shows live pressure/tilt/wetness feedback.
- Minimap HUD shows viewport/layers/zoom %, pinch-zoom spring animates, jump-on-tap
  works, toggleable.
- 4 designer palettes + harmonic contrast + eyedropper loupe implemented;
  `PaletteCatalogTest` still green.
- `gradle testDebugUnitTest` + `gradle assembleDebug` pass.
- REPORT.md: before/after, low-end fallbacks, reduce-motion compliance.

## Constraints
- No new permissions. No DB schema change. Do NOT edit `.github/workflows/`.
- Do NOT regress Phase 23/27 color work or the palette invariant test.
- Never log decrypted note content. Keep `ClipboardGuard` and FLAG_SECURE intact.
- No image assets (minimap/HUD/loupe drawn with Compose primitives).