package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-PLAT-3 (phase-59) source-level wiring pins for the export-destination fix.
 *
 * The finding (docs/security-report.md B1-PLAT-3): the whole-vault/plaintext
 * export functions auto-copied their zips into the world-readable
 * `/storage/emulated/0/Download` (`getExternalStoragePublicDirectory`) with a
 * single tap — no password, no confirm, no "unencrypted" warning.
 *
 * The fix, pinned here against the actual source (the pure-JVM consent/MIME
 * rules are unit-tested in <see>ExportDestinationPolicyTest</see>):
 *  1. NO export writes to public shared storage by itself — every wrapper in
 *     ImportExportService and PsdExportService returns an app-private cacheDir
 *     File and the `Environment.getExternalStoragePublicDirectory(...)`
 *     auto-copy blocks are gone repo-wide;
 *  2. EVERY user-facing export now routes through the SAF destination picker
 *     (`ACTION_CREATE_DOCUMENT`) via [SaFExporter];
 *  3. the whole-vault PLAINTEXT kinds require the bold pre-export warning
 *     before the picker opens.
 */
class B1Plat03ExportConsentTest {

    private val mainSourceRoot by lazy { File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow") }

    private fun allMainSources(): String =
        mainSourceRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .joinToString("\n") { it.readText() }

    // ---- 1. no export ever auto-writes to public shared storage --------------

    @Test
    fun `no export service auto-writes to public shared storage`() {
        // Production code is scanned with comment lines stripped so the doc
        // references in ExportDestinationPolicy (which explain why the historical
        // APIs must never return) do not trip the pin.
        val sources = allMainSources().lines()
            .filter { line ->
                val trimmed = line.trimStart()
                !trimmed.startsWith("//") && !trimmed.startsWith("/*") && !trimmed.startsWith("*") && !trimmed.startsWith("*/")
            }
            .joinToString("\n")
        assertFalse(
            "the world-readable public Downloads auto-copy must be gone repo-wide (B1-PLAT-3)",
            sources.contains("getExternalStoragePublicDirectory")
        )
        assertFalse(
            "DIRECTORY_DOWNLOADS must not be referenced in production code",
            sources.contains("DIRECTORY_DOWNLOADS")
        )
    }

    @Test
    fun `export service wrappers keep their output app-private in cacheDir`() {
        val importExport = File(mainSourceRoot, "services/ImportExportService.kt").readText()
        assertTrue(
            "exportAnnotatedPage must keep the page render in cacheDir",
            importExport.contains("File(context.cacheDir")
        )
        assertTrue(
            "exportNoteToHtml must keep the HTML note in cacheDir",
            importExport.contains("htmlFile")
        )
        for (needle in VALIDATING_KIND_DOC_MARKERS) {
            assertTrue(
                "the service must carry the B1-PLAT-3 no-public-copy remark near '$needle'",
                importExport.contains(needle)
            )
        }
        val psd = File(mainSourceRoot, "services/PsdExportService.kt").readText()
        assertTrue(
            "PsdExportService must keep the PSD in cacheDir",
            psd.contains("cacheDir")
        )
        assertTrue(
            "PsdExportService must no longer reference the public Downloads copy",
            !psd.contains("DIRECTORY_DOWNLOADS")
        )
    }

    // ---- 2. every user-facing export routes through the SAF picker -----------

    @Test
    fun `SaFExporter delivers every export through ACTION_CREATE_DOCUMENT`() {
        val saf = File(mainSourceRoot, "ui/components/SaFExporter.kt").readText()
        assertTrue(
            "the picker must use ACTION_CREATE_DOCUMENT",
            saf.contains("Intent.ACTION_CREATE_DOCUMENT")
        )
        assertTrue(
            "the picker must be OPENABLE so the doc is writable",
            saf.contains("Intent.CATEGORY_OPENABLE")
        )
        assertTrue(
            "the picker must be pre-filled with the export MIME type",
            saf.contains("type = ExportDestinationPolicy.mimeType(kind)")
        )
        assertTrue(
            "the picker must suggest the generated file name",
            saf.contains("Intent.EXTRA_TITLE")
        )
        assertTrue(
            "a successful write must transfer-then-delete the cacheDir staging copy",
            saf.contains("file.delete()")
        )
    }

    @Test
    fun `whole-vault plaintext kinds gate the picker behind the bold warning`() {
        val policy = File(mainSourceRoot, "services/ExportDestinationPolicy.kt").readText()
        assertTrue(
            "the warning title must exist and be unmissable",
            policy.contains("PLAINTEXT_WARNING_TITLE")
        )
        assertTrue(
            "the warning must explicitly say the content is NOT encrypted",
            policy.contains("NOT encrypted") || policy.contains("READABLE PLAINTEXT")
        )
        assertTrue(
            "the warning must suggest transfer-then-delete",
            policy.contains("DELETE")
        )
        val saf = File(mainSourceRoot, "ui/components/SaFExporter.kt").readText()
        assertTrue(
            "the consent dialog must render before the picker for warning-required kinds",
            saf.contains("requiresPlaintextWarning(kind)")
        )
        assertTrue(
            "the dialog title must be styled bold in the error/attention colour",
            saf.contains("fontWeight = FontWeight.Bold")
        )
    }

    // ---- 3. UI export call sites deliver files to the exporter, not Downloads --

    @Test
    fun `HomeScreen export and backup flows route through the SAF exporter`() {
        val home = File(mainSourceRoot, "ui/screens/HomeScreen.kt").readText()
        assertTrue(
            "HomeScreen must own a SaFExporter instance",
            home.contains("rememberSaFExporter(scope)")
        )
        assertTrue(
            "the plain backup flow must go through the exporter",
            home.contains("ExportDestinationPolicy.ExportKind.ENCRYPTED_BACKUP")
        )
        assertTrue(
            "the password-protected backup flow must go through the exporter",
            home.contains("exporter.export(")
        )
        assertTrue(
            "the Obsidian whole-vault export must carry the plaintext-warning kind",
            home.contains("ExportKind.OBSIDIAN_VAULT")
        )
        assertTrue(
            "the HTML-site whole-vault export must carry the plaintext-warning kind",
            home.contains("ExportKind.HTML_SITE")
        )
        assertTrue(
            "the notebook/section vault zips must carry the plaintext-warning kind",
            home.contains("ExportKind.VAULT_ZIP")
        )
        assertFalse("no HomeScreen export may reference public Downloads", home.contains("DIRECTORY_DOWNLOADS"))
    }

    @Test
    fun `EditorScreen export flows route through the SAF exporter`() {
        val editor = File(mainSourceRoot, "ui/screens/EditorScreen.kt").readText()
        assertTrue(
            "EditorScreen must own a SaFExporter instance",
            editor.contains("rememberSaFExporter(scope)")
        )
        for (kind in listOf("PAGE_PNG", "PAGE_WEBP", "PAGE_PDF", "DOCUMENT_PDF", "NOTE_HTML", "LAYERED_PSD", "VAULT_ZIP")) {
            assertTrue(
                "EditorScreen must export kind $kind through the exporter",
                editor.contains("ExportKind.$kind")
            )
        }
        assertFalse("no EditorScreen export may reference public Downloads", editor.contains("DIRECTORY_DOWNLOADS"))
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

    companion object {
        // Markers left next to each previously-public Downloads copy now removed.
        private val VALIDATING_KIND_DOC_MARKERS = listOf(
            "exportAnnotatedPage",
            "exportDocumentAsPdf",
            "exportVaultToZip",
            "exportNoteToHtml",
            "exportVaultToHtmlZip",
            "exportObsidianVaultZip"
        )
    }
}