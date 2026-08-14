package com.authorss81.noteflow.services

import kotlin.math.min

/**
 * Color family classification + a curated, organized palette catalog.
 *
 * Pure JVM so the palette logic (grouping by hue, dedup, gamut clamping) is
 * unit-testable without Android/Compose. The UI layer converts the resulting
 * ARGB ints into Compose [Color] swatches.
 */
enum class ColorFamily(val label: String) {
    REDS("Reds"),
    ORANGES("Oranges"),
    YELLOWS("Yellows"),
    GREENS("Greens"),
    BLUES("Blues"),
    PURPLES("Purples"),
    PINKS("Pinks"),
    BROWNS("Browns"),
    NEUTRALS("Neutrals / Ink");
}

object PaletteMath {

    data class Rgb(val r: Int, val g: Int, val b: Int) {
        /** CSS-style ARGB (alpha = FF). */
        val argb: Int
            get() = (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)
    }

    data class Hsv(val h: Float, val s: Float, val v: Float)

    fun newRgb(r: Int, g: Int, b: Int): Rgb =
        Rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

    fun toArgb(rgb: Rgb): Int = rgb.argb

    fun fromArgb(argb: Int): Rgb = Rgb(
        (argb ushr 16) and 0xFF,
        (argb ushr 8) and 0xFF,
        argb and 0xFF
    )

    fun gamutSafe(rgb: Rgb): Rgb =
        Rgb(rgb.r.coerceIn(0, 255), rgb.g.coerceIn(0, 255), rgb.b.coerceIn(0, 255))

    fun hsvOf(rgb: Rgb): Hsv {
        val rr = rgb.r / 255f
        val gg = rgb.g / 255f
        val bb = rgb.b / 255f
        val maxC = maxOf(rr, gg, bb)
        val minC = minOf(rr, gg, bb)
        val d = maxC - minC
        val v = maxC
        if (d < 1e-5f) return Hsv(0f, 0f, v)
        val s = d / maxC
        val h = when (maxC) {
            rr -> (((gg - bb) / d) + if (gg < bb) 6f else 0f) * 60f
            gg -> (((bb - rr) / d) + 2f) * 60f
            else -> (((rr - gg) / d) + 4f) * 60f
        }
        var hue = h % 360f
        if (hue < 0f) hue += 360f
        return Hsv(hue, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f))
    }

    /**
     * Assigns a color to one of the curated [ColorFamily] sections.
     * Neutrals (low saturation / near black / near white) and Browns (dark,
     * low-value warm hues) are recognized before the primary hue buckets.
     */
    fun familyFor(rgb: Rgb): ColorFamily {
        val hsv = hsvOf(rgb)
        val h = hsv.h
        val s = hsv.s
        val v = hsv.v
        if (s < 0.12f || v < 0.08f || (s < 0.3f && v > 0.95f)) return ColorFamily.NEUTRALS
        // Brown = low-value warm hues (red/orange/yellow family with dark value).
        if (v < 0.38f && (h < 62f || h >= 335f)) return ColorFamily.BROWNS
        return when {
            h < 10f || h >= 335f -> ColorFamily.REDS
            h < 42f -> ColorFamily.ORANGES
            h < 62f -> ColorFamily.YELLOWS
            h < 180f -> ColorFamily.GREENS
            h < 255f -> ColorFamily.BLUES
            h < 290f -> ColorFamily.PURPLES
            else -> ColorFamily.PINKS
        }
    }

    /** Removes duplicates (by rounded channel match); first occurrence wins, order preserved. */
    fun dedup(colors: List<Rgb>): List<Rgb> {
        val seen = mutableSetOf<Int>()
        val out = mutableListOf<Rgb>()
        for (c in colors) {
            val key = (c.r shl 16) or ((c.g and 0xFF) shl 8) or (c.b and 0xFF)
            if (seen.add(key)) out.add(gamutSafe(c))
        }
        return out
    }

    /** Groups an arbitrary color list into family sections, keeping input order within sections. */
    fun groupByFamily(colors: List<Rgb>): List<Pair<ColorFamily, List<Rgb>>> {
        val buckets = mutableMapOf<ColorFamily, MutableList<Rgb>>()
        for (c in colors) {
            val f = familyFor(c)
            buckets.getOrPut(f) { mutableListOf() }.add(gamutSafe(c))
        }
        return ColorFamily.entries.mapNotNull { f ->
            buckets[f]?.toList()?.let { f to it }
        }
    }

    /** Alpha-preserving preview of a color as a compact hex string (no '#' prefix). */
    fun hexString(rgb: Rgb): String {
        val hs = java.lang.String.format("%02X%02X%02X", rgb.r, rgb.g, rgb.b)
        return hs
    }
}

/**
 * A curated, organized palette of vibrant swatches, grouped into
 * [ColorFamily] sections — a far richer default than the old 13-color list.
 */
object PaletteCatalog {

    data class Swatch(val family: ColorFamily, val r: Int, val g: Int, val b: Int) {
        val rgb: PaletteMath.Rgb get() = PaletteMath.newRgb(r, g, b)
        val argb: Int get() = rgb.argb
    }

    private fun s(family: ColorFamily, hex: Int): Swatch = Swatch(
        family,
        r = (hex ushr 16) and 0xFF,
        g = (hex ushr 8) and 0xFF,
        b = hex and 0xFF
    )

