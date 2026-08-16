package com.authorss81.noteflow

import com.authorss81.noteflow.services.BackupPasswordPolicy
import com.authorss81.noteflow.services.EncryptionService
import com.authorss81.noteflow.services.ImportExportService
import com.authorss81.noteflow.services.PasswordStrengthVerdict
import java.io.File
import javax.crypto.AEADBadTagException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/**
 * B2-CRYPTO-04 (phase-84): password-protected backups can no longer be created
 * with a bare length-only 6-char password, and the exported NFLB3 header no
 * longer carries a cheap offline brute-force target.
 *
 * Pre-fix (`ImportExportService.exportBackup`):
 *  - the only gate was `require(backupPassword.length >= 6)` — no complexity;
 *  - the v2 header wrote `[NFLB2][salt][payloadIv][wrappedDek]` in the clear.
 *    `wrappedDek` is a 61-byte AES-GCM blob wrapped by KEK = PBKDF2(password,
 *    salt), so an attacker who steals the file (public Downloads, MTP/USB,
 *    share sheet, the WebDAV server) can test as many password guesses as a
 *    GPU/FPGA PBKDF2 rig allows, at ~zero cost per candidate beyond the KDF —
 *    a 6-7 char numeric/lowercase password cracks in hours-days, unwraps the
 *    DEK, and decrypts the whole vault, with no lockout anywhere.
 *
 * After:
 *  - `BackupPasswordPolicy` (a pure-JVM policy delegating to the master-
 *    password `PasswordStrengthPolicy` bar) is the authoritative export gate:
 *    weak backup passwords are REJECTED with the verdict + a loud offline
 *    warning, and the HomeScreen dialog pre-checks the same policy;
 *  - export writes the new v3 (NFLB3) format: the DEK is wrapped by a random
 *    32-byte key split into [16B part1 (header)] + [16B part2 (inside the
 *    password-encrypted payload)]. The public header holds half a key, so the
 *    wrapped DEK + salt can no longer be brute-forced without the payload-derived
 *    key half — every candidate must fully decrypt the payload first;
 *  - v2 (NFLB2) backups remain fully restoreable (both magics parse).
 */
class B2Crypto04BackupPasswordTest {

    companion object {
        private const val PASSWORD = "Tr0ub4dor&3!"
        private val SALT = ByteArray(16) { it.toByte() }
        private val DEK = ByteArray(32) { (0x10 + it).toByte() }
        private val ZIP_BYTES = "crafted vault zip bytes".toByteArray(Charsets.UTF_8)
        private lateinit var KEK: ByteArray

        @BeforeClass
        @JvmStatic
        fun setUp() {
            KEK = EncryptionService.deriveKey(PASSWORD, SALT)
        }
    }

    private fun dekHex(dek: ByteArray): String = dek.joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------------
    // password-strength policy (the raised minimum + complexity)
    // ---------------------------------------------------------------------

    @Test
    fun `backup passwords below the strength bar are rejected with a verdict`() {
        val cases: List<Pair<String, PasswordStrengthVerdict>> = listOf(
            "12345" to PasswordStrengthVerdict.TOO_SHORT,
            // the OLD accepted minimum — now rejected (raise from 6):
            "123456" to PasswordStrengthVerdict.TOO_SHORT,
            "abcdef" to PasswordStrengthVerdict.TOO_SHORT,
            "A9fj2l!" to PasswordStrengthVerdict.TOO_SHORT, // 7 chars — still too short
            "12345678" to PasswordStrengthVerdict.SEQUENTIAL,
            "qwertyui" to PasswordStrengthVerdict.SEQUENTIAL,
            "abcdefgh" to PasswordStrengthVerdict.SEQUENTIAL,
            "aaaaabaa" to PasswordStrengthVerdict.WEAK, // < 3 distinct graphemes
            "PASSWORD1" to PasswordStrengthVerdict.LOW_DIVERSITY, // 9 chars, upper+digit only
        )
        cases.forEach { (pw, expected) ->
            assertEquals("$pw must be rejected as $expected", expected, BackupPasswordPolicy.evaluate(pw))
        }

        // Long passphrases and diversified passwords pass.
        assertEquals(PasswordStrengthVerdict.ACCEPTED, BackupPasswordPolicy.evaluate("correct horse battery staple"))
        assertEquals(PasswordStrengthVerdict.ACCEPTED, BackupPasswordPolicy.evaluate(PASSWORD))
        assertEquals(PasswordStrengthVerdict.ACCEPTED, BackupPasswordPolicy.evaluate("Sup3r-S3cret-Passphrase-2026"))
    }

