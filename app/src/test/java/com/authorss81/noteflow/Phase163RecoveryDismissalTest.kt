package com.authorss81.noteflow

import com.authorss81.noteflow.services.RecoveryDismissalPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase-163: "Don't show again" on the two data-recovery screens must PERSIST
 * for the SAME recovery event across process death + cold start, but a NEW event
 * must always re-show the screen, and a successful restore / start-fresh must
 * clear the dismissal so it can never suppress a future event.
 *
 * The decision table is pure JVM and tested behaviourally here
 * ([RecoveryDismissalPolicy]); the Android-side wiring is pinned at source level
 * (same pattern as B1Db06WalCoverageAndDismissalTest / RestoreHardeningWiringTest).
 */
class Phase163RecoveryDismissalTest {

    // ------------------------------------------------------------------
    // RecoveryDismissalPolicy decision table (pure JVM)
    // ------------------------------------------------------------------

    @Test
    fun `not blocking never shows a screen`() {
        assertFalse(RecoveryDismissalPolicy.mayShow(blocking = false, eventTimestamp = 1L, dismissedTimestamp = 0L))
        assertFalse(RecoveryDismissalPolicy.mayShow(blocking = false, eventTimestamp = 1L, dismissedTimestamp = 1L))
        assertFalse(RecoveryDismissalPolicy.mayShow(blocking = false, eventTimestamp = 0L, dismissedTimestamp = 0L))
    }

    @Test
    fun `an unkeyable legacy event always shows - fail closed`() {
        assertTrue(RecoveryDismissalPolicy.mayShow(blocking = true, eventTimestamp = 0L, dismissedTimestamp = 0L))
        assertTrue(RecoveryDismissalPolicy.mayShow(blocking = true, eventTimestamp = -1L, dismissedTimestamp = 99L))
    }

    @Test
    fun `same-event dismissal persists - suppressed`() {
        assertFalse(RecoveryDismissalPolicy.mayShow(blocking = true, eventTimestamp = 1234L, dismissedTimestamp = 1234L))
    }

    @Test
    fun `a new event always re-shows even after an old dismissal`() {
        assertTrue(RecoveryDismissalPolicy.mayShow(blocking = true, eventTimestamp = 1234L, dismissedTimestamp = 999L))
        assertTrue(RecoveryDismissalPolicy.mayShow(blocking = true, eventTimestamp = 99L, dismissedTimestamp = 1234L))
    }

    @Test
    fun `a dismissal is only persistable for a positively keyable event`() {
        assertTrue(RecoveryDismissalPolicy.isDismissible(blocking = true, eventTimestamp = 1234L))
        assertFalse(RecoveryDismissalPolicy.isDismissible(blocking = true, eventTimestamp = 0L))
        assertFalse(RecoveryDismissalPolicy.isDismissible(blocking = true, eventTimestamp = -1L))
        assertFalse(RecoveryDismissalPolicy.isDismissible(blocking = false, eventTimestamp = 1234L))
    }

    // ------------------------------------------------------------------
    // Wiring pins — the screens actually call the ViewModel dismiss methods
    // ------------------------------------------------------------------

    @Test
    fun `corruption recovery screen wires dontShowAgain to the ViewModel`() {
        val main = mainSource()
        assertTrue(
            "the corruption screen must route 'Don't show again' through dismissCorruptionRecovery",
            main.contains("viewModel.dismissCorruptionRecovery(dontShowAgain)")
        )
        assertTrue(
            "the corruption screen must render a persistent-dismissal control",
            main.contains("Don't show again for this corruption event")
        )
    }

    @Test
    fun `keystore-key-lost recovery screen wires dontShowAgain to the ViewModel`() {
        val main = mainSource()
        assertTrue(
            "the key-lost screen must route 'Don't show again' through dismissKeystoreKeyLostRecovery",
            main.contains("viewModel.dismissKeystoreKeyLostRecovery(dontShowAgain)")
        )
        assertTrue(
            "the key-lost screen must render a persistent-dismissal control",
            main.contains("Don't show again for this lost device key")
        )
    }

