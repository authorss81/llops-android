package com.authorss81.noteflow

import com.authorss81.noteflow.services.VoiceRecordingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 79 (B2-DOS-03): the voice-recording limits decision table.
 *
 * The pre-fix recorder had NO duration or size bound anywhere — a 128 kbps AAC
 * blob grew ~57 MB/hour with no stop (internal-storage disk-fill DoS if left
 * running). This policy is the single source of truth for the two ceilings the
 * sampler enforces, plus the absolute stored-waveform ceiling that bounds the
 * re-parse side (legacy / crafted-backup `waveformJson`).
 */
class VoiceRecordingPolicyTest {

    @Test
    fun `the recording duration ceiling is 30 minutes`() {
        assertEquals(30L * 60L * 1000L, VoiceRecordingPolicy.MAX_RECORDING_DURATION_MS)
    }

    @Test
    fun `duration boundary is exact`() {
        val cap = VoiceRecordingPolicy.MAX_RECORDING_DURATION_MS
        assertFalse("a fresh recording is under the cap", VoiceRecordingPolicy.isOverDuration(0L))
        assertFalse("a 29:59.999 recording is under the cap", VoiceRecordingPolicy.isOverDuration(cap - 1L))
        assertTrue("a 30:00 recording ABORTS", VoiceRecordingPolicy.isOverDuration(cap))
        assertTrue("a 31:00 recording ABORTS", VoiceRecordingPolicy.isOverDuration(cap + 60_000L))
    }

    @Test
    fun `the file-size ceiling is 32 MB and the boundary is exact`() {
        assertEquals(32L * 1024L * 1024L, VoiceRecordingPolicy.MAX_RECORDING_BYTES)
        val cap = VoiceRecordingPolicy.MAX_RECORDING_BYTES
        assertFalse("0 bytes is under the cap", VoiceRecordingPolicy.isOverSize(0L))
        assertFalse("just under the cap is fine", VoiceRecordingPolicy.isOverSize(cap - 1L))
        assertTrue("AT the cap ABORTS", VoiceRecordingPolicy.isOverSize(cap))
        assertTrue("over the cap ABORTS", VoiceRecordingPolicy.isOverSize(cap + 4096L))
    }

    @Test
    fun `the stored waveform ceiling matches the finding s 600 entries`() {
        assertEquals(600, VoiceRecordingPolicy.MAX_STORED_WAVEFORM_ENTRIES)
        assertTrue(
            "the recorder emission (recordingLiveBuckets) must fit under the storage ceiling",
            com.authorss81.noteflow.services.WaveformPeakMath.recordingLiveBuckets <=
                VoiceRecordingPolicy.MAX_STORED_WAVEFORM_ENTRIES
        )
    }

    @Test
    fun `the sampler cadence is 100 ms`() {
        assertEquals(100L, VoiceRecordingPolicy.SAMPLER_TICK_MS)
    }

    @Test
    fun `limit-abort messages are non-alarming and say the audio was saved, not discarded`() {
        val durationMsg = VoiceRecordingPolicy.DURATION_LIMIT_MESSAGE
        assertTrue(durationMsg.contains("30 minutes"))
        assertTrue(durationMsg.contains("saved"))

        val sizeMsg = VoiceRecordingPolicy.SIZE_LIMIT_MESSAGE
        assertTrue(sizeMsg.contains("saved"))

        listOf(durationMsg, sizeMsg).forEach { msg ->
            assertFalse("limit message must not alarm the user", msg.contains("error", ignoreCase = true))
            assertFalse("limit message must not alarm the user", msg.contains("fail", ignoreCase = true))
            assertFalse("limit message must not alarm the user", msg.contains("lost", ignoreCase = true))
            assertFalse("limit message must not alarm the user", msg.contains("WARNING", ignoreCase = true))
        }
    }
}