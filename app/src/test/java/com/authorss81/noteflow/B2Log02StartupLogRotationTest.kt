package com.authorss81.noteflow

import com.authorss81.noteflow.services.StartupLogPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * B2-LOG-02 (phase-70) behavioral + wiring tests for the startup-event log cap,
 * rotation and pruning.
 *
 * Finding: `AppStartupLogger.appendToFile` was `FileWriter(logFile, true)` — append-only
 * with no length check, no rotation, no delete — so weeks of lifecycle events (plus, pre
 * phase-48, raw crash dumps) grew `app_startup.log` unboundedly on the same partition as
 * the encrypted vault (ENOSPC pressure) and retained crash history indefinitely.
 *
 * What this proves on the pure JVM:
 *  - the append cycle that `AppStartupLogger` runs (`appendLine` below mirrors it line-for-
 *    line) can NEVER push the active log past StartupLogPolicy.MAX_LOG_BYTES, no matter how
 *    many events are written;
 *  - rotation keeps `MAX_LOG_FILES` generations (active + one backup), so the TOTAL retained
 *    startup log is bounded by 2 * cap regardless of run time — the pre-fix unbounded growth
 *    is structurally impossible;
 *  - the oldest retained generation is dropped, the previous one survives into the backup
 *    slot, and the newest lands in a fresh active file;
 *  - prune-on-init clears an oversized leftover active/backup file (a process killed mid-
 *    rotation cannot leave an over-budget file behind) and leaves under-budget files alone;
 *  - the ~500KB budget is the same one `PrivacyCrashReporter.writeLogToFile` already uses.
 *
 * The Android-bound wiring (AppStartupLogger append routed through the policy, the dead
 * getLogs/clearLogs accessors removed, phase-48's no-crash-surface posture retained) is
 * pinned at source level below.
 */
class B2Log02StartupLogRotationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** Mirrors `AppStartupLogger.appendToFile` exactly: gate BEFORE the write. */
    private fun appendLine(dir: File, line: String) {
        val inactiveFile = StartupLogPolicy.activeFile(dir)
        val incoming = line.toByteArray(Charsets.UTF_8).size.toLong()
        if (inactiveFile.exists() && StartupLogPolicy.wouldExceedCap(inactiveFile.length(), incoming)) {
            StartupLogPolicy.rotateForAppend(dir)
        }
        inactiveFile.appendText(line)
    }

    private fun retainedLogFiles(dir: File): List<File> {
        val entries = dir.listFiles() ?: return emptyList()
        return entries
            .filter { it.isFile && it.name.startsWith(StartupLogPolicy.LOG_FILE_NAME) }
            .sortedBy { it.name }
    }

    // ---------- decision table ----------

    @Test
    fun `wouldExceedCap respects the byte-exact budget boundary`() {
        val cap = StartupLogPolicy.MAX_LOG_BYTES
        assertFalse("exactly-at-cap must not rotate", StartupLogPolicy.wouldExceedCap(cap, 0))
        assertTrue("cap + 1 byte must rotate", StartupLogPolicy.wouldExceedCap(cap, 1))
        assertTrue("cap - 10 + 11 must rotate", StartupLogPolicy.wouldExceedCap(cap - 10, 11))
        assertFalse("cap - 11 + 10 fits", StartupLogPolicy.wouldExceedCap(cap - 11, 10))
        assertTrue("a single over-cap line must rotate", StartupLogPolicy.wouldExceedCap(0, cap + 1))
        assertFalse("empty current + empty line fits", StartupLogPolicy.wouldExceedCap(0, 0))
    }

    // ---------- the core: never past the cap ----------

    @Test
    fun `sustained event writes never grow the active log past the cap`() {
        val dir = tmp.root
        val line = "[2026-08-16 12:00:00.001] EVENT: MainActivity.onCreate started\n"
        repeat(25_000) {
            appendLine(dir, line)
        }
        val activeLength = StartupLogPolicy.activeFile(dir).length()
        assertTrue(
            "active log exceeded the cap: $activeLength > ${StartupLogPolicy.MAX_LOG_BYTES}",
            activeLength <= StartupLogPolicy.MAX_LOG_BYTES
        )
    }

    @Test
    fun `rotation bounds total retained bytes and keeps exactly keep-last-N files`() {
        val dir = tmp.root
        val line = "[2026-08-16 12:00:00.001] EVENT: MainActivity.onCreate started\n"
        repeat(25_000) {
            appendLine(dir, line)
        }
        val files = retainedLogFiles(dir)
        assertEquals(
            "keep-last-N must leave exactly the active + one backup",
            2,
            files.count { it.name in setOf("app_startup.log", "app_startup.log.1") }
        )
        val totalBytes = files.sumOf { it.length() }
        assertTrue(
            "total retained startup log must stay within 2 * cap (was $totalBytes)",
            totalBytes <= StartupLogPolicy.MAX_LOG_BYTES * StartupLogPolicy.MAX_LOG_FILES
        )
        assertTrue(
            "the backup generation itself must respect the cap",
            StartupLogPolicy.backupFile(dir).length() <= StartupLogPolicy.MAX_LOG_BYTES
        )
    }

    @Test
    fun `rotation keeps the previous generation and drops the oldest`() {
        val dir = tmp.root
        StartupLogPolicy.activeFile(dir).writeText("generation-1")
        StartupLogPolicy.rotateForAppend(dir)
        StartupLogPolicy.activeFile(dir).writeText("generation-2")
        StartupLogPolicy.rotateForAppend(dir)
        StartupLogPolicy.activeFile(dir).writeText("generation-3")

        assertEquals("generation-3", StartupLogPolicy.activeFile(dir).readText())
        assertEquals("generation-2", StartupLogPolicy.backupFile(dir).readText())
        assertEquals(
            "the oldest generation must be dropped, leaving exactly active + backup",
            setOf("app_startup.log", "app_startup.log.1"),
            retainedLogFiles(dir).map { it.name }.toSet()
        )
    }

    @Test
    fun `first-ever write creates the active file without rotating`() {
        val dir = tmp.root
        appendLine(dir, "[ts] EVENT: AppStartupLogger initialized\n")
        assertTrue("active file must exist after the first event", StartupLogPolicy.activeFile(dir).isFile)
        assertFalse("no backup may exist before any rotation", StartupLogPolicy.backupFile(dir).exists())
        assertTrue(
            "the event must be in the active file",
            StartupLogPolicy.activeFile(dir).readText().contains("AppStartupLogger initialized")
        )
    }

    // ---------- prune on init ----------

    @Test
    fun `pruneOnInit drops an oversized leftover backup and active file`() {
        val dir = tmp.root
        val oversized = StringBuilder()
        repeat(600) { oversized.append("x".repeat(1000)) } // 600k bytes > 500k cap
        StartupLogPolicy.backupFile(dir).writeText(oversized.toString())
        StartupLogPolicy.activeFile(dir).writeText(oversized.toString())

        StartupLogPolicy.pruneOnInit(dir)

        assertFalse("oversized leftover backup must be pruned", StartupLogPolicy.backupFile(dir).exists())
        assertFalse("oversized leftover active file must be pruned", StartupLogPolicy.activeFile(dir).exists())
    }

    @Test
    fun `pruneOnInit leaves under-budget files untouched`() {
        val dir = tmp.root
        StartupLogPolicy.activeFile(dir).writeText("[ts] EVENT: healthy active\n")
        StartupLogPolicy.backupFile(dir).writeText("[ts] EVENT: healthy backup\n")

        StartupLogPolicy.pruneOnInit(dir)

        assertTrue("under-budget active file must survive pruning", StartupLogPolicy.activeFile(dir).isFile)
        assertTrue("under-budget backup must survive pruning", StartupLogPolicy.backupFile(dir).isFile)
        assertEquals(
            "healthy backup content must be untouched",
            "[ts] EVENT: healthy backup\n",
            StartupLogPolicy.backupFile(dir).readText()
        )
    }

    // ---------- constants / shared budget ----------

    @Test
    fun `budget and file-name constants are the policy's contract`() {
        assertEquals("app_startup.log", StartupLogPolicy.LOG_FILE_NAME)
        assertEquals(500_000L, StartupLogPolicy.MAX_LOG_BYTES)
        assertEquals(2, StartupLogPolicy.MAX_LOG_FILES)
        assertEquals("app_startup.log.1", StartupLogPolicy.backupFile(tmp.root).name)
        assertEquals("app_startup.log", StartupLogPolicy.activeFile(tmp.root).name)
    }

    @Test
    fun `the ~500KB budget matches PrivacyCrashReporter's existing cap`() {
        assertEquals(
            "the cap must be the same magnitude PrivacyCrashReporter already uses (500KB)",
            500_000L,
            StartupLogPolicy.MAX_LOG_BYTES
        )
        assertTrue(
            "PrivacyCrashReporter must still enforce its 500KB wipe",
            readPrivacyCrashReporterSource().contains("> 500_000")
        )
    }

    // ---------- source-level wiring pins ----------

    @Test
    fun `AppStartupLogger append path is rotation gated before every write`() {
        val source = readAppStartupLoggerSource()
        assertTrue("append must route through the policy's active file", source.contains("StartupLogPolicy.activeFile"))
        assertTrue("append must be capped", source.contains("StartupLogPolicy.wouldExceedCap"))
        assertTrue("append must rotate on size", source.contains("StartupLogPolicy.rotateForAppend"))
        assertTrue("init must prune on init", source.contains("StartupLogPolicy.pruneOnInit"))
        // The gate must sit BEFORE the (bounded) FileWriter so a line is never
        // appended across a rotation boundary.
        val gateIndex = source.indexOf("StartupLogPolicy.wouldExceedCap")
        val writerIndex = source.indexOf("FileWriter(logFile, true)")
        assertTrue("gate must precede the append writer", gateIndex in 0 until writerIndex)
    }

    @Test
    fun `dead getLogs and clearLogs accessors are removed`() {
        val source = readAppStartupLoggerSource()
        assertFalse("getLogs must be removed", source.contains("fun getLogs"))
        assertFalse("clearLogs must be removed", source.contains("fun clearLogs"))
        assertFalse("no raw readText log dump may remain", source.contains("readText()"))
        assertFalse("no 'No logs available.' fallback may remain", source.contains("No logs available."))
    }

    @Test
    fun `the startup log file name lives only in the policy, not AppStartupLogger`() {
        val source = readAppStartupLoggerSource()
        assertFalse("AppStartupLogger must not re-declare its own LOG_FILE_NAME", source.contains("private const val LOG_FILE_NAME"))
        assertTrue("AppStartupLogger must resolve the file via the policy", source.contains("StartupLogPolicy.activeFile"))
    }

    @Test
    fun `AppStartupLogger keeps the phase-48 no-crash-surface posture`() {
        val source = readAppStartupLoggerSource()
        assertFalse("no crash handler may return", source.contains("setDefaultUncaughtExceptionHandler"))
        assertFalse("no raw dump may return", source.contains("printStackTrace"))
        assertFalse("no raw dump may return", source.contains("logCrash"))
        assertFalse("no throwable may reach logcat", source.contains(", e)"))
        assertTrue("startup event timing must still be logged", source.contains("fun logEvent"))
    }

    @Test
    fun `apply capability physically cannot be reached via StartupLogPolicy call sites`() {
        // The policy is pure JVM: zero android.*/Room/SQLCipher references, so it can
        // never touch the vault, DEK, passwords or decrypted content.
        val source = readStartupLogPolicySource()
        assertFalse(source.contains("android."))
        assertFalse(source.contains("NoteflowDatabase"))
        assertFalse(source.contains("EncryptionService"))
        assertFalse(source.contains("VaultKeyHolder"))
    }

    // ---------- helpers ----------

    private fun readAppStartupLoggerSource(): String =
        File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/utils/AppStartupLogger.kt"
        ).let { check(it.isFile) { "AppStartupLogger.kt must exist" }; it.readText() }

    private fun readStartupLogPolicySource(): String =
        File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/services/StartupLogPolicy.kt"
        ).let { check(it.isFile) { "StartupLogPolicy.kt must exist" }; it.readText() }

    private fun readPrivacyCrashReporterSource(): String =
        File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/services/PrivacyCrashReporter.kt"
        ).let { check(it.isFile) { "PrivacyCrashReporter.kt must exist" }; it.readText() }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir: File? = cwd
        repeat(8) {
            dir?.let {
                if (File(it, "gradle/libs.versions.toml").isFile && File(it, "app").isDirectory) {
                    return it
                }
                dir = it.parentFile
            }
        }
        return cwd
    }
}