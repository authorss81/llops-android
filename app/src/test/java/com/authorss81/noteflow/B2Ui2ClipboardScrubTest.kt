package com.authorss81.noteflow

import com.authorss81.noteflow.services.ClipboardGuard
import com.authorss81.noteflow.services.ClipboardScrubPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * B2-UI-2 (phase-72) behavioral + wiring tests for the clipboard-scrub-on-lock
 * gap.
 *
 * Finding: `ClipboardGuard.scrubIfOwnCopy` was only wired to ON_PAUSE. The
 * in-app lock paths — the manual "Lock Vault Now" button, the idle auto-lock and
 * the ACTION_SCREEN_OFF receiver — called `viewModel.lock()` directly, and
 * because the app stays foregrounded ON_PAUSE may never fire, so decrypted note
 * content copied to the system clipboard (code blocks, OCR text) sat readable by
 * any installed clipboard-reader app after an in-app lock.
 *
 * What is provable on the pure JVM: the decide → clear → forget sequence —
 * [ClipboardScrubPolicy] is the decision table, [ClipboardGuard] routes the
 * actual primary-clip clear through the internal [ClipboardGuard.clearPrimaryClipOverride]
 * test seam so the clear event is observable, and the guard forgets its copy
 * timestamp after a scrub so a foreign (other-app) copy is never wiped. The
 * Android-bound wiring — `lock()` scrubbing the clipboard on every lock path
 * before the DEK is dropped, and both note-content copy sources stamping the
 * guard — is pinned at source level below.
 */
class B2Ui2ClipboardScrubTest {

    private var cleared = 0

    @Before
    fun setUp() {
        cleared = 0
        ClipboardGuard.mostRecentCopyAtMs = 0L
        ClipboardGuard.clearPrimaryClipOverride = { cleared++ }
    }

    @After
    fun tearDown() {
        ClipboardGuard.mostRecentCopyAtMs = 0L
        ClipboardGuard.clearPrimaryClipOverride = null
    }

    // ---------- the pure-JVM decision table ----------

    @Test
    fun `never copied means never scrub`() {
        assertFalse(
            "a zero timestamp (no app copy, or already scrubbed) must not clear anyone's clipboard",
            ClipboardScrubPolicy.shouldScrub(copiedAtMs = 0L, nowMs = 1_000L)
        )
    }

    @Test
    fun `a fresh app copy inside the window scrubs`() {
        val now = 10_000L
        assertTrue(ClipboardScrubPolicy.shouldScrub(copiedAtMs = now - 5_000L, nowMs = now))
    }

    @Test
    fun `an app copy exactly at the window boundary scrubs`() {
        val now = 10_000L
        assertTrue(
            "the boundary itself is owned by the app copy",
            ClipboardScrubPolicy.shouldScrub(copiedAtMs = now - ClipboardScrubPolicy.SCRUB_WINDOW_MS, nowMs = now)
        )
    }

    @Test
    fun `an app copy just past the window does not scrub`() {
        val now = 10_000L
        assertFalse(
            "an expired app copy is treated as foreign — never cleared",
            ClipboardScrubPolicy.shouldScrub(copiedAtMs = now - ClipboardScrubPolicy.SCRUB_WINDOW_MS - 1L, nowMs = now)
        )
    }

    // ---------- recordCopy -> scrub clears the primary clip ----------

    @Test
    fun `recordCopy then scrub clears the primary clip and forgets the timestamp`() {
        ClipboardGuard.recordCopy()

        val scrubbed = ClipboardGuard.scrubIfOwnCopy(null)

        assertTrue("a recent app-owned copy must be cleared by the lock", scrubbed)
        assertEquals("the primary clip was cleared exactly once", 1, cleared)
        assertEquals(
            "after a scrub the guard must forget its copy so the next lock leaves a later foreign copy alone",
            0L,
            ClipboardGuard.mostRecentCopyAtMs
        )
    }

    // ---------- a foreign (non-app) copy is never wiped ----------

    @Test
    fun `a lock with no app copy does not wipe the clipboard`() {
        assertFalse("nothing the app copied -> nothing to clear", ClipboardGuard.scrubIfOwnCopy(null))
        assertEquals("the foreign clipboard must be untouched", 0, cleared)
    }

    @Test
    fun `an expired app copy does not wipe a foreign clipboard`() {
        ClipboardGuard.mostRecentCopyAtMs = System.currentTimeMillis() - ClipboardScrubPolicy.SCRUB_WINDOW_MS - 1_000L

        assertFalse("an app copy older than the window is never cleared", ClipboardGuard.scrubIfOwnCopy(null))
        assertEquals("the foreign clipboard must be untouched", 0, cleared)
    }