    @Test
    fun `requireStrongBackupPassword throws loudly for weak passwords`() {
        val ex = assertThrows(IllegalArgumentException::class.java) {
            BackupPasswordPolicy.requireStrongBackupPassword("123456")
        }
        assertTrue("rejection carries the strength verdict", ex.message.orEmpty().contains("8 characters"))
        assertTrue(
            "rejection is never silent about the offline cost",
            ex.message.orEmpty().contains("offline")
        )
        // The policy's loud-warning copy exists.
        assertTrue(BackupPasswordPolicy.OFFLINE_BACKUP_NOTICE.contains("Downloads"))
        assertTrue(BackupPasswordPolicy.OFFLINE_BACKUP_NOTICE.contains("offline"))

        // A strong password passes the gate.
        BackupPasswordPolicy.requireStrongBackupPassword(PASSWORD)
    }

    // ---------------------------------------------------------------------
    // v3 (NFLB3): split-key header no longer carries a cheap crack target
    // ---------------------------------------------------------------------

    /** Builds a full v3 NFLB3 file: header ++ AES-GCM(part2 || zip) under KEK. */
    private fun buildV3Backup(): Triple<ByteArray, ByteArray, ByteArray> {
        val wrapKey = EncryptionService.generateDek() // 32 random bytes
        val part1 = wrapKey.copyOfRange(0, 16)
        val part2 = wrapKey.copyOfRange(16, 32)
        val wrappedDek = EncryptionService.encryptAad(DEK, wrapKey, ImportExportService.BACKUP_DEK_WRAP_AAD)
        val iv = ByteArray(12) { (0x40 + it).toByte() }
        val header = ImportExportService.buildBackupHeaderV3(SALT, iv, part1, wrappedDek)
        val payloadPlain = ByteArray(part2.size + ZIP_BYTES.size).also { out ->
            System.arraycopy(part2, 0, out, 0, part2.size)
            System.arraycopy(ZIP_BYTES, 0, out, part2.size, ZIP_BYTES.size)
        }
        val payloadCipher = ImportExportService.encryptBackupPayload(payloadPlain, KEK, iv, header)
        val full = ByteArray(header.size + payloadCipher.size).also { out ->
            System.arraycopy(header, 0, out, 0, header.size)
            System.arraycopy(payloadCipher, 0, out, header.size, payloadCipher.size)
        }
        wrapKey.fill(0.toByte())
        return Triple(full, part1, wrappedDek)
    }

    @Test
    fun `v3 wire format splits the wrap key - header carries only half`() {
        val (_, part1, wrappedDek) = buildV3Backup()

        // The wrapped DEK is the standard 61-byte versioned GCM payload
        // (1 + 12 + 32 + 16), so the AES-GCM tag is present and cheap to check
        // ONLY with the full 32-byte key.
        assertEquals(1 + 12 + 32 + 16, wrappedDek.size)
        assertEquals(16, part1.size)

        // With ONLY the public header knowledge (part1), neither half alone — nor
        // part1 padded to 32 bytes — can decrypt the wrapped DEK. The tag cannot
        // be verified, so a password guess has nothing cheap to test against.
        val part1Padded = ByteArray(32)
        System.arraycopy(part1, 0, part1Padded, 0, 16)
        assertThrows(AEADBadTagException::class.java) {
            EncryptionService.decryptAad(wrappedDek, part1Padded, ImportExportService.BACKUP_DEK_WRAP_AAD)
        }

        // The payload-derived half is encrypted inside the payload: gone without
        // the password. The correct full key (part1||part2, only recoverable
        // after a successful payload decrypt) opens it.
        val fullWrap = ByteArray(32).also { out ->
            System.arraycopy(part1, 0, out, 0, 16)
            System.arraycopy(ByteArray(16) { (0x70 + it).toByte() }, 0, out, 16, 16) // NOT the real part2
        }
        assertThrows(AEADBadTagException::class.java) {
            EncryptionService.decryptAad(wrappedDek, fullWrap, ImportExportService.BACKUP_DEK_WRAP_AAD)
        }
    }

