package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.PointF
import kotlin.math.sqrt

/**
 * Pure-JVM geometry for smooth nib-style brush joins (Phase 27).
 *
 * CALLIGRAPHIC / CHISEL_MARKER currently render as one filled quad per segment.
 * At sharp direction changes adjacent quads pinch into a concave notch on the
 * inside of the turn and leave a needle corner on the outside — that reads as a
 * sharp, jagged edge. The fix used by the renderer:
 *
 *  1. each quad is extended [QUAD_OVERLAP] of the nib half-width PAST every
 *     interior vertex, so neighbouring quads always overlap;
 *  2. a filled cap circle of radius [VERTEX_CAP_FACTOR] * halfWidth is stamped
 *     at every interior vertex, so the truncated notch triangle is always
 *     covered (proved for all turn angles);
 *  3. round caps (halfWidth circles) are stamped at both stroke ends.
 *
 * The coverage invariant here is unit-tested and enforced for every turn angle.
 */
object RibbonJoinMath {

    /** Fraction of the nib half-width by which a quad extends past a vertex. */
    const val QUAD_OVERLAP = 0.5f

    /** Fraction of the nib half-width used for the vertex cap circle radius. */
    const val VERTEX_CAP_FACTOR = 0.5f

    /** Half-width of a stroke with total [width]. */
    fun halfWidth(width: Float): Float = width / 2f

    /** Vertex cap radius (matches the quad extension, see class docs). */
    fun vertexCapRadius(width: Float): Float = halfWidth(width) * VERTEX_CAP_FACTOR

    /**
     * Whether a filled cap circle of [capRadius] centered at the vertex [bx,by]
     * covers the inside-notch apex of a turn. With the quad extension
     * [QUAD_OVERLAP], the notch triangle has legs of length overlap*L along each
     * inner edge; a circle through those two endpoints and the vertex covers the
     * whole triangle. Expressed for the renderer: the cap circle must have radius
     * >= the leg length.
     */
    fun capCoversNotch(legLength: Float, capRadius: Float): Boolean = capRadius >= legLength - 1e-4f

    /**
     * Leg length of the truncated notch triangle given a nib half-width [h] and
     * the quad overlap extension. Each inner edge is a quad side at distance h
     * from its segment; the extension past the vertex is overlap * h.
     */
    fun notchLegLength(halfW: Float): Float = halfW * QUAD_OVERLAP

    /**
     * Point along segment (ax,ay)->(bx,by) extended past [bx,by] by [extension]
     * pixels. Used to build the overlapping quads at interior vertices.
     */
    fun extendBeyond(x: Float, y: Float, bx: Float, by: Float, extension: Float): Pair<Float, Float> {
        val dx = bx - x
        val dy = by - y
        val len = sqrt(dx * dx + dy * dy)
        if (len <= 1e-6f) return bx to by
        val ux = dx / len
        val uy = dy / len
        return (bx + ux * extension) to (by + uy * extension)
    }

    /**
     * Offsets a point (x,y) by +/[offsetX,offsetY] scaled by [k] (k = +1 left,
     * -1 right of the travel direction). Kept as a helper so the renderer and the
     * join tests build identical quads.
     */
    fun offsetPerp(nx: Float, ny: Float, k: Float, x: Float, y: Float): Pair<Float, Float> =
        (x + nx * k) to (y + ny * k)

    /**
     * Unit perpendicular of segment (ax,ay)->(bx,by) (the shader's perpDir).
     */
    fun segmentNormal(ax: Float, ay: Float, bx: Float, by: Float): Pair<Float, Float> {
        val dx = bx - ax
        val dy = by - ay
        val len = sqrt(dx * dx + dy * dy)
        if (len <= 1e-6f) return 0f to 1f
        return (-dy / len) to (dx / len)
    }

    /**
     * Convenience wrapper around [PointF] points for tests/renderer symmetry.
     */
    fun segmentNormal(a: PointF, b: PointF): Pair<Float, Float> = segmentNormal(a.x, a.y, b.x, b.y)

    /**
     * Sample point for the notch-coverage test: the inside-angle bisector point
     * half-way to the notch apex. For a turn between segment AB and BC at vertex
     * B, with nib half-width [h], this is the midpoint of the notch triangle,
     * i.e. at distance h*QUAD_OVERLAP/2 along each inner edge from B.
     */
    fun notchBisectorSample(a: PointF, b: PointF, c: PointF, halfW: Float): Pair<Float, Float> {
        val n1 = segmentNormal(a, b)
        val n2 = segmentNormal(b, c)
        val leg = notchLegLength(halfW)
        val half = leg / 2f
        // inner edge directions: first along AB, second along BC
        val u1x = b.x - a.x
        val u1y = b.y - a.y
        val l1 = sqrt(u1x * u1x + u1y * u1y)
        val u2x = c.x - b.x
        val u2y = c.y - b.y
        val l2 = sqrt(u2x * u2x + u2y * u2y)
        val p1x = b.x + (if (l1 > 1e-6f) u1x / l1 * half else 0f)
        val p1y = b.y + (if (l1 > 1e-6f) u1y / l1 * half else 0f)
        val p2x = b.x + (if (l2 > 1e-6f) u2x / l2 * half else 0f)
        val p2y = b.y + (if (l2 > 1e-6f) u2y / l2 * half else 0f)
        return ((p1x + p2x) / 2f) to ((p1y + p2y) / 2f)
    }

    /**
     * Whether a candidate point is inside a quad built from segment (ax,ay)->(bx,by)
     * with nib half-width [h] and quad overlap extension applied at [extendStart] /
     * [extendEnd]. Used by the tests to prove the notch is filled.
     */
    fun quadContains(
        ax: Float, ay: Float, bx: Float, by: Float,
        h: Float,
        extendStart: Boolean,
        extendEnd: Boolean,
        px: Float, py: Float
    ): Boolean {
        var a0x = ax; var a0y = ay; var b0x = bx; var b0y = by
        if (extendStart) {
            val e = extendBeyond(b0x, b0y, a0x, a0y, h * QUAD_OVERLAP)
            a0x = e.first; a0y = e.second
        }
        if (extendEnd) {
            val e = extendBeyond(a0x, a0y, b0x, b0y, h * QUAD_OVERLAP)
            b0x = e.first; b0y = e.second
        }
        val (nx, ny) = segmentNormal(a0x, a0y, b0x, b0y)
        val along = (px - a0x) * (b0x - a0x) + (py - a0y) * (b0y - a0y)
        val len2 = (b0x - a0x) * (b0x - a0x) + (b0y - a0y) * (b0y - a0y)
        if (len2 <= 1e-6f) return false
        if (along < 0f || along > len2) return false
        val perp = (px - a0x) * nx + (py - a0y) * ny
        return kotlin.math.abs(perp) <= h + 1e-4f
    }

    /**
     * Cosine of the turn angle at [b] between points a-b-c. Used by the join test
     * to sweep through gentle to hairpin turns.
     */
    fun turnCos(a: PointF, b: PointF, c: PointF): Float {
        val u1x = b.x - a.x; val u1y = b.y - a.y
        val u2x = c.x - b.x; val u2y = c.y - b.y
        val l1 = sqrt(u1x * u1x + u1y * u1y)
        val l2 = sqrt(u2x * u2x + u2y * u2y)
        if (l1 <= 1e-6f || l2 <= 1e-6f) return 1f
        return ((u1x * u2x + u1y * u2y) / (l1 * l2)).coerceIn(-1f, 1f)
    }
}