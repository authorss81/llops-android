package com.authorss81.noteflow.services

import java.io.File

/**
 * Fine-grained outcome of an encrypt-a-recording attempt (phase-192). Lets the
 * caller distinguish recoverable conditions (storage full, transient I/O/JCE)
 * from structural refusals so the surface message is truthful instead of the
 * one-size-fits-all "could not be saved securely".
 */
sealed interface VoiceEncryptOutcome {
    /** The blob was fully written and the plaintext source is gone. */
    object Saved : VoiceEncryptOutcome

    data class Failed(val reason: VoiceEncryptFailure) : VoiceEncryptOutcome
}

/** Why an [encryptRecordingFileDetailed] attempt failed. */
enum class VoiceEncryptFailure {
    /** Source plaintext missing or over the [VoiceNoteCrypto.MAX_BLOB_BYTES] budget. */
    SOURCE,
    /** The blob name is not a real `.enc` target (would never decrypt). */
    BLOB_TARGET,
    /** Storage full ("No space left on device"). */
    ENOSPC,
    /** Any other I/O or JCE failure. */
    IO_OR_CIPHER,
}

/**
 * B1-DB-3 (phase-54): the voice-note audio cryptor.
 *
 * Voice recordings used to be written straight to `filesDir/voice_notes` as
 * plaintext MPEG-4/AAC `.m4a` — a debuggable build's `run-as`/adb or a rooted
 * forensic image read every private memo without touching the SQLCipher vault.
 * Since phase-54 the ONLY bytes stored at rest under `voice_notes/` are
 * AES-256-GCM blobs (`.enc`) encrypted with the vault DEK; the raw AAC exists
 * on disk only while a recording is actively in progress (MediaRecorder must
 * stream to a real file) via a transient cacheDir temp that is encrypted and
 * deleted at stop.
 *
 * Wire format is the same versioned payload as the field/backup layer:
 * `[PAYLOAD_VERSION][12-byte IV][ciphertext + GCM tag]` (see
 * [EncryptionService.encryptAad]), authenticated under the AAD
 * `Noteflow-Voice-Note-v1|<blob file name>` so a blob can never be relocated to
 * another file name, and so every decrypt is bound to the exact key the
 * recording was encrypted with.
 *
 * Pure JVM (android.util.Base64 only lives in the EncryptionService layer that
 * this object delegates to, and the object itself touches nothing Android) so
 * every decision table here is directly unit-testable —
 * see `B1Db03VoiceNoteEncryptionTest`.
 */
object VoiceNoteCrypto {
    /** Extension of the at-rest encrypted audio blob (`voice_<pageId>_<ts>.enc`). */
    const val ENCRYPTED_EXTENSION = "enc"

    /**
     * Hard bound on a single voice blob. AAC @ 128 kbps ≈ 1 MB/min, so 40 MB
     * is ~5 hours of audio — far beyond any real memo, and it caps the
     * memory/disk cost of a crafted oversized blob (B2-DOS-01 symmetry: no
     * unbounded `readBytes` on attacker-influenced bytes).
     */
    const val MAX_BLOB_BYTES = 40L * 1024 * 1024

    private const val AAD_PREFIX = "Noteflow-Voice-Note-v1|"

    /** The AAD binds every blob to its file name (blobs are never renamed). */
    private fun aadFor(blobName: String): ByteArray = (AAD_PREFIX + blobName).toByteArray(Charsets.UTF_8)

    /** True iff a file name is an at-rest encrypted voice blob (`*.enc`). */
    fun isEncryptedBlobName(fileName: String): Boolean =
        fileName.endsWith(".$ENCRYPTED_EXTENSION")

    /**
     * True iff a file name is a PLAINTEXT voice recording: a final legacy
     * `.m4a` (pre-phase-54) or a `.m4a` / `*.m4a.tmp` / `*.tmp.m4a` recording
     * temp that must never survive at rest.
     */
    fun isPlaintextRecordingName(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.endsWith(".m4a") ||
            lower.endsWith(".m4a.tmp") ||
            lower.endsWith(".tmp.m4a") ||
            lower.endsWith(".tmp") && lower.contains(".m4a")
    }

