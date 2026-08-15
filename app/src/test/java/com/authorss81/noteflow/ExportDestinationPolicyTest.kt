package com.authorss81.noteflow

import com.authorss81.noteflow.services.ExportDestinationPolicy
import com.authorss81.noteflow.services.ExportDestinationPolicy.ExportKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-PLAT-3 (phase-59): pure-JVM behavior pins for the export-destination
 * decision table ([ExportDestinationPolicy]). These are the rules SaFExporter
 * applies — MIME types, plaintext-warning classification, suggested file names
 * and the post-export guidance.
 */
class ExportDestinationPolicyTest {

    private val allKinds = ExportKind.values().toList()

    // ---- unclassified rule: every export needs a user-picked destination ----

    @Test
    fun `every export kind requires a user-picked destination`() {
        for (kind in allKinds) {
            assertTrue("$kind must always require the SAF picker", ExportDestinationPolicy.requiresUserPickedDestination(kind))
        }
    }

    @Test
    fun `every export kind has a non-blank MIME type`() {
        for (kind in allKinds) {
            assertTrue("$kind must have a MIME type", ExportDestinationPolicy.mimeType(kind).isNotBlank())
        }
    }

    // ---- MIME classification ------------------------------------------------

    @Test
    fun `whole vault exports are zip containers`() {
        for (kind in listOf(ExportKind.OBSIDIAN_VAULT, ExportKind.HTML_SITE, ExportKind.VAULT_ZIP)) {
            assertEquals("application/zip", ExportDestinationPolicy.mimeType(kind))
        }
    }

    @Test
    fun `single page renders carry their image or pdf MIME types`() {
        assertEquals("image/png", ExportDestinationPolicy.mimeType(ExportKind.PAGE_PNG))
        assertEquals("image/webp", ExportDestinationPolicy.mimeType(ExportKind.PAGE_WEBP))
        assertEquals("application/pdf", ExportDestinationPolicy.mimeType(ExportKind.PAGE_PDF))
        assertEquals("application/pdf", ExportDestinationPolicy.mimeType(ExportKind.DOCUMENT_PDF))
        assertEquals("image/vnd.adobe.photoshop", ExportDestinationPolicy.mimeType(ExportKind.LAYERED_PSD))
        assertEquals("text/html", ExportDestinationPolicy.mimeType(ExportKind.NOTE_HTML))
        assertEquals("application/octet-stream", ExportDestinationPolicy.mimeType(ExportKind.ENCRYPTED_BACKUP))
    }

    // ---- plaintext classification -------------------------------------------

    @Test
    fun `only whole-vault plaintext kinds require the pre-export warning`() {
        for (kind in listOf(ExportKind.OBSIDIAN_VAULT, ExportKind.HTML_SITE, ExportKind.VAULT_ZIP)) {
            assertTrue("$kind exposes every note as plaintext and MUST warn", ExportDestinationPolicy.requiresPlaintextWarning(kind))
        }
        for (kind in listOf(
            ExportKind.ENCRYPTED_BACKUP,
            ExportKind.PAGE_PNG,
            ExportKind.PAGE_WEBP,
            ExportKind.PAGE_PDF,
            ExportKind.DOCUMENT_PDF,
            ExportKind.NOTE_HTML,
            ExportKind.LAYERED_PSD
        )) {
            assertFalse("$kind must not gate on the whole-vault warning", ExportDestinationPolicy.requiresPlaintextWarning(kind))
        }
    }

    @Test
    fun `everything except the encrypted backup is readable plaintext`() {
        assertFalse(ExportDestinationPolicy.isUnencrypted(ExportKind.ENCRYPTED_BACKUP))
        for (kind in allKinds.filter { it != ExportKind.ENCRYPTED_BACKUP }) {
            assertTrue("$kind carries readable content and must be flagged unencrypted", ExportDestinationPolicy.isUnencrypted(kind))
        }
    }

    // ---- suggested file names ------------------------------------------------

    @Test
    fun `generated names pass through verbatim when non-blank`() {
        for (kind in allKinds) {
            assertEquals("out_${kind.name}.bin", ExportDestinationPolicy.suggestedFileName(kind, "out_${kind.name}.bin"))
        }
    }

    @Test
    fun `blank generated names fall back to a sensible default per kind`() {
        assertEquals("noteflow_backup.noteflow", ExportDestinationPolicy.suggestedFileName(ExportKind.ENCRYPTED_BACKUP, "  "))
        assertEquals("SmoothNotes_Vault_Obsidian.zip", ExportDestinationPolicy.suggestedFileName(ExportKind.OBSIDIAN_VAULT, ""))
        assertEquals("SmoothNotes_Site_HTML.zip", ExportDestinationPolicy.suggestedFileName(ExportKind.HTML_SITE, ""))
        assertEquals("SmoothNotes_Vault.zip", ExportDestinationPolicy.suggestedFileName(ExportKind.VAULT_ZIP, ""))
        assertEquals("page_export.png", ExportDestinationPolicy.suggestedFileName(ExportKind.PAGE_PNG, ""))
        assertEquals("page_export.webp", ExportDestinationPolicy.suggestedFileName(ExportKind.PAGE_WEBP, ""))
        assertEquals("page_export.pdf", ExportDestinationPolicy.suggestedFileName(ExportKind.PAGE_PDF, ""))
        assertEquals("document_export.pdf", ExportDestinationPolicy.suggestedFileName(ExportKind.DOCUMENT_PDF, ""))
        assertEquals("note_export.html", ExportDestinationPolicy.suggestedFileName(ExportKind.NOTE_HTML, ""))
        assertEquals("canvas_export.psd", ExportDestinationPolicy.suggestedFileName(ExportKind.LAYERED_PSD, ""))
    }

    // ---- the bold warning + post-export guidance ------------------------------

    @Test
    fun `the pre-export warning explicitly states plaintext and advises transfer-then-delete`() {
        assertTrue(ExportDestinationPolicy.PLAINTEXT_WARNING_TITLE.isNotBlank())
        assertTrue(
            "the body must warn the content is unencrypted/readable",
            ExportDestinationPolicy.PLAINTEXT_WARNING_BODY.contains("PLAINTEXT") ||
                ExportDestinationPolicy.PLAINTEXT_WARNING_BODY.contains("readable")
        )
        assertTrue(
            "the body must advise deleting the on-device copy after transfer",
            ExportDestinationPolicy.PLAINTEXT_WARNING_BODY.contains("DELETE")
        )
    }

    @Test
    fun `post-export guidance warns for plaintext kinds and reassures for the encrypted backup`() {
        assertTrue(ExportDestinationPolicy.postExportGuidance(ExportKind.ENCRYPTED_BACKUP).contains("Encrypted"))
        for (kind in listOf(ExportKind.OBSIDIAN_VAULT, ExportKind.HTML_SITE, ExportKind.VAULT_ZIP)) {
            val guidance = ExportDestinationPolicy.postExportGuidance(kind)
            assertTrue("$kind guidance must say the copy is NOT encrypted", guidance.contains("NOT encrypted"))
            assertTrue("$kind guidance must advise deleting the on-device copy", guidance.contains("delete"))
        }
    }
}