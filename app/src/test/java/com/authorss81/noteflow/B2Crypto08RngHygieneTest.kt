package com.authorss81.noteflow

import com.authorss81.noteflow.services.EncryptionService
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2-CRYPTO-08 (phase-114): RNG hygiene — every IV/salt/DEK flows through ONE
 * centralized random-bytes source.
 *
 * Pre-fix state (all CORRECT — the finding is a positive verification plus a
 * hardening note): every IV/salt/DEK came from a fresh `SecureRandom()`
 * (`EncryptionService.generateSalt`/`generateDek`, `encrypt`, `encryptAad`,
 * `ImportExportService.exportBackup` backup-payload IV,
 * `BackupExportPolicy.encryptStreamDeviceKeyedBase64`), and re-saving always
 * re-encrypted with a brand-new random IV, so there was NO IV/nonce reuse. The
 * only gap: the random-draw was duplicated at six call sites, so a future
 * provider pin (`SecureRandom.getInstanceStrong()`, a specific algorithm/
 * provider) would have to be threaded through every site.
 *
 * After:
 *  - `EncryptionService.randomBytes(size)` is the SINGLE random-bytes source
 *    (a fresh `SecureRandom()` per call — the correct Android pattern — kept
 *    behind one function so a provider pin is a one-line change);
 *  - `newIv()` / `newSalt()` / `newDek()` are the public helpers;
 *  - `generateSalt()`/`generateDek()` delegate to them (backward-compatible);
 *  - `encrypt()` and `encryptAad()` draw their IV via `newIv()`;
 *  - the backup IVs (`ImportExportService.kt`, `BackupExportPolicy.kt`) now
 *    route through `EncryptionService.newIv()`.
 * The guaranteed fresh-IV-per-encryption invariant is unchanged and re-pinned
 * here.
 */
class B2Crypto08RngHygieneTest {

    // ---------------------------------------------------------------------
    // behavior: the centralized helpers return fresh, correctly-sized bytes
    // ---------------------------------------------------------------------

    @Test
    fun `newIv returns exactly GCM_IV_LENGTH bytes`() {
        assertEquals(EncryptionService.GCM_IV_LENGTH, EncryptionService.newIv().size)
    }

    @Test
    fun `newIv is fresh on every call - no IV reuse`() {
        val samples = (1..50).map { EncryptionService.newIv() }
        assertEquals(50, samples.distinctBy { it.toList() }.size)
    }

    @Test
    fun `newSalt defaults to 16 bytes and is fresh per call`() {
        assertEquals(16, EncryptionService.newSalt().size)
        val samples = (1..50).map { EncryptionService.newSalt() }
        assertEquals(50, samples.distinctBy { it.toList() }.size)
    }

    @Test
    fun `newSalt honors a custom size`() {
        listOf(1, 8, 24, 64).forEach { size ->
            assertEquals(size, EncryptionService.newSalt(size).size)
        }
    }

    @Test
    fun `newDek defaults to 32 bytes and is fresh per call`() {
        assertEquals(32, EncryptionService.newDek().size)
        val samples = (1..50).map { EncryptionService.newDek() }
        assertEquals(50, samples.distinctBy { it.toList() }.size)
    }

