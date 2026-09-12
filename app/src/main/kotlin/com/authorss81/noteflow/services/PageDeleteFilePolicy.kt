package com.authorss81.noteflow.services

import java.io.File

/**
 * Phase-260: the single decision table for which on-disk files a page delete
 * may remove.
 *
 * The pre-fix `deletePagePermanently` decided by substring
 * (`path.contains("imports/") || path.contains("exports/")`) — a stored
 * `sourceFilePath` of `/tmp/evil/imports/x` or `…/exports/../vault` passed the
 * gate, while a legitimate confined file reached `File.delete()` with no
 * canonicalization at all. Voice-blob deletes had no directory gate either:
 * any absolute path stored in an AUDIO_NOTE embed's `contentUrlOrPath` was
 * deleted when the name merely ended in `.enc`.
 *
 * Both decisions are now confinement checks, pure JVM so the contract is
 * unit-testable on the host (see `Phase260StorageTest`):
 *  - a page's `sourceFilePath` may be deleted only when
 *    [SourceFilePathPolicy.confine] resolves it strictly inside the app-private
 *    imports root (blank/relative/`..`-traversing/outside-root values yield
 *    null — never deleted);
 *  - a voice-blob path may be deleted only when its file NAME is a real
 *    at-rest audio name (encrypted `.enc` blob or legacy plaintext recording
 *    name, per [VoiceNoteCrypto]) AND its canonical path sits strictly inside
 *    the app-private `voice_notes` dir — a crafted embed row pointing at an
 *    arbitrary `.enc`-suffixed file elsewhere is never deleted.
 */
object PageDeleteFilePolicy {

    /**
     * Resolves the page source file that a permanent delete may remove: the
     * canonical confined path, or null when the stored value must not be
     * touched. Callers delete the returned file AFTER the DB transaction
     * commits (files first + crash = dangling rows; DB first + crash = an
     * orphaned file the next sweep can reclaim — the safe order).
     */
    fun sourceFileForDelete(storedPath: String?, importsRoot: File?): File? {
        val confined = SourceFilePathPolicy.confine(storedPath, importsRoot) ?: return null
        return File(confined)
    }

    /**
     * Resolves a voice-blob file that a permanent delete may remove: the
     * canonical in-voice-dir path, or null when the stored value must not be
     * touched (wrong name shape, or escaping the voice dir).
     */
    fun voiceBlobForDelete(storedPath: String?, voiceDir: File?): File? {
        val raw = storedPath
        val dir = voiceDir ?: return null
        if (raw.isNullOrBlank()) return null
        if (!dir.isDirectory) return null
        val name = File(raw).name
        if (!VoiceNoteCrypto.isEncryptedBlobName(name) &&
            !VoiceNoteCrypto.isPlaintextRecordingName(name)
        ) {
            return null
        }
        return try {
            val rootCanonical = dir.canonicalPath
            val candidateCanonical = File(raw).canonicalPath
            if (candidateCanonical.length > rootCanonical.length &&
                candidateCanonical.startsWith(rootCanonical + File.separator)
            ) {
                File(candidateCanonical)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
