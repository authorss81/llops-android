package com.authorss81.noteflow

import com.authorss81.noteflow.services.AttachmentIngestPolicy
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 204 — silent data-loss fix #2: the legacy body migration could
 * overwrite a GOOD encrypted column with "" and then delete the plaintext
 * source when a read threw.
 *
 * Pre-fix: `AttachmentIngestPolicy.readTextHead` swallowed I/O errors into
 * `return ""`, so `NoteRepository.migrateLegacyPlaintextNoteBodies`'s
 * throw-guard was dead code — a transient read error during unlock-time
 * migration encrypted "" over the good `extractedText` column AND deleted the
 * only plaintext copy. Permanent silent loss.
 *
 * Fix contract (all provable on the pure JVM):
 *  - [AttachmentIngestPolicy.readTextHead] returns `String?`: `""` = benign
 *    no-content (missing/unreadable/empty/zero-budget), `null` = the read
 *    STARTED but FAILED (a partial head is NEVER returned);
 *  - the migration treats null as "content unknown": skips BOTH the column
 *    overwrite AND the file delete, counts filesRemaining, retries next unlock.
 */
class Phase204LegacyBodyMigrationGuardTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------------- behavioral: the null contract ----------------

    @Test
    fun `a failed mid-read returns null - never empty and never partial`() {
        val f = tmp.newFile("legacy.md")
        f.writeText("# real body content that must not be guessed")
        val head = AttachmentIngestPolicy.readTextHead(f, open = {
            throw IOException("simulated transient EIO")
        })
        assertNull("a failed read must be UNKNOWN content, not \"\"", head)
    }

    @Test
    fun `a PARTIAL read followed by failure is never returned`() {
        val f = tmp.newFile("legacy.md")
        f.writeText("first-half-of-body|second-half-of-body")
        var reads = 0
        val head = AttachmentIngestPolicy.readTextHead(f, open = { file ->
            object : InputStream() {
                private val inner = FileInputStream(file)
                override fun read(): Int {
                    if (++reads > 5) throw IOException("died mid-stream")
                    return inner.read()
                }
            }
        })
        assertNull("the truncated head must not leak out as a usable body", head)
    }

    @Test
    fun `benign no-content cases still yield the empty string`() {
        val missing = File(tmp.root, "nope.md")
        assertEquals("", AttachmentIngestPolicy.readTextHead(missing))
        val empty = tmp.newFile("empty.txt").apply { writeText("") }
        assertEquals("", AttachmentIngestPolicy.readTextHead(empty))
        val real = tmp.newFile("real.md").apply { writeText("body") }
        assertEquals("body", AttachmentIngestPolicy.readTextHead(real))
    }

    @Test
    fun `an opener throwing on a healthy file yields null while default path still works`() {
        val f = tmp.newFile("both.md")
        f.writeText("hello world")
        assertNotNull(AttachmentIngestPolicy.readTextHead(f))
        assertNull(
            AttachmentIngestPolicy.readTextHead(f, open = { throw IllegalStateException("locked") })
        )
    }

    // ---------------- source pins: the migration skips BOTH writes ----------

    private fun sourceFile(relative: String): String =
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative").readText()

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) return dir
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }

    @Test
    fun `migration treats null as skip-overwrite-and-skip-delete with retry accounting`() {
        val repo = sourceFile("data/repository/NoteRepository.kt")
        val readIdx = repo.indexOf("val fileBody = AttachmentIngestPolicy.readTextHead(file)")
        assertTrue("the migration must call readTextHead", readIdx >= 0)
        val guardRegion = repo.substring(readIdx, repo.indexOf("if (fileBody != dbBody)", readIdx))
        assertTrue(
            "null (read error) must count as remaining so the sweep retries next unlock",
            guardRegion.contains("filesRemaining++")
        )
        assertTrue(
            "null must skip this page entirely BEFORE any overwrite/delete",
            guardRegion.contains("return@forEach")
        )
        // The old dead try/catch around the (never-throwing) call is gone.
        assertTrue(
            "the pre-fix swallow-into-empty path must not come back",
            !guardRegion.contains("catch (e: Exception)")
        )
    }

    @Test
    fun `display-path and wikilink callers handle the null contract`() {
        val policy = sourceFile("services/NoteBodyVaultPolicy.kt")
        assertTrue(
            "resolveBodyForDisplay falls through to the encrypted column on null",
            policy.contains("if (!fileBody.isNullOrEmpty()) return fileBody")
        )
        val wiki = sourceFile("services/WikiLinkParser.kt")
        assertTrue(
            "the vault scan appends nothing for a failed legacy read",
            wiki.contains("AttachmentIngestPolicy.readTextHead(f) ?: \"\"")
        )
    }
}
