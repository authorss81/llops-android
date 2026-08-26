package com.authorss81.noteflow

import com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 217 — tests for the resize-handle visibility policy updates:
 * - HIDDEN_HANDLE_ALPHA raised from 0f to 0.45f (discoverability)
 * - Markdown code blocks use visibleAtRest=true
 * - Canvas embeds remain visibleAtRest=false
 * - shouldShow still gates collapsed items
 * - Min/max resize feedback constants are non-empty
 * - Markdown handle alpha is always 0.45f
 */
class ResizeHandleVisibilityPolicyTest {

    @Test
    fun `HIDDEN_HANDLE_ALPHA is 0_45 for discoverability`() {
        assertEquals(0.45f, ResizeHandleVisibilityPolicy.HIDDEN_HANDLE_ALPHA, 1e-6f)
    }

    @Test
    fun `MARKDOWN_HANDLE_ALPHA is 0_45`() {
        assertEquals(0.45f, ResizeHandleVisibilityPolicy.MARKDOWN_HANDLE_ALPHA, 1e-6f)
    }

    @Test
    fun `canvas embeds do NOT show handles at rest`() {
        assertFalse(ResizeHandleVisibilityPolicy.visibleAtRest())
    }

    @Test
    fun `markdown code blocks DO show handles at rest`() {
        assertTrue(ResizeHandleVisibilityPolicy.markdownVisibleAtRest())
    }

    @Test
    fun `handleAlpha returns 0_45 when not visible (dim at rest)`() {
        assertEquals(0.45f, ResizeHandleVisibilityPolicy.handleAlpha(visible = false), 1e-6f)
    }

    @Test
    fun `handleAlpha returns 1f when visible (active)`() {
        assertEquals(1f, ResizeHandleVisibilityPolicy.handleAlpha(visible = true), 1e-6f)
    }

    @Test
    fun `markdownHandleAlpha always returns 0_45`() {
        assertEquals(0.45f, ResizeHandleVisibilityPolicy.markdownHandleAlpha(), 1e-6f)
    }

    @Test
    fun `shouldShow returns true when interacting and not collapsed`() {
        assertTrue(ResizeHandleVisibilityPolicy.shouldShow(interacting = true, collapsed = false))
    }

    @Test
    fun `shouldShow returns false when not interacting`() {
        assertFalse(ResizeHandleVisibilityPolicy.shouldShow(interacting = false, collapsed = false))
    }

    @Test
    fun `shouldShow returns false when collapsed even if interacting`() {
        assertFalse(ResizeHandleVisibilityPolicy.shouldShow(interacting = true, collapsed = true))
    }

    @Test
    fun `shouldShow returns false when collapsed and not interacting`() {
        assertFalse(ResizeHandleVisibilityPolicy.shouldShow(interacting = false, collapsed = true))
    }

    @Test
    fun `visibleWhileActive mirrors interacting flag`() {
        assertTrue(ResizeHandleVisibilityPolicy.visibleWhileActive(interacting = true))
        assertFalse(ResizeHandleVisibilityPolicy.visibleWhileActive(interacting = false))
    }

    @Test
    fun `resize min max toast constants are non-empty`() {
        assertTrue(ResizeHandleVisibilityPolicy.RESIZE_MIN_WIDTH_TOAST.isNotEmpty())
        assertTrue(ResizeHandleVisibilityPolicy.RESIZE_MAX_WIDTH_TOAST.isNotEmpty())
        assertTrue(ResizeHandleVisibilityPolicy.RESIZE_MIN_HEIGHT_TOAST.isNotEmpty())
        assertTrue(ResizeHandleVisibilityPolicy.RESIZE_MAX_HEIGHT_TOAST.isNotEmpty())
    }

    @Test
    fun `handle size constants are positive`() {
        assertTrue(ResizeHandleVisibilityPolicy.HANDLE_SIZE_DP > 0f)
        assertTrue(ResizeHandleVisibilityPolicy.ROTATION_HANDLE_SIZE_DP > 0f)
    }
}
