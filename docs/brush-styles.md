# Brush Styles — Phase 18

This document describes the six new Phase 18 brush tools and the existing-brush
enhancements, how each style is expected to look, and how it is rendered.

Colors in this doc are best read on a white canvas. All new tools ship a Vector
fallback for API < 33 devices; the AGSL shader (API 33+) adds the wet/paper
physics on top. A style is only shipped if it renders *distinctly* on both
paths — no renamed PEN sticks.

## Style selector ids (uBrushStyle)

Keep in sync: `BrushStrokeMath` constants ↔ `AgslShaders.StyleIds` ↔ the id
table in `WET_MIXING_SHADER`'s doc comment.

| id | style                | tools |
|----|----------------------|-------|
| 0  | DEFAULT              | every classic pen/marker tool |
| 1  | WATERCOLOR           | WATERCOLOR |
| 2  | OIL_PAINT            | OIL_PAINT |
| 3  | SMUDGE               | SMUDGE |
| 4  | SPLATTER             | SPLATTER |
| 5  | CHARCOAL             | CHARCOAL |
| 6  | OIL_PASTEL           | OIL_PASTEL |
| 7  | INK_WASH             | INK_WASH |
| 8  | GOUACHE              | GOUACHE |
| 9  | DRY_BRUSH            | DRY_BRUSH |
| 10 | PALETTE_KNIFE        | PALETTE_KNIFE |

## New tools

### Charcoal (grainy) — style 5
Look: dry, powdery, rough. A dark smoky stroke with a speckled/grained core and
soft feathered edges; the harder you press, the darker and tighter the core,
while light strokes barely dust the paper. The stroke seeds a subtle texture
rotated per stroke so no two strokes look identical.
Render:
- AGSL: charcoal kernel strength modulates with stroke seed; grainy coverage
  scales with pressure/pigmentload (0.55).
- Vector: `drawCharcoalStroke` — offset streak passes + per-stroke texture
  rotation from `strokeSeedFromId`, speckles via the shared stamp engine.
- Ink: nearest stock brush `pencilUnstable` for `isAdvanced` strokes.

### Oil Pastel (waxy) — style 6
Look: bold, opaque, waxy. A thick solid band of colour with soft outer fringes;
overlapping strokes do not blend cleanly (wax resists water), leaving visible
viscous edges at boundaries.
Render:
- AGSL: wax kernel — high opacity, low mixStrength (0.5), no water pooling.
- Vector: `drawOilPastelStroke` — flat squared broad pass with a feathered
  fringe stamp band.
- Ink: nearest stock brush `marker`.

### Ink Wash / Sumi-e — style 7
Look: wet black-on-paper with a crisp dark core and a pronounced wet edge —
ink concentrates at the boundary of the wash, leaving a lighter interior.
Very wet (wetness 0.85), so adjacent strokes bleed into each other.
Render:
- AGSL: ink-wash fringe path pushes pigment toward the wet edge ring when
  wetness > 0.5; interior stays lighter. Shared with the secondary watercolor
  bloom ring.
- Vector: `drawInkWashStroke` — dark core pass + wide translucent fringe pass
  that grows with stroke width.
- Ink: nearest stock brush `marker`.
- Treated as a wet tool: wet-sheet UI, live smear preview, `wetnessPeakForTool`
  0.85.

### Gouache (matte) — style 8
Look: flat, opaque, chalky matte paint. Crisp strongly-outlined strokes that
cover everything underneath, like poster paint; no transparency, no sheen.
Render:
- AGSL: near-opaque flat coverage (pigment 0.98) with a distinct alpha branch
  and slight pigmentFactor variation so strokes stay flat but not plastic.
- Vector: `drawGouacheStroke` — `GOUACHE_MATTE` texture stamped along the path
  at high opacity.
- Ink: nearest stock brush `marker`.
- Treated as a wet tool (0.55 hydration peak) so it still layers/dries in the
  wet pass but dries fast to its matte finish.

### Dry Brush (bristle) — style 9
Look: scuffed, broken, hairy. The mark is interrupted: rotated bristle gaps
create white slivers through the stroke; the faster you move, the more the
pigment skips.
Render:
- AGSL: dry-brush kernel uses seed-rotated bristle-gap coverage.
- Vector: `drawDryBrushStroke` — dashed stamping with seed-scaled gap length.
- Ink: nearest stock brush `pencilUnstable`.

### Palette Knife — style 10
Look: thick flat sweeps of paint scraped straight onto the canvas, smearing and
dragging the paint underneath. Flat, squared, directional — none of the rounded
pen feel. (Phase 121: this is the ONLY intentional non-round edge in the app;
everything else renders round caps/joins — see `services/BrushEdgePolicy.kt`. The
palette preview keeps the square-cap swatch to match the real smear.)
Render:
- AGSL: knife kernel does directional smearing of the wet sheet (strongest
  mixStrength 0.95 among the new tools).
- Vector: `drawPaletteKnifeStroke` — square-cap flat pass + directional
  smudge-streak passes perpendicular to travel.
- Ink: nearest stock brush `marker`.
- Treated as a wet tool (0.5 hydration peak) because it must smear the sheet.

## Existing-brush enhancements

- **Pen / Fountain Pen / Fineliner / Marker — velocity taper.** With
  *velocity modulation* enabled (Settings → Brush Studio), faster strokes are
  thinner (down to 0.55×); below a slow threshold the multiplier is 1.0 so
  handwriting is unchanged. Off by default → pixel-identical to pre-Phase-18.
- **Oil Paint / Watercolor — pressure taper.** Stylus pressure widens the
  bristle contact patch (light = wider, down to 0.75×) and raises pigment
  load 0.55→1.0. Full-pressure (mouse/finger) input = classic width.
- **Calligraphic / Chisel Marker — live nib angle.** Nib angle is now
  adjustable (defaults 45°/30°, unchanged from the classic fixed values) so the
  flat/tapered slice you draw with is interactive, persisted in Settings.
- **Airbrush — dwell density.** Holding the spray still deposits a few extra
  stamps (up to 6) where the touch idles >120 ms, so the paint builds up while
  you hold instead of stopping cold.
- **Watercolor — stronger wet edge.** A secondary bloom ring accents the
  paper-painted wet edge on API 33+.

## Vector fallback + Ink path

- API < 33: every new tool renders through BrushTextureEngine with its own
  texture/stamp recipe listed above — distinct, never a plain PEN.
- `isAdvanced` strokes (Android Ink API): new tools map to nearest stock brush
  via `ProtobufBrushLoader` (charcoal/dry → pencilUnstable, ink/gouache →
  marker, others → pressurePen). This path is an approximation only.

## Serialization

- Tools are persisted by name (`StrokeTool.valueOf`), so saved strokes / custom
  presets with the new tools survive restarts. See `BrushStrokeMathTest`.