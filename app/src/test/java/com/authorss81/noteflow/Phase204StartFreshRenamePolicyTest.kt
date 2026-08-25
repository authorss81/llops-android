package com.authorss81.noteflow

import com.authorss81.noteflow.services.StartFreshVaultResetPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 204 — silent data-loss fix #3: the keystore-loss "start fresh" escape
 * hatch bricked exactly when needed.
 *
 * Pre-fix: `NoteflowViewModel.quarantineVaultFiles` wrapped each vault-file
 * rename in `runCatching { renameTo(...) }`, swallowed every failure, and
 * unconditionally proceeded to `clearDek()` + fresh-DEK boot. If
 * `noteflow.sqlite` (or a wal/shm sidecar) failed to move aside, the brand-new
 * vault was opened on top of the OLD ciphertext → decrypt/integrity fail →
 * recovery loop.
 *
 * Fix shape (pure-JVM decision table + wiring pins):
 *  - [StartFreshVaultResetPolicy.decide] = the outcome matrix: proceed only
 *    when EVERY vault file that existed actually moved; any unmoved existing
 *    file (main DB or sidecar) aborts;
 *  - `startFreshAfterKeystoreKeyLoss` aborts with a surfaced fixed error and
 *    leaves the recovery screen + old vault untouched (no clearDek, no new DEK).
 */
class Phase204StartFreshRenamePolicyTest {

    private fun rename(
        name: String,
        role: StartFreshVaultResetPolicy.VaultFileRole,
        existed: Boolean,
        moved: Boolean
    ) = StartFreshVaultResetPolicy.VaultFileRename(
        fileName = name, role = role, sourceExisted = existed, moved = moved
    )

    private val mainDb = "noteflow.sqlite"
    private val wal = "noteflow.sqlite-wal"
    private val shm = "noteflow.sqlite-shm"
    private val journal = "noteflow.sqlite-journal"

    // ---------------- the outcome matrix ----------------

    @Test
    fun `no rename attempts at all proceeds`() {
        assertEquals(
            StartFreshVaultResetPolicy.Decision.Proceed,
            StartFreshVaultResetPolicy.decide(emptyList())
        )
    }

    @Test
    fun `a brand-new install with no vault files proceeds`() {
        val outcomes = StartFreshVaultResetPolicy.QUARANTINE_FILES.map { (name, role) ->
            rename(name, role, existed = false, moved = false)
        }
        assertEquals(
            StartFreshVaultResetPolicy.Decision.Proceed,
            StartFreshVaultResetPolicy.decide(outcomes)
        )
    }

    @Test
    fun `every existing file moved proceeds`() {
        val outcomes = StartFreshVaultResetPolicy.QUARANTINE_FILES.map { (name, role) ->
            rename(name, role, existed = true, moved = true)
        }
        assertEquals(
            StartFreshVaultResetPolicy.Decision.Proceed,
            StartFreshVaultResetPolicy.decide(outcomes)
        )
    }

    @Test
    fun `only the main db exists and it moved proceeds`() {
        val outcomes = listOf(rename(mainDb, StartFreshVaultResetPolicy.VaultFileRole.MAIN_DB, existed = true, moved = true))
        assertEquals(
            StartFreshVaultResetPolicy.Decision.Proceed,
            StartFreshVaultResetPolicy.decide(outcomes)
        )
    }

    @Test
    fun `an unmoved MAIN DB aborts naming it`() {
        val outcomes = listOf(
            rename(mainDb, StartFreshVaultResetPolicy.VaultFileRole.MAIN_DB, existed = true, moved = false),
            rename(wal, StartFreshVaultResetPolicy.VaultFileRole.WAL, existed = true, moved = true)
        )
        val decision = StartFreshVaultResetPolicy.decide(outcomes)
        assertTrue(decision is StartFreshVaultResetPolicy.Decision.Abort)
        assertEquals(listOf(mainDb), (decision as StartFreshVaultResetPolicy.Decision.Abort).blockedBy)
    }

    @Test
    fun `an unmoved SIDECAR aborts too - leftover ciphertext poisons the fresh db`() {
        val outcomes = listOf(
            rename(mainDb, StartFreshVaultResetPolicy.VaultFileRole.MAIN_DB, existed = true, moved = true),
            rename(wal, StartFreshVaultResetPolicy.VaultFileRole.WAL, existed = true, moved = false)
        )
        val decision = StartFreshVaultResetPolicy.decide(outcomes)
        assertTrue(decision is StartFreshVaultResetPolicy.Decision.Abort)
        assertEquals(listOf(wal), (decision as StartFreshVaultResetPolicy.Decision.Abort).blockedBy)
    }

