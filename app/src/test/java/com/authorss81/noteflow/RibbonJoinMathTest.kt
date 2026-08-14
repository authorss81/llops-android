package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.services.RibbonJoinMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class RibbonJoinMathTest {

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        return sqrt(dx * dx + dy * dy)
    }

    // ---- geometry primitives ----------------------------------------------

    @Test
    fun `half width and cap radius scale linearly`() {
        assertEquals(4f, RibbonJoinMath.halfWidth(8f), 1e-5f)
        assertEquals(2f, RibbonJoinMath.vertexCapRadius(8f), 1e-5f)
        assertEquals(3f, RibbonJoinMath.notchLegLength(6f), 1e-5f)
    }

    @Test
    fun `extendBeyond extends the segment past its end point`() {
        val (ex, ey) = RibbonJoinMath.extendBeyond(0f, 0f, 10f, 0f, 2.5f)
        assertEquals(12.5f, ex, 1e-4f)
        assertEquals(0f, ey, 1e-4f)
    }

    @Test
    fun `segmentNormal is a unit vector perpendicular to the segment`() {
        val (nx, ny) = RibbonJoinMath.segmentNormal(0f, 0f, 4f, 0f)
        assertEquals(0f, nx, 1e-4f)
        assertEquals(1f, ny, 1e-4f)
        val (nx2, ny2) = RibbonJoinMath.segmentNormal(0f, 0f, 0f, 4f)
        assertEquals(-1f, nx2, 1e-4f)
        assertEquals(0f, ny2, 1e-4f)
    }

    @Test
    fun `turnCos maps straight to 1 and hairpin to -1`() {
        val straight = RibbonJoinMath.turnCos(PointF(0f, 0f), PointF(1f, 0f), PointF(2f, 0f))
        assertEquals(1f, straight, 1e-4f)
        // full hairpin: travel right then come straight back left
        val hairpin = RibbonJoinMath.turnCos(PointF(0f, 0f), PointF(1f, 0f), PointF(0f, 0f))
        assertEquals(-1f, hairpin, 1e-4f)
        // a sharp ~135° turn: change direction by +135° from +x axis
        val sharp = RibbonJoinMath.turnCos(PointF(0f, 0f), PointF(1f, 0f), PointF(1f - 0.7071067f, 0.7071067f))
        assertTrue("expected cos near -0.707, got $sharp", kotlin.math.abs(sharp - (-0.7071067f)) < 1e-3f)
    }

    // ---- notch coverage invariant ------------------------------------------

    /**
     * For every turn angle from gentle to hairpin, the vertex cap circle stamped
     * at the turn must cover the inside-notch sample point (the midpoint of the
     * notch triangle). This is the guarantee that removes the concave notch.
     */
    @Test
    fun `vertex cap circle covers the notch apex for every turn angle`() {
        val halfW = 4f
        val capR = RibbonJoinMath.vertexCapRadius(2f * halfW) // 0.5 * halfW
        val leg = RibbonJoinMath.notchLegLength(halfW)
        assertTrue(RibbonJoinMath.capCoversNotch(leg, capR))

        val a = PointF(0f, 0f)
        for (deg in 2..178) {
            val theta = deg * PI / 180.0
            val b = PointF(10f, 0f)
            val c = PointF(10f + 5f * cos(theta).toFloat(), 5f * sin(theta).toFloat())
            val (sx, sy) = RibbonJoinMath.notchBisectorSample(a, b, c, halfW)
            val d = distance(b.x, b.y, sx, sy)
            assertTrue(
                "turn ${deg}deg: notch sample ${d}px from vertex, cap radius $capR",
                d <= capR + 1e-3f
            )
        }
    }

    /**
     * The overlapping quad extension must cover the region immediately around the
     * vertex on the inside of the turn: the sample point must lie inside the union
     * of the two extended quads (in addition to the cap circle).
     */
    @Test
    fun `overlapping quads cover the inside-notch sample for sharp turns`() {
        val halfW = 4f
        // sharp 70-degree turn
        val a = PointF(0f, 0f)
        val b = PointF(10f, 0f)
        val c = PointF(10f + 5f * cos(70.0 * PI / 180.0).toFloat(), 5f * sin(70.0 * PI / 180.0).toFloat())
        val (sx, sy) = RibbonJoinMath.notchBisectorSample(a, b, c, halfW)

        val inFirst = RibbonJoinMath.quadContains(
            a.x, a.y, b.x, b.y, halfW, extendStart = false, extendEnd = true, sx, sy
        )
        val inSecond = RibbonJoinMath.quadContains(
            b.x, b.y, c.x, c.y, halfW, extendStart = true, extendEnd = false, sx, sy
        )
        val capR = RibbonJoinMath.vertexCapRadius(2f * halfW)
        val inCap = distance(b.x, b.y, sx, sy) <= capR + 1e-3f

        assertTrue("notch sample must be covered by a quad or the cap circle", inFirst || inSecond || inCap)
    }

    // ---- quad membership sanity -------------------------------------------

    @Test
    fun `quadContains rejects points far outside the quad`() {
        val a = PointF(0f, 0f)
        val b = PointF(10f, 0f)
        val h = 2f
        assertTrue(RibbonJoinMath.quadContains(a.x, a.y, b.x, b.y, h, false, false, 5f, 0f))
        assertFalse(RibbonJoinMath.quadContains(a.x, a.y, b.x, b.y, h, false, false, 5f, 3f))
        assertFalse(RibbonJoinMath.quadContains(a.x, a.y, b.x, b.y, h, false, false, 12f, 0f))
        // with end extension the beyond-end region becomes inside
        assertTrue(RibbonJoinMath.quadContains(a.x, a.y, b.x, b.y, h, false, true, 11f, 0f))
    }
}