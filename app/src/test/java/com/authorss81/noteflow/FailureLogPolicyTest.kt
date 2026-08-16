package com.authorss81.noteflow

import com.authorss81.noteflow.services.FailureLogPolicy
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2-LOG-03 (phase-71): every import/export failure `Log.e("ImportExportService", ..., e)`
 * call in `services/ImportExportService.kt` passed the exception OBJECT, so logcat
 * received the full throwable — whose message text embeds app-private file paths
 * whose filenames ARE note titles (the sanitized `<note_title>_<ts>.md` under
 * `filesDir/noteflow/imports/`, per B1-DB-4). `adb logcat`/`dumpstate` observers
 * learned real note titles + the vault file layout, and none of it ever passed
 * through PrivacyCrashReporter's sanitizer (B2-LOG-03, MEDIUM,
 * `docs/security-report.md:517`).
 *
 * Fix: the pure-JVM [FailureLogPolicy] is the single decision table — a failure
 * may only be logged by its exception CLASS NAME, never the exception object /
 * message / stack trace. Every `Log.e` call site in ImportExportService.kt now
 * emits `FailureLogPolicy.safeLogMessage(e, "<fixed operation label>")`, a 2-arg
 * `Log.e` whose message never contains path-carrying throwable text.
 *
 * What this proves on the pure JVM:
 *  - [FailureLogPolicy] output is guaranteed free of any throwable message text,
 *    note-title filenames and absolute app-private paths, even when the exception
 *    message is built from a real export/import failure;
 *  - the source-pin below verifies MECHANICALLY that no `Log.(e|w)` call in
 *    ImportExportService.kt can ever again pass a throwable: every call has
 *    exactly TWO arguments (tag + message) and routes through `FailureLogPolicy`
 *    — there is no 3-arg form in the file for a throwable to slip through.
 */
class FailureLogPolicyTest {

    // ---------- decision table: class name only, never message text ----------

    @Test
    fun `safeLogMessage emits a fixed operation label plus the exception class name only`() {
        val failure = FileNotFoundException("some file")
        val line = FailureLogPolicy.safeLogMessage(failure, "Failed to import HTML file")
        assertTrue("the fixed operation label must be present", line.contains("Failed to import HTML file"))
        assertTrue("the exception class name must be present", line.contains("FileNotFoundException"))
        assertEquals("Failed to import HTML file (FileNotFoundException)", line)
    }

    @Test
    fun `a path-carrying FileNotFoundException never leaks its message into the log line`() {
        // The B2-LOG-03 / B1-DB-4 shape verbatim: an export/import failure whose
        // message embeds the sanitized note-title import filename.
        val failure = FileNotFoundException(
            "/data/user/0/com.aistudio.inkflow.app.bkxjrz/files/noteflow/imports/" +
                "Cancer_Treatment_Plan_1724567890.md (No such file or directory)"
        )
        val line = FailureLogPolicy.safeLogMessage(failure, "Failed to import HTML file")

        assertFalse("the absolute app-private data dir must not appear", line.contains("/data/user/0/"))
        assertFalse("the applicationId dir must not appear", line.contains("com.aistudio.inkflow.app.bkxjrz"))
        assertFalse("the note-title filename must not appear", line.contains("Cancer_Treatment_Plan"))
        assertFalse("the os detail must not appear", line.contains("No such file or directory"))
        assertFalse("no throwable message text may survive", line.contains(failure.message.orEmpty()))
        // The sanitized line keeps only the class name + fixed label.
        assertTrue(line.contains("FileNotFoundException"))
        assertTrue(line.contains("Failed to import HTML file"))
    }

    @Test
    fun `underscore and dash note-title variants are both stripped`() {
        val dash = FileNotFoundException("import -> /data/user/0/us/0/files/noteflow/imports/Surgery_Checklist_1.md")
        val underscore = FileNotFoundException("import -> /data/user/0/us/0/files/noteflow/imports/Surgery-Checklist-1.md")
        assertFalse(FailureLogPolicy.safeLogMessage(dash, "x").contains("Surgery_Checklist"))
        assertFalse(FailureLogPolicy.safeLogMessage(underscore, "x").contains("Surgery-Checklist"))
    }

