package com.authorss81.noteflow.services

/**
 * Phase 79 (B2-DOS-03) — single decision table for voice-recording limits.
 *
 * Pure JVM, so the budgets and the abort decisions are unit-testable without a
 * device. The pre-fix recorder had NO duration or size bound anywhere
 * (`VoiceNoteManager.startRecording` sampled forever at 100 ms): the 128 kbps
 * AAC file grew ~57 MB/hour with no stop — a disk-fill DoS if the user left it
 * running. These budgets bound both axes:
 *
 *  - [MAX_RECORDING_DURATION_MS] = 30 minutes (the finding's suggested cap).
 *    10 samples/s ⇒ at most 18 000 raw samples are ever accumulated.
 *  - [MAX_RECORDING_BYTES] = 32 MB as the file-size cap. At the configured
 *    128 000 bps the recorder writes ~16 KB/s, so 30 min ≈ 28.8 MB — the byte
 *    cap is a defense-in-depth backstop for encoder bitrate variance, never
 *    reached in normal operation.
 *  - [MAX_STORED_WAVEFORM_ENTRIES] = 600 absolute ceiling for a stored / parsed
 *    waveform series. The recorder emits `WaveformPeakMath.recordingLiveBuckets`
 *    (160) buckets, so new recordings fit far under this; it exists so a legacy
 *    or restored (crafted-backup) `waveformJson` can never be re-parsed into an
 *    unbounded list (the "re-parsed at NoteRepository.kt:589-598" half of the
 *    finding).
 *  - [SAMPLER_TICK_MS] — the fixed sampler cadence.
 *
 * Limit-abort messages are deliberately NON-alarming (AGENTS.md hardware-reality
 * rule: never silent degradation, but no alarm wording either) and state what
 * happened — the recording up to the limit IS saved, it is not discarded.
 */
object VoiceRecordingPolicy {

    /** Hard ceiling for a recording: 30 minutes. */
    const val MAX_RECORDING_DURATION_MS = 30L * 60L * 1000L

    /** Hard ceiling for the raw audio file written by the MediaRecorder: 32 MB. */
    const val MAX_RECORDING_BYTES = 32L * 1024L * 1024L

    /** Absolute ceiling for any stored / parsed waveform series (finding: ≤600). */
    const val MAX_STORED_WAVEFORM_ENTRIES = 600

    /** Sampler cadence. */
    const val SAMPLER_TICK_MS = 100L

    const val DURATION_LIMIT_MESSAGE =
        "Recording limit reached (30 minutes) — the audio was saved."

    const val SIZE_LIMIT_MESSAGE =
        "Recording size limit reached — the audio was saved."

    /** True when the elapsed recording time reached the duration ceiling. */
    fun isOverDuration(elapsedMs: Long): Boolean = elapsedMs >= MAX_RECORDING_DURATION_MS

    /** True when the raw audio file reached the size ceiling. */
    fun isOverSize(bytes: Long): Boolean = bytes >= MAX_RECORDING_BYTES
}
