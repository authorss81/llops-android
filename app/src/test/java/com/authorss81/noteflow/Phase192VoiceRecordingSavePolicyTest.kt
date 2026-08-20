package com.authorss81.noteflow

import com.authorss81.noteflow.services.VoiceEncryptFailure
import com.authorss81.noteflow.services.VoiceEncryptOutcome
import com.authorss81.noteflow.services.VoiceNoteCrypto
import com.authorss81.noteflow.services.VoiceRecordingSavePolicy
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Phase 192 — the voice-recording save decision at STOP time.
 *
 * Report: voice recording always showed "The recording could not be saved
 * securely. Please try again." and never saved. `finalizeRecording` fired that
 * single generic string for EVERY failed save, collapsing three realities:
 *
 *  1. a GENUINELY-LOCKED password vault (DEK zeroized mid-recording) — must
 *     fail closed, and this is the ONLY case that keeps the historic wording;
 *  2. a PASSWORDLESS vault whose in-memory DEK holder was null at stop — the
 *     device-wrapped copy IS the boot credential (the DB factory re-reads it on
 *     every open), so the DEK is re-available at stop time instead of failing;
 *  3. a recoverable cipher/IO failure (storage full, transient I/O/JCE) — must
 *     get a TRUTHFUL, non-alarming message, never a false "saved securely".
 *
 * All provable on the pure JVM: [VoiceRecordingSavePolicy] (decision + message
 * tables), [VoiceNoteCrypto.encryptRecordingFileDetailed] (encrypt + failure
 * classification + blob-dir creation), plus source pins proving the Android
 * binding (`VoiceNoteManager.finalizeRecording`) wires the policy and never lets
 * a plaintext temp outlive a failed save.
 */
class Phase192VoiceRecordingSavePolicyTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dek: ByteArray = ByteArray(32) { 9 }
    private val audioBytes: ByteArray = "FAKE AAC memo bytes — must never linger plaintext".toByteArray(Charsets.UTF_8)

    // ---------------------------------------------------------------------
    // resolveStopTimeKey — the stop-time DEK decision table
    // ---------------------------------------------------------------------

    @Test
    fun `in-memory DEK is used as-is for both vault kinds`() {
        val inMemory = ByteArray(32) { 3 }
        val r = VoiceRecordingSavePolicy.resolveStopTimeKey(
            inMemoryDek = inMemory,
            vaultHasPassword = true,
            passwordlessReader = { throw AssertionError("reader must not run when the holder is live") }
        )
        assertTrue(r is VoiceRecordingSavePolicy.StopTimeKey.InMemory)
        assertTrue(VoiceRecordingSavePolicy.isKeyPresent(r))
        assertTrue("the SAME in-memory key is handed to the encrypt", (r as VoiceRecordingSavePolicy.StopTimeKey.InMemory).key === inMemory)
    }

    @Test
    fun `passwordless vault with null in-memory DEK re-reads the device copy`() {
        val deviceCopy = ByteArray(32) { 7 }
        var reads = 0
        val r = VoiceRecordingSavePolicy.resolveStopTimeKey(
            inMemoryDek = null,
            vaultHasPassword = false,
            passwordlessReader = { reads++; deviceCopy }
        )
        assertTrue(r is VoiceRecordingSavePolicy.StopTimeKey.PasswordlessReread)
        assertTrue("the reread key is the DEK to encrypt with", VoiceRecordingSavePolicy.isKeyPresent(r))
        assertTrue((r as VoiceRecordingSavePolicy.StopTimeKey.PasswordlessReread).key.contentEquals(deviceCopy))
        assertEquals("the passwordless re-read is exactly one call", 1, reads)
    }

    @Test
    fun `passwordless vault with unreadable device copy fails closed - never mints`() {
        val r = VoiceRecordingSavePolicy.resolveStopTimeKey(
            inMemoryDek = null,
            vaultHasPassword = false,
            passwordlessReader = { null }
        )
        assertTrue(r is VoiceRecordingSavePolicy.StopTimeKey.KeyUnavailable)
        assertFalse(VoiceRecordingSavePolicy.isKeyPresent(r))
        assertNull("no key is ever fabricated", r.key)
    }

    @Test
    fun `passwordless reader throwing fails closed`() {
        val r = VoiceRecordingSavePolicy.resolveStopTimeKey(
            inMemoryDek = null,
            vaultHasPassword = false,
            passwordlessReader = { throw java.io.IOException("keystore lost") }
        )
        assertTrue(r is VoiceRecordingSavePolicy.StopTimeKey.KeyUnavailable)
        assertFalse(VoiceRecordingSavePolicy.isKeyPresent(r))
    }

    @Test
    fun `locked password vault never consults the passwordless reader`() {
        var readerCalls = 0
        val r = VoiceRecordingSavePolicy.resolveStopTimeKey(
            inMemoryDek = null,
            vaultHasPassword = true,
            passwordlessReader = { readerCalls++; ByteArray(32) { 1 } }
        )
        assertTrue("a zeroized-DEK password vault is GENUINELY LOCKED", r is VoiceRecordingSavePolicy.StopTimeKey.LockedVault)
        assertFalse("locked => nothing to encrypt with", VoiceRecordingSavePolicy.isKeyPresent(r))
        assertEquals("the passwordless re-read must never run for a password vault", 0, readerCalls)
    }

    // ---------------------------------------------------------------------
    // encrypt — passwordless DEK available => saved, missing dir created
    // ---------------------------------------------------------------------

    @Test
    fun `passwordless vault with DEK available encrypts and destroys the plaintext`() {
        val voiceDir = File(tmp.newFolder("root"), "voice_notes") // does NOT exist yet
        val plain = File(voiceDir.parentFile, "voice_p1_1.m4a")
        plain.writeBytes(audioBytes)
        val blob = File(voiceDir, "voice_p1_1.enc")

        val stopKey = VoiceRecordingSavePolicy.resolveStopTimeKey(dek, vaultHasPassword = false) {
            throw AssertionError("in-memory key already available")
        }
        assertTrue(VoiceRecordingSavePolicy.isKeyPresent(stopKey))

        val outcome = VoiceNoteCrypto.encryptRecordingFileDetailed(plain, blob, dek)
        assertTrue("the DEK-available save is Saved, got $outcome", outcome is VoiceEncryptOutcome.Saved)
        assertFalse("the plaintext temp is gone", plain.exists())
        assertTrue("the encrypted blob exists at rest", blob.exists())
        assertFalse("blob bytes are never the plaintext", blob.readBytes().contentEquals(audioBytes))

        val restored = File(voiceDir.parentFile, "restored.m4a")
        assertTrue(VoiceNoteCrypto.decryptRecordingFile(blob, restored, dek))
        assertArrayEquals(audioBytes, restored.readBytes())
    }

    @Test
    fun `missing blob directory is created on save`() {
        val root = tmp.newFolder("root2")
        val plain = File(root, "voice_p2_2.m4a")
        plain.writeBytes(audioBytes)
        val blobParent = File(root, "voice_notes/never/existed")
        val blob = File(blobParent, "voice_p2_2.enc")

        val outcome = VoiceNoteCrypto.encryptRecordingFileDetailed(plain, blob, dek)

        assertTrue("the blob parent is created (mkdirs) and the save succeeds, got $outcome", outcome is VoiceEncryptOutcome.Saved)
        assertTrue("the missing nested dir now exists", blobParent.exists())
        assertTrue("the blob landed at the intended path", blob.exists())
        assertFalse(plain.exists())
    }

    // ---------------------------------------------------------------------
    // locked vault — fail closed, nothing persisted, plaintext destroyed
    // ---------------------------------------------------------------------

    @Test
    fun `locked vault fails closed with no encrypt attempt and the temp is destroyed`() {
        val root = tmp.newFolder("root3")
        val plain = File(root, "voice_p3_3.m4a")
        plain.writeBytes(audioBytes)
        val blob = File(root, "voice_notes").let { File(it, "voice_p3_3.enc") }

        // Mirror finalizeRecording's decision sequence for a locked vault:
        val stopKey = VoiceRecordingSavePolicy.resolveStopTimeKey(
            inMemoryDek = null,
            vaultHasPassword = true,
            passwordlessReader = { throw AssertionError("never consulted when locked") }
        )
        val keyPresent = VoiceRecordingSavePolicy.isKeyPresent(stopKey)
        // no key => the save branch must NOT call the cryptor at all
        val outcome = if (keyPresent) VoiceNoteCrypto.encryptRecordingFileDetailed(plain, blob, dek) else null
        assertNull("no encrypt attempt is made with a locked vault", outcome)
        // the failure branch deletes the plaintext temp immediately (B1-DB-3)
        plain.delete()
        assertFalse("no plaintext survives a locked-vault save", plain.exists())
        // and nothing was persisted
        assertFalse("no blob is created when locked", blob.exists())
    }

    // ---------------------------------------------------------------------
    // message table — truthful, recoverable-oriented; generic kept for locked
    // ---------------------------------------------------------------------

    @Test
    fun `the historic 'saved securely' wording is reserved for the genuinely locked vault`() {
        val locked = VoiceRecordingSavePolicy.messageFor(
            VoiceRecordingSavePolicy.StopTimeKey.LockedVault,
            null
        )
        assertEquals(VoiceRecordingSavePolicy.LOCKED_VAULT_MESSAGE, locked)
        assertEquals(
            "the exact user-reported string must be the locked-vault surface",
            "The recording could not be saved securely. Please try again.",
            locked
        )
    }

    @Test
    fun `recoverable failures never claim 'saved securely' and are non-alarming`() {
        val recoverable = listOf(
            VoiceRecordingSavePolicy.messageFor(
                VoiceRecordingSavePolicy.StopTimeKey.InMemory(dek),
                VoiceEncryptOutcome.Failed(VoiceEncryptFailure.ENOSPC)
            ),
            VoiceRecordingSavePolicy.messageFor(
                VoiceRecordingSavePolicy.StopTimeKey.InMemory(dek),
                VoiceEncryptOutcome.Failed(VoiceEncryptFailure.IO_OR_CIPHER)
            ),
            VoiceRecordingSavePolicy.messageFor(
                VoiceRecordingSavePolicy.StopTimeKey.PasswordlessReread(dek),
                VoiceEncryptOutcome.Failed(VoiceEncryptFailure.IO_OR_CIPHER)
            ),
            VoiceRecordingSavePolicy.messageFor(
                VoiceRecordingSavePolicy.StopTimeKey.KeyUnavailable,
                null
            ),
        )
        for (message in recoverable) {
            assertFalse("recoverable message must not claim 'saved securely': $message", message.contains("saved securely"))
            assertFalse("recoverable message must not claim the audio was saved: $message", message.contains("was saved") && message.contains("audio"))
            assertTrue("recoverable message is non-empty", message.isNotBlank())
        }
    }

    @Test
    fun `storage-full maps to the storage message and transient to the generic transient`() {
        val storage = VoiceRecordingSavePolicy.messageFor(
            VoiceRecordingSavePolicy.StopTimeKey.InMemory(dek),
            VoiceEncryptOutcome.Failed(VoiceEncryptFailure.ENOSPC)
        )
        assertEquals(VoiceRecordingSavePolicy.STORAGE_FULL_MESSAGE, storage)
        assertTrue("storage message tells the recoverable cause", storage.contains("storage"))

        val transient = VoiceRecordingSavePolicy.messageFor(
            VoiceRecordingSavePolicy.StopTimeKey.InMemory(dek),
            VoiceEncryptOutcome.Failed(VoiceEncryptFailure.IO_OR_CIPHER)
        )
        assertEquals(VoiceRecordingSavePolicy.TRANSIENT_FAILURE_MESSAGE, transient)
    }

    @Test
    fun `the key-unavailable passwordless message is honest and distinct`() {
        val msg = VoiceRecordingSavePolicy.messageFor(VoiceRecordingSavePolicy.StopTimeKey.KeyUnavailable, null)
        assertEquals(VoiceRecordingSavePolicy.KEY_UNAVAILABLE_MESSAGE, msg)
        assertTrue("names the missing key honestly", msg.contains("key"))
        assertNotEqualsSilently(VoiceRecordingSavePolicy.LOCKED_VAULT_MESSAGE, msg)
    }

    // ---------------------------------------------------------------------
    // cryptor failure classification
    // ---------------------------------------------------------------------

    @Test
    fun `encrypt failure classification distinguishes source, target and IO`() {
        val root = tmp.newFolder("root4")
        val plain = File(root, "voice_p4_4.m4a")
        plain.writeBytes(audioBytes)
        val blob = File(root, "voice_notes").apply { mkdirs() }.let { File(it, "voice_p4_4.enc") }

        // SOURCE: plaintext missing
        val missing = VoiceNoteCrypto.encryptRecordingFileDetailed(File(root, "does_not_exist.m4a"), blob, dek)
        assertEquals(VoiceEncryptOutcome.Failed(VoiceEncryptFailure.SOURCE), missing)

        // SOURCE: over the 40 MB budget
        val over = ByteArray((VoiceNoteCrypto.MAX_BLOB_BYTES + 1L).toInt()) { 4 }
        val bigPlain = File(root, "voice_huge.m4a")
        bigPlain.writeBytes(over)
        val overBlob = File(root, "voice_huge.enc")
        assertEquals(
            VoiceEncryptOutcome.Failed(VoiceEncryptFailure.SOURCE),
            VoiceNoteCrypto.encryptRecordingFileDetailed(bigPlain, overBlob, dek)
        )
        // a source refusal never touches the source (migration dependency)
        assertTrue(bigPlain.exists())

        // BLOB_TARGET: name not a .enc target — plaintext preserved
        val badBlob = File(root, "voice_p4_4.notenc")
        assertEquals(
            VoiceEncryptOutcome.Failed(VoiceEncryptFailure.BLOB_TARGET),
            VoiceNoteCrypto.encryptRecordingFileDetailed(plain, badBlob, dek)
        )
        assertTrue("structural refusal preserves the plaintext", plain.exists())
    }

    @Test
    fun `the boolean API preserves the legacy contract`() {
        val root = tmp.newFolder("root5")
        val plain = File(root, "voice_p5_5.m4a")
        plain.writeBytes(audioBytes)
        val blob = File(root, "voice_p5_5.enc")

        assertTrue(VoiceNoteCrypto.encryptRecordingFile(plain, blob, dek))
        assertTrue(blob.exists())
        assertFalse(plain.exists())
    }

    // ---------------------------------------------------------------------
    // source pins — VoiceNoteManager wires the policy, never leaks plaintext
    // ---------------------------------------------------------------------

    private fun sourceFile(relative: String): String =
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative").readText()

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

    @Test
    fun `finalizeRecording resolves the stop-time key and gates save on it`() {
        val vnm = sourceFile("services/VoiceNoteManager.kt")
        val finalize = vnm.substringAfter("private fun finalizeRecording(limitMessage: String?)")
            .substringBefore("fun startPlayback(")
        assertTrue("stop reads the DEK at stop time", finalize.contains("val dek = VaultKeyHolder.dek"))
        assertTrue("stop resolves through the policy", finalize.contains("VoiceRecordingSavePolicy.resolveStopTimeKey("))
        assertTrue("passwordless re-read uses the device copy, never a mint", finalize.contains("SecurityService.forDevice(context).readDek()"))
        assertTrue("a re-read is synced back into the holder for the save", finalize.contains("stopTimeKey.key?.let { VaultKeyHolder.dek = it }"))
        assertTrue("the gate is the DEK gate", finalize.contains("blobFile != null && dek != null"))
        assertTrue("the save uses the detailed outcome", finalize.contains("VoiceNoteCrypto.encryptRecordingFileDetailed(tempFile, blobFile, dek)"))
        assertTrue("the generic locked wording is gated on the GENUINELY-locked state", finalize.contains("stopTimeKey is VoiceRecordingSavePolicy.StopTimeKey.LockedVault"))
        assertTrue("the original locked string is kept", finalize.contains("\"The recording could not be saved securely. Please try again.\""))
    }

    @Test
    fun `finalizeRecording deletes the plaintext temp on a failed save`() {
        val vnm = sourceFile("services/VoiceNoteManager.kt")
        val finalize = vnm.substringAfter("private fun finalizeRecording(limitMessage: String?)")
            .substringBefore("fun startPlayback(")
        val failBranch = finalize.substringAfter("if (!saved) {").substringBefore("val result = VoiceRecordingResult(")
        assertTrue(
            "the failed-save branch destroys the plaintext temp immediately (B1-DB-3)",
            failBranch.contains("tempFile.delete()")
        )
        assertTrue("the discard flag is kept for the release() notice", failBranch.contains("discardOnRelease = true"))
        assertTrue("the failure message routes through the policy except the locked literal",
            failBranch.contains("VoiceRecordingSavePolicy.messageFor(stopTimeKey, saveOutcome)"))
    }

    @Test
    fun `the cryptor exposes detailed outcomes with ENOSPC classification`() {
        val crypto = sourceFile("services/VoiceNoteCrypto.kt")
        assertTrue("the detailed encrypt exists", crypto.contains("fun encryptRecordingFileDetailed(plaintext: File, blob: File, dek: ByteArray): VoiceEncryptOutcome"))
        assertTrue("the boolean API is a thin delegate", crypto.contains("encryptRecordingFileDetailed(plaintext, blob, dek) is VoiceEncryptOutcome.Saved"))
        assertTrue("failure reasons are explicit", crypto.contains("enum class VoiceEncryptFailure"))
        assertTrue("storage full is distinguished", crypto.contains("ENOSPC"))
        assertTrue("the no-space classifier inspects the IOException", crypto.contains("contains(\"No space left\")"))
        assertTrue("a structural refusal never touches the plaintext source", crypto.contains("plaintext.length() > MAX_BLOB_BYTES"))
    }

    private fun assertNotEqualsSilently(expected: String, actual: String) {
        if (expected == actual) {
            throw AssertionError("expected messages to differ, got equal: '$actual'")
        }
        assertNotNull(actual)
    }
}