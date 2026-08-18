package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-B1P-02 / R2-B1P-03 / R2-b2b3-LOG-04 (phase-141) — export/share hygiene
 * source pins.
 *
 *  - R2-B1P-02: SaFExporter must route EVERY picker outcome through the
 *    pure-JVM [com.authorss81.noteflow.services.ExportStagingPolicy] decision
 *    table (delivered → DELETE, copy-failed → KEEP, cancel/no-data → DELETE), and
 *    the plaintext-warning consent dismissals must delete the staged file too.
 *  - R2-B1P-03: the Export Engine share must be launched THROUGH
 *    `Intent.createChooser(...)` so the target is always user-chosen, and the
 *    staging file must be deleted once the chooser dismisses.
 *  - R2-b2b3-LOG-04: the share subject must be the generic "Exported note" —
 *    never the note title / filename-derived subject (`EXTRA_SUBJECT, file.name`
 *    is banned repo-wide).
 */
class Phase141ExportHygieneTest {

    private val mainSourceRoot by lazy { File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow") }

    private fun allMainSources(): String =
        mainSourceRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .joinToString("\n") { it.readText() }

    // ---- R2-B1P-02: SaFExporter cleans staging on EVERY outcome ----------------

    @Test
    fun `SaFExporter routes every picker outcome through the staging policy`() {
        val saf = File(mainSourceRoot, "ui/components/SaFExporter.kt").readText()
        assertTrue(
            "the picker callback must consult the pure-JVM decision table",
            saf.contains("ExportStagingPolicy.cleanupAfterSaF(")
        )
        assertTrue(
            "the import of the policy must be present",
            saf.contains("import com.authorss81.noteflow.services.ExportStagingPolicy")
        )
        assertTrue(
            "the file delete must be guarded by the DELETE verdict",
            saf.contains("== ExportStagingPolicy.Cleanup.DELETE") &&
                saf.contains("runCatching { file.delete() }")
        )
    }

    @Test
    fun `consent-dialog dismissals delete the staged plaintext file`() {
        val saf = File(mainSourceRoot, "ui/components/SaFExporter.kt").readText()
        val dismissRegion = saf.substringAfter("onDismissRequest = {").substringBefore("confirmButton = {")
        assertTrue(
            "dismiss-before-picker must delete the staged (decrypted) file",
            dismissRegion.contains("pendingRequest?.second?.let { runCatching { it.delete() } }")
        )
        val cancelButton = saf.substringAfter("dismissButton = {")
        assertTrue(
            "the consent dialog's Cancel must delete the staged (decrypted) file",
            cancelButton.contains("pendingRequest?.second?.let { runCatching { it.delete() } }")
        )
    }

    @Test
    fun `export staging is kept only when the destination write failed`() {
        // The inverse guarantee of R2-B1P-02: a KEEP verdict must exist and must
        // be reachable exclusively from a RESULT_OK-with-uri-but-failed-copy state
        // (never from cancel/no-data, whose verdict is always DELETE).
        val policy = File(mainSourceRoot, "services/ExportStagingPolicy.kt").readText()
        assertTrue("a KEEP branch must exist", policy.contains("copySucceeded == false -> Cleanup.KEEP"))
        assertTrue("cancel must resolve to DELETE", policy.contains("else -> Cleanup.DELETE"))
    }

    // ---- R2-B1P-03: chooser-gated share + delete-after-dismiss -----------------

    @Test
    fun `Export Engine share is wrapped in an explicit chooser`() {
        val plugin = File(mainSourceRoot, "plugins/export/ExportEnginePlugin.kt").readText()
        assertTrue(
            "the share intent must be wrapped in createChooser",
            plugin.contains("Intent.createChooser(send, CHOOSER_TITLE)")
        )
        assertTrue(
            "the underlying send must be an ACTION_SEND",
            plugin.contains("Intent.ACTION_SEND")
        )
        assertTrue(
            "the FileProvider read grant must be on the inner send intent",
            plugin.contains("Intent.FLAG_GRANT_READ_URI_PERMISSION")
        )
    }

    @Test
    fun `EditorScreen launches the chooser and deletes the file on dismiss`() {
        val editor = File(mainSourceRoot, "ui/screens/EditorScreen.kt").readText()
        assertTrue(
            "the overflow-menu share must build the chooser",
            editor.contains("ExportShareHelper.chooserForExport(")
        )
        assertTrue(
            "the chooser must be launched through the ActivityResult launcher",
            editor.contains("exportShareLauncher.launch(chooser)")
        )
        assertTrue(
            "dismissal must delete the staging file (transfer-then-delete)",
            editor.contains("pendingExportFilePath?.let { path -> runCatching { File(path).delete() } }")
        )
        assertFalse(
            "the raw intent must never be started directly (chooser required)",
            editor.contains("context.startActivity(shareIntent)")
        )
        assertFalse(
            "the share must no longer call the pre-fix helper",
            editor.contains("ExportShareHelper.shareFile(")
        )
    }

    // ---- R2-b2b3-LOG-04: no note-title metadata in share subjects --------------

    @Test
    fun `share subject is generic and never the note title or filename`() {
        val plugin = File(mainSourceRoot, "plugins/export/ExportEnginePlugin.kt").readText()
        assertTrue(
            "the share subject must be the generic constant",
            plugin.contains("putExtra(Intent.EXTRA_SUBJECT, SHARE_SUBJECT)")
        )
        assertTrue(
            "the generic constant must be the documented label",
            plugin.contains("SHARE_SUBJECT = \"Exported note\"")
        )
        assertFalse(
            "the filename-derived subject must be gone",
            plugin.contains("putExtra(Intent.EXTRA_SUBJECT, file.name)")
        )
    }

    @Test
    fun `no production source publishes the filename as EXTRA_SUBJECT`() {
        val codeLines = allMainSources().lines().filter { line ->
            val trimmed = line.trimStart()
            !trimmed.startsWith("//") && !trimmed.startsWith("/*") && !trimmed.startsWith("*")
        }
        var extraSubjectUsages = 0
        for (line in codeLines) {
            if (line.contains("EXTRA_SUBJECT")) {
                extraSubjectUsages++
                assertFalse(
                    "the subject must never be the note title / filename (found: '$line')",
                    line.contains("file.name")
                )
            }
        }
        assertEquals(
            "the generic SHARE_SUBJECT must be the ONLY EXTRA_SUBJECT writer in production code",
            1,
            extraSubjectUsages
        )
    }

    // ---- helpers ---------------------------------------------------------------

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
