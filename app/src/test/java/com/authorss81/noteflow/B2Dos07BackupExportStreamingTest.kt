package com.authorss81.noteflow

import com.authorss81.noteflow.services.BackupExportPolicy
import com.authorss81.noteflow.services.EncryptionService
import com.authorss81.noteflow.services.ImportExportService
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * B2-DOS-07 (phase-83): backup export can no longer materialize the ENTIRE vault
 * (SQLCipher DB copy + every imports file + every encrypted voice blob) as one
 * in-heap byte array, then make a SECOND full-size copy for AES-GCM `doFinal`
 * (or a ~1.37x Base64 expansion) — a few-hundred-MB vault OOM'd the IO thread on
 * every "Create backup", making the backup feature itself an interactive DoS.
 *
 * Pre-fix (`ImportExportService.exportBackup`):
 *  - `ByteArrayOutputStream` accumulated the zip, then `baos.toByteArray()`
 *    materialized the whole archive in heap;
 *  - the v2 password path fed that array to `cipher.doFinal(...)` (a second
 *    full-size copy) and wrote header+ciphertext;
 *  - the device-keyed path Base64-encoded it (`EncryptionService.encrypt`) — a
 *    further ~1.37x amplification — and wrote the string.
 *
 * After (`BackupExportPolicy.kt` + the rewired `exportBackup`):
 *  - the zip streams into a transient app-private staging FILE via
 *    `ZipOutputStream(FileOutputStream)` — never a heap archive;
 *  - the v2 payload is AES-GCM encrypted file-to-file with a bounded
 *    [BackupExportPolicy.ENCRYPT_CHUNK_BYTES] loop (header, then each chunk's
 *    `Cipher.update` output, then `doFinal` tail+tag) — byte-identical to the
 *    one-shot reference by the JCE stream-mode contract;
 *  - the device-keyed format is streamed through a `java.util.Base64.Encoder` —
 *    same on-disk wire format, no full-array expansion.
 *
 * Pure JVM: byte-level equivalence vs the one-shot reference, decrypt round-trips
 * through the REAL restore decryptors, chunk-budget invariants (reads AND writes
 * never exceed one chunk + tag slack), an incremental-valid-zip test, an
 * end-to-end large synthetic-vault round-trip, and source-level wiring pins.
 */
class B2Dos07BackupExportStreamingTest {

    companion object {
        private const val PASSWORD = "BackupPassword123!"
        private val SALT = ByteArray(16) { it.toByte() }
        private val DEK = ByteArray(32) { (0x10 + it).toByte() }
        private lateinit var KEK: ByteArray

        @BeforeClass
        @JvmStatic
        fun setUp() {
            KEK = EncryptionService.deriveKey(PASSWORD, SALT)
        }
    }

