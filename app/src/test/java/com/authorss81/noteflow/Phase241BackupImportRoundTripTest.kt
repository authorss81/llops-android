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

        // The vault archive entries written into the inner zip. These single
        // sources keep the written bytes and the budget-pin sizes in lock-step
        // (never hand-sprayed lengths).
        val ENTRY_DB_NAME = "noteflow.sqlite"
        val ENTRY_DB_BYTES = "restored-vault-db-bytes".toByteArray(Charsets.UTF_8)
        val ENTRY_IMPORTS_NAME = "imports/photo.jpg"
        val ENTRY_IMPORTS_BYTES = "imported-image".toByteArray(Charsets.UTF_8)
        val ENTRY_VOICE_NAME = "voice_notes/note1.m4a.enc"
        val ENTRY_VOICE_BYTES = "encrypted-voice-blob".toByteArray(Charsets.UTF_8)
    }

    private fun newTempDir(tag: String): File {
        val dir = java.nio.file.Files.createTempDirectory("phase241_${tag}_").toFile()
        dir.deleteOnExit()
        return dir
    }

    /** Returns the actual (uncompressed) source length of each vault entry just
     *  packed, so the restore-side budget gate pin uses the real sizes. */
    private fun entrySizes(): LinkedHashMap<String, Long> = linkedMapOf(
        ENTRY_DB_NAME to ENTRY_DB_BYTES.size.toLong(),
        ENTRY_IMPORTS_NAME to ENTRY_IMPORTS_BYTES.size.toLong(),
        ENTRY_VOICE_NAME to ENTRY_VOICE_BYTES.size.toLong()
    )

    /** Mirrors [ImportExportService.exportBackup]'s v3 (NFLB3) write exactly:
     *  split wrap key, real DEK-wrap AAD, and the production STREAMED
     *  `SequenceInputStream([part2], zip)` + [BackupExportPolicy.encryptStreamGcm]. */
    private fun writeProductionV3Backup(backupFile: File) {
        val salt = EncryptionService.generateSalt()
        val kek = EncryptionService.deriveKey(PASSWORD, salt)
        // Build the inner zip the SAME way exportBackup does (zipVaultEntriesToStream).
        val stagingZip = File(newTempDir("staging"), "staging.zip")
        BackupExportPolicy.zipVaultEntriesToStream(FileOutputStream(stagingZip)) { zos ->
            zos.putNextEntry(ZipEntry(ENTRY_DB_NAME))
            zos.write(ENTRY_DB_BYTES)
            zos.closeEntry()
            zos.putNextEntry(ZipEntry(ENTRY_IMPORTS_NAME))
            zos.write(ENTRY_IMPORTS_BYTES)
            zos.closeEntry()
            zos.putNextEntry(ZipEntry(ENTRY_VOICE_NAME))
            zos.write(ENTRY_VOICE_BYTES)
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
            listOf(ENTRY_DB_NAME, ENTRY_IMPORTS_NAME, ENTRY_VOICE_NAME),
            names
        )
        assertArrayEquals("the wrapped backup DEK unwraps to the original", DEK, p.dek)
        assertNotNull("the derived KEK is handed to importBackup for zeroization", p.kek)

        // The produced archive must pass the restore-side budget gate (exportable ==
        // restorable), using the REAL source sizes of the entries that were packed.
        val packAccounting = BackupBudgetPolicy.Accounting()
        entrySizes().forEach { (name, size) -> BackupBudgetPolicy.claimPackFile(packAccounting, name, size) }
        // No exception thrown = within every cap (exact lengths, not spray values).

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
