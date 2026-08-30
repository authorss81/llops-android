package com.authorss81.noteflow

import com.authorss81.noteflow.services.BackupPortabilityPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 252 (HIGH 4/5): passwordless backup portability.
 *
 * A backup export from a vault with NO master password silently wrote a
 * DEVICE-DEK-encrypted archive (the AndroidKeyStore-wrapped DEK blob,
 * B1-CRYPTO-05) — unreadable on any other device, effectively wedded to one
 * piece of hardware, with ZERO UI indication. These pins close that:
 *
 *   1. The HomeScreen export path checks hasMasterPassword BEFORE allowing the
 *      export; when it's false, the NON-BYPASSABLE
 *      `BackupPasswordRequirementDialog` is shown and no exportBackup call can
 *      run (the old silent device-keyed export is gone from that branch).
 *   2. `ImportExportService.exportBackup` gains `requireBackupPassword: Boolean
 *      = true` and — through the pure-JVM `BackupPortabilityPolicy` — throws
 *      `IllegalArgumentException` when called with `password == null` while a
 *      key is available. Because every vault's DEK is the AndroidKeyStore-bound
 *      device copy, this is a device-keyed export for BOTH passwordless and
 *      master-password vaults (the review-fix round widened the predicate off
 *      the old `hasMasterPassword` proxy, which leaked the master-password-no-
 *      password shape through as an unportable archive).
 *   3. The new `BackupPasswordRequirementDialog` exists with only the two
 *      actions "Set Master Password" + "Cancel Export"; its copy lives in
 *      `strings.xml` (no hardcoded English literals in the composable).
 */
class Phase252PasswordlessBackupTest {

    private fun mainSource(rel: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        val candidates = listOf(
            "src/main/kotlin/com/authorss81/noteflow/$rel",
            "app/src/main/kotlin/com/authorss81/noteflow/$rel"
        )
        while (dir != null) {
            candidates.forEach { c ->
                File(dir, c).takeIf { it.isFile }?.let { return it.readText() }
            }
            dir = dir.parentFile
        }
        throw AssertionError("could not locate $rel from ${start.path}")
    }

