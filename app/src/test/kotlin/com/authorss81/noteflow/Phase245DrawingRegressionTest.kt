package com.authorss81.noteflow

import com.authorss81.noteflow.services.MotionPredictionPolicy
import com.authorss81.noteflow.services.QuickColorRingMath
import com.authorss81.noteflow.services.RawInputSample
import com.authorss81.noteflow.services.StrokeBatchPolicy
import com.authorss81.noteflow.services.StrokeInputBatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 245 — the two remaining "stray-dot / weird-shape" drawing defects, as
 * pure-JVM logic tests over the real backing classes.
 *
 * Bug 1 ("weird shape when I draw" — a donut/dot pops over the canvas instead
 * of a stroke): the long-press QUICK-COLOR RING used to open for ANY quiet
 * hold of `longPressTimeoutMillis`, even when the user was AIMING the first
 * mark or drawing slow deliberate ink. The ring then consumed the press, so
 * the intended stroke never deposited and the ring donut (+ its center dot)
 * was left on screen. The movement wait now yields to a stroke the instant the
 * pointer's displacement-from-down crosses the touch slop — pinned here via
 * `QuickColorRingMath.holdWithinLongPressSlop`.
 *
 * Bug 2 ("dots are registered far from the touch"): phase-240 removed the
 * window-offset DOUBLE-SUBTRACTION (pointerInteropFilter / predicted tail /
 * batch drain all work in the canvas box's node-local space). Pinned here via
 * `MotionPredictionPolicy.predictedWorldPoint` (the neutral-frame contract the
 * LIVE channel passes) and `StrokeInputBatcher`/`StrokeBatchPolicy` (the drain
 * is a FIFO identity on node-local coords + a monotonic stale gate so no
 * duplicate/replayed sample can stack a second dot).
 *
 * Comparison with the pre-dots code lives in `workspace/phase-245/REPORT.md`;
 * the pre-phase-240 code passed canvasWindowX/Y = the real box-window offset to
 * node-local samples, and tests below prove that offset displaces every point.
 */
class Phase245DrawingRegressionTest {

    // ================= Bug 1: quick-color ring must yield to a stroke ==========

    @Test
    fun `still finger at the down position may still open the ring`() {
        assertTrue(
            QuickColorRingMath.holdWithinLongPressSlop(
                pointerX = 500f, pointerY = 500f, downX = 500f, downY = 500f, slopPx = 12f
            )
        )
    }

    @Test
    fun `finger resting within the touch slop still counts as a long-press`() {
        // 11px in X only, from a 12px slop: sub-slop tremor must NOT abort.
        assertTrue(
            QuickColorRingMath.holdWithinLongPressSlop(
                pointerX = 511f, pointerY = 500f, downX = 500f, downY = 500f, slopPx = 12f
            )
        )
        // Exactly ON the slop boundary is still a hold (boundary-inclusive).
        val onBoundary = kotlin.math.hypot(12f, 0f)
        assertTrue(
            QuickColorRingMath.holdWithinLongPressSlop(
                pointerX = 500f + onBoundary, pointerY = 500f, downX = 500f, downY = 500f, slopPx = 12f
            )
        )
    }

    @Test
    fun `crossing the touch slop yields the gesture to the drawing path`() {
        // A single pixel past slop on ANY axis = the user is drawing, not
        // long-pressing: the ring must yield so the stroke takes over.
        assertFalse(
            QuickColorRingMath.holdWithinLongPressSlop(
                pointerX = 513f, pointerY = 500f, downX = 500f, downY = 500f, slopPx = 12f
            )
        )
        assertFalse(
            QuickColorRingMath.holdWithinLongPressSlop(
                pointerX = 500f, pointerY = 513f, downX = 500f, downY = 500f, slopPx = 12f
            )
        )
        assertFalse(
            QuickColorRingMath.holdWithinLongPressSlop(
                pointerX = 520f, pointerY = 520f, downX = 500f, downY = 500f, slopPx = 12f
            )
        )
    }

    @Test
    fun `slow steady drift aborts exactly like a fast flick - displacement from down`() {
        // A slow but steady stroke accumulates displacement-from-DOWN; when that
        // crosses slop the ring must yield. This is the "slow deliberate ink"
        // case that popped the donut before phase-245.
        assertFalse(
            QuickColorRingMath.holdWithinLongPressSlop(
                pointerX = 540f, pointerY = 503f, downX = 500f, downY = 500f, slopPx = 12f
            )
        )
        // ...and a pointer that is CURRENTLY back at the anchor is a valid hold.
        // (The composable's wait yields at the FIRST slop crossing and never
        // re-arms mid-gesture, so this only pins the stateless decision's edge.)
        assertTrue(
            QuickColorRingMath.holdWithinLongPressSlop(
                pointerX = 500f, pointerY = 500f, downX = 500f, downY = 500f, slopPx = 12f
            )
        )
    }

    @Test
    fun `zero slop means any movement yields`() {
        assertTrue(
            QuickColorRingMath.holdWithinLongPressSlop(
                pointerX = 500f, pointerY = 500f, downX = 500f, downY = 500f, slopPx = 0f
            )
        )
        assertFalse(
            QuickColorRingMath.holdWithinLongPressSlop(
                pointerX = 500.5f, pointerY = 500f, downX = 500f, downY = 500f, slopPx = 0f
            )
        )
    }

    // ================= Bug 2: node-local frame, no window-offset double-subtract

    @Test
    fun `neutral-frame world mapping keeps the stroke exactly under the pointer`() {
        // The LIVE predicted-tail channel passes canvasWindowX/Y = 0f (the
        // recorded real samples and the extrapolated prediction are BOTH
        // node-local). With the neutral frame the mapping is a pure pan/zoom
        // un-apply: still-local (700, 700) minus pan, divided by zoom.
        val p = MotionPredictionPolicy.predictedWorldPoint(
            predictedViewX = 700f,
            predictedViewY = 700f,
            canvasWindowX = 0f,
            canvasWindowY = 0f,
            zoomScale = 2f,
            panX = 100f,
            panY = 50f,
            pageWidthPx = 2000f,
            pageTopY = 0f,
            pageBottomY = 3000f,
            pressure = 0.6f,
            tilt = null,
            timestampMs = 42L
        )
        assertEquals("world.x = (view.x - pan.x)/zoom WITHOUT any window offset", (700f - 100f) / 2f, p!!.x, 0.0001f)
        assertEquals((700f - 50f) / 2f, p.y, 0.0001f)
    }

    @Test
    fun `leaking a window offset into node-local samples displaces every dot`() {
        // This is exactly the PRE-phase-240 bug: subtracting a real box-window
        // offset (say the canvas sits 800px right / 400px down in the window)
        // from ALREADY node-local samples shifts the mapped point by that whole
        // offset — the "dots far from the touch" symptom.
        val fromCompute = (700f - 800f - 100f) / 2f
        val neutral = MotionPredictionPolicy.predictedWorldPoint(
            700f, 700f, 0f, 0f, 2f, 100f, 50f, 2000f, 0f, 3000f, 0.6f, null, 42L
        )!!
        val doubleShifted = MotionPredictionPolicy.predictedWorldPoint(
            700f, 700f, 800f, 400f, 2f, 100f, 50f, 2000f, 0f, 3000f, 0.6f, null, 42L
        )
        // (700-800-100)/2 == neutral.x - 400: leaking the window X offset into a
        // node-local sample shifts the point by exactly that offset/zoom.
        assertEquals(fromCompute, neutral.x - 400f, 0.0001f)
        // The doubly-offset point lands FAR outside the page (negative world x) →
        // proof the DOUBLE subtraction is what scattered dots; 0f/0f is the only
        // correct frame for node-local samples.
        assertNull("double-subtracted prediction escapes the page and is DROPPED", doubleShifted)
    }

    @Test
    fun `dots on both axes carry through the same neutral geometry`() {
        val p = MotionPredictionPolicy.predictedWorldPoint(
            predictedViewX = 250f,
            predictedViewY = 120f,
            canvasWindowX = 0f,
            canvasWindowY = 0f,
            zoomScale = 1.5f,
            panX = 10f,
            panY = 20f,
            pageWidthPx = 2000f,
            pageTopY = 0f,
            pageBottomY = 2000f,
            pressure = null,
            tilt = 30f,
            timestampMs = null
        )
        assertEquals((250f - 10f) / 1.5f, p!!.x, 0.0001f)
        assertEquals((120f - 20f) / 1.5f, p.y, 0.0001f)
        assertNull("null pressure is stored as null", p.pressure)
        assertEquals(30f, p.tilt!!, 0f)
    }

    @Test
    fun `batch drain is a FIFO identity on node-local coords - no transform at capture`() {
        // The passive bridge stores box-local coordinates UNTRANSFORMED; the
        // batcher must return the exact same values so the drain can feed the
        // world mapping once per sample (any capture-time window math would
        // corrupt the geometry before pan/zoom ever runs).
        val batcher = StrokeInputBatcher()
        val samples = listOf(
            RawInputSample(601f, 602f, 0.3f, 0.0f, 1001L),
            RawInputSample(611f, 612f, 0.4f, 0.1f, 1002L),
            RawInputSample(621f, 622f, 0.5f, 0.2f, 1003L)
        )
        samples.forEach { batcher.offer(it) }

        val drained = ArrayList<RawInputSample>()
        assertEquals(3, batcher.drainInto(drained))
        assertEquals(
            "drained coords are the offset node-local values, byte-for-byte",
            samples.map { it.x to it.y },
            drained.map { it.x to it.y }
        )
        assertEquals(0, batcher.size)
    }

    @Test
    fun `a replayed duplicate sample never registers a second dot`() {
        // StrokeBatchPolicy.isStale is the monotonic gate: the SAME physical
        // move surfacing twice (replay/duplicate event) is dropped, so it cannot
        // stack a second stamp/point at one location — the "dots" scatter least
        // suspects near a still finger.
        assertTrue("same timestamp is stale", StrokeBatchPolicy.isStale(1005L, 1005L))
        assertTrue("older timestamp is stale", StrokeBatchPolicy.isStale(1000L, 1005L))
        assertFalse("newer timestamp is fresh", StrokeBatchPolicy.isStale(1006L, 1005L))
        assertFalse("first sample of a stroke is never stale", StrokeBatchPolicy.isStale(1005L, null))
    }

    @Test
    fun `out-of-page world points are dropped not clamped - matches the drag path`() {
        // Parity pin: the real drag path early-returns on out-of-page
        // (`isOutsidePage`), so the predicted tail uses the SAME drop rule.
        // A point past the RIGHT edge is dropped; a point ON the edge is kept.
        assertNull(
            MotionPredictionPolicy.predictedWorldPoint(
                9999f, 500f, 0f, 0f, 1f, 0f, 0f, 2000f, 0f, 3000f, 0.5f, null, 1L
            )
        )
        val onEdge = MotionPredictionPolicy.predictedWorldPoint(
            2000f, 500f, 0f, 0f, 1f, 0f, 0f, 2000f, 0f, 3000f, 0.5f, null, 1L
        )
        assertEquals("boundary-inclusive: exactly on the page edge is kept", 2000f, onEdge!!.x, 0f)
    }
}