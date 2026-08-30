package com.authorss81.noteflow

import com.authorss81.noteflow.services.UiFailureTextPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-b2b3-LOG-01/-02/-03 (phase-148): no raw `${e.message}` / attacker-carried
 * text may reach a user-facing restore/recovery/backup/import surface or logcat.
 *
 * Pre-fix, a crafted NFLB3 archive whose inner zip entry name was hostile was
 * interpolated verbatim into `Backup contains unsafe relative path: $entryName`
 * and rendered raw in the restart dialog / recovery screens; SQLCipher/file-IO
 * exceptions carried `/data/user/0/...` paths into UI text; `VoiceNoteManager`
 * echoed `e.message` (app-private vault/cache paths) into logcat at 8 sites; and
 * `ProtobufBrushLoader` echoed `e.message` + the caller-supplied brush name.
 *
 * These tests exercise the new pure-JVM decision table [UiFailureTextPolicy]
 * (every branch returns a FIXED constant — the exception message is read for
 * classification only) and source-pin every named file to the sanitized form.
 */
class Phase148UiFailureTextScrubTest {

    // --- policy: restoreFailureMessage ----------------------------------------

    @Test
    fun `restoreFailureMessage maps an unsafe path to a FIXED refusal and never echoes the entry name`() {
        val hostile = IllegalStateException("Backup contains unsafe relative path: ../../evil-secret-notes.md")
        val text = UiFailureTextPolicy.restoreFailureMessage(hostile)
        assertEquals(UiFailureTextPolicy.RESTORE_UNSAFE_PATH_TEXT, text)
        for (fragment in listOf("evil", "secret", "notes", "..", "/")) {
            assertFalse("the entry name must never be echoed: $text", text.contains(fragment))
        }
    }

    @Test
    fun `restoreFailureMessage never surfaces an absolute vault path`() {
        val leaky = IllegalStateException(
            "SQLiteException: file is not a database (code 26): /data/user/0/com.aistudio.inkflow.app.bkxjrz/databases/noteflow.sqlite"
        )
        val text = UiFailureTextPolicy.restoreFailureMessage(leaky)
        assertFalse("an absolute path must never surface: $text", text.contains("/data/user/0"))
        assertFalse("the package directory name must never surface: $text", text.contains("com.aistudio"))
        assertFalse("a path token must never surface: $text", text.contains("databases"))
    }

    @Test
    fun `restoreFailureMessage classifies known restore failures into fixed text`() {
        val cases = listOf(
            IllegalStateException("Incorrect backup password.") to UiFailureTextPolicy.RESTORE_PASSWORD_TEXT,
            IllegalStateException("This backup is protected by a password. Enter the backup password to restore.") to
                UiFailureTextPolicy.RESTORE_PASSWORD_TEXT,
            IllegalStateException("Restore rejected: this is an unencrypted (unsigned) backup. Only password-protected backups can be restored.") to
                UiFailureTextPolicy.RESTORE_UNENCRYPTED_TEXT,
            IllegalStateException("Backup contains no noteflow.sqlite database entry.") to
                UiFailureTextPolicy.RESTORE_NO_DATABASE_TEXT,
            IllegalStateException("Restore rejected: the backup's database is empty.") to
                UiFailureTextPolicy.RESTORE_EMPTY_DATABASE_TEXT,
            IllegalStateException("Backup appears corrupted: could not unlock the backup key.") to
                UiFailureTextPolicy.RESTORE_CORRUPT_TEXT,
            IllegalStateException("Backup appears corrupted: the header and the encrypted payload do not match.") to
                UiFailureTextPolicy.RESTORE_CORRUPT_TEXT,
            IllegalStateException("Restore rejected: the backup database is corrupt or was created on a different device.") to
                UiFailureTextPolicy.RESTORE_CORRUPT_TEXT,
            IllegalStateException("Restore rejected: this backup was created by a newer version of the app (database schema 12, this app supports 9).") to
                UiFailureTextPolicy.RESTORE_NEWER_APP_TEXT,
            IllegalStateException("Backup file too large (max 400MB).") to
                UiFailureTextPolicy.BACKUP_TOO_LARGE_TEXT,
            IllegalStateException("Cannot restore: no data key available on this device.") to
                UiFailureTextPolicy.RESTORE_NO_DEVICE_KEY_TEXT,
            IllegalStateException("The vault locked before the restore — please unlock and try again.") to
                UiFailureTextPolicy.RESTORE_LOCKED_TEXT,
            IllegalStateException("Could not read the selected backup file.") to
                UiFailureTextPolicy.RESTORE_UNREADABLE_FILE_TEXT
        )
        for ((e, expected) in cases) {
            assertEquals("classification of ${e.message}", expected, UiFailureTextPolicy.restoreFailureMessage(e))
        }
    }

