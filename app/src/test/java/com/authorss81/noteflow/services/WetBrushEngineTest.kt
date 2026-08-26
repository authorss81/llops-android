package com.authorss81.noteflow.services

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 212: [WetBrushEngine] parameter-boundary tests — the tier-selection
 * math (thermal + sustained-GPU degradation ladder) and the point-throttle /
 * interpolation / dirty-rect helpers. Render output itself stays visual/manual.
 *
 * The degradation ladder: quality 1.0 → 0.5 → 0.35 → vector fallback, one step
 * per SUSTAINED second of EMA frame time above 20 ms.
 */
class WetBrushEngineTest {

    // ---- tier selection -------------------------------------------------------

    @Test
    fun `a fresh engine renders at full quality on the GPU path`() {
        val engine = WetBrushEngine()

        assertEquals(1.0f, engine.currentQuality, 0f)
        assertFalse(engine.useVectorFallback)
    }

    @Test
    fun `unsupported AGSL forces the vector fallback regardless of overrides`() {
        val engine = WetBrushEngine()

        engine.updateTierAndFallback(
            isAgslSupported = false,
            thermalStatus = 0,
            manualOverrideEnabled = true,
            currentTimeMs = 0L
        )

        assertTrue(engine.useVectorFallback)
        assertEquals(0.0f, engine.currentQuality, 0f)
    }

    @Test
    fun `manual override off forces the vector fallback`() {
        val engine = WetBrushEngine()

        engine.updateTierAndFallback(true, 0, manualOverrideEnabled = false, 0L)

        assertTrue(engine.useVectorFallback)
        assertEquals(0.0f, engine.currentQuality, 0f)
    }

    @Test
    fun `severe or critical thermal status degrades to 0_35 without falling back`() {
        for (status in listOf(3, 4)) {
            val engine = WetBrushEngine()
            engine.updateTierAndFallback(true, status, true, 0L)

            assertEquals("status $status", 0.35f, engine.currentQuality, 0f)
            assertFalse("status $status", engine.useVectorFallback)
        }
    }

    @Test
    fun `moderate thermal throttling alone does not degrade`() {
        val engine = WetBrushEngine() // EMA starts at the healthy 16.6

        engine.updateTierAndFallback(true, 2, true, 100L)

        assertEquals(1.0f, engine.currentQuality, 0f)
        assertFalse(engine.useVectorFallback)
    }

    @Test
    fun `one heavy frame does not degrade (EMA smooths spikes)`() {
        val engine = WetBrushEngine()
        repeat(2) { engine.recordFrameTime(33f) } // EMA ≈ 19.6 — still below 20

        engine.updateTierAndFallback(true, 0, true, 1000L)

        assertEquals(1.0f, engine.currentQuality, 0f)
        assertFalse(engine.useVectorFallback)
    }

    @Test
    fun `sustained slow frames step down the ladder once per second`() {
        val engine = WetBrushEngine()
        repeat(5) { engine.recordFrameTime(40f) } // EMA > 20
        val t0 = 10_000L

        // First observation arms the timer only.
        engine.updateTierAndFallback(true, 0, true, t0)
        assertEquals(1.0f, engine.currentQuality, 0f)
        // 999ms is not "sustained":
        engine.updateTierAndFallback(true, 0, true, t0 + 999L)
        assertEquals(1.0f, engine.currentQuality, 0f)
        // At exactly 1s: first auto-degrade step.
        engine.updateTierAndFallback(true, 0, true, t0 + 1_000L)
        assertEquals(0.5f, engine.currentQuality, 0f)
        assertFalse(engine.useVectorFallback)
        // Another full second at the same level: next step.
        engine.updateTierAndFallback(true, 0, true, t0 + 2_000L)
        assertEquals(0.35f, engine.currentQuality, 0f)
        // And a third: vector fallback.
        engine.updateTierAndFallback(true, 0, true, t0 + 3_000L)
        assertTrue(engine.useVectorFallback)
    }

    @Test
    fun `recovered frame times restore full quality and reset the timer`() {
        val engine = WetBrushEngine()
        repeat(5) { engine.recordFrameTime(40f) }
        engine.updateTierAndFallback(true, 0, true, 1_000L)
        engine.updateTierAndFallback(true, 0, true, 2_000L)
        assertEquals(0.5f, engine.currentQuality, 0f)

        repeat(25) { engine.recordFrameTime(16.6f) } // EMA recovers below 20
        engine.updateTierAndFallback(true, 0, true, 3_000L)

        assertEquals(1.0f, engine.currentQuality, 0f)
        assertFalse(engine.useVectorFallback)
    }

    @Test
    fun `frame EMA follows the 20-frame exponential average`() {
        val engine = WetBrushEngine()
        val alpha = 2f / 21f

        engine.recordFrameTime(26.4f)

        assertEquals(alpha * 26.4f + (1 - alpha) * 16.6f, engine.getFrameTimeEmaMs(), 1e-4f)
    }

    // ---- point throttle ---------------------------------------------------------

    @Test
    fun `the very first point always processes`() {
        val engine = WetBrushEngine()

        assertTrue(engine.shouldProcessPoint(null, Offset(0f, 0f), 0L, 0L))
    }

    @Test
    fun `exactly 6px of movement processes but just under does not`() {
        val engine = WetBrushEngine()

        assertTrue(engine.shouldProcessPoint(Offset(0f, 0f), Offset(6f, 0f), 0L, 1L))
        assertFalse(engine.shouldProcessPoint(Offset(0f, 0f), Offset(5.9f, 0f), 0L, 1L))
        // Euclidean distance, not axis-aligned:
        assertTrue(engine.shouldProcessPoint(Offset(0f, 0f), Offset(4f, 4.5f), 0L, 1L))
    }

    @Test
    fun `exactly 16ms elapsed processes while just under needs the distance`() {
        val engine = WetBrushEngine()

        assertTrue(engine.shouldProcessPoint(Offset(0f, 0f), Offset(1f, 0f), 0L, 16L))
        assertFalse(engine.shouldProcessPoint(Offset(0f, 0f), Offset(1f, 0f), 0L, 15L))
    }

    // ---- interpolation ------------------------------------------------------------

    @Test
    fun `zero-length segments collapse to the current point`() {
        val engine = WetBrushEngine()

        assertEquals(listOf(Offset(3f, 4f)), engine.interpolateSegment(Offset(3f, 4f), Offset(3f, 4f), 2f))
    }

    @Test
    fun `short segments emit exactly the endpoint`() {
        val engine = WetBrushEngine()

        val pts = engine.interpolateSegment(Offset(0f, 0f), Offset(2f, 0f), radius = 5f)

        assertEquals(listOf(Offset(2f, 0f)), pts)
    }

    @Test
    fun `long segments are capped at three sub-steps ending on the target`() {
        val engine = WetBrushEngine()

        val pts = engine.interpolateSegment(Offset(0f, 0f), Offset(30f, 0f), radius = 1f)

        assertEquals(3, pts.size)
        assertEquals(10f, pts[0].x, 0.001f)
        assertEquals(20f, pts[1].x, 0.001f)
        assertEquals(30f, pts[2].x, 0f)
        assertEquals(0f, pts[2].y, 0f)
    }

    @Test
    fun `tiny radii are clamped so steps stay finite`() {
        val engine = WetBrushEngine()

        val pts = engine.interpolateSegment(Offset(0f, 0f), Offset(3f, 0f), radius = 0f)

        assertEquals(3, pts.size) // ceil(3/1)=3 without clamping runaway
    }
}
