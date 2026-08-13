package com.authorss81.noteflow.plugins.export

import com.authorss81.noteflow.plugins.ExportFormat

/**
 * The fully-assembled text of an export: the file name, the full HTML document
 * (MARKDOWN/HTML), and the plain-text body used by the PDF writer.
 *
 * @param fileName shareable base name including extension (sanitized).
 * @param markdown the raw markdown body ("" when absent).
 * @param html the full standalone HTML document.
 * @param plainText the plain-text body (PDF path).
 */
data class ExportPayload(
    val title: String,
    val fileName: String,
    val markdown: String,
    val html: String,
    val plainText: String
)

/**
 * Pure-JVM assembly of an export payload from a note's raw data. This is the
 * "export-payload assembly" surface the Phase 15 DoD requires to be unit-tested:
 * file-name sanitization, Markdown→HTML via the app's CommonMark parser, HTML
 * fallback for plain-text-only notes, and plain-text derivation for the PDF
 * writer. No Android classes are touched here.
 */
object ExportPayloadAssembler {

    private const val DEFAULT_BASENAME = "Note"

    /** Sanitize a note title into a safe file base name (keeps [a-z A-Z 0-9 . _ -]). */
    fun sanitizeBaseName(title: String): String {
        val cleaned = title
            .trim()
            .replace(Regex("[^a-zA-Z0-9._ -]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_', '.', ' ')
        return if (cleaned.isBlank()) DEFAULT_BASENAME else cleaned
    }

    /**
     * Build an [ExportPayload] for [format].
     *
     * - MARKDOWN/HTML: the body is the note's markdown when present; a
     *   plain-text-only note is escaped into `<p>` paragraphs.
     * - PDF: the writer consumes [ExportPayload.plainText] (derived from
     *   markdown via [MarkdownHtmlConverter.toPlainText], or the raw body).
     */
    fun assemble(
        title: String,
        markdown: String?,
        plainText: String?,
        format: ExportFormat
    ): ExportPayload {
        val md = markdown?.trim().orEmpty()
        val body = plainText?.trim().orEmpty().ifBlank { null }
        val hasMd = md.isNotBlank()
        val hasBody = body != null

        val htmlBody: String = when {
            hasMd -> MarkdownHtmlConverter.toHtml(md)
            hasBody -> body!!.lineSequence().joinToString("\n") { "<p>${MarkdownHtmlConverter.escapeHtml(it)}</p>" }
            else -> "<p><em>Empty note</em></p>"
        }
        val plain = when {
            hasMd -> MarkdownHtmlConverter.toPlainText(md)
            hasBody -> body!!
            else -> ""
        }
        val safeTitle = MarkdownHtmlConverter.escapeHtml(title)
        val fullHtml = buildString {
            append("<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"UTF-8\">\n")
            append("<title>$safeTitle</title>\n")
            append("<style>")
            append("body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,sans-serif;")
            append("margin:40px;background:#fafafa;color:#1a1a1a;line-height:1.6;}")
            append(".container{max-width:900px;margin:0 auto;background:#fff;padding:40px;")
            append("border-radius:12px;box-shadow:0 4px 12px rgba(0,0,0,0.08);}")
            append("h1{color:#1e3a8a;border-bottom:2px solid #e2e8f0;padding-bottom:8px;}")
            append(".content{margin-top:20px;}")
            append("</style>\n</head>\n<body>\n<div class=\"container\">\n")
            append("<h1>$safeTitle</h1>\n<div class=\"content\">\n$htmlBody\n</div>\n")
            append("</div>\n</body>\n</html>\n")
        }

        val extension = when (format) {
            ExportFormat.MARKDOWN -> "md"
            ExportFormat.HTML -> "html"
            ExportFormat.PDF -> "pdf"
        }
        return ExportPayload(
            title = title,
            fileName = "${sanitizeBaseName(title)}.$extension",
            markdown = md,
            html = fullHtml,
            plainText = plain
        )
    }
}