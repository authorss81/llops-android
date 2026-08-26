package com.authorss81.noteflow

import com.authorss81.noteflow.services.StabilizerFilter
import com.authorss81.noteflow.services.StrokeSmoothingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Phase 214 (Stroke Smoothing v2) — Task 3: velocity-adaptive alpha.
 *
 * The pre-214 EWMA used one static alpha per stroke (`2/(window+1)`): slow
 * deliberate writing was over-smoothed (lag mush), fast strokes were
 * under-smoothed (jitter). Now each sample's segment velocity lerps the alpha
 * across [ALPHA_SLOW .. ALPHA_FAST] = [0.12 .. 0.55] over 0..6 px/ms, clamped
 * into a band around the tuned base alpha so the user's per-brush/per-input
 * tuning still bounds the response.
 */
class VelocityAdaptiveAlphaTest {

    // ---- Goldens: default window spans the full spec band -----------------------

    @Test
    fun `golden slow writing damps at the floor`() {
        assertEquals(
            StrokeSmoothingPolicy.ALPHA_SLOW,
            StrokeSmoothingPolicy.adaptiveAlpha(8, 0f),
            1e-6f
        )
    }

    @Test
    fun `golden fast strokes respond at the ceiling`() {
        assertEquals(
            StrokeSmoothingPolicy.ALPHA_FAST,
            StrokeSmoothingPolicy.adaptiveAlpha(8, 6f),
            1e-6f
        )
        // Beyond the reference speed stays clamped at the ceiling.
        assertEquals(
            StrokeSmoothingPolicy.ALPHA_FAST,
            StrokeSmoothingPolicy.adaptiveAlpha(8, 60f),
            1e-6f
        )
    }

    @Test
    fun `golden midpoint is the linear lerp`() {
        assertEquals(
            0.335f,
            StrokeSmoothingPolicy.adaptiveAlpha(8, 3f),
            1e-4f
        )
    }

    @Test
    fun `alpha rises monotonically with speed`() {
        var prev = -1f
        var v = 0f
        while (v <= 10f) {
            val a = StrokeSmoothingPolicy.adaptiveAlpha(8, v)
            assertTrue("alpha must never fall as speed rises (v=$v)", a >= prev)
            prev = a
            v += 0.25f
        }
    }

    // ---- Extreme windows keep their brush character ------------------------------

    @Test
    fun `raw-feeling window never damps below its character at rest`() {
        // Window 2 => base alpha 2/3; slow-end clamp = max(0.12, base*0.5) = 1/3.
        assertEquals(
            1f / 3f,
            StrokeSmoothingPolicy.adaptiveAlpha(2, 0f),
            1e-6f
        )
    }

    @Test
    fun `max-smoothing window never jumps past its designed responsiveness`() {
        // Window 12 => base 2/13; fast-end clamp = min(0.55, base*2.5).
        assertEquals(
            (2f / 13f) * 2.5f,
            StrokeSmoothingPolicy.adaptiveAlpha(12, 9f),
            1e-6f
        )
        assertTrue(StrokeSmoothingPolicy.adaptiveAlpha(12, 9f) < StrokeSmoothingPolicy.ALPHA_FAST)
    }

    @Test
    fun `degenerate inputs fall back to the static base alpha`() {
        for (w in 2..12) {
            val base = 2f / (w + 1f)
            assertEquals(base, StrokeSmoothingPolicy.adaptiveAlpha(w, null), 0f)
            assertEquals(base, StrokeSmoothingPolicy.adaptiveAlpha(w, Float.NaN), 0f)
        }
        // Sub-minimum windows clamp before deriving.
        assertEquals(2f / 3f, StrokeSmoothingPolicy.adaptiveAlpha(0, null), 1e-6f)
    }

    // ---- Behaviour through the real filter ----------------------------------------

    @Test
    fun `same step settles faster when written quickly than slowly`() {
        // Two identical filters; identical geometry, different reported speed.
        val pred = com.authorss81.noteflow.services.StrokeStabilizer.DEFAULT_PREDICTION
        val slow = StabilizerFilter(8, pred)
        val fast = StabilizerFilter(8, pred)

        fun settle(filter: StabilizerFilter, velocityPxPerMs: Float?): Int {
            filter.reset()
            filter.next(0f, 0f, 0.5f, null, null, null)
            var s = filter.next(0f, 0f, 0.5f, null, null, null)
            var samples = 0
            while (abs(s.x - 100f) > 5f) {
                s = filter.next(100f, 0f, 0.5f, null, velocityPxPerMs, 1_000L + samples * 16L)
                samples++
                if (samples > 200) break
            }
            return samples
        }

        val slowSamples = settle(slow, 0.2f)
        val fastSamples = settle(fast, 5f)
        assertTrue(
            "fast writing must converge visibly sooner ($fastSamples vs $slowSamples)",
            fastSamples < slowSamples * 3 / 4
        )
    }

    @Test
    fun `null velocity reproduces the static-alpha output bit-for-bit`() {
        val legacy = StabilizerFilter(8, com.authorss81.noteflow.services.StrokeStabilizer.DEFAULT_PREDICTION)
        val adapted = StabilizerFilter(8, com.authorss81.noteflow.services.StrokeStabilizer.DEFAULT_PREDICTION)
        val random = java.util.Random(3L)
        repeat(100) { i ->
            val x = i.toFloat() + (random.nextFloat() - 0.5f)
            val y = 50f + (random.nextFloat() - 0.5f)
            val viaLegacy = legacy.next(x, y)
            val viaAdapted = adapted.next(x, y, 0.42f, 12f, null, null)
            assertEquals(viaLegacy.x, viaAdapted.x, 0f)
            assertEquals(viaLegacy.y, viaAdapted.y, 0f)
        }
    }
}