    private fun resStrings(): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val f = File(dir, "app/src/main/res/values/strings.xml")
            if (f.isFile) return f.readText()
            dir = dir.parentFile
        }
        throw AssertionError("could not locate app/src/main/res/values/strings.xml from ${start.path}")
    }

    // --- 1. HomeScreen export path gate -------------------------------------

    @Test
    fun `HomeScreen passwordless export shows the requirement dialog and cannot bypass it`() {
        val src = mainSource("ui/screens/HomeScreen.kt")
        // The export handler must branch on the live hasMasterPassword state.
        assertTrue(
            "the export handler must check hasMasterPassword before allowing the export",
            src.contains("viewModel.hasMasterPassword.value")
        )
        // The passwordless branch must raise the requirement dialog...
        assertTrue(
            "the passwordless branch must set showBackupPasswordRequirementDialog = true",
            src.contains("showBackupPasswordRequirementDialog = true")
        )
        // ...and the state must be declared (rememberSaveable, survives rotation).
        assertTrue(
            "the requirement-dialog state must be declared",
            src.contains("var showBackupPasswordRequirementDialog by rememberSaveable")
        )
        // No path may bypass: the ONLY exportBackup call left in HomeScreen is
        // inside the master-password dialog flow (exactly one call site).
        val exportCalls = Regex("ImportExportService\\.exportBackup\\(").findAll(src).count()
        assertEquals(
            "a passwordless export must not reach exportBackup — the only call site is the password dialog",
            1,
            exportCalls
        )
    }

    @Test
    fun `HomeScreen dialogs compose the requirement dialog and route Set Master Password to Security`() {
        val src = mainSource("ui/screens/HomeScreen.kt")
        assertTrue(
            "HomeScreen must compose BackupPasswordRequirementDialog",
            src.contains("BackupPasswordRequirementDialog(")
        )
        // "Set Master Password" dismisses the requirement and opens the security
        // settings where the master password is enabled.
        val dialogBlock = src.substring(src.indexOf("BackupPasswordRequirementDialog("))
        assertTrue(
            "Set Master Password must route to the master-password setup (Security dialog)",
            dialogBlock.contains("showSecurityDialog = true")
        )
        assertTrue(
            "the dialog must be dismissible without a touch on the vault",
            dialogBlock.contains("onCancel = { showBackupPasswordRequirementDialog = false }")
        )
    }

    // --- 2. Service-layer gate (defense-in-depth) ----------------------------

    @Test
    fun `BackupPortabilityPolicy throws IllegalArgumentException for any device-keyed export with no backup password`() {
        // no backup password + key available (gate on, the default) => the export
        // would be device-locked => refuse. Holds for a PASSWORDLESS vault...
        assertThrows(IllegalArgumentException::class.java) {
            BackupPortabilityPolicy.requirePortableBackup(
                requireBackupPassword = true,
                backupPassword = null,
                keyAvailable = true
            )
        }
        // ...and for a MASTER-PASSWORD vault too: every vault's in-memory DEK is
        // the AndroidKeyStore-wrapped device copy, so a missing backup password
        // always yields an unportable archive regardless of hasMasterPassword.
        // (The old phase-252 predicate leaked this shape through; fixed in the
        // review-fix round — see BackupPortabilityPolicy KDoc.)
        assertThrows(IllegalArgumentException::class.java) {
            BackupPortabilityPolicy.requirePortableBackup(
                requireBackupPassword = true,
                backupPassword = null,
                keyAvailable = true
            )
        }
        // The thrown message must be the documented portable-path copy.
        try {
            BackupPortabilityPolicy.requirePortableBackup(
                requireBackupPassword = true,
                backupPassword = null,
                keyAvailable = true
            )
            throw AssertionError("must throw")
        } catch (e: IllegalArgumentException) {
            assertEquals(BackupPortabilityPolicy.PASSWORDLESS_DEVICE_KEYED_ERROR, e.message)
        }
    }

    @Test
    fun `BackupPortabilityPolicy allows every legitimate export shape`() {
        // a backup password WAS supplied => v3 portable export (even though this
        // represents a vault that may or may not have a master password).
        BackupPortabilityPolicy.requirePortableBackup(
            requireBackupPassword = true,
            backupPassword = "correct horse battery staple",
            keyAvailable = true
        )
        // explicit opt-in (requireBackupPassword = false) preserves the
        // B1-CRYPTO-05 device-keyed path (WebDAV/LocalSend sync producers).
        BackupPortabilityPolicy.requirePortableBackup(
            requireBackupPassword = false,
            backupPassword = null,
            keyAvailable = true
        )
        // locked vault (no key): the existing "unlock the vault" gate owns it.
        BackupPortabilityPolicy.requirePortableBackup(
            requireBackupPassword = true,
            backupPassword = null,
            keyAvailable = false
        )
        assertEquals(
            "the device-keyed predicate must classify the no-backup-password+key shape",
            true,
            BackupPortabilityPolicy.isDeviceKeyed(
                backupPassword = null,
                keyAvailable = true
            )
        )
        assertEquals(
            "a supplied backup password is never device-keyed",
            false,
            BackupPortabilityPolicy.isDeviceKeyed(
                backupPassword = "a real password",
                keyAvailable = true
            )
        )
        assertEquals(
            "a locked vault (no key) is not device-keyed — the unlock gate owns it",
            false,
            BackupPortabilityPolicy.isDeviceKeyed(
                backupPassword = null,
                keyAvailable = false
            )
        )
    }

    @Test
    fun `exportBackup wires the portability gate and defaults requireBackupPassword to true`() {
        val src = mainSource("services/ImportExportService.kt")
        // The public entry must carry the new default-true parameter.
        assertTrue(
            "exportBackup must declare requireBackupPassword: Boolean = true",
            src.contains("requireBackupPassword: Boolean = true")
        )
        // The gate must run against the device-keyed determination
        // before any bytes move, with a NO-key-mint contract (keyAvailable).
        val gateIdx = src.indexOf("BackupPortabilityPolicy.requirePortableBackup(")
        assertTrue("exportBackup must call the portability gate", gateIdx >= 0)
        val gateBlock = src.substring(gateIdx)
        assertTrue(
            "the gate must be keyed on the supplied backupPassword",
            gateBlock.contains("backupPassword = backupPassword")
        )
        assertTrue(
            "the gate must key on whether a key is available (never mints one)",
            gateBlock.contains("keyAvailable = key != null")
        )
        assertFalse(
            "the gate must NOT depend on hasMasterPassword — every vault DEK is device-bound",
            gateBlock.contains("hasMasterPassword")
        )
    }

    // --- 3+4. The dialog + strings.xml ---------------------------------------

    @Test
    fun `BackupPasswordRequirementDialog exists with the two mandatory actions from strings`() {
        val src = mainSource("ui/dialogs/BackupPasswordRequirementDialog.kt")
        assertTrue("the dialog composable must exist", src.contains("fun BackupPasswordRequirementDialog("))
        assertTrue(
            "the 'Set Master Password' action must come from strings.xml",
            src.contains("backup_password_requirement_set_password")
        )
        assertTrue(
            "the 'Cancel Export' action must come from strings.xml",
            src.contains("backup_password_requirement_cancel_export")
        )
        // No hardcoded English literals: EVERY `Text(...)` in the composable must
        // be built from stringResource(...). A literal like `Text("Backup…")`
        // would be a violation.
        val hardcodedTexts = Regex("Text\\(\\s*\"[A-Za-z]").findAll(src).count()
        assertEquals(
            "the composable must not embed English literals in Text() — must use stringResource()",
            0,
            hardcodedTexts
        )
    }

    @Test
    fun `strings resource file carries the warning copy and the two button labels`() {
        val res = resStrings()
        assertTrue(
            "the title must exist",
            res.contains("name=\"backup_password_requirement_title\"")
        )
        val body = Regex("name=\"backup_password_requirement_body\">([^<]*)").find(res)?.groupValues?.get(1)
        assertTrue("the warning body must exist", body != null)
        assertFalse(
            "the body must not be empty",
            body!!.trim().isEmpty()
        )
        assertTrue(
            "the body must name the device-bound key",
            body.contains("hardware")
        )
        assertTrue(
            "the body must say the backup would be unreadable",
            body.contains("never be opened again") || body.contains("unreadable")
        )
        assertTrue(
            "the body must direct the user to set a master password",
            body.contains("set a master password first")
        )
        assertTrue(
            "the 'Set Master Password' label must exist",
            res.contains("name=\"backup_password_requirement_set_password\">Set Master Password</string>")
        )
        assertTrue(
            "the 'Cancel Export' label must exist",
            res.contains("name=\"backup_password_requirement_cancel_export\">Cancel Export</string>")
        )
    }
}
