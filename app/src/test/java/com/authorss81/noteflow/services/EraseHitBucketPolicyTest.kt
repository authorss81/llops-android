package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.EraseMask
import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 249 (Bug 4): the eraser's spatial bucket bounds `applyEraser` to
 * strokes whose world-space bounding box intersects the eraser circle, instead
 * of scanning every stroke on the page per drag sample.
 *
 * Pure-JVM tests over the real [EraseHitBucketPolicy]:
 *  - world-space bucketing is monotonic and correct (no false negatives / no
 *    distant strokes scanned),
 *  - multi-cell strokes are deduplicated,
 *  - `replaceStrokes` re-tiles incrementally AND handles the wet partial-erase
 *    same-id replacement (original → masked copy) without leaking the stale
 *    object.
 */
class EraseHitBucketPolicyTest {

    private fun stroke(id: String, vararg pts: Pair<Float, Float>, width: Float = 3f): Stroke {
        val points = pts.map { PointF(it.first, it.second) }
        return Stroke(
            id = id,
            width = width,
            points = points,
            start = points.firstOrNull(),
            end = points.lastOrNull()
        )
    }

    /** The canvas whole-stroke coverage rule (`strokeContainsPoint`). */
    private val radiusFor: (Stroke) -> Float = { it.width + 18f }

    @Test
    fun `nearby strokes are candidates and distant strokes are never scanned`() {
        val near = stroke("near", 10f to 10f, 30f to 30f)
        val mid = stroke("mid", 800f to 800f, 830f to 800f)
        val far = stroke("far", 9000f to 9000f, 9030f to 9000f)
        val bucket = EraseHitBucketPolicy.build(listOf(near, mid, far), maxStampRadiusPx = 30f)

        val candidates = bucket.candidatesWithinCircle(cx = 20f, cy = 20f, radiusFor = radiusFor)

        assertTrue("stroke on the cursor must be a candidate", candidates.any { it.id == "near" })
        assertTrue("stroke tens of cells away must not be scanned", candidates.none { it.id == "mid" })
        assertTrue("stroke far away must not be scanned", candidates.none { it.id == "far" })
    }

    @Test
    fun `a stroke spanning many cells is returned exactly once`() {
        val long = stroke("long", 0f to 0f, 1000f to 0f, 2000f to 0f, 3000f to 0f)
        val bucket = EraseHitBucketPolicy.build(listOf(long), maxStampRadiusPx = 30f)

        val candidates = bucket.candidatesWithinCircle(cx = 0f, cy = 0f, radiusFor = radiusFor)

        assertEquals("multi-cell strokes must be deduplicated", 1, candidates.size)
        assertEquals("long", candidates.single().id)
    }

    @Test
    fun `candidate set is monotonic as the query radius grows`() {
        val strokes = listOf(
            stroke("s1", 10f to 10f, 60f to 60f),
            stroke("s2", 130f to 130f, 180f to 180f),
            stroke("s3", 400f to 400f, 440f to 440f)
        )
        val bucket = EraseHitBucketPolicy.build(strokes, maxStampRadiusPx = 200f)

        val small = bucket.candidatesWithinCircle(cx = 30f, cy = 30f, radiusFor = radiusFor).map { it.id }.toSet()
        val large = bucket.candidatesWithinCircle(cx = 30f, cy = 30f, radiusFor = { 150f }).map { it.id }.toSet()

        assertTrue("a smaller query radius must never return more strokes", small.size <= large.size)
        assertTrue("a larger query radius must be a superset", small.all { it in large })
        assertTrue("the larger radius must reach farther strokes", large.contains("s2"))
    }

    @Test
    fun `no false negatives - every stroke a coverage hit could reach is a candidate`() {
        val strokes = (0..30).map { i ->
            val x = 1000f + (i % 6) * 40f
            val y = 1000f + (i / 6) * 40f
            stroke("s$i", x to y, x + 20f to y + 20f)
        }
        val cx = 1000f
        val cy = 1000f
        val bucket = EraseHitBucketPolicy.build(strokes, maxStampRadiusPx = 40f)
        val candidates = bucket.candidatesWithinCircle(cx, cy) { it.width + 18f }.map { it.id }.toSet()

        val expected = strokes.filter { s ->
            s.points.any { (it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy) <= (s.width + 18f) * (s.width + 18f) }
        }.map { it.id }.toSet()

        assertTrue("every stroke the eraser could hit must be within the candidate superset", expected.all { it in candidates })
        assertTrue("far strokes must still be excluded", candidates.size < strokes.size)
    }

    @Test
    fun `replaceStrokes drops the removed stroke and surfaces the survivor`() {
        val original = stroke("a", 10f to 10f, 30f to 30f)
        val survivor = stroke("a2", 15f to 15f, 30f to 30f)
        val bucket = EraseHitBucketPolicy.build(listOf(original), maxStampRadiusPx = 30f)

        bucket.replaceStrokes(removed = listOf(original), added = listOf(survivor))

        val candidates = bucket.candidatesWithinCircle(cx = 20f, cy = 20f, radiusFor = radiusFor)
        assertTrue("the erased stroke must never be returned again", candidates.none { it.id == "a" })
        assertTrue("the surviving segment must be returned", candidates.any { it.id == "a2" })
    }

    @Test
    fun `same-id wet partial replacement keeps only the masked copy`() {
        val original = stroke("w", 10f to 10f, 30f to 30f)
        val masked = original.copy(eraseMask = listOf(EraseMask(20f, 20f, 8f)))
        val bucket = EraseHitBucketPolicy.build(listOf(original), maxStampRadiusPx = 30f)

        bucket.replaceStrokes(removed = listOf(original), added = listOf(masked))

        val candidates = bucket.candidatesWithinCircle(cx = 20f, cy = 20f, radiusFor = radiusFor)
        assertEquals("exactly the masked copy must remain", listOf("w"), candidates.map { it.id })
        assertEquals("the MASKED (new) object must be the surviving candidate", masked, candidates.single())
    }

    @Test
    fun `the per-call sample cap is the coalesced burst size of eight`() {
        assertEquals(8, EraseHitBucketPolicy.MAX_ERASE_SAMPLES_PER_APPLY)
    }
}
