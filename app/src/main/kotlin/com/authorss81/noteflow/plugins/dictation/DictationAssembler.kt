package com.authorss81.noteflow.plugins.dictation

/**
 * PURE JVM — the dictation text-assembly rules live here so the whole
 * "how committed utterances fold into the note text" path is unit-testable
 * with zero Android dependencies.
 *
 * The platform recognizer only emits raw utterances; [OnDeviceDictationPlugin]
 * funnels them through this assembler. Rules:
 *
 *  1. Whitespace normalization — internal runs of whitespace collapse to a
 *     single space; the utterance is trimmed on both ends.
 *  2. Spacing — exactly one space (or a clean newline when the note already
 *     ends on one) separates the existing note text from the utterance —
 *     never a doubled space, never no space.
 *  3. Capitalization — an utterance that starts a brand-new sentence (blank
 *     note, or the note ends with `.` `!` `?` or a newline) gets its first
 *     letter capitalized. Mid-sentence insertions keep the recognizer's casing.
 */
object DictationAssembler {

    private val sessionEnders = setOf('.', '!', '?')

    /**
     * Fold a committed [utterance] into [currentText]. Never modifies the
     * existing text beyond adding the normalized utterance. Returns the new
     * full note text.
     */
    fun appendUtterance(currentText: String, utterance: String): String {
        val clean = utterance.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return currentText
        val base = currentText ?: ""
        if (base.isBlank()) return capitalizeFirstLetter(clean)
        val trimmed = base.trimEnd(' ', '\n')
        val capitalized = if (endsSentence(trimmed)) capitalizeFirstLetter(clean) else clean
        return if (base.endsWith('\n')) {
            trimmed + "\n" + capitalized
        } else {
            trimmed + " " + capitalized
        }
    }

    private fun endsSentence(text: String): Boolean {
        if (text.isEmpty()) return true
        return sessionEnders.contains(text.last())
    }

    private fun capitalizeFirstLetter(text: String): String {
        if (text.isEmpty()) return text
        return text[0].uppercaseChar() + text.substring(1)
    }
}