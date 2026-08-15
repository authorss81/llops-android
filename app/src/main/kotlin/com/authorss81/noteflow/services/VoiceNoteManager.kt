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

    private val _playbackError = MutableStateFlow<String?>(null)
    val playbackError: StateFlow<String?> = _playbackError.asStateFlow()

    fun clearErrors() {
        _recordingError.value = null
        _playbackError.value = null
    }

    fun startRecording(pageId: String): File? {
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

            // Real amplitude & timer sampler (no simulated fallback)
            timerJob?.cancel()
            timerJob = scope.launch {
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
                    _waveformAmplitudes.value = _waveformAmplitudes.value + normalizedAmp

                    delay(100)
                }
            }
            return blobFile
        } catch (e: Exception) {
            Log.e("VoiceNoteManager", "Error starting audio recording: ${e.message}")
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
            return null
        }
    }

    fun stopRecording(): VoiceRecordingResult? {
        if (!_isRecording.value) return null

        _isRecording.value = false
        timerJob?.cancel()

        try {
            mediaRecorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            Log.e("VoiceNoteManager", "Error stopping MediaRecorder: ${e.message}")
            _recordingError.value = "Recording stopped unexpectedly — the audio file may be empty."
        }
        mediaRecorder = null

        val tempFile = currentOutputFile ?: return null
        val blobFile = currentBlobFile
        if (!tempFile.exists() || tempFile.length() < 44L) {
            // Log WITHOUT the absolute path: the path reveals the private vault
            // file layout in logcat (low risk but unnecessary).
            Log.w("VoiceNoteManager", "Recording produced no audio data (file too small)")
            try { tempFile.delete() } catch (_: Exception) {}
            currentOutputFile = null
            currentBlobFile = null
            _recordingError.value = "No audio was captured — the microphone may be busy or permission was revoked."
            return null
        }
        val duration = _recordingElapsedMs.value
        val amplitudes = _waveformAmplitudes.value

        // B1-DB-3 (phase-54): encrypt the finished AAC into the vault-DEK-wrapped
        // `.enc` blob NOW and destroy the plaintext temp. A locked vault (DEK
        // zeroized mid-recording) fails closed: the plaintext temp is deleted and
        // nothing is persisted rather than leaking raw audio at rest.
        val dek = VaultKeyHolder.dek
        val encrypted = blobFile != null && dek != null &&
            VoiceNoteCrypto.encryptRecordingFile(tempFile, blobFile, dek)
        currentOutputFile = null
        currentBlobFile = null
        if (!encrypted) {
            Log.w("VoiceNoteManager", "Recording could not be encrypted — plaintext temp destroyed")
            _recordingError.value = "The recording could not be saved securely. Please try again."
            return null
        }

        return VoiceRecordingResult(
            filePath = blobFile!!.absolutePath,
            durationMs = duration,
            waveformAmplitudes = amplitudes
        )
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
                Log.w("VoiceNoteManager", "Playback failed: ${e.message}")
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
            Log.e("VoiceNoteManager", "Error pausing player: ${e.message}")
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
                Log.e("VoiceNoteManager", "Failed to update playback speed: ${e.message}")
            }
        }
    }

    fun seekTo(positionMs: Long) {
        val bounded = positionMs.coerceIn(0L, _playbackDurationMs.value.coerceAtLeast(1L))
        _playbackPositionMs.value = bounded
        try {
            mediaPlayer?.seekTo(bounded.toInt())
        } catch (e: Exception) {
            Log.e("VoiceNoteManager", "Seek error: ${e.message}")
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
            Log.e("VoiceNoteManager", "Error releasing player: ${e.message}")
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
            Log.e("VoiceNoteManager", "Error removing playback temp: ${e.message}")
        }
    }

    fun release() {
        stopRecording()
        stopPlayback()
        scope.cancel()
        VoiceNoteCrypto.sweepPlaintextTemps(context.cacheDir)
    }
}

data class VoiceRecordingResult(
    val filePath: String,
    val durationMs: Long,
    val waveformAmplitudes: List<Float>
)
