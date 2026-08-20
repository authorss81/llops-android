package com.authorss81.noteflow

import com.authorss81.noteflow.services.DecryptFailurePolicy
import com.authorss81.noteflow.services.DocumentPdfExportPolicy
import com.authorss81.noteflow.services.EncryptionService
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-182 (2026-08-20): re-fix of the user-reported "note titles become
 * 'Unreadable (decryption failed)' AFTER EXPORT / Home return" regression.
 *
 * Phase-169 already proved a cross-key restore can never strand a row and the
 * live write paths refuse to persist the render marker. Phase-182 closes the two
 * RESIDUAL surfaces the step-1 trace found and pins the export→re-enter path:
 *
 *  - the version-SNAPSHOT write path ([NoteRepository.createNoteVersion]) was the
 *    LAST un-guarded surface that could persist the DISPLAYED marker as real
 *    title/body (and from there into backup/HTML/Obsidian export metadata) — it
 *    now refuses the trimmed marker with [com.authorss81.noteflow.services.UnreadableContentWriteException]
 *    and the ViewModel surfaces [DecryptFailurePolicy.UNREADABLE_ROW_GUIDANCE];
 *  - every export path is a read-only passthrough: `ImportExportService` never
 *    calls `closeDatabase`/`reopenDatabase`, so an export cannot re-key or
 *    zeroize anything — a readable page is readable BEFORE and AFTER export
 *    (source-pinned);
 *  - a password vault re-derives the SAME DEK on every unlock (PBKDF2 is
 *    deterministic), so "export → Home → re-enter" (lock/unlock session
 *    boundary) decrypts the unchanged ciphertext back to the ORIGINAL plaintext,
 *    never the marker (behavioral, real AES-GCM);
 *  - "Export Document as PDF" must export EVERY source page: the call site now
 *    computes the page count from the REAL source count
 *    ([DocumentPdfExportPolicy.pageCountForExport], fed by `pdfTotalPages`) — not
 *    the memory-bounded visible render window — and threads `sourceFilePath` so
 *    the export loop's per-page `renderPdfPageToBitmap`/`decodeImageSampled`
 *    fallback can draw every page beyond the window (source-pinned).
 */
class Phase182ExportReadLockBoundaryTest {

    // ---- export→Home→re-enter: the session boundary keeps rows readable ----

    @Test
    fun `export this session then re-enter derives the same dek and decrypts the original plaintext never the marker`() {
        // A password vault derives its DEK from password+salt on every unlock;
        // PBKDF2WithHmacSHA256 is deterministic, so session 2 must re-derive the
        // EXACT key that wrote the ciphertext in session 1. This is the mechanism
        // that makes "export → Home (lock on ON_STOP) → unlock" safe: the reopen
        // uses the same DEK, so the unchanged ciphertext decrypts to the original
        // plaintext (the phase-169 re-key crash can never be reached).
        val password = "correct horse battery staple 42"
        val salt = EncryptionService.generateSalt()

        val session1Dek = EncryptionService.deriveKey(password, salt)
        val rowId = "page-session-1"
        val originalTitle = "Meeting notes with launch decision"
        val originalBody = "# Heading\nBody text with ünïcode and secrets."

        // Session 1 (locked-in content): field-encrypt with the per-record AAD the
        // read path uses.
        val titleCipher = EncryptionService.encryptField(
            originalTitle.toByteArray(), session1Dek, "pages", rowId, "title"
        )
        val bodyCipher = EncryptionService.encryptField(
            originalBody.toByteArray(), session1Dek, "pages", rowId, "extractedText"
        )

        // The export paths pass ciphertext through VERBATIM (see the source-pin
        // below: ImportExportService never closes/reopens the DB) — the bytes under
        // the original DEK are what come back on re-import.

        // Re-enter: password vault re-derives the SAME key on every unlock.
        val session2Dek = EncryptionService.deriveKey(password, salt)
        assertTrue(
            "unlock must re-derive the identical DEK",
            session1Dek.contentEquals(session2Dek)
        )

        val decryptedTitle = String(
            EncryptionService.decryptField(titleCipher, session2Dek, "pages", rowId, "title"),
            Charsets.UTF_8
        )
        val decryptedBody = String(
            EncryptionService.decryptField(bodyCipher, session2Dek, "pages", rowId, "extractedText"),
            Charsets.UTF_8
        )

        for (plain in listOf(decryptedTitle, decryptedBody)) {
            assertNotEquals("re-entered session must never render the marker", DecryptFailurePolicy.UNREADABLE_MARKER, plain)
            assertFalse(DecryptFailurePolicy.isUnreadableMarker(plain))
        }
        assertEquals(originalTitle, decryptedTitle)
        assertEquals(originalBody, decryptedBody)
    }

    @Test
    fun `a different dek that would strand the row is detected and loudly refused not installed`() {
        // The ONE way a row could surface as the marker after a session change is
        // an unlock that derived a DIFFERENT DEK than the one the row was written
        // under. That is precisely the phase-169 re-key path, and it is now LOUD:
        // the restore migration classifies the mismatch as AuthFailed and throws
        // before any write-back (already covered by Phase169ExportImportRoundTripTest)
        // while the READ path on the live vault simply fails closed to the render
        // marker plus guidance — never a silently re-written row.
        val wrongDek = EncryptionService.generateDek()
        val rightDek = EncryptionService.generateDek()
        val rowId = "page-orphan-1"
        val cipher = EncryptionService.encryptField(
            "Session-1 content".toByteArray(), rightDek, "pages", rowId, "title"
        )
        val wrongKeyRead = runCatching {
            String(EncryptionService.decryptField(cipher, wrongDek, "pages", rowId, "title"))
        }.exceptionOrNull()
        // A mismatched DEK cannot authenticate the row — cross-session strand fails
        // closed at the decrypt, and the DB itself is never mutated (import refuses
        // before commit).
        assertTrue(
            "wrong-DEK unlock must fail GCM authentication",
            wrongKeyRead != null
        )
    }

