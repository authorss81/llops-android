package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.ClipKind
import com.authorss81.noteflow.plugins.SharedClip
import com.authorss81.noteflow.plugins.SharedStream
import com.authorss81.noteflow.services.PendingShareConfirmState
import com.authorss81.noteflow.services.PendingSharePolicy
import com.authorss81.noteflow.services.PendingShareState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-B1P-05 (phase-140) — share-confirmation state survival + lock flush.
 *
 * The finding: `pendingShareConfirm`/`pendingShare` were activity `mutableStateOf`
 * fields. MainActivity is `singleTask` with no `configChanges`, so a rotation
 * recreates the activity with the ORIGINAL SEND intent and `onCreate` re-parses
 * + re-prompts the confirm (dropping an in-flight confirm); the confirm
 * AlertDialog rendered OUTSIDE the lock branch, so it floated above `LockScreen`
 * after a screen-off lock, and a confirmed "Clip" auto-applied at the NEXT
 * unlock with no per-session expiry.
 *
 * The fix: state is hoisted to the ViewModel (survives rotation), the dialog
 * render is gated under `authenticated`, and lock() drops both states.
 */
class Phase140ShareStateLockTest {

    // ---- model --------------------------------------------------------------

    @Test
    fun `pending states carry the clip and the post-confirm copy source`() {
        val confirm = PendingShareConfirmState(
            clip = SharedClip(kind = ClipKind.TEXT, text = "hello", streams = emptyList()),
            uriStrings = listOf("content://provider/a")
        )
        assertEquals("content://provider/a", confirm.uriStrings.single())
        assertEquals("hello", confirm.clip.text)

        val pending = PendingShareState(
            text = "hello",
            imagePaths = emptyList(),
            rawUris = listOf("content://provider/a")
        )
        assertEquals("hello", pending.text)
        assertEquals(listOf("content://provider/a"), pending.rawUris)
    }

    // ---- staging / confirmation transitions ----------------------------------

    @Test
    fun `a fresh share is staged only when nothing is already in flight`() {
        assertTrue(
            "no in-flight share -> staging allowed",
            PendingSharePolicy.shouldStage(currentConfirm = null, currentPending = null)
        )
        val confirm = PendingShareConfirmState(
            clip = SharedClip(ClipKind.TEXT, text = "x"),
            uriStrings = emptyList()
        )
        assertFalse(
            "an unanswered confirm blocks re-staging (rotation re-parse guard)",
            PendingSharePolicy.shouldStage(currentConfirm = confirm, currentPending = null)
        )
        val pending = PendingShareState("x", emptyList(), emptyList())
        assertFalse(
            "an answered-but-deferred clip blocks re-staging",
            PendingSharePolicy.shouldStage(currentConfirm = null, currentPending = pending)
        )
    }

    @Test
    fun `confirmation maps the hold into the deferred clip without moving bytes`() {
        val request = PendingShareConfirmState(
            clip = SharedClip(
                kind = ClipKind.IMAGES,
                text = "attacker text",
                streams = listOf(SharedStream(uriString = "content://p/i", mimeType = "image/png"))
            ),
            uriStrings = listOf("content://p/i")
        )
        val pending = PendingSharePolicy.toPendingShare(request)
        assertEquals("attacker text", pending.text)
        assertEquals(listOf("content://p/i"), pending.rawUris)
        assertTrue("the deferred copy source must start empty (bytes move only post-unlock)", pending.imagePaths.isEmpty())
        // No byte copy happens at confirm time — the raw content URIs are what
        // the post-unlock apply effect copies from.
        assertEquals(listOf("content://p/i"), request.uriStrings)
    }

    // ---- lock flush ----------------------------------------------------------

    @Test
    fun `lock clears the share state only when a lock boundary exists`() {
        assertTrue(
            "master-password vault -> lock drops the deferred clip (fail-closed)",
            PendingSharePolicy.clearOnLock(hasMasterPassword = true)
        )
        assertFalse(
            "passwordless vault has no lock boundary -> deferral survives",
            PendingSharePolicy.clearOnLock(hasMasterPassword = false)
        )
    }

    // ---- wiring source pins --------------------------------------------------

    @Test
    fun `lock clears both pending share states via the policy`() {
        val lock = read("app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt")
            .substringAfter("fun lock()")
            .substringBefore("override fun onCleared()")
        assertTrue(
            "lock() must consult PendingSharePolicy.clearOnLock",
            lock.contains("PendingSharePolicy.clearOnLock(")
        )
        assertTrue(
            "lock() must clear the un-confirmed confirm",
            lock.contains("_pendingShareConfirm.value = null")
        )
        assertTrue(
            "lock() must clear the deferred clip (no auto-apply at next unlock)",
            lock.contains("_pendingShare.value = null")
        )
    }

    @Test
    fun `the confirm dialog renders only while authenticated`() {
        val main = read("app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt")
        assertTrue(
            "the share-confirm dialog must be gated under authenticated",
            main.contains("if (authenticated) {") && main.contains("pendingShareConfirm?.let { request ->")
        )
        assertTrue(
            "the confirm/dismiss must route through the ViewModel holding the state",
            main.contains("viewModel.confirmPendingShare()") &&
                main.contains("viewModel.cancelPendingShareConfirm()")
        )
    }

    @Test
    fun `a rotation re-parse of the original SEND intent cannot re-prompt`() {
        val readShare = read("app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt")
            .substringAfter("private fun readShareIntent")
            .substringBefore("// 22.5 + B1-PLAT-2: copy shared content URIs")
        assertTrue(
            "readShareIntent must bail when a share is already in flight",
            readShare.contains("viewModel.pendingShareConfirm.value != null || viewModel.pendingShare.value != null")
        )
        assertTrue(
            "the accepted clip must be staged into the ViewModel",
            readShare.contains("viewModel.stagePendingShare(")
        )
        assertFalse(
            "the activity must no longer own the confirm hold",
            readShare.contains("pendingShareConfirm = PendingShareConfirm(")
        )
    }

    private fun read(relative: String): String {
        val file = File(repoRoot(), relative)
        assertTrue("sanity: $relative exists", file.isFile)
        return file.readText()
            .replace(Regex("//[^\\n]*"), "")
            .replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
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