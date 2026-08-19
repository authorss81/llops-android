package com.authorss81.noteflow

import com.authorss81.noteflow.services.SnackbarLockPolicy
import com.authorss81.noteflow.services.UiFailureTextPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-b2b1-UI-04 + R2-b2b1-UI-05 (phase-153) — post-lock UI channels.
 *
 * Findings (docs/security-report-round2.md):
 *  - R2-b2b1-UI-04 (LOW): the root SnackbarHost is composed OUTSIDE the
 *    `LockScreen` conditional (`MainActivity.kt`), so vault-content-bearing
 *    messages (restore/import `e.message`, note titles) enqueued past `lock()`
 *    rendered over the locked UI.
 *  - R2-b2b1-UI-05 (LOW): locking mid-voice-recording silently destroyed the
 *    finished recording — `VoiceNoteManager.finalizeRecording` set its error
 *    AFTER the editor's observing `LaunchedEffect` was cancelled, so nobody saw
 *    it.
 *
 * Fix shape (all provable on the pure JVM):
 *  - [SnackbarLockPolicy] owns the single decision table: while locked only the
 *    survive-lock notice may be queued; every other message is dropped at the
 *    boundary.
 *  - the pipeline became a bounded `StateFlow<List<SnackbarMessage>>` FIFO so
 *    `lock()` can CLEAR it and the MainActivity collector can be gated on
 *    `authenticated`; `showSnackbar` (the ~140 call sites) is unchanged.
 *  - the editor teardown publishes the discard notice over that persistent
 *    pipeline via `notifyVoiceRecordDiscarded()`; `VoiceNoteManager.release()`
 *    now reports the discard. The fail-closed at-rest behavior (plaintext
 *    swept, nothing persisted without the DEK) is unchanged.
 *
 * The Android binding is pinned at source level below.
 */
class Phase153LockedSnackbarPolicyTest {

    // ---------------------------------------------------------------------
    // SnackbarLockPolicy decision table (R2-b2b1-UI-04)
    // ---------------------------------------------------------------------

    @Test
    fun `unlocked vaults buffer every message`() {
        val samples = listOf(
            "Restore completed — the app will restart to load the restored data.",
            UiFailureTextPolicy.restoreFailureMessage(IllegalStateException("boom")),
            "Screenshot saved as note: My private title",
            "Vault is locked — search results cleared",
            SnackbarLockPolicy.VOICE_RECORD_DISCARDED_NOTICE
        )
        for (text in samples) {
            assertTrue(
                "unlocked: every message may buffer (was: $text)",
                SnackbarLockPolicy.mayBufferWhileLocked(isAuthenticated = true, text = text)
            )
        }
    }

    @Test
    fun `locked vaults drop every vault-content message`() {
        val vaultContent = listOf(
            "Restore failed. Your vault was left unchanged.",
            UiFailureTextPolicy.restoreFailureMessage(IllegalStateException("Incorrect backup password.")),
            UiFailureTextPolicy.importSkippedMessage(IllegalStateException("too large")),
            UiFailureTextPolicy.backupFailureMessage(IllegalStateException("no encryption key is available")),
            "Screenshot saved as note: Secret note",
            "Vault is locked — version snapshot not saved",
            "Vault is locked — search results cleared",
            "Clip confirmed — it will be added once you unlock.",
            "Imported 3 page(s)"
        )
        for (text in vaultContent) {
            assertFalse(
                "locked: vault-content message must be dropped at the boundary (was: $text)",
                SnackbarLockPolicy.mayBufferWhileLocked(isAuthenticated = false, text = text)
            )
        }
    }

