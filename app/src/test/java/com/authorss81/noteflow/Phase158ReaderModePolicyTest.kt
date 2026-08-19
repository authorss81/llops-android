package com.authorss81.noteflow

import com.authorss81.noteflow.services.ReaderModePolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 158 (deferred ROADMAP 22.5) — reading/focus-mode decision table.
 */
class ReaderModePolicyTest {

    @Test
    fun `reader layout applies exactly when reader mode is on`() {
        assertTrue(ReaderModePolicy.shouldUseReaderLayout(readerMode = true))
        assertFalse(ReaderModePolicy.shouldUseReaderLayout(readerMode = false))
    }

    @Test
    fun `column width is bounded to the article measure`() {
        assertEquals(680f, ReaderModePolicy.MAX_COLUMN_WIDTH_DP, 0.001f)
    }

    @Test
    fun `reader line height widens the style's own leading at a fixed ratio`() {
        // The app's own type scale gives bodyLarge 16/24 and titleSmall 14/20
        // (TypeScale.kt). Reader mode multiplies the style's OWN line height, so
        // the reader leading is always WIDER than the default (24 -> 27.6,
        // 20 -> 23) — never the phase-158 bug where a fixed 1.35x fraction of the
        // type size produced 21.6sp, TIGHTER than the 24sp default.
        assertEquals(24f * ReaderModePolicy.BODY_LINE_HEIGHT_MULTIPLIER,
            ReaderModePolicy.readerLineHeightSp(baseFontSizeSp = 16f, baseLineHeightSp = 24f), 0.001f)
        assertEquals(20f * ReaderModePolicy.BODY_LINE_HEIGHT_MULTIPLIER,
            ReaderModePolicy.readerLineHeightSp(baseFontSizeSp = 14f, baseLineHeightSp = 20f), 0.001f)
        assertTrue("reader leading must exceed the style's own default",
            ReaderModePolicy.readerLineHeightSp(baseFontSizeSp = 16f, baseLineHeightSp = 24f) > 24f)
        assertTrue("reader leading must exceed the style's own default",
            ReaderModePolicy.readerLineHeightSp(baseFontSizeSp = 14f, baseLineHeightSp = 20f) > 20f)
        // A style that declares no line height falls back to the type-scale ratio,
        // still widened by the reader multiplier.
        assertEquals(12f * ReaderModePolicy.DEFAULT_BASE_LEADING_RATIO * ReaderModePolicy.BODY_LINE_HEIGHT_MULTIPLIER,
            ReaderModePolicy.readerLineHeightSp(baseFontSizeSp = 12f, baseLineHeightSp = 0f), 0.001f)
    }

    @Test
    fun `no absolute sizes are hardcoded into the policy`() {
        // The policy must never carry an absolute sp that would defeat the
        // system font-scale — every type decision flows through the theme's
        // already-scaled roles.
        val source = java.io.File(repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/services/ReaderModePolicy.kt")
            .readText()
            .replace(Regex("//[^\\n]*"), "")
        assertFalse(
            "no absolute sp override in the reader policy",
            source.contains(".sp") || source.contains("textSize") || source.contains("fontSize")
        )
    }

    @Test
    fun `captured notes default to reader mode`() {
        assertTrue(ReaderModePolicy.defaultReaderForCapturedNote(captureArrived = true))
        assertFalse(ReaderModePolicy.defaultReaderForCapturedNote(captureArrived = false))
    }

    @Test
    fun `reader toggle label lives in the string resource`() {
        // Review-fix: the toggle label was hardcoded in the policy; it now ships
        // as a resource so localization stays consistent with the widget strings.
        val strings = java.io.File(repoRoot(), "app/src/main/res/values/strings.xml").readText()
        assertTrue(strings.contains("<string name=\"reader_toggle_label\">"))
        assertTrue(strings.contains("Reader / focus mode"))
    }

    private fun repoRoot(): java.io.File {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (java.io.File(dir, "gradle/libs.versions.toml").isFile &&
                java.io.File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}