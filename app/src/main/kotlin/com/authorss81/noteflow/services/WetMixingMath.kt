package com.authorss81.noteflow.services

import androidx.compose.ui.graphics.colorspace.ColorSpace
import androidx.compose.ui.graphics.colorspace.ColorSpaces

/**
 * Kotlin reference implementations of the AGSL wet-mixing math in
 * [com.authorss81.noteflow.ui.components.AgslShaders.WET_MIXING_SHADER].
 *
 * The project rule is that tuned blend math must live in a tested Kotlin
 * reference first; the shader string mirrors it. If either side changes, the
 * other and the unit tests must be updated together.
 *
 * Phase 200 (PERF 3.2): pigment mixing happens in **linear light**. The
 * pre-200 reference mixed raw gamma-encoded sRGB channels, which is why
 * overlapping washes went muddy: gamma encoding compresses highlights, so any
 * average/product taken directly on encoded values under-represents light and
 * collapses bright complementary deposits toward dark desaturated browns. In
 * linear space the absorbance product (Beer–Lambert) is physically correct and
 * equal-energy mixes keep relative luminance (red+green stays a luminous
 * yellow instead of olive).
 *
 * The mix space is selected with a [ColorSpace] parameter so callers — and the
 * unit tests — can pin the exact working space:
 *  - [ColorSpaces.LinearSrgb] (default): channels pass through the standard
 *    piecewise sRGB transfer functions before/after the mix. Mirrored 1:1 by
 *    the shader's `srgbToLinear3`/`linearToSrgb3`.
 *  - [ColorSpaces.Srgb]: identity passthrough — byte-compatible with the
 *    pre-200 muddy behaviour, kept as the parity escape hatch.
 *  - Any other space fails safe to LINEAR (the honest physical model); AGSL
 *    cannot express arbitrary color-space matrices, so the shader mirror only
 *    ever implements the two supported encodings.
 */
object WetMixingMath {

    /**
     * Source-over alpha accumulation (wet-on-wet watercolor).
     * Overlapping washes must monotonically darken: a second wash over an
     * existing one can never lower the resulting alpha below the larger input.
     * Mirrors the shader's `base.a + brushA * (1.0 - base.a)`.
     *
     * Alpha compositing is color-space independent — this never touches RGB.
     */
    fun sourceOverAlpha(baseA: Float, brushA: Float): Float {
        val a = baseA.coerceIn(0f, 1f) + brushA.coerceIn(0f, 1f) * (1f - baseA.coerceIn(0f, 1f))
        return a.coerceIn(0f, 1f)
    }

    /** Standard piecewise sRGB EOTF: encoded channel -> linear light. */
    fun srgbToLinear(channel: Float): Float {
        val c = channel.coerceIn(0f, 1f)
        return if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055) / 1.055).toDouble(), 2.4).toFloat()
    }

    /** Standard piecewise sRGB inverse EOTF: linear light -> encoded channel. */
    fun linearToSrgb(channel: Float): Float {
        val c = channel.coerceIn(0f, 1f)
        return if (c <= 0.0031308f) c * 12.92f else (1.055 * Math.pow(c.toDouble(), 1.0 / 2.4) - 0.055).toFloat()
    }

    /**
     * One sRGB-encoded channel converted into [space]'s encoding. Only the two
     * spaces mirrored by the AGSL shader are honoured ([ColorSpaces.Srgb]
     * identity, everything else — including unknown custom spaces — fails safe
     * to linear light).
     */
    fun channelToMixSpace(srgbEncoded: Float, space: ColorSpace): Float =
        if (space === ColorSpaces.Srgb) srgbEncoded else srgbToLinear(srgbEncoded)

    /**
     * A channel encoded in [space] converted back to sRGB encoding (the canvas
     * surface stays sRGB-encoded, exactly like the shader's output write-back).
     */
    fun channelFromMixSpace(mixEncoded: Float, space: ColorSpace): Float =
        if (space === ColorSpaces.Srgb) mixEncoded else linearToSrgb(mixEncoded)

    /**
     * Pigment-space (subtractive/complementary-product) mixing for a single
     * channel ALREADY ENCODED IN THE WORKING MIX SPACE. Absorbances multiply:
     * result = 1-(1-base)(1-brush). Interpolates with [factor] (0.0 -> keep
     * base, 1.0 -> fully deposit brush).
     * Mirrors the shader's pigment mixing branch.
     */
    fun pigmentMix(base: Float, brush: Float, factor: Float): Float {
        val f = factor.coerceIn(0f, 1f)
        val b = base.coerceIn(0f, 1f)
        val c = brush.coerceIn(0f, 1f)
        val combined = 1f - (1f - b) * (1f - c)
        return (b + (combined - b) * f).coerceIn(0f, 1f)
    }

    /**
     * Pigment-space mixing for the three RGB channels of a color deposit,
     * performed in [mixSpace] (default [ColorSpaces.LinearSrgb]) and returned
     * re-encoded to sRGB. Passing [ColorSpaces.Srgb] reproduces the legacy
     * pre-200 gamma-space behaviour bit-for-bit.
     *
     * Mirror note (phase-200 review-fix): the AGSL shader additionally routes
     * zero-pigment pixels through a BIT-EXACT passthrough (`base.rgb` /
     * `vibBrushColor`) instead of paying an fp16 EOTF round trip; this Kotlin
     * reference stays purely algebraic — in double precision the factor-0 round
     * trip is already exact to <1e-7, so no branch is needed here.
     */
    fun pigmentMixRgb(
        baseR: Float, baseG: Float, baseB: Float,
        brushR: Float, brushG: Float, brushB: Float,
        factor: Float,
        mixSpace: ColorSpace = ColorSpaces.LinearSrgb
    ): FloatArray = floatArrayOf(
        channelFromMixSpace(
            pigmentMix(channelToMixSpace(baseR, mixSpace), channelToMixSpace(brushR, mixSpace), factor),
            mixSpace
        ),
        channelFromMixSpace(
            pigmentMix(channelToMixSpace(baseG, mixSpace), channelToMixSpace(brushG, mixSpace), factor),
            mixSpace
        ),
        channelFromMixSpace(
            pigmentMix(channelToMixSpace(baseB, mixSpace), channelToMixSpace(brushB, mixSpace), factor),
            mixSpace
        )
    )
}
