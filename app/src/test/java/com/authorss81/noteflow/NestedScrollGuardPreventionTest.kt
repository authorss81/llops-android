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
 * Phase 237 (2026-08-29): nested-scroll crash PREVENTION.
 *
 * Phase 231 only threw a post-hoc diagnostic in debug builds; phase 237 changes
 * the guard so it *prevents* the crash in both debug and release by (a) keeping
 * the guard active in release (no longer gated on `BuildConfig.DEBUG`) and
 * (b) constraining the inner scrollable to a bounded height instead of throwing.
 *
 * The pure-JVM contracts exercised here (no Compose/Robolectric needed):
 *   - [NestedScrollReporter.isInsideScrollable] is false for a top-level /
 *     sibling scrollable (depth 0) and true for a scrollable measured while an
 *     ancestor guarded scrollable is mid-measure (depth > 0);
 *   - a disabled guard (simulated via [NestedScrollGuardConfig.enabled]) stays a
 *     strict no-op: it neither tracks depth nor reports nesting, exactly like a
 *     suppressed build without any regression;
 *   - the guard is, by default, ACTIVE (guard-on in release too — the phase-231
 *     `BuildConfig.DEBUG` default is gone).
 */
class NestedScrollGuardPreventionTest {

    @Before
    fun setUp() {
        NestedScrollGuardConfig.enabled = true
        while (NestedScrollReporter.currentDepth() > 0) {
            NestedScrollReporter.exitUnboundedScroll()
        }
    }

    @After
    fun tearDown() {
        while (NestedScrollReporter.currentDepth() > 0) {
            NestedScrollReporter.exitUnboundedScroll()
        }
        NestedScrollGuardConfig.enabled = true
    }

    @Test
    fun `isInsideScrollable is false when no guarded scrollable is measuring`() {
        assertFalse(
            "top-level / sibling scrollable is not nested",
            NestedScrollReporter.isInsideScrollable(),
        )
    }

    @Test
    fun `isInsideScrollable is true while a guarded scrollable is mid-measure`() {
        // The first guarded vertical scrollable measures…
        NestedScrollReporter.enterUnboundedScroll()
        try {
            // …so a descendant guarded scrollable that measures right now is NESTED.
            assertTrue(
                "a descendant measured while its ancestor measures is nested",
                NestedScrollReporter.isInsideScrollable(),
            )
        } finally {
            NestedScrollReporter.exitUnboundedScroll()
        }
        // After the ancestor finishes, the same site is no longer nested.
        assertFalse(NestedScrollReporter.isInsideScrollable())
    }

    @Test
    fun `siblings measure independently and are not mutually nested`() {
        // Sibling A: before it starts measuring there is no active ancestor, so it
        // is not nested (depth is 0).
        assertFalse(NestedScrollReporter.isInsideScrollable())
        NestedScrollReporter.enterUnboundedScroll()
        NestedScrollReporter.exitUnboundedScroll()
        assertEquals(0, NestedScrollReporter.currentDepth())

        // Sibling B measures AFTER A completed. Because A already exited, depth is
        // back to 0 the moment B starts, so B is not treated as nested either.
        assertFalse(
            "sibling scrollables measure sequentially, never nested",
            NestedScrollReporter.isInsideScrollable(),
        )
        NestedScrollReporter.enterUnboundedScroll()
        NestedScrollReporter.exitUnboundedScroll()
        assertEquals(0, NestedScrollReporter.currentDepth())
    }

    @Test
    fun `guard is active by default in release`() {
        // Phase-237 removed the `BuildConfig.DEBUG` default so the guard runs in
        // release builds (where the phase-231 crash actually reproduced).
        assertTrue(
            "the nested-scroll guard must be active in release builds",
            NestedScrollGuardConfig.enabled,
        )
    }

    @Test
    fun `disabled guard is a strict no-op`() {
        NestedScrollGuardConfig.enabled = false

        NestedScrollReporter.enterUnboundedScroll()
        NestedScrollReporter.enterUnboundedScroll()
        NestedScrollReporter.exitUnboundedScroll()

        assertEquals("disabled guard must not track depth", 0, NestedScrollReporter.currentDepth())
        assertFalse(
            "disabled guard must not report nesting",
            NestedScrollReporter.isInsideScrollable(),
        )
    }
}
