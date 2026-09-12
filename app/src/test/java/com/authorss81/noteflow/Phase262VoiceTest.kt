package com.authorss81.noteflow

import com.authorss81.noteflow.services.PageDeleteFilePolicy
import com.authorss81.noteflow.services.VoiceNoteCrypto
import com.authorss81.noteflow.services.VoiceRecordingPolicy
import com.authorss81.noteflow.services.WaveformPeakMath
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 262 — voice ANR + clock-skew + orphan plaintext + OOM reads.
 *
 * Source pins (fail if the fix regresses) + JVM behavior over the real
 * pure-JVM decision tables (PageDeleteFilePolicy, VoiceNoteCrypto streaming
 * round-trip).
 */
class Phase262VoiceTest {

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) return dir
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }

    private fun read(path: String): String = File(repoRoot(), path).readText()

    private fun manager(): String =
        read("app/src/main/kotlin/com/authorss81/noteflow/services/VoiceNoteManager.kt")

    private fun crypto(): String =
        read("app/src/main/kotlin/com/authorss81/noteflow/services/VoiceNoteCrypto.kt")

    private fun repository(): String =
        read("app/src/main/kotlin/com/authorss81/noteflow/data/repository/NoteRepository.kt")

    private fun card(): String =
        read("app/src/main/kotlin/com/authorss81/noteflow/ui/components/AudioPlaybackCard.kt")

    // ---------- CRITICAL: no blocking prepare() on Main ----------

    @Test
    fun `playback uses prepareAsync never blocking prepare`() {
        val vnm = manager()
        assertTrue("playback must use prepareAsync (ANR fix)", vnm.contains("prepareAsync()"))
        assertFalse(
            "blocking prepare() on the Main scope ANRs on 2-core devices",
            vnm.contains(".prepare()")
        )
    }

    // ---------- HIGH: monotonic clock drives duration ----------

    @Test
    fun `recording elapsed uses elapsedRealtime not wall clock`() {
        val vnm = manager()
        assertTrue(vnm.contains("elapsedRealtime"))
        assertTrue(vnm.contains("voiceMonotonicNowMs() - startTime"))
        assertFalse(
            "wall-clock delta bypasses the ceiling on NTP/user steps",
            vnm.contains("System.currentTimeMillis() - startTime")
        )
    }

    // ---------- HIGH: delete covers legacy plaintext .m4a ----------

    @Test
    fun `voice delete policy covers legacy plaintext m4a inside the voice dir`() {
        val voiceDir = Files.createTempDirectory("voice262").toFile()
        try {
            val legacy = File(voiceDir, "voice_p1_99.m4a").apply { writeText("raw") }
            assertNotNull(
                "legacy voice_*.m4a must be removed with the page (B1-DB-3)",
                PageDeleteFilePolicy.voiceBlobForDelete(legacy.absolutePath, voiceDir)
            )
            val blob = File(voiceDir, "voice_p1_99.enc").apply { writeText("enc") }
            assertNotNull(PageDeleteFilePolicy.voiceBlobForDelete(blob.absolutePath, voiceDir))
            assertTrue(
                "delete path must consult both name shapes",
                read("app/src/main/kotlin/com/authorss81/noteflow/services/PageDeleteFilePolicy.kt")
                    .contains("isPlaintextRecordingName")
            )
        } finally {
            voiceDir.deleteRecursively()
        }
    }

    @Test
    fun `voice delete still refuses escapes and non-audio names`() {
        val voiceDir = Files.createTempDirectory("voice262b").toFile()
        val outside = Files.createTempDirectory("voice262out").toFile()
        try {
            assertNull(
                PageDeleteFilePolicy.voiceBlobForDelete(
                    File(outside, "voice_p1_99.m4a").absolutePath, voiceDir
                )
            )
            assertNull(
                PageDeleteFilePolicy.voiceBlobForDelete(
                    File(voiceDir, "notes.txt").absolutePath, voiceDir
                )
            )
        } finally {
            voiceDir.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    // ---------- MEDIUM: streaming crypto (no whole-file readBytes) ----------

    @Test
    fun `crypto streams through Cipher update without whole-file reads`() {
        val c = crypto()
        assertTrue(c.contains("cipher.update") || c.contains("Cipher.update"))
        assertTrue(c.contains("STREAM_BUFFER_BYTES"))
        assertFalse("encrypt must not readBytes the whole recording", c.contains("plaintext.readBytes()"))
        assertFalse("decrypt must not readBytes the whole blob", c.contains("blob.readBytes()"))
        assertFalse("re-key must not readBytes the whole blob", c.contains("combined = blob.readBytes()"))
    }

    @Test
    fun `streaming encrypt decrypt round-trips`() {
        val dir = Files.createTempDirectory("voice262crypto").toFile()
        try {
            val dek = ByteArray(32) { (it * 7 + 3).toByte() }
            val plain = File(dir, "rec.m4a").apply {
                writeBytes(ByteArray(257 * 1024) { (it % 251).toByte() })
            }
            val blob = File(dir, "voice_p1_1.enc")
            val outcome = VoiceNoteCrypto.encryptRecordingFileDetailed(plain, blob, dek)
            assertTrue(outcome is com.authorss81.noteflow.services.VoiceEncryptOutcome.Saved)
            assertTrue(blob.isFile)
            assertFalse("plaintext temp must be destroyed on save", plain.exists())
            val restored = File(dir, "out.m4a")
            assertTrue(VoiceNoteCrypto.decryptRecordingFile(blob, restored, dek))
            assertEquals(257 * 1024L, restored.length())
            // Re-key under a new DEK keeps the bytes playable.
            val newDek = ByteArray(32) { (it * 13 + 1).toByte() }
            assertTrue(VoiceNoteCrypto.reencryptAudioBlobInPlace(blob, dek, newDek))
            val restored2 = File(dir, "out2.m4a")
            assertTrue(VoiceNoteCrypto.decryptRecordingFile(blob, restored2, newDek))
            assertEquals(257 * 1024L, restored2.length())
            assertFalse(
                "old key must no longer open the re-keyed blob",
                VoiceNoteCrypto.decryptRecordingFile(blob, File(dir, "out3.m4a"), dek)
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    // ---------- MEDIUM: native caps + permission + focus + names ----------

    @Test
    fun `recorder sets native ceilings and gates RECORD_AUDIO in-manager`() {
        val vnm = manager()
        assertTrue(vnm.contains("setMaxDuration"))
        assertTrue(vnm.contains("setMaxFileSize"))
        assertTrue(vnm.contains("MEDIA_RECORDER_INFO_MAX_DURATION_REACHED"))
        assertTrue(vnm.contains("Manifest.permission.RECORD_AUDIO"))
        assertTrue(vnm.contains("checkSelfPermission"))
    }

    @Test
    fun `playback requests audio focus and pauses on becoming noisy`() {
        val vnm = manager()
        assertTrue(vnm.contains("AudioFocusRequest"))
        assertTrue(vnm.contains("requestAudioFocus"))
        assertTrue(vnm.contains("abandonAudioFocus"))
        assertTrue(vnm.contains("ACTION_AUDIO_BECOMING_NOISY"))
    }

    @Test
    fun `cache names are unpredictable and playback is confined`() {
        val vnm = manager()
        assertTrue(vnm.contains("UUID.randomUUID()"))
        assertTrue(vnm.contains("isConfinedVoiceBlob"))
        assertFalse(
            "predictable voice_pb_<ms>.m4a lets a same-uid reader guess scratch paths",
            vnm.contains("voice_pb_\${System.currentTimeMillis()}")
        )
    }

    @Test
    fun `KeyStore and encrypt run outside the recorder lock`() {
        val vnm = manager()
        assertTrue("fast capture snapshot runs under the lock", vnm.contains("FinalizeSnapshot"))
        assertTrue(vnm.contains("synchronized(recorderLock)"))
        // The heavy DEK re-read must not sit inside the synchronized block:
        // the snapshot return closes the lock before resolveStopTimeKey runs.
        val lockClose = vnm.indexOf("} ?: return null")
        val keyRead = vnm.indexOf("resolveStopTimeKey")
        assertTrue(lockClose >= 0 && keyRead > lockClose)
    }

    // ---------- MEDIUM: waveform persist bounded + finite ----------

    @Test
    fun `waveform persist is capped and finite-filtered`() {
        val repo = repository()
        assertTrue(repo.contains("MAX_STORED_WAVEFORM_ENTRIES"))
        assertTrue(repo.contains("WaveformPeakMath.finiteOrZero(it)"))
        // Cap+FILTER must feed the stored joinToString (not a bare persist).
        val persistAt = repo.indexOf(".joinToString(prefix = \"[\", postfix = \"]\")")
        assertTrue(persistAt >= 0)
        val window = repo.substring(maxOf(0, persistAt - 400), persistAt)
        assertTrue(window.contains("take("))
    }

    @Test
    fun `waveform cap math holds`() {
        assertTrue(VoiceRecordingPolicy.MAX_STORED_WAVEFORM_ENTRIES <= 600)
        assertTrue(WaveformPeakMath.recordingLiveBuckets <= VoiceRecordingPolicy.MAX_STORED_WAVEFORM_ENTRIES)
        assertEquals(0.1f, WaveformPeakMath.renderAmp(Float.NaN), 1e-6f)
    }

    // ---------- MEDIUM: migrate sweeps the voice dir itself ----------

    @Test
    fun `legacy migrate sweeps the canonical voice dir for empty-dir orphans`() {
        val repo = repository()
        val migrateAt = repo.indexOf("suspend fun migrateLegacyPlaintextVoiceNotes")
        assertTrue(migrateAt >= 0)
        val body = repo.substring(migrateAt, minOf(repo.length, migrateAt + 4000))
        assertTrue("empty-dir orphans need the voice dir itself in the sweep set", body.contains("voiceNotesDir()"))
    }

    // ---------- AudioPlaybackCard: drag scrub + speed guard ----------

    @Test
    fun `waveform supports drag scrub through clamped policy`() {
        val c = card()
        assertTrue(c.contains("detectHorizontalDragGestures"))
        assertTrue(c.contains("detectTapGestures"))
        assertTrue(c.contains("WaveformPeakMath.scrubTargetMs"))
    }

    // ---------- Review fixes ----------

    @Test
    fun `streaming temps are matched and swept without touching real blobs`() {
        val dir = Files.createTempDirectory("voice262sweep").toFile()
        try {
            val blob = File(dir, "voice_p1_1.enc").apply { writeText("enc") }
            val encTmp = File(dir, "voice_p1_1.enc.tmp").apply { writeText("tmp") }
            val rekeyTmp = File(dir, "voice_p1_2.enc.rekey.tmp").apply { writeText("tmp") }
            val recTmp = File(dir, "voice_rec_p1_1_m.tmp").apply { writeText("tmp") }
            assertTrue(VoiceNoteCrypto.isStreamingTempName("voice_p1_1.enc.tmp"))
            assertTrue(VoiceNoteCrypto.isStreamingTempName("voice_p1_2.enc.rekey.tmp"))
            assertFalse(VoiceNoteCrypto.isStreamingTempName("voice_p1_1.enc"))
            assertFalse(VoiceNoteCrypto.isStreamingTempName("voice_p1_1.m4a"))
            assertEquals(2, VoiceNoteCrypto.sweepStreamingTemps(dir))
            assertTrue("finished blob must survive the sweep", blob.isFile)
            assertTrue("recording temps are not streaming temps", recTmp.isFile)
            assertFalse(encTmp.exists())
            assertFalse(rekeyTmp.exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `voice dir streaming sweep is wired into record release and migrate paths`() {
        val vnm = manager()
        assertTrue(vnm.contains("sweepStreamingTemps"))
        val repo = repository()
        val migrateAt = repo.indexOf("suspend fun migrateLegacyPlaintextVoiceNotes")
        assertTrue(migrateAt >= 0)
        val body = repo.substring(migrateAt, minOf(repo.length, migrateAt + 4000))
        assertTrue(body.contains("sweepStreamingTemps"))
    }

    @Test
    fun `decrypt falls back to legacy FIELD_AAD blobs`() {
        val dir = Files.createTempDirectory("voice262aad").toFile()
        try {
            val dek = ByteArray(32) { (it * 7 + 3).toByte() }
            val raw = ByteArray(6000) { (it % 251).toByte() }
            val combined = com.authorss81.noteflow.services.EncryptionService.encryptAad(
                raw, dek, com.authorss81.noteflow.services.EncryptionService.FIELD_AAD
            )
            val blob = File(dir, "voice_p1_9.enc").apply { writeBytes(combined) }
            val restored = File(dir, "out.m4a")
            assertTrue(
                "pre-domain-separation blob must still play via the FIELD_AAD retry",
                VoiceNoteCrypto.decryptRecordingFile(blob, restored, dek)
            )
            assertEquals(raw.size.toLong(), restored.length())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `playback honors audio-focus denial and splits the confinement message`() {
        val vnm = manager()
        assertTrue(vnm.contains("if (!requestPlaybackFocus())"))
        assertTrue(vnm.contains("Could not get audio focus"))
        assertTrue(vnm.contains("outside the vault voice folder"))
    }

    @Test
    fun `waveform seeks once per gesture without drag-start seek`() {
        val c = card()
        assertTrue(c.contains("detectTapGestures"))
        assertTrue(c.contains("detectHorizontalDragGestures"))
        assertTrue(c.contains("WaveformPeakMath.scrubTargetMs"))
        assertFalse(
            "onDragStart fires on every touch-down, double-seeking taps",
            c.contains("onDragStart =")
        )
    }

    @Test
    fun `speed selector guards unknown speeds`() {
        val c = card()
        assertTrue(c.contains("speedIndex"))
        assertTrue(c.contains("indexOf(playbackSpeed)"))
        assertFalse(
            "bare (indexOf+1)%size jumps an unknown speed to 0.5x",
            c.contains("speeds[(speeds.indexOf(playbackSpeed) + 1) % speeds.size]")
        )
    }
}
