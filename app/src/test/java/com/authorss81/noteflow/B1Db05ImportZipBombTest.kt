package com.authorss81.noteflow

import com.authorss81.noteflow.services.ImportArchivePolicy
import com.authorss81.noteflow.services.ImportArchivePolicy.Accounting
import com.authorss81.noteflow.services.ImportArchivePolicy.ImportSizeLimitException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-DB-5 (phase-55) behavioral + wiring tests for the zip-bomb caps on the
 * HTML/Obsidian ZIP import readers.
 *
 * Finding: `ImportExportService.importHtmlZipOrFolder` (old `:1791-1792`) and
 * `importObsidianVaultZip` (old `:1969-1972`, `:1983-1985`) called unbounded
 * `zis.readBytes()` on every archive entry with no per-entry / total /
 * expansion-ratio / entry-count budgets, and the originating `readUriBytes`
 * stream had no size cap — a crafted zip shared via the picker or the share
 * sheet decompressed megabytes→gigabytes into heap (OOM/ANR). The restore
 * path's `copyWithLimit` already bounded that path; the imports did not.
 *
 * What this proves on the pure JVM (no Room/SQLCipher/Context): the new
 * `ImportArchivePolicy` refuses a high-compression-ratio entry, an oversized
 * per-entry / total read, and an over-limit entry count — each with a clean
 * [ImportSizeLimitException]. Real zip-bombs are used where the stream is
 * faithful (per-entry size, entry count); the ratio guard is a forged-declared
 * size decision and is proven as a pure function (a real `ZipInputStream`
 * REFUSES forged compressed sizes mid-inflate before the ratio ever matters, so
 * the guard is exactly the extra seal against a backup-style forged header).
 * The Android-bound wiring (import functions routed through the bounded
 * reader, `readUriBytes` bounded and fail-closed, restore callers keeping the
 * 400MB backup cap, the HomeScreen snackbar) is pinned at source level below.
 */
class B1Db05ImportZipBombTest {

    // ---- archive input cap -------------------------------------------------

    @Test
    fun `archive input over the 200MB cap is refused`() {
        assertTrue(
            ImportArchivePolicy.inputArchiveOverLimit(ImportArchivePolicy.MAX_IMPORT_ARCHIVE_INPUT_BYTES + 1)
        )
        assertFalse(
            ImportArchivePolicy.inputArchiveOverLimit(ImportArchivePolicy.MAX_IMPORT_ARCHIVE_INPUT_BYTES)
        )
        assertTrue(ImportArchivePolicy.inputArchiveOverLimit(1, maxInputBytes = 0))
        assertFalse(ImportArchivePolicy.inputArchiveOverLimit(0, maxInputBytes = 0))
    }

    // ---- entry-count cap ---------------------------------------------------

    @Test
    fun `entry count beyond the budget is refused after exactly maxEntries claims`() {
        val accounting = Accounting()
        val max = 100
        for (i in 1..max) ImportArchivePolicy.claimEntry(accounting, maxEntries = max)
        assertEquals(max, accounting.entryCount)
        val ex = assertThrows(ImportSizeLimitException::class.java) {
            ImportArchivePolicy.claimEntry(accounting, maxEntries = max)
        }
        assertTrue(ex.message.orEmpty().contains("more than $max entries"))
    }

