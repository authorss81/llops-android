package com.authorss81.noteflow.services

/**
 * Phase 227 — user-tunable paper texture ("tooth") strength. Pure JVM so the
 * clamp, the alpha lerp and the shader mapping are unit-testable without
 * Android.
 *
 * The paper grain previously shipped at one baked strength: a tileable noise
 * brush whose peak pixel alpha is [PaperGrainPolicy.LIGHT_SPECKLE_MAX_ALPHA] /
 * [DARK_SPECKLE_MAX_ALPHA] (0.05 / 0.07), with NO user control. This policy
 * turns the user's 0–100 dial into three concrete numbers:
 *
 *  - [grainScale]   — the draw-time global-alpha multiplier applied to the
 *                     CACHED grain tile (one tile per paper family stays valid
 *                     across every strength, so there is no per-strength tile
 *                     explosion in `PaperGrainTileCache`).
 *  - [grainDrawAlpha] — the prompt's `0.02 + strength/100 * 0.05` lerp; the
 *                     fallback absolute-alpha estimate used by callers that
 *                     want a number, and the DEFAULT-anchoring for [grainScale].
 *  - [shaderStrength] / [shaderGain] — the AGSL wet-shader `uPaperGrain`
 *                     mapping. The wet brush's per-preset granulation
 *                     (`WetBrushParams.paperGrain`, e.g. 0.92 charcoal) is
 *                     SCALED by [shaderGain], which is anchored at 1.0 for the
 *                     default 50 so a stock install keeps the exact pre-227
 *                     wet-paint granulation (never silent degradation).
 *
 * Anchoring at [DEFAULT] (50) is the key honesty property: an untouched
 * install sees [grainScale] == [shaderGain] == 1.0, so the grain tile and the
 * wet shader are byte-identical to pre-227 rendering.
 *
 * Phase 247 — TRUE ZERO at strength 0. Previously the CACHED-tile draw path
 * had a hardcoded [MIN_ALPHA] floor: `grainDrawAlpha(0)` == `0.02` and
 * `grainScale(0)` ≈ `0.444`, so dialing the tooth all the way down still kept
 * ~44% of the default grain (the "the dots never fully disappear" report).
 * Now [grainDrawAlpha] and [grainScale] early-return exactly `0f` at clamped
 * strength 0, so the grain tile's draw alpha collapses to 0. The AGSL mapping
 * was already zero at the floor pre-fix (`shaderGain(0)` == `fraction(0) /
 * fraction(DEFAULT)` == `0`, and [shaderStrength](0) == `0`) — their
 * early-returns are defensive only, kept for symmetry. The lerp math for
 * strength &gt; 0 is untouched and the [DEFAULT] anchors stay byte-identical
 * (`grainDrawAlpha(50)` == `0.045f`, `grainScale(50)` == `shaderGain(50)` ==
 * `1.0f`), so a stock install renders the exact pre-227 grain. Note the 0 → 1
 * dial step is intentionally a jump (true zero vs. the [MIN_ALPHA] lerp base);
 * that discontinuity is inherent to the specified pattern.
 */
object PaperTextureStrengthPolicy {

    const val MIN = 0
    const val MAX = 100

    /** The pre-227 look is the 50 midpoint; strength below/above is the dial. */
    const val DEFAULT = 50

    /**
     * Lerp base for strengths 1..100. Superseded by a TRUE ZERO at strength 0
     * (the tooth no longer "remains" at the minimum — see the class KDoc).
     */
    const val MIN_ALPHA = 0.02f

    /** Lerp ceiling — `MIN_ALPHA + 0.05`, the prompt's `strength/100 * 0.05` term. */
    const val MAX_ALPHA = 0.07f

    /** Clamp any Int to the valid dial range (corrupt prefs cannot escape). */
    fun clamp(strength: Int): Int = strength.coerceIn(MIN, MAX)

    /** Dial position as a 0..1 fraction of the dial. */
    fun fraction(strength: Int): Float = clamp(strength) / 100f

    /**
     * The absolute average alpha estimate. TRUE ZERO at strength 0 (the grain
     * must fully disappear, not sit at the old [MIN_ALPHA] floor), then the
     * lerp resumes from its [MIN_ALPHA] base: the visible dial range is
     * `0f..[MAX_ALPHA]`, with the linear `[MIN_ALPHA]..[MAX_ALPHA]` band mapped
     * onto strengths 1..100 (that 0 → 1 discontinuity is inherent to the
     * specified true-zero pattern).
     */
    fun grainDrawAlpha(strength: Int): Float =
        if (clamp(strength) == 0) 0f
        else MIN_ALPHA + fraction(strength) * (MAX_ALPHA - MIN_ALPHA)

    /**
     * Draw-time multiplier over the CACHED tile. Anchored at exactly 1.0 for
     * [DEFAULT]; TRUE ZERO at strength 0 (the draw call's alpha-multiplier
     * collapses the tile to nothing), then the lerp resumes from its
     * [MIN_ALPHA] floor so the linear dial behavior is unchanged — 100 maps
     * to ~1.56 (nearly twice the default fleck, still comfortably inside the
     * alpha envelope).
     */
    fun grainScale(strength: Int): Float =
        if (clamp(strength) == 0) 0f
        else grainDrawAlpha(strength) / grainDrawAlpha(DEFAULT)

    /** AGSL `uPaperGrain` absolute value (`strength/100`, 0..1). Already 0 at zero. */
    fun shaderStrength(strength: Int): Float = fraction(strength)

    /**
     * Multiplier over the wet brush's OWN per-preset `paperGrain`
     * (`uPaperGrain = brushParams.paperGrain * shaderGain(strength)`). Anchored
     * at 1.0 for [DEFAULT]; TRUE ZERO at strength 0 (defensive only — this was
     * already `fraction(0) / fraction(DEFAULT)` == 0 pre-fix); range 0..2 (the
     * caller clamps the product to ≤1).
     */
    fun shaderGain(strength: Int): Float =
        if (clamp(strength) == 0) 0f
        else fraction(strength) / fraction(DEFAULT)
}
