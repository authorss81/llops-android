package com.authorss81.noteflow

import com.authorss81.noteflow.services.DatabaseHmacPolicy
import com.authorss81.noteflow.services.DatabaseIntegrityPolicy
import com.authorss81.noteflow.services.DatabaseIntegrityVerdict
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * R2-B1D-01 (phase-136): the tamper baseline is re-armed at every SESSION END,
 * not only at event-driven mutations.
 *
 * Finding: the WAL-aware HMAC baseline (`noteflow.sqlite` + `-wal`, B1-DB-6) was
 * armed only at fresh-vault creation / migration / re-encrypt / backup / restore,
 * ever. The vault run schedule meant ordinary note edits leave committed-but-
 * uncheckpointed WAL frames on disk; with no re-arm at the (master-password)
 * session end, the next process start verified those frames against a baseline
 * that predated them and raised a FALSE "Database integrity check failed"
 * banner for a completely ordinary edit.
 *
 * Fix shape: `NoteflowDatabase.dispose()` — the single session-end funnel
 * (master-password lock, app exit, restore swap, reopen-after-lock) — now
 * FULL-checkpoints the WAL while the keyed connection is live, closes the vault,
 * and re-arms the stored baseline against the quiescent file. The session's own
 * writes are therefore part of the baseline, and a verification only ever
 * trips on bytes that changed while the app was NOT running.
 *
 * What is provable on the pure JVM:
 *  - the cadence decision: arm -> in-session edit -> session-end re-arm ->
 *    verify is [DatabaseIntegrityVerdict.Verified]; without the session-end
 *    re-arm the same edit is [DatabaseIntegrityVerdict.Mismatch] (the tripwire
 *    still works for post-exit changes).
 *  - the checkpoint folds WAL-only session frames into the baseline, and a
 *    fully-checkpointed (empty) `-wal` never moves the re-armed baseline.
 *  - source pins hold that the re-arm runs inside `NoteflowDatabase.dispose()`
 *    AFTER the FULL checkpoint and AFTER the connection closes, that the
 *    master-password `lock()` and the app-exit / reopen / restore paths all
 *    funnel through that one disposal, and that the re-arm never leaks into
 *    `verifyDatabaseIntegrity` (verification still never re-baselines).
 */
