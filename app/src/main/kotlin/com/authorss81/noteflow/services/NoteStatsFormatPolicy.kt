package com.authorss81.noteflow.services

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

/**
 * Phase 174 — note-stats footer formatting decision table.
 *
 * Pure JVM: locale-safe number formatting and reading-time phrasing for the small
 * unobtrusive stats line under the markdown editor/preview
 * ("1,234 words · ~6 min read · 5,678 chars"). The UI calls the exact same
 * formatting the tests assert, so the rendered line can never drift from the
 * locale-safe numbers.
 */
object NoteStatsFormatPolicy {

    /** Fewest literal-change chars that justify re-computing word/read stats. */
    const val MIN_MATERIAL_LENGTH_DELTA = 8

    /** Debounce window (ms) applied before the stats line recomputes. */
    const val STATS_DEBOUNCE_MILLIS = 250L

    private const val WORDS_PER_MINUTE = 200

    /**
     * Ceil [seconds] to whole minutes, minimum 1 for any positive content
     * (everything a second or more reads as "~1 min read"). 0 stays 0.
     */
    fun readingTimeMinutes(seconds: Int): Int {
        if (seconds <= 0) return 0
        return ((seconds + 59) / 60).coerceAtLeast(1)
    }

    /** "~6 min read" / "~1 min read"; empty for a zero-minute result. */
    fun readingTimeLabel(seconds: Int): String {
        val minutes = readingTimeMinutes(seconds)
        if (minutes <= 0) return ""
        return "~$minutes min read"
    }

    /** Locale-safe integer formatting ("1,234" in en-US, "1.234" in de-DE). */
    fun formatCount(value: Int, locale: Locale): String =
        NumberFormat.getIntegerInstance(locale).format(value.toLong())

    /** Blank note detection — an empty note's stats line renders nothing. */
    fun isBlankNote(wordCount: Int, characterCount: Int): Boolean =
        wordCount <= 0 && characterCount <= 0

    /** "1 word" / "1,234 words". */
    fun wordCountLabel(wordCount: Int, locale: Locale): String {
        val n = formatCount(wordCount, locale)
        return if (wordCount == 1) "$n word" else "$n words"
    }

    /** "5,678 chars". */
    fun charCountLabel(characterCount: Int, locale: Locale): String =
        formatCount(characterCount, locale) + " chars"

    /**
     * The full footer line: "1,234 words · ~6 min read · 5,678 chars". Returns
     * null for a blank note so the UI renders no line.
     */
    fun statsLabel(
        wordCount: Int,
        readingTimeSeconds: Int,
        characterCount: Int,
        locale: Locale
    ): String? {
        if (isBlankNote(wordCount, characterCount)) return null
        val parts = buildList {
            add(wordCountLabel(wordCount, locale))
            val read = readingTimeLabel(readingTimeSeconds)
            if (read.isNotBlank()) add(read)
            add(charCountLabel(characterCount, locale))
        }
        return parts.joinToString(" · ")
    }

    /**
     * Recompute guard: a debounced sample that changed fewer than
     * [MIN_MATERIAL_LENGTH_DELTA] literal chars keeps the previous result, so
     * trailing-whitespace / punctuation-only keystrokes never re-tokenize the
     * document. A negative [previousTextLength] (first sample) always computes.
     */
    fun shouldRecomputeStats(previousTextLength: Int, currentTextLength: Int): Boolean =
        previousTextLength < 0 || abs(currentTextLength - previousTextLength) >= MIN_MATERIAL_LENGTH_DELTA
}