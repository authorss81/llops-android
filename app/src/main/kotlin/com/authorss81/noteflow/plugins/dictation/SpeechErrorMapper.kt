package com.authorss81.noteflow.plugins.dictation

import android.speech.SpeechRecognizer

/**
 * PURE JVM — maps platform `onError` codes to user-facing, non-alarming
 * messages. Kept dependency-free (the `SpeechRecognizer.ERROR_*` constants are
 * plain ints) so the mapping is unit-testable without an Android runtime.
 */
object SpeechErrorMapper {

    /** A stable, user-facing message for a recognizer error code. */
    fun message(errorCode: Int, hasPartial: Boolean): String = when (errorCode) {
        SpeechRecognizer.ERROR_NO_MATCH ->
            "I couldn't hear a clear phrase — please try again (a bit louder, near the mic)."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
            "No speech detected. Tap the mic and start talking, then tap stop."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "Network error — dictation needs voice recognition available on this device."
        SpeechRecognizer.ERROR_AUDIO ->
            "Microphone problem — allow the app to use the microphone and retry."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is missing — enable it in app settings, then retry."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "The selected recognition language isn't available on this device."
        SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER ->
            "The speech recognizer couldn't start. Please retry (or restart the app)."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "The recognizer is busy — please wait a moment and try again."
        else ->
            if (hasPartial) "Recognition stopped early — the partial phrasing above was kept."
            else "Voice recognition failed. Please try again."
    }

    /** Message for the "recognized silence" case (no final text). */
    fun noSpeechMessage(): String =
        "No speech was recognized — please try again near the mic and tap stop when done."

    /** Whether the recognizer is even present on this device's hardware profile. */
    fun isAvailableMessage(): String =
        "Speech recognition isn't available on this device — dictation can't run here."
}