class Phase136TamperBaselineCadenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val hmacKey = SecretKeySpec("Phase136CadenceKey".toByteArray(Charsets.UTF_8), "HmacSHA256")

    /** Mirrors `DatabaseSecurityHelper.computeDatabaseHmac` (main + `-wal` via the policy). */
    private fun baselineHex(dbFile: File): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(hmacKey)
        val consumed = DatabaseHmacPolicy.streamDbAndWal(mac, dbFile)
        assertTrue("the fixture must have a readable main file", consumed > 0L)
        return mac.doFinal().joinToString("") { "%02x".format(it) }
    }

    private fun writeMain(dir: File, content: ByteArray): File {
        val f = File(dir, "noteflow.sqlite")
        f.writeBytes(content)
        return f
    }

    private fun mainContent(): ByteArray = ByteArray(12_288) { (it % 251).toByte() }

    /** Simulates an in-session edit committed to the WAL (main file untouched). */
    private fun walFrames(): ByteArray {
        val header = ByteArray(32) { 0x5a.toByte() }
        val frames = ByteArray(16_384) { (it % 253).toByte() }
        return header + frames
    }

    // ---------- the cadence: session-end re-arm clears ordinary edits ----------

    @Test
    fun `a session-end re-arm after an in-session edit verifies clean`() {
        val dir = tmp.newFolder()
        val dbFile = writeMain(dir, mainContent())
        val storedBaseline = baselineHex(dbFile)

        // In-session edit: main file changed while the vault was active.
        dbFile.writeBytes(dbFile.readBytes().also { it[0] = (it[0] + 7).toByte() })

        // Session end (lock / exit): the teardown re-arms against the quiescent state.
        val rearMedBaseline = baselineHex(dbFile)

        assertTrue("an in-session edit must move the file state", storedBaseline != rearMedBaseline)
        assertEquals(
            "the session-end re-arm must put the session's own writes INTO the baseline",
            DatabaseIntegrityVerdict.Verified,
            DatabaseIntegrityPolicy.verdictFor(rearMedBaseline, baselineHex(dbFile))
        )
    }

    @Test
    fun `without the session-end re-arm the same in-session edit trips the tripwire`() {
        val dir = tmp.newFolder()
        val dbFile = writeMain(dir, mainContent())
        val storedBaseline = baselineHex(dbFile)

        dbFile.writeBytes(dbFile.readBytes().also { it[0] = (it[0] + 7).toByte() })

        assertEquals(
            "a baseline armed BEFORE the session's only edit must flag the edit — " +
                "proving the cadence (not the tripwire) is what changed",
            DatabaseIntegrityVerdict.Mismatch,
            DatabaseIntegrityPolicy.verdictFor(storedBaseline, baselineHex(dbFile))
        )
    }

    @Test
    fun `the checkpoint folds WAL-only session frames into the re-armed baseline`() {
        val dir = tmp.newFolder()
        val dbFile = writeMain(dir, mainContent())
        val preEditBaseline = baselineHex(dbFile)

        // In-session edits land in the -wal (committed but uncheckpointed).
        DatabaseHmacPolicy.walFile(dbFile).writeBytes(walFrames())
        assertTrue(
            "the WAL-only edit must move the authenticated state",
            preEditBaseline != baselineHex(dbFile)
        )

        // Session end: FULL checkpoint + re-arm folds (main + wal) into the baseline.
        val rearMedBaseline = baselineHex(dbFile)
        assertEquals(
            "the session-end re-arm must cover the WAL frames, so the next verification is clean",
            DatabaseIntegrityVerdict.Verified,
            DatabaseIntegrityPolicy.verdictFor(rearMedBaseline, baselineHex(dbFile))
        )
    }

    @Test
    fun `a fully checkpointed empty WAL never moves the re-armed baseline`() {
        val dir = tmp.newFolder()
        val dbFile = writeMain(dir, mainContent())

        val noWal = baselineHex(dbFile)
        DatabaseHmacPolicy.walFile(dbFile).writeBytes(ByteArray(0))

        assertEquals(
            "a cleanly-emptied -wal (the dispose checkpoint outcome) is part of the " +
                "same authenticated state the fresh baseline covered",
            noWal,
            baselineHex(dbFile)
        )
        assertEquals(
            DatabaseIntegrityVerdict.Verified,
            DatabaseIntegrityPolicy.verdictFor(noWal, baselineHex(dbFile))
        )
    }

    // ---------- source-level wiring pins ----------

    @Test
    fun `dispose checkpoints closes then schedules the re-arm and getDatabase joins it`() {
        val db = readSource("data/db/NoteflowDatabase.kt")
        val disposeBody = db.substringAfter("fun dispose()")
        val checkpointIdx = disposeBody.indexOf("PRAGMA wal_checkpoint(FULL)")
        val closeIdx = disposeBody.indexOf("db.close()")
        val nullIdx = disposeBody.indexOf("INSTANCE = null")
        assertTrue("dispose must FULL-checkpoint the WAL", checkpointIdx >= 0)
        assertTrue("dispose must fully step the checkpoint cursor", disposeBody.contains("while (cursor.moveToNext())"))
        assertTrue("dispose must close the live connection", closeIdx >= 0)
        assertTrue(
            "checkpoint and close must run BEFORE the instance is forgotten",
            checkpointIdx in 0 until nullIdx && closeIdx in 0 until nullIdx && checkpointIdx < closeIdx
        )
        assertTrue(
            "the close must be best-effort so a close failure never leaks the teardown",
            disposeBody.contains("runCatching { db.close() }")
        )
        val scheduleIdx = disposeBody.indexOf("CompletableFuture.runAsync")
        assertTrue(
            "the full-file re-arm must run on the re-arm executor AFTER the instance is forgotten",
            scheduleIdx in 0 until disposeBody.length && scheduleIdx > nullIdx
        )
        assertTrue(
            "the disposal re-arm must be best-effort and never break teardown",
            disposeBody.contains("runCatching { DatabaseSecurityHelper.updateStoredChecksum(ctx) }")
        )
        assertTrue(
            "the re-arm needs the cached app context for the checksum prefs",
            disposeBody.contains("cachedAppContext") && disposeBody.contains("pendingRearm")
        )

        val getDbBody = db.substringAfter("fun getDatabase(context: Context): NoteflowDatabase {")
            .substringBefore("fun dispose()")
        assertTrue(
            "the app context must be cached when the database is built",
            getDbBody.indexOf("cachedAppContext = context.applicationContext") >= 0
        )
        val pendingIdx = getDbBody.indexOf("pendingRearm")
        val builderIdx = getDbBody.indexOf("Room.databaseBuilder")
        assertTrue(
            "getDatabase must join the pending session-end re-arm before reopening",
            pendingIdx >= 0 && getDbBody.indexOf(".join()", pendingIdx) in (pendingIdx + 1)..builderIdx
        )
        assertTrue("the join must precede the vault rebuild", pendingIdx < builderIdx)
    }

    @Test
    fun `lock disposes only inside the master-password branch and onCleared awaits the re-arm`() {
        val vm = readSource("ui/viewmodel/NoteflowViewModel.kt")
        val lockBody = vm.substringAfter("fun lock()")
            .substringBefore("override fun onCleared()", "END")

        val mpIdx = lockBody.indexOf("if (settings.hasMasterPassword) {")
        val disposeIdx = lockBody.indexOf("NoteflowDatabase.dispose()")
        assertTrue("lock() must gate the teardown on the master-password vault", mpIdx >= 0)
        assertTrue("lock() must route the master-password session end through dispose()", disposeIdx >= 0)
        assertTrue(
            "the teardown/re-arm may only run when a master-password vault locks",
            mpIdx < disposeIdx
        )
        assertTrue(
            "the lock is the only in-session disposal inside lock()",
            lockBody.indexOf("NoteflowDatabase.dispose()", disposeIdx + 1) == -1
        )

        val onCleared = vm.substringAfter("override fun onCleared()", "END")
        assertTrue("app exit must funnel through the same disposal re-arm", onCleared.contains("NoteflowDatabase.dispose()"))
        assertTrue(
            "app exit must await the pending re-arm so the daemon-executor re-arm is durable",
            onCleared.contains("NoteflowDatabase.awaitPendingRearm()")
        )
        assertTrue("app exit must still zeroize the DEK", onCleared.contains("repository.zeroizeKey()"))
    }

    @Test
    fun `the re-arm helpers persist synchronously with commit not apply`() {
        val helper = readSource("services/DatabaseSecurityHelper.kt")
        val update = helper.substringAfter("fun updateStoredChecksum(context: Context)")
            .substringBefore("fun rearmBaselineFromFile", "END")
        val rearm = helper.substringAfter("fun rearmBaselineFromFile(context: Context, dbFile: File): Boolean")
            .substringBefore("fun hasRestoreBlock", "END")
        assertTrue("updateStoredChecksum must commit synchronously (durable baseline)", update.contains(".commit()"))
        assertFalse("updateStoredChecksum must not use async apply()", update.contains(".apply()"))
        assertTrue("rearmBaselineFromFile must commit synchronously (durable baseline)", rearm.contains(".commit()"))
        assertFalse("rearmBaselineFromFile must not use async apply()", rearm.contains(".apply()"))
    }

    @Test
    fun `the restore and reopen paths funnel through the same disposal`() {
        val repo = readSource("data/repository/NoteRepository.kt")
        val closeDb = repo.substringAfter("fun closeDatabase()", "END").substringBefore("fun reopenDatabase", "END")
        val reopen = repo.substringAfter("fun reopenDatabase(", "END")
        assertTrue("closeDatabase (pre-restore swap) must dispose", closeDb.contains("NoteflowDatabase.dispose()"))
        assertTrue("reopenDatabase (recover/reopen) must dispose then rebuild", reopen.contains("NoteflowDatabase.dispose()"))
    }

    @Test
    fun `verification still never re-baselines`() {
        val helper = readSource("services/DatabaseSecurityHelper.kt")
        val verifyBody = helper.substringAfter("fun verifyDatabaseIntegrity(", "END")
            .substringBefore("}", "END")
        assertFalse(
            "the session-end re-arm must not leak into verification — verify NEVER re-baselines",
            verifyBody.contains("updateStoredChecksum")
        )
        assertFalse(
            "the session-end re-arm must not leak into verification — verify NEVER re-baselines",
            verifyBody.contains("rearmBaselineFromFile")
        )
    }

    // ---------- helpers ----------

    private fun readSource(relative: String): String {
        val file = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative")
        assertTrue("$relative must exist", file.isFile)
        return file.readText()
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