    @Test
    fun `the voice-discard notice is the only survive-lock message`() {
        assertTrue(
            "locked: the discard notice is buffered for replay after unlock",
            SnackbarLockPolicy.mayBufferWhileLocked(isAuthenticated = false, text = SnackbarLockPolicy.VOICE_RECORD_DISCARDED_NOTICE)
        )
        assertTrue(SnackbarLockPolicy.messageSurvivesLock(SnackbarLockPolicy.VOICE_RECORD_DISCARDED_NOTICE))
        // The pre-existing error the editor banner used must NOT survive — the
        // lock clears the pipeline, so it would be lost (it is replaced by the
        // honest persistent notice).
        assertFalse(
            SnackbarLockPolicy.messageSurvivesLock("The recording could not be saved securely. Please try again.")
        )
        assertFalse(
            SnackbarLockPolicy.messageSurvivesLock("Recording limit reached (30 minutes) — the audio was saved.")
        )
    }

    @Test
    fun `the discard notice is honest and fixed`() {
        val notice = SnackbarLockPolicy.VOICE_RECORD_DISCARDED_NOTICE
        assertTrue("states the recording was discarded", notice.contains("discarded"))
        assertTrue("names the cause honestly", notice.contains("vault locked"))
        assertFalse("never claims the audio WAS saved", notice.contains("was saved"))
        assertFalse("no interpolation of vault content", notice.contains("${'$'}{"))
    }

    @Test
    fun `the queue bound replaces the old SharedFlow buffer`() {
        assertEquals(16, SnackbarLockPolicy.MAX_PENDING)
    }

    // ---------------------------------------------------------------------
    // Source pins: MainActivity.kt — collector gated on `authenticated`
    // ---------------------------------------------------------------------

    @Test
    fun `the root collector is gated on authenticated and dismisses a lingering snackbar at the lock boundary`() {
        val main = sourceFile("MainActivity.kt")
        val collector = main.substringAfter("LaunchedEffect(authenticated) {")
            .substringBefore("val pages by viewModel.pages.collectAsState()")
        assertTrue(
            "collector must not consume while locked",
            collector.contains("if (!authenticated) {")
        )
        assertTrue(
            "a snackbar still showing at the lock boundary is dismissed",
            collector.contains("snackbarHostState.currentSnackbarData?.dismiss()")
        )
        assertTrue(
            "the collector drains the FIFO while authenticated",
            collector.contains("viewModel.snackbarMessages.collect") &&
                collector.contains("viewModel.nextSnackbarMessage()")
        )
        assertTrue(
            "the shown message is acknowledged so the FIFO cannot loop",
            collector.contains("viewModel.consumeSnackbar(message)")
        )
        assertTrue(
            "the host still renders bottom-center (visible, TalkBack-reachable)",
            main.contains("hostState = snackbarHostState,")
        )
    }

    // ---------------------------------------------------------------------
    // Source pins: NoteflowViewModel.kt — clear-on-lock + emission gate
    // ---------------------------------------------------------------------

    @Test
    fun `lock clears the snackbar queue inside the master-password teardown`() {
        val vm = sourceFile("ui/viewmodel/NoteflowViewModel.kt")
        val lockBody = vm.substringAfter("fun lock() {").substringBefore("override fun onCleared()")
        assertTrue(
            "lock() must clear the pre-lock queue",
            lockBody.contains("_snackbarMessages.value = emptyList()")
        )
        // The clear must only happen for vaults that actually show a LockScreen
        // (passwordless vaults have no lock boundary by design).
        val teardown = lockBody.substringAfter("if (settings.hasMasterPassword) {")
            .substringBefore("invalidatePaletteIndex()")
        assertTrue(
            "the clear sits inside the hasMasterPassword teardown",
            teardown.contains("_snackbarMessages.value = emptyList()")
        )
    }

