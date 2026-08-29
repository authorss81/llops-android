package com.authorss81.noteflow

import com.authorss81.noteflow.utils.NestedScrollGuardConfig
import com.authorss81.noteflow.utils.NestedScrollReporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Phase 231 (2026-08-28) + Phase 237 (2026-08-29): runtime nested-scroll guard.
 *
 * The guard detects a vertically scrollable being measured while already inside
 * another guarded vertical scroll parent (the "Vertically scrollable component
 * was measured with infinity maximum height constraints" /
 * CheckScrollableContainerConstraints crash) so the inner scrollable can be
 * handed a bounded height and the crash can be PREVENTED.
 *
 * Phase 237 changed the guard's semantics: [NestedScrollGuardConfig.enabled]
 * now defaults `true` (it was `BuildConfig.DEBUG`, i.e. a release no-op), and
 * nesting is no longer diagnosed by throwing in
 * [NestedScrollReporter.enterUnboundedScroll] — it is detected via
 * [NestedScrollReporter.isInsideScrollable] and neutralised by constraining the
 * nested scrollable's height (see [Modifier.nestedScrollGuard]).
 *
 * Because [NestedScrollGuardConfig.enabled] is a mutable flag, the pure-JVM test
 * can toggle it to simulate a disabled build without needing Robolectric or a
 * different BuildConfig.
 *
 * NOTE (review fix): the guard contracts under test are:
 *   - disabled (release) → enter/exit are no-ops that never track depth;
 *   - enabled, genuinely nested (enter while already inside) → reports nested
 *     via [NestedScrollReporter.isInsideScrollable] (it no longer throws — the
 *     crash is prevented, not diagnosed);
 *   - a single balanced enter/exit returns to depth 0, and exiting an empty
 *     stack is clamped to 0 (never negative).
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
        // Simulate release: BuildConfig.DEBUG=false makes the guarded branch
        // always false, so enter/exit must not track depth nor throw.
        NestedScrollGuardConfig.enabled = false

        // Enters/exits must not increment the counter nor throw, even many times.
        NestedScrollReporter.enterUnboundedScroll()
        NestedScrollReporter.enterUnboundedScroll()
        NestedScrollReporter.enterUnboundedScroll()

        assertEquals("disabled guard must not track depth", 0, NestedScrollReporter.currentDepth())
        assertFalse("guard must be off when simulating release", NestedScrollGuardConfig.enabled)
    }

    @Test
    fun `enabled guard reports nested while another scrollable is mid-measure`() {
        NestedScrollGuardConfig.enabled = true

        // First guarded scrollable starts measuring -> not yet nested, it is the
        // top-level scrollable of this measure pass.
        assertFalse(NestedScrollReporter.isInsideScrollable())
        NestedScrollReporter.enterUnboundedScroll()
        assertEquals(1, NestedScrollReporter.currentDepth())
        assertTrue("a guarded scrollable is nested while its ancestor measures", NestedScrollReporter.isInsideScrollable())

        // The nested inner scrollable detects nesting (isInsideScrollable) but no
        // longer throws — the guard constrains its height instead (phase-237).
        assertTrue(NestedScrollReporter.isInsideScrollable())
        NestedScrollReporter.enterUnboundedScroll()
        assertEquals(2, NestedScrollReporter.currentDepth())
        assertTrue(NestedScrollReporter.isInsideScrollable())

        // Exit all levels back to a clean baseline.
        NestedScrollReporter.exitUnboundedScroll()
        NestedScrollReporter.exitUnboundedScroll()
        assertEquals(0, NestedScrollReporter.currentDepth())
        assertFalse(NestedScrollReporter.isInsideScrollable())
    }

    @Test
    fun `balanced enter and exit resets depth to zero`() {
        NestedScrollGuardConfig.enabled = true

        // A single guarded scrollable measures and leaves.
        NestedScrollReporter.enterUnboundedScroll()
        assertEquals(1, NestedScrollReporter.currentDepth())

        NestedScrollReporter.exitUnboundedScroll()
        assertEquals(0, NestedScrollReporter.currentDepth())

        // Re-entering after a clean exit is allowed (new frame, single level).
        NestedScrollReporter.enterUnboundedScroll()
        NestedScrollReporter.exitUnboundedScroll()
        assertEquals(0, NestedScrollReporter.currentDepth())

        // Exiting below zero is clamped, never negative.
        NestedScrollReporter.exitUnboundedScroll()
        assertEquals(0, NestedScrollReporter.currentDepth())
    }
}
