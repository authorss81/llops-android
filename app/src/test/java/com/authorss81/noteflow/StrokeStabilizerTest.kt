package com.authorss81.noteflow

import com.authorss81.noteflow.services.StabilizerPoint
import com.authorss81.noteflow.services.StrokeSmoothingPolicy
import com.authorss81.noteflow.services.StrokeStabilizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class StrokeStabilizerTest {

    /** Builds [n] samples of the line y = slope*x + intercept with deterministic noise. */
    private fun noisyLine(
        n: Int,
        slope: Float = 2f,
        intercept: Float = 50f,
        seed: Long = 42L
    ): List<StabilizerPoint> {
        val random = java.util.Random(seed)
        return (0 until n).map { i ->
            val x = i.toFloat()
            StabilizerPoint(
                x = x + (random.nextFloat() - 0.5f) * 12f,
                y = slope * x + intercept + (random.nextFloat() - 0.5f) * 12f
            )
        }
    }

    private fun residualVariance(
        points: List<StabilizerPoint>,
        slope: Float,
        intercept: Float
    ): Double {
        val residuals = points.map { p -> p.y - (slope * p.x + intercept) }
        val mean = residuals.sum() / residuals.size
        return residuals.fold(0.0) { acc, r -> acc + (r - mean) * (r - mean) } / residuals.size
    }

    /** Root-mean-square perpendicular distance to the intended line. */
    private fun perpendicularRms(
        points: List<StabilizerPoint>,
        slope: Float,
        intercept: Float
    ): Double {
        val denom = sqrt(slope * slope + 1.0)
        val distances = points.map { p -> abs(p.y - (slope * p.x + intercept)) / denom }
        val squaredSum = distances.sumOf { it * it }
        return sqrt(squaredSum / distances.size)
    }

    @Test
    fun `stabilized path is closer to the intended line than the noisy input`() {
        val raw = noisyLine(n = 300)
        val stabilized = StrokeStabilizer.smooth(raw)

        val rawVar = residualVariance(raw, 2f, 50f)
        val outVar = residualVariance(stabilized, 2f, 50f)
        assertTrue("input must actually be noisy (rawVar=$rawVar)", rawVar > 10.0)
        assertTrue("stabilizer must reduce jitter near the line: $outVar >= $rawVar", outVar < rawVar)

        val rawRms = perpendicularRms(raw, 2f, 50f)
        val outRms = perpendicularRms(stabilized, 2f, 50f)
        assertTrue("root-mean-square distance must shrink, got $outRms vs $rawRms", outRms < rawRms)
        // The output must also stay a tight fit, not a wild overshoot.
        assertTrue("stabilized output drifts too far from the line: $outRms", outRms < 3.0)
    }

    @Test
    fun `warm-up point passes through unchanged`() {
        val stabilizer = StrokeStabilizer.create()
        val first = stabilizer.next(10f, 20f)
        assertEquals(10f, first.x, 1e-5f)
        assertEquals(20f, first.y, 1e-5f)
    }

    @Test
    fun `reset discards stale window state`() {
        val stabilizer = StrokeStabilizer.create()
        // Prime with an off-path point, then reset and confirm the next stroke
        // warms up fresh (first point passes through).
        stabilizer.next(-500f, -500f)
        stabilizer.next(-400f, -400f)
        stabilizer.reset()
        val fresh = stabilizer.next(3f, 4f)
        assertEquals(3f, fresh.x, 1e-5f)
        assertEquals(4f, fresh.y, 1e-5f)
    }

    @Test
    fun `constant sequence converges exactly`() {
        val stabilizer = StrokeStabilizer.create()
        var p = stabilizer.next(7f, 9f)
        repeat(200) {
            p = stabilizer.next(7f, 9f)
        }
        assertEquals(7f, p.x, 1e-3f)
        assertEquals(9f, p.y, 1e-3f)
    }

    @Test
    fun `length-preserving and bounded`() {
        val raw = noisyLine(n = 50)
        val out = StrokeStabilizer.smooth(raw)
        assertEquals(raw.size, out.size)
        assertTrue(out.all { it.x.isFinite() && it.y.isFinite() })
        // The damped output stays within the input's coordinate span (plus a small
        // margin for the lag-compensation overshoot).
        val inMinY = raw.minOf { it.y }
        val inMaxY = raw.maxOf { it.y }
        val span = inMaxY - inMinY
        assertTrue("output y must stay near input bounds", out.all { it.y >= inMinY - span * 0.25f && it.y <= inMaxY + span * 0.25f })
        val inMinX = raw.minOf { it.x }
        val inMaxX = raw.maxOf { it.x }
        assertTrue("output x must stay near input bounds", out.all { it.x >= inMinX - 2f && it.x <= inMaxX + 2f })
    }

    // ---- Phase 197 goldens: explicit window sizes 2 / 8 / 12 -------------------
    //
    // These pin the three tuning corners the smoothing policy can produce:
    // window 2 = raw-feeling (finger-off / slider 0%), window 8 = legacy default,
    // window 12 = maximum smoothing (slider 100% + smoothing 1.0).

    /** Samples needed until a unit step settles within [tolerance]. */
    private fun stepSettleSamples(windowSize: Int, tolerance: Float = 5f, cap: Int = 60): Int {
        val stabilizer = StrokeStabilizer.create(
            windowSize = windowSize,
            prediction = StrokeStabilizer.DEFAULT_PREDICTION
        )
        stabilizer.next(0f, 0f)
        var p = stabilizer.next(0f, 0f)
        var samples = 0
        while (abs(p.x - 100f) > tolerance) {
            p = stabilizer.next(100f, 0f)
            samples++
            if (samples > cap) return cap + 1
        }
        return samples
    }

    @Test
    fun `golden window 2 tracks a step almost immediately`() {
        val settle = stepSettleSamples(windowSize = 2)
        assertTrue("window 2 must settle within 6 samples, took $settle", settle <= 6)
    }

    @Test
    fun `golden window 8 matches the legacy default exactly`() {
        // Same seed → same input; the parameterless create() must remain
        // byte-identical to the explicit legacy tuning (pre-197 parity).
        val raw = noisyLine(n = 120)
        val legacy = StrokeStabilizer.create()
        val explicit = StrokeStabilizer.create(
            windowSize = StrokeStabilizer.DEFAULT_WINDOW_SIZE,
            prediction = StrokeStabilizer.DEFAULT_PREDICTION
        )
        for (point in raw) {
            val a = legacy.next(point.x, point.y)
            val b = explicit.next(point.x, point.y)
            assertEquals(a.x, b.x, 0f)
            assertEquals(a.y, b.y, 0f)
        }
    }

    @Test
    fun `golden window 12 lags far behind the same step`() {
        val settleFast = stepSettleSamples(windowSize = 2)
        val settleSlow = stepSettleSamples(windowSize = 12)
        assertTrue(
            "window 12 must react visibly slower than window 2 ($settleSlow vs $settleFast)",
            settleSlow > settleFast * 2
        )
        // After 5 samples window 12 is still far from the target while window 2
        // has essentially arrived.
        val w12 = StrokeStabilizer.create(windowSize = 12, prediction = StrokeStabilizer.DEFAULT_PREDICTION)
        w12.next(0f, 0f)
        var p = w12.next(0f, 0f)
        repeat(5) { p = w12.next(100f, 0f) }
        assertTrue("window 12 must still lag after 5 samples, was ${p.x}", abs(p.x - 100f) > 20f)
    }

    @Test
    fun `jitter suppression increases monotonically with window size`() {
        val raw = noisyLine(n = 300)
        fun varianceFor(windowSize: Int): Double =
            residualVariance(
                run {
                    val f = StrokeStabilizer.create(windowSize = windowSize, prediction = StrokeStabilizer.DEFAULT_PREDICTION)
                    raw.map { f.next(it.x, it.y) }
                },
                2f, 50f
            )
        val v2 = varianceFor(2)
        val v8 = varianceFor(8)
        val v12 = varianceFor(12)
        val rawVar = residualVariance(raw, 2f, 50f)
        assertTrue("raw input must be noisy", rawVar > 10.0)
        assertTrue("window 12 must beat window 8 ($v12 vs $v8)", v12 < v8)
        assertTrue("window 8 must beat window 2 ($v8 vs $v2)", v8 < v2)
        assertTrue("every window must reduce jitter ($v2 vs $rawVar)", v2 < rawVar)
    }

    @Test
    fun `retune changes the live filter between strokes`() {
        val filter = com.authorss81.noteflow.services.StabilizerFilter(
            StrokeSmoothingPolicy.MAX_WINDOW_SIZE,
            StrokeStabilizer.DEFAULT_PREDICTION
        )
        // Converge somewhere off-origin, then re-tune DOWN to window 2 and
        // verify the very next steps move with window-2 responsiveness.
        repeat(40) { filter.next(-500f, -500f) }
        filter.reset()
        filter.retune(StrokeSmoothingPolicy.MIN_WINDOW_SIZE, StrokeStabilizer.DEFAULT_PREDICTION)
        filter.next(0f, 0f)
        var p = filter.next(0f, 0f)
        var samples = 0
        while (abs(p.x - 100f) > 5f) {
            p = filter.next(100f, 0f)
            samples++
            assertTrue("retuned filter must not stall (>$samples samples)", samples < 20)
        }
        assertTrue(samples <= 6)

        // Sub-window sizes clamp up to the minimum instead of dividing by ~0.
        val clamped = StrokeStabilizer.create(windowSize = 12, prediction = StrokeStabilizer.DEFAULT_PREDICTION)
        clamped.retune(0, StrokeStabilizer.DEFAULT_PREDICTION)
        clamped.next(0f, 0f)
        var q = clamped.next(0f, 0f)
        var clampedSamples = 0
        while (abs(q.x - 100f) > 5f) {
            q = clamped.next(100f, 0f)
            clampedSamples++
            if (clampedSamples > 20) break
        }
        assertTrue("retune(0) must behave like the minimum window", clampedSamples <= 6)
    }

    // ---- Phase 201 (PERF 1.4) goldens: stabilized ink + per-brush RDP ----------
    //
    // The committed stroke is simplify(stabilize(raw)). These pin that a
    // hairline brush's tighter epsilon keeps strictly more of the stabilized
    // geometry than the legacy coarse epsilon, and that the RDP contract
    // (exact start/end, never more points than the input) holds on the exact
    // artifact the canvas commits on pointer-up.

    /** A gentle 1px-amplitude wiggle — the detail a hairline nib must survive commit. */
    private fun hairlineWiggle(n: Int = 120): List<StabilizerPoint> =
        (0 until n).map { i ->
            val x = i.toFloat()
            StabilizerPoint(x = x, y = 100f + kotlin.math.sin(i * 0.35f))
        }

    @Test
    fun `golden hairline epsilon keeps strictly more stabilized detail than the coarse one`() {
        val raw = hairlineWiggle()
        val stabilized = StrokeStabilizer.smooth(raw).map {
            com.authorss81.noteflow.data.model.PointF(it.x, it.y, pressure = 0.08f)
        }
        val policy = com.authorss81.noteflow.services.StrokeSimplifyPolicy
        // FINELINER at 1.5 px is hairline -> inside the 0.6..0.8 band.
        val hairlineEps = policy.epsilonFor(com.authorss81.noteflow.data.model.StrokeTool.FINELINER, 1.5f)
        assertTrue("hairline epsilon must sit in the tight band, was $hairlineEps", hairlineEps in policy.HAIRLINE_MIN_EPSILON_PX..policy.HAIRLINE_MAX_EPSILON_PX)

        val hairline = com.authorss81.noteflow.utils.RamerDouglasPeucker.simplify(stabilized, hairlineEps)
        val coarse = com.authorss81.noteflow.utils.RamerDouglasPeucker.simplify(
            stabilized,
            com.authorss81.noteflow.services.StrokeSimplifyPolicy.DEFAULT_EPSILON_PX
        )
        assertTrue(
            "hairline commit must keep more inflections ($hairline kept ${hairline.size}, coarse kept ${coarse.size})",
            hairline.size > coarse.size
        )
        // The 1 px-amplitude wiggle collapses almost entirely at 1.3 px...
        assertTrue("coarse epsilon should flatten this detail", coarse.size < stabilized.size / 4)
        // ...yet the hairline result still preserves real structure.
        assertTrue("hairline epsilon should keep structure, kept ${hairline.size}", hairline.size >= 8)
    }

    @Test
    fun `golden simplified stabilized stroke preserves endpoints and can only shrink`() {
        val raw = noisyLine(n = 200)
        val stabilized = StrokeStabilizer.smooth(raw).map {
            com.authorss81.noteflow.data.model.PointF(it.x, it.y, pressure = 0.5f)
        }
        for (tool in com.authorss81.noteflow.data.model.StrokeTool.entries) {
            val eps = com.authorss81.noteflow.services.StrokeSimplifyPolicy.epsilonFor(tool, 2f)
            val out = com.authorss81.noteflow.utils.RamerDouglasPeucker.simplify(stabilized, eps)
            assertTrue("output can never exceed the input ($tool)", out.size <= stabilized.size)
            assertEquals("$tool start preserved exactly", stabilized.first(), out.first())
            assertEquals("$tool end preserved exactly", stabilized.last(), out.last())
        }
    }
}
