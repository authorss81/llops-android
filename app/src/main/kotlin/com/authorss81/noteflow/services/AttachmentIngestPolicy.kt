package com.authorss81.noteflow.services

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * B2-DOS-05 (phase-81): single decision table + bounded reader for ATTACHMENT and
 * IMPORT ingestion of attacker- or user-supplied files.
 *
 * Pre-fix, several picker/import paths read the WHOLE source into heap with no
 * cap:
 *  - `EditorScreen.kt` photo-embed / custom-background / paper-texture pickers did
 *    `contentResolver.openInputStream(uri)?.use { it.readBytes() }` — a 500 MB
 *    "photo" was fully slurped into a [ByteArrayOutputStream], then re-copied for
 *    the persisted file; OOM crash at embed time.
 *  - `NoteflowViewModel.restoreEncryptedBackupFromZip` did `sourceZip.readBytes()`
 *    on the WebDAV-downloaded archive before handing it to importBackup.
 *  - `DocumentTextExtractor` did `file.readText()` on a large .txt (only the else
 *    branch at :40 carried a 1 MB guard) and `extractPdfText` did
 *    `file.readBytes()` on the WHOLE PDF plus a second full String copy.
 *
 * The fix bounds every such read DURING the read (never after the whole body is in
 * heap): [boundedReadBytes] streams under [MAX_ATTACHMENT_BYTES] with a running
 * byte counter and aborts mid-stream (raising
 * [ImportArchivePolicy.ImportSizeLimitException]) on the first chunk that crosses
 * the cap, so the accumulator can never exceed the cap plus one read buffer.
 *
 * Pure JVM — plain `java.io`, no `android.*`, API 26+ floor, no fallback needed.
 */
object AttachmentIngestPolicy {

    /**
     * Hard ceiling for a single attachment / imported text source (bytes).
     * 25 MB comfortably holds any embedded photo, PDF text extract head, markdown
     * source, or paper texture while guaranteeing an OOM on a 1 GB device cannot
     * be reached from a single ingest.
     */
    const val MAX_ATTACHMENT_BYTES: Long = 25L * 1024 * 1024

    /** Read buffer for the bounded loop (bounds the over-read on abort). */
    const val READ_BUFFER_BYTES: Int = 64 * 1024

    /** Over-budget means the stream exceeds [maxBytes]. */
    fun isOverBudget(totalBytes: Long, maxBytes: Long = MAX_ATTACHMENT_BYTES): Boolean =
        totalBytes > maxBytes

    /**
     * Reads at most the first [maxBytes] bytes of [file] (never the whole file),
     * decoded as UTF-8. Missing/unreadable/empty files yield "". A [maxBytes] of
     * 0 yields "". The read is head-bounded so a multi-GB file can never pin its
     * full size in heap; any multi-byte UTF-8 sequence split at the boundary
     * decodes lossily.
     */
    fun readTextHead(file: File, maxBytes: Long = MAX_ATTACHMENT_BYTES): String {
        if (!file.exists() || !file.canRead()) return ""
        require(maxBytes >= 0L) { "maxBytes must be non-negative" }
        if (maxBytes == 0L || file.length() <= 0L) return ""
        val out = ByteArrayOutputStream()
        try {
            FileInputStream(file).use { input ->
                val buf = ByteArray(READ_BUFFER_BYTES)
                var remaining = minOf(file.length(), maxBytes)
                while (remaining > 0L) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    remaining -= n
                }
            }
        } catch (e: Exception) {
            return ""
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /**
     * Streams [input] into a [ByteArray] while guaranteeing at most [maxBytes]
     * source bytes are read — the cap is enforced DURING the read. Throws
     * [ImportArchivePolicy.ImportSizeLimitException] on the first chunk that
     * pushes the total over [maxBytes], so the caller never receives a byte array
     * that exceeded the budget and the heap-pinned accumulator never grows past
     * roughly [maxBytes]. Re-throws [ImportArchivePolicy.ImportSizeLimitException]
     * verbatim (never swallows it) so the caller can surface ONE clean,
     * non-alarming message.
     */
    fun boundedReadBytes(
        input: InputStream,
        maxBytes: Long = MAX_ATTACHMENT_BYTES
    ): ByteArray {
        require(maxBytes >= 0L) { "maxBytes must be non-negative" }
        val out = ByteArrayOutputStream()
        val buf = ByteArray(READ_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) continue
            total += n
            if (total > maxBytes) {
                throw ImportArchivePolicy.ImportSizeLimitException(
                    "File is too large to import (max ${maxBytes / (1024L * 1024L)}MB)."
                )
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
