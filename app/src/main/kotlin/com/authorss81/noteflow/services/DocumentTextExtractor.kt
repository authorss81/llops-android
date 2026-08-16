package com.authorss81.noteflow.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import com.authorss81.noteflow.data.model.NotePageEntity
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
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

    /**
     * B2-DOS-05 (phase-81): the biggest file region a document-text extraction may
     * ever read into heap. Both the `.txt` head and the PDF-operator scan abort
     * beyond this budget, so a multi-GB imported text file / PDF can never pin its
     * full size (or a full String copy) into memory.
     */
    private const val MAX_EXTRACT_BYTES = 25L * 1024 * 1024

    /**
     * B2-DOS-05 (phase-81): head-of-file budget for TEXT documents. This keeps the
     * old `else < 1_000_000` guard's spirit (head read, small capped String) with a
     * single explicit constant, so a multi-GB `.txt` yields the first MB of text
     * instead of an OOMing full-file read or an empty result.
     */
    private const val MAX_TEXT_HEAD_BYTES = 1L * 1024 * 1024

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
                "text" -> readTextBounded(file)
                else -> {
                    val ext = file.extension.lowercase()
                    if (ext == "pdf") extractPdfText(file)
                    else if (ext in listOf("jpg", "jpeg", "png", "webp")) extractImageText(file)
                    else readTextBounded(file)
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
            // B2-DOS-05 (phase-81): only the first N MB of a large PDF are scanned
            // for text operators — a whole-PDF readBytes() (plus a second String
            // copy) previously pinned arbitrary PDF sizes into heap.
            val bytes = readFirstBytesBounded(file, MAX_EXTRACT_BYTES)
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

    /**
     * Reads the first [MAX_TEXT_HEAD_BYTES] bytes of [file] (bounded), so a
     * multi-GB text document never materializes fully in heap. Mirrors the
     * `else < 1_000_000` guard's intent but with a single consistent budget and
     * a head read (never the whole file).
     */
    private fun readTextBounded(file: File): String {
        val bytes = readFirstBytesBounded(file, MAX_TEXT_HEAD_BYTES)
        if (bytes.isEmpty()) return ""
        // Decode up to the extract budget as UTF-8; lossily truncate any
        // multi-byte character split at the boundary.
        return String(bytes, Charsets.UTF_8)
    }

    /**
     * Reads at most [maxBytes] bytes from the start of [file]. Returns
     * an empty array for unreadable/missing files. Never reads past the budget,
     * so a crafted oversized file cannot drive heap growth here.
     */
    private fun readFirstBytesBounded(file: File, maxBytes: Long): ByteArray {
        if (!file.exists()) return ByteArray(0)
        if (!file.canRead() || file.length() <= 0L) return ByteArray(0)
        require(maxBytes >= 0L) { "maxBytes must be non-negative" }
        val out = ByteArrayOutputStream()
        return try {
            FileInputStream(file).use { input ->
                val buf = ByteArray(DocumentTextExtractorReadBuffer)
                var remaining = minOf(file.length(), maxBytes)
                while (remaining > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
                out.toByteArray()
            }
        } catch (e: Exception) {
            ByteArray(0)
        }
    }
}

/** Size of the fixed read buffer used by [DocumentTextExtractor]'s bounded head read. */
private const val DocumentTextExtractorReadBuffer = 64 * 1024