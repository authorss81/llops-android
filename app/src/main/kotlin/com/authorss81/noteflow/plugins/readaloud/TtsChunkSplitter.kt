package com.authorss81.noteflow.plugins.readaloud

import com.authorss81.noteflow.plugins.TtsChunk

/**
 * PURE JVM — splits a passage into TTS-safe chunks. Unit-tested with zero
 * Android dependencies.
 *
 * Rules:
 *  - Fenced code blocks (``` or ~~~) are tagged [TtsChunk.isCode] and spoken
 *    VERBATIM (flat, no Markdown intonation) in line-sized chunks.
 *  - Prose is chunked at paragraph boundaries first, then at sentence
 *    boundaries, so the TTS engine never swallows a half-paragraph. Chunks
 *    never split a word and never exceed [maxChunkChars].
 */
object TtsChunkSplitter {

    // Also matches fences with a language tag (e.g. ```kotlin , ~~~bash).
    private val fenceRegex = Regex("^\\s*(```|~~~)[a-zA-Z0-9_+.#-]*\\s*$")

    /** A raw segment of the passage with its code-ness. */
    private data class Segment(val text: String, val isCode: Boolean)

    /**
     * @param maxChunkChars soft cap per chunk (a single sentence longer than
     *   the cap is hard-wrapped at spaces, so no text is ever dropped).
     */
    fun chunkText(passage: String, maxChunkChars: Int = 500): List<TtsChunk> {
        if (passage.isBlank()) return emptyList()
        val cap = maxChunkChars.coerceAtLeast(40)
        val out = mutableListOf<TtsChunk>()
        var index = 0
        splitIntoSegments(passage).forEach { seg ->
            val chunks = if (seg.isCode) codeChunks(seg.text, cap) else proseChunks(seg.text, cap)
            chunks.forEach { text ->
                out += TtsChunk(index = index++, text = text, isCode = seg.isCode)
            }
        }
        return out
    }

    private fun splitIntoSegments(passage: String): List<Segment> {
        val lines = passage.split('\n')
        val segments = mutableListOf<Segment>()
        val normal = StringBuilder()
        var inCode = false
        val code = StringBuilder()

        fun flushNormal() {
            if (normal.isNotBlank()) {
                segments += Segment(normal.toString().trim('\n', ' '), isCode = false)
                normal.setLength(0)
            }
        }

        fun flushCode() {
            if (code.isNotBlank()) {
                segments += Segment(code.toString().trim('\n', ' '), isCode = true)
                code.setLength(0)
            }
        }

        lines.forEach { line ->
            if (fenceRegex.matches(line)) {
                if (inCode) {
                    flushCode()
                    inCode = false
                } else {
                    flushNormal()
                    inCode = true
                }
            } else if (inCode) {
                code.appendLine(line)
            } else {
                normal.appendLine(line)
            }
        }
        if (inCode) flushCode() else flushNormal()
        return segments
    }

    private fun codeChunks(text: String, cap: Int): List<String> {
        val out = mutableListOf<String>()
        text.split('\n').forEach { line ->
            if (line.isBlank()) return@forEach
            if (line.length <= cap) {
                out += line
            } else {
                line.chunked(cap).forEach { out += it }
            }
        }
        return out
    }

    private fun proseChunks(text: String, cap: Int): List<String> {
        val out = mutableListOf<String>()
        // Paragraphs are separated by blank lines.
        text.split(Regex("\\n\\s*\\n")).forEach { paragraph ->
            if (paragraph.isBlank()) return@forEach
            if (paragraph.length <= cap) {
                out += paragraph.replace(Regex("\\s+"), " ").trim()
            } else {
                // Split the paragraph into sentences, pack greedily up to cap.
                val sentences = paragraph.split(Regex("(?<=[.!?])\\s+"))
                var buf = StringBuilder()
                sentences.forEach { sentence ->
                    val cleaned = sentence.replace(Regex("\\s+"), " ").trim()
                    if (cleaned.isEmpty()) return@forEach
                    if (buf.isNotEmpty() && buf.length + 1 + cleaned.length > cap) {
                        out += buf.toString().trim()
                        buf = StringBuilder()
                    }
                    if (buf.isNotEmpty()) buf.append(' ')
                    buf.append(cleaned)
                    if (cleaned.length > cap && buf.length >= cap) {
                        // Single over-long sentence: hard-wrap at spaces.
                        hardWrap(buf.toString(), cap).forEach { out += it }
                        buf = StringBuilder()
                    }
                }
                if (buf.isNotBlank()) out += buf.toString().trim()
            }
        }
        return out
    }

    private fun hardWrap(text: String, cap: Int): List<String> {
        val out = mutableListOf<String>()
        val words = text.split(' ')
        val buf = StringBuilder()
        words.forEach { word ->
            if (buf.isNotEmpty() && buf.length + 1 + word.length > cap) {
                out += buf.toString()
                buf.setLength(0)
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(word)
        }
        if (buf.isNotBlank()) out += buf.toString()
        return out
    }
}