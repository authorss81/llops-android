package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 205: LASER trail lifetime decision table.
 *
 * Pre-205 behaviour being replaced (AnnotationCanvas ~:315): a 25 Hz
 * `delay(40)` poll removed each expired laser INDIVIDUALLY through the normal
 * onStrokesChanged channel, so every fade tick pushed a full-list undo entry,
 * cleared redo and armed a Room autosave — for an ephemeral pointer highlight.
 *
 * Post-205 contract pinned here:
 *  - ONE batched [LaserTrailPolicy.stripExpired] wave per wake containing ALL
 *    trails due in that wave;
 *  - order of surviving strokes preserved, non-laser strokes untouched;
 *  - trails without a capture timestamp never expire (live preview);
 *  - render-side [LaserTrailPolicy.fadeFraction] shares the SAME 1800 ms
 *    budget/boundary as removal, so nothing is removed while still visible.
 */
class LaserTrailPolicyTest {

    private fun laser(id: String, tsMs: Long?): Stroke = Stroke(
        id = id,
        tool = StrokeTool.LASER,
        points = emptyList(),
        timestampMs = tsMs
    )

    private fun pen(id: String, page: Int = 0): Stroke = Stroke(
        id = id,
        tool = StrokeTool.PEN,
        points = emptyList(),
        pdfPage = page
    )

    // ---- fade envelope -------------------------------------------------------

    @Test
    fun `fade fraction is full for a trail without a capture timestamp`() {
        assertEquals(1f, LaserTrailPolicy.fadeFraction(null, nowMs = 999_999L), 0f)
    }

    @Test
    fun `fade fraction ramps linearly over the 1800ms budget`() {
        val committedAt = 10_000L
        assertEquals(1f, LaserTrailPolicy.fadeFraction(committedAt, nowMs = committedAt), 0f)
        assertEquals(0.5f, LaserTrailPolicy.fadeFraction(committedAt, nowMs = committedAt + 900), 1e-4f)
        assertEquals(0f, LaserTrailPolicy.fadeFraction(committedAt, nowMs = committedAt + 1800), 0f)
    }

    @Test
    fun `fade fraction clamps past end-of-life and negative ages`() {
        val committedAt = 10_000L
        assertEquals(0f, LaserTrailPolicy.fadeFraction(committedAt, nowMs = committedAt + 5000), 0f)
        assertEquals(1f, LaserTrailPolicy.fadeFraction(committedAt + 100, nowMs = committedAt), 0f)
    }

    // ---- expiry boundary -----------------------------------------------------

    @Test
    fun `a trail expires exactly at the full fade budget`() {
        val stroke = laser("l1", tsMs = 5_000L)
        assertFalse(LaserTrailPolicy.isExpired(stroke, nowMs = 5_000L + 1799))
        assertTrue(LaserTrailPolicy.isExpired(stroke, nowMs = 5_000L + 1800))
    }

    @Test
    fun `a trail without a capture timestamp never expires`() {
        assertFalse(LaserTrailPolicy.isExpired(laser("live", tsMs = null), nowMs = Long.MAX_VALUE / 2))
    }

    @Test
    fun `non-laser strokes are never expiry candidates`() {
        val penStroke = Stroke(id = "p", tool = StrokeTool.PEN, points = emptyList(), timestampMs = 1L)
        assertFalse(LaserTrailPolicy.isExpired(penStroke, nowMs = 10_000_000L))
        assertTrue(LaserTrailPolicy.expiredIds(listOf(penStroke, laser("l", tsMs = 0L)), nowMs = 10_000_000L) == listOf("l"))
    }

    // ---- batched wave --------------------------------------------------------

    @Test
    fun `stripExpired returns null when nothing expired - no emission at all`() {
        val all = listOf(pen("a"), laser("l1", tsMs = 1_000L))
        assertNull(LaserTrailPolicy.stripExpired(all, nowMs = 1_000L + 1799))
    }

    @Test
    fun `one wave removes ALL due trails together - not one emit per stroke`() {
        // Two bursts committed 300ms apart, observed well past both budgets:
        // pre-205 this was two separate onStrokesChanged emissions per 40 ms
        // tick window (dozens over a fade); post-205 it is exactly one wave.
        val all = listOf(
            pen("a"),
            laser("burst-1", tsMs = 0L),
            laser("burst-2", tsMs = 300L),
            pen("b")
        )
        val wave = LaserTrailPolicy.stripExpired(all, nowMs = 3_000L)!!
        assertEquals(listOf("burst-1", "burst-2"), wave.removedIds)
        assertEquals(listOf("a", "b"), wave.remaining.map { it.id })
    }

    @Test
    fun `a wave removes only trails actually due and preserves survivor order`() {
        val all = listOf(
            laser("old", tsMs = 0L),
            pen("mid-page-stroke"),
            laser("young", tsMs = 2_500L)
        )
        val wave = LaserTrailPolicy.stripExpired(all, nowMs = 2_600L)!!
        assertEquals(listOf("old"), wave.removedIds)
        assertEquals(listOf("mid-page-stroke", "young"), wave.remaining.map { it.id })
    }

    @Test
    fun `successive waves converge with exactly one removal event per due set`() {
        // Model of the post-205 frame-clock loop: two lasers committed 500ms
        // apart produce EXACTLY two ephemeral emissions total (one per wave),
        // versus the pre-205 poll which re-emitted every 40ms while any trail
        // was pending.
        var state = listOf(laser("first", tsMs = 0L), laser("second", tsMs = 500L))
        var emissions = 0

        val waveOne = LaserTrailPolicy.stripExpired(state, nowMs = 1_800L)
        if (waveOne != null) {
            state = waveOne.remaining
            emissions++
        }
        val waveTwo = LaserTrailPolicy.stripExpired(state, nowMs = 2_300L)
        if (waveTwo != null) {
            state = waveTwo.remaining
            emissions++
        }
        val waveThree = LaserTrailPolicy.stripExpired(state, nowMs = 2_301L)

        assertEquals(2, emissions)
        assertTrue(state.isEmpty())
        assertNull(waveThree)
    }
}
