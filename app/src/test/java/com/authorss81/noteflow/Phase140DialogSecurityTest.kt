package com.authorss81.noteflow

import com.authorss81.noteflow.services.SecureDialogPolicy
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-b2b1-UI-02 (phase-140) — per-dialog FLAG_SECURE: pure-JVM decision gate +
 * source pins that every content-bearing Compose dialog window carries its own
 * secure flag.
 *
 * The finding: FLAG_SECURE is applied to the ACTIVITY window only, but every
 * Compose `Dialog`/`AlertDialog` is a separate `WindowManager` window that does
 * NOT inherit the activity's flags — so a screencap captures the Command
 * Palette's decrypted note-title list, the OCR dialog's full recognized text,
 * and the MarkdownPreviewScreen / plugin dialogs over an open note, even though
 * the main window is protected.
 */
class Phase140DialogSecurityTest {

    private val helperName = "secureDialogProperties("

    // ---- pure-JVM decision gate ---------------------------------------------

    @Test
    fun `dialog windows are secure for exactly the release builds`() {
        assertFalse(
            "debug builds must stay renderable (emulator streaming mirror)",
            SecureDialogPolicy.dialogWindowsAreSecure(debug = true)
        )
        assertTrue(
            "release builds must carry FLAG_SECURE on dialog windows",
            SecureDialogPolicy.dialogWindowsAreSecure(debug = false)
        )
    }

    @Test
    fun `the compose helper maps the gate to SecureOn or Inherit`() {
        val helper = read("app/src/main/kotlin/com/authorss81/noteflow/ui/components/SecureDialogProperties.kt")
        assertTrue(
            "SecureOn must be applied when the gate says the build is secure",
            helper.contains("SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit")
        )
        assertTrue(
            "the helper must consult the pure-JVM gate",
            helper.contains("SecureDialogPolicy.dialogWindowsAreSecure(") &&
                helper.contains("BuildConfig.DEBUG")
        )
    }

    // ---- finding-listed dialog call sites -----------------------------------

    @Test
    fun `the command palette window carries its own secure flag`() {
        val src = read("app/src/main/kotlin/com/authorss81/noteflow/ui/components/CommandPaletteOverlay.kt")
        assertTrue(
            "CommandPaletteOverlay (decrypted note-title list) must use secureDialogProperties",
            src.contains(helperName)
        )
        assertFalse(
            "the palette must no longer construct DialogProperties directly",
            src.contains("properties = DialogProperties(")
        )
    }

    @Test
    fun `the OCR result dialog carries its own secure flag`() {
        val src = read("app/src/main/kotlin/com/authorss81/noteflow/ui/components/OcrResultDialog.kt")
        assertTrue(
            "OcrResultDialog (full recognized note text) must use secureDialogProperties",
            src.contains(helperName)
        )
    }

    @Test
    fun `the MarkdownPreviewScreen dialogs carry their own secure flag`() {
        val src = read("app/src/main/kotlin/com/authorss81/noteflow/ui/screens/MarkdownPreviewScreen.kt")
        // Transform-confirm AlertDialog + TextToolsDialog + LanguageDetectionDialog.
        val count = countOccurrences(src, helperName)
        assertTrue(
            "all three content dialogs in MarkdownPreviewScreen must be secured (found $count)",
            count >= 3
        )
    }

    @Test
    fun `the phase-15 and phase-16 plugin dialogs over a note carry the flag`() {
        val webSearch = read("app/src/main/kotlin/com/authorss81/noteflow/ui/components/WebSearchDialog.kt")
        assertTrue(
            "WebSearchDialog must use secureDialogProperties",
            webSearch.contains(helperName)
        )
        val phase16 = read("app/src/main/kotlin/com/authorss81/noteflow/ui/components/Phase16PluginDialogs.kt")
        // Dictation + ReadAloud + Translation.
        val count = countOccurrences(phase16, helperName)
        assertTrue(
            "all three phase-16 plugin dialogs must be secured (found $count)",
            count >= 3
        )
    }

    @Test
    fun `the phase-26 plugin dialogs over a note carry the flag`() {
        val phase26 = read("app/src/main/kotlin/com/authorss81/noteflow/ui/components/Phase26PluginDialogs.kt")
        // Dictionary + Weather + UnitConverter + Outline + Citation.
        val count = countOccurrences(phase26, helperName)
        assertTrue(
            "all five phase-26 plugin dialogs must be secured (found $count)",
            count >= 5
        )
    }

    private fun read(relative: String): String {
        val file = File(repoRoot(), relative)
        assertTrue("sanity: $relative exists", file.isFile)
        // Comment-strip so a stale comment can't satisfy a pin.
        return file.readText()
            .replace(Regex("//[^\\n]*"), "")
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var idx = haystack.indexOf(needle)
        while (idx >= 0) {
            count++
            idx = haystack.indexOf(needle, idx + needle.length)
        }
        return count
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile &&
                File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}