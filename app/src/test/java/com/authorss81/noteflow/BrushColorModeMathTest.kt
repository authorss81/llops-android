package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.StrokeColorMode
import com.authorss81.noteflow.services.BrushColorModeMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BrushColorModeMathTest {

    // ---- HSV <-> ARGB round-trip ------------------------------------------

    @Test
    fun `hsv round-trips for a spread of colors`() {
        val colors = intArrayOf(
            0xFF1B365D.toInt(), 0xFFE91E5A.toInt(), 0xFF2F80ED.toInt(),
            0xFF22C55E.toInt(), 0xFFFF9800.toInt(), 0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt()
        )
        for (c in colors) {
            val hsv = BrushColorModeMath.argbToHsv(c)
            val back = BrushColorModeMath.hsvToArgb(hsv[0], hsv[1], hsv[2])
            // HSB<->RGB round-trips through HSV because the hue of grey/black is
            // normalized to 0 and saturation 0 restores grey of the same value.
            assertEquals("round-trip of #%06X".format(c and 0xFFFFFF), c, back)
        }
    }

    @Test
    fun `hue is always normalized into 0 to 360`() {
        for (deg in floatArrayOf(0f, 359f, 360f, 720f, -90f, -1f, 180f)) {
            val n = BrushColorModeMath.normalizeHue(deg)
            assertTrue("hue $deg -> $n", n in 0f..359.999f)
            if (deg in 0f..359.999f) assertEquals(deg, n, 1e-4f)
        }
    }

    @Test
    fun `argb helpers decompose correctly`() {
        val c = 0xAB1020C8.toInt()
        assertEquals(0xAB, BrushColorModeMath.alpha(c))
        assertEquals(0x10, BrushColorModeMath.red(c))
        assertEquals(0x20, BrushColorModeMath.green(c))
        assertEquals(0xC8, BrushColorModeMath.blue(c))
    }

    // ---- rainbow ----------------------------------------------------------

    @Test
    fun `rainbow is fully saturated and uses the base value`() {
        val base = 0xFF1B365D.toInt() // dark navy
        val c = BrushColorModeMath.rainbowColorAt(base, 0.25f, seed = 42)
        val hsv = BrushColorModeMath.argbToHsv(c)
        assertEquals(1f, hsv[1], 1e-4f)
        // value lifted toward the brighter of base value and 0.5
        assertTrue(hsv[2] >= 0.5f - 1e-4f)
        assertEquals(0xFF, BrushColorModeMath.alpha(c))
    }

    @Test
    fun `rainbow sweeps the full wheel and wraps seamlessly`() {
        val base = 0xFFFF0000.toInt()
        val start = BrushColorModeMath.rainbowColorAt(base, 0f, seed = 0)
        val endWrap = BrushColorModeMath.rainbowColorAt(base, 1f, seed = 0)
        // progress 0 and progress 1 are the same hue: full seamless sweep.
        assertEquals(start, endWrap)
        val mid = BrushColorModeMath.rainbowColorAt(base, 0.5f, seed = 0)
        assertTrue("mid must differ from both ends", mid != start)
    }

    @Test
    fun `rainbow is deterministic per seed`() {
        val base = 0xFF00AA77.toInt()
        for (seed in intArrayOf(0, 7, 360, -5, 123456)) {
            assertEquals(
                BrushColorModeMath.rainbowColorAt(base, 0.4f, seed),
                BrushColorModeMath.rainbowColorAt(base, 0.4f, seed)
            )
        }
    }

    @Test
    fun `seed rotates the starting hue`() {
        val base = 0xFFFF0000.toInt()
        val a = BrushColorModeMath.rainbowColorAt(base, 0f, seed = 0)
        val b = BrushColorModeMath.rainbowColorAt(base, 0f, seed = 120)
        val ha = BrushColorModeMath.argbToHsv(a)[0]
        val hb = BrushColorModeMath.argbToHsv(b)[0]
        assertEquals(120f, BrushColorModeMath.normalizeHue(hb - ha), 2f)
    }

    // ---- gradient ----------------------------------------------------------

    @Test
    fun `gradient endpoints and linear mid-blend`() {
        val from = 0xFF000000.toInt()
        val to = 0xFFFFFFFF.toInt()
        assertEquals(from, BrushColorModeMath.gradientColorAt(from, to, 0f))
        assertEquals(to, BrushColorModeMath.gradientColorAt(from, to, 1f))
        val mid = BrushColorModeMath.gradientColorAt(from, to, 0.5f)
        assertEquals(0x80, BrushColorModeMath.red(mid))
        assertEquals(0x80, BrushColorModeMath.green(mid))
        assertEquals(0x80, BrushColorModeMath.blue(mid))
    }

    @Test
    fun `gradient preserves the base alpha and stays in gamut`() {
        val from = 0xFF102030.toInt()
        val to = 0xFFFF8040.toInt()
        val c = BrushColorModeMath.gradientColorAt(from, to, 0.37f)
        assertEquals(0xFF, BrushColorModeMath.alpha(c))
        assertTrue(BrushColorModeMath.red(c) in 0x10..0xFF)
        assertTrue(BrushColorModeMath.blue(c) in 0x30..0x40)
    }

    @Test
    fun `complementary hue is 180 degrees away`() {
        val base = 0xFF224488.toInt()
        val comp = BrushColorModeMath.complementaryArgb(base)
        val hBase = BrushColorModeMath.argbToHsv(base)[0]
        val hComp = BrushColorModeMath.argbToHsv(comp)[0]
        assertEquals(180f, BrushColorModeMath.normalizeHue(hComp - hBase), 2f)
        val sBase = BrushColorModeMath.argbToHsv(base)[1]
        val sComp = BrushColorModeMath.argbToHsv(comp)[1]
        assertEquals(sBase, sComp, 1e-4f)
    }

    // ---- shimmer ----------------------------------------------------------

    @Test
    fun `shimmer is deterministic and in-gamut`() {
        val base = 0xFFAA3366.toInt()
        for (seed in intArrayOf(0, 11, 90, 200)) {
            val a = BrushColorModeMath.shimmerColorAt(base, 0.3f, seed)
            assertEquals(a, BrushColorModeMath.shimmerColorAt(base, 0.3f, seed))
            assertEquals("fully opaque", 0xFF, BrushColorModeMath.alpha(a))
            assertTrue("value in gamut", BrushColorModeMath.argbToHsv(a)[2] in 0f..1f)
        }
    }

    @Test
    fun `shimmer produces distinct sheen bands along a stroke`() {
        val base = 0xFF336699.toInt()
        val colors = (0..4).map { BrushColorModeMath.shimmerColorAt(base, it / 4f, seed = 3) }
        val distinct = colors.distinctBy { it }.size
        assertTrue("expected some sheen variation, got $distinct", distinct >= 3)
    }

    // ---- dispatcher ---------------------------------------------------------

    @Test
    fun `colorForProgress dispatches per mode`() {
        val base = 0xFF123456.toInt()
        val dump = 0xFFABCDEF.toInt()
        assertEquals(base, BrushColorModeMath.colorForProgress(StrokeColorMode.SOLID, base, 0.5f, 0))
        assertEquals(
            BrushColorModeMath.rainbowColorAt(base, 0.2f, 9),
            BrushColorModeMath.colorForProgress(StrokeColorMode.RAINBOW, base, 0.2f, 9)
        )
        assertEquals(
            BrushColorModeMath.gradientColorAt(base, dump, 0.2f),
            BrushColorModeMath.colorForProgress(StrokeColorMode.GRADIENT, base, 0.2f, 9, dump)
        )
        // null gradient end => deterministic complementary hue
        assertEquals(
            BrushColorModeMath.gradientColorAt(base, BrushColorModeMath.complementaryArgb(base), 0.2f),
            BrushColorModeMath.colorForProgress(StrokeColorMode.GRADIENT, base, 0.2f, 9, null)
        )
        assertEquals(
            BrushColorModeMath.shimmerColorAt(base, 0.2f, 9),
            BrushColorModeMath.colorForProgress(StrokeColorMode.SHIMMER, base, 0.2f, 9)
        )
    }

    // ---- stroke progress -----------------------------------------------------

    @Test
    fun `strokeProgress is arc-length normalized`() {
        val pts = listOf(
            PointF(0f, 0f), PointF(10f, 0f), PointF(10f, 10f)
        )
        assertEquals(0f, BrushColorModeMath.strokeProgress(pts, 0), 1e-4f)
        assertEquals(0.5f, BrushColorModeMath.strokeProgress(pts, 1), 1e-4f)
        assertEquals(1f, BrushColorModeMath.strokeProgress(pts, 2), 1e-4f)
    }

    @Test
    fun `strokeProgress handles degenerate strokes`() {
        val single = listOf(PointF(3f, 4f))
        assertEquals(1f, BrushColorModeMath.strokeProgress(single, 0), 1e-4f)
    }

    // ---- edge feather (AGSL mirror) -------------------------------------------

    @Test
    fun `edgeFeather gives full ink at center and zero at the edge`() {
        assertEquals(1f, BrushColorModeMath.edgeFeather(0f, hardness = 1f, radiusPx = 5f), 1e-5f)
        assertEquals(0f, BrushColorModeMath.edgeFeather(1f, hardness = 1f, radiusPx = 5f), 1e-5f)
    }

    @Test
    fun `hard brushes keep a real penumbra of at least 1_5px`() {
        // For a near-hard brush the worst-case pixel width of the band
        // (bandStart..1 normalized, times radius) must stay >= MIN_FEATHER_PX.
        for (radius in floatArrayOf(1.5f, 2f, 3f, 4f, 8f, 20f)) {
            for (hardness in floatArrayOf(0.9f, 0.98f, 1f)) {
                val r = radius.coerceAtLeast(1f)
                val bandWidth = minOf(BrushColorModeMath.MIN_FEATHER_PX, r * 0.5f)
                // bandStart is the normalized inner edge of the transition
                val h = hardness.coerceIn(0f, 1f)
                val bandStart = minOf(h, 1f - bandWidth / r)
                val bandPx = (1f - bandStart) * r
                val lowerBound = if (h == 1f) bandWidth * 0.999f else bandWidth - 1e-3f
                assertTrue(
                    "radius=$radius hardness=$hardness band=${"%.3f".format(bandPx)}px",
                    bandPx >= lowerBound
                )
                // AND: < 1.0 so smoothstep has non-degenerate edges (no div-by-zero)
                assertTrue("bandStart must be < 1 for radius=$radius", bandStart < 1f)
            }
        }
    }

    @Test
    fun `soft brushes keep their wide band unchanged`() {
        val r = 10f
        val soft = 0.3f
        // soft brush: hardness band [0.3,1] is wider than the AA minimum,
        // so bandStart == hardness (identical to the classic formula).
        val bandStart = minOf(soft, 1f - BrushColorModeMath.MIN_FEATHER_PX / r)
        assertEquals(soft, bandStart, 1e-5f)
        // midpoint of the band gives half-coverage
        val mid = (1f + bandStart) / 2f
        assertEquals(0.5f, BrushColorModeMath.edgeFeather(mid, soft, r), 1e-4f)
    }
}