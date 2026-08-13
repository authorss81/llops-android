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

        val voiceDir = File(context.filesDir, "voice_notes").apply { if (!exists()) mkdirs() }
        val file = File(voiceDir, "voice_${pageId}_${System.currentTimeMillis()}.m4a")
        currentOutputFile = file

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
                setOutputFile(file.absolutePath)
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
            return file
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
            if (file.exists()) file.delete()
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

        val file = currentOutputFile ?: return null
        if (!file.exists() || file.length() < 44L) {
            Log.w("VoiceNoteManager", "Recording produced no audio data: ${file.absolutePath}")
            _recordingError.value = "No audio was captured — the microphone may be busy or permission was revoked."
            return null
        }
        val duration = _recordingElapsedMs.value
        val amplitudes = _waveformAmplitudes.value

        return VoiceRecordingResult(
            filePath = file.absolutePath,
            durationMs = duration,
            waveformAmplitudes = amplitudes
        )
    }

    fun startPlayback(filePath: String, speed: Float = 1.0f) {
        stopPlayback()
        val file = File(filePath)

        _playbackError.value = null
        _activePlayingFilePath.value = filePath
        _playbackSpeed.value = speed

        if (!file.exists() || file.length() < 200L) {
            _isPlaying.value = false
            _activePlayingFilePath.value = null
            _playbackError.value = "Audio file is missing or empty — it can't be played."
            return
        }

        try {
            val player = MediaPlayer().apply {
                setDataSource(filePath)
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
            _playbackError.value = "Playback failed — the audio file may be corrupted."
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
    }

    fun release() {
        stopRecording()
        stopPlayback()
        scope.cancel()
    }
}

data class VoiceRecordingResult(
    val filePath: String,
    val durationMs: Long,
    val waveformAmplitudes: List<Float>
)