    @Test
    fun `a v3 payload cannot be decrypted without the password`() {
        val (full, _, _) = buildV3Backup()
        val wrongKek = EncryptionService.deriveKey("wrong-Password-2026!", SALT)
        val iv = full.copyOfRange("NFLB3".length + 16, "NFLB3".length + 16 + 12)
        val header = full.copyOfRange(0, 110)
        val cipherText = full.copyOfRange(110, full.size)
        assertThrows(AEADBadTagException::class.java) {
            ImportExportService.decryptBackupPayloadV3(cipherText, wrongKek, iv, header)
        }
    }

    @Test
    fun `a v3 backup round-trips through the restore parse with the right password`() {
        val (full, _, _) = buildV3Backup()

        val parsed = ImportExportService.tryParseBackupV2(full, PASSWORD)
        assertNotNull("v3 must parse via the restore path", parsed)
        assertArrayEquals("the split payload-derived half is stripped, the zip recovered", ZIP_BYTES, parsed!!.zipBytes)
        assertEquals("the wrapped DEK unwraps to the vault DEK", dekHex(DEK), parsed.dekHex)
        assertNotNull("the derived KEK is handed to importBackup for zeroization", parsed.kek)
        parsed.kek?.fill(0.toByte())
    }

    @Test
    fun `a v3 backup rejects the wrong password and stays binary-compatible with v2 reads`() {
        val (full, _, _) = buildV3Backup()
        // Wrong password: no candidate decrypts the payload -> rejected loudly.
        val wrong = assertThrows(IllegalArgumentException::class.java) {
            ImportExportService.tryParseBackupV2(full, "wrong-Password-2026!")
        }
        assertTrue(wrong.message.orEmpty().contains("Incorrect backup password"))

        // An NFLB3 magic + header with NO payload parses to null (handed to the
        // legacy device-keyed reader, which can never match v3 bytes) — never a
        // password success on an incomplete file.
        assertNull(ImportExportService.tryParseBackupV2(full.copyOfRange(0, 110), PASSWORD))
    }

    @Test
    fun `validateBackupPassword exercises both magics and never strength-gates`() {
        val (v3Bytes, _, _) = buildV3Backup()
        // v3 validate = one full payload decrypt + DEK unwrap (the only test).
        ImportExportService.validateBackupPassword(v3Bytes, PASSWORD)
        val ex = assertThrows(IllegalArgumentException::class.java) {
            ImportExportService.validateBackupPassword(v3Bytes, "wrong-Password-2026!")
        }
        assertTrue(ex.message.orEmpty().contains("Incorrect backup password"))

        // v2 file still validates (that path keeps its cheap wrapped-DEK probe).
        val v2 = buildV2Backup()
        ImportExportService.validateBackupPassword(v2, PASSWORD)
    }

    // ---------------------------------------------------------------------
    // v2 (NFLB2) backward compatibility — old password backups still restore
    // ---------------------------------------------------------------------

    private fun buildV2Backup(): ByteArray {
        val wrappedDek = EncryptionService.encryptAad(DEK, KEK, ImportExportService.BACKUP_DEK_WRAP_AAD)
        val iv = ByteArray(12) { (0x0A + it).toByte() }
        val header = ImportExportService.buildBackupHeader(SALT, iv, wrappedDek)
        val payload = ImportExportService.encryptBackupPayload(ZIP_BYTES, KEK, iv, header)
        return ByteArray(header.size + payload.size).also { out ->
            System.arraycopy(header, 0, out, 0, header.size)
            System.arraycopy(payload, 0, out, header.size, payload.size)
        }
    }

    @Test
    fun `pre-fix v2 backups still restore with the exact same format`() {
        val v2 = buildV2Backup()
        val parsed = ImportExportService.tryParseBackupV2(v2, PASSWORD)
        assertNotNull(
            "v2 backups must keep parsing after the v3 introduction",
            parsed
        )
        assertArrayEquals(ZIP_BYTES, parsed!!.zipBytes)
        assertEquals(dekHex(DEK), parsed.dekHex)

        val wrong = assertThrows(IllegalArgumentException::class.java) {
            ImportExportService.tryParseBackupV2(v2, "wrong-Password-2026!")
        }
        assertTrue(wrong.message.orEmpty().contains("Incorrect backup password"))
        parsed.kek?.fill(0.toByte())
    }

