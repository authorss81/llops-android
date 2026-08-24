package com.authorss81.noteflow

import com.authorss81.noteflow.services.UiFailureTextPolicy
import com.authorss81.noteflow.services.VaultSnapshotCopyPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 202 — Bug-Fix Batch: Mirror + Import + Backup + FD leak (audit
 * `docs/report-2026-08-24.md` §6).
 *
 * 1. Mirror only page 0 — the page-local bitmap-cache recording passed the
 *    LOCAL mirror centre while [com.authorss81.noteflow.services.SymmetryHelper.mirrorPoint]
 *    ran on RAW WORLD stroke points, so HORIZONTAL/RADIAL mirrors reflected
 *    about the PAGE-0 axis and landed off-bitmap on every later page. Fix:
 *    world centre at both cache-recording sites (math regression pins live in
 *    [SymmetryHelperTest]).
 * 2. Import blank / swallowed failures — corrupt-PDF page counts no longer
 *    degrade to a silent blank page ([ImportExportService.getPdfPageCount]
 *    throws), one malformed entry can no longer abort the whole import run,
 *    an auto-lock before a file's turn skips it with a FIXED notice, and the
 *    split-into-pages path rasterizes each PDF page into its OWN standalone
 *    slice (pre-fix every "Page N" note rendered PDF slice 0 because no
 *    per-page index exists on the row).
 * 3. Backup locked/rotation — exportBackup snapshot-copies the handed DEK so
 *    a mid-export auto-lock (VaultKeyHolder.zeroize fills the live array in
 *    place) cannot poison the prunes/encryption; VaultSnapshotCopyPolicy got
 *    bounded inter-attempt backoff and a 5-attempt budget.
 * 4. FD leaks — PdfRenderer/ParcelFileDescriptor/Page close via use{} on every
 *    path.
 *
 * Pure JVM: real policy behaviour where possible, source-level wiring pins
 * elsewhere (the render/import surfaces need Android framework types).
 */
