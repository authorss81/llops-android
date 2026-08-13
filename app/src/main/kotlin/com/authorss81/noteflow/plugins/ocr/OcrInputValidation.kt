package com.authorss81.noteflow.plugins.ocr

import java.io.File

/**
 * Pure, JVM-testable input validation for an OCR request.
 *
 * The model can't be run in a unit test, but the input gate absolutely can —
 * this is the "wrapping logic" the tests must cover (see `OcrPluginWrapperTest`).
 */
object OcrInputValidator {

    /**
     * Validate [imagePath] for OCR. Returns `null` when the path is usable, or
     * a user-facing reason when it isn't.
     */
    fun validateImagePath(path: String?): String? {
        if (path.isNullOrBlank()) return "No image to read — the attachment is missing its file path."
        val file = File(path)
        if (!file.exists()) return "Image file not found — it may have been moved or deleted."
        if (!file.isFile) return "The image path is not a file."
        if (!file.canRead()) return "The image file cannot be read."
        return null
    }
}

/**
 * Pure, JVM-testable normalization of raw OCR output.
 *
 * Raw model output frequently has ragged spacing and tripled blank lines (each
 * text block is a paragraph break). We normalize to single spaces within a line
 * and a single blank line between paragraphs — without ever inventing text.
 */
object OcrTextFormatter {

    /** Collapse runs of spaces/tabs to one, trim, and clamp blank lines to one. */
    fun format(raw: String): String {
        if (raw.isBlank()) return ""
        val singleSpaced = raw.replace(WHITESPACE_RUN, " ").trim()
        return singleSpaced.replace(BLANK_LINE_RUN, "\n\n")
    }

    private val WHITESPACE_RUN = Regex("[ \\t\\x0B\\f\\r]+")
    private val BLANK_LINE_RUN = Regex("\n{3,}")
}

/**
 * Pure, JVM-testable mapping of a thrown model failure to a safe, user-facing
 * message. Never embeds model output or exception content (see the framework
 * "never log content" rule) — only the exception CLASS decides the message.
 */
object OcrErrorMapper {

    fun userMessage(e: Throwable): String = when (e) {
        is kotlinx.coroutines.CancellationException -> "OCR cancelled."
        is java.io.FileNotFoundException -> "Image file not found — it may have been moved or deleted."
        is java.io.IOException -> "Could not read the image file."
        else -> "OCR failed unexpectedly — try again."
    }

    /** The "the model ran but saw no text" message. */
    fun noTextMessage(): String = "No readable text found in this image."
}
