package com.authorss81.noteflow.services

/**
 * Render-time saturation ("vibrancy") boost math.
 *
 * Pure JVM so it is unit-testable without Android — converts an RGB color to
 * HSV, lifts only the saturation toward fully-saturated, then converts back.
 * Hue and value are untouched, so the result is always in gamut and the color's
 * identity (hue) is preserved. Boost amount 0 is the identity transform.
 *
 * This is a RENDER-TIME effect only: callers must never write the boosted value
 * back into a stored [Stroke]/[PaletteItemEntity].colorInt.
 */
object ColorVibrancy {

    data class Rgb(val r: Float, val g: Float, val b: Float)
    data class Hsv(val h: Float, val s: Float, val v: Float)

    /** Converts a CSS-style ARGB integer (0xAARRGGBB) to HSV. */
    fun rgbToHsv(r: Float, g: Float, b: Float): Hsv {
        val rr = (r / 255f).coerceIn(0f, 1f)
        val gg = (g / 255f).coerceIn(0f, 1f)
        val bb = (b / 255f).coerceIn(0f, 1f)
        val maxC = maxOf(rr, gg, bb)
        val minC = minOf(rr, gg, bb)
        val d = maxC - minC
        val v = maxC
        if (d < 1e-5f) {
            return Hsv(0f, 0f, v)
        }
        val s = d / maxC
        val h = when (maxC) {
            rr -> (((gg - bb) / d) + if (gg < bb) 6f else 0f) * 60f
            gg -> (((bb - rr) / d) + 2f) * 60f
            else -> (((rr - gg) / d) + 4f) * 60f
        }
        return Hsv(normalizeHue(h), s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
    }

    fun hsvToRgb(h: Float, s: Float, v: Float): Rgb {
        val hue = normalizeHue(h)
        val ss = s.coerceIn(0f, 1f)
        val vv = v.coerceIn(0f, 1f)
        if (ss < 1e-5f) {
            val g = (vv * 255f).coerceIn(0f, 255f)
            return Rgb(g, g, g)
        }
        val hh = (hue / 60f)
        val sector = kotlin.math.floor(hh).toInt() % 6
        val f = hh - kotlin.math.floor(hh)
        val p = vv * (1f - ss)
        val q = vv * (1f - ss * f)
        val t = vv * (1f - ss * (1f - f))
        val (cr, cg, cb) = when (sector) {
            0 -> Triple(vv, t, p)
            1 -> Triple(q, vv, p)
            2 -> Triple(p, vv, t)
            3 -> Triple(p, q, vv)
            4 -> Triple(t, p, vv)
            else -> Triple(vv, p, q)
        }
        return Rgb(
            (cr * 255f).coerceIn(0f, 255f),
            (cg * 255f).coerceIn(0f, 255f),
            (cb * 255f).coerceIn(0f, 255f)
        )
    }

    /** Gradient of saturation toward 1.0; amount 0 is identity, 1 is fully saturated. */
    fun saturationFor(s: Float, amount: Float): Float {
        val a = amount.coerceIn(0f, 1f)
        return (s + (1f - s) * a).coerceIn(0f, 1f)
    }

    fun boostRgb(r: Float, g: Float, b: Float, amount: Float): Rgb {
        if (amount <= 0f) return Rgb(r.coerceIn(0f, 255f), g.coerceIn(0f, 255f), b.coerceIn(0f, 255f))
        val hsv = rgbToHsv(r, g, b)
        if (hsv.s < 1e-5f) {
            // Achromatic colors have no hue to enrich — boosting a grey must not
            // tint it (with s -> 1 and an arbitrary hue 0 it would turn red).
            return Rgb(r.coerceIn(0f, 255f), g.coerceIn(0f, 255f), b.coerceIn(0f, 255f))
        }
        return hsvToRgb(hsv.h, saturationFor(hsv.s, amount), hsv.v)
    }

    /**
     * Boosts a CSS-style ARGB integer (0xAARRGGBB) in place, preserving alpha.
     * Pure bit-math so it runs on the JVM for tests and in the vector render path.
     */
    fun boostColorInt(argb: Int, amount: Float): Int {
        if (amount <= 0f) return argb
        val a = (argb ushr 24) and 0xFF
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val boosted = boostRgb(r.toFloat(), g.toFloat(), b.toFloat(), amount)
        return (a shl 24) or
            (boosted.r.toInt().coerceIn(0, 255) shl 16) or
            (boosted.g.toInt().coerceIn(0, 255) shl 8) or
            (boosted.b.toInt().coerceIn(0, 255))
    }

    fun normalizeHue(h: Float): Float {
        var hh = h % 360f
        if (hh < 0f) hh += 360f
        return hh
    }
}