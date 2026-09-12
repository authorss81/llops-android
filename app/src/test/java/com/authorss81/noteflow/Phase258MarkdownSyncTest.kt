package com.authorss81.noteflow

import com.authorss81.noteflow.services.MarkdownSyncPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 258 — the whole-document markdown editor's external-content sync policy
 * and at-caret insertion, plus wiring pins that enforce the single-editor
 * decision (WholeMarkdownEditor is THE markdown editor; HybridMarkdownEditor is
 * deleted and the preview screen's sync/guard/insert paths route through
 * [MarkdownSyncPolicy]).
 *
 * The policy class is pure JVM (`services/`), so all decision logic is pinned
 * without an emulator (mirroring the ReaderModePolicy / WikiSuggestionPolicy
 * pattern).
 */
class Phase258MarkdownSyncTest {

    // ---- MarkdownSyncPolicy.shouldAdoptExternal ------------------------------

    @Test
    fun `pristine editor adopts a newer external value in any direction`() {
        // The async decrypted-body read landing after an empty first composition.
        assertTrue(MarkdownSyncPolicy.shouldAdoptExternal("", "", "populated"))
        // A re-read / version restore replacing an unchanged-on-screen baseline.
        assertTrue(MarkdownSyncPolicy.shouldAdoptExternal("old", "old", "new"))
        // ALL-direction heal (the old empty→non-empty-only guard refused this):
        // a pristine editor adopts an arriving EMPTY value over a stale baseline.
        assertTrue(MarkdownSyncPolicy.shouldAdoptExternal("abc", "abc", ""))
    }

    @Test
    fun `no-change external values are never adopted`() {
        assertFalse(MarkdownSyncPolicy.shouldAdoptExternal("", "", ""))
        assertFalse(MarkdownSyncPolicy.shouldAdoptExternal("same", "same", "same"))
        // Empty arriving at an already-empty committed baseline is a no-op echo.
        assertFalse(MarkdownSyncPolicy.shouldAdoptExternal("", "baseline", "baseline"))
    }

    @Test
    fun `a dirty editor is never clobbered by a late external echo`() {
        // User already typed before the DB echo landed: contentText != savedContent
        // (the version token). The late external value must NOT replace the edits.
        assertFalse(MarkdownSyncPolicy.shouldAdoptExternal("my typed note", "", "my note"))
        assertFalse(MarkdownSyncPolicy.shouldAdoptExternal("typed", "old", "newer"))
    }

    // ---- MarkdownSyncPolicy.insertAtCaret -----------------------------------

    @Test
    fun `insertAtCaret splices at the caret and reports the caret after`() {
        val r = MarkdownSyncPolicy.insertAtCaret("abc", 1, "X")
        assertEquals("aXbc", r.text)
        assertEquals(2, r.caretAfter)
    }

    @Test
    fun `insertAtCaret appends when the offset is unknown or at end - legacy semantics`() {
        val atNull = MarkdownSyncPolicy.insertAtCaret("abc", null, "X")
        assertEquals("abcX", atNull.text)
        assertEquals(4, atNull.caretAfter)
        val atEnd = MarkdownSyncPolicy.insertAtCaret("abc", 3, "X")
        assertEquals("abcX", atEnd.text)
        assertEquals(4, atEnd.caretAfter)
    }

    @Test
    fun `insertAtCaret clamps out-of-range offsets without corrupting the document`() {
        val below = MarkdownSyncPolicy.insertAtCaret("abc", -5, "X")
        assertEquals("Xabc", below.text)
        assertEquals(1, below.caretAfter)
        val above = MarkdownSyncPolicy.insertAtCaret("abc", 999, "X")
        assertEquals("abcX", above.text)
        assertEquals(4, above.caretAfter)
    }

    @Test
    fun `insertAtCaret never splits an astral surrogate pair`() {
        val text = "a\uD83D\uDE00c" // 'a' + emoji + 'c'
        // Caret right before the emoji (offset 1): the pair stays intact.
        val before = MarkdownSyncPolicy.insertAtCaret(text, 1, "X")
        assertEquals("aX\uD83D\uDE00c", before.text)
        assertEquals(2, before.caretAfter)
        // Caret INSIDE the pair (offset 2, between the surrogate halves): the
        // splice is pushed past the whole pair so the emoji is never torn apart.
        val midPair = MarkdownSyncPolicy.insertAtCaret(text, 2, "X")
        assertEquals("a\uD83D\uDE00Xc", midPair.text)
        assertEquals(4, midPair.caretAfter)
        // Caret after the pair (end of text): plain append.
        val after = MarkdownSyncPolicy.insertAtCaret(text, 4, "X")
        assertEquals("a\uD83D\uDE00cX", after.text)
        assertEquals(5, after.caretAfter)
    }

    // ---- single-editor wiring pins -------------------------------------------

    @Test
    fun `WholeMarkdownEditor reports caret and accepts a post-insert reposition`() {
        val editor = sourceFile("ui/components/markdown/WholeMarkdownEditor.kt")
        assertTrue("the editor is the shipped whole-document surface",
            editor.contains("fun WholeMarkdownEditor("))
        assertTrue("the editor reports its live caret",
            editor.contains("onSelectionChanged"))
        assertTrue("the editor accepts a one-shot caret reposition",
            editor.contains("selectionOverride"))
        assertTrue("the editor keeps a TextFieldValue so the caret is observable",
            editor.contains("TextFieldValue("))
    }

    @Test
    fun `HybridMarkdownEditor is deleted - one editor ships, not two`() {
        val hybrid = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/markdown/HybridMarkdownEditor.kt")
        assertFalse("HybridMarkdownEditor.kt must not exist in app/src/main", hybrid.exists())
        val preview = sourceFile("ui/screens/MarkdownPreviewScreen.kt")
        assertFalse("the preview screen no longer imports the dead hybrid editor",
            preview.contains("import com.authorss81.noteflow.ui.components.markdown.HybridMarkdownEditor"))
    }

    @Test
    fun `the preview screen routes sync, guard and inserts through MarkdownSyncPolicy`() {
        val preview = sourceFile("ui/screens/MarkdownPreviewScreen.kt")
        assertTrue("the bidirectional adopt guard is wired",
            preview.contains("MarkdownSyncPolicy.shouldAdoptExternal"))
        assertTrue("the flush-on-dispose is keyed by page so a page switch flushes the old page",
            preview.contains("DisposableEffect(page.id)"))
        assertTrue("slash inserts splice at the caret",
            preview.contains("MarkdownSyncPolicy.insertAtCaret"))
        assertTrue("the version-token baseline is updated on adopt",
            preview.contains("savedContent = initialContent"))
    }

    // ---- helpers ------------------------------------------------------------

    private fun sourceFile(relative: String): String {
        val file = File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/$relative"
        )
        assertTrue("$relative must exist for the wiring pin", file.isFile)
        return file.readText()
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