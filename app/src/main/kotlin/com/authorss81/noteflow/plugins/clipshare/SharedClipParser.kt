package com.authorss81.noteflow.plugins.clipshare

import com.authorss81.noteflow.plugins.ClipKind
import com.authorss81.noteflow.plugins.ClipParseOutcome
import com.authorss81.noteflow.plugins.SharedClip
import com.authorss81.noteflow.plugins.SharedInput
import com.authorss81.noteflow.plugins.SharedStream

/**
 * PURE-JVM classification + validation of incoming share-sheet content
 * ("Clip to InkFlow", Phase 15).
 *
 * The Android glue (MainActivity's share-intent reading + copying the streams
 * into app storage) is platform-only; everything that can be tested without a
 * device lives here: deciding whether a clip is text vs image vs file vs
 * multipart, applying the size guard, and rejecting empty/oversized shares with
 * a clear user-facing reason. A rejected share NEVER reaches the encrypted
 * note store.
 *
 * Size guards mirror the app's backup limits (single item 50 MB, total 200 MB)
 * so a giant share can't blow up app-private storage or slow the UI.
 */
object SharedClipParser {

    const val ACTION_SEND = "android.intent.action.SEND"
    const val ACTION_SEND_MULTIPLE = "android.intent.action.SEND_MULTIPLE"

    const val MAX_SINGLE_STREAM_BYTES = 50L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
    const val MAX_TEXT_BYTES = 5 * 1024 * 1024

    @JvmStatic
    fun parse(input: SharedInput): ClipParseOutcome {
        val streams = input.streams.filter { it.uriString.isNotBlank() }
        val isBlankText = input.text.isNullOrBlank()

        if (isBlankText && streams.isEmpty()) {
            return ClipParseOutcome.Rejected("Nothing to clip \u2014 the shared content was empty.")
        }

        for (stream in streams) {
            val size = stream.sizeBytes
            if (size != null && size > MAX_SINGLE_STREAM_BYTES) {
                return ClipParseOutcome.Rejected("One shared item exceeds 50 MB and can't be clipped.")
            }
        }
        val knownTotal = streams.sumOf { it.sizeBytes ?: 0L }
        if (streams.isNotEmpty() && knownTotal > MAX_TOTAL_BYTES) {
            return ClipParseOutcome.Rejected("Shared content exceeds 200 MB total and can't be clipped.")
        }

        val text = input.text?.trim().orEmpty()
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
            return ClipParseOutcome.Rejected("Shared text is too large (max 5 MB).")
        }

        val allImages = streams.isNotEmpty() && streams.all { it.mimeType?.lowercase()?.startsWith("image/") == true }
        val parsedStreams = if (streams.isEmpty()) emptyList() else streams

        val kind: ClipKind = when {
            parsedStreams.isEmpty() -> ClipKind.TEXT
            allImages && text.isEmpty() -> ClipKind.IMAGES
            allImages -> ClipKind.MULTIPART
            text.isEmpty() -> ClipKind.FILES
            else -> ClipKind.MULTIPART
        }
        return ClipParseOutcome.Success(
            SharedClip(
                kind = kind,
                text = text.ifEmpty { null },
                streams = parsedStreams
            )
        )
    }
}