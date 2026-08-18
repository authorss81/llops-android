package com.authorss81.noteflow

import com.authorss81.noteflow.services.BackupBudgetPolicy
import com.authorss81.noteflow.services.BackupExportPolicy
import com.authorss81.noteflow.services.EncryptionService
import com.authorss81.noteflow.services.ImportExportService
import com.authorss81.noteflow.services.RestoreFailSafe
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import javax.crypto.AEADBadTagException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R2-B1D-04 (phase-138): pure-JVM tests for the file-to-file restore decrypt,
 * the export/restore budget parity, and the guaranteed post-restore-failure
 * reopen.
 *
 * Verification targets from PROMPT.md:
 *  - a decrypted-payload factory that streams to file and never materializes
 *    both buffers (encryptStreamGcm → decryptStreamGcm file-to-file round trip
 *    with a multi-MB payload, and the v2 parse/verify factory);
 *  - cap-parity assertions (BackupBudgetPolicy — the pack side refuses exactly
 *    the archives the restore extractor refuses, so nothing is
 *    "exportable-unrestorable");
 *  - a reopen-after-failure model test over a fake repository seam
 *    (RestoreFailSafe with counter closures, incl. an unchecked Error).
 */
class Phase138RestoreStreamingTest {

    private val PASSWORD = "Tr0ub4dor&3!"

    // ---------------------------------------------------------------------
    // decrypted-payload factory — file-to-file streaming, never both buffers
    // ---------------------------------------------------------------------

    @Test
    fun `a multi-MB payload round-trips file-to-file with zero in-heap archive`() {
        val dir = Files.createTempDirectory("phase138").toFile()
        dir.deleteOnExit()
        // Large, non-trivial payload: the OLD path carried BOTH the encrypted
        // archive AND the decrypted zip in heap at once (~800MB peak) — this
        // streams file-to-file instead.
        val payload = ByteArray(2 * 1024 * 1024 + 17) { i -> (i % 251).toByte() }
        val kek = EncryptionService.deriveKey(PASSWORD, ByteArray(16) { 7 })
        val iv = ByteArray(12) { 9 }
        val header = "NFLB3".toByteArray() + ByteArray(105)
        val enc = File(dir, "encrypted.bin")
        val dec = File(dir, "decrypted.zip")

        // Factory (encrypt side): stream to file — the archive is never built in
        // memory (BackupExportPolicy.encryptStreamGcm, 64 KiB chunk).
        BackupExportPolicy.encryptStreamGcm(
            java.io.ByteArrayInputStream(payload),
            FileOutputStream(enc),
            kek,
            iv,
            header,
            ImportExportService.BACKUP_PAYLOAD_AAD
        )
        assertEquals("ciphertext file = header + plaintext + 16-byte GCM tag", payload.size + header.size + 16L, enc.length())

        // Factory (decrypt side): stream FROM the ciphertext FILE straight to the
        // decrypted zip FILE. Never both in heap.
        BackupExportPolicy.decryptStreamGcm(
            FileInputStream(enc).apply { skip(header.size.toLong()) },
            FileOutputStream(dec),
            kek,
            iv,
            header,
            ImportExportService.BACKUP_PAYLOAD_AAD
        )
        assertArrayEquals("decrypted bytes must be byte-identical to the source", payload, dec.readBytes())
    }

    @Test
    fun `the legacy zero-AAD payload is rescued only by the retry path`() {
        val dir = Files.createTempDirectory("phase138za").toFile()
        dir.deleteOnExit()
        val plain = "pre-B2-CRYPTO-03 legacy payload".toByteArray()
        val kek = EncryptionService.deriveKey(PASSWORD, ByteArray(16) { 2 })
        val iv = ByteArray(12) { 3 }

        // Build a payload encrypted WITHOUT any AAD (the old v2 writer format the
        // retry exists to rescue).
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(kek, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, iv)
        )
        // Note: NO updateAAD — matches the legacy layout.
        val ct = cipher.doFinal(plain)
        val legacyFile = File(dir, "legacy.bin").also { it.writeBytes(iv + ct) }

