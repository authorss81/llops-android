package com.authorss81.noteflow

import com.authorss81.noteflow.services.VoicePendingRecordingSlot
import com.authorss81.noteflow.services.VoiceRecordingResult
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 204 — silent data-loss fix #1: rotation mid-recording orphaned a
 * SAVED recording.
 *
 * Pre-fix: `EditorScreen`'s `remember { VoiceNoteManager(context) }` is
 * composition-scoped; with no `configChanges` in the manifest any rotation
 * disposes the editor, `release()` finalizes the recording (the `.enc` blob IS
 * written) and then DROPS the returned success result — no embed, no notice,
 * an orphaned encrypted blob forever.
 *
 * Fix shape:
 *  - [VoicePendingRecordingSlot] is a pure-JVM, ViewModel-scoped relay keyed by
 *    pageId; publish on teardown, consume-once on the next editor instance.
 *  - `VoiceNoteManager` tracks whether the finished recording was attached;
 *    `release()` captures an UNATTACHED success for one-shot relay instead of
 *    dropping it (the phase-153 discard notice for FAILED saves is untouched).
 *
 * Slot lifecycle unit tests + source pins that release() can no longer drop a
 * successful save silently.
 */
class Phase204VoicePendingSlotTest {

    private fun result(path: String = "/data/vault/voice_notes/a.enc") =
        VoiceRecordingResult(filePath = path, durationMs = 42_000L, waveformAmplitudes = listOf(0.1f, 0.9f))

    // ---------------- slot lifecycle ----------------

    @Test
    fun `publish then consume round-trips the recording`() {
        val slot = VoicePendingRecordingSlot()
        val r = result()
        slot.publish("page-1", r)
        assertEquals(r, slot.consume("page-1"))
    }

    @Test
    fun `consume of an unknown page is null`() {
        val slot = VoicePendingRecordingSlot()
        assertNull(slot.consume("never-published"))
    }

    @Test
    fun `consume is one-shot - a second consume returns null`() {
        val slot = VoicePendingRecordingSlot()
        slot.publish("page-1", result())
        assertEquals(result(), slot.consume("page-1"))
        assertNull("the recovered embed must never double-attach", slot.consume("page-1"))
        assertFalse(slot.hasPending("page-1"))
    }

    @Test
    fun `republishing for the same page keeps only the latest result`() {
        val slot = VoicePendingRecordingSlot()
        val second = result("/new.enc")
        slot.publish("page-1", result("/old.enc"))
        slot.publish("page-1", second)
        assertEquals(second, slot.consume("page-1"))
    }

    @Test
    fun `pages are independent`() {
        val slot = VoicePendingRecordingSlot()
        slot.publish("page-a", result("/a.enc"))
        slot.publish("page-b", result("/b.enc"))
        assertEquals(result("/a.enc"), slot.consume("page-a"))
        assertTrue(slot.hasPending("page-b"))
        assertFalse(slot.hasPending("page-a"))
        assertEquals(result("/b.enc"), slot.consume("page-b"))
    }

    @Test
    fun `the recovered notice is fixed non-blank honest text`() {
        assertTrue(VoicePendingRecordingSlot.RECOVERED_NOTICE.isNotBlank())
        assertTrue(VoicePendingRecordingSlot.RECOVERED_NOTICE.contains("recording"))
    }

    // ---------------- source pins: the relay cannot be dropped again ---------

