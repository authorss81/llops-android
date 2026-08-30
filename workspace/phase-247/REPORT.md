# Phase 247 — Paper Texture: true zero at strength 0

## Goal
User report: "paper texture dots never fully disappear." `PaperTextureStrengthPolicy.grainDrawAlpha` had a hardcoded `MIN_ALPHA = 0.02f` lerp floor, so at strength 0 the grain still drew at `0.02 / 0.045 ≈ 44%` of the default. Fix: return exactly `0f` at strength 0 while preserving the DEFAULT=50 anchoring contract byte-identically.

## Verified at `2709453`
`PaperTextureStrengthPolicy.kt:40` `const val MIN_ALPHA = 0.02f`; `grainDrawAlpha(0) = 0.02 f` (old `:52-53`), `grainScale(0) = 0.02 / 0.045 = 0.444…` (old `:60-61`). The complaint was real.

## Change made — `app/src/main/kotlin/com/authorss81/noteflow/services/PaperTextureStrengthPolicy.kt`
- `grainDrawAlpha` (`:66-68`): `if (clamp(strength) == 0) 0f else MIN_ALPHA + fraction(strength) * (MAX_ALPHA - MIN_ALPHA)`.
- `grainScale` (`:78-80`): `if (clamp(strength) == 0) 0f else grainDrawAlpha(strength) / grainDrawAlpha(DEFAULT)`.
- `shaderGain` (`:91-93`): `if (clamp(strength) == 0) 0f else fraction(strength) / fraction(DEFAULT)`.
- `shaderStrength` (`:83`): unchanged — already linear `fraction(strength)`, exactly `0f` at 0.
- KDoc updated (`:31-39`): zero-anchor at strength 0 + the preserved 50-anchor honesty property. `MIN_ALPHA`/`MAX_ALPHA` constants unchanged.

Lerp math for strength > 0 is untouched, so the linear dial behavior is byte-identical to pre-fix.

## Anchoring verified
- `grainDrawAlpha(50) = 0.02 + 0.5 * 0.05 = 0.045` — exactly the pre-227 default. ✓
- `grainScale(50) = 0.045 / 0.045 = 1.0`. ✓
- `shaderGain(50) = 0.5 / 0.5 = 1.0`. ✓

## Call sites verified to honor the zero
- `grainScale` computed at `AnnotationCanvas.kt:1059-1061`; the cached tile is reused across all strengths (no per-strength tile explosion — `PaperGrainTileCache` unchanged).
- `drawPaperGrain` at `AnnotationCanvas.kt:4843-4860`: `scale = grainScale.coerceAtLeast(0f)` (0f at strength 0) → `drawRoundRect(..., alpha = scale.coerceIn(0f, 1f))` collapses the tile's alpha to exactly 0; the `scale > 1f` additive boost can never fire below the default. Card draw sites `:2880`, `:2958`, `:3059` pass `grainScale` straight through.
- AGSL `uPaperGrain` mapping at `AnnotationCanvas.kt:5892-5895`: `wetCanvasEngine.brushParams.paperGrain * shaderGain(paperTextureStrength)` — with `shaderGain(0) = 0f` the product is `0f` (the caller's `.coerceIn(0f, 1f)` no-ops), so the shader's grain sampling vanishes at strength 0 while staying at the exact pre-227 value at 50.

## Tests
- `PaperTextureStrengthPolicyTest` updated: strength-0 assertions now expect true zero (`grainDrawAlpha(0) == 0f`, `grainScale(0) == 0f`) off the old `MIN_ALPHA` floor; all 50/100 anchors unchanged.
- New `app/src/test/java/com/authorss81/noteflow/PaperTextureStrengthZeroTest.kt` (10):
  - `grainDrawAlpha(0) == 0f` exactly
  - `grainScale(0) == 0f` exactly
  - `shaderGain(0) == 0f` exactly
  - `shaderStrength(0) == 0f` exactly
  - `grainDrawAlpha(50) == 0.045f` (unchanged pre-227 default)
  - `grainScale(50) == 1.0f` (anchoring preserved)
  - `shaderGain(50) == 1.0f` (anchoring preserved)
  - `grainDrawAlpha(100) == 0.07f` (ceiling preserved)
  - monotonicity `grainDrawAlpha(0) < grainDrawAlpha(50) < grainDrawAlpha(100)`
  - clamp `grainDrawAlpha(-5) == 0f`, `grainDrawAlpha(999) == 0.07f`

## DoD
- `gradle :app:testDebugUnitTest` — **3583 / 0 failures / 0 errors** (baseline 3573 + 10 new; the `Phase148UiFailureTextScrubTest` UNC-path flake green this run).
- `gradle :app:assembleDebug` + `:app:assembleRelease` (R8 + shrinkResources + signed) — **green**.
- `gradle :app:lintDebug` — **0 errors** (109 warnings / 15 info, no Error-severity issues).
- Manual: paper texture at strength 0 = no dots — guaranteed by the pure-JVM policy (draw alpha 0, AGSL gain 0) and the verified call sites above.
- Manual: paper texture at strength 50 = unchanged pre-227 look — all DEFAULT anchors asserted byte-identical.

## Constraints respected
- No schema change, no new dependencies, `.github/workflows/` untouched, `verification-metadata.xml` untouched.
- No behavior change for strength > 0; `grainDrawAlpha(50) == 0.045f` holds exactly.