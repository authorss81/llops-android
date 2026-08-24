package com.authorss81.noteflow

import com.authorss81.noteflow.services.BrushColorModeMath
import com.authorss81.noteflow.services.EraserGeometryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 200 (PERF 3.5): the PARTIAL eraser aim cursor must draw its soft edge
 * with the SAME guaranteed-AA falloff the ink path uses
 * ([BrushColorModeMath.edgeFeather] at hardness 1.0 — the exact mirror of the
 * AGSL wet shader's feather band), instead of a hard-edged flat fill.
 */
class Phase200EraserCursorAAParityTest {

    private fun sampleRadii(): List<Float> =
        listOf(1f, 2f, 4f, 8f, EraserGeometryPolicy.MIN_ERASE_WIDTH_PX, 16f, 24f, 48f, 96f, 200f)

    @Test
    fun `feather band is at least MIN_FEATHER_PX wide for usable radii`() {
        for (r in sampleRadii()) {
            val band = EraserGeometryPolicy.cursorFeatherBand(r)
            if (r >= BrushColorModeMath.MIN_FEATHER_PX * 2f) {
                assertTrue("radius $r band $band too narrow", band >= BrushColorModeMath.MIN_FEATHER_PX - 1e-6f)
            }
        }
    }

    @Test
    fun `feather band never exceeds half the radius (tiny disks keep a real penumbra)`() {
        for (r in sampleRadii()) {
            assertTrue(
                "radius $r band exceeds half-radius",
                EraserGeometryPolicy.cursorFeatherBand(r) <= r.coerceAtLeast(1f) * 0.5f + 1e-6f
            )
        }
        // degenerate inputs fail safe — mirroring edgeFeather, the radius is
        // floored to 1px BEFORE halving, so a 0px disk still gets a half-pixel
        // band (never a zero-width hard rim)
        assertEquals(0.5f, EraserGeometryPolicy.cursorFeatherBand(0f), 1e-6f)
        assertEquals(BrushColorModeMath.MIN_FEATHER_PX, EraserGeometryPolicy.cursorFeatherBand(6f), 1e-6f)
    }

    @Test
    fun `cursor falloff is EXACTLY edgeFeather at hardness 1`() {
        var nd = 0f
        while (nd <= 1f) {
            for (r in sampleRadii()) {
                assertEquals(
                    "nd=$nd r=$r diverged from ink falloff",
                    BrushColorModeMath.edgeFeather(nd, 1f, r),
                    EraserGeometryPolicy.cursorFillAlphaAt(nd, r),
                    0f
                )
            }
            nd += 0.05f
        }
    }

    @Test
    fun `cursor disk is fully opaque inside and invisible at the rim`() {
        for (r in sampleRadii()) {
            assertEquals(1f, EraserGeometryPolicy.cursorFillAlphaAt(0f, r), 1e-6f)
            assertEquals(0f, EraserGeometryPolicy.cursorFillAlphaAt(1f, r), 1e-6f)
        }
    }

    @Test
    fun `cursor falloff is monotonic non-increasing`() {
        for (r in sampleRadii()) {
            var prev = 1f
            var nd = 0f
            while (nd <= 1f) {
                val a = EraserGeometryPolicy.cursorFillAlphaAt(nd, r)
                assertTrue("alpha rose at nd=$nd r=$r", a <= prev + 1e-6f)
                prev = a
                nd += 0.02f
            }
        }
    }

    @Test
    fun `penumbra reaches the rim from inside the last stop`() {
        // The radial-gradient sampling uses CURSOR_FEATHER_STOP_COUNT+1 stops;
        // the band start must sit strictly INSIDE (0,1) so the gradient actually
        // renders a penumbra rather than a hard rim.
        for (r in listOf(8f, 16f, 32f, 64f)) {
            val bandStart = 1f - EraserGeometryPolicy.cursorFeatherBand(r) / r
            assertTrue(bandStart > 0f && bandStart < 1f)
        }
        assertEquals(12, EraserGeometryPolicy.CURSOR_FEATHER_STOP_COUNT)
    }

    @Test
    fun `cursor constants keep the pre-200 aim look`() {
        assertEquals(0.22f, EraserGeometryPolicy.CURSOR_FILL_ALPHA, 0f)
        assertEquals(0.6f, EraserGeometryPolicy.CURSOR_RING_ALPHA, 0f)
        assertEquals(2f, EraserGeometryPolicy.CURSOR_RING_WIDTH_PX, 0f)
    }
}
