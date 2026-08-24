package com.authorss81.noteflow.services

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.media.PlaybackParams
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

class VoiceNoteManager(private val context: Context) {
    private var mediaRecorder: MediaRecorder? = null
    private var mediaPlayer: MediaPlayer? = null
    private var timerJob: Job? = null
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Recording State
    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording.asStateFlow()

    private val _recordingElapsedMs = MutableStateFlow(0L)
    val recordingElapsedMs: StateFlow<Long> = _recordingElapsedMs.asStateFlow()

    private val _waveformAmplitudes = MutableStateFlow<List<Float>>(emptyList())
    val waveformAmplitudes: StateFlow<List<Float>> = _waveformAmplitudes.asStateFlow()

    private var currentOutputFile: File? = null
    private var currentBlobFile: File? = null
    private var currentPlaybackTempFile: File? = null

    // Playback State
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _playbackPositionMs = MutableStateFlow(0L)
    val playbackPositionMs: StateFlow<Long> = _playbackPositionMs.asStateFlow()

    private val _playbackDurationMs = MutableStateFlow(0L)
    val playbackDurationMs: StateFlow<Long> = _playbackDurationMs.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _activePlayingFilePath = MutableStateFlow<String?>(null)
    val activePlayingFilePath: StateFlow<String?> = _activePlayingFilePath.asStateFlow()

    // Honest error surface — shown as a banner instead of silent simulated behavior
    private val _recordingError = MutableStateFlow<String?>(null)
    val recordingError: StateFlow<String?> = _recordingError.asStateFlow()

    // B2-DOS-03 (phase-79): when the recorder aborts at the duration/size ceiling
    // the audio up to the limit IS a completed recording — the finished result is
    // published here so the editor attaches the audio embed instead of leaving an
    // orphaned encrypted blob with no DB row. The manual stop path (`stopRecording`)
    // returns its result directly and does NOT publish here, so a capped auto-stop
    // can never double-attach.
    private val _completedRecordingResult = MutableStateFlow<VoiceRecordingResult?>(null)
    val completedRecordingResult: StateFlow<VoiceRecordingResult?> = _completedRecordingResult.asStateFlow()

    // R2-b2b1-UI-05 (phase-153): set in the SOLE fail-closed branch of
    // `finalizeRecording` (DEK null / encryption failure) so `release()` can
    // report that a finished recording was destroyed — the editor then
    // republishes the honest notice over the persistent snackbar pipeline
    // instead of the disposed editor's short-lived error banner.
    private var discardOnRelease = false

    // Phase 204: last SUCCESSFULLY finalized recording and whether the editor
    // attached its embed. A rotation mid-recording finalizes inside release()
    // (blob written) with no observer left to attach — pre-fix the result was
    // returned into release() and dropped: orphaned `.enc` blob, no embed, no
    // notice. Now release() captures any UNATTACHED success so the editor
    // teardown can relay it to the ViewModel-scoped pending slot
    // (`VoicePendingRecordingSlot`) and the next editor instance attaches it.
    private var lastFinishedResult: VoiceRecordingResult? = null
    private var lastFinishedResultAttached = true
    private var unpublishedResultForRelay: VoiceRecordingResult? = null

    // B2-DOS-03 (phase-79): fixed-budget live waveform accumulator. Appends are
    // O(1) amortized into a preallocated FloatArray and the emitted StateFlow view
    // never exceeds `WaveformPeakMath.recordingLiveBuckets` (160) entries — the
    // pre-fix `_waveformAmplitudes.value + amp` full-list copy per tick is gone.
    private var waveformBuckets = LiveWaveformBuckets(WaveformPeakMath.recordingLiveBuckets)

    // Serializes recorder start/stop/finalize. The B2-DOS-03 sampler now runs on
    // Dispatchers.Default while `stop()`/finalize can be triggered on the main
    // thread (chip tap) or the sampler thread (ceiling abort) — a racing
    // double-finalize must be impossible.
    private val recorderLock = Any()

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    fun clearErrors() {
        _recordingError.value = null
        _playbackError.value = null
    }

