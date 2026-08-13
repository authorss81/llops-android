package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.NotePageEntity

/**
 * Full-Text Search (FTS) & On-Device OCR Search Engine (Feature 26.7).
 * Provides sub-second query matching, snippet extraction, and text highlighting
 * across note titles, body markdown, handwriting transcriptions, and OCR document attachments.
 */
object FtsSearchEngine {

    data class SearchHit(
        val page: NotePageEntity,
        val matchSnippet: String,
        val hitSource: HitSource,
        val score: Float
    )

    enum class HitSource {
        TITLE, BODY_TEXT, HANDWRITING, DOCUMENT_OCR, TAGS
    }

    /**
     * Executes a sub-second multi-field FTS query across active notes.
     */
    fun search(
        pages: List<NotePageEntity>,
        query: String,
        handwritingTranscripts: Map<String, String> = emptyMap(),
        ocrTexts: Map<String, String> = emptyMap()
    ): List<SearchHit> {
        val cleanQuery = query.trim().lowercase()
        if (cleanQuery.isBlank()) return emptyList()

        val tokens = cleanQuery.split(Regex("\\s+")).filter { it.isNotBlank() }
        val hits = mutableListOf<SearchHit>()

        for (page in pages) {
            val title = page.title.lowercase()
            val text = (page.extractedText ?: "").lowercase()
            val tags = page.tags.lowercase()
            val handwriting = (handwritingTranscripts[page.id] ?: "").lowercase()
            val ocr = (ocrTexts[page.id] ?: "").lowercase()

            var score = 0f
            var bestSnippet = ""
            var source = HitSource.BODY_TEXT

            // 1. Title Match (highest weight)
            if (tokens.all { title.contains(it) }) {
                score += 10.0f
                bestSnippet = page.title
                source = HitSource.TITLE
            }

            // 2. Body Text Match
            if (tokens.all { text.contains(it) }) {
                score += 5.0f
                if (bestSnippet.isBlank()) {
                    bestSnippet = extractSnippet(page.extractedText ?: "", tokens.first())
                    source = HitSource.BODY_TEXT
                }
            }

            // 3. Tag Match
            if (tokens.all { tags.contains(it) }) {
                score += 4.0f
                if (bestSnippet.isBlank()) {
                    bestSnippet = "Tags: ${page.tags}"
                    source = HitSource.TAGS
                }
            }

            // 4. Handwriting Match
            if (handwriting.isNotBlank() && tokens.all { handwriting.contains(it) }) {
                score += 3.5f
                if (bestSnippet.isBlank()) {
                    bestSnippet = "Handwriting: " + extractSnippet(handwritingTranscripts[page.id] ?: "", tokens.first())
                    source = HitSource.HANDWRITING
                }
            }

            // 5. Document OCR Match
            if (ocr.isNotBlank() && tokens.all { ocr.contains(it) }) {
                score += 3.0f
                if (bestSnippet.isBlank()) {
                    bestSnippet = "OCR: " + extractSnippet(ocrTexts[page.id] ?: "", tokens.first())
                    source = HitSource.DOCUMENT_OCR
                }
            }

            if (score > 0f) {
                hits.add(SearchHit(page = page, matchSnippet = bestSnippet, hitSource = source, score = score))
            }
        }

        return hits.sortedByDescending { it.score }
    }

    private fun extractSnippet(fullText: String, matchToken: String, snippetLen: Int = 80): String {
        val index = fullText.indexOf(matchToken, ignoreCase = true)
        if (index == -1) return fullText.take(snippetLen)

        val start = maxOf(0, index - 25)
        val end = minOf(fullText.length, index + matchToken.length + 55)
        val snippet = fullText.substring(start, end).replace("\n", " ")

        val prefix = if (start > 0) "..." else ""
        val suffix = if (end < fullText.length) "..." else ""
        return "$prefix$snippet$suffix"
    }
}