    @Test
    fun `a foreign copy made after a previous scrub survives the next lock`() {
        ClipboardGuard.recordCopy()
        assertTrue(ClipboardGuard.scrubIfOwnCopy(null))
        assertEquals(1, cleared)
        assertEquals(0L, ClipboardGuard.mostRecentCopyAtMs)

        // The user then copies something from another app — the guard tracks only
        // app copies, so the next lock must not touch the foreign clipboard.
        assertFalse("a second lock must not wipe a later foreign copy", ClipboardGuard.scrubIfOwnCopy(null))
        assertEquals("no second clear happened", 1, cleared)
    }

    @Test
    fun `a locked system clear failure returns false and never crashes`() {
        ClipboardGuard.clearPrimaryClipOverride = { throw RuntimeException("clipboard denied") }
        ClipboardGuard.recordCopy()

        assertFalse("a platform clear failure must be swallowed (best-effort)", ClipboardGuard.scrubIfOwnCopy(null))
        assertEquals(0, cleared)
    }

    // ---------- source-level wiring pins ----------

    @Test
    fun `lock scrubs the clipboard on every lock path before the DEK is dropped`() {
        val source = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt").readText()
        val lockBlock = source.substringAfter("fun lock()", "END").substringBefore("override fun onCleared", "END")

        val scrubIdx = lockBlock.indexOf("ClipboardGuard.scrubIfOwnCopy(appContext)")
        assertTrue("lock() must scrub the clipboard on every lock path", scrubIdx >= 0)
        val zeroizeIdx = lockBlock.indexOf("repository.zeroizeKey()")
        assertTrue("lock() must still zeroize the DEK", zeroizeIdx >= 0)
        assertTrue(
            "the scrub must run BEFORE the DEK is dropped so an app-owned copy never survives the lock",
            scrubIdx < zeroizeIdx
        )
        val passwordGateIdx = lockBlock.indexOf("if (settings.hasMasterPassword)")
        assertTrue(passwordGateIdx >= 0)
        assertTrue(
            "the scrub must run unconditionally — before any passwordless-vault gate — so every lock covers every vault kind",
            scrubIdx < passwordGateIdx
        )
    }

    @Test
    fun `every in-app lock entry point routes through viewModel lock and needs no separate scrub`() {
        val activity = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt").readText()
        val lockCalls = Regex("viewModel\\.lock\\(\\)").findAll(activity).count()
        assertTrue(
            "ON_STOP, idle auto-lock and ACTION_SCREEN_OFF must each lock via the viewModel (found $lockCalls)",
            lockCalls >= 3
        )

        val dialogs = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/Dialogs.kt").readText()
        val lockLabelIdx = dialogs.indexOf("Text(\"Lock Vault Now\")")
        assertTrue("Lock Vault Now button must exist", lockLabelIdx > 0)
        val buttonBlock = dialogs.substring(lockLabelIdx - 2000, lockLabelIdx)
        assertTrue("the manual Lock Vault Now button must lock via the viewModel", buttonBlock.contains("viewModel.lock()"))

        // The ON_PAUSE scrub is retained as defense-in-depth — the lock that
        // ON_STOP issues now scrubs regardless of whether ON_PAUSE ever fired.
        assertTrue("ON_PAUSE still scrubs as defense in depth", activity.contains("ClipboardGuard.scrubIfOwnCopy"))
    }

    @Test
    fun `production scrub still clears through the real system clipboard service`() {
        val source = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/ClipboardGuard.kt").readText()
        assertTrue(source.contains("getSystemService(Context.CLIPBOARD_SERVICE)"))
        assertTrue("API 28+ clears via clearPrimaryClip", source.contains("cm.clearPrimaryClip()"))
        assertTrue("API 26-27 falls back to an empty primary clip", source.contains("cm.setPrimaryClip(ClipData.newPlainText(\"\", \"\"))"))
    }

    @Test
    fun `every note-content copy stamps the guard before writing to the clipboard`() {
        val ocr = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/OcrResultDialog.kt").readText()
        val ocrCopyAt = ocr.indexOf("ClipboardGuard.recordCopy()")
        val ocrWriteAt = ocr.indexOf("clipboardManager.setText(AnnotatedString(s.text))")
        assertTrue("OCR copy must stamp the guard", ocrCopyAt >= 0 && ocrWriteAt >= 0)
        assertTrue("OCR copy must stamp the guard before writing to the clipboard", ocrCopyAt < ocrWriteAt)

        val embed = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/MediaEmbedComponents.kt").readText()
        val codeCopyAt = embed.indexOf("ClipboardGuard.recordCopy()")
        val codeWriteAt = embed.indexOf("clipboardManager.setText(AnnotatedString(codeText))")
        assertTrue("code-block copy must stamp the guard", codeCopyAt >= 0 && codeWriteAt >= 0)
        assertTrue("code-block copy must stamp the guard before writing to the clipboard", codeCopyAt < codeWriteAt)
    }

    private fun repoRoot(): java.io.File {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (java.io.File(dir, "gradle/libs.versions.toml").isFile &&
                java.io.File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}