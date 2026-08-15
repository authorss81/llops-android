package com.authorss81.noteflow

import com.authorss81.noteflow.services.PrivacyCrashReporter
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2-LOG-01 (phase-48): AppStartupLogger's uncaught-exception handler dumped the
 * RAW, unsanitized stack trace to logcat (And `app_startup.log`), and because it
 * was registered after PrivacyCrashReporter it ran FIRST - so the sanitizer never
 * protected the logcat copy. A crash embedding an app-private path (vault layout,
 * note-title filenames) was written VERBATIM, readable by any `adb logcat` /
 * `dumpstate` observer (B2-LOG-01, HIGH, `docs/security-report.md:489`).
 *
 * Fix (single-owner model, the recommended remedy in the finding):
 *  - AppStartupLogger is now a startup-*event* timer only: it installs no crash
 *    handler, owns no stack-dump code, and never passes an exception to logcat.
 *    There is no raw-crash path through it anymore.
 *  - PrivacyCrashReporter is the SOLE uncaught-exception handler and every crash
 *    entry flows through the pure-JVM [PrivacyCrashReporter.crashLogEntry] builder
 *    (sanitized message + scrubbed frames), which the uncaught path persists to
 *    the local file without touching logcat.
 *
 * What is provable on the pure JVM: the sanitization + stack-scrub behavior of the
 * crash entry (no android.util.Log capture here - `Log` is a no-op stateless stub
 * under `isReturnDefaultValues`, so the logcat-absence claim is pinned at source
 * level instead, same technique as SecurityCryptoAbsenceTest / B1Crypto02DekAtRestTest).
 *
 * NOTE: redaction of the REAL runtime data dir (`/data/user/0/com.aistudio.inkflow.app.bkxjrz/...`)
 * is B1-PLAT-5 (phase-89) - out of scope here; the sanitizer still masks the namespace
 * path it is built for, and this phase's HIGH severity (logcat raw dump) is closed by the
 * removal regardless.
 */
class B2Log01CrashReportingTest {

    // ---------- AppStartupLogger: no crash surface left ----------

    @Test
    fun `AppStartupLogger installs no uncaught-exception handler anymore`() {
        val source = readAppStartupLoggerSource()
        assertFalse("must not read the previous handler", source.contains("getDefaultUncaughtExceptionHandler"))
        assertFalse("must not register a crash handler", source.contains("setDefaultUncaughtExceptionHandler"))
        assertFalse("the captured-handler field must be gone", source.contains("defaultHandler"))
        assertFalse("the raw-dump function must be gone", source.contains("logCrash"))
        assertFalse("no full-trace dumping may remain", source.contains("printStackTrace"))
        assertFalse("no StringWriter stack capture may remain", source.contains("StringWriter"))
        assertFalse("no PrintWriter stack capture may remain", source.contains("PrintWriter"))
    }

    @Test
    fun `AppStartupLogger never passes a throwable to logcat`() {
        val source = readAppStartupLoggerSource()
        assertFalse(
            "Log calls must carry fixed messages only - no exception argument that would print its trace",
            source.contains(", e)")
        )
        // The only logcat emission left is the sanitized event line and the two
        // fixed-message file-write failures. Both are static strings never derived
        // from crash or note data.
        assertTrue("startup event timing must still be logged", source.contains("fun logEvent"))
    }

    // ---------- PrivacyCrashReporter: sole owner, sanitized format ----------

    @Test
    fun `crash entry sanitizes app-private paths and the raw trace`() {
        val throwable = FileNotFoundException(
            "/data/user/0/com.authorss81.noteflow/files/noteflow/imports/Cancer-Treatment-Plan_1724567890.md"
        )
        val entry = PrivacyCrashReporter.crashLogEntry("main", throwable, now = 1_700_000_000_000L)

        assertFalse("the raw app-private path must not appear", entry.contains("/data/user/0/"))
        assertFalse("the note-title filename must not appear", entry.contains("Cancer-Treatment-Plan"))
        assertTrue("the redacted token must replace it", entry.contains("[PATH_REDACTED]"))
        assertTrue("the sanitized message must still identify the source", entry.contains("FileNotFoundException"))
    }

    @Test
    fun `crash entry scrubs the trace - no printStackTrace form, no cause chain`() {
        val cause = IllegalArgumentException("root cause: Another-Note_Title.txt")
        val throwable = IllegalStateException(
            "failed importing /data/user/0/com.authorss81.noteflow/files/noteflow/imports/Another-Note_Title.txt",
            cause
        )
        val entry = PrivacyCrashReporter.crashLogEntry("main", throwable, now = 1_700_000_000_000L)

        assertFalse("the raw printStackTrace 'Caused by' chain must not be in the entry", entry.contains("Caused by"))
        assertFalse("the cause's own message must not be in the entry", entry.contains("root cause"))
        assertFalse("the second note-title filename must not be in the entry", entry.contains("Another-Note_Title"))
        // The scrubbed stack keeps only class.method(file:line) frames.
        assertTrue("the scrubbed frame format must be present", entry.contains("at com.authorss81.noteflow.B2Log01CrashReportingTest."))
    }

    @Test
    fun `uncaught path persists a sanitized entry without touching logcat`() {
        val source = readPrivacyCrashReporterSource()
        val uncaughtBody = source.substringAfter("private fun logUncaughtException", "END")
            .substringBefore("private fun writeLogToFile", "END")
        assertTrue(
            "the uncaught path must route through the single sanitized entry builder",
            uncaughtBody.contains("crashLogEntry")
        )
        assertFalse(
            "the uncaught path must never write to logcat (the logcat-safe copy is recordException's job)",
            uncaughtBody.contains("Log.")
        )
    }

    // ---------- repo-wide wiring pin: single crash handler ----------

    @Test
    fun `PrivacyCrashReporter is the only uncaught-exception handler in the app`() {
        val offenders = StringBuilder()
        mainSourceTargets().forEach { file ->
            val text = file.readText()
            if (text.contains("setDefaultUncaughtExceptionHandler")) {
                val isReporter = file.name == "PrivacyCrashReporter.kt"
                if (!isReporter) offenders.append("\n  ").append(relativeToRepo(file))
            }
        }
        assertTrue(
            "Only PrivacyCrashReporter may register an uncaught-exception handler " +
                "(AppStartupLogger's was removed in phase-48):" + offenders,
            offenders.isEmpty()
        )
    }

    // ---------- helpers ----------

    private fun readAppStartupLoggerSource(): String {
        val file = File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/utils/AppStartupLogger.kt"
        )
        assertTrue("AppStartupLogger.kt must exist", file.isFile)
        return file.readText()
    }

    private fun readPrivacyCrashReporterSource(): String {
        val file = File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/services/PrivacyCrashReporter.kt"
        )
        assertTrue("PrivacyCrashReporter.kt must exist", file.isFile)
        return file.readText()
    }

    /** Every production `.kt` under `app/src/main` (repo-root-relative). */
    private fun mainSourceTargets(): List<File> {
        val mainDir = File(repoRoot(), "app/src/main")
        check(mainDir.isDirectory) { "app/src/main must exist" }
        return mainDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
    }

    private fun relativeToRepo(file: File): String =
        file.absolutePath.removePrefix(repoRoot().absolutePath).removePrefix(File.separator)

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