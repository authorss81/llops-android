# Phase 247 — Paper Texture: true zero at strength 0

## Goal
User-reported: "paper texture dots never fully disappear." `PaperTextureStrengthPolicy.grainDrawAlpha` had a hardcoded `MIN_ALPHA = 0.02f` floor at strength 0, so grain still drew at ~44% of the default — never reached true zero. Fix: return `0f` exactly at strength 0 while preserving anchoring at 50 → 1.0.

## Context — verified at `2709453`

`app/src/main/kotlin/com/authorss81/noteflow/services/PaperTextureStrengthPolicy.kt:40` `const val MIN_ALPHA = 0.02f` and `grainDrawAlpha(0) = 0.02` and `grainScale(0) = grainDrawAlpha(0) / grainDrawAlpha(50) = 0.02/0.045 = 0.444…`. So strength=0 keeps 44% of the default grain — the user's complaint.

The fix is surgical: at strength 0 every grain-related function returns 0, every call site (grain tile draw, AGSL `uPaperGrain` mapping) must honor it. Anchoring at 50 must stay byte-identical to pre-227 grain so untouched installs look unchanged.

## File to change

`app/src/main/kotlin/com/authorss81/noteflow/services/PaperTextureStrengthPolicy.kt`

### Implementation

Add an early-return for the zero-strength case. Recommended pattern (keep the lerp math for non-zero strength so the linear dial behavior is unchanged):

```kotlin
fun grainDrawAlpha(strength: Int): Float =
    if (clamp(strength) == 0) 0f
    else MIN_ALPHA + fraction(strength) * (MAX_ALPHA - MIN_ALPHA)

fun grainScale(strength: Int): Float =
    if (clamp(strength) == 0) 0f
    else grainDrawAlpha(strength) / grainDrawAlpha(DEFAULT)

fun shaderGain(strength: Int): Float =
    if (clamp(strength) == 0) 0f
    else fraction(strength) / fraction(DEFAULT)
```

`shaderStrength` is already linear (`fraction(strength)`) and returns 0 at 0 — no change.

Update the KDoc to document the zero-anchor at strength 0 and the unchanged 50-anchor (the "anchoring at DEFAULT = 50 → 1.0" honesty property is preserved: `grainDrawAlpha(50) = 0.045 = pre-fix MIN_ALPHA + 0.5*(MAX_ALPHA-MIN_ALPHA)`, `grainScale(50) = 1.0`, `shaderGain(50) = 1.0`).

### Verify call sites still respect 0

- `AnnotationCanvas.drawPaperGrain(grainBrush, ..., grainScale)` — passes 0f at strength 0; the draw call's alpha-multiplier collapses the tile to 0, the tile cache is reused (no per-strength tile explosion).
- AGSL `uPaperGrain = brushParams.paperGrain * shaderGain(strength)` — at strength 0 `shaderGain=0f`, the AGSL shader's grain sampling vanishes.
- `PaperTextureStrengthPolicyTest` — assert `grainDrawAlpha(0) == 0f`, `grainScale(0) == 0f`, `shaderGain(0) == 0f`, `shaderStrength(0) == 0f`, AND `grainDrawAlpha(50) == 0.045f` (unchanged), `grainScale(50) == 1.0f`, `shaderGain(50) == 1.0f`.

## New tests

`app/src/test/java/com/authorss81/noteflow/PaperTextureStrengthZeroTest.kt` (pure JVM):

- `grainDrawAlpha(0) == 0f` exactly
- `grainScale(0) == 0f` exactly
- `shaderGain(0) == 0f` exactly
- `shaderStrength(0) == 0f` exactly
- `grainDrawAlpha(50) == 0.045f` (unchanged pre-227 default)
- `grainScale(50) == 1.0f` (anchoring preserved)
- `shaderGain(50) == 1.0f` (anchoring preserved)
- `grainDrawAlpha(100) == 0.07f` (ceiling preserved)
- monotonicity: `grainDrawAlpha(0) < grainDrawAlpha(50) < grainDrawAlpha(100)`
- clamp: `grainDrawAlpha(-5) == 0f`, `grainDrawAlpha(999) == 0.07f`

## Constraints
- No schema change
- No new dependencies
- No `.github/workflows/` edits
- No behavior change for strength > 0; grain at 50 is byte-identical to pre-fix (the phase-227 anchoring contract)
- `verification-metadata.xml` untouched (no dep changes)
- `grainDrawAlpha(50) == 0.045f` must hold exactly (untouched installs look unchanged)

## DoD
- `gradle :app:testDebugUnitTest` 3556+ green (new tests added)
- `gradle :app:assembleDebug` + `assembleRelease` green
- `gradle :app:lintDebug` 0 errors
- Manual: paper texture at strength 0 = no dots (verified)
- Manual: paper texture at strength 50 = unchanged pre-227 look
- `workspace/phase-247/REPORT.md` with file:line evidence
