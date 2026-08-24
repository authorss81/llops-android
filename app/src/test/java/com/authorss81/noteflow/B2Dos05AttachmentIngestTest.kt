package com.authorss81.noteflow

import com.authorss81.noteflow.services.AttachmentIngestPolicy
import com.authorss81.noteflow.services.DocumentTextExtractor
import com.authorss81.noteflow.services.ImportArchivePolicy
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B2-DOS-05 (phase-81): attachment/import/export ingestion can no longer slurp
 * attacker- or user-supplied files wholly into heap with zero size cap.
 *
 * Pre-fix:
 *  - `EditorScreen` photo-embed / custom-background / paper-texture pickers did
 *    `openInputStream(uri)?.use { it.readBytes() }` — a 500 MB "photo" was fully
 *    slurped, then re-copied for the persisted file → OOM at embed time.
 *  - `NoteflowViewModel.restoreEncryptedBackupFromZip` did `sourceZip.readBytes()`
 *    on the WebDAV-downloaded archive.
 *  - `DocumentTextExtractor` did `file.readText()` on a large .txt (only the else
 *    branch carried a 1 MB guard) and `extractPdfText` did `file.readBytes()` on
 *    the whole PDF plus a second full String copy.
 *  - `NoteBodyVaultPolicy.resolveBodyForDisplay` / `WikiLinkParser` legacy body
 *    reads used raw `file.readText()` on the same surface.
 *
 * All of those now route through [AttachmentIngestPolicy.boundedReadBytes] (a
 * bounded streaming read that aborts mid-stream over budget) or
 * [AttachmentIngestPolicy.readTextHead] (a head-bounded text read), and
 * `DocumentTextExtractor` bounds its text/PDF reads to a capped head. Pure JVM —
 * no Android under test. Behavior + source pins.
 */
