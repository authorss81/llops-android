# Phase 4: Real AGSL watercolor & oil — fix wet-mixing math (HIGH-VALUE PAINTING) [DONE]

You are working on **InkFlow/Noteflow**, an offline-first notes + canvas Android
app with a genuine AGSL (API 33+) wet-mixing paint engine. This phase makes the
watercolor/oil brushes actually behave like paint. The AGSL pipeline IS wired
(`WetMixingEffect` in `AgslShaders.kt`, `drawWetLayerPass` in
`AnnotationCanvas.kt`, gated by `ShaderCapabilityHelper` + `WetBrushEngine` tiers)
— but several Phase-31 roadmap sub-features were never implemented, and one
piece of grid code is dead weight. This is the "real watercolor" phase the
product needs. No schema changes, no new deps.

## Verified problems (from graphics audit — fix each)

### 1. Wet-on-wet alpha accumulation is WRONG (highest-value fix)
`app/src/main/kotlin/com/authorss81/noteflow/ui/components/AgslShaders.kt` (:116-123):
the `uWetness > 0.5` branch still uses `newAlpha = max(base.a, brushAlpha * 0.75) + base.a * brushAlpha * 0.25`,
which BLOCKS overlap darkening — the exact opposite of wet-on-wet watercolor.
Roadmap 31.2(C) claims it was replaced with source-over accumulation
`base.a + brush.a*falloff*wet*(1-base.a)` — it wasn't. (Note: source-over
accumulation ALREADY exists in the `else`/dry branch at `:122` —
`base.a + brushAlpha * (1.0 - base.a)`. The fix is to apply source-over to the
wet branch.)

**Fix:** Replace the wet-branch alpha formula with true source-over accumulation
so overlapping washes bloom darker (wet-on-wet). Keep the kernel loop-free.

### 2. Muddy linear-RGB pigment mixing
The kernel mixes colors with linear-RGB `mix(base.rgb, uBrushColor.rgb, uPigmentLoad * falloff * uMixStrength * granulation)` (`AgslShaders.kt:70-76`),
which makes overlapping paint go muddy/gray. Real watercolor stays clean because
mixing happens in a pigment/subtractive space.

**Fix:** Implement pigment-space mixing in the shader (e.g. complementary
product mixing `1 - (1-a)*(1-b)` per channel, or a cheap "paint space" conversion
before the lerp). Mirror the math in a Kotlin reference function that unit tests
verify, per the project's "keep blend math in a tested Kotlin reference" rule.

### 3. Full-page shader pass instead of dirty-rect (perf)
`drawWetLayerPass` does a full-page `saveLayer(RectF(0f, offsetY, pageWidth, offsetY + pageHeight))`
every frame (~1.65M px, `AnnotationCanvas.kt:2055-2056`) when roadmap 31.4
promised a dirty-rect `clipRect(bbox±radius±8px)` + `translate(-l,-t)` (~40K px,
~98% GPU cut) and a 0.5× internal preview resolution during drag with full-res
commit at stroke end. (Verified: `graphicsLayer` is passed into
`drawWetLayerPass` at :2006 but never used — safe to repurpose for scoping.)

**Fix:** Scope the wet pass to the stroke bounding rect (record the clip rect,
translate so the shader coords are local). Optionally add the 0.5× preview-res
during active drag with a full-res pass at stroke end. Keep `renderEffect = null`
when idle (zero GPU work) — already true: `hasEffect` gate at :2020-2039, effect
set at :2046-2053.

### 4. Dead `WetCanvasEngine` grid
`WetCanvasEngine.kt` writes 128×128 wetness/pigment grids
(`depositStrokePoint`, wired at `AnnotationCanvas.kt` ~:624-631) but NOTHING ever
reads them for rendering (all reads at `WetCanvasEngine.kt:174-175,180,188-242`
are internal diffusion/drying only) — pure CPU burn at ~8fps with zero visual
effect. The only data that reaches the shader is `brushParams.paperGrain` →
`uPaperGrain`; there are NO grid/texture uniforms.

**Fix (honest choice):**
- Preferred: feed the wetness grid into the shader as a low-res "water map"
  uniform/texture so pigment diffusion actually blooms (this is the real way to
  get watercolor spread — the "pigment dispersion kernel" effect). This requires
  adding a texture sampler uniform to the AGSL kernel, OR
- Remove the grid entirely and the `depositStrokePoint` call if wiring it is not
  tractable this phase. Do NOT leave silent dead CPU burn.

### 5. Grain shimmer (STRICTLY optional — only if items 1-4 are done and tests green)
`pseudoNoise` uses `fract(sin())` (`AgslShaders.kt:41-43`, used :67) which is
non-temporal but position-deterministic only. Replace with stable multi-octave
hash noise + a per-page seed uniform so paper grain is spatially coherent and
dithers between strokes. **Time-box: if you reach this after items 1-4 + tests,
it's a bonus; if it risks destabilizing the phase, skip it and note it as
deferred.**

## Shader interface (exact, for the implementer)
`AgslShaders.kt:29-39` uniforms: `contents` (shader child, bound via
`createRuntimeShaderEffect(runtimeShader, "contents")` :188,:190), `uPrevPos`
(float2), `uBrushPos` (float2), `uBrushRadius` (float), `uBrushColor` (half4),
`uWetness`, `uPigmentLoad`, `uMixStrength`, `uImpasto`, `uPaperGrain`,
`uHardness`. Setters at :206-215. If you add a water-map texture, add a new
uniform + setter here.

## Definition of done
- `gradle assembleDebug` succeeds.
- `gradle testDebugUnitTest` passes.
- New unit tests cover the wet-mixing alpha and pigment-mixing reference
  functions (pure Kotlin/JVM — verify numeric behavior, e.g. overlap darkens,
  overlapping complementary colors stay clean).
- The wet pass is scoped (dirty-rect) and does zero GPU work when idle.
- The `WetCanvasEngine` grid is either wired to rendering or removed — not dead.

## Constraints
- No new third-party dependencies. All shader math stays AGSL string + Kotlin.
- API 33+ only for the AGSL path — do NOT touch the API<33 vector fallback
  brushes; they must keep working unchanged.
- Do NOT change the DB schema (wet params are per-tool presets, not persisted).
- Do NOT edit `.github/workflows/`.
- Visual tuning on-device is expected later; this phase delivers the correct
  math + scoping so that tuning has a real baseline.