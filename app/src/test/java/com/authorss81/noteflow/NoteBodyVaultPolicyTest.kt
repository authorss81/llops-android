package com.authorss81.noteflow

import com.authorss81.noteflow.services.NoteBodyVaultPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 44 (B1-DB-4): the markdown/text note BODY must live only in the
 * field-encrypted `extractedText` column — a plaintext `.md`/`.txt` companion
 * file under `filesDir/noteflow/imports` is the exact at-rest bypass the
 * finding exploits. These pure-JVM tests pin the policy that keeps bodies out
 * of the plaintext filesystem:
 *
 *  - only "text"-typed pages and `.md`/`.txt` file names classify as note-body
 *    sources (imported PDFs/images and exported artifacts are untouched);
 *  - the DISPLAY body prefers a surviving legacy plaintext file (it was the
 *    pre-fix authority) but coalesces it transiently — the resolution has no
 *    persistence side effects;
 *  - a legacy file actually present for an image/pdf page is never read as a
 *    note body;
 *  - deleting the legacy file kills that source so the encrypted column becomes
 *    the only readable body.
 */
class NoteBodyVaultPolicyTest {

    // B1-AUTH-05 (phase-69): legacy source files may only be read/deleted when
    // they are confined under the app-private imports root. The real files are
    // placed there so the coercion tests exercise the pre-fix (confined) path.
    private val importsRoot: File =
        File(System.getProperty("java.io.tmpdir"), "inkflow-nbp-root-" + java.util.UUID.randomUUID()).apply { mkdirs() }

    @org.junit.After
    fun tearDown() {
        importsRoot.deleteRecursively()
    }

    private fun legacyFile(name: String, content: String): File =
        File(importsRoot, name).apply { writeText(content) }

    // --- isNoteTextBodySource ---

    @Test
    fun `text typed page with a path is a note body source`() {
        assertTrue(
            NoteBodyVaultPolicy.isNoteTextBodySource("/data/data/x/files/noteflow/imports/My_Note.md", "text")
        )
    }

    @Test
    fun `md or txt suffix is a note body source regardless of stored type`() {
        assertTrue(NoteBodyVaultPolicy.isNoteTextBodySource("/imports/My_Note.md", null))
        assertTrue(NoteBodyVaultPolicy.isNoteTextBodySource("/imports/My_Note.TXT", "pdf"))
    }

    @Test
    fun `pdf and image sources are never note body sources`() {
        assertFalse(NoteBodyVaultPolicy.isNoteTextBodySource("/imports/scan.pdf", "pdf"))
        assertFalse(NoteBodyVaultPolicy.isNoteTextBodySource("/imports/photo.jpg", "image"))
        assertFalse(NoteBodyVaultPolicy.isNoteTextBodySource("/imports/layers.psd", "image"))
    }

    @Test
    fun `blank or null path is never a note body source`() {
        assertFalse(NoteBodyVaultPolicy.isNoteTextBodySource(null, "text"))
        assertFalse(NoteBodyVaultPolicy.isNoteTextBodySource("  ", "text"))
    }

    // --- resolveBodyForDisplay ---

    @Test
    fun `existing legacy text file wins over the db column for display`() {
        val file = legacyFile("legacy-note.md", "# legacy body from file")
        try {
            val body = NoteBodyVaultPolicy.resolveBodyForDisplay(
                extractedText = "# stale db body",
                sourceFilePath = file.absolutePath,
                sourceFileType = "text",
                importsRoot = importsRoot
            )
            assertEquals("# legacy body from file", body)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `missing legacy file falls back to the encrypted column`() {
        val body = NoteBodyVaultPolicy.resolveBodyForDisplay(
            extractedText = "# db-only body",
            sourceFilePath = "/no/such/file.md",
            sourceFileType = "text"
        )
        assertEquals("# db-only body", body)
    }

    @Test
    fun `after deleting the legacy file the column is the only visible body`() {
        val file = legacyFile("legacy-delete.md", "# legacy text")
        try {
            NoteBodyVaultPolicy.deleteLegacyNoteTextBody(file.absolutePath, "text", importsRoot)
            val body = NoteBodyVaultPolicy.resolveBodyForDisplay(
                extractedText = "# encrypted body",
                sourceFilePath = file.absolutePath,
                sourceFileType = "text",
                importsRoot = importsRoot
            )
            assertEquals("# encrypted body", body)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `image page body is the column, never the image file`() {
        val image = File.createTempFile("photo", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        try {
            val body = NoteBodyVaultPolicy.resolveBodyForDisplay(
                extractedText = "Image: photo",
                sourceFilePath = image.absolutePath,
                sourceFileType = "image"
            )
            assertEquals("Image: photo", body)
        } finally {
            image.delete()
        }
    }

    @Test
    fun `blank everything yields empty string`() {
        assertEquals("", NoteBodyVaultPolicy.resolveBodyForDisplay(null, null, null))
    }

    // --- deleteLegacyNoteTextBody ---

    @Test
    fun `delete removes a surviving legacy note body file and reports its path`() {
        val file = legacyFile("legacy-note.md", "secret body")
        try {
            val deleted = NoteBodyVaultPolicy.deleteLegacyNoteTextBody(file.absolutePath, "text", importsRoot)
            assertEquals(file.absolutePath, deleted)
            assertFalse(file.exists())
        } finally {
            file.delete()
        }
    }

    @Test
    fun `delete never touches a pdf or image source`() {
        val image = File.createTempFile("precious", ".jpg")
        try {
            assertNull(
                NoteBodyVaultPolicy.deleteLegacyNoteTextBody(image.absolutePath, "image")
            )
            assertTrue(image.exists())
        } finally {
            image.delete()
        }
    }

    @Test
    fun `delete on a text page with no surviving file reports null`() {
        assertNull(NoteBodyVaultPolicy.deleteLegacyNoteTextBody("/no/such/file.md", "text"))
        assertNull(NoteBodyVaultPolicy.deleteLegacyNoteTextBody(null, "text"))
    }
}