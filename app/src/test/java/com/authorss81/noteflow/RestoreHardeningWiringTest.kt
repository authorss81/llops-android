package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-B1D-02 + R2-b2b1-UI-03 + R2-b2b1-UI-06 (phase-135): source-level wiring
 * pins for the restore hardening. The *decisions* are pure JVM and covered
 * behaviorally in RestoredDbPolicyTest / RestoreInflightGateTest; these pins
 * prove the security boundary is actually WIRED into the Android layers:
 *  - every restore entry point enters the shared one-in-flight gate;
 *  - the pre-swap structural gate (RestoredDbPolicy) runs before re-arm + swap;
 *  - a rejected/empty backup is quarantined and the live vault is never swapped;
 *  - recovery-screen state is rememberSaveable and restore buttons disable while
 *    a restore is in flight.
 */
class RestoreHardeningWiringTest {

    private val ieSource by lazy {
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt").readText()
    }

    private val vmSource by lazy {
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt").readText()
    }

    private val homeSource by lazy {
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt").readText()
    }

    private val mainSource by lazy {
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt").readText()
    }

    private val webDavSource by lazy {
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/WebDavSyncDialog.kt").readText()
    }

    // ------------------------------------------------------------------
    // R2-B1D-02 — the pre-swap structural gate is wired before re-arm/swap
    // ------------------------------------------------------------------

    @Test
    fun `a zero-byte database entry is rejected during extraction`() {
        val extractRegion = ieSource
            .substringAfter("private fun extractBackupEntriesTo")
            .substringBefore("private fun validateAndPrepareRestoredDb")
        assertTrue(
            "the 0-byte entry must be refused at extract time",
            extractRegion.contains("tempDb.length() == 0L")
        )
        assertTrue(
            "the refusal message must name the empty database",
            extractRegion.contains("Restore rejected: the backup's database is empty.")
        )
    }

    @Test
    fun `validateAndPrepareRestoredDb classifies via RestoredDbPolicy before the swap`() {
        val region = ieSource
            .substringAfter("private fun validateAndPrepareRestoredDb")
            .substringBefore("private fun rekeyVoiceNoteBlobs")

        assertTrue("the decision must come from the pure-JVM policy", region.contains("RestoredDbPolicy.decide"))
        assertTrue("schema presence must be collected under the candidate open", region.contains("countPresentRestoredTables"))
        assertTrue("page count must be collected under the candidate open", region.contains("countRestoredRows(db, \"pages\")"))
        assertTrue("a structural reject quarantines the incoming file", region.contains("quarantineRejectedRestoredDb(context, tempDb)"))
        assertTrue("a zero-row vault aborts with the confirmable exception", region.contains("throw EmptyVaultRestoreDecisionException()"))
        assertTrue(
            "a reject must throw - never a fall-through to re-arm/swap",
            region.contains("throw IllegalStateException(decision.reason)")
        )
    }

    @Test
    fun `importBackup threads allowEmptyVault and only ever as the confirmed escape hatch`() {
        val importRegion = ieSource
            .substringAfter("suspend fun importBackup")
            .substringBefore("private fun restoreFromZip")
        val restoreRegion = ieSource
            .substringAfter("private fun restoreFromZip")
            .substringBefore("private fun extractBackupEntriesTo")

        assertTrue("importBackup must carry the confirmed flag", importRegion.contains("allowEmptyVault: Boolean = false"))
        assertTrue("both parse paths thread the flag through", importRegion.contains("restoreFromZip(context, v2.zipFile, v2.offsetBytes, v2.dekHex, currentDekHex, allowEmptyVault)"))
        assertTrue("the legacy path threads the flag too", importRegion.contains("restoreFromZip(context, stagingZip, 0, null, currentDekHex, allowEmptyVault)"))
        assertTrue("restoreFromZip must forward it to the validator", restoreRegion.contains("allowEmptyVault"))
    }