    @Test
    fun `showSnackbar routes through the lock gate and the channel is a bounded StateFlow FIFO`() {
        val vm = sourceFile("ui/viewmodel/NoteflowViewModel.kt")
        assertTrue(
            "the channel is a StateFlow FIFO so lock() can clear it",
            vm.contains("MutableStateFlow<List<SnackbarMessage>>(emptyList())")
        )
        assertTrue(
            "the emission gate is the policy's decision table",
            vm.contains("SnackbarLockPolicy.mayBufferWhileLocked(_authenticated.value, text)")
        )
        assertTrue(
            "the queue is bounded by the policy's cap",
            vm.contains(".takeLast(com.authorss81.noteflow.services.SnackbarLockPolicy.MAX_PENDING)")
        )
        assertTrue(
            "the collector ack removes exactly the shown instance",
            vm.contains("current.filterNot { it === message }")
        )
    }

    @Test
    fun `notifyVoiceRecordDiscarded publishes the persistent notice through the pipeline`() {
        val vm = sourceFile("ui/viewmodel/NoteflowViewModel.kt")
        val fn = vm.substringAfter("fun notifyVoiceRecordDiscarded() {")
            .substringBefore("    private val restoreGate")
        assertTrue(
            "the discard notice is emitted through the persistent pipeline",
            fn.contains("showSnackbar(") &&
                fn.contains("SnackbarLockPolicy.VOICE_RECORD_DISCARDED_NOTICE, isLong = true")
        )
    }

    // ---------------------------------------------------------------------
    // Source pins: VoiceNoteManager.kt — DEK-null sweep path unchanged,
    // discard reported on release
    // ---------------------------------------------------------------------

    @Test
    fun `the fail-closed DEK-null finalize path is unchanged and flags the discard`() {
        val vnm = sourceFile("services/VoiceNoteManager.kt")
        val finalize = vnm.substringAfter("private fun finalizeRecording(limitMessage: String?)")
            .substringBefore("fun startPlayback(")
        // B1-DB-3: the encryption gate still reads the DEK and short-circuits
        // on null, and the plaintext temp is swept by the caller/release.
        assertTrue("the DEK still gates encryption", finalize.contains("val dek = VaultKeyHolder.dek"))
        assertTrue("the null-DEK short-circuit is intact", finalize.contains("blobFile != null && dek != null"))
        assertTrue(
            "the fail-closed error surface is intact",
            finalize.contains("\"The recording could not be saved securely. Please try again.\"")
        )
        assertTrue("the lock-path discard is flagged", finalize.contains("discardOnRelease = true"))
    }

    @Test
    fun `release reports the discard and keeps the plaintext sweep`() {
        val vnm = sourceFile("services/VoiceNoteManager.kt")
        val release = vnm.substringAfter("fun release(): Boolean {")
            .substringBefore("}\n\ndata class VoiceRecordingResult")
        assertTrue("release still stops the recorder", release.contains("stopRecording()"))
        assertTrue("release still stops playback", release.contains("stopPlayback()"))
        assertTrue("release still cancels the scope", release.contains("scope.cancel()"))
        assertTrue(
            "the plaintext-temp sweep is intact (B1-DB-3)",
            release.contains("VoiceNoteCrypto.sweepPlaintextTemps(context.cacheDir)")
        )
        assertTrue(
            "the discard is a one-shot read-and-reset",
            release.contains("val discarded = discardOnRelease") && release.contains("discardOnRelease = false")
        )
        assertTrue("the discard is reported to the caller", release.contains("return discarded"))
    }

    // ---------------------------------------------------------------------
    // Source pins: EditorScreen.kt — teardown republishes the discard notice
    // ---------------------------------------------------------------------

    @Test
    fun `the editor teardown republishes a discard through the persistent pipeline`() {
        val editor = sourceFile("ui/screens/EditorScreen.kt")
        val dispose = editor.substringAfter("DisposableEffect(voiceNoteManager) {")
            .substringBefore("val recordAudioPermissionLauncher")
        assertTrue(
            "release() outcome is observed",
            dispose.contains("if (voiceNoteManager.release())")
        )
        assertTrue(
            "the honest notice is published via the ViewModel pipeline",
            dispose.contains("viewModel.notifyVoiceRecordDiscarded()")
        )
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun sourceFile(relative: String): String {
        val file = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative")
        return file.readText()
    }

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