    /** Curated vibrant swatches, one per family, ordered by family. */
    val curated: List<Swatch> = listOf(
        // Reds
        s(ColorFamily.REDS, 0xFF2E2E), s(ColorFamily.REDS, 0xEF4444),
        s(ColorFamily.REDS, 0xF87171), s(ColorFamily.REDS, 0xDC2626),
        s(ColorFamily.REDS, 0xB91C1C), s(ColorFamily.REDS, 0x991B1B),
        s(ColorFamily.REDS, 0xE11D48), s(ColorFamily.REDS, 0xBE123C),
        s(ColorFamily.REDS, 0xF43F5E),
        // Oranges
        s(ColorFamily.ORANGES, 0xF97316), s(ColorFamily.ORANGES, 0xFB923C),
        s(ColorFamily.ORANGES, 0xEA580C), s(ColorFamily.ORANGES, 0xFF7A00),
        s(ColorFamily.ORANGES, 0xFB8B24), s(ColorFamily.ORANGES, 0xFF8C42),
        s(ColorFamily.ORANGES, 0xF59E0B), s(ColorFamily.ORANGES, 0xD97706),
        s(ColorFamily.ORANGES, 0xB45309),
        // Yellows
        s(ColorFamily.YELLOWS, 0xFAFA33), s(ColorFamily.YELLOWS, 0xFFD60A),
        s(ColorFamily.YELLOWS, 0xFACC15), s(ColorFamily.YELLOWS, 0xEAB308),
        s(ColorFamily.YELLOWS, 0xCA8A04), s(ColorFamily.YELLOWS, 0xFDE047),
        s(ColorFamily.YELLOWS, 0xFFE135), s(ColorFamily.YELLOWS, 0xFFC400),
        s(ColorFamily.YELLOWS, 0xE5C100),
        // Greens
        s(ColorFamily.GREENS, 0x16A34A), s(ColorFamily.GREENS, 0x22C55E),
        s(ColorFamily.GREENS, 0x4ADE80), s(ColorFamily.GREENS, 0x15803D),
        s(ColorFamily.GREENS, 0x059669), s(ColorFamily.GREENS, 0x10B981),
        s(ColorFamily.GREENS, 0x34D399), s(ColorFamily.GREENS, 0x0D9488),
        s(ColorFamily.GREENS, 0x14B8A6),
        // Blues
        s(ColorFamily.BLUES, 0x2563EB), s(ColorFamily.BLUES, 0x3B82F6),
        s(ColorFamily.BLUES, 0x60A5FA), s(ColorFamily.BLUES, 0x1D4ED8),
        s(ColorFamily.BLUES, 0x1E3A8A), s(ColorFamily.BLUES, 0x0284C7),
        s(ColorFamily.BLUES, 0x38BDF8), s(ColorFamily.BLUES, 0x06B6D4),
        s(ColorFamily.BLUES, 0x4F46E5),
        // Purples
        s(ColorFamily.PURPLES, 0x7C3AED), s(ColorFamily.PURPLES, 0x8B5CF6),
        s(ColorFamily.PURPLES, 0xA78BFA), s(ColorFamily.PURPLES, 0x6D28D9),
        s(ColorFamily.PURPLES, 0x9333EA), s(ColorFamily.PURPLES, 0xA855F7),
        s(ColorFamily.PURPLES, 0xC084FC), s(ColorFamily.PURPLES, 0x7C3AED),
        s(ColorFamily.PURPLES, 0x6B21A8),
        // Pinks
        s(ColorFamily.PINKS, 0xEC4899), s(ColorFamily.PINKS, 0xF472B6),
        s(ColorFamily.PINKS, 0xDB2777), s(ColorFamily.PINKS, 0xBE185D),
        s(ColorFamily.PINKS, 0xF9A8D4), s(ColorFamily.PINKS, 0xFB7185),
        s(ColorFamily.PINKS, 0xF43F5E), s(ColorFamily.PINKS, 0xE863A4),
        s(ColorFamily.PINKS, 0xD85A9A),
        // Browns
        s(ColorFamily.BROWNS, 0x92400E), s(ColorFamily.BROWNS, 0x78350F),
        s(ColorFamily.BROWNS, 0x7C3A1E), s(ColorFamily.BROWNS, 0x8B4513),
        s(ColorFamily.BROWNS, 0xA0522D), s(ColorFamily.BROWNS, 0x6F4518),
        s(ColorFamily.BROWNS, 0x9A6B4F), s(ColorFamily.BROWNS, 0x6B4A3C),
        s(ColorFamily.BROWNS, 0x5D4037),
        // Neutrals / Ink
        s(ColorFamily.NEUTRALS, 0x111827), s(ColorFamily.NEUTRALS, 0x1E293B),
        s(ColorFamily.NEUTRALS, 0x0F172A), s(ColorFamily.NEUTRALS, 0x334155),
        s(ColorFamily.NEUTRALS, 0x475569), s(ColorFamily.NEUTRALS, 0x64748B),
        s(ColorFamily.NEUTRALS, 0x6B7280), s(ColorFamily.NEUTRALS, 0x4B5563),
        s(ColorFamily.NEUTRALS, 0x000000), s(ColorFamily.NEUTRALS, 0xFFFFFF)
    ).let { all ->
        // De-duplicate (in case two family rows accidentally share a color) and
        // drop the intentional PINK/ROSE crossover so every family is distinct.
        val seen = mutableSetOf<Int>()
        all.filter { sw -> seen.add(sw.argb) }
    }

    fun forFamily(family: ColorFamily): List<Swatch> = curated.filter { it.family == family }

    /** Default palette shown to brand-new users / the recent-colors row. */
    fun defaultColorInts(): List<Int> = curated.map { it.argb }
}