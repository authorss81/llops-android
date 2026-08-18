package com.authorss81.noteflow

import com.authorss81.noteflow.services.EncryptionService
import com.authorss81.noteflow.services.backupRestoreOpenCandidates
import com.authorss81.noteflow.services.isPlainPkBackupBytes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-DB-7 (phase-56) behavioral + wiring tests for the plain-zip/empty-key
 * restore fix.
 *
 * Finding: the legacy restore path treated any `PK`-headed payload as a plain
 * keyless backup and `validateAndPrepareRestoredDb` tried the EMPTY SQLCipher
 * passphrase (`""`) as an open candidate. An attacker-crafted zip whose
 * `noteflow.sqlite` was created with the empty key passes PRAGMA
 * integrity_check, gets re-keyed to the victim's real DEK, HMAC-rearmed and
 * moved over the live vault — attacker-chosen content (phishing notes, planted
 * data, an empty DB wiping everything) presented as a "successful restore".
 *
 * What is provable on the pure JVM (no Room/SQLCipher/Context): (a) the raw-PK
 * classifier used at the restore entry gate, (b) the open-candidate decision —
 * the empty-key entry must be gone AND stripped fail-closed even if a future
 * caller passes `""`, so a plaintext SQLCipher DB (only openable via `""`) can
 * never open. The Android-bound gates (importBackup throwing for plain zips
 * before any decrypt/extract, and validateAndPrepareRestoredDb consuming only
 * `backupRestoreOpenCandidates`) are pinned at source level below.
 */
class B1Db07PlainZipRestoreRejectedTest {

    // ---- isPlainPkBackupBytes (pure JVM behavior) ---------------------------

