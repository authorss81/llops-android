package com.authorss81.noteflow

import com.authorss81.noteflow.services.FloatingWindowPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 238 review-fix (F2) — the one-time floating-window notice must classify
 * by WINDOW SHAPE, not by the bare multi-window flag. A usable tablet split pane
 * or a phone portrait split is NOT a floating surface and must never fire the
 * notice; only a square-ish, short window reads like a real freeform surface.
 */
class FloatingWindowPolicyTest {

    @Test
    fun `usable tablet split pane never fires`() {
        assertFalse(FloatingWindowPolicy.isLikelyFloatingWindow(700, 1000, inMultiWindow = true))
        assertFalse(FloatingWindowPolicy.isLikelyFloatingWindow(1280, 1000, inMultiWindow = true))
    }

    @Test
    fun `phone portrait split never fires`() {
        assertFalse("too narrow regardless of height", FloatingWindowPolicy.isLikelyFloatingWindow(411, 891, inMultiWindow = true))
        assertFalse(FloatingWindowPolicy.isLikelyFloatingWindow(360, 800, inMultiWindow = true))
    }

    @Test
    fun `square short freeform surface fires`() {
        assertTrue(FloatingWindowPolicy.isLikelyFloatingWindow(640, 540, inMultiWindow = true))
        assertTrue(FloatingWindowPolicy.isLikelyFloatingWindow(600, 480, inMultiWindow = true))
    }

    @Test
    fun `multi-window flag alone is not enough`() {
        assertFalse("landscape tablet shape outside multi-window is not freeform", FloatingWindowPolicy.isLikelyFloatingWindow(640, 540, inMultiWindow = false))
        assertFalse(FloatingWindowPolicy.isLikelyFloatingWindow(700, 450, inMultiWindow = false))
    }

    @Test
    fun `notice fires once per install`() {
        assertTrue(FloatingWindowPolicy.noticeDue(alreadyShown = false))
        assertFalse(FloatingWindowPolicy.noticeDue(alreadyShown = true))
    }
}