    private fun sourceFile(relative: String): String =
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative").readText()

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) return dir
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }

    @Test
    fun `finalizeRecording records every success as unattached`() {
        val vnm = sourceFile("services/VoiceNoteManager.kt")
        val finalize = vnm.substringAfter("private fun finalizeRecording(limitMessage: String?)")
            .substringBefore("fun startPlayback(")
        assertTrue(
            "a successful finalize must be tracked for attach accounting",
            finalize.contains("lastFinishedResult = result") &&
                finalize.contains("lastFinishedResultAttached = false")
        )
        assertTrue(
            "a new session must invalidate the prior result latch",
            vnm.substringAfter("_completedRecordingResult.value = null")
                .substringBefore("discardOnRelease = false")
                .contains("lastFinishedResultAttached = true")
        )
    }

    @Test
    fun `release captures an unattached SUCCESS instead of dropping it`() {
        val vnm = sourceFile("services/VoiceNoteManager.kt")
        val release = vnm.substringAfter("fun release(): Boolean {")
            .substringBefore("}\n\ndata class VoiceRecordingResult")
        assertTrue(
            "release must stop the recorder first so the teardown finalize lands",
            release.contains("stopRecording()")
        )
        assertTrue(
            "release must capture the unattached save BEFORE clearing it",
            release.contains("unpublishedResultForRelay =\n            if (lastFinishedResultAttached) null else lastFinishedResult")
        )
        assertTrue(
            "the phase-153 discard path is unchanged",
            release.contains("val discarded = discardOnRelease") &&
                release.contains("return discarded")
        )
        val take = vnm.substringAfter("fun takeUnattachedRecordingForRelay(): VoiceRecordingResult? {")
            .substringBefore("fun release(): Boolean {")
        assertTrue(
            "the relay accessor must be one-shot",
            take.contains("unpublishedResultForRelay = null")
        )
    }

    @Test
    fun `the editor teardown relays the saved recording to the viewmodel slot`() {
        val editor = sourceFile("ui/screens/EditorScreen.kt")
        val dispose = editor.substringAfter("DisposableEffect(voiceNoteManager) {")
            .substringBefore("val recordAudioPermissionLauncher")
        assertTrue(
            "phase-153 discard notice wiring intact",
            dispose.contains("if (voiceNoteManager.release())") &&
                dispose.contains("viewModel.notifyVoiceRecordDiscarded()")
        )
        assertTrue(
            "teardown must relay the unattached-but-saved result into the VM slot",
            dispose.contains("takeUnattachedRecordingForRelay()") &&
                dispose.contains("viewModel.publishPendingVoiceRecording(page.id, recovered)")
        )
    }

    @Test
    fun `both live attach sites acknowledge so nothing relays twice`() {
        val editor = sourceFile("ui/screens/EditorScreen.kt")
        val acks = Regex("voiceNoteManager\\.markRecordingAttached\\(\\)").findAll(editor).count()
        assertTrue(
            "manual chip-tap stop AND ceiling observer must acknowledge the attach (found $acks)",
            acks >= 2
        )
    }

    @Test
    fun `the next editor instance adopts the recovered recording after load`() {
        val editor = sourceFile("ui/screens/EditorScreen.kt")
        val consumeIdx = editor.indexOf("viewModel.consumePendingVoiceRecording(page.id)")
        assertTrue("the consume call site must exist", consumeIdx >= 0)
        val effectStart = editor.lastIndexOf("LaunchedEffect(page.id, isInitialLoadComplete, isAuthenticated)", consumeIdx)
        assertTrue("consumption must run inside the post-load effect", effectStart >= 0)
        val region = editor.substring(effectStart, consumeIdx)
        assertTrue(
            "attach must wait until the initial canvas load completed AND the vault is authenticated " +
                "(review fix: auth must be a collected key so adoption retries on unlock)",
            region.contains("if (!isInitialLoadComplete || !isAuthenticated) return@LaunchedEffect") &&
                !region.contains("authenticated.value")
        )
        val afterConsume = editor.substring(consumeIdx, consumeIdx + 400)
        assertTrue(
            "the adoption must surface the honest recovered notice",
            afterConsume.contains("attachVoiceRecording(recovered)") &&
                afterConsume.contains("RECOVERED_NOTICE")
        )
    }

    @Test
    fun `the viewmodel exposes the page-keyed relay`() {
        val vm = sourceFile("ui/viewmodel/NoteflowViewModel.kt")
        assertTrue(vm.contains("private val pendingVoiceRecordings = com.authorss81.noteflow.services.VoicePendingRecordingSlot()"))
        assertTrue(vm.contains("fun publishPendingVoiceRecording(pageId: String, result:"))
        assertTrue(vm.contains("fun consumePendingVoiceRecording(pageId: String):"))
    }
}