    fun startRecording(pageId: String): File? = synchronized(recorderLock) {
        stopPlayback()

        // B1-DB-3 (phase-54): MediaRecorder must stream to a real file, but that
        // raw AAC is PLAINTEXT — so it is written to a transient cacheDir temp
        // (the OS-scrubbed, never-backed-up location) and AES-GCM-encrypted with
        // the vault DEK into `filesDir/voice_notes/*.enc` the moment recording
        // stops. At rest the voice dir holds ONLY encrypted blobs; the raw audio
        // exists on disk no longer than the recording itself. Any stale plaintext
        // temp from an interrupted pre-fix session is swept first.
        VoiceNoteCrypto.sweepPlaintextTemps(context.cacheDir)

        val stamp = System.currentTimeMillis()
        val tempFile = File(context.cacheDir, "voice_rec_${pageId}_${stamp}.m4a.tmp")
        val voiceDir = File(context.filesDir, "voice_notes").apply { if (!exists()) mkdirs() }
        val blobFile = File(voiceDir, "voice_${pageId}_${stamp}.${VoiceNoteCrypto.ENCRYPTED_EXTENSION}")
        currentOutputFile = tempFile
        currentBlobFile = blobFile

        _recordingError.value = null
        _completedRecordingResult.value = null
        // Phase 204: a new session invalidates any prior finished result.
        lastFinishedResult = null
        lastFinishedResultAttached = true
        discardOnRelease = false
        waveformBuckets = LiveWaveformBuckets(WaveformPeakMath.recordingLiveBuckets)
        _waveformAmplitudes.value = emptyList()
        _recordingElapsedMs.value = 0L

        try {
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            recorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128000)
                setAudioSamplingRate(44100)
                setOutputFile(tempFile.absolutePath)
                prepare()
                start()
            }
            mediaRecorder = recorder
            _isRecording.value = true