    @Test
    fun `restoreFailureMessage maps an empty-vault decision to fixed text`() {
        val e = IllegalStateException(
            "This backup contains an EMPTY vault (no notes). Restoring it would replace everything with an empty vault."
        )
        assertEquals(UiFailureTextPolicy.RESTORE_EMPTY_VAULT_TEXT, UiFailureTextPolicy.restoreFailureMessage(e))
    }

    @Test
    fun `restoreFailureMessage falls back to generic fixed text with a crash-y unknown exception`() {
        val e = IllegalStateException("random internal detail /tmp/note1.md")
        val text = UiFailureTextPolicy.restoreFailureMessage(e)
        assertEquals(UiFailureTextPolicy.RESTORE_FAILED_GENERIC, text)
        assertFalse(text.contains("note1"))
    }

    @Test
    fun `the fixed restore constants never interpolate`() {
        val constants = listOf(
            UiFailureTextPolicy.RESTORE_FAILED_GENERIC,
            UiFailureTextPolicy.RESTORE_PASSWORD_TEXT,
            UiFailureTextPolicy.RESTORE_UNENCRYPTED_TEXT,
            UiFailureTextPolicy.RESTORE_UNSAFE_PATH_TEXT,
            UiFailureTextPolicy.RESTORE_NO_DATABASE_TEXT,
            UiFailureTextPolicy.RESTORE_EMPTY_DATABASE_TEXT,
            UiFailureTextPolicy.RESTORE_CORRUPT_TEXT,
            UiFailureTextPolicy.RESTORE_NEWER_APP_TEXT,
            UiFailureTextPolicy.BACKUP_TOO_LARGE_TEXT,
            UiFailureTextPolicy.RESTORE_EMPTY_VAULT_TEXT,
            UiFailureTextPolicy.RESTORE_LOCKED_TEXT,
            UiFailureTextPolicy.RESTORE_UNREADABLE_FILE_TEXT,
            UiFailureTextPolicy.RESTORE_NO_DEVICE_KEY_TEXT
        )
        for (const in constants) {
            assertFalse("a fixed restore constant must never interpolate: $const", const.contains("\${"))
        }
    }

    // --- policy: recoveryMessage ----------------------------------------------

    @Test
    fun `recoveryMessage maps unknown failures to its generic and known ones to fixed restore text`() {
        assertEquals(
            UiFailureTextPolicy.RECOVERY_FAILED_GENERIC,
            UiFailureTextPolicy.recoveryMessage(IllegalStateException("weird platform error"))
        )
        assertEquals(
            UiFailureTextPolicy.RESTORE_PASSWORD_TEXT,
            UiFailureTextPolicy.recoveryMessage(IllegalStateException("Incorrect backup password."))
        )
        assertEquals(
            UiFailureTextPolicy.RESTORE_CORRUPT_TEXT,
            UiFailureTextPolicy.recoveryMessage(IllegalStateException("Backup appears corrupted: could not unlock the backup key."))
        )
        assertFalse(
            "recovery text must never echo the exception",
            UiFailureTextPolicy.recoveryMessage(IllegalStateException("/data/user/0/x/y")).contains("/data/")
        )
    }

    // --- policy: backupFailureMessage ------------------------------------------

    @Test
    fun `backupFailureMessage is fixed and never echoes note-derived filenames`() {
        val e = IllegalStateException("Could not write backup set /data/user/0/com.aistudio.inkflow.app.bkxjrz/cache/vault_exports/TaxReturn_2026")
        val text = UiFailureTextPolicy.backupFailureMessage(e)
        assertEquals(UiFailureTextPolicy.BACKUP_FAILED_GENERIC, text)
        assertFalse(text.contains("TaxReturn"))
        assertFalse(text.contains("/data/"))
    }