class Phase202BugFixBatchTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------------------------------------------------------------
    // 3. backup — VaultSnapshotCopyPolicy retry/backoff behaviour
    // ---------------------------------------------------------------------

    @Test
    fun `the verify budget is now five attempts with a real default backoff`() {
        assertEquals("phase-202 raised the racing-writer budget 3 -> 5", 5, VaultSnapshotCopyPolicy.MAX_VERIFY_ATTEMPTS)
        assertTrue(
            "the default backoff must give a ~500ms autosave burst room to settle",
            VaultSnapshotCopyPolicy.DEFAULT_RETRY_BACKOFF_MILLIS >= 100L
        )
    }

    @Test
    fun `a perpetually-mutating source still fails closed after exactly the new budget`() {
        val dir = tmp.newFolder()
        val src = File(dir, "noteflow.sqlite")
        src.writeBytes(ByteArray(2048) { ((it * 7) and 0x7f).toByte() })
        val dest = File(dir, "snapshot.sqlite")

        var copyCalls = 0
        val alwaysRacing = VaultSnapshotCopyPolicy.DbCopy { s, d ->
            copyCalls++
            s.writeBytes(s.readBytes().plus(ByteArray(1) { 0x01 }))
            s.copyTo(d, overwrite = true)
        }

        val ok = VaultSnapshotCopyPolicy.checkpointThenCopy(
            src,
            dest,
            copy = alwaysRacing,
            retryBackoffMillis = 0L // keep the test fast; backoff itself is pinned above
        )

        assertFalse("exhaustion must still fail closed", ok)
        assertEquals(VaultSnapshotCopyPolicy.MAX_VERIFY_ATTEMPTS.toLong(), copyCalls.toLong())
        assertFalse(dest.exists())
    }

    @Test
    fun `a writer that settles between attempts succeeds within the larger budget`() {
        val dir = tmp.newFolder()
        val src = File(dir, "noteflow.sqlite")
        src.writeBytes(ByteArray(8192) { ((it * 13) and 0x7f).toByte() })
        val dest = File(dir, "snapshot.sqlite")

        var copyCalls = 0
        val settlesLate = VaultSnapshotCopyPolicy.DbCopy { s, d ->
            copyCalls++
            if (copyCalls <= 4) {
                // Simulate the ~500ms autosave burst holding the DB busy for the
                // first FOUR attempts — the pre-fix 3-attempt budget failed here.
                s.writeBytes(s.readBytes().plus(ByteArray(1) { 0x02 }))
            }
            s.copyTo(d, overwrite = true)
        }

        val ok = VaultSnapshotCopyPolicy.checkpointThenCopy(
            src,
            dest,
            copy = settlesLate,
            retryBackoffMillis = 0L
        )

        assertTrue(
            "attempt #5 must succeed once the writer settled — pre-fix (3 tries) could not",
            ok && copyCalls == 5
        )
    }

    // ---------------------------------------------------------------------
    // 2. import — fixed-text classification for the new skip reasons
    // ---------------------------------------------------------------------

    @Test
    fun `unreadable pdf failures classify to the fixed unreadable-pdf text`() {
        val msg = UiFailureTextPolicy.importSkippedMessage(
            com.authorss81.noteflow.services.ImportExportService.PdfImportException(
                "PDF import failed: the document appears corrupted or is password-protected."
            )
        )
        assertEquals(UiFailureTextPolicy.IMPORT_PDF_UNREADABLE_TEXT, msg)
        assertFalse(msg.contains("PDF import failed"))
    }

    @Test
    fun `locked-vault imports classify to the fixed locked text`() {
        val msg = UiFailureTextPolicy.importSkippedMessage(IllegalStateException("Vault is locked — page not created"))
        assertEquals(UiFailureTextPolicy.IMPORT_LOCKED_TEXT, msg)
    }

    // ---------------------------------------------------------------------
    // source-level wiring pins
    // ---------------------------------------------------------------------

    private fun sourceFile(relative: String): String {
        val file = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative")
        assertTrue("$relative must exist", file.isFile)
        return codeOnly(file.readText())
    }

    /** Source with comment lines removed so the pins never trip on their own docs. */
    private fun codeOnly(raw: String): String =
        raw.lineSequence()
            .filterNot { line ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("*/")
            }
            .joinToString("\n")

    private fun repoRoot(): File {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) return dir
            dir = dir.parentFile ?: dir
        }
        return dir
    }

    // ---- 1. mirror -----------------------------------------------------

    @Test
    fun `symmetry mirror stays in WORLD space — baked at capture, never re-mirrored at render`() {
        val canvas = sourceFile("ui/components/AnnotationCanvas.kt")
        // Phase 203: the view-time committed-stroke mirror is GONE — the old
        // call forms (world-centre AND local-centre) must not resurrect.
        assertFalse(
            "committed strokes must never be re-mirrored at render time",
            canvas.contains("drawStrokeWithSymmetry")
        )
        assertFalse(
            "the pre-fix local-centre form must stay gone (mirror only worked on page 0)",
            canvas.contains("symmetryCenterX, symmetryCenterY - pageTopY)")
        )
        // The capture-time twin bake freezes the WORLD axis centre the live
        // preview used for the gesture (page-anchored via calculatePageYOffset),
        // so twins land inside their own page slab on every page >= 1.
        assertTrue(
            "the commit site must freeze the world centre from calculatePageYOffset",
            canvas.contains("symmetryCenterFor(size.width.toFloat(), calculatePageYOffset(targetPage))")
        )
        // The paginated renderer still resolves the per-page WORLD centre for
        // the LIVE preview mirror only.
        assertTrue(canvas.contains("symmetryCenterY = symmetryCenterFor(size.width, pageTopY).y"))
    }

    // ---- 2. import -----------------------------------------------------

    @Test
    fun `pdf open failures propagate instead of degrading to a silent blank page`() {
        val svc = sourceFile("services/ImportExportService.kt")
        assertTrue(
            "PdfImportException must exist and be an IOException subclass",
            svc.contains("class PdfImportException(message: String) : java.io.IOException(message)")
        )
        val countFn = svc.substringAfter("fun getPdfPageCount(").substringBefore("fun renderPdfPageToBitmap(")
        assertTrue(countFn.contains("throw PdfImportException("))
    }

    @Test
    fun `split import rasterizes each page to its own standalone slice`() {
        val home = sourceFile("ui/screens/HomeScreen.kt")
        val splitRegion = home.substringAfter("if (type == \"pdf\" && pageCount > 1 && importAsSeparatePages)")
        assertTrue(
            "each split page must be rendered to its own PNG (correct slices without a schema change)",
            splitRegion.contains("renderPdfPageToPngFile(context, path!!, i, baseTitle)")
        )
        assertTrue(
            "the created page must be a standalone image slice, not the shared multi-page pdf",
            splitRegion.contains("sourceFileType = \"image\"")
        )
        assertTrue(splitRegion.contains("minOf(pageCount, ImportExportService.PDF_SPLIT_MAX_PAGES)"))
        assertTrue(
            "the consumed source pdf must be deleted once its slices committed",
            splitRegion.contains("runCatching { File(path!!).delete() }")
        )
    }

    @Test
    fun `one malformed entry or an early auto-lock skips honestly instead of killing the run`() {
        val home = sourceFile("ui/screens/HomeScreen.kt")
        val loop = home.substringAfter("fun processImportedUris(").substringBefore("val filePickerLauncher")
        assertTrue(
            "a lock landing before a file's turn must SKIP it with the fixed notice",
            loop.contains("viewModel.repository.encryptionKey == null") &&
                loop.contains("UiFailureTextPolicy.IMPORT_LOCKED_TEXT")
        )
        assertTrue(loop.contains("catch (e: ImportExportService.PdfImportException)"))
        assertTrue(loop.contains("orphanRun.sweepOrphans()"))
        // Cancellation must NEVER be converted into a skip notice.
        val catchOrder = listOf(
            loop.indexOf("catch (e: ImportExportService.PdfImportException)"),
            loop.indexOf("catch (e: kotlinx.coroutines.CancellationException)"),
            loop.lastIndexOf("catch (e: Exception)")
        )
        assertTrue(catchOrder.all { it > 0 } && catchOrder == catchOrder.sorted())
        assertTrue(loop.contains("throw e"))
    }

    // ---- 3. backup -----------------------------------------------------

    @Test
    fun `exportBackup snapshots the handed DEK so a mid-export lock cannot poison it`() {
        val svc = sourceFile("services/ImportExportService.kt")
        val entry = svc.substringAfter("suspend fun exportBackup(").substringBefore("private fun exportBackupInternal")
        assertTrue(
            "entry point must pin a COPY of the live handed-in array",
            entry.contains("vaultDek: ByteArray?") && entry.contains("val key = vaultDek?.copyOf()")
        )
        assertTrue(entry.contains("ExportSessionPolicy.zeroize(key)"))
        val internal = svc.substringAfter("private suspend fun exportBackupInternal")
            .substringBefore("private fun copyWithLimit")
        assertTrue(
            "the whole export body (prunes + encryption) must run on the pinned copy",
            internal.contains("ExportSessionPolicy.pinnedPruneDek(key) { VaultKeyHolder.dek }") &&
                internal.contains("encryptAad(key, wrapKey") &&
                !internal.contains("vaultDek")
        )
    }

    @Test
    fun `snapshot copy policy spaces its retries with a bounded backoff`() {
        val policy = sourceFile("services/VaultSnapshotCopyPolicy.kt")
        val fn = policy.substringAfter("fun checkpointThenCopy(")
        assertTrue(fn.contains("retryBackoffMillis"))
        assertTrue(fn.contains("Thread.sleep(retryBackoffMillis)"))
        // The pause only applies BETWEEN attempts, never before the first read.
        val sleepIdx = fn.indexOf("Thread.sleep(retryBackoffMillis)")
        val attemptsIncIdx = fn.indexOf("attempts++")
        assertTrue(sleepIdx in 0 until attemptsIncIdx)
    }

    // ---- 4. FD leaks -----------------------------------------------------

    @Test
    fun `every remaining PdfRenderer open closes through use`() {
        val svc = sourceFile("services/ImportExportService.kt")
        val editor = sourceFile("ui/screens/EditorScreen.kt")
        val extractor = sourceFile("services/DocumentTextExtractor.kt")
        for (source in listOf(svc, editor, extractor)) {
            assertFalse(
                "sequential renderer.close()/pfd.close() pattern must be gone",
                source.contains("renderer.close()\n")
            )
            assertTrue(source.contains(".use { pfd ->"))
        }
        val editorRender = editor.substringAfter("private fun renderPdfPageToRawBitmap(")
            .substringBefore("decodeBoundedBitmap(")
        assertTrue(editorRender.contains(".use { pdfPage ->"))
        val home = sourceFile("ui/screens/HomeScreen.kt")
        val landscape = home.substringAfter("landscapeFormat = false").substringBefore("Triple(extracted, count, landscapeFormat)")
        assertTrue(landscape.contains(".use { pfd ->") && landscape.contains(".use { renderer ->"))
    }
}