            // Real amplitude & timer sampler (no simulated fallback).
            // B2-DOS-03 (phase-79): the sampler:
            //  1. runs OFF the main dispatcher (launched on Dispatchers.Default) so
            //     the per-tick amplitude work never janks/ANRs a 2-core device;
            //  2. accumulates amplitudes via the fixed-budget LiveWaveformBuckets
            //     (O(1)-amortized appends into a preallocated FloatArray) and emits
            //     a BOUNDED downsampled view (<=160 entries) — the pre-fix
            //     `_waveformAmplitudes.value + amp` full-list copy-on-write per tick
            //     (~648M element copies after an hour) is gone;
            //  3. aborts at the duration (30 min) and file-size (32 MB) ceilings —
            //     the pre-fix recorder had NO cap and could fill internal storage
            //     (~57 MB/hour at 128 kbps) — then surfaces a non-alarming error and
            //     publishes the completed recording so it is saved, not discarded.
            timerJob?.cancel()
            timerJob = scope.launch(Dispatchers.Default) {
                val startTime = System.currentTimeMillis()
                while (isActive && _isRecording.value) {
                    val elapsed = System.currentTimeMillis() - startTime
                    _recordingElapsedMs.value = elapsed

                    val maxAmp = try {
                        mediaRecorder?.maxAmplitude?.toFloat() ?: 0f
                    } catch (e: Exception) {
                        0f
                    }
                    val normalizedAmp = (maxAmp / 32767f).coerceIn(0.05f, 1.0f)
                    waveformBuckets.append(normalizedAmp)
                    _waveformAmplitudes.value = waveformBuckets.snapshot()

                    // B2-DOS-03: abort at the recording-length ceiling.
                    if (VoiceRecordingPolicy.isOverDuration(elapsed)) {
                        finalizeRecording(VoiceRecordingPolicy.DURATION_LIMIT_MESSAGE)
                        return@launch
                    }
                    // B2-DOS-03: abort at the file-size ceiling (defense-in-depth
                    // for encoder bitrate variance beyond the 128 kbps nominal).
                    val rawBytes = try {
                        currentOutputFile?.length() ?: 0L
                    } catch (e: Exception) {
                        0L
                    }
                    if (VoiceRecordingPolicy.isOverSize(rawBytes)) {
                        finalizeRecording(VoiceRecordingPolicy.SIZE_LIMIT_MESSAGE)
                        return@launch
                    }

                    delay(VoiceRecordingPolicy.SAMPLER_TICK_MS)
                }
            }
            blobFile
        } catch (e: Exception) {
            Log.e("VoiceNoteManager", "Error starting audio recording (${FailureLogPolicy.classNameToken(e)})")
            _isRecording.value = false
            _recordingError.value = "Could not start recording: microphone unavailable or in use."
            try {
                mediaRecorder?.release()
            } catch (_: Exception) {
            }
            mediaRecorder = null
            currentOutputFile = null
            currentBlobFile = null
            if (tempFile.exists()) tempFile.delete()
            null
        }
    }

    fun stopRecording(): VoiceRecordingResult? {
        if (!_isRecording.value) return null
        return finalizeRecording(null)
    }

    /**
     * Stops the recorder, encrypts the finished audio into the vault-DEK `.enc`
     * blob and destroys the plaintext temp. Shared by the manual stop path
     * (`[stopRecording]`, [limitMessage] == null) and the B2-DOS-03 ceiling
     * abort (`[VoiceRecordingPolicy]` duration/size caps, [limitMessage] != null).
     *
     * Serialized under [recorderLock] so a manual stop racing a sampler abort can
     * never double-finalize. On the ceiling path the completed recording is
     * published via [completedRecordingResult] so the editor attaches the audio
     * embed (never silently orphaned) alongside the non-alarming error banner.
     */
    private fun finalizeRecording(limitMessage: String?): VoiceRecordingResult? = synchronized(recorderLock) {
        if (!_isRecording.value) return@synchronized null
        _isRecording.value = false
        timerJob?.cancel()

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("VoiceNoteManager", "Error stopping MediaRecorder (${FailureLogPolicy.classNameToken(e)})")
            _recordingError.value = "Recording stopped unexpectedly — the audio file may be empty."
        }
        mediaRecorder = null

        val tempFile = currentOutputFile ?: return@synchronized null
        val blobFile = currentBlobFile
        if (!tempFile.exists() || tempFile.length() < 44L) {
            // Log WITHOUT the absolute path: the path reveals the private vault
            // file layout in logcat (low risk but unnecessary).
            Log.w("VoiceNoteManager", "Recording produced no audio data (file too small)")
            try { tempFile.delete() } catch (_: Exception) {}
            currentOutputFile = null
            currentBlobFile = null
            _recordingError.value = "No audio was captured — the microphone may be busy or permission was revoked."
            return@synchronized null
        }
        val duration = _recordingElapsedMs.value
        val amplitudes = _waveformAmplitudes.value

        // Phase 192: resolve the stop-time DEK BEFORE the save. A passwordless
        // vault's device-wrapped copy IS the boot credential by design (the DB
        // factory re-reads it on every open — NoteflowDatabase.kt:440-444, and
        // LockedOpenGuard gates passwordless as "still re-reads its
        // device-wrapped copy"), so a cleared in-memory holder must NOT fail the
        // save: it is re-read (never minted, never a locked password-vault
        // bypass). A genuinely-locked PASSWORD vault (DEK zeroized mid-recording)
        // resolves to LockedVault and stays fail-closed. Any re-read is synced
        // back into the holder so the gate below and the later attach/flush use
        // the exact same key.
        val stopTimeKey = VoiceRecordingSavePolicy.resolveStopTimeKey(
            inMemoryDek = VaultKeyHolder.dek,
            vaultHasPassword = com.authorss81.noteflow.services.SettingsManager(context.applicationContext).hasMasterPassword,
            passwordlessReader = { com.authorss81.noteflow.services.SecurityService.forDevice(context).readDek() }
        )
        stopTimeKey.key?.let { VaultKeyHolder.dek = it }
        val dek = VaultKeyHolder.dek

        // B1-DB-3 (phase-54): encrypt the finished AAC into the vault-DEK-wrapped
        // `.enc` blob NOW and destroy the plaintext temp. A locked vault (DEK
        // zeroized mid-recording) fails closed: the plaintext temp is deleted and
        // nothing is persisted rather than leaking raw audio at rest.
        val saveOutcome = if (blobFile != null && dek != null) {
            VoiceNoteCrypto.encryptRecordingFileDetailed(tempFile, blobFile, dek)
        } else null
        val saved = saveOutcome is VoiceEncryptOutcome.Saved
        currentOutputFile = null
        currentBlobFile = null
        if (!saved) {
            Log.w("VoiceNoteManager", "Recording could not be encrypted — plaintext temp destroyed")
            // R2-b2b1-UI-05: the encrypted=false path (a finished recording was
            // destroyed) — flag it so `release()` reports the discard and the
            // editor re-surfaces the notice through the persistent snackbar
            // pipeline.
            discardOnRelease = true
            // B1-DB-3 (phase-192): the plaintext temp must never outlive a
            // failed save — delete it NOW (fail closed) instead of letting it
            // linger in cacheDir until the next record-start/release sweep.
            try { tempFile.delete() } catch (_: Exception) {}
            // Phase 192: the generic "could not be saved securely" wording is
            // reserved for the GENUINELY-LOCKED vault; recoverable conditions
            // (storage full / transient I/O-JCE) and the anomalous
            // passwordless missing-key state get a truthful, non-alarming
            // message from the policy.
            _recordingError.value = if (stopTimeKey is VoiceRecordingSavePolicy.StopTimeKey.LockedVault) {
                "The recording could not be saved securely. Please try again."
            } else {
                VoiceRecordingSavePolicy.messageFor(stopTimeKey, saveOutcome)
            }
            return@synchronized null
        }

        val result = VoiceRecordingResult(
            filePath = blobFile!!.absolutePath,
            durationMs = duration,
            waveformAmplitudes = amplitudes
        )
        // Phase 204: remember the success as UNATTACHED until the editor
        // confirms it built the embed (manual stop path or ceiling observer
        // both call [markRecordingAttached]).
        lastFinishedResult = result
        lastFinishedResultAttached = false
        if (limitMessage != null) {
            // B2-DOS-03: a ceiling abort STOPS the recorder and saves what was
            // recorded (the audio is the user's — never discard it silently). A
            // non-alarming banner + a published result the editor observes and
            // attaches as an audio embed.
            _recordingError.value = limitMessage
            _completedRecordingResult.value = result
        }
        result
    }

    fun startPlayback(filePath: String, speed: Float = 1.0f) {
        stopPlayback()
        val blob = File(filePath)

        _playbackError.value = null
        _activePlayingFilePath.value = filePath
        _playbackSpeed.value = speed

        // B1-DB-3 (phase-54): the stored path is an AES-GCM `.enc` blob — the
        // raw AAC only exists on disk as a transient cacheDir temp while the
        // user is actively listening (deleted on stop/completion/release).
        // Decrypt off the main thread so a slow read never janks the UI.
        if (!VoiceNoteCrypto.isEncryptedBlobName(blob.name) || !blob.isFile || blob.length() < 2L) {
            _isPlaying.value = false
            _activePlayingFilePath.value = null
            _playbackError.value = "Audio file is missing or empty — it can't be played."
            return
        }

        playbackJob?.cancel()
        playbackJob = scope.launch {
            val tempPlayback = File(context.cacheDir, "voice_pb_${System.currentTimeMillis()}.m4a")
            val playFilePath = withContext(Dispatchers.Default) {
                val dek = VaultKeyHolder.dek
                if (dek == null || !VoiceNoteCrypto.decryptRecordingFile(blob, tempPlayback, dek)) null
                else tempPlayback.absolutePath
            }
            if (playFilePath == null) {
                try { tempPlayback.delete() } catch (_: Exception) {}
                _isPlaying.value = false
                _activePlayingFilePath.value = null
                _playbackError.value = "Audio file is missing or empty — it can't be played."
                return@launch
            }
            currentPlaybackTempFile = tempPlayback
            if (!isActive) {
                // A stop() raced the decryption — never start a player the user
                // already asked to stop.
                deletePlaybackTemp()
                return@launch
            }

            try {
                val player = MediaPlayer().apply {
                    setDataSource(playFilePath)
                    prepare()
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        playbackParams = PlaybackParams().apply { this.speed = speed }
                    }
                    start()
                }
                mediaPlayer = player
                _isPlaying.value = true
                _playbackDurationMs.value = player.duration.toLong()

                player.setOnCompletionListener {
                    stopPlayback()
                }

                // Track progress
                playbackJob?.cancel()
                playbackJob = scope.launch {
                    while (isActive && _isPlaying.value) {
                        try {
                            _playbackPositionMs.value = player.currentPosition.toLong()
                        } catch (e: Exception) {
                            break
                        }
                        delay(50)
                    }
                }
            } catch (e: Exception) {
                Log.w("VoiceNoteManager", "Playback failed (${FailureLogPolicy.classNameToken(e)})")
                _isPlaying.value = false
                _activePlayingFilePath.value = null
                deletePlaybackTemp()
                _playbackError.value = "Playback failed — the audio file may be corrupted."
            }
        }
    }

    fun pausePlayback() {
        try {
            mediaPlayer?.pause()
        } catch (e: Exception) {
            Log.e("VoiceNoteManager", "Error pausing player (${FailureLogPolicy.classNameToken(e)})")
        }
        _isPlaying.value = false
    }

    fun resumePlayback() {
        try {
            mediaPlayer?.start()
            _isPlaying.value = true
        } catch (e: Exception) {
            _activePlayingFilePath.value?.let { startPlayback(it, _playbackSpeed.value) }
        }
    }

    fun togglePlayback(filePath: String) {
        if (_activePlayingFilePath.value == filePath && _isPlaying.value) {
            pausePlayback()
        } else if (_activePlayingFilePath.value == filePath && !_isPlaying.value) {
            resumePlayback()
        } else {
            startPlayback(filePath, _playbackSpeed.value)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        _playbackSpeed.value = speed
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                mediaPlayer?.playbackParams = PlaybackParams().apply { this.speed = speed }
            } catch (e: Exception) {
                Log.e("VoiceNoteManager", "Failed to update playback speed (${FailureLogPolicy.classNameToken(e)})")
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val bounded = positionMs.coerceIn(0L, _playbackDurationMs.value.coerceAtLeast(1L))
        _playbackPositionMs.value = bounded
        try {
            mediaPlayer?.seekTo(bounded.toInt())
        } catch (e: Exception) {
            Log.e("VoiceNoteManager", "Seek error (${FailureLogPolicy.classNameToken(e)})")
        }
    }

    fun stopPlayback() {
        playbackJob?.cancel()
        try {
            mediaPlayer?.apply {
                if (isPlaying) stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("VoiceNoteManager", "Error releasing player (${FailureLogPolicy.classNameToken(e)})")
        }
        mediaPlayer = null
        _isPlaying.value = false
        _playbackPositionMs.value = 0L
        _activePlayingFilePath.value = null
        deletePlaybackTemp()
    }

    private fun deletePlaybackTemp() {
        val temp = currentPlaybackTempFile ?: return
        currentPlaybackTempFile = null
        try {
            if (temp.exists()) temp.delete()
        } catch (e: Exception) {
            Log.e("VoiceNoteManager", "Error removing playback temp (${FailureLogPolicy.classNameToken(e)})")
        }
    }

    /**
     * Phase 204: the editor acknowledges that a finished recording's audio
     * embed was ATTACHED (manual chip-tap stop or the ceiling-abort observer).
     * Without this ack, `release()` would treat the save as unattached and
     * relay it to the pending slot, double-attaching on the next editor open.
     */
    fun markRecordingAttached() {
        lastFinishedResultAttached = true
    }

    /**
     * One-shot relay accessor: the recording that was finalized and SAVED but
     * never attached before [release] tore this manager down (rotation /
     * composition disposal). Null after the first call or when nothing was
     * pending. The editor publishes the result into the ViewModel-scoped
     * `VoicePendingRecordingSlot` so the NEXT editor instance attaches it.
     */
    fun takeUnattachedRecordingForRelay(): VoiceRecordingResult? {
        val result = unpublishedResultForRelay
        unpublishedResultForRelay = null
        return result
    }

    /**
     * Stops any in-flight recording/playback, cancels the manager scope and
     * sweeps plaintext temps.
     *
     * R2-b2b1-UI-05 (phase-153): returns `true` exactly when a FINISHED
     * recording was destroyed on this teardown (the DEK-null / lock path), so
     * the editor can publish the honest discard notice over the persistent
     * snackbar pipeline — the fail-closed at-rest behavior is unchanged.
     *
     * Phase 204: a teardown-triggered stopRecording() may SUCCESSFULLY save a
     * finished recording (rotation mid-recording) — the success used to be
     * dropped here, orphaning the `.enc` blob. It is now captured as the
     * unattached result retrievable via [takeUnattachedRecordingForRelay].
     */
    fun release(): Boolean {
        stopRecording()
        unpublishedResultForRelay =
            if (lastFinishedResultAttached) null else lastFinishedResult
        lastFinishedResult = null
        lastFinishedResultAttached = true
        stopPlayback()
        scope.cancel()
        VoiceNoteCrypto.sweepPlaintextTemps(context.cacheDir)
        val discarded = discardOnRelease
        discardOnRelease = false
        return discarded
    }
}

data class VoiceRecordingResult(
    val filePath: String,
    val durationMs: Long,
    val waveformAmplitudes: List<Float>
)
