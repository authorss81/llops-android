package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-PLAT-2 (phase-58) + R2-B1P-05 (phase-140) source-level wiring pins for the
 * exported `singleTask` MainActivity share fix.
 *
 * B1-PLAT-2 (the finding): a malicious app can fire ACTION_SEND directly at the
 * exported component, the app is yanked to the foreground, and (a) attacker-
 * supplied EXTRA_STREAM bytes are copied into app-private storage while (b) an
 * attacker-controlled note is silently created on the next unlock.
 *
 * The fix (pinned here): an ACCEPTED share is HELD behind an explicit
 * "Clip into InkFlow?" confirmation (never copied at intent-arrival time), the
 * staging copy is byte-capped, and the copy runs only inside the post-unlock
 * apply effect.
 *
 * R2-B1P-05 (phase-140, same code path): the confirm + deferred-clip state was
 * hoisted from activity `mutableStateOf` fields into the ViewModel so a
 * rotation (no configChanges -> recreation with the ORIGINAL SEND intent)
 * cannot re-prompt a confirm the user already answered/dismissed, the dialog
 * renders ONLY while authenticated, and lock() drops both states instead of
 * letting a pre-lock "Clip" auto-apply at the next unlock.
 */
class B1Plat02ShareConfirmationTest {

    private val source by lazy {
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt").readText()
    }

    private val viewModelSource by lazy {
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt").readText()
    }

    private val readShareIntentRegion: String
        get() = source.substringAfter("private fun readShareIntent")
            .substringBefore("// R2-B1P-05: the \"user explicitly confirmed\" transition now lives in the")

    private val copySharedUrisRegion: String
        get() = source.substringAfter("private fun copySharedUris")
            .substringBefore("override fun onTrimMemory")

    // ---- 1. confirmation gate before any staging -----------------------------

    @Test
    fun `an accepted share is held behind an explicit confirmation - never copied on arrival`() {
        val region = readShareIntentRegion
        assertTrue(
            "a parsed Accept MUST create the pending-confirm hold via the ViewModel, not copy",
            region.contains("viewModel.stagePendingShare(parsed.clip, parsed.clip.streams.map { it.uriString })")
        )
        assertFalse(
            "the intent-arrival path must NOT call copySharedUris",
            region.contains("copySharedUris(")
        )
        assertTrue(
            "the pre-confirm hold must keep the raw content URIs for the deferred copy",
            region.contains("parsed.clip.streams.map { it.uriString }")
        )
        // R2-B1P-05: the activity must no longer own the confirm state, and a
        // re-parsed original-SEND-intent on a rotated-recreated activity must
        // bail while a share is in flight.
        assertFalse(
            "the activity must not own an activity-scoped confirm hold",
            region.contains("pendingShareConfirm = PendingShareConfirm(")
        )
        assertTrue(
            "a rotated-recreated activity must not re-parse an in-flight share",
            region.contains("viewModel.pendingShareConfirm.value != null || viewModel.pendingShare.value != null")
        )
    }

    @Test
    fun `the confirmation dialog is wired and copy only starts on an explicit Clip tap`() {
        assertTrue("the dialog title must state it clips into InkFlow", source.contains("\"Clip into InkFlow?\""))
        assertTrue("the dialog must summarize the held share", source.contains("ClipShareConfirmNotice.summary(request.clip)"))
        assertTrue(
            "confirm must route through the ViewModel state (R2-B1P-05) and carry the 22.5 capture mode",
            source.contains("viewModel.confirmPendingShare(clipMode)")
        )
        assertTrue(
            "dismiss must clear the ViewModel-held confirm",
            source.contains("viewModel.cancelPendingShareConfirm()")
        )
        // R2-B1P-05: the confirm dialog must never float above the LockScreen.
        assertTrue(
            "the dialog render must be gated under authenticated",
            source.contains("if (authenticated) {") && source.contains("pendingShareConfirm?.let { request ->")
        )
        val confirm = viewModelSource.substringAfter("fun confirmPendingShare(captureMode: ShareCaptureMode")
            .substringBefore("fun consumePendingShare")
        assertTrue(
            "confirm must transition the hold into the pending share WITHOUT copying (22.5: + capture mode)",
            confirm.contains("PendingSharePolicy.toPendingShare(request")
        )
        assertFalse("confirm itself must not copy bytes", confirm.contains("copySharedUris("))
    }

    // ---- 2. byte cap on copySharedUris ---------------------------------------

    @Test
    fun `the staging copy is bounded per item and in total`() {
        val region = copySharedUrisRegion
        assertTrue(
            "every staged stream must flow through the bounded copier",
            region.contains("BoundedStreamCopier.copyBounded(")
        )
        assertFalse(
            "the unbounded copyTo must be gone from the staging path",
            region.contains("input.copyTo(out)")
        )
        assertTrue(
            "an over-budget share must fail closed with the clean cap exception",
            region.contains("catch (e: com.authorss81.noteflow.services.ImportArchivePolicy.ImportSizeLimitException)")
        )
        assertTrue(
            "an over-budget share must scrub its partial/prior staged files",
            region.contains("copied.forEach { File(it).delete() }")
        )
    }

    // ---- 3. no pre-copy while the vault is locked ----------------------------

    @Test
    fun `bytes are copied only in the post-unlock apply effect - never while locked`() {
        val applyEffect = source.substringAfter("LaunchedEffect(authenticated, pendingShare)")
            .substringBefore("NoteflowTheme(themeMode = themeMode)")
        assertTrue(
            "the copy must live in the apply effect only",
            applyEffect.contains("copySharedUris(")
        )
        assertTrue(
            "the apply effect must bail while the vault is locked",
            applyEffect.contains("if (!authenticated) return@LaunchedEffect")
        )
        val beforeEachCopy = applyEffect.substringBefore("copySharedUris(")
        assertTrue(
            "the locked check must precede the byte copy",
            beforeEachCopy.contains("if (!authenticated) return@LaunchedEffect")
        )
        assertTrue("the pending share must carry the raw URIs for the post-unlock copy", source.contains("rawUris"))
    }

    // ---- 4. lock() drops the deferred clip -----------------------------------

    @Test
    fun `lock clears both share states so a pre-lock Clip never auto-applies at the next unlock`() {
        val lock = viewModelSource.substringAfter("fun lock()")
            .substringBefore("override fun onCleared()")
        assertTrue(
            "lock must consult R2-B1P-05's flush policy",
            lock.contains("PendingSharePolicy.clearOnLock(")
        )
        assertTrue(
            "lock must clear the un-confirmed confirm",
            lock.contains("_pendingShareConfirm.value = null")
        )
        assertTrue(
            "lock must clear the deferred clip",
            lock.contains("_pendingShare.value = null")
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