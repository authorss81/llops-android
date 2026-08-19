package com.authorss81.noteflow

import com.authorss81.noteflow.services.GestureRedoUndoClassifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 155: pure-JVM classifier decisions — two-finger swipe left/right,
 * two-finger double tap, and the pinch guard that must never be hijacked.
 */
class GestureRedoUndoClassifierTest {

    private fun swipeSession(direction: Int, steps: Int = 4, stepPx: Float = 50f): GestureRedoUndoClassifier.Action {
        val c = GestureRedoUndoClassifier()
        c.onFrame(2, floatArrayOf(0f, 0f, 100f, 0f), 0L)
        for (i in 1..steps) {
            val dx = direction * stepPx * i
            c.onFrame(2, floatArrayOf(dx, 0f, dx + 100f, 0f), i * 16L)
        }
        return c.onFrame(0, null, (steps + 1) * 16L)
    }

    // ---- swipes ---------------------------------------------------------------

    @Test
    fun swipeLeftFiresUndo() {
        assertEquals(GestureRedoUndoClassifier.Action.UNDO, swipeSession(-1))
    }

    @Test
    fun swipeRightFiresRedo() {
        assertEquals(GestureRedoUndoClassifier.Action.REDO, swipeSession(1))
    }

    @Test
    fun shortSwipeBelowThresholdIsNone() {
        assertEquals(GestureRedoUndoClassifier.Action.NONE, swipeSession(-1, steps = 1, stepPx = 40f))
    }

    // ---- pinch guard ----------------------------------------------------------

    @Test
    fun pinchOutNeverFires() {
        val c = GestureRedoUndoClassifier()
        c.onFrame(2, floatArrayOf(0f, 0f, 100f, 0f), 0L)      // start separation 100
        c.onFrame(2, floatArrayOf(-50f, 0f, 150f, 0f), 16L)   // spread to 200 → ratio 2.0
        assertEquals(GestureRedoUndoClassifier.Action.NONE, c.onFrame(0, null, 32L))
    }

    @Test
    fun pinchInNeverFires() {
        val c = GestureRedoUndoClassifier()
        c.onFrame(2, floatArrayOf(0f, 0f, 200f, 0f), 0L)      // start separation 200
        c.onFrame(2, floatArrayOf(0f, 0f, 100f, 0f), 16L)     // close to 100 → ratio 0.5
        assertEquals(GestureRedoUndoClassifier.Action.NONE, c.onFrame(0, null, 32L))
    }

    @Test
    fun pinchDoesNotCountAsTap() {
        val c = GestureRedoUndoClassifier()
        // First a clean tap.
        c.onFrame(2, floatArrayOf(0f, 0f, 100f, 0f), 0L)
        c.onFrame(0, null, 20L)
        // Then a pinch — must NOT pair with the previous tap.
        c.onFrame(2, floatArrayOf(0f, 0f, 100f, 0f), 100L)
        c.onFrame(2, floatArrayOf(-50f, 0f, 150f, 0f), 116L)  // ratio 2.0
        assertEquals(GestureRedoUndoClassifier.Action.NONE, c.onFrame(0, null, 132L))
    }

    // ---- vertical pan / degenerate sessions ------------------------------------

    @Test
    fun verticalPanIsNeverUndoOrRedo() {
        val c = GestureRedoUndoClassifier()
        c.onFrame(2, floatArrayOf(0f, 0f, 100f, 0f), 0L)
        for (i in 1..4) {
            c.onFrame(2, floatArrayOf(0f, i * 60f, 100f, i * 60f), i * 16L)
        }
        assertEquals(GestureRedoUndoClassifier.Action.NONE, c.onFrame(0, null, 80L))
    }

    @Test
    fun nearlyCoincidentPointersNeverStart() {
        val c = GestureRedoUndoClassifier()
        assertEquals(GestureRedoUndoClassifier.Action.NONE, c.onFrame(2, floatArrayOf(100f, 100f, 110f, 100f), 0L))
        assertEquals(GestureRedoUndoClassifier.Action.NONE, c.onFrame(0, null, 16L))
        assertFalse(c.isSessionActive)
    }

    // ---- taps & double taps ----------------------------------------------------

    private fun tapAt(c: GestureRedoUndoClassifier, cx: Float, t0: Long): GestureRedoUndoClassifier.Action {
        c.onFrame(2, floatArrayOf(cx, 100f, cx + 100f, 100f), t0)
        return c.onFrame(0, null, t0 + 20L)
    }

    @Test
    fun singleTwoFingerTapDoesNothing() {
        val c = GestureRedoUndoClassifier()
        assertEquals(GestureRedoUndoClassifier.Action.NONE, tapAt(c, 0f, 0L))
    }

    @Test
    fun twoFingerDoubleTapFiresUndo() {
        val c = GestureRedoUndoClassifier()
        assertEquals(GestureRedoUndoClassifier.Action.NONE, tapAt(c, 0f, 0L))
        assertEquals(GestureRedoUndoClassifier.Action.UNDO, tapAt(c, 4f, 200L))
    }

    @Test
    fun secondTapOutsideIntervalDoesNotFire() {
        val c = GestureRedoUndoClassifier()
        assertEquals(GestureRedoUndoClassifier.Action.NONE, tapAt(c, 0f, 0L))
        assertEquals(GestureRedoUndoClassifier.Action.NONE, tapAt(c, 4f, 1000L))
    }

    @Test
    fun resetClearsPendingTap() {
        val c = GestureRedoUndoClassifier()
        assertEquals(GestureRedoUndoClassifier.Action.NONE, tapAt(c, 0f, 0L))
        c.reset()
        assertEquals(GestureRedoUndoClassifier.Action.NONE, tapAt(c, 4f, 200L))
    }

    @Test
    fun swipeNeverPairsWithNextTap() {
        val c = GestureRedoUndoClassifier()
        c.onFrame(2, floatArrayOf(0f, 0f, 100f, 0f), 0L)
        for (i in 1..4) {
            val dx = -50f * i
            c.onFrame(2, floatArrayOf(dx, 0f, dx + 100f, 0f), i * 16L)
        }
        assertEquals(GestureRedoUndoClassifier.Action.UNDO, c.onFrame(0, null, 80L))
        // The swipe must NOT have left a pending tap that pairs with the next one.
        assertEquals(GestureRedoUndoClassifier.Action.NONE, tapAt(c, 0f, 400L))
    }

    @Test
    fun sessionAgeReportsDurationWhileActive() {
        val c = GestureRedoUndoClassifier()
        c.onFrame(2, floatArrayOf(0f, 0f, 100f, 0f), 0L)
        c.onFrame(2, floatArrayOf(-20f, 0f, 80f, 0f), 50L)
        assertTrue(c.isSessionActive)
        assertTrue(c.sessionAgeMs() >= 50L)
        c.onFrame(0, null, 70L)
        assertFalse(c.isSessionActive)
        assertEquals(0L, c.sessionAgeMs())
    }
}
