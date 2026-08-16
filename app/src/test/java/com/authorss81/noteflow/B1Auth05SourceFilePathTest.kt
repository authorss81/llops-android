package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.NotePageEntity
import com.authorss81.noteflow.services.NoteBodyVaultPolicy
import com.authorss81.noteflow.services.SourceFilePathPolicy
import com.authorss81.noteflow.services.WikiLinkParser
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Phase 69 (B1-AUTH-05): a note's `pages.sourceFilePath` is stored unencrypted
 * and was never validated — a crafted vault backup could transplant rows
 * pointing at arbitrary readable/writable files, and the body readers would
 * display/save there. These pure-JVM tests prove the fix: the confinement
 * decision table, the confined read/delete helpers, the WikiLinkParser read
 * gate, and the write/restore wiring pins.
 */
class B1Auth05SourceFilePathTest {

    private lateinit var importsRoot: File
    private lateinit var outside: File

    @Before
    fun setUp() {
        importsRoot = File(System.getProperty("java.io.tmpdir"), "inkflow-sourcepath-root-" + java.util.UUID.randomUUID())
        importsRoot.mkdirs()
        outside = File(System.getProperty("java.io.tmpdir"), "inkflow-sourcepath-outside-" + java.util.UUID.randomUUID())
        outside.mkdirs()
        WikiLinkParser.resetCacheMetrics()
        WikiLinkParser.invalidateCaches()
    }

    @After
    fun tearDown() {
        importsRoot.deleteRecursively()
        outside.deleteRecursively()
        WikiLinkParser.invalidateCaches()
    }

    // ---------- SourceFilePathPolicy decision table ----------

    @Test
    fun `blank and null values confine to null`() {
        assertNull(SourceFilePathPolicy.confine(null, importsRoot))
        assertNull(SourceFilePathPolicy.confine("", importsRoot))
        assertNull(SourceFilePathPolicy.confine("   ", importsRoot))
        assertNull("no root confines nothing", SourceFilePathPolicy.confine("/ignored/abs", null))
    }

    @Test
    fun `relative values are refused outright`() {
        assertNull(
            "a stored sourceFilePath is always absolute — a relative value is never a real reference",
            SourceFilePathPolicy.confine("imports/legacy.md", importsRoot)
        )
        assertNull(SourceFilePathPolicy.confine("noteflow/imports/page.md", importsRoot))
    }

    @Test
    fun `traversal segments in either separator are refused before file IO`() {
        for (bad in listOf(
            "$importsRoot/../${outside.name}/secret.txt",
            "$importsRoot/a/../../${outside.name}/esc.txt",
            "${importsRoot}\\..\\${outside.name}\\win.txt"
        )) {
            assertNull("$bad must be refused", SourceFilePathPolicy.confine(bad, importsRoot))
        }
    }

    @Test
    fun `absolute path outside the root is refused`() {
        val stray = File(outside, "voice.enc").apply { writeText("ATTACKER") }
        try {
            assertNull("an absolute escape must never resolve", SourceFilePathPolicy.confine(stray.absolutePath, importsRoot))
            assertFalse(SourceFilePathPolicy.isConfined(stray.absolutePath, importsRoot))
            assertFalse("non-existent outside paths are also refused", SourceFilePathPolicy.isConfined(
                File(outside, "missing.txt").absolutePath, importsRoot))
        } finally {
            stray.delete()
        }
    }

    @Test
    fun `confined absolute path resolves to its canonical form`() {
        val inside = File(importsRoot, "page.md").apply { writeText("body") }
        try {
            val resolved = SourceFilePathPolicy.confine(inside.absolutePath, importsRoot)
            assertNotNull(resolved)
            assertEquals(inside.canonicalPath, resolved)
            assertTrue(SourceFilePathPolicy.isConfined(inside.absolutePath, importsRoot))
        } finally {
            inside.delete()
        }
    }

    @Test
    fun `a symlink planted under the root cannot escape it`() {
        val target = File(outside, "secret.md").apply { writeText("ATTACKER BODY") }
        val linkPath = File(importsRoot, "innocent.md").toPath()
        val created = try {
            Files.createSymbolicLink(linkPath, target.toPath())
            true
        } catch (e: Exception) {
            false
        }
        assumeTrue("symlinks must be creatable to test the escape", created)
        try {
            assertNull(
                "a symlink that resolves outside the canonical root must be refused",
                SourceFilePathPolicy.confine(linkPath.toString(), importsRoot)
            )
        } finally {
            linkPath.toFile().delete()
            target.delete()
        }
    }