    @Test
    fun `the corruption dismissal is keyed to the event timestamp, not a bare boolean`() {
        val vm = vmSource()
        val dismissBody = vm
            .substringAfter("fun dismissCorruptionRecovery(dontShowAgain: Boolean) {")
            .substringBefore("fun dismissKeystoreKeyLostRecovery")
        assertTrue(
            "the dismissal must be gated by RecoveryDismissalPolicy.isDismissible",
            dismissBody.contains("RecoveryDismissalPolicy.isDismissible(true, eventTs)")
        )
        assertTrue(
            "the persisted value must be the event timestamp",
            dismissBody.contains("setCorruptionDismissedTimestamp(appContext, eventTs)")
        )
    }

    // ------------------------------------------------------------------
    // Wiring pins — restore / start-fresh CLEAR the dismissal
    // ------------------------------------------------------------------

    @Test
    fun `corruption clear drops the dismissed-timestamp key with the event`() {
        val helper = helperSource()
        val clearBody = helper
            .substringAfter("fun clearCorruptionDetected(context: Context) {")
            .substringBefore("fun getCorruptionDismissedTimestamp")
        assertTrue(
            "clearing the corruption event must also drop its dismissal key",
            clearBody.contains(".remove(PREF_CORRUPTION_DISMISSED_TIMESTAMP)")
        )
    }

    @Test
    fun `keystore-lost restore path clears the event and dismissal`() {
        val vm = vmSource()
        val restoreBody = vm
            .substringAfter("fun attemptKeystoreKeyLostRecoveryFromBackup(")
            .substringBefore("fun startFreshAfterKeystoreKeyLoss(")
        assertTrue(
            "the key-lost restore path must clear the keyed-lost event + dismissal",
            restoreBody.contains("DatabaseSecurityHelper.clearKeystoreLostDismissal(getApplication())")
        )
    }

    @Test
    fun `keystore-lost start-fresh path clears the event and dismissal`() {
        val vm = vmSource()
        val freshBody = vm
            .substringAfter("fun startFreshAfterKeystoreKeyLoss() {")
            .substringBefore("private fun quarantineVaultFiles")
        assertTrue(
            "start-fresh after key loss must clear the keyed-lost event + dismissal",
            freshBody.contains("DatabaseSecurityHelper.clearKeystoreLostDismissal(getApplication())")
        )
    }

    // ------------------------------------------------------------------
    // Wiring pins — the ViewModel gates the screens on the policy at EVERY
    // detection site, not on a bare remembered boolean
    // ------------------------------------------------------------------

    @Test
    fun `every keystore-lost detection routes the alias through the keyed gate`() {
        val vm = vmSource()
        val authBranch = vm
            .substringAfter("is DekReadResult.AuthRequired -> {")
            .substringBefore("is DekReadResult.KeyLost -> {")
        assertTrue(
            "AuthRequired must key the dismissal to the single-read alias",
            authBranch.contains("keystoreLostBlockedForCurrentEvent(result.wrapperAlias)")
        )
        val keyLostBranch = vm
            .substringAfter("is DekReadResult.KeyLost -> {")
            .substringBefore("firstDataInitDone.complete(Unit)")
        assertTrue(
            "KeyLost must key the dismissal to the same single-read alias",
            keyLostBranch.contains("keystoreLostBlockedForCurrentEvent(result.wrapperAlias)")
        )
        assertTrue(
            "no detection site may re-read the blob for the identity",
            !vm.contains("keystoreLostBlockedForCurrentEvent(security.currentWrapperAlias())")
        )
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private fun mainSource(): String =
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt").readText()

    private fun vmSource(): String =
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt").readText()

    private fun helperSource(): String =
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/DatabaseSecurityHelper.kt").readText()

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