package com.authorss81.noteflow.plugins.texttools

import com.authorss81.noteflow.plugins.TextAnalysis
import kotlin.math.roundToInt

/**
 * Pure-Kotlin note-text statistics (Phase 15, capability `TextTools`).
 *
 * Word/character/paragraph/sentence counts, reading time (at a standard 200
 * words-per-minute pace) and the Flesch-Kincaid readability metrics (grade
 * level + reading-ease label). Zero dependencies, fully unit-testable.
 *
 * Heuristics are deliberately simple and documented so the numbers are stable:
 * - words = whitespace-separated tokens that contain at least one letter/digit;
 * - sentences = runs of `.!?` followed by whitespace/quotes/end-of-input;
 * - paragraphs = blocks separated by one or more blank lines;
 * - syllables = vowel-group heuristic with a silent-`e` correction.
 */
object TextToolsAnalyzer {

    private val wordRegex = Regex("\\S+")
    private val sentenceTerminalRegex = Regex("[.!?]+(?=[\\s\"')\\]}»„\\u201d\\u2019]|$)")

    fun analyze(text: String): TextAnalysis {
        val words = wordTokens(text)
        val wordCount = words.size
        val characterCount = text.length
        val characterCountNoSpaces = text.count { !it.isWhitespace() }
        val sentenceCount = sentenceCountish(text)
        val paragraphCount = paragraphCount(text)
        val readingTimeSeconds =
            if (wordCount == 0) 0 else (wordCount / 200.0 * 60.0).roundToInt()

        val syllableTotal = words.sumOf { syllableCount(it) }
        val (grade, label) = readability(wordCount, sentenceCount, syllableTotal)
        return TextAnalysis(
            wordCount = wordCount,
            characterCount = characterCount,
            characterCountNoSpaces = characterCountNoSpaces,
            paragraphCount = paragraphCount,
            sentenceCount = sentenceCount,
            readingTimeSeconds = readingTimeSeconds,
            fleschKincaid = grade,
            fleschKincaidLabel = label
        )
    }

    /** Whitespace-separated tokens that contain at least one letter or digit. */
    fun wordTokens(text: String): List<String> =
        wordRegex.findAll(text).map { it.value }.filter { token ->
            token.any { it.isLetter() || it.isDigit() }
        }.toList()

    /** Count `.!?` terminal punctuation runs (abbreviations/decimals may skew). */
    fun sentenceCountish(text: String): Int =
        sentenceTerminalRegex.findAll(text).count()

    /** Non-blank blocks separated by one or more blank lines (min 0). */
    fun paragraphCount(text: String): Int {
        if (text.isBlank()) return 0
        return text.split(Regex("\n\\s*\n")).count { it.isNotBlank() }
    }

    /** Vowel-group syllables with a silent-final-`e` correction. */
    fun syllableCount(word: String): Int {
        val lower = word.lowercase()
        val groups = Regex("[aeiouy]+").findAll(lower).count()
        var count = groups
        if (lower.endsWith("e") && count > 1 && !lower.endsWith("le")) {
            count--
        }
        return count.coerceAtLeast(1)
    }

    /**
     * Flesch-Kincaid pair: grade level (what most people call "Flesch-Kincaid")
     * and a reading-ease label. Returns (0.0, "No text") for empty input.
     */
    fun readability(wordCount: Int, sentenceCount: Int, syllableTotal: Int): Pair<Double, String> {
        if (wordCount == 0 || sentenceCount == 0) return 0.0 to "No text"
        val wordsPerSentence = wordCount.toDouble() / sentenceCount
        val syllablesPerWord = syllableTotal.toDouble() / wordCount

        val grade = 0.39 * wordsPerSentence + 11.8 * syllablesPerWord - 15.59
        val ease = (206.835 - 1.015 * wordsPerSentence - 84.6 * syllablesPerWord)
            .coerceIn(0.0, 100.0)
        val label = when {
            ease >= 90 -> "Very easy"
            ease >= 80 -> "Easy"
            ease >= 70 -> "Fairly easy"
            ease >= 60 -> "Average"
            ease >= 50 -> "Fairly difficult"
            ease >= 30 -> "Difficult"
            else -> "Very difficult"
        }
        return (grade.coerceAtLeast(0.0).let { (it * 100).roundToInt() / 100.0 }) to label
    }
}