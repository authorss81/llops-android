package com.authorss81.noteflow.plugins.export

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream

/**
 * Platform-only writer that renders a note's plain text into a PDF using the
 * Android built-in [PdfDocument] — deliberately a "simple text layout": title +
 * word-wrapped paragraphs paginated to A4. No heavyweight PDF dependency.
 *
 * Not JVM-unit-testable (android.graphics); the payload derivation it consumes
 * ([ExportPayloadAssembler] → [ExportPayload.plainText]) IS pure-JVM covered.
 */
internal object TextPdfWriter {

    private const val PAGE_WIDTH = 595 // A4 @ 72dpi
    private const val PAGE_HEIGHT = 842
    private const val MARGIN = 48f
    private const val LINE_HEIGHT = 16f

    /**
     * Write [title] + [body] into [file] as a PDF. Returns true on success.
     * Long lines are word-wrapped; text flows across pages.
     */
    fun write(file: File, title: String, body: String): Boolean = try {
        val doc = PdfDocument()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 11f
            color = Color.BLACK
        }
        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.BLACK
        }
        val measure = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11f }
        val contentWidth = PAGE_WIDTH - MARGIN * 2

        var page: PdfDocument.Page? = null
        var canvas: Canvas? = null
        var y = 0f
        var pageNumber = 0

        fun ensurePage() {
            if (page == null) {
                pageNumber++
                val info = PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, pageNumber).create()
                page = doc.startPage(info)
                canvas = page!!.canvas
                y = MARGIN + 30f
            }
        }

        fun advanceToNextPage() {
            try {
                page?.let { doc.finishPage(it) }
            } finally {
                page = null
                canvas = null
            }
        }

        fun drawWrapped(text: String, textPaint: Paint) {
            val paragraphs = text.replace("\r\n", "\n").split("\n\n").map { it.trim() }.filter { it.isNotBlank() }
            if (paragraphs.isEmpty()) return
            val allLines = paragraphs.flatMap { wrap(it, measure, contentWidth) }
            for (line in allLines) {
                if (y + LINE_HEIGHT > PAGE_HEIGHT - MARGIN) {
                    advanceToNextPage()
                    ensurePage()
                }
                canvas?.drawText(line, MARGIN, y, textPaint)
                y += LINE_HEIGHT
            }
        }

        ensurePage()
        // Title (single line, ellipsized by wrap to width just for safety).
        drawWrapped(title.ifBlank { "Note" }, titlePaint)
        y += 6f
        if (body.isNotBlank()) drawWrapped(body, paint)
        page?.let { doc.finishPage(it) }

        file.parentFile?.mkdirs()
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()
        true
    } catch (e: Exception) {
        false
    }

    /** Break [text] into lines whose measured width <= [maxWidth]. */
    internal fun wrap(text: String, measure: Paint, maxWidth: Float): List<String> {
        val rawLines = text.split('\n')
        val out = mutableListOf<String>()
        for (rawLine in rawLines) {
            if (rawLine.isBlank()) {
                out += ""
                continue
            }
            val words = rawLine.trim().split(' ')
            var current = ""
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (measure.measureText(candidate) <= maxWidth || current.isEmpty()) {
                    current = candidate
                } else {
                    out += current
                    if (measure.measureText(word) > maxWidth) {
                        // Pathological single overlong token: hard-split.
                        val chunks = chunkLongWord(word, measure, maxWidth)
                        chunks.forEachIndexed { i, chunk ->
                            if (i == chunks.size - 1) current = chunk else out += chunk
                        }
                    } else {
                        current = word
                    }
                }
            }
            out += current
        }
        return out
    }

    private fun chunkLongWord(word: String, measure: Paint, maxWidth: Float): List<String> {
        val chunks = mutableListOf<String>()
        var idx = 0
        while (idx < word.length) {
            var end = idx + 1
            while (end <= word.length && measure.measureText(word.substring(idx, end)) <= maxWidth) {
                end++
            }
            end = (end - 1).coerceAtLeast(idx + 1)
            chunks += word.substring(idx, end)
            idx = end
        }
        return chunks
    }
}