    @Test
    fun `backupFailureMessage classifies budget and snapshot failures`() {
        assertEquals(
            UiFailureTextPolicy.BACKUP_TOO_LARGE_TEXT,
            UiFailureTextPolicy.backupFailureMessage(IllegalStateException("Backup rejected: 'huge-note.md' is too large to be restored (max 100MB per entry)."))
        )
        assertEquals(
            UiFailureTextPolicy.BACKUP_TOO_LARGE_TEXT,
            UiFailureTextPolicy.backupFailureMessage(IllegalStateException("Backup rejected: the encrypted backup is larger than the restoreable size (max 400MB)."))
        )
        assertEquals(
            "Backup failed — the vault kept changing during the backup. Please try again.",
            UiFailureTextPolicy.backupFailureMessage(IllegalStateException("Backup failed: the vault database kept changing during the snapshot copy. Please try again."))
        )
        assertEquals(
            "Backup failed — the vault is locked. Unlock the vault and try again.",
            UiFailureTextPolicy.backupFailureMessage(IllegalStateException("Backup rejected: no encryption key is available and no backup password was provided. Unlock the vault before exporting."))
        )
        assertFalse(
            "budget text must not echo the entry name",
            UiFailureTextPolicy.backupFailureMessage(IllegalStateException("Backup rejected: 'secret-note.md' is too large to be restored (max 100MB per entry).")).contains("secret-note")
        )
    }

    // --- policy: importSkippedMessage -----------------------------------------

    @Test
    fun `importSkippedMessage is fixed and never echoes archive text`() {
        assertEquals(
            "Import skipped — the file is too large.",
            UiFailureTextPolicy.importSkippedMessage(IllegalStateException("Import rejected: single file is too large (max 50MB per file)."))
        )
        assertEquals(
            "Import skipped — the file is too large.",
            UiFailureTextPolicy.importSkippedMessage(IllegalStateException("Import rejected: total archive size exceeds 200MB."))
        )
        assertEquals(
            "Import skipped — the archive looks unsafe (possible zip bomb).",
            UiFailureTextPolicy.importSkippedMessage(IllegalStateException("Import rejected: archive contains more than 10000 entries (possible zip bomb)."))
        )
        assertEquals(
            "Import skipped — the archive looks unsafe (possible zip bomb).",
            UiFailureTextPolicy.importSkippedMessage(IllegalStateException("Import rejected: suspicious compression ratio detected (possible zip bomb)."))
        )
        assertEquals(
            UiFailureTextPolicy.IMPORT_SKIPPED_GENERIC,
            UiFailureTextPolicy.importSkippedMessage(IllegalStateException("odd detail"))
        )
        assertFalse(
            UiFailureTextPolicy.importSkippedMessage(IllegalStateException("odd detail /data/user/0/x/file.md")).contains("/data/")
        )
    }

    // --- policy: scrubForUi ----------------------------------------------------

    @Test
    fun `scrubForUi strips userinfo collapses URL paths and redacts absolute paths`() {
        assertEquals(
            "host/...",
            UiFailureTextPolicy.scrubForUi("https://user:S3CrEt@host/secret/path")
        )
        assertEquals(
            "plain words stay plain",
            UiFailureTextPolicy.scrubForUi("plain words stay plain")
        )
        assertFalse(
            "data path must be redacted: ${UiFailureTextPolicy.scrubForUi("err /data/user/0/com.aistudio.inkflow.app.bkxjrz/databases/noteflow.sqlite")}",
            UiFailureTextPolicy.scrubForUi("err /data/user/0/com.aistudio.inkflow.app.bkxjrz/databases/noteflow.sqlite").contains("com.aistudio")
        )
        assertFalse(
            "storage path must be redacted: ${UiFailureTextPolicy.scrubForUi("err /storage/emulated/0/Download/vault/noteflow.backup")}",
            UiFailureTextPolicy.scrubForUi("err /storage/emulated/0/Download/vault/noteflow.backup").contains("Download")
        )
        // review-fix: Windows drive paths, UNC shares and /home|/tmp trees are
        // redacted too.
        assertFalse(
            "windows path must be redacted: ${UiFailureTextPolicy.scrubForUi("err C:\\Users\\smith\\Documents\\Tax Return.docx")}",
            UiFailureTextPolicy.scrubForUi("err C:\\Users\\smith\\Documents\\Tax Return.docx").contains("Tax Return")
        )
        assertFalse(
            "UNC path must be redacted: ${UiFailureTextPolicy.scrubForUi("err \\\\fileserver\\share\\secret-wills.docx")}",
            UiFailureTextPolicy.scrubForUi("err \\\\fileserver\\share\\secret-wills.docx").contains("secret-wills")
        )
        assertFalse(
            "home path must be redacted: ${UiFailureTextPolicy.scrubForUi("err /home/runner/.ssh/id_rsa")}",
            UiFailureTextPolicy.scrubForUi("err /home/runner/.ssh/id_rsa").contains("id_rsa")
        )
        assertEquals("", UiFailureTextPolicy.scrubForUi(""))
    }

