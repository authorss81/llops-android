package com.authorss81.noteflow

import com.authorss81.noteflow.utils.NestedScrollGuardConfig
import com.authorss81.noteflow.utils.NestedScrollReporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

/**
 * Phase 231 (2026-08-28): runtime nested-scroll guard.
 *
 * The guard is a DEBUG-ONLY diagnostic: it detects a vertically scrollable
 * being composed while already inside an unbounded-height vertical scroll
 * parent (the "Vertically scrollable component was measured with infinity
 * maximum height constraints" / CheckScrollableContainerConstraints crash).
 *
 * Because [NestedScrollGuardConfig.enabled] is a `var` (initialised from
 * `BuildConfig.DEBUG`), the pure-JVM test can toggle it to simulate a release
 * build (`false` → no-op) and a debug build (`true` → throws on nesting),
 * without needing Robolectric or a different BuildConfig.
 */
class Phase231NestedScrollGuardTest {

    @Before
    fun setUp() {
        // Enable so the depth counter can be drained to a clean baseline. The
        // ThreadLocal is process-static, so each test starts from depth 0.
        NestedScrollGuardConfig.enabled = true
        while (NestedScrollReporter.currentDepth() > 0) {
            NestedScrollReporter.exitUnboundedScroll()
        }
    }

    @After
    fun tearDown() {
        // Restore a balanced state and a non-throwing production flag.
        while (NestedScrollReporter.currentDepth() > 0) {
            NestedScrollReporter.exitUnboundedScroll()
        }
        NestedScrollGuardConfig.enabled = true
    }

    @Test
    fun `release build is a no-op`() {
        // Simulate release: the static-final BuildConfig.DEBUG=false would make
        // the whole body dead code; here we exercise the same branch by
        // disabling the guard flag.
        NestedScrollGuardConfig.enabled = false

        // Enters/exits must not increment the counter nor throw.
        NestedScrollReporter.enterUnboundedScroll()
        NestedScrollReporter.enterUnboundedScroll()
        NestedScrollReporter.enterUnboundedScroll()

        assertEquals("disabled guard must not track depth", 0, NestedScrollReporter.currentDepth())
        assertFalse("guard must be off when simulating release", NestedScrollGuardConfig.enabled)
    }

    @Test
    fun `debug guard throws when nesting exceeds depth 1`() {
        NestedScrollGuardConfig.enabled = true

        NestedScrollReporter.enterUnboundedScroll()
        assertEquals(1, NestedScrollReporter.currentDepth())

        // A second nested unbounded scroller must fail loudly with guidance.
        val ex = assertThrows(IllegalStateException::class.java) {
            NestedScrollReporter.enterUnboundedScroll()
        }
        assertTrue("exception must carry the guard guidance message", ex.message!!.contains("NestedScrollGuard"))
    }

    @Test
    fun `balanced enter and exit resets depth to zero`() {
        NestedScrollGuardConfig.enabled = true

        NestedScrollReporter.enterUnboundedScroll()
        NestedScrollReporter.enterUnboundedScroll()
        assertEquals(2, NestedScrollReporter.currentDepth())

        NestedScrollReporter.exitUnboundedScroll()
        assertEquals(1, NestedScrollReporter.currentDepth())

        NestedScrollReporter.exitUnboundedScroll()
        assertEquals(0, NestedScrollReporter.currentDepth())

        // Exiting below zero is clamped, never negative.
        NestedScrollReporter.exitUnboundedScroll()
        assertEquals(0, NestedScrollReporter.currentDepth())
    }
}