    @Test
    fun `a real zip with too many entries fails the scan loop cleanly`() {
        // 101 tiny entries with a 100-entry budget — a "many tiny entries"
        // bomb's scan itself must be bounded (no ANR just walking the archive).
        val zip = buildZip((1..101).map { it.toString() to "tiny".toByteArray() })
        val accounting = Accounting()
        val ex = assertThrows(ImportSizeLimitException::class.java) {
            ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    ImportArchivePolicy.claimEntry(accounting, maxEntries = 100)
                    if (isConsumable(entry)) {
                        ImportArchivePolicy.readEntryBounded(zis, entry, accounting)
                    }
                    entry = zis.nextEntry
                }
            }
        }
        assertEquals("the scan must stop at the budget, not at the archive end", 100, accounting.entryCount)
        assertTrue(ex.message.orEmpty().contains("possible zip bomb"))
    }

    // ---- per-entry size cap (real decompression, faithful stream) ----------

    @Test
    fun `a giant single entry is refused mid-read without materializing the full blob`() {
        // 60 MB of zeros compresses to ~60 KB, so the INPUT stays tiny while the
        // DECOMPRESSED stream is 60 MB — the classic single-entry bomb. The
        // bounded reader must throw as soon as the per-entry cap crosses.
        val bombData = ByteArray((ImportArchivePolicy.MAX_IMPORT_ENTRY_BYTES + 10L * 1024 * 1024).toInt()) // 60 MB of zeros
        val zip = buildZip(listOf("giant.md" to bombData))

        val accounting = Accounting()
        val ex = assertThrows(ImportSizeLimitException::class.java) {
            ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
                val entry = zis.nextEntry
                ImportArchivePolicy.readEntryBounded(zis, entry!!, accounting)
            }
        }
        assertTrue(
            "the thrown rejection must mention the violated budget — got: " + ex.message,
            ex.message.orEmpty().contains("single file is too large")
        )
        assertTrue(
            "the throw must leave the accounting far below the 60 MB entry — heap never grew beyond the cap",
            accounting.totalBytes <= ImportArchivePolicy.MAX_IMPORT_ENTRY_BYTES + 8192
        )
        assertTrue(
            "the violated entry must not count toward an over-budget total",
            accounting.totalBytes <= ImportArchivePolicy.MAX_IMPORT_TOTAL_BYTES
        )
    }

    // ---- expansion-ratio guard (forged-declared-size decision) -------------

    @Test
    fun `a forged tiny declared compressedSize trips the ratio guard`() {
        // Declared compressedSize = 10 KB but the actual decompressed bytes
        // exceed 10 KB * 100 — the ratio seal (keyed off ACTUAL bytes read,
        // exactly like the restore path's copyWithLimit) refuses before the
        // per-entry/total caps are needed.
        val forgedEntry = ZipEntry("bomb.bin").apply {
            size = 10L * 1024 * 1024
            compressedSize = 10_000L
        }
        val accounting = Accounting()
        val ex = assertThrows(ImportSizeLimitException::class.java) {
            ImportArchivePolicy.checkEntryChunk(forgedEntry, 1_000_001L, accounting)
        }
        assertTrue(ex.message.orEmpty().contains("compression ratio"))
        assertEquals("the violated chunk never counts toward the total", 0L, accounting.totalBytes)
    }

    @Test
    fun `a forged tiny declared uncompressed size trips the ratio guard`() {
        // Same seal via the OTHER declared field: entry claims 10 KB uncompressed
        // but the reader has already seen 1 MB of actual data.
        val forgedEntry = ZipEntry("bomb.bin").apply {
            size = 10_000L
            compressedSize = -1L
        }
        val accounting = Accounting()
        val ex = assertThrows(ImportSizeLimitException::class.java) {
            ImportArchivePolicy.checkEntryChunk(forgedEntry, 1_000_001L, accounting)
        }
        assertTrue(ex.message.orEmpty().contains("compression ratio"))
        assertEquals(0L, accounting.totalBytes)
    }

    @Test
    fun `an honest entry with an accurate declaration does not trip the ratio guard`() {
        val accounting = Accounting()
        val honest = ZipEntry("notes.md").apply {
            size = 1L * 1024 * 1024
            compressedSize = 300_000L
        }
        ImportArchivePolicy.checkEntryChunk(honest, 100_000L, accounting) // well inside 100x
        assertEquals(
            "the per-chunk check must never mutate the total (settled once per completed entry)",
            0L,
            accounting.totalBytes
        )
        ImportArchivePolicy.settleEntryRead(accounting, 100_000L)
        assertEquals(100_000L, accounting.totalBytes)
    }

    // ---- total cap ---------------------------------------------------------

    @Test
    fun `total uncompressed bytes beyond the 200MB cap are refused`() {
        // Seed the accounting just below the total while every CHUNK stays under
        // the 50 MB per-entry cap, then push the final chunk past the total.
        val accounting = Accounting()
        accounting.totalBytes = ImportArchivePolicy.MAX_IMPORT_TOTAL_BYTES - 10
        val ex = assertThrows(ImportSizeLimitException::class.java) {
            ImportArchivePolicy.checkEntryChunk(
                ZipEntry("b.md"),
                20,
                accounting
            )
        }
        assertTrue(
            "the thrown rejection must mention the total budget — got: " + ex.message,
            ex.message.orEmpty().contains("total archive size exceeds")
        )
        assertEquals("the rejected chunk never advances the total", ImportArchivePolicy.MAX_IMPORT_TOTAL_BYTES - 10, accounting.totalBytes)
    }

    // ---- happy path round-trip ---------------------------------------------

    @Test
    fun `a legitimate small archive reads through exactly`() {
        val htmlA = "<html><body><h1>A</h1><p>hello</p></body></html>".toByteArray()
        val htmlB = "<html><body><h1>B</h1><p>world</p></body></html>".toByteArray()
        val stray = "not-an-html".toByteArray()
        val zip = buildZip(listOf("a.html" to htmlA, "b.html" to htmlB, "notes.txt" to stray))

        val accounting = Accounting()
        val readBack = mutableListOf<ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                ImportArchivePolicy.claimEntry(accounting)
                if (isConsumable(entry)) {
                    readBack += ImportArchivePolicy.readEntryBounded(zis, entry, accounting)
                }
                entry = zis.nextEntry
            }
        }

        assertEquals("all entries scanned", 3, accounting.entryCount)
        assertEquals(
            "only the two html entries count toward the total (stray is never read)",
            2 * htmlA.size.toLong(),
            accounting.totalBytes
        )
        assertTrue(htmlA.contentEquals(readBack[0]))
        assertTrue(htmlB.contentEquals(readBack[1]))
    }

    private fun isConsumable(entry: ZipEntry): Boolean =
        entry.name.endsWith(".html", ignoreCase = true) || entry.name.endsWith(".md", ignoreCase = true)

    private fun buildZip(entries: List<Pair<String, ByteArray>>): ByteArray {
        return ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                entries.forEach { (name, data) ->
                    val entry = ZipEntry(name).apply { size = data.size.toLong() }
                    zos.putNextEntry(entry)
                    zos.write(data)
                    zos.closeEntry()
                }
            }
            baos.toByteArray()
        }
    }

    // ---- source-level wiring pins (the Android-bound flow) -----------------

    private val importSource by lazy {
        File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt"
        ).readText()
    }

    private val homeScreenSource by lazy {
        File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt"
        ).readText()
    }

    private val viewModelSource by lazy {
        File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt"
        ).readText()
    }

    @Test
    fun `no unbounded ZipInputStream readBytes survives in ImportExportService`() {
        assertFalse(
            "the unbounded `zis.readBytes()` call must be gone everywhere in the service",
            importSource.contains("zis.readBytes()")
        )
        assertTrue("the shared bounded reader must exist", importSource.contains("ImportArchivePolicy.readEntryBounded"))
    }

    @Test
    fun `both zip import readers route every entry through the bounded policy`() {
        val htmlZip = importSource.substringAfter("suspend fun importHtmlZipOrFolder")
            .substringBefore("suspend fun exportNoteToHtml")
        val obsidian = importSource.substringAfter("suspend fun importObsidianVaultZip")
            .substringBefore("suspend fun exportObsidianVaultZip")

        for (region in listOf(htmlZip, obsidian)) {
            assertTrue("the compressed input must be size-capped", region.contains("ImportArchivePolicy.inputArchiveOverLimit(bytes.size)"))
            assertTrue("every entry must be claimed against the entry-count cap", region.contains("ImportArchivePolicy.claimEntry(accounting)"))
            assertTrue("every consumed entry must go through the bounded reader", region.contains("ImportArchivePolicy.readEntryBounded(zis, entry, accounting)"))
            assertTrue("a budget breach must fail closed with the clean error", region.contains("catch (e: ImportArchivePolicy.ImportSizeLimitException)"))
        }
        assertFalse("Obsidian must no longer scan the archive twice", obsidian.contains("// Pass 1"))
        assertFalse("Obsidian must no longer scan the archive twice", obsidian.contains("// Pass 2"))
        assertFalse("no unbounded readBytes on zip entries may survive", htmlZip.contains("zis.readBytes()"))
        assertFalse(obsidian.contains("zis.readBytes()"))
    }

    @Test
    fun `readUriBytes is bounded and the restore callers keep the 400MB backup cap`() {
        val readUri = importSource.substringAfter("suspend fun readUriBytes")
            .substringBefore("fun extensionOf")
        assertTrue("the stream read must carry a maximum", readUri.contains("maxBytes:"))
        assertTrue("an oversized stream is refused via ImportSizeLimitException", readUri.contains("ImportArchivePolicy.ImportSizeLimitException("))
        assertTrue("the rejection is re-thrown, never swallowed by the null-on-error catch", readUri.contains("catch (e: ImportArchivePolicy.ImportSizeLimitException)"))

        // Restore keeps its larger legacy budget so a legitimate huge vault still restores.
        assertTrue(importSource.contains("const val MAX_BACKUP_INPUT_BYTES = 400L * 1024 * 1024"))
        assertTrue(
            "HomeScreen restore picker must keep passing the backup cap",
            homeScreenSource.contains("ImportExportService.MAX_BACKUP_INPUT_BYTES")
        )
        assertTrue(
            "CorruptionRecoveryScreen restore path must keep passing the backup cap",
            viewModelSource.contains("ImportExportService.MAX_BACKUP_INPUT_BYTES")
        )
    }

    @Test
    fun `HomeScreen import loop surfaces the clean rejection instead of a silent skip`() {
        assertTrue(
            "the picker's originating stream read must refuse oversized files with a snackbar",
            homeScreenSource.contains("catch (e: ImportArchivePolicy.ImportSizeLimitException)")
        )
        assertTrue(
            "the zip/HTML import dispatch must surface the zip-bomb rejection",
            // R2-b2b3-LOG-01 (phase-148): the snackbar text is now FIXED policy
            // output — never `Import skipped: ${e.message}`.
            homeScreenSource.contains("viewModel.showSnackbar(UiFailureTextPolicy.importSkippedMessage(e), isLong = true)")
        )
        assertTrue(
            "HomeScreen must import the new policy type",
            homeScreenSource.contains("import com.authorss81.noteflow.services.ImportArchivePolicy")
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