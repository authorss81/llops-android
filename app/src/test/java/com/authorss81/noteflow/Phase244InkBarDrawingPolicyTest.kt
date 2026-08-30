package com.authorss81.noteflow

import com.authorss81.noteflow.services.InkBarDrawingPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM policy tests for Phase 244 (Bug 2): the floating ink bar yields the
 * top drawing area back to the canvas while a drawing tool is active.
 */
class Phase244InkBarDrawingPolicyTest {

    @Test
    fun `non-drawing tool never yields`() {
        assertFalse(
            InkBarDrawingPolicy.shouldYieldDrawingArea(
                drawingToolActive = false,
                barTopY = 10f,
                availableHeight = 2000f
            )
        )
        // Even a bar parked at the very top does not move when pan/select active.
        assertFalse(
            InkBarDrawingPolicy.shouldYieldDrawingArea(
                drawingToolActive = false,
                barTopY = 0f,
                availableHeight = 2000f
            )
        )
    }

    @Test
    fun `drawing tool plus top-half bar yields`() {
        assertTrue(
            InkBarDrawingPolicy.shouldYieldDrawingArea(
                drawingToolActive = true,
                barTopY = 100f,
                availableHeight = 2000f
            )
        )
        assertTrue(
            InkBarDrawingPolicy.shouldYieldDrawingArea(
                drawingToolActive = true,
                barTopY = 999f,
                availableHeight = 2000f
            )
        )
    }

    @Test
    fun `bottom-half bar is never nudged even while drawing`() {
        assertFalse(
            InkBarDrawingPolicy.shouldYieldDrawingArea(
                drawingToolActive = true,
                barTopY = 1000f,
                availableHeight = 2000f
            )
        )
        assertFalse(
            InkBarDrawingPolicy.shouldYieldDrawingArea(
                drawingToolActive = true,
                barTopY = 1999f,
                availableHeight = 2000f
            )
        )
    }

    @Test
    fun `tiny available height never yields at its floor`() {
        // Degenerate inputs are clamped and never report a spurious yield.
        assertFalse(
            InkBarDrawingPolicy.shouldYieldDrawingArea(
                drawingToolActive = true,
                barTopY = 0f,
                availableHeight = 0f
            )
        )
    }

    @Test
    fun `yield boundary sits at the vertical midpoint`() {
        // exactly on the midpoint is treated as the lower half (not yielded).
        assertFalse(
            InkBarDrawingPolicy.shouldYieldDrawingArea(
                drawingToolActive = true,
                barTopY = 1000f,
                availableHeight = 2000f
            )
        )
        // one dp above the midpoint yields.
        assertTrue(
            InkBarDrawingPolicy.shouldYieldDrawingArea(
                drawingToolActive = true,
                barTopY = 999f,
                availableHeight = 2000f
            )
        )
    }
}
