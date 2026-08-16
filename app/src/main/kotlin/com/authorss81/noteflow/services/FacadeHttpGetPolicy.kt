package com.authorss81.noteflow.services

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * B2-DOS-04 (phase-80): single decision table for the BOUNDED body read of a
 * plugin-granted `FacadeHost.httpGet`.
 *
 * Pre-fix, `AppFacadeHost.httpGet` checked the size cap in two places that both
 * let an unbounded response through:
 *  - a pre-check against `HttpURLConnection.contentLengthLong` — skipped for
 *    chunked/unknown-length responses (the header is `-1`), and
 *  - a post-read `stream.readBytes()` that slurped the WHOLE body into heap
 *    (via an unbounded `ByteArrayOutputStream` inside `readBytes()`) before the
 *    `bytes.size > MAX` comparison ever ran.
 *
 * A granted plugin pointing at a slow-chunked endpoint therefore pinned
 * hundreds of MB in heap with ZERO cap enforcement during the read and OOM'd
 * the process. [readCapped] mirrors `WebPageFetcher.readCapped`: a bounded
 * streaming loop that aborts mid-read ([ResponseTooLargeException]) on the
 * first chunk that crosses [MAX_FACADE_GET_BYTES] — the accumulator can never
 * exceed the cap, and the read buffer bounds the over-read to one extra chunk.
 *
 * Pure JVM — plain `java.io`/`java.lang`, no `android.*`, API 26+ floor with no
 * platform requirement and no fallback needed (works identically on every
 * supported API).
 */
object FacadeHttpGetPolicy {

    /** Hard ceiling for a single HTTP GET response body (bytes). */
    const val MAX_FACADE_GET_BYTES: Long = 10L * 1024 * 1024

    /** Read buffer for the bounded loop (bounds the over-read on abort). */
    const val READ_BUFFER_BYTES: Int = 64 * 1024

    /** Raised by [readCapped] mid-stream when the budget is exceeded. */
    class ResponseTooLargeException(message: String) : IOException(message)

    /**
     * Stream [input] into a UTF-8 [String], enforcing [MAX_FACADE_GET_BYTES]
     * DURING the read. Throws [ResponseTooLargeException] on the first read
     * that pushes the total over the cap — the caller never receives a body
     * that exceeded the budget, and the heap-pinned accumulator never grows
     * past roughly [MAX_FACADE_GET_BYTES].
     */
    fun readCapped(input: InputStream): String {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(READ_BUFFER_BYTES)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > MAX_FACADE_GET_BYTES) {
                throw ResponseTooLargeException("HTTP GET response too large.")
            }
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}