    private val mainSourceRoot by lazy { File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow") }

    // ---------------------------------------------------------------------
    // v2 password path: streamed output == one-shot doFinal output
    // ---------------------------------------------------------------------

    @Test
    fun `streamed v2 payload is byte-identical to the one-shot reference and decrypts`() {
        val plaintext = deterministicBytes(6 * 1024 * 1024, seed = 7)
        val plainFile = File.createTempFile("b2dos07-v2-plain", ".bin").apply { writeBytes(plaintext) }
        try {
            val iv = ByteArray(12) { (0x40 + it).toByte() }
            val wrap = EncryptionService.encryptAad(DEK, KEK, ImportExportService.BACKUP_DEK_WRAP_AAD)
            val header = ImportExportService.buildBackupHeader(SALT, iv, wrap)

            val oneShot = ImportExportService.encryptBackupPayload(plaintext, KEK, iv, header)

            val dest = File.createTempFile("b2dos07-v2-streamed", ".nfb")
            try {
                BackupExportPolicy.encryptStreamGcm(
                    FileInputStream(plainFile),
                    FileOutputStream(dest),
                    KEK,
                    iv,
                    header,
                    ImportExportService.BACKUP_PAYLOAD_AAD
                )
                val streamed = dest.readBytes()
                // on-disk layout = header verbatim, then ciphertext+tag.
                assertEquals(header.size + oneShot.size, streamed.size)
                assertArrayEquals(header, streamed.copyOfRange(0, header.size))
                // streamed ciphertext+tag == one-shot doFinal (JCE stream-mode
                // contract: chunked update+doFinal concatenates to one doFinal).
                assertArrayEquals(oneShot, streamed.copyOfRange(header.size, streamed.size))
            } finally {
                dest.delete()
            }
            // and the REAL restore decryptor reads the payload back.
            assertArrayEquals(
                plaintext,
                ImportExportService.decryptBackupPayload(oneShot, KEK, iv, header)
            )
        } finally {
            plainFile.delete()
        }
    }

    // ---------------------------------------------------------------------
    // device-keyed legacy path: streamed output decrypts via the real restore
    // ---------------------------------------------------------------------

    @Test
    fun `streamed device-keyed backup decrypts through the real restore decryptor`() {
        val plain = deterministicBytes(1024 * 1024, seed = 11)
        val plainFile = File.createTempFile("b2dos07-legacy-plain", ".bin").apply { writeBytes(plain) }
        val key = ByteArray(32) { (0x20 + it).toByte() }
        val dest = File.createTempFile("b2dos07-legacy-streamed", ".noteflow")
        try {
            BackupExportPolicy.encryptStreamDeviceKeyedBase64(
                FileInputStream(plainFile),
                FileOutputStream(dest),
                key
            )
            val fileText = String(dest.readBytes(), Charsets.UTF_8)
            // The file is Base64 (ASCII); the first decoded byte is the version
            // marker — the exact legacy wire format EncryptionService.encrypt wrote.
            val decoded = java.util.Base64.getDecoder().decode(fileText)
            assertEquals(EncryptionService.PAYLOAD_VERSION.toInt(), decoded[0].toInt())
            // Restore path: importBackup does String(bytes, UTF_8) ->
            // EncryptionService.decrypt(str, key) -> the zip bytes.
            assertArrayEquals(plain, EncryptionService.decrypt(fileText, key))
        } finally {
            plainFile.delete()
            dest.delete()
        }
    }

    // ---------------------------------------------------------------------
    // memory bound: reads AND writes never exceed one chunk (+ tag slack)
    // ---------------------------------------------------------------------

    @Test
    fun `streaming GCM never reads or writes more than one chunk at a time`() {
        val plaintext = deterministicBytes(6 * 1024 * 1024, seed = 13)
        val plainFile = File.createTempFile("b2dos07-chunked-plain", ".bin").apply { writeBytes(plaintext) }
        try {
            val iv = ByteArray(12) { (0x60 + it).toByte() }
            val wrap = EncryptionService.encryptAad(DEK, KEK, ImportExportService.BACKUP_DEK_WRAP_AAD)
            val header = ImportExportService.buildBackupHeader(SALT, iv, wrap)

            var maxRequestedReadLen = 0
            var readCount = 0
            val monitoredPlain = object : FilterInputStream(FileInputStream(plainFile)) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    maxRequestedReadLen = maxOf(maxRequestedReadLen, len)
                    readCount++
                    return super.read(b, off, len)
                }
            }

            var maxOutputWrite = 0L
            var outputWriteCount = 0
            val sink = object : OutputStream() {
                val backing = java.io.ByteArrayOutputStream()

                override fun write(b: Int) {
                    backing.write(b)
                    record(1L)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    backing.write(b, off, len)
                    record(len.toLong())
                }

                private fun record(n: Long) {
                    outputWriteCount++
                    maxOutputWrite = maxOf(maxOutputWrite, n)
                }
            }

            BackupExportPolicy.encryptStreamGcm(monitoredPlain, sink, KEK, iv, header, ImportExportService.BACKUP_PAYLOAD_AAD)

            // 6 MiB of input must be pulled in ~96 bounded reads, never one full file.
            assertTrue(
                "the whole input must be read through many bounded reads",
                readCount >= 6 * 1024 * 1024 / BackupExportPolicy.ENCRYPT_CHUNK_BYTES
            )
            assertTrue(
                "a read request must never exceed the chunk budget",
                maxRequestedReadLen <= BackupExportPolicy.ENCRYPT_CHUNK_BYTES
            )
            // GPU: on the JDK GCM provider, update() streams each chunk's ciphertext
            // out, so a single write is at most one chunk (+ the 16-byte tag tail).
            assertTrue(
                "a single output write must never exceed one chunk plus tag slack",
                maxOutputWrite <= BackupExportPolicy.ENCRYPT_CHUNK_BYTES + 16L
            )
            assertTrue(
                "the ciphertext must be emitted over many bounded writes, not one array",
                outputWriteCount >= readCount
            )
            val written = sink.backing.toByteArray()
            val headerSize = header.size
            assertEquals(headerSize + plaintext.size + 16, written.size)
            assertArrayEquals(header, written.copyOfRange(0, headerSize))
            assertArrayEquals(plaintext, ImportExportService.decryptBackupPayload(written.copyOfRange(headerSize, written.size), KEK, iv, header))
        } finally {
            plainFile.delete()
        }
    }

    // ---------------------------------------------------------------------
    // the zip stage: incremental, valid, and byte-complete
    // ---------------------------------------------------------------------

    @Test
    fun `vault zip streams incrementally to the staging file and stays a valid archive`() {
        val vaultDir = File(System.getProperty("java.io.tmpdir"), "b2dos07-vault-" + UUID.randomUUID()).apply { mkdirs() }
        val expected = linkedMapOf<String, File>()
        try {
            val db = newVaultFile(vaultDir, "noteflow.sqlite", deterministicBytes(2 * 1024 * 1024, 3))
            val deep = newVaultFile(vaultDir, "imports/sub/a.md", deterministicBytes(700 * 1024, 5))
            val top = newVaultFile(vaultDir, "imports/b.txt", deterministicBytes(2048, 11))
            val voice = newVaultFile(vaultDir, "voice_notes/v1.enc", deterministicBytes(50 * 1024, 17))
            expected["noteflow.sqlite"] = db
            expected["imports/sub/a.md"] = deep
            expected["imports/b.txt"] = top
            expected["voice_notes/v1.enc"] = voice

            val destFile = File.createTempFile("b2dos07-staging", ".zip")
            try {
                var writeCount = 0
                var maxWrite = 0L
                var totalWritten = 0L
                val rawOut = FileOutputStream(destFile)
                val countingOut = object : OutputStream() {
                    override fun write(b: Int) {
                        rawOut.write(b)
                        record(1L)
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        rawOut.write(b, off, len)
                        record(len.toLong())
                    }

                    private fun record(n: Long) {
                        writeCount++
                        maxWrite = maxOf(maxWrite, n)
                        totalWritten += n
                    }

                    override fun close() {
                        rawOut.close()
                    }
                }

                BackupExportPolicy.zipVaultEntriesToStream(countingOut) { zos ->
                    expected.forEach { (name, file) ->
                        zos.putNextEntry(ZipEntry(name))
                        FileInputStream(file).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }

                // The archive is WRITTEN incrementally: many small writes, never one
                // whole-archive array. (ZipOutputStream compresses each entry-chunk.)
                assertTrue("the archive must be written over many bounded writes", writeCount > 8)
                assertTrue("no single write may be the whole archive", maxWrite < destFile.length())
                assertEquals(destFile.length(), totalWritten)

                // And it is a VALID zip containing every vault byte.
                val entries = linkedMapOf<String, ByteArray>()
                ZipInputStream(FileInputStream(destFile)).use { zis ->
                    var e = zis.nextEntry
                    while (e != null) {
                        entries[e.name] = zis.readBytes()
                        e = zis.nextEntry
                    }
                }
                assertEquals(expected.keys, entries.keys)
                expected.forEach { (name, file) ->
                    assertArrayEquals("entry $name must round-trip its bytes", file.readBytes(), entries[name]!!)
                }
            } finally {
                destFile.delete()
            }
        } finally {
            vaultDir.deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------
    // end-to-end: a large synthetic vault completes the streamed export path
    // ---------------------------------------------------------------------

    @Test
    fun `a large synthetic vault rounds-trips through the streamed export path`() {
        val vaultDir = File(System.getProperty("java.io.tmpdir"), "b2dos07-e2e-" + UUID.randomUUID()).apply { mkdirs() }
        val stagingZip = File.createTempFile("b2dos07-e2e-staging", ".zip")
        val backupFile = File.createTempFile("b2dos07-e2e-backup", ".nfb")
        try {
            // ~6.7 MiB synthetic vault across the three payload sources.
            val vaultFiles = mapOf(
                "noteflow.sqlite" to newVaultFile(vaultDir, "noteflow.sqlite", deterministicBytes(4 * 1024 * 1024, 23)),
                "imports/a.md" to newVaultFile(vaultDir, "imports/a.md", deterministicBytes(1500 * 1024, 29)),
                "imports/nested/b.bin" to newVaultFile(vaultDir, "imports/nested/b.bin", deterministicBytes(900 * 1024, 31)),
                "voice_notes/v7.enc" to newVaultFile(vaultDir, "voice_notes/v7.enc", deterministicBytes(300 * 1024, 37))
            )
            BackupExportPolicy.zipVaultEntriesToStream(FileOutputStream(stagingZip)) { zos ->
                vaultFiles.forEach { (name, file) ->
                    zos.putNextEntry(ZipEntry(name))
                    FileInputStream(file).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }

            val iv = ByteArray(12) { (0x70 + it).toByte() }
            val wrap = EncryptionService.encryptAad(DEK, KEK, ImportExportService.BACKUP_DEK_WRAP_AAD)
            val header = ImportExportService.buildBackupHeader(SALT, iv, wrap)
            BackupExportPolicy.encryptStreamGcm(
                FileInputStream(stagingZip),
                FileOutputStream(backupFile),
                KEK,
                iv,
                header,
                ImportExportService.BACKUP_PAYLOAD_AAD
            )

            // The restore decryptor reads the STREAMED archive back (production
            // restore path decryptBackupPayload over the payload slice).
            val bytes = backupFile.readBytes()
            val headerSize: Int = header.size
            val payload = bytes.copyOfRange(headerSize, bytes.size)
            val decryptedZip = ImportExportService.decryptBackupPayload(payload, KEK, iv, header)
            // The streamed ciphertext decrypts back to EXACTLY the staged archive.
            assertArrayEquals(stagingZip.readBytes(), decryptedZip)

            val restored = linkedMapOf<String, ByteArray>()
            ZipInputStream(java.io.ByteArrayInputStream(decryptedZip)).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    restored[e.name] = zis.readBytes()
                    e = zis.nextEntry
                }
            }
            assertEquals(vaultFiles.keys, restored.keys)
            vaultFiles.forEach { (name, file) ->
                assertArrayEquals("restored $name must match the vault file", file.readBytes(), restored[name]!!)
            }
        } finally {
            vaultDir.deleteRecursively()
            stagingZip.delete()
            backupFile.delete()
        }
    }

    // ---------------------------------------------------------------------
    // source pins: the whole-archive-in-heap shapes are gone from the code
    // ---------------------------------------------------------------------

    @Test
    fun `exportBackup streams the archive to a staging file instead of a heap array`() {
        val source = codeOnly(mainSourceRootFile("services/ImportExportService.kt").readText())
        assertFalse("the whole-archive heap byte array must be gone", source.contains("val zipData"))
        assertFalse("the baos archive materialization must be gone", source.contains("baos.toByteArray()"))
        assertFalse("the v2 full-copy write must be gone", source.contains("fos.write(cipherText)"))
        assertFalse("the device-keyed Base64 full-copy must be gone", source.contains("val encryptedBase64"))
        assertFalse("no encrypt(zipData,...) call may survive", source.contains("encrypt(zipData"))
        assertTrue("the zip must stream into an app-private staging file", source.contains("File(context.cacheDir, BackupExportPolicy.stagingFileName(backupName))"))
        assertTrue("v2 must encrypt streamed through the policy", source.contains("BackupExportPolicy.encryptStreamGcm("))
        assertTrue("device-keyed must encrypt streamed through the policy", source.contains("BackupExportPolicy.encryptStreamDeviceKeyedBase64("))
        assertTrue("the staging file must be deleted after encryption", source.contains("stagingZip.delete()"))
        assertTrue("the output name must still come from the filename policy", source.contains("File(context.cacheDir, backupName)"))
    }

    @Test
    fun `BackupExportPolicy holds only bounded chunk buffers, never the archive`() {
        val source = codeOnly(mainSourceRootFile("services/BackupExportPolicy.kt").readText())
        assertEquals("the chunk budget is 64 KiB", 64 * 1024, BackupExportPolicy.ENCRYPT_CHUNK_BYTES)
        assertEquals("each streamer draws exactly ONE fixed chunk buffer", 2, Regex("ByteArray\\(ENCRYPT_CHUNK_BYTES\\)").findAll(source).count())
        assertFalse("no whole-file read may survive in the streamers", source.contains("readBytes("))
        assertFalse("no whole-archive byte array may be materialized", source.contains(".toByteArray()"))
        assertFalse("no in-heap zip accumulator may survive", source.contains("ByteArrayOutputStream"))
        assertTrue("the device-keyed path streams through a Base64 encoder", source.contains("Base64.getEncoder().wrap("))
        assertTrue("the device-keyed path uses the app FIELD_AAD domain", source.contains("EncryptionService.FIELD_AAD"))
        assertTrue("the device-keyed path writes the versioned wire format", source.contains("EncryptionService.PAYLOAD_VERSION"))
        assertTrue("the device-keyed path uses the 12-byte IV length", source.contains("EncryptionService.GCM_IV_LENGTH"))
    }

    @Test
    fun `staging file never becomes a public download name`() {
        // The staging zip is cacheDir-only with a suffix; only the ENCRYPTED
        // output may carry the policy download name (B2-CRYPTO-06 naming).
        assertEquals("noteflow_backup_x.noteflow.zip-staging", BackupExportPolicy.stagingFileName("noteflow_backup_x.noteflow"))
        assertFalse(BackupExportPolicy.stagingFileName("n.nfb").contains(".noteflow"))
        assertTrue(BackupExportPolicy.stagingFileName("n.nfb").endsWith(BackupExportPolicy.STAGING_SUFFIX))
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private fun deterministicBytes(size: Int, seed: Int): ByteArray =
        ByteArray(size) { i -> ((i * 31 + seed) and 0xFF).toByte() }

    private fun newVaultFile(dir: File, relPath: String, bytes: ByteArray): File {
        val f = File(dir, relPath)
        f.parentFile?.mkdirs()
        f.writeBytes(bytes)
        return f
    }

    private fun mainSourceRootFile(rel: String): File = File(mainSourceRoot, rel)

    /** Source with comment/KDoc lines removed so the pins never trip on their own docs. */
    private fun codeOnly(raw: String): String =
        raw.lineSequence()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*") || trimmed.startsWith("*/")
            }
            .joinToString("\n")

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}