    @Test
    fun `nested causes cannot leak either`() {
        val cause = IllegalArgumentException("root cause: Another-Note_Title.txt")
        val failure = IllegalStateException("failed importing Another-Note_Title.txt", cause)
        val line = FailureLogPolicy.safeLogMessage(failure, "Failed to parse docx")
        assertFalse("the cause message must not leak", line.contains("root cause"))
        assertFalse("the second note-title must not leak", line.contains("Another-Note_Title"))
        assertFalse("the outer message must not leak", line.contains("failed importing"))
        assertTrue("the outer class name is the only failure detail", line.contains("IllegalStateException"))
    }

    @Test
    fun `anonymous exception classes fall back to a fixed token`() {
        val anon = object : FileNotFoundException("anonymous path") {}
        assertEquals("Exception", FailureLogPolicy.classNameToken(anon))
        assertTrue(FailureLogPolicy.safeLogMessage(anon, "op").contains("(Exception)"))
    }

    @Test
    fun `class name token never contains message or path data`() {
        val failure = FileNotFoundException("/data/user/0/app/files/noteflow/imports/Note_Title.md")
        val token = FailureLogPolicy.classNameToken(failure)
        assertEquals("FileNotFoundException", token)
        assertFalse(token.contains("Note_Title"))
        assertFalse(token.contains("imports"))
        assertFalse(token.contains("/"))
    }

    // ---------- source pins: ImportExportService never passes a throwable ----------

    @Test
    fun `every Log call in ImportExportService is 2-argument and routed through FailureLogPolicy`() {
        val source = readImportExportServiceSource()
        val bodies = logCallBodies(source)
        assertTrue(
            "the file must contain its import/export Log calls (11 audited B2-LOG-03 sites)",
            bodies.isNotEmpty()
        )
        bodies.forEach { body ->
            assertTrue("every log call must route through the sanitizer: $body", body.contains("FailureLogPolicy"))
            assertEquals(
                "a Log call must take exactly TWO arguments (tag + sanitized message); a throwable would " +
                    "be a third arg that prints its path-carrying trace: $body",
                1,
                topLevelCommas(body)
            )
            assertFalse(
                "no Log call may end in a bare throwable argument (the 3-arg pre-fix shape): $body",
                body.endsWith(", e)")
            )
        }
    }

    @Test
    fun `all 11 B2-LOG-03 operation labels are present with sanitized messages`() {
        val source = readImportExportServiceSource()
        val expected = listOf(
            "Failed to export annotated page",
            "Failed to export document as PDF",
            "Failed to parse docx",
            "Failed to export vault to ZIP",
            "Failed to import HTML file",
            "Failed to import HTML ZIP folder",
            "Failed to export note to HTML",
            "Failed to export vault HTML site",
            "Failed to import Obsidian Vault ZIP",
            "Failed to export Obsidian Vault ZIP",
            "Failed to export page to PSD"
        )
        expected.forEach { operation ->
            assertTrue(
                "operation label must still be surfaced as a FIXED log message, sanitized: $operation",
                source.contains("FailureLogPolicy.safeLogMessage(e, \"$operation\")")
            )
        }
    }

    // ---------- helpers ----------

    /** Every `Log.X(...)` call body in the file (paren-balanced from the opening paren). */
    private fun logCallBodies(source: String): List<String> {
        val bodies = mutableListOf<String>()
        var i = 0
        while (i < source.length) {
            val kw = source.indexOf("Log.", i)
            if (kw < 0) break
            val open = source.indexOf('(', kw)
            if (open < 0) break
            var depth = 0
            var j = open
            while (j < source.length) {
                when (source[j]) {
                    '(' -> depth++
                    ')' -> {
                        depth--
                        if (depth == 0) {
                            bodies += source.substring(kw, j + 1)
                            break
                        }
                    }
                }
                j++
            }
            i = open + 1
        }
        return bodies
    }

    /** Number of commas at argument depth (direct children of the call's first paren). */
    private fun topLevelCommas(body: String): Int {
        var commas = 0
        var depth = 0
        for (c in body) {
            when (c) {
                '(' -> depth++
                ')' -> depth--
                ',' -> if (depth == 1) commas++
            }
        }
        return commas
    }

    private fun readImportExportServiceSource(): String {
        val file = File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt"
        )
        assertTrue("ImportExportService.kt must exist", file.isFile)
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