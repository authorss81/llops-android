# Phase 18: Brush engine expansion — new brush types, painting styles, enhanced existing brushes

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with a real paint engine: AGSL GPU wet-mixing (`AgslShaders.WET_MIXING_SHADER`
+ `WetMixingEffect`, API 33+), vector fallback for API < 33
(`AnnotationCanvas` non-AGSL render path), `WetBrushEngine` quality/tiering,
`BrushStudioDialog` parameters (size, pressure curve, wetness, pigment load,
texture, hardness, granulation), `PressureCurveHelper`, `StrokeStabilizer`,
`ColorHarmonyHelper`, and a `StrokeTool` enum with freehand + wet tools.

This phase (1) adds NEW brush types and painting styles, (2) ENHANCES existing
brushes with real, visible behavior improvements. NO fake brushes — every new
brush must render distinctly and persist. Everything is built on the EXISTING
engine architecture (you may extend the AGSL shader and the vector render path);
do NOT replace the engine.

## 1. New brush types & painting styles (real, distinct rendering)
Extend `StrokeTool` with new freehand tools AND give each a genuinely different
look via new AGSL uniforms + vector-fallback equivalents. Recommended set (add
at least these 6 — each MUST be visually distinct from existing tools):
- **CHARCOAL** — rough, powdery, grainy stroke with soft-edged streaks; pigment
  deposits mainly on paper-grain peaks; smudgy dark blacks.
- **OIL_PASTEL** — waxy, opaque, chalky layering; hard-ish edge, strong color
  body, visible wax streak noise.
- **INK_WASH (sumi-e)** — a wet ink that bleeds heavily along edges with a strong
  dark wet-edge fringe and slow pool behavior (distinct from WATERCOLOR's light
  washes — INK is high-contrast, concentrated).
- **GOUACHE** — opaque matte paint: near-100% pigment coverage, minimal
  transparency, flat even coat (distinct from OIL_PAINT's impasto relief).
- **DRY_BRUSH** — bristle-tip gaps: paint is deposited only where individual
  bristles touch; sparse, streaky, low coverage by default.
- **PALETTE_KNIFE** — flat spatula smear: a wide, flat swipe that drags/smears
  existing paint rather than piling on pigment (mixes strongly, low impasto
  height, directional streaks).

Implementation notes:
- Add the new tools to `StrokeTool`, its `label`, and `isFreehandTool`.
- AGSL: extend `WET_MIXING_SHADER` (or add one shared shader with a new
  `uBrushStyle` uniform) to realize each style: dry-brush bristle gaps, chalk
  grain streaks, ink wet-edge emphasis, gouache opacity, knife smear direction.
  Update `ToolPreset`/`PRESETS` with entries for each new tool.
- Vector fallback (API < 33) must implement a reasonable approximation of each
  style (e.g. alpha-jitter for dry-brush, streak lines for charcoal) in the
  existing non-AGSL branch — never silently render as plain PEN.
- `ProtobufBrushLoader.getBrushFamilyForTool` must handle the new tools honestly
  (map to a nearest real family or extend the mapping) — check how it's used and
  extend without breaking existing tools.
- VERIFY how `StrokeTool` is serialized (pointsJson / stroke storage / PSD /
  import-export). If tool is stored by name, adding enum values is safe; if any
  numeric/ordinal mapping exists, extend it safely with a migration note. Do not
  silently break existing saved strokes.

## 2. Enhance existing brushes (visible, testable improvements)
Pick and implement at least 5 of these on EXISTING tools — each must be a real
change you can demonstrate:
- **Velocity-based width modulation**: faster stroke → thinner line (applies to
  PEN/FOUNTAIN_PEN/FINELINER/CALLIGRAPHIC). Pure math, unit-testable.
- **Pressure-driven bristle spread** for OIL_PAINT/WATERCOLOR: higher pressure →
  wider contact + more pigment (extend existing pressure mapping).
- **Per-stroke texture rotation/seed**: give each stroke a fresh `uSeed` so paper
  grain/texture orientation varies per stroke instead of being fixed per page.
- **Calligraphic angle control**: rotate the nib angle in CALLIGRAPHIC and
  CHISEL_MARKER via a small angle parameter in BrushStudioDialog.
- **Watercolor edge improvement**: tighter, more realistic wet-fringe + bloom for
  WATERCOLOR (existing fringe is a single smoothstep — add a secondary bloom
  ring). Must stay cheap on GPU.
- **Dry-brush skip**: for PENCIL/CHARCOAL-like tools, skip depositing pigment on
  paper-grain valleys so texture is visible in the falloff region.
- **Airbrush density**: make AIRBRUSH respect dwell time (longer hold = denser)
  via existing interpolation, not a new engine.
Add any new exposed parameters to `BrushStudioDialog` (reachable, functional,
persisted via existing settings path — NO schema change).

## Definition of done
- `gradle assembleDebug` succeeds (AGSL shader compiles, vector path compiles).
- `gradle testDebugUnitTest` passes with new pure-JVM tests:
  - velocity→width function,
  - pressure→bristle-spread mapping,
  - new `ToolPreset` entries have valid param ranges (wetness/pigment/impasto/
    hardness each in [0,1]),
  - per-stroke seed varies across strokes,
  - serialization round-trip for the new tools (if serialization is by-name).
- Each new brush renders DISTINCTLY (documented; ideally a manual note in
  `docs/` describing each style's expected look).
- New tools reachable in the tool picker, work, and persist after app restart.
- No existing brush regresses: PEN/OIL_PAINT/WATERCOLOR default look unchanged
  when their new params are at defaults.

## Constraints
- NO new third-party dependencies. NO new permissions. NO `INTERNET`.
- Do NOT change the DB schema. Extend serialization SAFELY if needed (explain).
- Do NOT edit `.github/workflows/`.
- Respect existing tiering: AGSL = high/mid; vector = API < 33. Heavy shaders
  must be cheap enough for the low-end tier (use `WetBrushEngine.currentQuality`
  to scale style detail like existing code does).
- Be honest: if a style cannot be made visually distinct within this phase's
  constraints, omit it rather than ship a renamed PEN. Say what was deferred.