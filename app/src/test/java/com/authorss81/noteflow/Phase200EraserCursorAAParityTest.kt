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
    fun `penumbra band sits strictly inside the disk and owns all sampling segments`() {
        // The radial-gradient sampling holds an opaque plateau out to
        // cursorBandStartNd and spends ALL CURSOR_FEATHER_STOP_COUNT+1 curve
        // samples across [bandStart, 1]; bandStart must sit strictly inside
        // (0,1) so the gradient actually renders a penumbra rather than a hard
        // rim.
        for (r in listOf(6f, 8f, 16f, 32f, 64f, 96f)) {
            val bandStart = EraserGeometryPolicy.cursorBandStartNd(r)
            assertTrue("r=$r bandStart $bandStart not strictly inside (0.5,1)", bandStart >= 0.5f && bandStart < 1f)
        }
        assertEquals(12, EraserGeometryPolicy.CURSOR_FEATHER_STOP_COUNT)
    }

    @Test
    fun `cursorFeatherBand matches the band implied BY edgeFeather itself`() {
        // Review-fix (phase-200) drift guard: the policy band rule must stay in
        // lockstep with BrushColorModeMath.edgeFeather's INTERNAL band — derived
        // here from the curve itself (last nd whose falloff is still fully 1).
        val step = 0.00025f
        for (r in sampleRadii()) {
            var nd = 0f
            var lastFull = 0f
            while (nd <= 1f) {
                if (BrushColorModeMath.edgeFeather(nd, 1f, r) >= 1f) lastFull = nd
                nd += step
            }
            val impliedBandStart = lastFull + step
            // tolerance = 2 scan steps: the hermite is flat to within one float
            // ulp of 1.0 for ~1.4e-4 of nd past the knee, so curve-derived
            // detection cannot be sharper than ~step; genuine rule drift moves
            // the band by orders more.
            assertEquals(
                "policy band start diverged from edgeFeather at r=$r",
                EraserGeometryPolicy.cursorBandStartNd(r),
                impliedBandStart,
                step * 2f
            )
        }
    }

    /** The EXACT stop list AnnotationCanvas builds for the aim-cursor gradient. */
    private fun shippedStops(r: Float): List<Pair<Float, Float>> {
        val n = EraserGeometryPolicy.CURSOR_FEATHER_STOP_COUNT
        val bandStart = EraserGeometryPolicy.cursorBandStartNd(r)
        val stops = mutableListOf(0f to EraserGeometryPolicy.cursorFillAlphaAt(0f, r))
        for (i in 0..n) {
            val nd = bandStart + (1f - bandStart) * i / n
            stops.add(nd to EraserGeometryPolicy.cursorFillAlphaAt(nd, r))
        }
        return stops.sortedBy { it.first }
    }

    private fun interpolatedAlpha(stops: List<Pair<Float, Float>>, nd: Float): Float {
        if (nd <= stops.first().first) return stops.first().second
        for (i in 1 until stops.size) {
            val p1 = stops[i].first
            if (nd <= p1) {
                val p0 = stops[i - 1].first
                val a0 = stops[i - 1].second
                return if (p1 == p0) stops[i].second else a0 + (stops[i].second - a0) * ((nd - p0) / (p1 - p0))
            }
        }
        return stops.last().second
    }

    @Test
    fun `shipped gradient stop layout tracks the ink curve to sub-1 percent alpha`() {
        // Review-fix (phase-200): the pre-fix UNIFORM stop layout put the whole
        // hermite drop inside one stop interval on large radii (local error up
        // to ~75 alpha points). The shipped plateau + band-concentrated layout
        // must keep the piecewise-linear interpolation error under 1% of full
        // scale at every usable radius.
        for (r in sampleRadii()) {
            val stops = shippedStops(r)
            var nd = 0f
            var maxErr = 0f
            while (nd <= 1f) {
                val err = Math.abs(interpolatedAlpha(stops, nd) - EraserGeometryPolicy.cursorFillAlphaAt(nd, r))
                if (err > maxErr) maxErr = err
                nd += 0.001f
            }
            assertTrue("radius $r max interpolation error $maxErr >= 1%", maxErr < 0.01f)
        }
    }

    @Test
    fun `cursor constants keep the pre-200 aim look`() {
        assertEquals(0.22f, EraserGeometryPolicy.CURSOR_FILL_ALPHA, 0f)
        assertEquals(0.6f, EraserGeometryPolicy.CURSOR_RING_ALPHA, 0f)
        assertEquals(2f, EraserGeometryPolicy.CURSOR_RING_WIDTH_PX, 0f)
    }
}