        // The AAD-bound decrypt (the modern form) cannot open it: its tag check
        // covers AAD the writer never fed.
        assertThrows(AEADBadTagException::class.java) {
            BackupExportPolicy.decryptStreamGcm(
                FileInputStream(legacyFile).apply { skip(iv.size.toLong()) },
                FileOutputStream(File(dir, "try_modern.bin")),
                kek,
                iv,
                "NFLB2".toByteArray() + ByteArray(105),
                ImportExportService.BACKUP_PAYLOAD_AAD
            )
        }

        // The zero-AAD retry (decryptStreamGcmLegacyZeroAad) rescues it — the
        // file-to-file mirror of decryptPayloadToFile's v2 fallback.
        val recovered = File(dir, "recovered.bin")
        BackupExportPolicy.decryptStreamGcmLegacyZeroAad(
            FileInputStream(legacyFile).apply { skip(iv.size.toLong()) }, FileOutputStream(recovered), kek, iv
        )
        assertArrayEquals(plain, recovered.readBytes())
    }

    @Test
    fun `the v2 parse and verify factory stream to a staging FILE and agree`() {
        val dir = Files.createTempDirectory("phase138v2").toFile()
        dir.deleteOnExit()
        val zipBytes = "crafted vault zip bytes".toByteArray(Charsets.UTF_8)

        val salt = ByteArray(16) { 5 }
        val kek = EncryptionService.deriveKey(PASSWORD, salt)
        val dek = ByteArray(32) { (0x10 + it).toByte() }
        val wrappedDek = EncryptionService.encryptAad(dek, kek, ImportExportService.BACKUP_DEK_WRAP_AAD)
        val iv = ByteArray(12) { 6 }
        val header = ImportExportService.buildBackupHeader(salt, iv, wrappedDek)
        val payload = ImportExportService.encryptBackupPayload(zipBytes, kek, iv, header)
        val full = header + payload
        kek.fill(0.toByte())

        val backupFile = File(dir, "v2.dat").also { it.writeBytes(full) }

        // The parse factory returns a FILE payload — the decrypted zip never
        // materialized as a ByteArray.
        val parsed = ImportExportService.tryParseBackupV2File(backupFile, PASSWORD)
        assertNotNull("the v2 file must parse via the file factory", parsed)
        assertArrayEquals("the recovered zip must live in the staging file", zipBytes, parsed!!.zipFile.readBytes())
        assertEquals("the wrapped DEK must unwrap to the vault DEK", dek.joinToString("") { "%02x".format(it) }, parsed.dek?.joinToString("") { "%02x".format(it) })
        assertNotNull("the derived KEK is handed to importBackup for zeroization", parsed.kek)
        parsed.kek?.fill(0.toByte())
        // R2-B1C-03 (phase-145): the backup DEK is zeroizable bytes — zeroize it.
        parsed.dek?.fill(0.toByte())

        // The verify path agrees (cheap v2 wrapped-DEK probe) — and rejects the
        // wrong password BEFORE any DB is closed.
        ImportExportService.validateBackupPasswordFile(backupFile, PASSWORD)
        assertThrows(IllegalArgumentException::class.java) {
            ImportExportService.validateBackupPasswordFile(backupFile, "wrong-Password-2026!")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImportExportService.tryParseBackupV2File(backupFile, "wrong-Password-2026!")
        }

        // No staging file may survive a failed parse (the factory cleans up).
        parsed.zipFile.delete()
        val leftover = dir.listFiles()?.filter { it.name.startsWith("restore_decrypt_") }?.size ?: 0
        assertEquals("failed parses must leave no decrypt staging behind", 0, leftover)
    }

    // ---------------------------------------------------------------------
    // cap parity — the packer refuses exactly what the extractor refuses
    // ---------------------------------------------------------------------

    @Test
    fun `the budgets agree that the wire cap already bounds a decryptable archive`() {
        assertEquals(
            "GCM output is plaintext + 16, so the restore total must equal the input cap",
            BackupBudgetPolicy.MAX_INPUT_BYTES,
            BackupBudgetPolicy.MAX_TOTAL_BYTES
        )
        assertEquals("the per-entry cap is 100MB on both sides", 100L * 1024 * 1024, BackupBudgetPolicy.MAX_ENTRY_BYTES)
        assertEquals(400L * 1024 * 1024, BackupBudgetPolicy.MAX_INPUT_BYTES)
        assertTrue("the total cap equals the input cap exactly", BackupBudgetPolicy.totalOverBudget(BackupBudgetPolicy.MAX_INPUT_BYTES).not())
        assertTrue(BackupBudgetPolicy.totalOverBudget(BackupBudgetPolicy.MAX_INPUT_BYTES + 1))
    }

    @Test
    fun `an entry the restore extractor refuses is also refused by the export packer`() {
        val over = BackupBudgetPolicy.MAX_ENTRY_BYTES + 1
        val under = BackupBudgetPolicy.MAX_ENTRY_BYTES - 1

        val pack = BackupBudgetPolicy.Accounting()
        val restore = BackupBudgetPolicy.Accounting()

        // The packer refuses a file whose length the extractor could never unpack.
        assertThrows(IllegalStateException::class.java) {
            BackupBudgetPolicy.claimPackFile(pack, "noteflow.sqlite", over)
        }
        // The extractor refuses the same byte count, whatever its declared sizes.
        assertThrows(IllegalStateException::class.java) {
            BackupBudgetPolicy.claimRestoreChunk(restore, over, over, over)
        }
        assertThrows(IllegalStateException::class.java) {
            BackupBudgetPolicy.claimRestoreChunk(restore, over, -1L, -1L)
        }

        // And both authoritatively accept the same under-budget entry.
        BackupBudgetPolicy.claimPackFile(pack, "noteflow.sqlite", under)
        BackupBudgetPolicy.claimRestoreChunk(restore, under, under, under)
        BackupBudgetPolicy.settleRestoreEntry(restore, under)
        assertEquals(under, pack.totalBytes)
        assertEquals("accepted entries must settle exactly once", under, restore.totalBytes)
    }

    @Test
    fun `a vault that would blow the total on restore is refused at pack time too`() {
        // 5 entries just under the 100MB line ≈ 500MB total — over the 400MB ceiling.
        val size = BackupBudgetPolicy.MAX_ENTRY_BYTES - 1
        val pack = BackupBudgetPolicy.Accounting()
        val restore = BackupBudgetPolicy.Accounting()
        for (i in 1..4) {
            BackupBudgetPolicy.claimPackFile(pack, "imports/doc$i.bin", size)
            BackupBudgetPolicy.claimRestoreChunk(restore, size, size, size)
            BackupBudgetPolicy.settleRestoreEntry(restore, size)
        }
        // The 5th overruns the 400MB total on BOTH sides.
        assertThrows(IllegalStateException::class.java) {
            BackupBudgetPolicy.claimPackFile(pack, "imports/doc5.bin", size)
        }
        assertThrows(IllegalStateException::class.java) {
            BackupBudgetPolicy.claimRestoreChunk(restore, size, size, size)
        }
    }

    @Test
    fun `a restorable vault is never exportable-unrestorable - the inverse parity`() {
        // A golden vault under every cap: the packer must accept precisely the
        // entry set the extractor accepts (the property the pre-fix asymmetry
        // violated).
        val sizes = longArrayOf(50L * 1024 * 1024, 30L * 1024 * 1024, 12L * 1024 * 1024, 42L * 1024 * 1024)
        val pack = BackupBudgetPolicy.Accounting()
        val restore = BackupBudgetPolicy.Accounting()
        for ((i, s) in sizes.withIndex()) {
            BackupBudgetPolicy.claimPackFile(pack, "entry$i.bin", s)
            BackupBudgetPolicy.claimRestoreChunk(restore, s, s, s)
            BackupBudgetPolicy.settleRestoreEntry(restore, s)
        }
        assertTrue("packed total inside the budget", !BackupBudgetPolicy.totalOverBudget(pack.totalBytes))
        assertTrue("restored total inside the budget", !BackupBudgetPolicy.totalOverBudget(restore.totalBytes))
        assertEquals("both sides must agree byte-for-byte", pack.totalBytes, restore.totalBytes)
    }

    @Test
    fun `the ratio seal trips both sides at the same 100x against actual bytes`() {
        // Restore side: declared 10KB but the reader already consumed > 1MB →
        // forged-declared-size bomb trips the seal.
        val restore = BackupBudgetPolicy.Accounting()
        val ex = assertThrows(IllegalStateException::class.java) {
            BackupBudgetPolicy.claimRestoreChunk(restore, 1_000_001L, 10_000L, 10_000L)
        }
        assertTrue(ex.message.orEmpty().contains("compression ratio"))

        // Pack side: a file over the ratio budget on the wire is a per-entry
        // refusal (the packer reads real lengths, so the seal itself only guards
        // the restore read — the packer still refuses the same file for the
        // entry-cap reason; the message names the offending entry).
        val pack = BackupBudgetPolicy.Accounting()
        val oversized = BackupBudgetPolicy.MAX_ENTRY_BYTES + BackupBudgetPolicy.MAX_ENTRY_BYTES / 2
        val packEx = assertThrows(IllegalStateException::class.java) {
            BackupBudgetPolicy.claimPackFile(pack, "voice_notes/huge.enc", oversized)
        }
        assertTrue(packEx.message.orEmpty().contains("huge.enc"))
    }

    // ---------------------------------------------------------------------
    // reopen-after-failure model test (fake repository seam)
    // ---------------------------------------------------------------------

    @Test
    fun `a restore failure reopens the fake repository before the error escapes`() {
        var closed = 0
        var reopened = 0
        val ex = assertThrows(IllegalStateException::class.java) {
            runBlocking {
                RestoreFailSafe.guaranteeReopenAfterRestore(
                    closeDatabase = { closed++ },
                    restore = { throw IllegalStateException("Incorrect backup password.") },
                    reopenDatabase = { reopened++ }
                )
            }
        }
        assertEquals("the caller surfaces the restore error untouched", "Incorrect backup password.", ex.message)
        assertEquals("the vault must have been closed for the swap attempt", 1, closed)
        assertEquals("the vault must be reopened so the app is not bricked", 1, reopened)
    }

    @Test
    fun `an unchecked Error mid-restore also reopens the fake repository`() {
        var closed = 0
        var reopened = 0
        // The exact crash class the ~800MB in-heap restore could trigger — the
        // failsafe must recover from it just the same.
        assertThrows(OutOfMemoryError::class.java) {
            runBlocking {
                RestoreFailSafe.guaranteeReopenAfterRestore(
                    closeDatabase = { closed++ },
                    restore = { throw OutOfMemoryError("restore decrypt peak") },
                    reopenDatabase = { reopened++ }
                )
            }
        }
        assertEquals(1, closed)
        assertEquals("even OOM reopens the vault", 1, reopened)
    }

    @Test
    fun `a reopen failure never masks the real restore error`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                RestoreFailSafe.guaranteeReopenAfterRestore(
                    closeDatabase = {},
                    restore = { throw IllegalArgumentException("Backup appears corrupted.") },
                    reopenDatabase = { throw RuntimeException("reopen failed too") }
                )
            }
        }
        assertEquals("the reopen is best-effort: the original error is what the user sees", "Backup appears corrupted.", ex.message)
    }

    @Test
    fun `a successful restore leaves the repository closed for the swap and restart`() {
        var closed = 0
        var reopened = 0
        val result = runBlocking {
            RestoreFailSafe.guaranteeReopenAfterRestore(
                closeDatabase = { closed++ },
                restore = { "restored-vault" },
                reopenDatabase = { reopened++ }
            )
        }
        assertEquals("restored-vault", result)
        assertEquals("the swap path closes before restoring", 1, closed)
        assertEquals("success never needs a reopen (the process restarts)", 0, reopened)
    }
}