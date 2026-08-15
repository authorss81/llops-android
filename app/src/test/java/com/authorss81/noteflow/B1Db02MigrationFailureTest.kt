package com.authorss81.noteflow

import com.authorss81.noteflow.data.db.quarantineMigrateFailed
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B1-DB-2 (phase-53) behavioral + wiring tests for the plaintext→SQLCipher
 * migration failure path.
 *
 * Finding: `NoteflowDatabase.migratePlaintextIfNeeded` (old `:191-232`) deleted
 * the ORIGINAL plaintext database (the user's only copy of their notes) plus
 * `-wal`/`-shm` on ANY exception — with no `*.corrupt-*`/`*.migrate-failed-*`
 * rescue and no corruption flag, so there was no recovery screen afterwards.
 * That is exactly the Phase-09 H2 defect the code fixes for normal opens but
 * violated for migration.
 *
 * What is provable on the pure JVM (no Room/SQLCipher/Context): the failure
 * quarantine decision itself — a migration failure must RENAME (never delete)
 * the original db + wal/shm/journal companions to `*.migrate-failed-<ts>`,
 * drop only the scratch encrypted copy, and return a timestamp so the caller
 * can raise the persistent corruption flag. The Android-bound wiring (the
 * catch block calling `quarantineMigrateFailed` → `setCorruptionDetected` →
 * `throw e`, and the atomic rename swap) is pinned at source level below.
 */
class B1Db02MigrationFailureTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- quarantineMigrateFailed (pure JVM behavior) ------------------------

    @Test
    fun `migration failure preserves the original under migrate-failed name and deletes only the scratch`() {
        val dir = tmp.root
        val db = File(dir, "noteflow.sqlite")
        val originalBytes = "PLAINTEXT NOTEFLOW DATABASE -- THE USER'S ONLY COPY".toByteArray(Charsets.UTF_8)
        db.writeBytes(originalBytes)
        val wal = File(dir, "noteflow.sqlite-wal").apply { writeText("wal-bytes") }
        val shm = File(dir, "noteflow.sqlite-shm").apply { writeText("shm-bytes") }
        val journal = File(dir, "noteflow.sqlite-journal").apply { writeText("journal-bytes") }
        val scratch = File(dir, "noteflow_encrypted.sqlite").apply { writeText("partial-ciphertext") }

        val ts = quarantineMigrateFailed(db, scratch)

        assertFalse("the live plaintext db must be moved away, never remain", db.exists())
        assertFalse("the scratch encrypted copy is the only thing deleted", scratch.exists())

        val renamed = quarantinedFiles(dir)
        assertTrue("original must be quarantined as *.migrate-failed-<ts>", renamed.isNotEmpty())
        val main = renamed.first { it.name == "noteflow.sqlite.migrate-failed-$ts" }
        assertTrue(
            "the original bytes must be preserved byte-for-byte",
            main.readBytes().contentEquals(originalBytes)
        )
        assertTrue("the -wal companion must ride along", renamed.any { it.name == "noteflow.sqlite-wal.migrate-failed-$ts" })
        assertTrue("the -shm companion must ride along", renamed.any { it.name == "noteflow.sqlite-shm.migrate-failed-$ts" })
        assertTrue("the -journal companion must ride along", renamed.any { it.name == "noteflow.sqlite-journal.migrate-failed-$ts" })
    }

    @Test
    fun `migration failure with missing companions still preserves the original`() {
        val dir = tmp.root
        val db = File(dir, "noteflow.sqlite")
        val originalBytes = "ONLY A DB, NO COMPANIONS".toByteArray(Charsets.UTF_8)
        db.writeBytes(originalBytes)
        val scratch = File(dir, "noteflow_encrypted.sqlite").apply { writeText("partial") }

        val ts = quarantineMigrateFailed(db, scratch)

        assertFalse(db.exists())
        assertFalse(scratch.exists())
        val renamed = quarantinedFiles(dir)
        assertEquals("exactly one quarantined db file", 1, renamed.size)
        assertTrue(renamed.first().readBytes().contentEquals(originalBytes))
        assertTrue(renamed.first().name.endsWith(".migrate-failed-$ts"))
    }

    @Test
    fun `migration failure with no scratch file still quarantines the original without throwing`() {
        val dir = tmp.root
        val db = File(dir, "noteflow.sqlite")
        val originalBytes = "DB WITHOUT ANY SCRATCH COPY".toByteArray(Charsets.UTF_8)
        db.writeBytes(originalBytes)
        val missingScratch = File(dir, "noteflow_encrypted.sqlite")

        val ts = quarantineMigrateFailed(db, missingScratch)

        assertFalse(db.exists())
        val renamed = quarantinedFiles(dir)
        assertEquals(1, renamed.size)
        assertTrue(renamed.first().readBytes().contentEquals(originalBytes))
        assertTrue(renamed.first().name.endsWith(".migrate-failed-$ts"))
    }

    @Test
    fun `quarantine returns the timestamp embedded in the migrate-failed suffix`() {
        val dir = tmp.root
        val db = File(dir, "noteflow.sqlite").apply { writeText("x") }
        val scratch = File(dir, "noteflow_encrypted.sqlite").apply { writeText("y") }

        val ts = quarantineMigrateFailed(db, scratch)

        assertTrue("timestamp must be a plausible current-time millis", ts > 0L && ts < Long.MAX_VALUE)
        val renamed = quarantinedFiles(dir)
        assertEquals(1, renamed.size)
        assertTrue("suffix must carry the returned timestamp", renamed.first().name.endsWith(".migrate-failed-$ts"))
    }

    // ---- source-level wiring pins (the Android-bound flow) ------------------

    private val databaseSource by lazy {
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/data/db/NoteflowDatabase.kt").readText()
    }

    /** The `migratePlaintextIfNeeded` body (stub-guard through rethrow). */
    private val migrationRegion: String
        get() = databaseSource
            .substringAfter("private fun migratePlaintextIfNeeded")
            .substringBefore("private class SafeSupportSQLiteOpenHelper")

    @Test
    fun `migration catch preserves the original, raises the corruption flag and rethrows`() {
        val region = migrationRegion

        assertTrue("shared quarantine helper must exist", databaseSource.contains("internal fun quarantineMigrateFailed"))
        assertTrue("the catch must route through the quarantine helper", region.contains("quarantineMigrateFailed(dbFile, tempFile)"))
        assertTrue("the catch must raise the persistent corruption flag", region.contains("DatabaseSecurityHelper.setCorruptionDetected(context, timestamp)"))
        assertTrue("the catch must rethrow so initializeData surfaces the recovery screen", region.contains("throw e"))

        val catchTail = region.substringAfter("} catch (e: Exception) {")
        assertFalse("the catch must NEVER delete the live db file", catchTail.contains("dbFile.delete()"))
        assertFalse("the catch must NEVER delete the -wal companion", catchTail.contains("dbFile.path + \"-wal\") walFile.delete()"))
    }

    @Test
    fun `migration success swaps the verified encrypted file over the original without a delete window`() {
        val region = migrationRegion

        val beforeCatch = region.substringBefore("} catch (e: Exception) {")
        val liveDeletes = Regex("dbFile\\.delete\\(\\)").findAll(beforeCatch).count()
        assertTrue(
            "the only allowed live-db delete is the empty-stub guard at the top (actual=$liveDeletes)",
            liveDeletes == 1
        )
        assertTrue(
            "the scratch file must be verified encrypted before it may replace the original",
            beforeCatch.contains("isPlaintextSqlite(tempFile) || tempFile.length() == 0L")
        )
        assertTrue(
            "the swap itself is a rename over the original (atomic replace, no delete-then-rename window)",
            beforeCatch.contains("tempFile.renameTo(dbFile)")
        )
    }

    @Test
    fun `stale companions are deleted only after the encrypted file is verified in place`() {
        val region = migrationRegion
        val beforeCatch = region.substringBefore("} catch (e: Exception) {")

        val swapIndex = beforeCatch.indexOf("tempFile.renameTo(dbFile)")
        val checksumIndex = beforeCatch.indexOf("DatabaseSecurityHelper.updateStoredChecksum")
        val walDeleteIndex = beforeCatch.indexOf("-wal\")")
        assertTrue("the rename must come before companion cleanup / checksum stamping", swapIndex in 0..checksumIndex)
        assertTrue("wal cleanup must follow the successful swap", walDeleteIndex > swapIndex)
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

    /** Files renamed to the `*.migrate-failed-*` quarantine, sorted by name. */
    private fun quarantinedFiles(dir: File): List<File> =
        (dir.listFiles() ?: emptyArray<File>())
            .filter { it.isFile && it.name.startsWith("noteflow.sqlite") && it.name.contains(".migrate-failed-") }
            .sortedBy { it.name }
}