package com.authorss81.noteflow

import com.authorss81.noteflow.services.VaultSnapshotCopyPolicy
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * R2-B1D-05 + R2-B1D-03 (phase-137): every DB-file copy producer runs
 * checkpoint-then-copy, and the snapshot copy itself is VERIFIED so a torn
 * main-file snapshot (a WAL auto-checkpoint firing mid-copy) or a WAL-stale
 * archive (no pre-export FULL checkpoint) can never be produced.
 *
 * Findings:
 *  - R2-B1D-05: `ImportExportService.exportBackup` copied the live main DB file
 *    with a plain unbounded FileInputStream — unlike the checkpoint-first
 *    producers — so an auto-checkpoint during the copy yielded a main-file
 *    snapshot that never existed (silently broken archive).
 *  - R2-B1D-03: LocalSend's `VAULT_BACKUP` called `exportBackup` directly with
 *    no pre-export `wal_checkpoint(FULL)`, so the receiving archive silently
 *    missed the last note edits still resident in the -wal.
 *
 * Fix shape: `exportBackup` is now the SINGLE disciplined producer — it
 * FULL-checkpoints the WAL, re-stamps the HMAC baseline, and copies the main
 * file through [VaultSnapshotCopyPolicy.checkpointThenCopy]'s verified snapshot
 * (source digested before + after the copy AND the staging digested; accepted
 * only when all three match, retried on a racing writer, fail-closed after
 * [VaultSnapshotCopyPolicy.MAX_VERIFY_ATTEMPTS]). LocalSend and every other
 * producer route through it by passing their repository.
 *
 * Pure JVM: real `VaultSnapshotCopyPolicy` behavior against temp files with a
 * fake checkpoint/copy seam (simulating a concurrent auto-checkpoint), plus
 * source-level wiring pins that the checkpoint runs before the copy inside
 * `exportBackup` and that all four producers pass their repository.
 */