    @Test
    fun `non-directory root confines nothing`() {
        val notDir = File(outside, "not-a-dir.txt").apply { writeText("x") }
        try {
            File(outside, "other.txt").writeText("y") // ensure outside is still a dir
            assertNull(SourceFilePathPolicy.confine(
                File(importsRoot, "p.md").absolutePath, notDir))
        } finally {
            notDir.delete()
            File(outside, "other.txt").delete()
        }
    }

    // ---------- NoteBodyVaultPolicy confined reads ----------

    private fun page(id: String, title: String, text: String?, sourceFile: File?): NotePageEntity =
        NotePageEntity(
            id = id, sectionId = "sec", title = title,
            sourceFilePath = sourceFile?.absolutePath,
            sourceFileType = if (sourceFile != null) "text" else null,
            extractedText = text ?: ""
        )

    @Test
    fun `legacy body under the root is coalesced when the root is provided`() {
        val legacy = File(importsRoot, "note.md").apply { writeText("LEGACY FILE BODY") }
        try {
            val body = NoteBodyVaultPolicy.resolveBodyForDisplay(
                extractedText = "column body",
                sourceFilePath = legacy.absolutePath,
                sourceFileType = "text",
                importsRoot = importsRoot
            )
            assertEquals("a confined legacy file is still the authoritative pre-fix content", "LEGACY FILE BODY", body)
        } finally {
            legacy.delete()
        }
    }

    @Test
    fun `body read without a root falls back to the column and never reads the file`() {
        val legacy = File(importsRoot, "note.md").apply { writeText("UNREADABLE SECRET") }
        try {
            val body = NoteBodyVaultPolicy.resolveBodyForDisplay(
                extractedText = "column body",
                sourceFilePath = legacy.absolutePath,
                sourceFileType = "text",
                importsRoot = null // fail-closed: no root ⇒ no file read
            )
            assertEquals("without a root the file must never be read", "column body", body)
        } finally {
            legacy.delete()
        }
    }

    @Test
    fun `body read refuses a file outside the root`() {
        val stray = File(outside, "note.md").apply { writeText("SECRET FILE BODY") }
        try {
            val body = NoteBodyVaultPolicy.resolveBodyForDisplay(
                extractedText = "column body",
                sourceFilePath = stray.absolutePath,
                sourceFileType = "text",
                importsRoot = importsRoot
            )
            assertEquals("an escaping sourceFilePath must never be read", "column body", body)
        } finally {
            stray.delete()
        }
    }

    @Test
    fun `legacy body delete refuses a file outside the root`() {
        val stray = File(outside, "note.md").apply { writeText("KEEP") }
        try {
            assertNull(NoteBodyVaultPolicy.deleteLegacyNoteTextBody(stray.absolutePath, "text", importsRoot))
            assertTrue("the outside file must not be deleted", stray.exists())
            assertNull("a null root never deletes", NoteBodyVaultPolicy.deleteLegacyNoteTextBody(stray.absolutePath, "text", null))
            assertTrue(stray.exists())
        } finally {
            stray.delete()
        }
    }

    @Test
    fun `legacy body delete removes a confined file`() = runBlocking {
        val confined = File(importsRoot, "gone.md").apply { writeText("old") }
        try {
            val deleted = NoteBodyVaultPolicy.deleteLegacyNoteTextBody(confined.absolutePath, "text", importsRoot)
            assertEquals(confined.absolutePath, deleted)
            assertFalse("the confined file must be deleted", confined.exists())
        } finally {
            confined.delete()
        }
    }

    // ---------- WikiLinkParser read gate ----------

    @Test
    fun `backlinks still read a legacy file confined under the root`() = runBlocking {
        val legacy = File(importsRoot, "confined.md")
            .apply { writeText("A note that mentions [[Target]] from a confined file.") }
        val target = page("target", "Target", "body", null)
        val linker = page("linker", "Confined linker", null, legacy)
        try {
            val (explicit, _) = WikiLinkParser.findBacklinks(target, listOf(linker, target), importsRoot = importsRoot)
            assertEquals("a confined legacy file must still contribute to backlinks", 1, explicit.size)
        } finally {
            legacy.delete()
        }
    }

