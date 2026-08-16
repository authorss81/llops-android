package com.authorss81.noteflow.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import com.authorss81.noteflow.data.model.NotePageEntity
import java.io.File
import java.util.regex.Pattern

/**
 * On-device document text and metadata extractor.
 *
 * This is NOT optical character recognition (OCR): it performs direct on-device text
 * extraction from PDFs and text/markdown files, and for image files it extracts structural
 * image metadata (such as dimensions and mime type) rather than recognizing the text in
 * the image. True OCR would require ML-Kit or similar (see ROADMAP Phase 21.7).
 */
object DocumentTextExtractor {
    fun extractTextFromDocument(context: Context, page: NotePageEntity): String {
        val importsRoot = ImportExportService.getImportsDir(context)
        // B1-DB-4 (phase-44): a text page's body lives ONLY in the encrypted
        // extractedText column — never as a plaintext .md/.txt companion file.
        // A legacy plaintext source is coalesced transiently if it still exists.
        if (NoteBodyVaultPolicy.isNoteTextBodySource(page.sourceFilePath, page.sourceFileType)) {
            return NoteBodyVaultPolicy.resolveBodyForDisplay(
                page.extractedText, page.sourceFilePath, page.sourceFileType, importsRoot
            )
        }
        // B1-AUTH-05 (phase-69): a non-text source (PDF/image) is only ever read
        // when its stored path is confined under the imports root — a crafted
        // sourceFilePath pointing at an arbitrary readable file is refused.
        val path = SourceFilePathPolicy.confine(page.sourceFilePath, importsRoot) ?: return ""
        val file = File(path)
        return extractText(file, page.sourceFileType)
    }

    /**
     * Extracts text directly from a file based on its type.
     */
    fun extractText(file: File, fileType: String?): String {
        if (!file.exists()) return ""
        return try {
            when (fileType) {
                "pdf" -> extractPdfText(file)
                "image" -> extractImageText(file)
                "text" -> file.readText()
                else -> {
                    val ext = file.extension.lowercase()
                    if (ext == "pdf") extractPdfText(file)
                    else if (ext in listOf("jpg", "jpeg", "png", "webp")) extractImageText(file)
                    else if (file.length() < 1_000_000) file.readText()
                    else ""
                }
            }
        } catch (e: Exception) {
            ""
        }
    }

    private fun extractPdfText(file: File): String {
        val sb = StringBuilder()
        // 1. Quick regex extraction of standard text stream operators in PDF (strings within parentheses)
        try {
            val bytes = file.readBytes()
            val content = String(bytes, Charsets.ISO_8859_1)
            val matcher = Pattern.compile("\\((.*?)\\)").matcher(content)
            while (sb.length < 5000 && matcher.find()) {
                val match = matcher.group(1)
                if (match != null && match.length > 2) {
                    val cleanMatch = match.filter { it.isLetterOrDigit() || it.isWhitespace() || it in ".,;:!?'-" }
                    if (cleanMatch.length > 2) {
                        sb.append(cleanMatch).append(" ")
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback to basic details
        }

        // 2. Add structural page info
        try {
            val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            if (sb.isBlank()) {
                sb.append("Document: ").append(file.nameWithoutExtension).append("\n")
                sb.append("Total Pages: ").append(renderer.pageCount).append("\n")
            }
            renderer.close()
            pfd.close()
        } catch (e: Exception) {
            // Ignore
        }

        return if (sb.isBlank()) file.nameWithoutExtension else sb.toString().trim()
    }

    private fun extractImageText(file: File): String {
        val sb = StringBuilder()
        sb.append("Image: ").append(file.nameWithoutExtension).append("\n")
        try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            sb.append("Dimensions: ").append(options.outWidth).append("x").append(options.outHeight).append("\n")
            sb.append("Type: ").append(options.outMimeType ?: "unknown")
        } catch (e: Exception) {
            // Ignore
        }
        return sb.toString().trim()
    }
}
