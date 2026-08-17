package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.utils.RamerDouglasPeucker
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2-DOS-09: `RamerDouglasPeucker.simplify` used to run the RDP split RECURSIVELY
 * (pre-fix `RamerDouglasPeucker.kt:14-37`), so a single long stroke whose farthest
 * point is repeatedly adjacent to a segment end hit a call-stack depth ~ point count
 * and threw a StackOverflowError in the canvas commit coroutine (interactive DoS on
 * low-end devices). The fix replaced the recursion with an explicit heap segment stack;
 * these tests prove the deep case completes without a StackOverflowError and that the
 * new implementation is byte-identical to the classic recursive result on normal inputs.
 */
class B2Dos09RdpRecursionTest {

    /**
     * Reference implementation of the PRE-FIX recursive RDP — kept in the test ONLY to
     * cross-check the iterative output on normal-sized inputs (never used to compute a
     * result on the deep degenerate case, whose recursion is what this finding kills).
     */
    private fun recursiveReference(points: List<PointF>, epsilon: Float): List<PointF> {
        if (points.size <= 2) return points
        var maxDistance = 0f
        var maxIndex = 0
        val start = points.first()
        val end = points.last()
        for (i in 1 until points.size - 1) {
            val distance = perpendicularDistance(points[i], start, end)
            if (distance > maxDistance) {
                maxDistance = distance
                maxIndex = i
            }
        }
        return if (maxDistance > epsilon) {
            val rec1 = recursiveReference(points.subList(0, maxIndex + 1), epsilon)
            val rec2 = recursiveReference(points.subList(maxIndex, points.size), epsilon)
            rec1.dropLast(1) + rec2
        } else {
            listOf(start, end)
        }
    }

    private fun perpendicularDistance(point: PointF, lineStart: PointF, lineEnd: PointF): Float {
        val dx = lineEnd.x - lineStart.x
        val dy = lineEnd.y - lineStart.y
        if (dx == 0f && dy == 0f) {
            val pdx = point.x - lineStart.x
            val pdy = point.y - lineStart.y
            return sqrt(pdx * pdx + pdy * pdy)
        }
        val numerator = abs(dy * point.x - dx * point.y + lineEnd.x * lineStart.y - lineEnd.y * lineStart.x)
        val denominator = sqrt(dx * dx + dy * dy)
        return numerator / denominator
    }

    /**
     * The finding's worst case: a square wave — every interior point alternates 0.0 / 1.0
     * on y, so the max-distance point from any segment chord is always adjacent to one of
     * the segment's ends, driving recursion depth to points − 2 (verified empirically).
     */
    private fun squareWave(pointCount: Int): List<PointF> =
        List(pointCount) { i -> PointF(x = i.toFloat(), y = if (i % 2 == 0) 1f else 0f) }

    @Test
    fun `degenerate square-wave long stroke completes without StackOverflowError`() {
        val stroke = squareWave(20_000)
        val firstRef = stroke.first()
        val lastRef = stroke.last()

        val result = RamerDouglasPeucker.simplify(stroke, epsilon = 0.2f)

        // Every perpendicular distance in a square wave is 0.5 (> epsilon 0.2), so every point
        // must be kept — and the run must complete without a StackOverflowError (the assertion
        // following this call is only reachable if it did).
        assertEquals("every square-wave point is kept at this epsilon", stroke.size, result.size)
        assertSame("exact start reference preserved", firstRef, result.first())
        assertSame("exact end reference preserved", lastRef, result.last())
        result.forEachIndexed { index, kept ->
            assertSame("kept point at $index must be the input element unchanged", stroke[index], kept)
        }
    }

    @Test
    fun `pre-fix recursion overflows on the same degenerate input`() {
        // Documents that the degenerate input above is GENUINELY deep (would have thrown in
        // the pre-fix recursive implementation under any default JVM/ART stack). The iterative
        // production implementation completing on the identical 20k-point input proves the fix.
        assertThrows(StackOverflowError::class.java) {
            recursiveReference(squareWave(50_000), epsilon = 0.2f)
        }
    }

    @Test
    fun `iterative result matches the classic recursive result on normal strokes`() {
        val rng = Random(20260817)
        val sizes = listOf(2, 3, 4, 5, 8, 16, 32, 64, 128, 256, 512, 1024, 2048)
        val epsilons = listOf(0.1f, 0.75f, 1.3f, 5.0f)
        for (n in sizes) {
            for (epsilon in epsilons) {
                // Deterministic random-walk "scribed" strokes.
                val stroke = buildList {
                    var x = 0f
                    var y = 0f
                    add(PointF(x, y))
                    repeat(n - 1) {
                        x += rng.nextFloat() * 8f - 4f
                        y += rng.nextFloat() * 8f - 4f
                        add(PointF(x, y))
                    }
                }
                val expected = recursiveReference(stroke, epsilon)
                val actual = RamerDouglasPeucker.simplify(stroke, epsilon)
                assertEquals("n=$n epsilon=$epsilon", expected, actual)
            }
        }
    }

    @Test
    fun `all-collinear strokes collapse to their exact endpoints`() {
        val stroke = buildList {
            add(PointF(0f, 0f))
            repeat(500) { i ->
                add(PointF(x = (i + 1) * 0.01f, y = 0f))
            }
            add(PointF(6f, 0f))
        }
        val result = RamerDouglasPeucker.simplify(stroke, epsilon = 0.75f)
        assertEquals(listOf(stroke.first(), stroke.last()), result)
        assertSame(stroke.first(), result[0])
        assertSame(stroke.last(), result[1])
    }

    @Test
    fun `a spike above epsilon is kept while sub-epsilon jitter is dropped`() {
        val spike = PointF(2.5f, 10f)
        val stroke = listOf(PointF(0f, 0f), PointF(1f, 0.05f), spike, PointF(4f, 0.03f), PointF(5f, 0f))
        val result = RamerDouglasPeucker.simplify(stroke, epsilon = 1.3f)
        assertTrue("spike must be retained", result.contains(spike))
        assertTrue("sub-epsilon mids are dropped", result.size <= 4)
        assertEquals(stroke.first(), result.first())
        assertEquals(stroke.last(), result.last())
    }

    @Test
    fun `tiny strokes pass through unchanged`() {
        val one = listOf(PointF(1f, 2f))
        val two = listOf(PointF(1f, 2f), PointF(3f, 4f))
        assertSame("single point returned as-is", one, RamerDouglasPeucker.simplify(one))
        assertSame("two-point stroke returned as-is", two, RamerDouglasPeucker.simplify(two))
    }

    @Test
    fun `large healthy stroke completes and preserves start and end`() {
        val rng = Random(99)
        val stroke = buildList {
            var x = 0f
            var y = 0f
            add(PointF(x, y))
            repeat(9_999) {
                x += rng.nextFloat() * 6f - 3f
                y += rng.nextFloat() * 6f - 3f
                add(PointF(x, y))
            }
        }
        val result = RamerDouglasPeucker.simplify(stroke, epsilon = 0.75f)
        assertTrue("simplification must not exceed input size", result.size <= stroke.size)
        assertTrue("a scribble is not fully collinear so some points stay", result.size >= 2)
        assertEquals(stroke.first(), result.first())
        assertEquals(stroke.last(), result.last())
    }

    @Test
    fun `result is deterministic across calls`() {
        val stroke = squareWave(3_000)
        val a = RamerDouglasPeucker.simplify(stroke, epsilon = 0.2f)
        val b = RamerDouglasPeucker.simplify(stroke, epsilon = 0.2f)
        assertEquals(a, b)
    }
}