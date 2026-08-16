package com.authorss81.noteflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 79 (B2-DOS-03): source-level proof that the voice-note DoS path is
 * closed. The pure-JVM behavior (fixed-budget O(1)-amortized sampler view,
 * duration/size ceilings, bounded re-parse) is exercised in
 * [LiveWaveformBucketsTest] / [VoiceRecordingPolicyTest]; the Android-bound
 * wiring (MediaRecorder-based sampler) can't run on a JVM-only unit test, so
 * it is pinned here at source level, mirroring B2-Dos02VaultSearchBoundedTest:
 *
 *  1. the sampler runs OFF the main dispatcher,
 *  2. amplitudes append O(1) into a fixed-budget accumulator and the emitted
 *     StateFlow view is the bounded downsampled snapshot (the
 *     `_waveformAmplitudes.value + amp` full-list copy-on-write is gone),
 *  3. the recorder aborts at the 30 min / 32 MB ceilings, surfacing a
 *     non-alarming error AND publishing the completed recording so the editor
 *     attaches the audio embed — never a silently-discarded or orphaned blob,
 *  4. finalize is serialized so a tap racing a ceiling abort can't double-stop,
 *  5. the re-parse side (`NoteRepository.parseWaveformJson`) is bounded, and
 *  6. the editor auto-attaches the ceiling-completed recording via the same
 *     shared path a manual stop uses.
 */
class B2Dos03VoiceRecordingTest {

    // ---------- VoiceNoteManager: sampler + ceilings ----------

    @Test
    fun `the amplitude sampler runs off the main dispatcher`() {
        val source = readVoiceNoteManager()
        assertTrue(
            "the sampler loop must be launched on Dispatchers.Default (off main)",
            source.contains("scope.launch(Dispatchers.Default)")
        )
    }

    @Test
    fun `the per tick copy on write list concat is gone`() {
        val source = readVoiceNoteManager()
        assertFalse(
            "the pre-fix `= _waveformAmplitudes.value + normalizedAmp` full-list copy per tick must be gone",
            source.contains("= _waveformAmplitudes.value + ")
        )
    }

    @Test
    fun `amplitudes append O(1) into a fixed budget accumulator and emit a bounded snapshot`() {
        val source = readVoiceNoteManager()
        assertTrue(
            "each tick must append into the fixed-budget accumulator",
            source.contains("waveformBuckets.append(normalizedAmp)")
        )
        assertTrue(
            "the emitted StateFlow view must be the bounded snapshot",
            source.contains("_waveformAmplitudes.value = waveformBuckets.snapshot()")
        )
        assertTrue(
            "the accumulator budget must be the documented bounded recording budget",
            source.contains("LiveWaveformBuckets(WaveformPeakMath.recordingLiveBuckets)")
        )
        assertTrue(
            "a fresh recording must start with a clean accumulator",
            source.contains("waveformBuckets = LiveWaveformBuckets(WaveformPeakMath.recordingLiveBuckets)")
        )
    }

    @Test
    fun `the recorder aborts at the duration ceiling with a surfaced non alarming error`() {
        val source = readVoiceNoteManager()
        assertTrue(
            "the sampler must check the duration ceiling",
            source.contains("VoiceRecordingPolicy.isOverDuration(elapsed)")
        )
        assertTrue(
            "past the ceiling the recording must finalize via the shared abort path",
            source.contains("finalizeRecording(VoiceRecordingPolicy.DURATION_LIMIT_MESSAGE)")
        )
    }

    @Test
    fun `the recorder aborts at the file size ceiling as defense in depth`() {
        val source = readVoiceNoteManager()
        assertTrue(
            "the sampler must check the raw audio file size each tick",
            source.contains("currentOutputFile?.length() ?: 0L")
        )
        assertTrue(
            "past the size ceiling the recording must finalize via the shared abort path",
            source.contains("VoiceRecordingPolicy.isOverSize(rawBytes)")
        )
        assertTrue(
            "over the size ceiling the finalize path must carry the size message",
            source.contains("finalizeRecording(VoiceRecordingPolicy.SIZE_LIMIT_MESSAGE)")
        )
    }

    @Test
    fun `a ceiling abort finalizes, surfaces the error, and publishes the completed result`() {
        val source = readVoiceNoteManager()
        assertTrue(
            "the ceiling path must surface a non-alarming error banner",
            source.contains("_recordingError.value = limitMessage")
        )
        assertTrue(
            "the ceiling path must publish the completed result so the UI attaches the embed",
            source.contains("_completedRecordingResult.value = result")
        )
        assertTrue(
            "the completed-result flow must be exposed for the editor observer",
            source.contains("val completedRecordingResult: StateFlow<VoiceRecordingResult?>")
        )
    }