    @Test
    fun `generateSalt delegates to the 16-byte helper`() {
        val a = EncryptionService.generateSalt()
        val b = EncryptionService.generateSalt()
        assertEquals(16, a.size)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `generateDek delegates to the 32-byte helper`() {
        val a = EncryptionService.generateDek()
        val b = EncryptionService.generateDek()
        assertEquals(32, a.size)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `two generated arrays never collide byte-for-byte`() {
        val saltA = EncryptionService.newSalt()
        val dekA = EncryptionService.newDek()
        val saltB = EncryptionService.newSalt()
        val dekB = EncryptionService.newDek()
        assertNotEquals(saltA.toList(), saltB.toList())
        assertNotEquals(dekA.toList(), dekB.toList())
    }

    // ---------------------------------------------------------------------
    // behavior: fresh IV per encryption (no nonce reuse at the cipher level)
    // ---------------------------------------------------------------------

    @Test
    fun `encrypt uses a fresh IV per call - identical input yields different ciphertext`() {
        val key = ByteArray(32) { it.toByte() }
        val plain = "same plaintext".toByteArray(Charsets.UTF_8)
        val first = EncryptionService.encrypt(plain, key)
        val second = EncryptionService.encrypt(plain, key)
        assertNotEquals("same key+plaintext must never reuse an IV", first, second)
        // both still round-trip
        assertArrayEquals(plain, EncryptionService.decrypt(first, key))
        assertArrayEquals(plain, EncryptionService.decrypt(second, key))
    }

    @Test
    fun `encryptAad uses a fresh IV per call`() {
        val key = ByteArray(32) { (0x20 + it).toByte() }
        val aad = "backup/payload".toByteArray(Charsets.UTF_8)
        val plain = "payload".toByteArray(Charsets.UTF_8)
        val first = EncryptionService.encryptAad(plain, key, aad)
        val second = EncryptionService.encryptAad(plain, key, aad)
        assertNotEquals("same key+plaintext+AAD must never reuse an IV", first.toList(), second.toList())
        assertArrayEquals(plain, EncryptionService.decryptAad(first, key, aad))
        assertArrayEquals(plain, EncryptionService.decryptAad(second, key, aad))
    }

    @Test
    fun `newIv bytes vary enough to not be a fixed or trivial pattern`() {
        val iv = EncryptionService.newIv()
        assertEquals(12, iv.size)
        // A fixed/zero IV would be a nonce-reuse disaster; assert non-blank.
        assertTrue("IV must not be all zeros", iv.any { it.toInt() != 0 })
    }

    // ---------------------------------------------------------------------
    // source pins: ONE random-bytes source, every IV routed through newIv()
    // ---------------------------------------------------------------------

    private fun readSource(relative: String): String {
        val file = File(repoRoot(), relative)
        assertTrue("$relative must exist", file.isFile)
        return file.readText()
    }

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

    private fun prodSourceFilesUnder(root: File): List<File> {
        val out = ArrayList<File>()
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.path.contains("/src/main/") && !it.path.contains("/build/") }
            .forEach { out.add(it) }
        return out
    }

    @Test
    fun `nextBytes appears in exactly one production source - EncryptionService randomBytes`() {
        val dirs = listOf(
            File(repoRoot(), "app"),
            File(repoRoot(), "plugin-sdk"),
            File(repoRoot(), "plugins")
        )
        val hits = dirs.flatMap { dir ->
            if (dir.isDirectory) prodSourceFilesUnder(dir) else emptyList()
        }.filter { it.readText().contains("nextBytes") }
        assertEquals(
            "SecureRandom().nextBytes must exist ONLY behind EncryptionService.randomBytes",
            listOf(File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/EncryptionService.kt")),
            hits
        )
    }

    @Test
    fun `EncryptionService contains exactly one nextBytes draw`() {
        val source = readSource("app/src/main/kotlin/com/authorss81/noteflow/services/EncryptionService.kt")
        assertEquals(1, "nextBytes".toRegex().findAll(source).count())
        assertTrue(source.contains("private fun randomBytes(size: Int)"))
        assertTrue(source.contains("fun newIv(): ByteArray"))
        assertTrue(source.contains("fun newSalt(size: Int = SALT_BYTES)"))
        assertTrue(source.contains("fun newDek(size: Int = DEK_BYTES)"))
        assertTrue(source.contains("fun generateSalt(): ByteArray = newSalt()"))
        assertTrue(source.contains("fun generateDek(): ByteArray = newDek()"))
    }

    @Test
    fun `backup export IVs route through EncryptionService newIv`() {
        val importExport = readSource("app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt")
        assertTrue("backup payload IV must use EncryptionService.newIv()", importExport.contains("val payloadIv = EncryptionService.newIv()"))
        assertTrue("ImportExportService must not construct a SecureRandom", !importExport.contains("SecureRandom"))

        val backupPolicy = readSource("app/src/main/kotlin/com/authorss81/noteflow/services/BackupExportPolicy.kt")
        assertTrue("device-keyed IV must use EncryptionService.newIv()", backupPolicy.contains("val iv = EncryptionService.newIv()"))
        assertTrue("BackupExportPolicy must not construct a SecureRandom", !backupPolicy.contains("SecureRandom"))
    }

    @Test
    fun `encrypt and encryptAad draw their IV via newIv`() {
        val source = readSource("app/src/main/kotlin/com/authorss81/noteflow/services/EncryptionService.kt")
        // both encrypt paths must reference the centralized helper, never a raw draw
        assertEquals(2, Regex("""val iv = newIv\(\)""").findAll(source).count())
    }
}