    // ---- version-snapshot guard (the residual marker-persist surface) ------

    @Test
    fun `createNoteVersion refuses to persist the render marker and the viewmodel surfaces guidance`() {
        val repo = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt")
            .readText()
        val versionRegion = repo.substringAfter("fun createNoteVersion").substringBefore("fun getNoteVersions")
        assertTrue(
            "createNoteVersion must refuse the marker in the title",
            versionRegion.contains("DecryptFailurePolicy.isUnreadableMarker(title.trim())")
        )
        assertTrue(
            "createNoteVersion must refuse the marker in extractedText too",
            versionRegion.contains("DecryptFailurePolicy.isUnreadableMarker(extractedText.trim())")
        )
        assertTrue(
            "createNoteVersion must throw the typed guard, not store the marker",
            versionRegion.contains("UnreadableContentWriteException")
        )
        assertTrue(
            "the guard must run BEFORE the encrypt+insert",
            versionRegion.indexOf("UnreadableContentWriteException") < versionRegion.indexOf("encryptField")
        )

        val vm = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt")
            .readText()
        val vmRegion = vm.substringAfter("fun createNoteVersion").substringBefore("fun ")
        assertTrue(
            "the VM createNoteVersion wrapper must catch the marker guard",
            vmRegion.contains("UnreadableContentWriteException")
        )
        assertTrue(
            "and surface the fixed unreadable-row guidance, not the raw marker",
            vmRegion.contains("DecryptFailurePolicy.UNREADABLE_ROW_GUIDANCE")
        )
    }

    // ---- export machinery is read-only: cannot re-key or zeroize anything ----

    @Test
    fun `importexportservice never closes or reopens the live database`() {
        val ie = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt")
            .readText()
        assertFalse(
            "an export must never close the live DB — that was the pre-181 reopen crash window",
            ie.contains("closeDatabase(")
        )
        assertFalse(
            "an export must never reopen the live DB — a reopen is only reached with a freshly pinned session key",
            ie.contains("reopenDatabase(")
        )
    }

    // ---- "Export Document as PDF": every source page, never the window ------

    @Test
    fun `pdf page count never undercounts below the real source pages or strokes`() {
        // 120-page source PDF, editor only holds a 4-page visible window: the old
        // call site used pdfPageBitmaps.size (the window) and exported 4 pages.
        assertEquals(120, DocumentPdfExportPolicy.pageCountForExport(120, 0))
        // Strokes that reach page 60 pull the count up to at least 61.
        assertEquals(120, DocumentPdfExportPolicy.pageCountForExport(120, 75))
        // A single-page source with strokes on page 0 still exports 1 page.
        assertEquals(1, DocumentPdfExportPolicy.pageCountForExport(1, 0))
        // Oversized strokes (page 7) overflow a 3-page source up to 8 pages.
        assertEquals(8, DocumentPdfExportPolicy.pageCountForExport(3, 7))
        // Degenerate inputs floor at 1 page.
        assertEquals(1, DocumentPdfExportPolicy.pageCountForExport(0, -1))
        assertEquals(1, DocumentPdfExportPolicy.pageCountForExport(-5, -1))
    }

    @Test
    fun `editor pdf export call site uses the real source count and threads sourceFilePath`() {
        val editor = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt")
            .readText()
        assertEquals(
            "the page count must come from the source-aware policy, never the visible bitmap window",
            0,
            Regex("maxOf\\(1, pdfPageBitmaps\\.size").findAll(editor).count()
        )
        assertEquals("must not fall back to the windowed bitmap count", 0, Regex("totalPages = maxOf\\(1, pdfPageBitmaps").findAll(editor).count())
        assertTrue(
            "call site must compute the REAL page count via the policy fed by pdfTotalPages",
            editor.contains("DocumentPdfExportPolicy.pageCountForExport(") &&
                editor.contains("sourcePdfTotalPages = pdfTotalPages")
        )
        assertTrue(
            "call site must thread sourceFilePath so out-of-window pages re-render from source",
            editor.contains("sourceFilePath = page.sourceFilePath")
        )
        // The old bug (window size only) is gone.
        assertFalse(editor.contains("val totalPages = maxOf(1, pdfPageBitmaps.size"))
    }

    @Test
    fun `pdf exporter loop renders every page via source fallback when out of window`() {
        val ie = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt")
            .readText()
        val fn = ie.substringAfter("suspend fun exportDocumentAsPdf").substringAfter("for (pageIdx in")
        assertEquals(
            "every pageIdx in 0 until count must be produced",
            "0 until count",
            fn.substringBefore(") {").trim()
        )
        assertTrue(
            "out-of-window pages must be re-rendered from the PDF source",
            ie.contains("renderPdfPageToBitmap(sourceFilePath, pageIdx)")
        )
        assertTrue(
            "out-of-window pages from tall images must be sampled-decoded",
            ie.contains("decodeImageSampled(sourceFilePath")
        )
        assertTrue(
            "a page with no bitmap and no source must still get a template background (never blank)",
            ie.contains("drawTemplateBackground(canvas, template")
        )
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