    @Test
    fun `the manual stop path delegates to the shared finalize with no publish`() {
        val source = readVoiceNoteManager()
        assertTrue(
            "manual stop must route through the shared finalize (limit message null)",
            source.contains("return finalizeRecording(null)")
        )
        assertTrue(
            "the completed result must only publish on the ceiling path, never the manual path",
            source.contains("if (limitMessage != null)")
        )
    }

    @Test
    fun `finalize is serialized so a tap racing a ceiling abort can never double stop`() {
        val source = readVoiceNoteManager()
        assertTrue(
            "the finalize path must be guarded by the recorder lock",
            source.contains("synchronized(recorderLock)")
        )
        assertTrue(
            "the re-check under the lock must refuse a double finalize",
            source.contains("if (!_isRecording.value) return@synchronized null")
        )
    }

    // ---------- EditorScreen: auto-attach the ceiling-completed recording ----------

    @Test
    fun `the editor observes the ceiling completed recording and attaches the audio embed`() {
        val source = readEditorScreen()
        assertTrue(
            "the editor must observe the published result",
            source.contains("val completedVoiceRecording by voiceNoteManager.completedRecordingResult.collectAsState()")
        )
        assertTrue(
            "a ceiling-completed recording must be auto-attached",
            source.contains("LaunchedEffect(completedVoiceRecording)")
        )
        val attachCalls = Regex("attachVoiceRecording\\(result\\)").findAll(source).count()
        assertTrue(
            "attachVoiceRecording(result) must exist at least twice: the auto-attach observer AND the chip-tap stop path (found $attachCalls)",
            attachCalls >= 2
        )
    }

    @Test
    fun `the shared attach path is the only place the audio embed is constructed`() {
        val source = readEditorScreen()
        assertTrue(
            "the shared helper must exist and build the AUDIO_NOTE embed",
            source.contains("fun attachVoiceRecording(result: com.authorss81.noteflow.services.VoiceRecordingResult)")
        )
        assertTrue(
            "the embed must be AUDIO_NOTE with the encrypted blob path",
            source.contains("type = MediaEmbedType.AUDIO_NOTE")
        )
        // The chip handler must not re-construct the embed inline — it now calls
        // the shared helper (which is declared before the chip handler and does
        // the only `newAudioEmbed` construction).
        val attachIndex = source.indexOf("fun attachVoiceRecording(result:")
        val chipIndex = source.indexOf("val result = voiceNoteManager.stopRecording()")
        assertTrue("the shared attach helper must exist", attachIndex >= 0)
        assertTrue("the chip-tap stop must still exist", chipIndex >= 0)
        assertTrue("the attach helper must be declared before the chip handler", attachIndex < chipIndex)
        val helperRegion = source.substring(attachIndex, chipIndex)
        assertTrue(
            "the shared helper must own the only audio-embed construction",
            helperRegion.contains("newAudioEmbed")
        )
        assertTrue(
            "the embed must flow into the lock-safe media-embeds write",
            helperRegion.contains("handleMediaEmbedsChange(mediaEmbeds + newAudioEmbed)")
        )
    }

    @Test
    fun `the completed result resets on each new recording`() {
        val source = readVoiceNoteManager()
        assertTrue(
            "startRecording must clear the completed-result latch so a new session never re-attaches",
            source.contains("_completedRecordingResult.value = null")
        )
    }

    // ---------- NoteRepository: bounded re-parse ----------

    @Test
    fun `the re parse of a stored waveform is bounded`() {
        val source = readNoteRepository()
        assertTrue(
            "parseWaveformJson must materialize at most the stored-waveform ceiling entries",
            source.contains("VoiceRecordingPolicy.MAX_STORED_WAVEFORM_ENTRIES")
        )
        assertTrue(
            "the JSONArray path must be length-bounded before building the list",
            source.contains("minOf(arr.length(), VoiceRecordingPolicy.MAX_STORED_WAVEFORM_ENTRIES)")
        )
        assertTrue(
            "the fallback split path must be take-bounded too",
            source.contains(".take(VoiceRecordingPolicy.MAX_STORED_WAVEFORM_ENTRIES)")
        )
    }

    // ---------- source readers ----------

    private fun readVoiceNoteManager(): String =
        readSource("services/VoiceNoteManager.kt")

    private fun readEditorScreen(): String =
        readSource("ui/screens/EditorScreen.kt")

    private fun readNoteRepository(): String =
        readSource("data/repository/NoteRepository.kt")

    private fun readSource(relative: String): String {
        val file = java.io.File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/$relative")
        assertTrue("$relative must exist for the wiring pin", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): java.io.File {
        val cwd = java.io.File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (java.io.File(dir, "gradle/libs.versions.toml").isFile &&
                java.io.File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}