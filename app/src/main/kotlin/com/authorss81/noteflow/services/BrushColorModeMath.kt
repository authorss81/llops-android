package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.StrokeColorMode
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure-JVM color math for multi-color brush effects (Phase 27).
 *
 * The per-point color of a non-solid stroke is DERIVED at render time from the
 * stroke's stored [StrokeColorMode] + seed (+ optional gradient end color) —
 * never stored per point — so persistence is unchanged (mode + seed round-trip
 * through the stroke's existing serialized payload). Everything here is
 * unit-testable on the JVM and MUST stay free of Android dependencies.
 *
 * The [edgeFeather] function is the exact Kotlin mirror of the AGSL shader's
 * falloff so the vector path and the shader share one tested formula.
 */
object BrushColorModeMath {

    /** Number of pixels of edge feather the AGSL falloff guarantees (shader mirror). */
    const val MIN_FEATHER_PX = 1.5f

    // ---- HSV <-> ARGB -----------------------------------------------------

    fun normalizeHue(h: Float): Float {
        var hh = h % 360f
        if (hh < 0f) hh += 360f
        return hh
    }

    /** Converts an ARGB color to [hue, saturation, value]. Alpha is ignored. */
    fun argbToHsv(argb: Int): FloatArray {
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val maxC = max(r, max(g, b))
        val minC = min(r, min(g, b))
        val d = maxC - minC
        val v = maxC
        if (d < 1e-5f) return floatArrayOf(0f, 0f, v)
        val s = d / maxC
        val h = when (maxC) {
            r -> (((g - b) / d) + if (g < b) 6f else 0f) * 60f
            g -> (((b - r) / d) + 2f) * 60f
            else -> (((r - g) / d) + 4f) * 60f
        }
        return floatArrayOf(normalizeHue(h), s, v)
    }

    /** Converts hue/saturation/value to an opaque ARGB color, always in gamut. */
    fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val hue = normalizeHue(h)
        val ss = s.coerceIn(0f, 1f)
        val vv = v.coerceIn(0f, 1f)
        if (ss <= 1e-5f) {
            val g = (vv * 255f).roundToInt().coerceIn(0, 255)
            return 0xFF000000.toInt() or ((g shl 16) or (((g shl 8)) or g))
        }
        val sector = floor(hue / 60f).toInt() % 6
        val f = hue / 60f - floor(hue / 60f)
        val p = vv * (1f - ss)
        val q = vv * (1f - ss * f)
        val t = vv * (1f - ss * (1f - f))
        var r = 0f
        var g = 0f
        var b = 0f
        when (sector) {
            0 -> { r = vv; g = t; b = p }
            1 -> { r = q; g = vv; b = p }
            2 -> { r = p; g = vv; b = t }
            3 -> { r = p; g = q; b = vv }
            4 -> { r = t; g = p; b = vv }
            else -> { r = vv; g = p; b = q }
        }
        return 0xFF000000.toInt() or
            ((r * 255f).roundToInt().coerceIn(0, 255) shl 16) or
            ((g * 255f).roundToInt().coerceIn(0, 255) shl 8) or
            (b * 255f).roundToInt().coerceIn(0, 255)
    }

    /** Channel-wise ARGB decomposition helper (0xAARRGGBB). */
    fun red(argb: Int): Int = (argb ushr 16) and 0xFF
    fun green(argb: Int): Int = (argb ushr 8) and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF
    fun alpha(argb: Int): Int = (argb ushr 24) and 0xFF

    fun argb(r: Int, g: Int, b: Int, a: Int = 0xFF): Int =
        (a.coerceIn(0, 255) shl 24) or
            (r.coerceIn(0, 255) shl 16) or
            (g.coerceIn(0, 255) shl 8) or
            b.coerceIn(0, 255)

    // ---- stroke progress --------------------------------------------------

    /**
     * Normalized progress [0..1] of point [index] along a polyline measured by
     * cumulative arc length, so the color gradient tracks real stroke distance
     * instead of raw point index (dense point runs would otherwise compress the
     * color band). A degenerate stroke returns 1f for any index.
     */
    fun strokeProgress(points: List<PointF>, index: Int): Float {
        if (points.size <= 1) return 1f
        val i = index.coerceIn(0, points.size - 1)
        var total = 0f
        for (k in 1 until points.size) {
            total += distance(points[k - 1], points[k])
        }
        if (total <= 1e-4f) return if (i >= points.size - 1) 1f else 0f
        var acc = 0f
        for (k in 1..i) {
            acc += distance(points[k - 1], points[k])
        }
        return (acc / total).coerceIn(0f, 1f)
    }

    private fun distance(a: PointF, b: PointF): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        return sqrt(dx * dx + dy * dy)
    }

    // ---- color effects ----------------------------------------------------

    /** Deterministic hue phase (degrees) for a seed, always in [0, 360). */
    fun seedHueDeg(seed: Int): Float {
        var h = (seed % 360)
        if (h < 0) h += 360
        return h.toFloat()
    }

    /**
     * Rainbow: a full seamless 360° hue sweep along the stroke progress, fully
     * saturated and vibrant. The base color only contributes its value (when the
     * user picked a bright color the rainbow stays bright); the seed rotates the
     * starting hue so every stroke begins at a different place on the wheel.
     */
    fun rainbowColorAt(baseArgb: Int, progress: Float, seed: Int): Int {
        val p = progress.coerceIn(0f, 1f)
        val baseHsv = argbToHsv(baseArgb)
        val hue = normalizeHue(seedHueDeg(seed) + p * 360f)
        val v = max(baseHsv[2], 0.5f)
        return hsvToArgb(hue, 1f, v)
    }

    /**
     * Gradient: linear RGB blend from the base color to [toArgb] along the
     * stroke progress. Always in gamut (both endpoints are in gamut).
     */
    fun gradientColorAt(fromArgb: Int, toArgb: Int, progress: Float): Int {
        val t = progress.coerceIn(0f, 1f)
        val r = red(fromArgb) + (red(toArgb) - red(fromArgb)) * t
        val g = green(fromArgb) + (green(toArgb) - green(fromArgb)) * t
        val b = blue(fromArgb) + (blue(toArgb) - blue(fromArgb)) * t
        val a = alpha(fromArgb)
        return argb(r.roundToInt().coerceIn(0, 255), g.roundToInt().coerceIn(0, 255), b.roundToInt().coerceIn(0, 255), a)
    }

    /**
     * Shimmer / iridescent: a subtle hue wobble plus metallic sheen bands along
     * the stroke, both driven deterministically by the seed. The hue swings ±[wobbleDeg]
     * around the base hue and the value/saturation are lifted toward white on
     * 5 sheen bands, giving a metallic falloff without ever leaving the gamut.
     */
    fun shimmerColorAt(baseArgb: Int, progress: Float, seed: Int, wobbleDeg: Float = 14f, bands: Float = 5f): Int {
        val p = progress.coerceIn(0f, 1f)
        val base = argbToHsv(baseArgb)
        val phase = seedHueDeg(seed) * (Math.PI.toFloat() / 180f)
        val band = 0.5f + 0.5f * kotlin.math.sin(p * 2f * Math.PI.toFloat() * bands + phase)
        val hue = normalizeHue(base[0] + (band - 0.5f) * 2f * wobbleDeg)
        val s = min(1f, base[1] * (1f + 0.3f * band))
        val v = min(1f, base[2] * (1f + 0.35f * band))
        return hsvToArgb(hue, s, v)
    }

    /** Complementary hue (hue + 180°). Used as the default gradient endpoint. */
    fun complementaryArgb(argb: Int): Int {
        val hsv = argbToHsv(argb)
        return hsvToArgb(normalizeHue(hsv[0] + 180f), hsv[1], hsv[2])
    }

    /**
     * Render-time dispatcher: given a [mode], the stored base color, a [progress]
     * in [0..1] and the stroke seed, produce the derived color. [gradientToArgb]
     * is the stored second gradient color (or null -> deterministic complement).
     */
    fun colorForProgress(
        mode: StrokeColorMode,
        baseArgb: Int,
        progress: Float,
        seed: Int,
        gradientToArgb: Int? = null
    ): Int = when (mode) {
        StrokeColorMode.SOLID -> baseArgb
        StrokeColorMode.RAINBOW -> rainbowColorAt(baseArgb, progress, seed)
        StrokeColorMode.GRADIENT -> gradientColorAt(baseArgb, gradientToArgb ?: complementaryArgb(baseArgb), progress)
        StrokeColorMode.SHIMMER -> shimmerColorAt(baseArgb, progress, seed)
    }

    // ---- AGSL edge-feather mirror -----------------------------------------

    /** Standard GLSL/AGSL smoothstep (t*t*(3-2t) hermite). */
    fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        var t = (x - edge0) / (edge1 - edge0)
        t = t.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /**
     * Edge falloff for a radial brush. [normDist] is dist/radius (0 center, 1 edge).
     * The transition band is guaranteed to be at least [MIN_FEATHER_PX] pixels wide
     * (capped at half the radius) so hard brushes (high [hardness]) never alias into
     * a sub-pixel ring at small widths. Using `min(hardness, …)` preserves the exact
     * soft-brush behaviour (wide band when the hardness band is already wider than the
     * AA minimum) while forcing a real penumbra for near-hard brushes. This is the
     * exact mirror of the AGSL shader formula; keeping both in sync is enforced by tests.
     */
    fun edgeFeather(normDist: Float, hardness: Float, radiusPx: Float): Float {
        val h = hardness.coerceIn(0f, 1f)
        val r = radiusPx.coerceAtLeast(1f)
        val bandWidth = min(MIN_FEATHER_PX, r * 0.5f)
        val bandStart = min(h, 1f - bandWidth / r)
        return smoothstep(1.0f, bandStart, normDist)
    }
}
