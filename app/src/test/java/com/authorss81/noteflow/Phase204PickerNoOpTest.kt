package com.authorss81.noteflow

import com.authorss81.noteflow.services.UiFailureTextPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 204 — silent data-loss/no-op fix #4: media pickers silently did
 * NOTHING when `contentResolver.openInputStream(uri)` returned null (e.g. a
 * cloud provider offline) — the photo embed, custom background and paper
 * texture taps appeared dead.
 *
 * Fix shape:
 *  - [UiFailureTextPolicy.pickerSourceUnavailable] owns ONE fixed non-alarming
 *    sentence per picker kind (fixed-text discipline — never exception text);
 *  - EVERY picker site in EditorScreen now handles `bytes == null` with that
 *    snackbar; this test source-pins that no bare `?.use {}` picker remains.
 */
class Phase204PickerNoOpTest {

    // ---------------- behavior: fixed text per kind ----------------

    @Test
    fun `every picker kind maps to its own fixed sentence`() {
        val kinds = UiFailureTextPolicy.PickerSourceKind.values()
        val texts = kinds.map { UiFailureTextPolicy.pickerSourceUnavailable(it) }
        assertEquals("one distinct fixed sentence per kind", texts.size, texts.toSet().size)
        texts.forEach { text ->
            assertTrue(text.isNotBlank())
            assertFalse("never leak exception text or paths", text.contains('/') || text.contains('\\'))
            assertTrue("must invite a retry", text.contains("Try again"))
        }
    }

    @Test
    fun `the mapping is deterministic`() {
        val kind = UiFailureTextPolicy.PickerSourceKind.PHOTO_EMBED
        repeat(3) {
            assertEquals(
                UiFailureTextPolicy.pickerSourceUnavailable(kind),
                UiFailureTextPolicy.pickerSourceUnavailable(kind)
            )
        }
    }

    // ---------------- source pins: no bare picker remains ----------------

    private fun editorScreen(): String =
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt")
            .readText()

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) return dir
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }

    @Test
    fun `every openInputStream picker site guards the null-bytes case`() {
        val source = editorScreen()
        // Code call sites only — comments mentioning openInputStream don't count.
        val sites = Regex("openInputStream\\((uri|it)\\)").findAll(source).toList()
        assertTrue(
            "expected the five picker read sites (photo, bg, texture, underlay, brush import), found ${sites.size}",
            sites.size == 5
        )
        val bare = sites.count { match ->
            // A site is "bare" when no `bytes == null` guard exists between the
            // openInputStream call and the end of its callback block.
            val windowEnd = minOf(source.length, match.range.first + 900)
            val window = source.substring(match.range.first, windowEnd)
            !window.contains("bytes == null")
        }
        assertEquals(
            "no picker may silently no-op on a null stream (cloud provider offline etc.)",
            0,
            bare
        )
    }

    @Test
    fun `all four media pickers surface the policy text`() {
        val source = editorScreen()
        assertEquals(
            "exactly four policy-routed picker snackbars expected",
            4,
            Regex("UiFailureTextPolicy\\.pickerSourceUnavailable\\(").findAll(source).count()
        )
        UiFailureTextPolicy.PickerSourceKind.values().forEach { kind ->
            assertTrue(
                "picker $kind must surface its fixed snackbar through UiFailureTextPolicy",
                source.contains("UiFailureTextPolicy.PickerSourceKind.$kind")
            )
        }
    }

    @Test
    fun `each null-guard shows the snackbar before any success path`() {
        val source = editorScreen()
        listOf(
            "PickerSourceKind.CUSTOM_BACKGROUND",
            "PickerSourceKind.PAPER_TEXTURE",
            "PickerSourceKind.PHOTO_EMBED",
            "PickerSourceKind.REFERENCE_UNDERLAY"
        ).forEach { kind ->
            val idx = source.indexOf(kind)
            assertTrue(kind, idx >= 0)
            val guardStart = source.lastIndexOf("if (bytes == null)", idx)
            val successStart = source.indexOf("if (bytes != null)", idx)
            assertTrue(
                "$kind must sit inside a bytes == null guard",
                guardStart in 0 until idx
            )
            assertTrue(
                "$kind guard must run BEFORE the success path",
                successStart < 0 || guardStart < successStart
            )
            val guardBody = source.substring(guardStart, successStart.takeIf { it > guardStart } ?: guardStart + 400)
            assertTrue("$kind guard must show a snackbar", guardBody.contains("viewModel.showSnackbar("))
        }
    }
}
