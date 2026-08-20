package com.authorss81.noteflow

import com.authorss81.noteflow.services.VoiceNoteCrypto
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
 * B1-DB-3 (phase-54) behavioral + wiring tests for encrypted-at-rest voice
 * notes.
 *
 * Finding: recordings were written straight to `filesDir/voice_notes` as raw
 * MPEG-4/AAC `.m4a` files — a debuggable build's `run-as`/adb or a rooted forensic image
 * read every private memo in cleartext without touching the SQLCipher vault;
 * deleted pages left orphaned plaintext audio behind and no backup carried it
 * (simultaneously unprotected and unrecoverable).
 *
 * What this proves on the pure JVM (no MediaRecorder/MediaPlayer/Room/Context):
 * the cryptor decisions themselves — a recorded-then-stopped voice note leaves
 * NO plaintext `.m4a` on disk (only the AES-GCM `.enc` blob and a transient
 * scratch name), a wrong key / tampered or over-sized blob fails closed, the
 * old→new DEK re-key preserves playback, legacy `.m4a` files migrate to `.enc`
 * without ever deleting a file before its encryption completed, and orphan
 * legacy plaintext is swept. The Android-bound wiring (VoiceNoteManager
 * record/play, NoteRepository deletePagePermanently + one-time migration,
 * ImportExportService export/restore/re-key) is pinned at source level below.
 */
