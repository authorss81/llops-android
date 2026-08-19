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
    fun `reader line height stays proportional to the base font size`() {
        // A user with a larger system font feeds a larger already-scaled base;
        // the ratio must stay fixed so leading scales with accessibility.
        assertEquals(13.5f * ReaderModePolicy.BODY_LINE_HEIGHT_MULTIPLIER,
            ReaderModePolicy.readerLineHeightSp(13.5f), 0.001f)
        assertEquals(20f * ReaderModePolicy.BODY_LINE_HEIGHT_MULTIPLIER,
            ReaderModePolicy.readerLineHeightSp(20f), 0.001f)
        val base = ReaderModePolicy.readerLineHeightSp(12f)
        val bigger = ReaderModePolicy.readerLineHeightSp(18f)
        assertEquals(ReaderModePolicy.BODY_LINE_HEIGHT_MULTIPLIER,
            (bigger - base) / (18f - 12f), 0.001f)
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
    fun `reader toggle has a stable non-localized label`() {
        assertTrue(ReaderModePolicy.READER_TOGGLE_LABEL.isNotBlank())
        assertEquals("Reader / focus mode", ReaderModePolicy.READER_TOGGLE_LABEL)
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