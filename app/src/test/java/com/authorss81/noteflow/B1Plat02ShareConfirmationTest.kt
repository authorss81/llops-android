package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-PLAT-2 (phase-58) source-level wiring pins for the exported
 * `singleTask` MainActivity share fix.
 *
 * The finding (AndroidManifest.xml:33-68 + MainActivity.kt:95,502-600): a
 * malicious app can fire ACTION_SEND directly at the exported component, the app
 * is yanked to the foreground, and (a) attacker-supplied EXTRA_STREAM bytes are
 * copied into app-private storage while (b) an attacker-controlled note is
 * silently created on the next unlock.
 *
 * The fix, pinned here against the actual source (the pure-JVM byte-cap and
 * dialog-copy behaviors are unit-tested in <see>BoundedStreamCopierTest</see>
 * and <see>ClipShareConfirmNoticeTest</see>):
 *  1. an ACCEPTED share is HELD behind an explicit "Clip into InkFlow?"
 *     confirmation — it is never copied at intent-arrival time;
 *  2. the staging copy (`copySharedUris`) is byte-capped (per-item + total);
 *  3. the copy runs only inside the post-unlock apply effect — never while the
 *     vault is locked.
 */
class B1Plat02ShareConfirmationTest {

    private val source by lazy {
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt").readText()
    }

    private val readShareIntentRegion: String
        get() = source.substringAfter("private fun readShareIntent")
            .substringBefore("// B1-PLAT-2 (phase-58): the user explicitly confirmed the pending share")

    private val copySharedUrisRegion: String
        get() = source.substringAfter("private fun copySharedUris")
            .substringBefore("override fun onTrimMemory")

    // ---- 1. confirmation gate before any staging -----------------------------

    @Test
    fun `an accepted share is held behind an explicit confirmation - never copied on arrival`() {
        val region = readShareIntentRegion
        assertTrue(
            "a parsed Accept MUST create the pending-confirm hold, not copy",
            region.contains("pendingShareConfirm = PendingShareConfirm(")
        )
        assertFalse(
            "the intent-arrival path must NOT call copySharedUris",
            region.contains("copySharedUris(")
        )
        assertTrue(
            "the pre-confirm hold must keep the raw content URIs for the deferred copy",
            region.contains("uriStrings = parsed.clip.streams.map { it.uriString }")
        )
    }

    @Test
    fun `the confirmation dialog is wired and copy only starts on an explicit Clip tap`() {
        assertTrue("the dialog title must state it clips into InkFlow", source.contains("\"Clip into InkFlow?\""))
        assertTrue("the dialog must summarize the held share", source.contains("ClipShareConfirmNotice.summary(request.clip)"))
        assertTrue(
            "confirmPendingShare must be the ONLY staging trigger from the dialog",
            source.contains("TextButton(onClick = { confirmPendingShare() })")
        )
        val confirm = source.substringAfter("private fun confirmPendingShare")
            .substringBefore("// 22.5 + B1-PLAT-2: copy shared content URIs")
        assertTrue(
            "confirm must transition the hold into the pending share WITHOUT copying",
            confirm.contains("pendingShare = SharedContent(")
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