    @Test
    fun `the empty-vault decision exception and reject quarantine helper exist`() {
        assertTrue(
            "the confirmable empty-vault exception must be declared",
            ieSource.contains("internal class EmptyVaultRestoreDecisionException")
        )
        assertTrue(
            "the rejected incoming file must be quarantined next to the live vault",
            ieSource.contains("noteflow.sqlite.restore-rejected-")
        )
        assertTrue(
            "the gate helpers must be pure members of RestoredDbPolicy",
            ieSource.contains("RestoredDbPolicy.REQUIRED_TABLES")
        )
    }

    // ------------------------------------------------------------------
    // R2-b2b1-UI-03 — every restore entry point enters the shared gate
    // ------------------------------------------------------------------

    @Test
    fun `the viewmodel owns exactly one shared restore gate`() {
        assertTrue("one gate instance for all entry points", vmSource.contains("private val restoreGate = RestoreInflightGate()"))
        assertTrue("the gate state is exposed to the UI", vmSource.contains("val isRestoring: StateFlow<Boolean> = restoreGate.isRestoring"))
        assertTrue("HomeScreen can acquire the gate", vmSource.contains("fun tryBeginRestore(): Boolean = restoreGate.tryBegin()"))
        assertTrue("HomeScreen can release the gate", vmSource.contains("fun endRestore() = restoreGate.end()"))
        assertTrue("the empty-vault confirm channel is exposed", vmSource.contains("val pendingEmptyVaultConfirm: StateFlow<CompletableDeferred<Boolean>?>"))
        assertTrue("the screens answer it in view-model state", vmSource.contains("fun answerEmptyVaultRestore(confirmed: Boolean)"))
    }

    @Test
    fun `attemptRecoveryFromBackup is gated and confirms an empty vault before re-import`() {
        val region = vmSource
            .substringAfter("fun attemptRecoveryFromBackup(")
            .substringBefore("fun attemptKeystoreKeyLostRecoveryFromBackup(")

        assertTrue("the recovery path acquires the shared gate", region.contains("restoreGate.tryBegin()"))
        assertTrue("a refused second restore is surfaced", region.contains("A restore is already in progress. Wait for it to finish."))
        assertTrue("the gate is released on every exit", region.contains("restoreGate.end()"))
        assertTrue("the first import runs WITHOUT the empty-vault bypass", region.contains("allowEmptyVault = false"))
        assertTrue("an empty vault waits for explicit confirmation", region.contains("awaitEmptyVaultConfirm()"))
        assertTrue("the confirmed re-import passes allowEmptyVault = true", region.contains("allowEmptyVault = true"))
    }

    @Test
    fun `attemptKeystoreKeyLostRecoveryFromBackup is gated too`() {
        val region = vmSource
            .substringAfter("fun attemptKeystoreKeyLostRecoveryFromBackup(")
            .substringBefore("fun startFreshAfterKeystoreKeyLoss(")

        assertTrue("the keystore-lost path acquires the shared gate", region.contains("restoreGate.tryBegin()"))
        assertTrue("the keystore-lost path refuses a second restore", region.contains("A restore is already in progress. Wait for it to finish."))
        assertTrue("the keystore-lost path releases the gate", region.contains("restoreGate.end()"))
        assertTrue("the keystore-lost path gates the empty-vault escape", region.contains("awaitEmptyVaultConfirm()"))
        assertTrue("the confirmed re-import passes allowEmptyVault = true", region.contains("allowEmptyVault = true"))
    }

    @Test
    fun `the WebDAV restore path is gated and refuses to import into a locked vault`() {
        val region = vmSource
            .substringAfter("fun restoreEncryptedBackupFromZip(")
            .substringBefore("// Phase 38")

        assertTrue("the WebDAV path acquires the SAME shared gate", region.contains("restoreGate.tryBegin()"))
        assertTrue("a refused second restore is surfaced", region.contains("A restore is already in progress. Wait for it to finish."))
        assertTrue("the WebDAV path releases the gate", region.contains("restoreGate.end()"))
        assertTrue(
            "the WebDAV path checks auth right before closeDatabase",
            region.contains("if (!_authenticated.value || repository.encryptionKey == null)")
        )
        assertTrue(
            "a mid-download lock aborts + reopens untouched",
            region.contains("The vault locked during the download — restore cancelled. Unlock the vault and try again.")
        )
        assertTrue("the WebDAV path never bypasses the empty-vault gate", region.contains("allowEmptyVault = false"))
    }

