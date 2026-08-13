package com.authorss81.noteflow.plugins.readaloud

import com.authorss81.noteflow.plugins.TtsSpeechPlan

/**
 * PURE JVM — the read-aloud decision policy. Unit-tested, no Android deps.
 *
 * Order of precedence:
 *  1. Nothing to speak (blank/whitespace-only passage) → [TtsSpeechPlan.NothingToSpeak].
 *  2. Quiet mode enabled → [TtsSpeechPlan.RefuseQuiet] — NOT one byte is ever
 *     spoken while the user's SilentToggle is active (the dialog keeps working,
 *     it just refuses with an explanatory message; no silent degradation).
 *  3. Otherwise → [TtsSpeechPlan.Play] with the chunked passage.
 */
object ReadAloudPolicy {

    fun plan(passage: String, quietMode: Boolean, maxChunkChars: Int = 500): TtsSpeechPlan {
        val chunks = TtsChunkSplitter.chunkText(passage, maxChunkChars)
        if (chunks.isEmpty()) return TtsSpeechPlan.NothingToSpeak
        if (quietMode) {
            return TtsSpeechPlan.RefuseQuiet(
                "Quiet mode is on — read-aloud won't speak right now. " +
                    "Turn off SilentToggle (or wait until it's off) to hear the note."
            )
        }
        return TtsSpeechPlan.Play(chunks)
    }
}