# Phase 247 — Paper Texture: true zero at strength 0

## Goal
User report: "paper texture dots never fully disappear." `PaperTextureStrengthPolicy.grainDrawAlpha` had a hardcoded `MIN_ALPHA = 0.02f` lerp floor, so at strength 0 the grain still drew at `0.02 / 0.045 ≈ 44%` of the default. Fix: return exactly `0f` at strength 0 while preserving the DEFAULT=50 anchoring contract byte-identically.

## Verified at `2709453`
`PaperTextureStrengthPolicy.kt:40` `const val MIN_ALPHA = 0.02f`; `grainDrawAlpha(0) = 0.02 f` (old `:52-53`), `grainScale(0) = 0.02 / 0.045 = 0.444…` (old `:60-61`). The complaint was real.

## Change made — `app/src/main/kotlin/com/authorss81/noteflow/services/PaperTextureStrengthPolicy.kt`
- `grainDrawAlpha` (`:77-79`): `if (clamp(strength) == 0) 0f else MIN_ALPHA + fraction(strength) * (MAX_ALPHA - MIN_ALPHA)`.
- `grainScale` (`:89-91`): `if (clamp(strength) == 0) 0f else grainDrawAlpha(strength) / grainDrawAlpha(DEFAULT)`.
- `shaderGain` (`:103-105`): `if (clamp(strength) == 0) 0f else fraction(strength) / fraction(DEFAULT)` — **defensive only, behavior-neutral** (`shaderGain(0)` was already `fraction(0)/fraction(DEFAULT)` = `0` pre-fix; see review-fixes below).
- `shaderStrength` (`:94`): unchanged — already linear `fraction(strength)`, exactly `0f` at 0.
- KDoc updated (`:31-45`): zero-anchor at strength 0 + the preserved 50-anchor honesty property. `MIN_ALPHA`/`MAX_ALPHA` constants unchanged.

Lerp math for strength > 0 is untouched, so the linear dial behavior is byte-identical to pre-fix.

## Anchoring verified
- `grainDrawAlpha(50) = 0.02 + 0.5 * 0.05 = 0.045` — exactly the pre-227 default. ✓
- `grainScale(50) = 0.045 / 0.045 = 1.0`. ✓
- `shaderGain(50) = 0.5 / 0.5 = 1.0`. ✓

## Call sites verified to honor the zero
- `grainScale` computed at `AnnotationCanvas.kt:1059-1061`; the cached tile is reused across all strengths (no per-strength tile explosion — `PaperGrainTileCache` unchanged).
- `drawPaperGrain` at `AnnotationCanvas.kt:4843-4860`: `scale = grainScale.coerceAtLeast(0f)` (0f at strength 0) → `drawRoundRect(..., alpha = scale.coerceIn(0f, 1f))` collapses the tile's alpha to exactly 0; the `scale > 1f` additive boost can never fire below the default. Card draw sites `:2880`, `:2958`, `:3059` pass `grainScale` straight through.
- AGSL `uPaperGrain` mapping at `AnnotationCanvas.kt:5892-5895`: `wetCanvasEngine.brushParams.paperGrain * shaderGain(paperTextureStrength)` — at strength 0 the product is `0f` (the caller's `.coerceIn(0f, 1f)` no-ops). **This path was already zero pre-fix** (`shaderGain(0)` == `shaderStrength(0)` == `0`); the user-facing defect was only the CACHED-tile raster path.

## Review fixes (phase-247 review-fix round)
- **F1 (KDoc accuracy)** — corrected the overclaim that "every grain path" (incl. AGSL) kept a `MIN_ALPHA` floor: the AGSL mapping was already zero at the floor pre-fix (`shaderGain(0)`/`shaderStrength(0)` == `0`). `shaderGain`'s early-return is documented as defensive only. `PaperTextureStrengthPolicy.kt:31-45` class KDoc + `:96-102` function KDoc updated.
- **F2 (`.editorconfig` compliance)** — `insert_final_newline = true` was violated by the new/changed Kotlin files and this REPORT; final newlines added to `PaperTextureStrengthPolicy.kt`, `PaperTextureStrengthPolicyTest.kt`, `PaperTextureStrengthZeroTest.kt`, `REPORT.md`.
- **F3 (stale constant KDoc)** — `MIN_ALPHA` (:54-58) no longer claims "the tooth that remains at minimum strength"; it now documents the TRUE ZERO supersession. `grainDrawAlpha` KDoc (:69-76) range text corrected to `0f..MAX_ALPHA` (the `MIN_ALPHA..MAX_ALPHA` band maps onto strengths 1..100).
- **F4 (documented 0→1 cliff)** — the intentional discontinuity at the 0 → 1 dial step (true zero vs. the `MIN_ALPHA` lerp base) is now called out in the class KDoc (:42-44).
- **F5 (test brittleness contract)** — `PaperTextureStrengthZeroTest` documents the float32 bit-identity pins (`0.045f` == `0x3d3851ec`, `0.07f` == `0x3d8f5c29`) so a future lerp reformulation breaks loudly, not silently.

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
