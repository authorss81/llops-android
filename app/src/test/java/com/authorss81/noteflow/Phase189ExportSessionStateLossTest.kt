package com.authorss81.noteflow

import com.authorss81.noteflow.services.ExportSessionPolicy
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 189 (2026-08-20): "Backup to file" / "Backup from file" must keep
 * working immediately after a vault export.
 *
 * Root cause (STEP1_TRACE.md): the export chain is session read-only (it never
 * closes/zeroizes/reopens the Room instance), but its staged-snapshot prunes
 * (`pruneStagedSnapshotVersions` / `pruneStagedSnapshotLayers`) re-read the
 * mutable `VaultKeyHolder.dek` singleton at PRUNE time. `MainActivity` calls
 * `viewModel.lock()` on ANY ON_STOP (`MainActivity.kt:207-209` — including the
 * SAF export destination picker); for a password-protected vault that zeroizes
 * the DEK mid-export, so the current export fails with the fixed-text "vault is
 * locked" message and the immediately-following backup is poisoned until the
 * identity is re-seeded (a restart).
 *
 * Fix: `exportBackup` pins the DEK it was HANDED at export start — a
 * snapshot-at-entry COPY resolved once through the pure-JVM
 * [ExportSessionPolicy.pinnedPruneDek] — and hands that pin to both staged
 * prunes, which no longer reference `VaultKeyHolder` at all. The pin is zeroized
 * immediately after both prunes. A lock that zeroizes the live array mid-export
 * can no longer fail the backup (or the next one); the prunes run under the
 * SAME key the export used.
 *
 * Pure-JVM: the [ExportSessionPolicy] decision is exercised directly; the
 * Android-bound wiring is pinned at source level (same technique as
 * Phase181ExportReturnNotebookRestoreTest / Phase149NoteVersionsRetentionTest).
 */
class Phase189ExportSessionStateLossTest {

    // ---------- behavior: the pinned DEK survives a mid-export zeroization ----------

    @Test
    fun `the export pin is a COPY that survives a mid-export zeroization of the source DEK`() {
        val liveDek = ByteArray(32) { (it + 1).toByte() } // the export's own key
        val pin = ExportSessionPolicy.pinnedPruneDek(liveDek) { null }
            ?: error("a non-null held key must yield a pin")

        val expected = ByteArray(32) { (it + 1).toByte() }
        assertArrayEquals("the pin holds the export key's bytes", expected, pin)
        assertTrue("the pin must be a distinct copy, not the live array", pin !== liveDek)

        // A lock() during export zeroizes the LIVE array:
        liveDek.fill(0.toByte())

        assertArrayEquals(
            "zeroizing the source must not null out the pinned prune key",
            expected,
            pin
        )
    }

    @Test
    fun `the export pin falls back to the holder and still yields a copy`() {
        val held = ByteArray(16) { (it + 7).toByte() }
        val pin = ExportSessionPolicy.pinnedPruneDek(null) { held }
            ?: error("a non-null holder key must yield a pin")

        assertTrue("the fallback pin is a copy", pin !== held)
        val expected = ByteArray(16) { (it + 7).toByte() }
        assertArrayEquals("copy matches the holder bytes", expected, pin)

        held.fill(0.toByte())
        assertArrayEquals("a zeroized holder array cannot null the pin", expected, pin)
    }

    @Test
    fun `a locked vault with no key and no holder degrades to the fail-closed refusal`() {
        assertNull(
            "no DEK anywhere -> null, surfaced as the locked-snapshot error",
            ExportSessionPolicy.pinnedPruneDek(null) { null }
        )
    }

    @Test
    fun `zeroize clears the pinned copy after the prunes run`() {
        val pin = ExportSessionPolicy.pinnedPruneDek(ByteArray(32) { 5 }) { null }
            ?: error("expected a pin")
        ExportSessionPolicy.zeroize(pin)
        assertTrue("the pin is zeroized after use", pin.all { it == 0.toByte() })
        ExportSessionPolicy.zeroize(null) // safe no-op must not throw
    }

    // ---------- wiring pins: exportBackup + both staged prunes (source-level) ----------

    private val service by lazy {
        File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt"
        ).readText()
    }
    private val exportRegion by lazy {
        service.substringAfter("suspend fun exportBackup(").substringBefore("private fun copyWithLimit")
    }

    @Test
    fun `exportBackup pins the DEK it was handed for the staged-snapshot prunes and zeroizes it after`() {
        assertTrue(
            "the pin must be resolved once, from the export's own key with the holder fallback",
            exportRegion.contains("ExportSessionPolicy.pinnedPruneDek(key) { VaultKeyHolder.dek }")
        )
        assertTrue(
            "the version-history prune must run under the pinned key",
            exportRegion.contains("pruneStagedSnapshotVersions(stagedDb, pruneDek)")
        )
        assertTrue(
            "the layer prune must run under the pinned key",
            exportRegion.contains("pruneStagedSnapshotLayers(stagedDb, pruneDek)")
        )
        assertTrue(
            "the pin must be zeroized immediately after both prunes",
            exportRegion.contains("ExportSessionPolicy.zeroize(pruneDek)")
        )
        assertTrue(
            "the locked-vault refusal must come from the policy, not a fresh singleton read",
            exportRegion.contains("ExportSessionPolicy.LOCKED_SNAPSHOT_ERROR")
        )
    }

    @Test
    fun `the staged prunes are keyed by the pinned DEK parameter and never re-read the mutable singleton`() {
        val versionsPrune = service.substringAfter("private fun pruneStagedSnapshotVersions")
            .substringBefore("private fun ByteArray.toSqlcipherPassphraseBytes")
        val layersPrune = service.substringAfter("private fun pruneStagedSnapshotLayers")
            .substringBefore("private fun sanitizeRestoredSourceFilePaths")

        for (prune in listOf(versionsPrune, layersPrune)) {
            assertTrue(
                "the prune must accept the pinned DEK as a parameter",
                prune.contains("stagedDb: File, dek: ByteArray)")
            )
            assertTrue(
                "the prune must open ONLY the staged snapshot copy",
                prune.contains("openOrCreateDatabase(") && prune.contains("stagedDb, passphrase")
            )
            assertTrue(
                "the prunes must be fully decoupled from VaultKeyHolder (the phase-189 defect)",
                !prune.contains("VaultKeyHolder")
            )
            assertTrue("the prune must never touch the live repository", !prune.contains("repository."))
        }
    }

    @Test
    fun `the fixed backup-failure texts are centralized in the policy with no inline duplicates`() {
        assertTrue(
            "the torn-copy text routes through the policy constant",
            exportRegion.contains("ExportSessionPolicy.KEEP_CHANGING_ERROR")
        )
        assertTrue(
            "no old inline 'kept changing' string survives in exportBackup",
            !exportRegion.contains("\"Backup failed: the vault database kept changing")
        )
        assertTrue(
            "no old inline 'vault is locked; cannot bound' string survives in the export path",
            !exportRegion.contains("\"Backup failed: the vault is locked; cannot bound")
        )
    }

    // ---------- helpers ----------

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