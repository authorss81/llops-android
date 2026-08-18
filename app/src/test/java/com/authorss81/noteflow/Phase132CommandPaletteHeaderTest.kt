package com.authorss81.noteflow

import com.authorss81.noteflow.services.CommandPaletteHeaderPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM policy tests for Phase 132: the command-palette header no longer
 * squishes the title on portrait/mobile viewports.
 *
 * The layout fix itself is a Compose change (title on its own full-width
 * line, shortcut hint ellipsized beneath it) and is not JVM-testable; this
 * class pins the extracted decision table — the hint string + its
 * truncation/ellipsis math — and the single-source-of-truth constants the
 * composable now consumes.
 */
class Phase132CommandPaletteHeaderTest {

    // ---- Constants (single source of truth consumed by the composable) -----

    @Test
    fun `header copy lives in the policy`() {
        assertEquals("Command Palette", CommandPaletteHeaderPolicy.TITLE)
        assertEquals(
            "⌘ ↑/↓ · Enter · two-finger swipe down to open",
            CommandPaletteHeaderPolicy.SHORTCUT_HINT
        )
        assertEquals("…", CommandPaletteHeaderPolicy.ELLIPSIS)
    }

    @Test
    fun `hint is long enough that an unconstrained layout would crowd a narrow header`() {
        assertTrue(
            "hint must exceed a 40-char budget so the pre-fix single-line squeeze is real",
            CommandPaletteHeaderPolicy.SHORTCUT_HINT.length > CommandPaletteHeaderPolicy.DEFAULT_HINT_MAX_CHARS
        )
    }

    // ---- shortcutHint (hint builder) ---------------------------------------

    @Test
    fun `shortcutHint returns the full hint when it fits the budget`() {
        val full = CommandPaletteHeaderPolicy.SHORTCUT_HINT
        assertEquals(full, CommandPaletteHeaderPolicy.shortcutHint(full.length))
        assertEquals(full, CommandPaletteHeaderPolicy.shortcutHint(full.length + 50))
        // The default budget is smaller than the real hint → truncated, never full.
        val atDefault = CommandPaletteHeaderPolicy.shortcutHint()
        assertEquals(CommandPaletteHeaderPolicy.truncate(full, CommandPaletteHeaderPolicy.DEFAULT_HINT_MAX_CHARS), atDefault)
        assertFalse(atDefault == full)
    }

    @Test
    fun `shortcutHint never exceeds its budget and always ellipsizes`() {
        for (budget in 0..CommandPaletteHeaderPolicy.SHORTCUT_HINT.length) {
            val hint = CommandPaletteHeaderPolicy.shortcutHint(budget)
            // A zero budget fails closed to the bare ellipsis marker (length 1).
            if (budget == 0) {
                assertTrue("budget=0 must fail closed to the ellipsis", hint == CommandPaletteHeaderPolicy.ELLIPSIS)
            } else {
                assertTrue("budget=$budget length=${hint.length}", hint.length <= budget)
            }
            if (budget in 1 until CommandPaletteHeaderPolicy.SHORTCUT_HINT.length) {
                assertTrue("budget=$budget must ellipsize", hint.endsWith(CommandPaletteHeaderPolicy.ELLIPSIS))
                assertFalse("budget=$budget must not be the full hint", hint == CommandPaletteHeaderPolicy.SHORTCUT_HINT)
            }
        }
    }

    @Test
    fun `shortcutHint is a strict prefix of the full hint plus ellipsis`() {
        val truncated = CommandPaletteHeaderPolicy.shortcutHint(24)
        assertTrue(truncated.startsWith(CommandPaletteHeaderPolicy.SHORTCUT_HINT.take(24 - 1)))
        assertEquals(24, truncated.length)
    }

    // ---- truncate (pure helper) --------------------------------------------

    @Test
    fun `truncate keeps short text verbatim`() {
        assertEquals("Command Palette", CommandPaletteHeaderPolicy.truncate("Command Palette", 40))
        assertEquals("", CommandPaletteHeaderPolicy.truncate("", 40))
    }

    @Test
    fun `truncate drops the tail for one ellipsis on overflow`() {
        val t = CommandPaletteHeaderPolicy.truncate("abcdefgh", 5)
        assertEquals("abcd…", t)
        assertEquals(5, t.length)
    }

    @Test
    fun `truncate at exactly the length keeps the whole text`() {
        assertEquals("abcdefgh", CommandPaletteHeaderPolicy.truncate("abcdefgh", 8))
    }

    @Test
    fun `truncate fails closed on degenerate budgets`() {
        assertEquals(CommandPaletteHeaderPolicy.ELLIPSIS, CommandPaletteHeaderPolicy.truncate("abcdefgh", 0))
        assertEquals(CommandPaletteHeaderPolicy.ELLIPSIS, CommandPaletteHeaderPolicy.truncate("abcdefgh", -1))
        // Budget large enough for the ELLIPSIS but the text itself fits.
        assertEquals("x", CommandPaletteHeaderPolicy.truncate("x", 1))
        // Overflow into a one-char budget yields the bare ellipsis.
        assertEquals(CommandPaletteHeaderPolicy.ELLIPSIS, CommandPaletteHeaderPolicy.truncate("ab", 1))
    }

    @Test
    fun `truncate with unicode hint keeps the ellipsis boundary clean`() {
        val t = CommandPaletteHeaderPolicy.truncate("⌘ ↑/↓ · Enter · two-finger swipe down to open", 20)
        assertEquals(20, t.length)
        assertTrue(t.endsWith(CommandPaletteHeaderPolicy.ELLIPSIS))
        assertTrue(t.startsWith("⌘ ↑/↓ · Enter · two"))
    }
}