    // ---------------------------------------------------------------------
    // source-level wiring pins
    // ---------------------------------------------------------------------

    @Test
    fun `exportBackup gates the password with the strength policy and writes v3`() {
        val source = codeOnly(mainSourceRootFile("services/ImportExportService.kt").readText())
        val exportRegion = source.substringAfter("fun exportBackup(").substringBefore("private fun copyWithLimit")

        // The pre-fix bare length-only check is gone from the export path.
        assertFalse(exportRegion.contains("normalizedGraphemeCount(backupPassword)"))
        assertFalse(exportRegion.contains("MIN_PASSWORD_GRAPHEMES"))

        // The password gate is the strength policy, and the rejection is loud.
        assertTrue(exportRegion.contains("BackupPasswordPolicy.requireStrongBackupPassword(backupPassword)"))

        // The v3 split-key + streaming write is wired.
        assertTrue(exportRegion.contains("buildBackupHeaderV3("))
        assertTrue(exportRegion.contains("BACKUP_WRAP_KEY_HALF_SIZE"))
        assertTrue(exportRegion.contains("SequenceInputStream("))
        assertTrue(exportRegion.contains("wrapKeyPart2"))
        // The assembled wrap key and BOTH halves are zeroized after the write.
        assertTrue(exportRegion.contains("wrapKey.fill(0.toByte())"))
        assertTrue(exportRegion.contains("wrapKeyPart1.fill(0.toByte())"))
        assertTrue(exportRegion.contains("wrapKeyPart2.fill(0.toByte())"))
        // The derived KEK remains zeroized on every outcome.
        assertTrue(exportRegion.contains("kek.fill(0.toByte())"))
    }

    @Test
    fun `the restore parse and verify paths understand both magics but never strength-gate`() {
        val source = codeOnly(mainSourceRootFile("services/ImportExportService.kt").readText())
        // Both magics are parsed by one format-agnostic reader.
        assertTrue(source.contains("BACKUP_MAGIC_V3"))
        assertTrue(source.contains("parseBackupHeader("))
        // The v3 payload prefix (payload-derived key half) is handled.
        assertTrue(source.contains("payloadKeyPrefix"))

        // Restore NEVER strength-gates (a pre-fix weak-password backup must
        // still restore — unlock paths never strength-gate, matching phase-63).
        val parseRegion = source.substringAfter("fun tryParseBackupV2").substringBefore("fun validateBackupPassword")
        val validateRegion = source.substringAfter("fun validateBackupPassword").substringBefore("private fun rekeySqlcipherDb")
        assertFalse(parseRegion.contains("BackupPasswordPolicy"))
        assertFalse(validateRegion.contains("BackupPasswordPolicy"))
    }

    @Test
    fun `the HomeScreen backup dialog pre-checks the policy and shows the offline warning`() {
        val source = codeOnly(mainSourceRootFile("ui/screens/HomeScreen.kt").readText())
        assertTrue(
            "the dialog must pre-check the same strength policy",
            source.contains("BackupPasswordPolicy.evaluate(backupPasswordInput)")
        )
        assertFalse(
            "the bare length-only check is gone from the backup dialog",
            source.contains("normalizedGraphemeCount(backupPasswordInput) < EncryptionService.MIN_PASSWORD_GRAPHEMES")
        )
        assertTrue(
            "the offline weakness warning is surfaced on the create-backup dialog",
            source.contains("BackupPasswordPolicy.OFFLINE_BACKUP_NOTICE")
        )
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private val mainSourceRoot by lazy {
        var dir = File(System.getProperty("user.dir") ?: ".")
        repeat(8) {
            if (File(dir, "app/src/main/kotlin/com/authorss81/noteflow").isDirectory) return@lazy File(dir, "app/src/main/kotlin/com/authorss81/noteflow")
            dir = dir.parentFile ?: return@lazy File(".")
        }
        File(".")
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
}