    @Test
    fun `mixed outcomes list ONLY the unmoved files`() {
        val outcomes = listOf(
            rename(mainDb, StartFreshVaultResetPolicy.VaultFileRole.MAIN_DB, existed = true, moved = false),
            rename(wal, StartFreshVaultResetPolicy.VaultFileRole.WAL, existed = true, moved = true),
            rename(shm, StartFreshVaultResetPolicy.VaultFileRole.SHM, existed = false, moved = false),
            rename(journal, StartFreshVaultResetPolicy.VaultFileRole.JOURNAL, existed = true, moved = false)
        )
        val decision = StartFreshVaultResetPolicy.decide(outcomes)
        assertEquals(
            StartFreshVaultResetPolicy.Decision.Abort(listOf(mainDb, journal)),
            decision
        )
    }

    @Test
    fun `the abort message is fixed honest text without paths or exception fragments`() {
        val msg = StartFreshVaultResetPolicy.ABORT_MESSAGE
        assertTrue(msg.isNotBlank())
        assertFalse("never leak absolute paths", msg.contains('/'))
        assertFalse("never leak file names", msg.contains(mainDb))
        assertTrue(
            "review fix: a PARTIAL move must not be called 'unchanged' — promise only no-deletion",
            msg.contains("Nothing was deleted") && !msg.contains("unchanged")
        )
    }

    // ---------------- wiring pins ----------------

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
    fun `start-fresh evaluates the matrix BEFORE clearing anything and aborts surfaced`() {
        val vm = sourceFile("ui/viewmodel/NoteflowViewModel.kt")
        val body = vm.substringAfter("fun startFreshAfterKeystoreKeyLoss() {")
            .substringBefore("private fun quarantineVaultFiles")
        val decideIdx = body.indexOf("StartFreshVaultResetPolicy.decide(outcomes)")
        val clearDekIdx = body.indexOf("security.clearDek()")
        // The abort write sits AFTER the decision (the leading `_startFreshError.value = null`
        // reset at function top is intentionally earlier).
        val abortIdx = body.indexOf("_startFreshError.value =", decideIdx)
        assertTrue("the decision must be evaluated", decideIdx >= 0)
        assertTrue(
            "the decision must gate BEFORE clearDek / new-DEK boot",
            decideIdx in 0 until clearDekIdx
        )
        val returnIdx = if (abortIdx >= 0) body.indexOf("return@launch", abortIdx) else -1
        assertTrue(
            "an abort surfaces the fixed policy message",
            abortIdx > decideIdx && body.contains("StartFreshVaultResetPolicy.ABORT_MESSAGE")
        )
        assertTrue(
            "an abort leaves the recovery flow up (no keystore-lost reset)",
            returnIdx > abortIdx &&
                body.indexOf("_keystoreKeyLost.value = false") > returnIdx
        )
    }

    @Test
    fun `quarantine collects outcomes instead of swallowing them`() {
        val vm = sourceFile("ui/viewmodel/NoteflowViewModel.kt")
        val fn = vm.substringAfter("private fun quarantineVaultFiles(suffixTag: String)")
        assertTrue(
            "the renames must return VaultFileRename outcomes",
            fn.contains("List<com.authorss81.noteflow.services.StartFreshVaultResetPolicy.VaultFileRename>")
        )
        assertTrue(
            "a thrown rename is collected as unmoved",
            fn.contains("catch (e: Exception)") && fn.contains("moved = moved")
        )
        assertTrue(
            "the canonical QUARANTINE_FILES table drives the loop",
            fn.contains("StartFreshVaultResetPolicy.QUARANTINE_FILES")
        )
    }

    @Test
    fun `the recovery screen renders the start-fresh abort error`() {
        val activity = File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt"
        ).readText()
        assertTrue(
            "KeystoreKeyLostScreen collects startFreshError",
            activity.contains("val startFreshError by viewModel.startFreshError.collectAsState()")
        )
        assertTrue(
            "the abort message is rendered on screen",
            activity.contains("startFreshError?.let {")
        )
    }
}