class Phase137BackupCopyConsistencyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun deterministicBytes(size: Int, seed: Int): ByteArray =
        ByteArray(size) { ((it * seed) and 0x7f).toByte() }

    // ---------------------------------------------------------------------
    // pure-JVM behavior: the verified checkpoint-then-copy policy
    // ---------------------------------------------------------------------

    @Test
    fun `checkpoint runs before any copy and a stable source yields a verified byte-identical snapshot`() {
        val dir = tmp.newFolder()
        val src = File(dir, "noteflow.sqlite")
        val body = deterministicBytes(64 * 1024 + 7, seed = 3)
        src.writeBytes(body)
        val dest = File(dir, "snapshot.sqlite")

        val events = mutableListOf<String>()
        val ok = VaultSnapshotCopyPolicy.checkpointThenCopy(
            source = src,
            destination = dest,
            checkpoint = VaultSnapshotCopyPolicy.DbCheckpoint { events.add("checkpoint") },
            copy = VaultSnapshotCopyPolicy.DbCopy { s, d ->
                events.add("copy")
                s.copyTo(d)
            }
        )

        assertTrue("the verified copy must succeed on a stable source", ok)
        assertEquals(
            "the FULL checkpoint must run BEFORE any byte of the copy",
            listOf("checkpoint", "copy"),
            events
        )
        assertTrue("the verified snapshot must exist", dest.exists())
        assertArrayEquals(
            "the snapshot must be byte-identical to the source (the post-checkpoint main file)",
            body,
            dest.readBytes()
        )
    }

    @Test
    fun `a source mutated mid-copy is retried and the final snapshot is consistent, never torn`() {
        val dir = tmp.newFolder()
        val src = File(dir, "noteflow.sqlite")
        val body = deterministicBytes(4096, seed = 5)
        src.writeBytes(body)
        val dest = File(dir, "snapshot.sqlite")

        var copyCalls = 0
        val racingCopy = VaultSnapshotCopyPolicy.DbCopy { s, d ->
            copyCalls++
            if (copyCalls == 1) {
                // Simulate a WAL auto-checkpoint tearing the copy: write HALF the
                // source, then rewrite the source (the checkpoint), then finish the
                // now-stale copy — a main-file snapshot that never existed.
                val bytes = s.readBytes()
                val half = bytes.size / 2
                d.writeBytes(bytes.copyOf(half))
                s.writeBytes(bytes.plus(ByteArray(16) { 0x7f.toByte() }))
                FileOutputStream(d, true).use { out -> out.write(ByteArray(16) { 0x7f.toByte() }) }
            } else {
                s.copyTo(d, overwrite = true)
            }
        }

        val ok = VaultSnapshotCopyPolicy.checkpointThenCopy(src, dest, copy = racingCopy)

        assertTrue("the racing copy must be retried until the source is stable", ok)
        assertTrue(
            "the first torn attempt must have been discarded and re-run",
            copyCalls >= 2
        )
        assertArrayEquals(
            "the accepted snapshot must equal the FINAL source state (never a torn mix)",
            src.readBytes(),
            dest.readBytes()
        )
    }

    @Test
    fun `a perpetually-mutating source fails closed and leaves no torn staging behind`() {
        val dir = tmp.newFolder()
        val src = File(dir, "noteflow.sqlite")
        src.writeBytes(deterministicBytes(2048, seed = 9))
        val dest = File(dir, "snapshot.sqlite")

        var copyCalls = 0
        val alwaysRacing = VaultSnapshotCopyPolicy.DbCopy { s, d ->
            copyCalls++
            s.writeBytes(s.readBytes().plus(ByteArray(1) { 0x01 }))
            s.copyTo(d, overwrite = true)
        }

        val ok = VaultSnapshotCopyPolicy.checkpointThenCopy(
            src,
            dest,
            copy = alwaysRacing,
            maxAttempts = VaultSnapshotCopyPolicy.MAX_VERIFY_ATTEMPTS
        )

        assertFalse(
            "a source that never stops changing must FAIL the copy (never ship a torn archive)",
            ok
        )
        assertEquals(
            "the policy must have retried up to the bounded attempt budget",
            VaultSnapshotCopyPolicy.MAX_VERIFY_ATTEMPTS.toLong(),
            copyCalls.toLong()
        )
        assertFalse(
            "the torn staging must be deleted — a partial snapshot never survives",
            dest.exists()
        )
    }

    @Test
    fun `the digest is deterministic so the before-after compare is meaningful`() {
        val dir = tmp.newFolder()
        val file = File(dir, "blob.bin")
        file.writeBytes(byteArrayOf(0x61, 0x62, 0x63))
        val a = VaultSnapshotCopyPolicy.sha256Digest(file)
        val b = VaultSnapshotCopyPolicy.sha256Digest(file)
        assertArrayEquals(a, b)
        assertEquals(
            "sha256(\"abc\") must be the well-known digest",
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            a.joinToString("") { "%02x".format(it) }
        )
    }

    // ---------------------------------------------------------------------
    // source-level wiring pins
    // ---------------------------------------------------------------------

    @Test
    fun `exportBackup checkpoints re-stamps then verified-copies before zipping the db entry`() {
        val exportRegion = sourceRegion(
            "services/ImportExportService.kt",
            "suspend fun exportBackup(",
            "private fun copyWithLimit"
        )

        val checkpointIdx = exportRegion.indexOf("repository.checkpointWal()")
        val rearmIdx = exportRegion.indexOf("repository.stampDatabaseChecksum(context)")
        val verifiedIdx = exportRegion.indexOf("VaultSnapshotCopyPolicy.checkpointThenCopy(dbFile, stagedDb)")
        val zipIdx = exportRegion.indexOf("zos.putNextEntry(ZipEntry(\"noteflow.sqlite\"))")

        assertTrue("the signature must take the repository (the discipline source)", exportRegion.contains("repository:"))
        assertTrue("exportBackup must run the FULL WAL checkpoint before any copy", checkpointIdx >= 0)
        assertTrue("the checkpoint must be followed by the HMAC re-stamp", rearmIdx > checkpointIdx)
        assertTrue("the copy must route through the verified snapshot policy", verifiedIdx > rearmIdx)
        assertTrue(
            "the verified snapshot must be ready BEFORE the db zip entry is written",
            zipIdx > verifiedIdx
        )

        // The DB zip entry reads the VERIFIED staged copy, never the raw live file.
        assertTrue("the db entry must zip the staged snapshot", exportRegion.contains("FileInputStream(stagedDb)"))
        assertFalse(
            "the pre-fix raw live-file copy must be gone from the db path",
            exportRegion.contains("FileInputStream(dbFile).use { fis -> fis.copyTo(zos) }")
        )
        // A failed verification aborts the backup loudly — never a silent torn archive.
        assertTrue(
            "a racing source must fail closed with a loud message",
            exportRegion.contains("kept changing during the snapshot copy")
        )
    }

    @Test
    fun `every DB-file copy producer routes through exportBackup with its repository`() {
        val home = sourceFile("ui/screens/HomeScreen.kt")
        val vm = sourceFile("ui/viewmodel/NoteflowViewModel.kt")
        val localSend = sourceFile("ui/components/LocalSendSendDialog.kt")

        // HomeScreen: both backup paths (plain + password) pass the repository so
        // exportBackup runs checkpoint-then-copy; the duplicated explicit
        // checkpoint/re-stamp is gone.
        assertTrue(
            "the plain backup must route through exportBackup with the repository",
            home.contains("ImportExportService.exportBackup(") && home.contains("repository = viewModel.repository")
        )
        assertFalse(
            "HomeScreen must not double up on the producer's checkpoint",
            home.contains("viewModel.repository.checkpointWal()")
        )
        assertFalse(
            "HomeScreen must not double up on the producer's HMAC re-stamp",
            home.contains("viewModel.repository.stampDatabaseChecksum(context)")
        )

        // WebDAV export: routes through exportBackup with its repository.
        val webdavExport = vm.substringAfter("fun exportEncryptedBackupToZip(")
            .substringBefore("fun restoreEncryptedBackupFromZip(", "END")
        assertTrue(
            "the WebDAV producer must route through exportBackup with the repository",
            webdavExport.contains("ImportExportService.exportBackup(") &&
                webdavExport.contains("repository = repository")
        )
        assertFalse(
            "the WebDAV producer must not double up on the producer's checkpoint",
            webdavExport.contains("repository.checkpointWal()")
        )

        // LocalSend VAULT_BACKUP: passes its repository (R2-B1D-03 — previously a
        // direct call with NO checkpoint, shipping a WAL-stale archive).
        val vaultBackupBranch = localSend.substringAfter("LocalSendPayload.VAULT_BACKUP ->", "END")
            .substringBefore("LocalSendPayload.OBSIDIAN_ZIP ->", "END")
        assertTrue(
            "LocalSend's vault-backup path must route through exportBackup with the repository",
            vaultBackupBranch.contains("ImportExportService.exportBackup(") &&
                vaultBackupBranch.contains("repository = viewModel.repository")
        )
    }

    @Test
    fun `the policy itself checkpoints before any read and re-digests to reject torn copies`() {
        val policy = sourceFile("services/VaultSnapshotCopyPolicy.kt")
        assertTrue(
            "the FULL checkpoint must be the first statement of the discipline",
            policy.contains("checkpoint?.checkpointFull()")
        )
        val checkpointIdx = policy.indexOf("checkpoint?.checkpointFull()")
        val digestIdx = policy.indexOf("sha256Digest(source)")
        assertTrue(
            "the checkpoint must precede every source read",
            checkpointIdx in 0 until digestIdx
        )
        assertTrue(
            "a torn copy must be detected by comparing the source before AND after",
            policy.contains("before.contentEquals(after)")
        )
        assertTrue(
            "the staging must be digested and matched against the source",
            policy.contains("before.contentEquals(sha256Digest(destination))")
        )
        assertTrue(
            "torn staging must be discarded and the race retried bounded",
            policy.contains("destination.delete()") && policy.contains("maxAttempts")
        )
    }

    // ---------- helpers ----------

    private fun sourceFile(relative: String): String {
        val file = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative")
        assertTrue("$relative must exist", file.isFile)
        return codeOnly(file.readText())
    }

    private fun sourceRegion(relative: String, after: String, before: String): String =
        sourceFile(relative).substringAfter(after).substringBefore(before, "END")

    /** Source with comment/KDoc lines removed so the pins never trip on their own docs. */
    private fun codeOnly(raw: String): String =
        raw.lineSequence()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("//") || trimmed.startsWith("*") ||
                    trimmed.startsWith("/*") || trimmed.startsWith("*/")
            }
            .joinToString("\n")

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
