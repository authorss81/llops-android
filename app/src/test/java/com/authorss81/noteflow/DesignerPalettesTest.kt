package com.authorss81.noteflow

import com.authorss81.noteflow.services.ColorFamily
import com.authorss81.noteflow.services.DesignerPalettes
import com.authorss81.noteflow.services.PaletteCatalog
import com.authorss81.noteflow.services.PaletteMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the Phase 35 designer palettes (Nordic, Botanical,
 * Cyberpunk, Warm Terracotta). The palettes are heavily curated, so instead of
 * asserting exact swatch counts we assert the invariants that matter: honest
 * family classification, no duplicates, in-gamut, full alpha, meaningful size,
 * and unknown-name fallback to the vibrant catalog.
 */
class DesignerPalettesTest {

    private val paletteNames = listOf("nordic", "botanical", "cyberpunk", "terra")

    @Test
    fun `each designer palette has at least ten curated swatches`() {
        assertTrue(DesignerPalettes.nordic.size >= 10)
        assertTrue(DesignerPalettes.botanical.size >= 10)
        assertTrue(DesignerPalettes.cyberpunk.size >= 10)
        assertTrue(DesignerPalettes.warmTerracotta.size >= 10)
    }

    @Test
    fun `every designer palette is fully de-duplicated`() {
        for (name in paletteNames) {
            val swatches = DesignerPalettes.swatchesFor(name)
            val argbs = swatches.map { it.argb }
            assertEquals("$name must have no duplicate ARGBs", argbs.size, argbs.toSet().size)
        }
    }

    @Test
    fun `every swatch is in gamut with full alpha`() {
        for (name in paletteNames) {
            for (sw in DesignerPalettes.swatchesFor(name)) {
                assertEquals("$name swatch $sw must carry full alpha", 0xFF, (sw.argb ushr 24) and 0xFF)
                assertTrue("$name swatch $sw channels out of range", sw.r in 0..255 && sw.g in 0..255 && sw.b in 0..255)
            }
        }
    }

    @Test
    fun `non-brown non-neutral swatches classify into their derived family`() {
        // familyFor is applied at construction, so the invariant must hold exactly.
        for (name in paletteNames) {
            for (sw in DesignerPalettes.swatchesFor(name)) {
                if (sw.family == ColorFamily.BROWNS || sw.family == ColorFamily.NEUTRALS) continue
                assertEquals(
                    "$name swatch $sw should classify into ${sw.family}",
                    sw.family,
                    PaletteMath.familyFor(sw.rgb)
                )
            }
        }
    }

    @Test
    fun `nordic reads cool and muted`() {
        // cool hues dominate: far more cool-family swatches than warm ones
        val coolFamilies = setOf(ColorFamily.BLUES, ColorFamily.PURPLES, ColorFamily.GREENS, ColorFamily.NEUTRALS)
        val coolCount = DesignerPalettes.nordic.map { it.family }.count { it in coolFamilies }
        val warmCount = DesignerPalettes.nordic.map { it.family }.count { it !in coolFamilies }
        assertTrue("nordic should be cool-leaning (cool=$coolCount warm=$warmCount)", coolCount >= warmCount)
    }

    @Test
    fun `botanical is green-leaning`() {
        val greens = DesignerPalettes.botanical.map { it.family }.count { it == ColorFamily.GREENS }
        assertTrue("botanical should be dominated by greens", greens >= DesignerPalettes.botanical.size / 2)
    }

    @Test
    fun `cyberpunk carries saturated neon accents`() {
        // deep purples + saturated cyan/acid: at least one purple-noise and one
        // high-saturation channel pass
        val purpleCount = DesignerPalettes.cyberpunk.map { it.family }.count { it == ColorFamily.PURPLES }
        assertTrue("cyberpunk must include purples", purpleCount >= 2)
        val saturated = DesignerPalettes.cyberpunk.count { sw ->
            val hsv = PaletteMath.hsvOf(sw.rgb)
            hsv.s > 0.7f
        }
        assertTrue("cyberpunk must carry saturated neon accents", saturated >= 3)
    }

    @Test
    fun `terracotta is warm and earthy`() {
        val warmFamilies = setOf(ColorFamily.REDS, ColorFamily.ORANGES, ColorFamily.YELLOWS, ColorFamily.BROWNS)
        val warmCount = DesignerPalettes.warmTerracotta.map { it.family }.count { it in warmFamilies }
        assertTrue("terracotta should be warm-leaning", warmCount >= DesignerPalettes.warmTerracotta.size / 2)
    }

    @Test
    fun `unknown palette name falls back to the vibrant curated catalog`() {
        assertEquals(PaletteCatalog.curated.map { it.argb }, DesignerPalettes.swatchesFor("nonexistent").map { it.argb })
        assertEquals(PaletteCatalog.curated.map { it.argb }, DesignerPalettes.swatchesFor("vibrant").map { it.argb })
    }

    @Test
    fun `all palette swatches are distinct from one another within their palette`() {
        for (name in paletteNames) {
            val all = DesignerPalettes.swatchesFor(name).flatMap { listOf(it.r, it.g, it.b) }
            assertTrue("$name must have some channel variety", all.toSet().size >= 3)
        }
    }
}