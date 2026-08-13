package com.authorss81.noteflow

import com.authorss81.noteflow.services.StabilizerPoint
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
}
