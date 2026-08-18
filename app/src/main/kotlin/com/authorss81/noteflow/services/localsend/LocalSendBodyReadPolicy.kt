package com.authorss81.noteflow.services.localsend

import java.io.IOException
import java.io.Reader

/**
 * R2-B1N-01 (phase-142): single decision table for the BOUNDED mid-stream body
 * reads of LocalSend peer responses.
 *
 * Pre-fix, `LocalSendSender` read a LAN peer's response body UNBOUNDED and only
 * truncated the already-slurped [String] afterwards (`.take(2048)` / `.take(8192)`
 * / `.take(512)`) — so a same-Wi-Fi host answering the legacy register probe, or
 * a device claiming a paired device's IP answering `/prepare-upload`, could
 * stream an endless body and pin it in heap for the whole read timeout
 * (`LEGACY_SCAN_TIMEOUT_MS` 500 ms / `PREPARE_READ_TIMEOUT_MS` 180 s). Every
 * other network client reads a capped body mid-stream
 * ([readText](limit) in `DuckDuckGoClient`/`WeatherClient`/`DictionaryClient`/
 * `HttpsTitleFetcher`); here the same bounded loop is shared by all three
 * LocalSend read sites.
 *
 * [readText] aborts ([ResponseTooLargeException]) on the first read window that
 * pushes the total over [limit], so the accumulated [StringBuilder] can never
 * exceed the cap plus one read buffer — fail closed, never a truncation-after-
 * slurp. The caller treats an over-cap response as unusable (probe failure /
 * null body), which a well-behaved LocalSend receiver never triggers (the
 * protocol bodies are tiny JSON).
 *
 * Pure JVM — `java.io.Reader` only, no `android.*`.
 */
object LocalSendBodyReadPolicy {

    /** Cap for the legacy-HTTP register-probe response body (characters). */
    const val REGISTER_BODY_LIMIT = 2048

    /** Cap for a successful POST response body (e.g. `/prepare-upload` JSON). */
    const val SUCCESS_BODY_LIMIT = 8192

    /** Cap for an HTTP error-stream response body (used for a message suffix). */
    const val ERROR_BODY_LIMIT = 512

    /** Read window for the bounded loop (bounds the over-read on abort). */
    const val READ_BUFFER_CHARS = 2048

    /** Raised by [readText] mid-read when the cap is crossed. */
    class ResponseTooLargeException(message: String) : IOException(message)

    /**
     * Reads [reader] into a UTF-8 [String] while guaranteeing at most [limit]
     * characters are consumed. Throws [ResponseTooLargeException] on the first
     * read that pushes the total over [limit] — the caller never receives a body
     * that exceeded the budget, and the accumulated [StringBuilder] never grows
     * past roughly [limit] + [READ_BUFFER_CHARS].
     */
    fun readText(reader: Reader, limit: Int): String {
        require(limit >= 0) { "limit must be non-negative" }
        val out = StringBuilder(minOf(limit, 4096))
        val buffer = CharArray(READ_BUFFER_CHARS)
        var total = 0
        while (true) {
            val read = reader.read(buffer, 0, buffer.size)
            if (read == -1) break
            total += read
            if (total > limit) {
                throw ResponseTooLargeException(
                    "LocalSend peer response exceeds the ${limit}-character cap."
                )
            }
            out.append(buffer, 0, read)
        }
        return out.toString()
    }
}