    @Test
    fun `backlinks never read a legacy file outside the root`() = runBlocking {
        // The file exists, matches the text-source gate AND mentions the target,
        // but its path escapes the imports root — it must be skipped entirely.
        val escaping = File(outside, "escaping.md")
            .apply { writeText("A note that mentions [[Target]] but lives outside the root.") }
        val target = page("target", "Target", "body", null)
        val linker = page("linker", "Escaping linker", null, escaping)
        try {
            val (explicit, _) = WikiLinkParser.findBacklinks(target, listOf(linker, target), importsRoot = importsRoot)
            assertEquals("an escaping sourceFilePath must not be read for backlinks", 0, explicit.size)
            val (unlinked, _) = WikiLinkParser.findBacklinks(
                page("other", "Target", "body", null), listOf(linker, target), importsRoot = importsRoot
            )
            assertEquals("the escaping file's content must never surface anywhere", 0, unlinked.size)
        } finally {
            escaping.delete()
        }
    }

    @Test
    fun `backlinks without any root do not read legacy files`() = runBlocking {
        val confined = File(importsRoot, "confined.md")
            .apply { writeText("A note that mentions [[Target]] but has no root passed.") }
        val target = page("target", "Target", "body", null)
        val linker = page("linker", "Confined linker", null, confined)
        try {
            val (explicit, _) = WikiLinkParser.findBacklinks(target, listOf(linker, target))
            assertEquals("a null root must fail closed (no file reads)", 0, explicit.size)
        } finally {
            confined.delete()
        }
    }

    // ---------- wiring pins ----------

    private fun repoSource(relativePath: String): String {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) {
                return File(dir, relativePath).readText()
            }
            dir = dir.parentFile ?: return@repeat
        }
        return File(cwd, relativePath).readText()
    }

    @Test
    fun `the read helpers and readers all route through the confinement policy`() {
        assertTrue(
            "NoteBodyVaultPolicy must confine the legacy body read",
            repoSource("app/src/main/kotlin/com/authorss81/noteflow/services/NoteBodyVaultPolicy.kt")
                .contains("SourceFilePathPolicy.confine(sourceFilePath, importsRoot)")
        )
        assertTrue(
            "WikiLinkParser must confine the legacy file read",
            repoSource("app/src/main/kotlin/com/authorss81/noteflow/services/WikiLinkParser.kt")
                .contains("SourceFilePathPolicy.confine(rawPath, importsRoot)")
        )
        assertTrue(
            "DocumentTextExtractor must confine non-text source reads",
            repoSource("app/src/main/kotlin/com/authorss81/noteflow/services/DocumentTextExtractor.kt")
                .contains("SourceFilePathPolicy.confine(page.sourceFilePath, importsRoot)")
        )
    }

    @Test
    fun `the write and restore layers enforce the confinement`() {
        val repo = repoSource("app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt")
        assertTrue("NoteRepository.updatePageSource must confine before persisting", repo.contains("SourceFilePathPolicy.confine(sourceFilePath, importsRoot)"))
        assertTrue("NoteRepository.createPage must confine the stored source", repo.contains("SourceFilePathPolicy.confine(sourceFilePath, importsRoot)"))
        assertTrue("the legacy-body migration must only read confined files", repo.contains("SourceFilePathPolicy.confine(page.sourceFilePath, importsRoot)"))
        assertTrue("NoteRepository must own the imports root", repo.contains("private val importsRoot: File"))

        val importer = repoSource("app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt")
        assertTrue("restore must sanitize the restored sourceFilePath column", importer.contains("sanitizeRestoredSourceFilePaths(db, getImportsDir(context))"))
        assertTrue("the restore sanitizer drops unconfined rows", importer.contains("SourceFilePathPolicy.isConfined(stored, importsRoot)"))

        assertTrue(
            "MainActivity must pass the imports root into the body resolver",
            repoSource("app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt")
                .contains("resolveBodyForDisplay(")
                && repoSource("app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt")
                    .contains("ImportExportService.getImportsDir(this@MainActivity)")
        )
        assertTrue(
            "NoteflowViewModel.updatePageSource must confine before persisting",
            Regex("""SourceFilePathPolicy\.confine\(\s*sourceFilePath""")
                .containsMatchIn(repoSource("app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt"))
        )
        assertTrue(
            "NoteflowViewModel must build the repository with the imports root",
            repoSource("app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt")
                .contains("NoteRepository(db, ImportExportService.getImportsDir(appContext))")
        )
    }
}