    @Test
    fun `the HomeScreen local restore is gated and releases on every exit`() {
        val region = homeSource
            .substringAfter("fun performRestore(")
            .substringBefore("// File picker for import")

        assertTrue("the local restore acquires the shared gate", region.contains("viewModel.tryBeginRestore()"))
        assertTrue("a refused second restore is surfaced", region.contains("Restore already in progress"))
        assertTrue("the local restore releases the gate", region.contains("viewModel.endRestore()"))
        assertTrue("a lock before closeDatabase fails closed", region.contains("The vault locked before the restore"))
        assertTrue("the local restore never bypasses the empty-vault gate", region.contains("allowEmptyVault = false"))
    }

    // ------------------------------------------------------------------
    // R2-b2b1-UI-06 — saveable recovery state + disabled buttons
    // ------------------------------------------------------------------

    @Test
    fun `restore button state is saveable on the Home screen`() {
        val stateRegion = homeSource
            .substringAfter("var showBackupPasswordDialog")
            .substringBefore("// Phase 125")
        assertTrue("the restore dialog triggers survive rotation", stateRegion.contains("rememberSaveable"))
        assertTrue("the typed password survives rotation", stateRegion.contains("backupPasswordInput by rememberSaveable"))
        assertTrue("the in-flight flag is collected from the shared gate", stateRegion.contains("viewModel.isRestoring.collectAsState()"))
    }

    @Test
    fun `all three recovery screens use saveable state and disable the restore button in flight`() {
        val restoreBlocked = mainSource.substringAfter("private fun RestoreBlockedScreen(").substringBefore("private fun CorruptionRecoveryScreen(")
        val corruption = mainSource.substringAfter("private fun CorruptionRecoveryScreen(").substringBefore("private fun KeystoreKeyLostScreen(")
        val keystoreLost = mainSource.substringAfter("private fun KeystoreKeyLostScreen(").substringBefore("private fun IntegrityBannerCard")

        for ((name, region) in listOf(
            "RestoreBlockedScreen" to restoreBlocked,
            "CorruptionRecoveryScreen" to corruption,
            "KeystoreKeyLostScreen" to keystoreLost
        )) {
            assertTrue("[$name] password/error state must be saveable", region.contains("rememberSaveable"))
            assertTrue("[$name] must collect the shared in-flight state", region.contains("viewModel.isRestoring.collectAsState()"))
            assertTrue("[$name] must disable the restore trigger while restoring", region.contains("enabled = !isRestoring"))
        }
    }

    @Test
    fun `all three recovery screens render the empty-vault confirm dialog`() {
        for (needle in listOf(
            "private fun EmptyVaultRestoreConfirmDialog",
            "viewModel.pendingEmptyVaultConfirm.collectAsState()",
            "viewModel.answerEmptyVaultRestore"
        )) {
            assertTrue("the confirm dialog composable must exist and use the VM channel", mainSource.contains(needle))
        }
        assertTrue("RestoreBlockedScreen hosts the dialog", mainSource.substringAfter("private fun RestoreBlockedScreen(").contains("EmptyVaultRestoreConfirmDialog(viewModel)"))
        assertTrue("CorruptionRecoveryScreen hosts the dialog", mainSource.substringAfter("private fun CorruptionRecoveryScreen(").contains("EmptyVaultRestoreConfirmDialog(viewModel)"))
        assertTrue("KeystoreKeyLostScreen hosts the dialog", mainSource.substringAfter("private fun KeystoreKeyLostScreen(").contains("EmptyVaultRestoreConfirmDialog(viewModel)"))
    }

    @Test
    fun `the WebDAV dialog disables Download and Restore while a restore is in flight`() {
        assertTrue(
            "the WebDAV dialog must observe the shared gate",
            webDavSource.contains("viewModel.isRestoring.collectAsState()")
        )
        assertTrue(
            "the download-restore button must disable while a restore is in flight",
            webDavSource.contains("enabled = !isLoading && !isRestoring")
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