    /**
     * True iff a file name is a VOICE temp (transient recording or playback
     * scratch written to cacheDir by VoiceNoteManager). Stricter than
     * [isPlaintextRecordingName] so a cache-dir sweep never touches a
     * non-voice `.m4a` owned by another subsystem.
     */
    fun isVoiceTempName(fileName: String): Boolean {
        val lower = fileName.lowercase()
        return lower.startsWith("voice_rec_") || lower.startsWith("voice_pb_") ||
            lower.endsWith(".m4a.tmp") || lower.endsWith(".tmp.m4a")
    }

    /** `voice_<page>_<ts>.m4a` -> `voice_<page>_<ts>.enc` (AAD-stable rename). */
    fun encryptedBlobNameFor(legacyName: String): String {
        val trimmed = legacyName.removeSuffix(".m4a.tmp").removeSuffix(".tmp.m4a")
            .removeSuffix(".m4a").removeSuffix(".tmp")
        return if (trimmed.isEmpty()) legacyName + ".$ENCRYPTED_EXTENSION"
        else trimmed + ".$ENCRYPTED_EXTENSION"
    }

    /**
     * Encrypts a finished plaintext AAC recording into an `.enc` blob and
     * deletes the plaintext. AAD is bound to [blob]'s name. The target must be
     * a real `.enc` blob name (a blob with any other name could never decrypt,
     * mirroring [decryptRecordingFile]'s guard). Returns true only when the
     * blob was fully written and the plaintext source is gone.
     */
    fun encryptRecordingFile(plaintext: File, blob: File, dek: ByteArray): Boolean =
        encryptRecordingFileDetailed(plaintext, blob, dek) is VoiceEncryptOutcome.Saved

    /**
     * Detailed variant of [encryptRecordingFile] (phase-192): returns why a
     * failed attempt failed so the caller can surface a truthful, non-alarming
     * message (storage full vs transient I/O/JCE vs structural refusal). The
     * same fail-closed semantics as the boolean form — a partial blob is always
     * deleted, and a structural refusal (`SOURCE`, `BLOB_TARGET`) never touches
     * the plaintext source (migration path dependency).
     */
    fun encryptRecordingFileDetailed(plaintext: File, blob: File, dek: ByteArray): VoiceEncryptOutcome {
        if (!plaintext.isFile || plaintext.length() > MAX_BLOB_BYTES) {
            return VoiceEncryptOutcome.Failed(VoiceEncryptFailure.SOURCE)
        }
        if (!isEncryptedBlobName(blob.name)) {
            return VoiceEncryptOutcome.Failed(VoiceEncryptFailure.BLOB_TARGET)
        }
        return try {
            val bytes = plaintext.readBytes()
            val combined = EncryptionService.encryptAad(bytes, dek, aadFor(blob.name))
            bytes.fill(0.toByte())
            blob.parentFile?.mkdirs()
            blob.writeBytes(combined)
            combined.fill(0.toByte())
            if (!plaintext.delete()) {
                // Prefer a hard failure over leaving plaintext behind: if the
                // source cannot be removed the blob is not usable (the temp
                // would linger unencrypted).
                blob.delete()
                return VoiceEncryptOutcome.Failed(VoiceEncryptFailure.IO_OR_CIPHER)
            }
            VoiceEncryptOutcome.Saved
        } catch (e: Exception) {
            try { blob.delete() } catch (_: Exception) {}
            VoiceEncryptOutcome.Failed(classifyException(e))
        }
    }

    /** Storage-full ("No space left on device") vs everything else. */
    private fun classifyException(e: Exception): VoiceEncryptFailure =
        if (e is java.io.IOException && (e.message?.contains("No space left") == true)) {
            VoiceEncryptFailure.ENOSPC
        } else {
            VoiceEncryptFailure.IO_OR_CIPHER
        }

