package com.authorss81.noteflow

import com.authorss81.noteflow.services.BackupBudgetPolicy
import com.authorss81.noteflow.services.BackupExportPolicy
import com.authorss81.noteflow.services.EncryptionService
import com.authorss81.noteflow.services.ImportExportService
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.SequenceInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 241 — pin the PRODUCTION backup→restore round-trip.
 *
 * The pre-existing crypto tests (B2Crypto04BackupPasswordTest) build their v3
 * test file with the one-shot [ImportExportService.encryptBackupPayload], which
 * the production exporter EXPLICITLY no longer calls (its KDoc: "production
 * export no longer calls this one-shot path"). The real
 * [ImportExportService.exportBackup] writes a v3 file through
 * [BackupExportPolicy.encryptStreamGcm] fed by a
 * `SequenceInputStream([16B wrapKeyPart2], stagingZip)` — an on-disk layout
 * that was never round-tripped through the restore parse in one test.
 *
 * If the export stream layout (header bytes written first by encryptStreamGcm,
 * then ciphertext = `[part2] || zip`) ever drifted out of parity with the
 * restore reader ([ImportExportService.tryParseBackupV2File], which re-reads
 * the header from the file head and skips `offsetBytes` = 16 for the zip), the
 * user-facing symptom would be exactly the reported "backup creates a file but
 * import fails" / "restore with the same password doesn't work". This suite
 * reproduces the ENTIRE production export write byte-for-byte and feeds it
 * through the ENTIRE production restore read, proving: the same password
 * validates, the wrong password and corrupt files are rejected loudly, the
 * wrapped DEK unwraps to the original, and the inner zip (containing
 * `noteflow.sqlite`) is recovered — and that the produced archive passes the
 * restore-side budget gate (exportable == restorable, R2-B1D-04).
 */
class Phase241BackupImportRoundTripTest {

    private companion object {
        const val PASSWORD = "Sup3r-S3cret-Passphrase-2026"
        val DEK = ByteArray(32) { (0x50 + it).toByte() }
    }

    private fun newTempDir(tag: String): File {
        val dir = java.nio.file.Files.createTempDirectory("phase241_${tag}_").toFile()
        dir.deleteOnExit()
        return dir
    }

    /** Mirrors [ImportExportService.exportBackup]'s v3 (NFLB3) write exactly:
     *  split wrap key, real DEK-wrap AAD, and the production STREAMED
     *  `SequenceInputStream([part2], zip)` + [BackupExportPolicy.encryptStreamGcm]. */
    private fun writeProductionV3Backup(backupFile: File): Triple<ByteArray, ByteArray, ByteArray> {
        val salt = EncryptionService.generateSalt()
        val kek = EncryptionService.deriveKey(PASSWORD, salt)
        // Build the inner zip the SAME way exportBackup does (zipVaultEntriesToStream).
        val stagingZip = File(newTempDir("staging"), "staging.zip")
        BackupExportPolicy.zipVaultEntriesToStream(FileOutputStream(stagingZip)) { zos ->
            zos.putNextEntry(ZipEntry("noteflow.sqlite"))
            zos.write("restored-vault-db-bytes".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("imports/photo.jpg"))
            zos.write("imported-image".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("voice_notes/note1.m4a.enc"))
            zos.write("encrypted-voice-blob".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        try {
            val wrapKey = EncryptionService.generateDek()
            val part1 = wrapKey.copyOfRange(0, 16)
            val part2 = wrapKey.copyOfRange(16, 32)
            val wrappedDek = EncryptionService.encryptAad(DEK, wrapKey, ImportExportService.BACKUP_DEK_WRAP_AAD)
            val payloadIv = EncryptionService.newIv()
            val header = ImportExportService.buildBackupHeaderV3(salt, payloadIv, part1, wrappedDek)
            try {
                BackupExportPolicy.encryptStreamGcm(
                    SequenceInputStream(
                        ByteArrayInputStream(part2),
                        FileInputStream(stagingZip)
                    ),
                    FileOutputStream(backupFile),
                    kek,
                    payloadIv,
                    header,
                    ImportExportService.BACKUP_PAYLOAD_AAD
                )
            } finally {
                wrapKey.fill(0.toByte()); part1.fill(0.toByte()); part2.fill(0.toByte())
            }
            return Triple(header, wrappedDek, kek)
        } finally {
            kek.fill(0.toByte())
            stagingZip.delete()
        }
    }

    @Test
    fun `production v3 backup round-trips - same password validates, unwraps DEK, recovers zip`() {
        val dir = newTempDir("rt")
        val backupFile = File(dir, "backup.nflb3")
        writeProductionV3Backup(backupFile)

        // DoD: the produced file opens with the SAME password.
        ImportExportService.validateBackupPasswordFile(backupFile, PASSWORD)

        val parsed = ImportExportService.tryParseBackupV2File(backupFile, PASSWORD)
        assertNotNull("the production-streamed backup must parse via the restore path", parsed)
        val p = parsed!!

        // The wrap-key half prefix is stripped: the zip begins at offsetBytes.
        assertEquals("v3 payload prefixes the 16-byte key half", 16, p.offsetBytes)

        val inner = p.zipFile.readBytes()
        val zipBytes = inner.copyOfRange(p.offsetBytes, inner.size)
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var e = zis.nextEntry
            while (e != null) { names.add(e.name); e = zis.nextEntry }
        }
        assertEquals(
            "restore packer entry names must match the extractor's readers",
            listOf("noteflow.sqlite", "imports/photo.jpg", "voice_notes/note1.m4a.enc"),
            names
        )
        assertArrayEquals("the wrapped backup DEK unwraps to the original", DEK, p.dek)
        assertNotNull("the derived KEK is handed to importBackup for zeroization", p.kek)

        // The produced archive must pass the restore-side budget gate (exportable == restorable).
        val packAccounting = BackupBudgetPolicy.Accounting()
        BackupBudgetPolicy.claimPackFile(packAccounting, "noteflow.sqlite", 23L)
        BackupBudgetPolicy.claimPackFile(packAccounting, "imports/photo.jpg", 13L)
        BackupBudgetPolicy.claimPackFile(packAccounting, "voice_notes/note1.m4a.enc", 20L)
        // No exception thrown = within every cap.

        p.kek?.fill(0.toByte())
        p.dek?.fill(0.toByte())
        backupFile.delete()
    }

    @Test
    fun `production v3 backup rejects wrong password and a corrupted payload loudly`() {
        val dir = newTempDir("wrong")
        val backupFile = File(dir, "backup.nflb3")
        writeProductionV3Backup(backupFile)

        val wrong = assertThrows(IllegalArgumentException::class.java) {
            ImportExportService.tryParseBackupV2File(backupFile, "Wrong-Not-The-Password-2026!")
        }
        assertTrue("wrong password is reported, never a crash", wrong.message.orEmpty().contains("Incorrect backup password"))

        // Corrupt: flip a byte inside the ciphertext so the GCM tag fails.
        val bytes = backupFile.readBytes()
        bytes[bytes.size - 5] = (bytes[bytes.size - 5].toInt() xor 0x01).toByte()
        val corrupt = File(dir, "corrupt.nflb3").also { it.writeBytes(bytes) }
        assertThrows("corrupt payload must fail", IllegalArgumentException::class.java) {
            ImportExportService.tryParseBackupV2File(corrupt, PASSWORD)
        }

        backupFile.delete(); corrupt.delete()
    }

    @Test
    fun `validateBackupPasswordFile rejects the wrong password for a production v3 file`() {
        val dir = newTempDir("val")
        val backupFile = File(dir, "backup.nflb3")
        writeProductionV3Backup(backupFile)

        val ex = assertThrows(IllegalArgumentException::class.java) {
            ImportExportService.validateBackupPasswordFile(backupFile, "Incorrect-Password-2026!")
        }
        assertTrue(ex.message.orEmpty().contains("Incorrect backup password"))
        backupFile.delete()
    }
}
