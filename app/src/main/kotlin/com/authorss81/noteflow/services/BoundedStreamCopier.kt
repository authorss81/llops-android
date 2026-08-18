package com.authorss81.noteflow.services

import java.io.InputStream
import java.io.OutputStream

/**
 * B1-PLAT-2 (phase-58): a hard byte budget for copying an attacker-controlled
 * stream into app-private storage.
 *
 * The old share-ingest path (`MainActivity.copySharedUris`) used the unbounded
 * `input.copyTo(out)` so an `ACTION_SEND` could stage an arbitrary amount of
 * bytes into `filesDir/shared` (storage-exhaustion DoS). Every shared stream
 * now flows through [copyBounded], which enforces a per-stream cap and a
 * running total cap against the ACTUAL bytes read and throws
 * [ImportArchivePolicy.ImportSizeLimitException] the moment a budget is
 * exceeded — clean, non-alarming, fail-closed.
 *
 * Pure JVM so it is unit-tested without a device.
 */
object BoundedStreamCopier {

    /** Mirrors [com.authorss81.noteflow.plugins.clipshare.SharedClipParser.MAX_SINGLE_STREAM_BYTES]. */
    const val MAX_SINGLE_STREAM_BYTES = 50L * 1024 * 1024

    /** Mirrors [com.authorss81.noteflow.plugins.clipshare.SharedClipParser.MAX_TOTAL_BYTES]. */
    const val MAX_TOTAL_BYTES = 200L * 1024 * 1024

    private const val BUFFER_SIZE = 8192

    /** Consecutive zero-progress reads tolerated before [copyBounded] bails
     *  (mirrors `AttachmentIngestPolicy`'s phase-81 idle-read guard). */
    const val MAX_CONSECUTIVE_IDLE_READS = 16

    /**
     * Copies [input] to [output] while guaranteeing that at most [maxBytes]
     * source bytes are written. Raises [ImportArchivePolicy.ImportSizeLimitException]
     * before a chunk that would exceed the budget (so the target never holds
     * over-budget bytes) and returns the number of bytes actually written.
     *
     * R2-B1P-04 (phase-142): a stream whose `read()` returns 0 forever without
     * ever reaching EOF (contract-legal for a hostile ContentProvider) can no
     * longer busy-spin the calling IO thread — after [MAX_CONSECUTIVE_IDLE_READS]
     * consecutive zero-progress reads it throws [java.io.IOException] (the same
     * idle-read bailout phase-81 added to
     * `AttachmentIngestPolicy.boundedReadBytes`).
     */
    fun copyBounded(input: InputStream, output: OutputStream, maxBytes: Long): Long {
        require(maxBytes >= 0L) { "maxBytes must be non-negative" }
        val buffer = ByteArray(BUFFER_SIZE)
        var total = 0L
        var idleReads = 0
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            if (read == 0) {
                if (++idleReads > MAX_CONSECUTIVE_IDLE_READS) {
                    throw java.io.IOException("Stream made no progress; aborting bounded copy")
                }
                continue
            }
            idleReads = 0
            if (total + read > maxBytes) {
                throw ImportArchivePolicy.ImportSizeLimitException(
                    "Shared content is too large to clip (max 50 MB per item, 200 MB total)."
                )
            }
            output.write(buffer, 0, read)
            total += read
        }
        return total
    }
}