class B1Db03VoiceNoteEncryptionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val dekA = "voice-note-test-dek-A-0000000000000000".toByteArray(Charsets.UTF_8).copyOf(32)
    private val dekB = "voice-note-test-dek-B-0000000000000000".toByteArray(Charsets.UTF_8).copyOf(32)

    // ---- naming classifiers ------------------------------------------------

    @Test
    fun `encrypted blob names are the enc ext and plaintext names are detected`() {
        assertTrue(VoiceNoteCrypto.isEncryptedBlobName("voice_p1_123.enc"))
        assertFalse(VoiceNoteCrypto.isEncryptedBlobName("voice_p1_123.m4a"))
        assertFalse(VoiceNoteCrypto.isEncryptedBlobName("voice_p1_123.m4a.tmp"))
        assertTrue(VoiceNoteCrypto.isPlaintextRecordingName("voice_p1_123.m4a"))
        assertTrue(VoiceNoteCrypto.isPlaintextRecordingName("voice_rec_p1_123.m4a.tmp"))
        assertTrue(VoiceNoteCrypto.isPlaintextRecordingName("voice_pb_123.m4a"))
        assertFalse(VoiceNoteCrypto.isPlaintextRecordingName("voice_p1_123.enc"))
        assertFalse(VoiceNoteCrypto.isPlaintextRecordingName("notes.txt"))
    }

    @Test
    fun `encryptedBlobNameFor retargets a legacy recording to the enc sibling`() {
        assertEquals("voice_p1_123.enc", VoiceNoteCrypto.encryptedBlobNameFor("voice_p1_123.m4a"))
        assertEquals("voice_p1_123.enc", VoiceNoteCrypto.encryptedBlobNameFor("voice_p1_123.m4a.tmp"))
        assertEquals("voice_p1_123.enc", VoiceNoteCrypto.encryptedBlobNameFor("voice_p1_123"))
    }

    @Test
    fun `voice temps are name-scoped so a cache sweep never touches foreign m4a`() {
        assertTrue(VoiceNoteCrypto.isVoiceTempName("voice_rec_p1_123.m4a.tmp"))
        assertTrue(VoiceNoteCrypto.isVoiceTempName("voice_pb_123.m4a"))
        assertFalse(VoiceNoteCrypto.isVoiceTempName("my_music.m4a"))
        assertFalse(VoiceNoteCrypto.isVoiceTempName("voice_p1_123.enc"))
    }

    // ---- record-then-stop produces no plaintext at rest --------------------

    @Test
    fun `audio encrypted at rest decrypts back exactly and the plaintext is gone`() {
        val voiceDir = tmp.newFolder("voice_notes")
        val plain = File(tmp.root, "voice_p1_111.m4a")
        val originalBytes = "FAKE AAC AUDIO BYTES - a whole private memo".toByteArray(Charsets.UTF_8)
        plain.writeBytes(originalBytes)
        val blob = File(voiceDir, "voice_p1_111.enc")

        val ok = VoiceNoteCrypto.encryptRecordingFile(plain, blob, dekA)

        assertTrue(ok)
        assertFalse("recorded-then-stopped note must leave NO plaintext .m4a", plain.exists())
        assertTrue("only the encrypted blob survives at rest", blob.exists())
        assertFalse("blob bytes must not be the plaintext", blob.readBytes().contentEquals(originalBytes))

        val restored = File(tmp.root, "restored.m4a")
        assertTrue(VoiceNoteCrypto.decryptRecordingFile(blob, restored, dekA))
        assertTrue(restored.exists())
        assertArrayEquals("decrypted audio must round-trip byte-for-byte", originalBytes, restored.readBytes())
    }

    @Test
    fun `wrong dek fails closed and does not leave a plaintext scratch file`() {
        val voiceDir = tmp.newFolder("voice_notes")
        val plain = File(tmp.root, "voice_p1_222.m4a").apply { writeBytes("memo-two".toByteArray(Charsets.UTF_8)) }
        val blob = File(voiceDir, "voice_p1_222.enc")
        assertTrue(VoiceNoteCrypto.encryptRecordingFile(plain, blob, dekA))

        val out = File(tmp.root, "restored2.m4a")
        assertFalse("wrong key must not play (no plaintext materialized)", VoiceNoteCrypto.decryptRecordingFile(blob, out, dekB))
        assertFalse("failed decrypt removes the scratch destination", out.exists())
    }

    @Test
    fun `encrypt failure keeps the plaintext untouched`() {
        val voiceDir = tmp.newFolder("voice_notes")
        val plain = File(tmp.root, "voice_p1_333.m4a")
        plain.writeBytes("memo-three".toByteArray(Charsets.UTF_8))
        val blob = File(voiceDir, "voice_p1_333.enc")

        // Naming the blob like a non-blob forces the AAD classifier off; the
        // plaintext source must survive (never destroyed when encryption fails).
        val badBlob = File(voiceDir, "voice_p1_333.notenc")
        assertFalse(VoiceNoteCrypto.encryptRecordingFile(plain, badBlob, dekA))
        assertTrue("plaintext stays if the blob itself is invalid", plain.exists())
    }

    @Test
    fun `oversized blob is refused and never buffered`() {
        val voiceDir = tmp.newFolder("voice_notes")
        val big = File(voiceDir, "voice_big.enc")
        big.writeBytes(ByteArray((VoiceNoteCrypto.MAX_BLOB_BYTES + 1).toInt()) { 7 })
        val out = File(tmp.root, "restored_big.m4a")
        assertFalse("over-budget blob is refused", VoiceNoteCrypto.decryptRecordingFile(big, out, dekA))
        assertFalse(out.exists())
    }

    // ---- re-key (cross-device restore) --------------------------------------

    @Test
    fun `rekey rewrites the blob in place from the old to the new dek`() {
        val voiceDir = tmp.newFolder("voice_notes")
        val plain = File(tmp.root, "voice_p1_444.m4a").apply { writeBytes("cross-device-memo".toByteArray(Charsets.UTF_8)) }
        val blob = File(voiceDir, "voice_p1_444.enc")
        assertTrue(VoiceNoteCrypto.encryptRecordingFile(plain, blob, dekA))

        assertTrue(VoiceNoteCrypto.reencryptAudioBlobInPlace(blob, dekA, dekB))

        val oldKeyOut = File(tmp.root, "oldkey.m4a")
        assertFalse("decrypting under the OLD key must now fail", VoiceNoteCrypto.decryptRecordingFile(blob, oldKeyOut, dekA))
        val newKeyOut = File(tmp.root, "newkey.m4a")
        assertTrue("decrypting under the NEW key succeeds", VoiceNoteCrypto.decryptRecordingFile(blob, newKeyOut, dekB))
        assertArrayEquals("cross-device restored audio round-trips under the new dek", "cross-device-memo".toByteArray(Charsets.UTF_8), newKeyOut.readBytes())
    }

    // ---- legacy migration ----------------------------------------------------

    @Test
    fun `legacy m4a migrates to an enc sibling and the plaintext is deleted only after encryption`() {
        val voiceDir = tmp.newFolder("voice_notes")
        val legacy = File(voiceDir, "voice_old_999.m4a")
        val memo = "a pre-fix plaintext memo".toByteArray(Charsets.UTF_8)
        legacy.writeBytes(memo)

        val blob = VoiceNoteCrypto.migrateLegacyRecordingFile(legacy, dekA)

        assertNotNull(blob)
        assertEquals("blob rides beside the legacy file with the .enc extension", File(voiceDir, "voice_old_999.enc"), blob)
        assertFalse("encrypted migration deletes the plaintext", legacy.exists())
        assertTrue(blob!!.exists())

        val out = File(tmp.root, "legacy_restored.m4a")
        assertTrue(VoiceNoteCrypto.decryptRecordingFile(blob, out, dekA))
        assertArrayEquals(memo, out.readBytes())
    }

    @Test
    fun `legacy migration failure keeps the plaintext for a later unlock`() {
        val voiceDir = tmp.newFolder("voice_notes")
        val legacy = File(voiceDir, "voice_locked_555.m4a")
        legacy.writeBytes("memo-kept".toByteArray(Charsets.UTF_8))

        // A locked vault would hand a zeroized/null key — simulate by migrating
        // with a wrong-seeming call (identity check only happens at the repo);
        // here we simply verify the file is untouched by a failed attempt using an
        // empty DEK array is meaningless, so instead force failure through the
        // blob-name guard by removing write permissions is not pure-JVM friendly;
        // use an empty (zero-length) key — decryption/encryption will throw.
        val zeroKey = ByteArray(0)
        assertNull("a failed migration returns null", VoiceNoteCrypto.migrateLegacyRecordingFile(legacy, zeroKey))
        assertTrue("the plaintext survives any failed migration (never delete before encrypt)", legacy.exists())
        assertFalse("no partial blob is left behind", File(voiceDir, "voice_locked_555.enc").exists())
    }

    @Test
    fun `orphan plaintext is swept but referenced-or-pending files are kept`() {
        val voiceDir = tmp.newFolder("voice_notes")
        File(voiceDir, "voice_orphan_1.m4a").writeBytes("orphan1".toByteArray(Charsets.UTF_8))
        File(voiceDir, "voice_orphan_2.m4a").writeBytes("orphan2".toByteArray(Charsets.UTF_8))
        val kept = File(voiceDir, "voice_kept.m4a").apply { writeBytes("kept".toByteArray(Charsets.UTF_8)) }
        File(voiceDir, "voice_kept.m4a.tmp").writeBytes("temp".toByteArray(Charsets.UTF_8))

        val deleted = VoiceNoteCrypto.deleteOrphanPlaintext(voiceDir, setOf(kept.absolutePath))

        assertEquals("two orphans + one stale recording temp removed", 3, deleted)
        assertFalse(File(voiceDir, "voice_orphan_1.m4a").exists())
        assertFalse(File(voiceDir, "voice_orphan_2.m4a").exists())
        assertFalse(File(voiceDir, "voice_kept.m4a.tmp").exists())
        assertTrue("a retained/pending row's plaintext is never swept", kept.exists())
    }

    @Test
    fun `cache sweep removes only voice scratch files`() {
        val cache = tmp.newFolder("cache")
        File(cache, "voice_pb_123.m4a").writeBytes("pb".toByteArray(Charsets.UTF_8))
        File(cache, "voice_rec_p1_123.m4a.tmp").writeBytes("rec".toByteArray(Charsets.UTF_8))
        val foreign = File(cache, "song.m4a").apply { writeBytes("song".toByteArray(Charsets.UTF_8)) }

        val deleted = VoiceNoteCrypto.sweepPlaintextTemps(cache)

        assertEquals(2, deleted)
        assertFalse(File(cache, "voice_pb_123.m4a").exists())
        assertFalse(File(cache, "voice_rec_p1_123.m4a.tmp").exists())
        assertTrue("a non-voice m4a in cache is never touched", foreign.exists())
    }

    // ---- source-level wiring pins -------------------------------------------

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
    fun `VoiceNoteManager records to a temp and only ever persists an encrypted blob`() {
        val vm = sourceFile("services/VoiceNoteManager.kt")

        val record = vm.substringBefore("fun stopRecording")
        assertTrue("MediaRecorder streams to the cacheDir temp, not the voice dir", record.contains("File(context.cacheDir, \"voice_rec_${'$'}{pageId}_${'$'}{stamp}.m4a.tmp\")"))
        val voiceDirArtifacts = Regex("File\\(voiceDir, \"([^\"]*)\"\\)").findAll(record).map { it.groupValues[1] }.toList()
        assertTrue("record constructs the at-rest artifact in the voice dir", voiceDirArtifacts.isNotEmpty())
        for (artifact in voiceDirArtifacts) {
            assertTrue("the only voiceDir artifact is the .enc blob, got: $artifact", artifact.contains("VoiceNoteCrypto.ENCRYPTED_EXTENSION"))
        }

        val stop = vm.substringAfter("fun stopRecording").substringBefore("fun startPlayback")
        assertTrue("stop encrypts the finished AAC with the DEK", stop.contains("VoiceNoteCrypto.encryptRecordingFileDetailed(tempFile, blobFile, dek)"))
        assertTrue("stop reads the DEK at stop time (locked -> fails closed)", stop.contains("VaultKeyHolder.dek"))
        assertFalse("a failed encryption must NEVER return a result (plaintext destroyed)", stop.contains("filePath = tempFile"))
        assertTrue("the result path is the blob path", stop.contains("filePath = blobFile!!.absolutePath"))

        val play = vm.substringAfter("fun startPlayback").substringBefore("fun pausePlayback")
        assertTrue("playback decrypts the .enc blob to a transient cache temp", play.contains("VoiceNoteCrypto.decryptRecordingFile(blob, tempPlayback, dek)"))
        assertTrue("playback refuses non-blob paths", play.contains("VoiceNoteCrypto.isEncryptedBlobName(blob.name)"))
        assertTrue("playback temp is deleted when playback stops", vm.contains("fun deletePlaybackTemp"))
    }

    @Test
    fun `page permanent delete removes the page's voice recordings`() {
        val repo = sourceFile("data/repository/NoteRepository.kt")
        val del = repo.substringAfter("suspend fun deletePagePermanently").substringBefore("suspend fun migrateLegacyPlaintextVoiceNotes")

        assertTrue("the AUDIO_NOTE embeds are queried before the embeds rows are dropped", del.contains("getMediaEmbedsForPage(id)"))
        assertTrue("only AUDIO_NOTE embeds' files are deleted", del.contains("MediaEmbedType.AUDIO_NOTE.name"))
        assertTrue(
            "voice files are removed via the encrypted-blob classifier (plaintext too)",
            del.contains("VoiceNoteCrypto.isEncryptedBlobName(File(audioPath).name)")
        )
        assertTrue("audio files are deleted BEFORE the media_embeds rows are dropped", del.indexOf("try { File(audioPath).delete() }") < del.indexOf("deleteMediaEmbedsForPage(id)"))
    }

    @Test
    fun `one-time migration encrypts referenced m4a and retargets the rows`() {
        val repo = sourceFile("data/repository/NoteRepository.kt")
        val migration = repo.substringAfter("suspend fun migrateLegacyPlaintextVoiceNotes").substringBefore("    suspend fun emptyTrash")

        assertTrue("only AUDIO_NOTE rows with plaintext .m4a paths are considered", migration.contains("MediaEmbedType.AUDIO_NOTE.name"))
        assertTrue("a locked vault defers instead of dropping the file", migration.contains("retainedPlaintext.add(legacyPath)"))
        assertTrue("the legacy file is encrypted to its .enc sibling", migration.contains("VoiceNoteCrypto.migrateLegacyRecordingFile(legacyFile, dek)"))
        assertTrue("the embed row is retargeted to the blob path", migration.contains("db.mediaEmbedDao().updateContentUrlOrPath(embed.id, blob.absolutePath)"))
        assertTrue("orphan plaintext with no row is swept", migration.contains("VoiceNoteCrypto.deleteOrphanPlaintext(it, retainedPlaintext)"))
        assertTrue("the outcome is surfaced so the flag can gate re-runs", migration.contains("VoiceNoteMigrationResult("))
    }

    @Test
    fun `settings flag and unlock hook drive the migration`() {
        val settings = sourceFile("services/SettingsManager.kt")
        assertTrue(settings.contains("var voiceNotesEncryptedMigrated: Boolean"))
        assertTrue(settings.contains("voice_notes_encrypted_migrated"))

        val vm = sourceFile("ui/viewmodel/NoteflowViewModel.kt")
        assertTrue("the hook is gated on the flag", vm.contains("if (!settings.voiceNotesEncryptedMigrated)"))
        assertTrue("the hook sweeps stale plaintext temps then runs the migration", vm.contains("VoiceNoteCrypto.sweepPlaintextTemps(appContext.cacheDir)"))
        assertTrue(vm.contains("repository.migrateLegacyPlaintextVoiceNotes()"))
        assertTrue("WAL flush + HMAC re-stamp follow the row mutations", vm.contains("repository.checkpointWal()"))
    }

    @Test
    fun `backup carries only encrypted blobs and restore re-keys them per device`() {
        val ies = sourceFile("services/ImportExportService.kt")

        val export = ies.substringAfter("suspend fun exportBackup").substringBefore("private fun copyWithLimit")
        assertTrue("encrypted voice blobs ride in the backup", export.contains("File(context.filesDir, \"voice_notes\")"))
        assertTrue("only .enc blobs are packed", export.contains("VoiceNoteCrypto.isEncryptedBlobName(it.name)"))
        assertTrue("the voice packing entry path is the voice_notes layout", export.contains("\"voice_notes/\${file.name}\""))

        val extract = ies.substringAfter("private fun extractBackupEntriesTo").substringBefore("if (!sawDatabase)")
        assertTrue("voice_notes zip entries are extracted", extract.contains("entryName.startsWith(\"voice_notes/\")"))
        assertTrue("extraction reuses the traversal/zip-bomb guards", extract.contains("safeImportRelativePath(entryName.substring(\"voice_notes/\".length))"))

        val commit = ies.substringAfter("private fun commitRestoredFiles").substringBefore("private fun String.fromHex")
        assertTrue("restored voice blobs swap into filesDir/voice_notes", commit.contains("File(context.filesDir, \"voice_notes\")"))

        val validate = ies.substringAfter("private fun validateAndPrepareRestoredDb").substringBefore("private fun rekeySqlcipherDb")
        assertTrue("cross-device restore re-keys the voice blobs", validate.contains("rekeyVoiceNoteBlobs(tempVoiceNotes, openedWith.fromHex(), currentDekHex.fromHex())"))
        assertTrue("re-key is gated on a real DEK change", validate.contains("openedWith != currentDekHex"))
    }

    @Test
    fun `login-sensitive names never reveal the recording in logs`() {
        val vm = sourceFile("services/VoiceNoteManager.kt")
        // Logs were already path-free; assert no absolute path ever reaches Log.e/w
        val logLines = Regex("Log\\.(e|w)\\([^)]*\\)").findAll(vm).map { it.value }.toList()
        for (line in logLines) {
            assertFalse("log lines must not print the recording path: $line", line.contains("voice_"))
        }
    }
}