    @Test
    fun `a PK headed payload is classified as the plain unencrypted backup signature`() {
        val realZipHeader = byteArrayOf(
            'P'.code.toByte(), 'K'.code.toByte(), 0x03, 0x04
        )

        assertTrue(isPlainPkBackupBytes(realZipHeader))
        // PK\x05\x06 (end-of-central-directory) is a valid zip start signature too.
        assertTrue(isPlainPkBackupBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x05, 0x06)))
        // ZIP64 EOCD record and data-descriptor signatures are also PK-headed.
        assertTrue(isPlainPkBackupBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x06, 0x06)))
        assertTrue(isPlainPkBackupBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x07, 0x08)))
    }

    @Test
    fun `a payload that merely begins with the ASCII letters PK is not a plain zip`() {
        // The classifier must validate the FULL 4-byte zip signature, not just the
        // "PK" prefix — otherwise a text/base64 payload that happens to begin with
        // "P","K" (e.g. device-DEK ciphertext whose base64 starts with those two
        // letters) would be misclassified as an unencrypted backup and rejected.
        assertFalse(
            "binary that is PK + non-zip signature is not a zip",
            isPlainPkBackupBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x00, 0x00, 'r'.code.toByte()))
        )
        assertFalse("ASCII text starting with PK is not a zip", isPlainPkBackupBytes("PKTextThatIs/*base64*".toByteArray(Charsets.UTF_8)))
        assertFalse("PK followed by EOF marker bytes is not a zip", isPlainPkBackupBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x00, 0x04)))
        assertFalse("PK followed by 0x03 0x00 is not a valid zip signature", isPlainPkBackupBytes(byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03, 0x00)))
    }

    @Test
    fun `v2 password backups, device-keyed ciphertext and undersized payloads are never plain zips`() {
        assertFalse(isPlainPkBackupBytes("NFLB2_not_a_zip".toByteArray(Charsets.UTF_8)))
        assertFalse("device-keyed base64 ciphertext is not a raw zip", isPlainPkBackupBytes("Q2lwaGVydGV4dA==".toByteArray(Charsets.UTF_8)))
        assertFalse("fewer than 4 bytes cannot be a zip header", isPlainPkBackupBytes(byteArrayOf('P'.code.toByte())))
        assertFalse("empty bytes cannot be a zip header", isPlainPkBackupBytes(ByteArray(0)))
    }

    @Test
    fun `a real device-keyed legacy backup produces bytes that never trip the plain-zip classifier`() {
        // exportBackup's legacy path emits EncryptionService.encrypt(zipData, key)
        // — a base64 string of [PAYLOAD_VERSION=1][12B IV][ciphertext+tag]. The
        // payload's first byte is the version marker (0x01), whose top 6 bits are
        // 0, so the base64 output can only start with 'A' — never "PK". Pin that
        // end-to-end so the entry gate (importBackup) never false-rejects a real
        // device-keyed backup, which would be a restore-breaking regression.
        val key = "0123456789abcdef0123456789abcdef".toByteArray(Charsets.UTF_8)
        val fakeZip = byteArrayOf('P'.code.toByte(), 'K'.code.toByte(), 0x03, 0x04) + ByteArray(64)

        val ciphertext = EncryptionService.encrypt(fakeZip, key)
        val rawBackupBytes = ciphertext.toByteArray(Charsets.UTF_8)

        assertTrue(
            "the device-DEK ciphertext must start with the version-prefixed base64 alphabet, never 'PK'",
            !rawBackupBytes.copyOfRange(0, 2).contentEquals(byteArrayOf('P'.code.toByte(), 'K'.code.toByte()))
        )
        assertFalse(
            "a genuine device-keyed export must not be classified as an unencrypted plain zip",
            isPlainPkBackupBytes(rawBackupBytes)
        )
    }

    // ---- backupRestoreOpenCandidates (pure JVM behavior) --------------------

    @Test
    fun `the empty passphrase candidate is never returned even when called with empty strings`() {
        // The historic exploit lived in the `listOfNotNull(...,"")` entry. A
        // future caller must not be able to re-introduce it by passing "".
        for (backupDek in listOf<String?>(null, "", "   ")) {
            for (currentDek in listOf<String?>(null, "", "   ")) {
                val candidates = backupRestoreOpenCandidates(backupDek, currentDek)
                assertTrue(
                    "empty-key candidate must be stripped fail-closed (backupDek=$backupDek, currentDek=$currentDek)",
                    candidates.none { it.isEmpty() }
                )
                assertTrue(
                    "whitespace-only candidates must be stripped too",
                    candidates.none { it != it.trim() }
                )
            }
        }
    }

    @Test
    fun `a plaintext SQLCipher database is only openable by the empty key, which is no longer offered`() {
        // The exploit premise: the attacker's DB was created with the empty
        // passphrase, so the ONLY key that opens it is "". The permitted
        // open-key universe must therefore exclude "" — otherwise the crafted
        // DB passes integrity_check and becomes the vault.
        val backupDek = "ab" + "0f".repeat(31) // 32-byte hex DEK
        val currentDek = "1c" + "e9".repeat(31)
        val candidates = backupRestoreOpenCandidates(backupDek, currentDek)

        assertFalse(
            "the empty key — the only way a plaintext SQLCipher DB opens — must never be a candidate",
            candidates.any { it.isEmpty() }
        )
        assertTrue("the backup's own wrapped DEK must still be offered", candidates.contains(backupDek))
        assertTrue("the current DEK must still be offered", candidates.contains(currentDek))
    }

    @Test
    fun `only unique real device keys are offered - no empty key, no duplicates`() {
        val dek = "7a" + "3c".repeat(31)
        assertEquals(listOf(dek), backupRestoreOpenCandidates(dek, dek))
        assertEquals(listOf(dek), backupRestoreOpenCandidates(null, dek))
        assertEquals(listOf(dek), backupRestoreOpenCandidates(dek, null))
        assertEquals(listOf(dek), backupRestoreOpenCandidates("", dek))
        assertEquals(listOf(dek), backupRestoreOpenCandidates(dek, ""))
    }

    @Test
    fun `with no key material the candidate set is empty - fail closed, restore rejected`() {
        assertEquals(
            "no keys -> no candidate -> openedWith stays null -> restore rejected",
            listOf<String>(), backupRestoreOpenCandidates(null, null)
        )
    }

    // ---- source-level wiring pins (the Android-bound gates) -----------------

    private val ieSource by lazy {
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/ImportExportService.kt").readText()
    }

    private val importBackupRegion: String
        get() = ieSource
            .substringAfter("suspend fun importBackup")
            .substringBefore("private fun restoreFromZip")

    private val restoreDbRegion: String
        get() = ieSource
            .substringAfter("private fun validateAndPrepareRestoredDb")
            .substringBefore("private fun rekeyVoiceNoteBlobs")

    @Test
    fun `importBackup rejects a raw plain zip before any decrypt or extraction`() {
        val region = importBackupRegion

        assertTrue(
            "the plain-zip gate must fire on PK-headed payloads",
            region.contains("if (isPlainPkBackupBytes(rawBytes))")
        )
        assertTrue(
            "the rejection message must flag the backup as unencrypted/unsigned",
            region.contains("Restore rejected: this is an unencrypted (unsigned) backup")
        )

        // The gate must be structural: the reject fires BEFORE any keyed decrypt
        // and BEFORE the legacy zip extraction can happen.
        val rejectIndex = region.indexOf("Restore rejected: this is an unencrypted")
        val decryptIndex = region.indexOf("EncryptionService.decrypt(encryptedStr, key)")
        val extractIndex = region.indexOf("restoreFromZip(context, rawBytes, null, currentDekHex, allowEmptyVault)")
        assertTrue("the plain-zip reject must precede the decrypt path", rejectIndex >= 0 && rejectIndex < decryptIndex)
        assertTrue("the plain-zip reject must precede any extraction", rejectIndex >= 0 && rejectIndex < extractIndex)
    }

    @Test
    fun `the device-keyed legacy decrypt path must remain for authenticated backups`() {
        val region = importBackupRegion
        assertTrue(
            "device-DEK-encrypted (non-PK) legacy backups must still restore",
            region.contains("rawBytes = EncryptionService.decrypt(encryptedStr, key)")
        )
        assertTrue(
            "the device-keyed path still needs an encryption key",
            region.contains("This backup is encrypted. Please set and verify your Master Password first.")
        )
    }

    @Test
    fun `validateAndPrepareRestoredDb offers only backupRestoreOpenCandidates - never a literal empty key`() {
        val region = restoreDbRegion

        assertTrue(
            "the candidate set must come from the pure helper",
            region.contains("val candidates = backupRestoreOpenCandidates(backupDekHex, currentDekHex)")
        )
        assertFalse(
            "the historic listOfNotNull(...,\"\") candidate must be gone",
            region.contains("listOfNotNull(backupDekHex, currentDekHex, \"\")")
        )
        assertTrue(
            "the DB open must use the candidate VARIABLE, never a literal key",
            region.contains("tempDb, candidate, null, null, null")
        )
        assertTrue("the old v2/device-keyed integrity check must still run", region.contains("PRAGMA integrity_check"))
    }

    @Test
    fun `the empty-key open candidate is gone from the whole import export service`() {
        assertTrue(
            "the pure candidate helper must be declared",
            ieSource.contains("internal fun backupRestoreOpenCandidates(backupDekHex: String?, currentDekHex: String?)")
        )
        assertTrue(
            "the restore path must open the DB with the candidate variable, never a literal key",
            ieSource.contains("tempDb, candidate, null, null, null")
        )
        // The only remaining `""` text in the file documents the historical bug
        // (KDoc); no open call in the CODE may pass a literal empty key.
        assertFalse(
            "no DB open may pass a literal empty passphrase",
            Regex("openOrCreateDatabase\\s*\\([^)]*\"\"").containsMatchIn(ieSource)
        )
    }

    @Test
    fun `the HomeScreen picker refuses a plain zip before offering the legacy dialog`() {
        val home = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt").readText()
        val picker = home
            .substringAfter("val restorePickerLauncher")
            .substringBefore("// Global Vault Search state")

        assertTrue(
            "the picker must classify a plain zip and refuse it",
            picker.contains("isPlainPkBackupBytes(bytes)")
        )
        assertTrue(
            "the refusal must happen before the legacy-confirm dialog is shown",
            picker.indexOf("isPlainPkBackupBytes(bytes)") < picker.indexOf("showLegacyRestoreConfirmDialog = true")
        )
        assertTrue(
            "the refusal surfaces a non-alarming snackbar",
            picker.contains("Restore rejected: this is an unencrypted (unsigned) backup")
        )
    }

    @Test
    fun `the legacy device-keyed confirm dialog warns that the backup is untrusted and unsigned`() {
        val home = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/HomeScreen.kt").readText()
        assertTrue(
            "the remaining legacy (device-keyed) path must carry the UNTRUSTED warning",
            home.contains("UNTRUSTED")
        )
        assertTrue(
            "the remaining legacy (device-keyed) path must carry the UNSIGNED warning",
            home.contains("UNSIGNED backup")
        )
    }

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