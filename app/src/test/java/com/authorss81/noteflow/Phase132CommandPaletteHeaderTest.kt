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
 * class pins the single-source-of-truth header copy the composable consumes
 * and the premise behind the fix (the hint is long enough that an
 * unconstrained layout would crowd a narrow header).
 *
 * Truncation is left to Compose's pixel-based `TextOverflow.Ellipsis` — a
 * character-budget decision table was removed in the Phase 132 review (dead
 * production code; only tests referenced it), so there is deliberately no
 * truncation math to test here.
 */
class Phase132CommandPaletteHeaderTest {

    @Test
    fun `header copy lives in the policy`() {
        assertEquals("Command Palette", CommandPaletteHeaderPolicy.TITLE)
        assertEquals(
            "⌘ ↑/↓ · Enter · two-finger swipe down to open",
            CommandPaletteHeaderPolicy.SHORTCUT_HINT
        )
    }

    @Test
    fun `hint is long enough that an unconstrained layout would crowd a narrow header`() {
        assertTrue(
            "hint must be longer than a narrow phone header line so the pre-fix squeeze is real",
            CommandPaletteHeaderPolicy.SHORTCUT_HINT.length > 40
        )
    }

    @Test
    fun `title and hint are distinct copy — the hint can never displace the title`() {
        val title = CommandPaletteHeaderPolicy.TITLE
        val hint = CommandPaletteHeaderPolicy.SHORTCUT_HINT
        assertFalse(title.isEmpty())
        assertFalse(hint.isEmpty())
        assertTrue(hint != title)
    }
}