    /**
     * Decrypts an `.enc` blob into a transient plaintext file for playback.
     * Never called on a blob larger than [MAX_BLOB_BYTES]; a tag failure
     * (wrong key / tampered / renamed) fails closed and the destination is
     * removed.
     */
    fun decryptRecordingFile(blob: File, destination: File, dek: ByteArray): Boolean {
        if (!blob.isFile || !isEncryptedBlobName(blob.name)) return false
        if (blob.length() > MAX_BLOB_BYTES) return false
        return try {
            val combined = blob.readBytes()
            val plain = EncryptionService.decryptAad(combined, dek, aadFor(blob.name))
            combined.fill(0.toByte())
            destination.parentFile?.mkdirs()
            destination.writeBytes(plain)
            plain.fill(0.toByte())
            true
        } catch (e: Exception) {
            try { destination.delete() } catch (_: Exception) {}
            false
        }
    }

    /**
     * Re-keys a single `.enc` blob from [oldDek] to [newDek] in place — used
     * by the cross-device backup restore path where the vault DEK changes and
     * the migrated `media_embeds` rows (which are field-re-encrypted too)
     * must keep playing under the new key. Returns true only when the blob was
     * rewritten under the new DEK.
     */
    fun reencryptAudioBlobInPlace(blob: File, oldDek: ByteArray, newDek: ByteArray): Boolean {
        if (!blob.isFile || !isEncryptedBlobName(blob.name)) return false
        if (blob.length() > MAX_BLOB_BYTES) return false
        return try {
            val combined = blob.readBytes()
            val plain = EncryptionService.decryptAad(combined, oldDek, aadFor(blob.name))
            combined.fill(0.toByte())
            val recombined = EncryptionService.encryptAad(plain, newDek, aadFor(blob.name))
            plain.fill(0.toByte())
            blob.writeBytes(recombined)
            recombined.fill(0.toByte())
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * One-time migration entry for a legacy plaintext `.m4a` recording:
     * encrypts it to `<name>.enc` in the same directory. On success the
     * plaintext is deleted and the new blob file is returned; on any failure
     * (missing DEK, corrupt bytes, IO) the plaintext is PRESERVED and null is
     * returned so the caller re-tries on a later unlock — a plaintext file is
     * never destroyed when its encryption did not complete (same invariant as
     * the phase-44 note-body migration).
     */
    fun migrateLegacyRecordingFile(legacyFile: File, dek: ByteArray): File? {
        val blob = File(legacyFile.parentFile, encryptedBlobNameFor(legacyFile.name))
        return if (encryptRecordingFile(legacyFile, blob, dek)) blob else null
    }

    /**
     * Deletes any plaintext `.m4a`/recording-temp file under [dir] that is NOT
     * in [retainedReferencedPaths] (absolute paths of rows whose migration is
     * still pending / whose legacy file failed to encrypt). Pre-fix crashes
     * could leave orphaned plaintext recordings in the voice dir that no DB
     * row references any more; those must not outlive the sweep. Returns the
     * number of files deleted.
     */
    fun deleteOrphanPlaintext(dir: File, retainedReferencedPaths: Set<String>): Int {
        if (!dir.isDirectory) return 0
        var deleted = 0
        for (file in dir.listFiles()?.filter { it.isFile } ?: emptyList()) {
            val name = file.name
            if (!isPlaintextRecordingName(name)) continue
            if (file.absolutePath in retainedReferencedPaths) continue
            try {
                if (file.delete()) deleted++
            } catch (e: Exception) {
                // force deletion is out of scope; count as not deleted
            }
        }
        return deleted
    }

    /**
     * Removes stale transient plaintext audio (interrupted recordings / lived
     * playback temps) from a cacheDir recording/playback scratch area. The
     * scratch dir should only ever contain `voice_*` `.m4a`/`.tmp` files —
     * anything matching [isVoiceTempName] there is expendable.
     */
    fun sweepPlaintextTemps(dir: File): Int {
        if (!dir.isDirectory) return 0
        var deleted = 0
        for (file in dir.listFiles()?.filter { it.isFile } ?: emptyList()) {
            if (!isVoiceTempName(file.name)) continue
            try {
                if (file.delete()) deleted++
            } catch (e: Exception) {
                // nothing to do — a later sweep retries
            }
        }
        return deleted
    }
}