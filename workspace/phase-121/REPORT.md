# Phase 121 — Rounder, smoother non-pen brush edges

**Status:** DONE
**Dates:** implemented 2026-08-17
**Build:** `gradle testDebugUnitTest` → 1692 tests, 0 failures, 0 errors · `gradle assembleDebug` → BUILD SUCCESSFUL

## Goal (from PROMPT)
All non-pen brushes must render with round caps/joins and smooth (non-stepped)
width transitions — no sharp edges at joins, ends, or pressure changes — while
keeping each brush's wet-mixing/texture character. Pure-JVM tests where the
geometry is testable, else file:line proof on every non-pen brush path.

## Audit result (what was already true)
A full read of the render pipeline shows the strokes were **already round** — the
sharpness the user reported lived in the **brush-palette previews**, not the strokes:

| Tool(s) | Path | Cap/join |
|---|---|---|
| PEN, HIGHLIGHTER | `AnnotationCanvas.kt:2943` (velocity), `:2985` (Bezier path) | `StrokeCap.Round` / `StrokeJoin.Round` |
| FOUNTAIN_PEN | `AnnotationCanvas.kt:3017` | `StrokeCap.Round` |
| MARKER | `AnnotationCanvas.kt:3199`, `:3212` | `StrokeCap.Round` / `StrokeJoin.Round` |
| DOTTED | `AnnotationCanvas.kt:3262`, `:3278-3279` | `StrokeCap.Round` / `StrokeJoin.Round` |
| NEON | `AnnotationCanvas.kt:3306-3316` | `StrokeCap.Round` / `StrokeJoin.Round` |
| FINELINER | `AnnotationCanvas.kt:3339`, `:3364` | `StrokeCap.Round` / `StrokeJoin.Round` |
| LASER | `AnnotationCanvas.kt:3416-3426` | `StrokeCap.Round` / `StrokeJoin.Round` |
| PENCIL, OIL_PAINT, WATERCOLOR, OIL_PASTEL, GOUACHE (textured) | `BrushTextureEngine.drawTexturedStrokePath` `:136-137` | `Paint.Cap.ROUND` / `Paint.Join.ROUND` |
| CHARCOAL, DRY_BRUSH, INK_WASH | route through the above via `drawCharcoalStroke`/`drawDryBrushStroke`/`drawInkWashStroke` | round |
| CALLIGRAPHIC, CHISEL_MARKER | `drawRibbonStroke` (quad overlap + interior vertex caps + round end caps) | round by construction |
| PALETTE_KNIFE (flat smear pass) | `BrushTextureEngine.kt:329-330` | `Paint.Cap.SQUARE` / `Paint.Join.BEVEL` — **intentional, documented** (docs/brush-styles.md style 10) |

**Width transitions:** the velocity-tapered tools (PEN/FOUNTAIN_PEN/FINELINER
velocity modes) use `BrushStrokeMath.velocityWidthFactor` — a continuous,
non-stepped ramp in [0.55, 1.0] (proven monotone + slope-bounded in the new
test). Constant-width textured/stamp tools have a single smooth width by design.

## Root cause of the "sharp edges" users saw
`PenNibVisualPreview.kt` hard-coded `StrokeCap.Square` for FINELINER (`:126`),
HIGHLIGHTER (`:170`), OIL_PASTEL/GOUACHE (`:193`, `:198`) — but every one of those
tools **renders ROUND on the canvas**. The palette swatches therefore showed sharp
square ends that the real stroke never produces. Only PALETTE_KNIFE legitimately
keeps square caps (its real smear is square).

## Implementation

### 1. `app/src/main/kotlin/com/authorss81/noteflow/services/BrushEdgePolicy.kt` (new, pure JVM)
Single source of truth for brush-edge roundness:
- `LineCap.ROUND/SQUARE`, `LineJoin.ROUND/BEVEL` enums.
- `EdgeStyle(cap, join, smoothWidth)` + `rationaleFor(tool)` with a docs anchor.
- `edgeStyleFor(tool)`: every tool → ROUND/ROUND, **except PALETTE_KNIFE** →
  SQUARE/BEVEL (documented flat smear, docs/brush-styles.md style 10).
- Velocity-taper tools (PEN, FOUNTAIN_PEN, FINELINER, CALLIGRAPHIC,
  CHISEL_MARKER) flagged `smoothWidth = true`.

### 2. `ui/components/PenNibVisualPreview.kt` (fixed)
`previewCap` is now derived from `BrushEdgePolicy.edgeStyleFor(tool).cap` and every
swatch draw site uses it — so a preview can never silently diverge from the stroke
render again. FINELINER/HIGHLIGHTER/OIL_PASTEL/GOUACHE swatches now show round
caps (matching reality); palette-knife keeps its square swatch (matching its
smear). No other ui/components files touched — `AnnotationCanvas.kt` stays as-is
(already correct), preserving nib character.

### 3. `docs/brush-styles.md`
Palette-knife section now states it is the intentional non-round exception
(pointed at `BrushEdgePolicy`), so future phases don't "fix" it away.

## Tests — `app/src/test/java/com/authorss81/noteflow/Phase121BrushEdgeTest.kt` (9)
- **Policy table**: every freehand tool has an entry; all ROUND caps/joins except
  the documented flat knife (SQUARE cap + BEVEL join).
- **Smooth width**: `usesSmoothWidthTransitions` correctly flags the 5 velocity
  tools only; `velocityWidthFactor` is monotone non-increasing with no step bigger
  than the analytic max slope and hits the documented 1.0→0.55 endpoints.
- **Source pins** (read the real files): no `StrokeCap.Square`/`StrokeJoin.Bevel`
  anywhere in `AnnotationCanvas.kt`; `BrushTextureEngine` has exactly one
  `Paint.Cap.SQUARE` + one `Paint.Join.BEVEL`, both inside `drawPaletteKnifeStroke`;
  the shared textured path paints `Paint.Cap.ROUND`/`Paint.Join.ROUND`;
  `PenNibVisualPreview` maps the policy (no hard-coded `StrokeCap.Square` outside
  the mapping line, 5 `cap = previewCap` draw sites).
- **doc pin**: `docs/brush-styles.md` still documents the flat squared knife.

## Verification
- `gradle testDebugUnitTest` → **BUILD SUCCESSFUL**, 1692 tests, 0 failures / 0
  errors / 0 skipped (9 new). The pre-existing `B1Plat01ReleaseSigningTest`
  appears green now under the release-signing env the CI provides.
- `gradle assembleDebug` → **BUILD SUCCESSFUL** (first plain invocation had one
  transient daemon flake; immediate re-run green — non-reproducible, matches the
  phase-120 flake pattern).

## Constraints honoured
NO schema change, no migrations, no new dependencies, `.github/workflows/` untouched,
no logging of secrets, `allowBackup=false`/FLAG_SECURE untouched, low-end safe (the
preview cap is a single compile-time enum pick; the policy table is O(1) `when`).
No render-pipeline change, so AGSL wet-mixing and all brush textures are untouched.