class B2Dos05AttachmentIngestTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- behavior: bounded stream read (photo/background/texture/backup) -----

    @Test
    fun `over-budget stream aborts mid-read and never surfaces the whole body`() {
        val drip = DripInputStream(
            totalBytes = AttachmentIngestPolicy.MAX_ATTACHMENT_BYTES + (200L * 1024L),
            chunkBytes = 1024
        )

        val ex = assertThrows(ImportArchivePolicy.ImportSizeLimitException::class.java) {
            AttachmentIngestPolicy.boundedReadBytes(drip)
        }
        assertTrue("message must name the size violation", "too large" in ex.message.orEmpty())
        assertTrue("the read must ABORT mid-stream (never drain the whole body)", drip.yielded < drip.totalBytes)
        assertTrue(
            "the abort must happen AT the budget boundary: at most one read buffer over-read",
            drip.yielded <= AttachmentIngestPolicy.MAX_ATTACHMENT_BYTES + AttachmentIngestPolicy.READ_BUFFER_BYTES
        )
        assertTrue("bytes must actually have flowed", drip.yielded > AttachmentIngestPolicy.MAX_ATTACHMENT_BYTES)
    }

    @Test
    fun `body equal to the cap exactly is acceptable`() {
        val drip = DripInputStream(
            totalBytes = AttachmentIngestPolicy.MAX_ATTACHMENT_BYTES,
            chunkBytes = 64 * 1024
        )
        val bytes = AttachmentIngestPolicy.boundedReadBytes(drip)
        assertEquals(AttachmentIngestPolicy.MAX_ATTACHMENT_BYTES.toInt(), bytes.size)
        assertEquals(drip.totalBytes, drip.yielded)
    }

    @Test
    fun `a small attachment still round-trips`() {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val bytes = AttachmentIngestPolicy.boundedReadBytes(ByteArrayInputStream(payload))
        assertTrue(bytes.contentEquals(payload))
    }

    @Test
    fun `an empty stream yields empty bytes`() {
        assertEquals(0, AttachmentIngestPolicy.boundedReadBytes(ByteArrayInputStream(ByteArray(0))).size)
    }

    // ---- behavior: head-bounded text read (legacy body / DOCX text) ----------

    @Test
    fun `head read never returns the whole body of an oversized file`() {
        val big = tmp.newFile("huge.txt")
        // 1 MB file, 64 KB explicit head budget: the read must be capped to the
        // budget, never the file length.
        val budget = 64L * 1024
        big.writeBytes(ByteArray(1024 * 1024) { ('a'.code + (it % 26)).toByte() })

        // Phase 204: null now means READ ERROR; a healthy file must still yield
        // its bounded (non-null) head.
        val head = AttachmentIngestPolicy.readTextHead(big, maxBytes = budget)
            ?: error("a readable non-empty file must never produce a null (error) head")
        assertTrue(
            "the read must be bounded to the budget, never the file length",
            head.length <= budget.toInt()
        )
        assertTrue("the head read must still return content", head.isNotBlank())
        // The bounded head is a PREFIX of the full file content.
        val prefix = String(
            java.io.FileInputStream(big).use { input ->
                val p = ByteArray(4096)
                val n = input.read(p)
                p.copyOfRange(0, n.coerceAtLeast(0))
            },
            Charsets.UTF_8
        )
        assertTrue("the head must be a prefix of the real file", head.startsWith(prefix))
    }

    @Test
    fun `head read with a zero budget yields empty`() {
        val small = tmp.newFile("a.txt")
        small.writeText("hello")
        assertEquals("", AttachmentIngestPolicy.readTextHead(small, maxBytes = 0L))
    }

    @Test
    fun `head read round-trips a small file fully`() {
        val small = tmp.newFile("small.md")
        small.writeText("# Title\nbody text")
        assertEquals("# Title\nbody text", AttachmentIngestPolicy.readTextHead(small))
    }

    @Test
    fun `head read refuses a missing or unreadable file`() {
        assertEquals("", AttachmentIngestPolicy.readTextHead(File(tmp.root, "nope-missing.md")))
    }

    // ---- behavior: DocumentTextExtractor bounces on its own budgets ----------

    @Test
    fun `document text extractor reads only the first MB of an oversized text file`() {
        val big = tmp.newFile("notes.txt")
        // 3 MB ASCII body; only the first 1 MB head budget should ever be read into heap.
        big.writeBytes(ByteArray(3 * 1024 * 1024) { ('a'.code + (it % 10)).toByte() })

        val result = DocumentTextExtractor.extractText(big, "text")
        assertTrue(
            "the extraction must be bounded to the 1 MB text head",
            result.length <= (1L * 1024 * 1024)
        )
        assertTrue("the head extraction must still return content", result.isNotBlank())
    }

    @Test
    fun `document text extractor reads only the first N bytes of a giant pdf body`() {
        val bigPdf = tmp.newFile("giant.pdf")
        // Text operators packed at the head + enormous tail beyond the extract budget.
        val headText = "(extracted operator text) ".repeat(20)
        val headBytes = headText.toByteArray(Charsets.ISO_8859_1)
        val filler = ByteArray((AttachmentIngestPolicy.MAX_ATTACHMENT_BYTES * 2 + 4096).toInt()) { 'x'.code.toByte() }
        bigPdf.writeBytes(headBytes + filler)

        val result = DocumentTextExtractor.extractText(bigPdf, "pdf")
        // The operator head was found within the bounded read — no OOM, no empty.
        assertTrue(result.contains("extracted operator text"))
    }

    // ---- source pins ---------------------------------------------------------

    @Test
    fun `EditorScreen pickers no longer readBytes the whole source`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt").readText()
        // A direct picker slurp reads the source through the opener — multiline regex
        // matches `openInputStream(...)?.use { ... .readBytes() }` REGARDLESS of any
        // policy reference elsewhere in the file (the pre-fix photo/bg/texture shape).
        val rawPickerSlurp = Regex(
            "openInputStream\\s*\\([^)]*\\)\\s*\\??\\.\\s*use\\s*\\{[^}]*\\.readBytes\\s*\\(\\)",
            RegexOption.DOT_MATCHES_ALL
        )
        assertFalse(
            "photo embed / background / texture pickers must never readBytes the picker stream directly",
            rawPickerSlurp.containsMatchIn(source)
        )
        // Also forbid any `openInputStream(...)...readBytes()` that skips the `use` block.
        assertFalse(
            "no direct openInputStream(...)??.readBytes() slurp may remain in EditorScreen",
            Regex("openInputStream\\s*\\([^)]*\\)\\s*\\??\\.\\s*readBytes\\s*\\(\\)").containsMatchIn(source)
        )
        assertEquals(
            "every picker ingest must route through the bounded reader",
            5,
            Regex("AttachmentIngestPolicy\\.boundedReadBytes").findAll(source).count()
        )
    }

    @Test
    fun `NoteflowViewModel WebDAV restore no longer readBytes the whole archive`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt").readText()
        assertFalse(
            "restoreEncryptedBackupFromZip must never call sourceZip.readBytes()",
            source.contains("sourceZip.readBytes()")
        )
        // R2-B1D-04 (phase-138): the archive is handed to importBackup as a FILE —
        // never read into heap. The pre-close size gate is a bounded File.length()
        // check against the SAME 400MB backup budget, failing closed before any
        // DB work with a truthful message.
        assertTrue(
            "the restore must keep the backup budget as a pre-close length gate",
            source.contains("sourceZip.length() > ImportExportService.MAX_BACKUP_INPUT_BYTES")
        )
        assertTrue(
            "the over-budget refusal must stay truthful and non-alarming",
            source.contains("Backup is too large to restore (max 400 MB).")
        )
    }

    @Test
    fun `DocumentTextExtractor no longer reads whole files`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/DocumentTextExtractor.kt").readText()
        assertFalse(
            "extractPdfText must never readBytes the whole PDF",
            source.contains("file.readBytes()")
        )
        assertFalse(
            "text extraction must never readText the whole file",
            source.contains(".readText()")
        )
        assertTrue(
            "all extraction reads must be head-bounded",
            source.contains("readFirstBytesBounded")
        )
    }

    @Test
    fun `NoteBodyVaultPolicy and WikiLinkParser legacy body reads are head-bounded`() {
        val noteBodySource = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/NoteBodyVaultPolicy.kt").readText()
        assertFalse(
            "resolveBodyForDisplay must never readText a legacy body wholesale",
            noteBodySource.contains("file.readText()")
        )
        assertTrue(
            "resolveBodyForDisplay must route through the bounded head read",
            noteBodySource.contains("AttachmentIngestPolicy.readTextHead")
        )

        val wikiSource = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/WikiLinkParser.kt").readText()
        assertFalse(
            "getFullTextForPage legacy body reads must never readText wholesale",
            wikiSource.contains("f.readText()")
        )
        assertTrue(
            "getFullTextForPage must route through the bounded head read",
            wikiSource.contains("AttachmentIngestPolicy.readTextHead")
        )
    }

    @Test
    fun `legacy body migration never slurps a whole legacy file`() {
        val raw = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt").readText()
        // Phase-81 review fix (F1): the unlock-time migration previously read the WHOLE
        // legacy plaintext body via file.readText() (then wrote it into the column and
        // DELETED the file) — an oversized/attacker-supplied file OOM'd the migration.
        // Drop comment lines so the pin checks real code, not the review comment text.
        val source = raw.lineSequence().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        assertFalse(
            "migrateLegacyPlaintextNoteBodies must never readText a whole legacy body",
            source.contains("file.readText()")
        )
        assertTrue(
            "the migration must route through the bounded head read",
            source.contains("AttachmentIngestPolicy.readTextHead")
        )
        assertTrue(
            "oversized legacy bodies must be refused (never read into the column, never deleted)",
            source.contains("AttachmentIngestPolicy.MAX_ATTACHMENT_BYTES")
        )
    }

    // ---- synthetic chunked stream used by the cap test -----------------------

    /**
     * Hands out at most [chunkBytes] per read and counts what it yielded, so a
     * test can prove the reader stopped early instead of draining everything —
     * which is exactly what the pre-fix `readBytes()` did before any cap could
     * run.
     */
    private class DripInputStream(
        val totalBytes: Long,
        private val chunkBytes: Int
    ) : InputStream() {
        private var pos = 0L
        var yielded: Long = 0L
            private set

        override fun read(): Int {
            if (pos >= totalBytes) return -1
            pos++
            yielded = pos
            return 0x41
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= totalBytes) return -1
            val n = minOf(len.toLong(), chunkBytes.toLong(), totalBytes - pos).toInt()
            java.util.Arrays.fill(b, off, off + n, 0x41.toByte())
            pos += n
            yielded = pos
            return n
        }
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}