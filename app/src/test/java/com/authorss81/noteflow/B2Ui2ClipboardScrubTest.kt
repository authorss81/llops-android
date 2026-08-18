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
 * timestamp after a scrub so a foreign (other-app) copy is never wiped on the
 * windowed path. The Android-bound wiring — `lock()` clearing the clip
 * UNCONDITIONALLY on every lock path before the DEK is dropped (R2-B1P-01,
 * phase-139: the markdown-editor selection Copy and the OCR dialog's
 * `SelectionContainer` Copy are NATIVE, unstamped surfaces, so the lock cannot
 * rely on a stamp — it clears the whole primary clip, no window), the ON_PAUSE
 * hook keeping the windowed own-copy scrub, and both note-content copy sources
 * stamping the guard — is pinned at source level below.
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

    // ---------- R2-B1P-01 (phase-139): the lock clears UNCONDITIONALLY ----------
    //
    // The markdown editor's selection Copy (HybridMarkdownEditor) and the OCR
    // dialog's `SelectionContainer` Copy are PLATFORM-native — they write
    // decrypted note content to the system clipboard and no `recordCopy()` stamp
    // can observe them. The lock therefore cannot rely on a stamp; it clears the
    // whole primary clip, no window, fail-closed.

    @Test
    fun `unconditional scrub clears a foreign unstamped copy - the editor native copy case`() {
        // A decrypted note body was copied via the editor's platform selection
        // menu — ClipboardGuard.mostRecentCopyAtMs is still 0L (nothing stamped).
        assertTrue(
            "lock must clear the primary clip even when nothing stamped it (an unstamped editor/OCR native copy)",
            ClipboardGuard.scrubUnconditionally(null)
        )
        assertEquals("the untracked native copy is scrubbed — no window", 1, cleared)
        assertEquals("the stamp is forgotten after the unconditional clear", 0L, ClipboardGuard.mostRecentCopyAtMs)
    }

    @Test
    fun `unconditional scrub clears a fresh stamped app copy too`() {
        ClipboardGuard.recordCopy()

        assertTrue(ClipboardGuard.scrubUnconditionally(null))
        assertEquals(1, cleared)
        assertEquals("the stamp is forgotten after the unconditional clear", 0L, ClipboardGuard.mostRecentCopyAtMs)
    }

    @Test
    fun `an unconditional scrub failure is swallowed and returns false`() {
        ClipboardGuard.clearPrimaryClipOverride = { throw RuntimeException("clipboard denied") }

        assertFalse("a platform clear failure must be swallowed (best-effort)", ClipboardGuard.scrubUnconditionally(null))
        assertEquals(0, cleared)
    }

    @Test
    fun `the windowed path is retained on ON_PAUSE only - a foreign copy survives a brief app switch`() {
        // The ON_PAUSE lifecycle hook keeps ClipboardScrubPolicy windowed: a
        // brief switch to another app must never wipe a copy the user made there.
        assertFalse(ClipboardScrubPolicy.shouldScrub(copiedAtMs = 0L, nowMs = 1_000L))
        assertFalse("nothing stamped -> windowed ON_PAUSE scrub leaves the clipboard alone", ClipboardGuard.scrubIfOwnCopy(null))
        assertEquals(0, cleared)
    }

    // ---------- source-level wiring pins ----------

    @Test
    fun `lock clears the clipboard unconditionally on every lock path before the DEK is dropped`() {
        val source = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt").readText()
        val lockBlock = source.substringAfter("fun lock()", "END").substringBefore("override fun onCleared", "END")

        val scrubIdx = lockBlock.indexOf("ClipboardGuard.scrubUnconditionally(appContext)")
        assertTrue("lock() must clear the clipboard on every lock path", scrubIdx >= 0)
        assertFalse(
            "R2-B1P-01: the lock must NOT call the windowed own-copy-only scrub — the editor/OCR native copies stamp nothing, so the clip is cleared unconditionally",
            lockBlock.contains("ClipboardGuard.scrubIfOwnCopy(appContext)")
        )
        val zeroizeIdx = lockBlock.indexOf("repository.zeroizeKey()")
        assertTrue("lock() must still zeroize the DEK", zeroizeIdx >= 0)
        assertTrue(
            "the scrub must run BEFORE the DEK is dropped so a decrypted note body never survives the lock",
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

        // The ON_PAUSE scrub is retained as defense-in-depth, and it stays the
        // WINDOWED own-copy-only decision (ClipboardScrubPolicy) so a brief app
        // switch never wipes a foreign copy — the unconditional clear is the
        // lock() path's job (ON_STOP / manual / idle / screen-off).
        assertTrue(
            "ON_PAUSE still scrubs as defense in depth via the windowed own-copy path",
            activity.contains("ClipboardGuard.scrubIfOwnCopy(this)")
        )
        assertFalse(
            "ON_PAUSE must NOT use the unconditional clear — only the lock path may wipe a foreign clip",
            activity.substringBefore("Lifecycle.Event.ON_STOP").contains("scrubUnconditionally")
        )
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
        // R2-B1P-01 grep-pin: every Compose clipboard write in ui/ is preceded
        // by ClipboardGuard.recordCopy(). Enumerate rather than hard-code the
        // two known sites, so a future unguarded copy is caught here.
        val uiRoot = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui")
        val clipboardWrites = uiRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val text = file.readText()
                text.indicesOf("clipboardManager.setText(").map { it to file }
            }
            .toList()
        assertTrue(
            "there must be at least one note-content clipboard write in ui/ to pin",
            clipboardWrites.isNotEmpty()
        )
        for ((index, file) in clipboardWrites) {
            val text = file.readText()
            val stampedAt = text.lastIndexOf("ClipboardGuard.recordCopy()", index)
            assertTrue(
                "every clipboard write in ui/ must be preceded by ClipboardGuard.recordCopy() (${file.name})",
                stampedAt >= 0 && stampedAt < index
            )
        }
    }

    @Test
    fun `every production clipboard write is routed through the guard - no raw system writes elsewhere`() {
        // The guard is the ONLY production writer to the system clipboard:
        // clearPrimaryClip/setPrimaryClip live solely inside ClipboardGuard.kt,
        // so no note-content surface can bypass the lock-time scrub.
        val sourceRoot = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow")
        val rawWrites = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.absolutePath to it.readText() }
            .filter { (path, text) ->
                !path.endsWith("services/ClipboardGuard.kt") &&
                    (text.contains(".setPrimaryClip(") || text.contains(".clearPrimaryClip("))
            }
            .toList()
        assertTrue(
            "raw system clipboard writes must live only in ClipboardGuard.kt (found ${rawWrites.map { it.first }})",
            rawWrites.isEmpty()
        )
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

    private fun String.indicesOf(substring: String): List<Int> {
        val result = mutableListOf<Int>()
        var from = 0
        while (true) {
            val idx = indexOf(substring, from)
            if (idx < 0) break
            result.add(idx)
            from = idx + substring.length
        }
        return result
    }
}