    // --- source pins: HomeScreen.kt -------------------------------------------

    @Test
    fun `HomeScreen routes restore-backup-import failures through the policy`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt").readText()
        for (leak in listOf(
            "Restore failed: \${e.message}",
            "Backup failed: \${e.message}",
            "Import skipped: \${e.message}"
        )) {
            assertFalse("$leak must be gone from HomeScreen", source.contains(leak))
        }
        // Counting pins are intentionally "at least" (>=): adding another policy
        // call site is progress, not a regression — the raw-interpolation
        // assertFalse checks above are the real guards.
        assertTrue(
            "restore-failure text must route through the policy",
            Regex("UiFailureTextPolicy\\.restoreFailureMessage\\(e\\)").findAll(source).toList().size >= 3
        )
        assertTrue(
            "import-skip text must route through the policy",
            Regex("UiFailureTextPolicy\\.importSkippedMessage\\(e\\)").findAll(source).toList().size >= 3
        )
        // Phase-252 (2026-08-30) removed the passwordless device-keyed export
        // surface from HomeScreen (it now redirects to the non-bypassable
        // BackupPasswordRequirementDialog), deleting the second backup-failure
        // catch site. The surviving master-password export surface still routes
        // its error text through the policy, which is the real guarantee here.
        assertTrue(
            "backup-failure text must route through the policy",
            Regex("UiFailureTextPolicy\\.backupFailureMessage\\(e\\)").findAll(source).toList().size >= 1
        )
    }

    // --- source pins: NoteflowViewModel.kt -------------------------------------

    @Test
    fun `MainActivity recovery screens never build raw error text`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt").readText()
        assertFalse("MainActivity must not read e.message", source.contains("e.message"))
        assertFalse("MainActivity must not build a snackbar from a raw message elvis", source.contains("message ?:"))
        // The three recovery screens render whatever the (sanitized) VM callback
        // emits — they never construct text from an exception themselves.
        assertEquals(
            "recovery screens render the callback-provided message verbatim",
            3,
            Regex("errorMessage = msg").findAll(source).toList().size
        )
    }

    @Test
    fun `NoteflowViewModel recovery and restore completions are sanitized`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt").readText()
        assertFalse("onError must not read e.message", source.contains("onError(e.message"))
        assertFalse("onComplete must not read e.message", source.contains("onComplete(false, e.message"))
        assertFalse("Recovery failed elvis must be gone", source.contains("e.message ?: \"Recovery failed.\""))
        assertTrue(
            "both recovery screens' error text must route through recoveryMessage",
            Regex("UiFailureTextPolicy\\.recoveryMessage\\(e\\)").findAll(source).toList().size >= 2
        )
        assertTrue(
            "both WebDAV restore-complete texts must route through restoreFailureMessage",
            Regex("UiFailureTextPolicy\\.restoreFailureMessage\\(e\\)").findAll(source).toList().size >= 2
        )
    }

    // --- source pins: ImportExportService.kt -----------------------------------

    @Test
    fun `ImportExportService no longer interpolates the hostile entry name`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt").readText()
        assertFalse("the entryName echo must be gone", source.contains("unsafe relative path: \$entryName"))
        assertEquals(
            "the fixed refusal text is used at both traversal sites",
            2,
            Regex("Backup contains an unsafe relative path in the archive\\.").findAll(source).toList().size
        )
        assertFalse("no restore-UI e.message echo remains", source.contains("\${e.message}"))
    }

    // --- source pins: Dialogs.kt + EditorScreen.kt -----------------------------

    @Test
    fun `Dialogs and EditorScreen no longer render raw e-message`() {
        val dialogs = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/Dialogs.kt").readText()
        val editor = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt").readText()
        assertFalse("APK-read error must not echo e.message", dialogs.contains("Error reading APK: \${e.message}"))
        assertTrue("APK-read error is the fixed text", dialogs.contains("Could not read the selected APK file."))
        assertFalse("photo-attach must not echo e.message", editor.contains("Failed to attach photo: \${e.message}"))
        assertTrue("photo-attach error is fixed text", editor.contains("Could not attach the photo. It may be unreadable or unavailable."))
    }

    // --- source pins: LocalSendSender.kt ---------------------------------------

    @Test
    fun `LocalSendSender never surfaces transport exception text`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/localsend/LocalSendSender.kt").readText()
        assertTrue("mapTransportError must use the class-name token", source.contains("FailureLogPolicy.classNameToken(e)"))
        assertTrue("parse failures use the fixed text", source.contains("The receiving device returned an unexpected response."))
        // `e.message` may appear only inside comments (the one explanatory
        // comment in mapTransportError) — never in code. Comments are stripped
        // (line comments, block comments and KDoc) before the check so the pin
        // does not depend on a specific comment's position.
        val code = source.lines()
            .filterNot { line ->
                val t = line.trimStart()
                t.isEmpty() || t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
        assertFalse("no code path reads e.message", code.contains("e.message"))
    }

    // --- source pins: BiometricAuthHelper.kt (phase-148 review fix) ------------

    @Test
    fun `BiometricAuthHelper never surfaces biometric-init exception text`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/BiometricAuthHelper.kt").readText()
        // `e.message` may appear only inside comments (the finding note) — strip
        // comment lines before scanning so the pin does not depend on comment
        // wording.
        val code = source.lines()
            .filterNot { line ->
                val t = line.trimStart()
                t.isEmpty() || t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
            }
            .joinToString("\n")
        assertFalse("no raw exception message may reach onError", code.contains("e.message"))
        assertFalse("no raw message elvis may reach onError", code.contains("message ?:"))
        assertTrue(
            "the biometric-init failure is fixed text",
            source.contains("Failed to initialize the biometric prompt.")
        )
    }

    // --- source pins: VoiceNoteManager.kt --------------------------------------

    @Test
    fun `VoiceNoteManager logs only class-name tokens`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/VoiceNoteManager.kt").readText()
        assertFalse("no e.message may reach logcat", source.contains("\${e.message}"))
        assertEquals(
            "all 8 failure logs must log the sanitized token",
            8,
            Regex("FailureLogPolicy\\.classNameToken\\(e\\)").findAll(source).toList().size
        )
        // Every failing log line is a fixed label + the class-name token only —
        // no file path, page id, temp name or timestamp can ride a log line.
        val interpolatedLogLines = source.lines().filter {
            Regex("Log\\.\\w\\(\"VoiceNoteManager\"").containsMatchIn(it) && it.contains("\${")
        }
        assertTrue("the 8 failure logs are interpolated", interpolatedLogLines.size == 8)
        for (line in interpolatedLogLines) {
            assertTrue(
                "a failing log may interpolate ONLY the class-name token: $line",
                line.contains("FailureLogPolicy.classNameToken(e)")
            )
        }
    }

    // --- source pins: ProtobufBrushLoader.kt -----------------------------------

    @Test
    fun `ProtobufBrushLoader logs no message and no brush name`() {
        val source = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/ProtobufBrushLoader.kt").readText()
        assertFalse("the protobuf err log must not echo e.message", source.contains("\${e.message}"))
        assertFalse("the brush stream err log must not echo e.message", source.contains("\$name: \${e.message}"))
        assertFalse("the file err log must not echo the class directly", source.contains("\${e::class.java.simpleName}"))
        assertEquals(
            "all three logs must use the sanitized token",
            3,
            Regex("FailureLogPolicy\\.classNameToken\\(e\\)").findAll(source).toList().size
        )
    }

    // --- helpers ---------------------------------------------------------------

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