package com.authorss81.noteflow.services

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * B1-NET-07 (phase-86): single decision table for the WebDAV remote-listing →
 * download slice of the sync engine. Pure JVM (plain `java.io`/`java.time`/`java.lang`,
 * no `android.*`), so every rule is unit-testable on desktop — API 26+ floor
 * (java.time landed in API 26, our minSdk), no fallback needed.
 *
 * Pre-fix (`WebDavSyncService.kt`):
 *  - the "latest" backup was `matches.last()` — the last href in XML DOCUMENT
 *    ORDER, not the newest by filename timestamp, so a server (malicious or
 *    simply Nextcloud-ordered) returning non-chronological hrefs silently served
 *    an OLDER backup for "Download & Restore" (data rollback);
 *  - the `.nfb` GET was streamed with `input.copyTo(output)` and NO size limit,
 *    so a malicious server could stream an unbounded body into the app's cache
 *    (`webdav_download_import.nfb`) — disk-exhaustion DoS;
 *  - `remoteFolderName` was interpolated into every URL path without
 *    percent-encoding, so a folder name like `../../Other` (or `%2e%2e%2f`)
 *    routed uploads/downloads at unintended server paths.
 *
 * This policy:
 *  - [findBackupHrefs] parses the PROPFIND body (regex unchanged from the
 *    pre-fix code so old and new names keep matching);
 *  - [filenameTimestampMillis] extracts each remote filename's timestamp — the
 *    legacy `noteflow_vault_backup_<epochMillis>.nfb` form AND the current
 *    day-granular `noteflow_vault_backup_<yyyy-MM-dd>_<token>.nfb` form
 *    (B2-CRYPTO-06) — as one comparable epoch-millis value;
 *  - [newestBackupHref] picks the href with the MAXIMUM timestamp (unparseable
 *    names sort last; same-timestamp ties break deterministically by href, never
 *    by XML order);
 *  - [copyBounded] streams the download into the target file under
 *    [MAX_DOWNLOAD_BYTES], aborting mid-stream ([DownloadTooLargeException]) on
 *    the first chunk that crosses the cap — the target file never exceeds the
 *    budget (mirrors `FacadeHttpGetPolicy`/`AttachmentIngestPolicy`);
 *  - [encodedRemoteFolderSegment] validates [config.remoteFolderName] as ONE
 *    path segment (rejects blank, `.`/`..`, separators and control characters)
 *    and percent-encodes it RFC 3986 so it can never escape the folder's own path.
 */
object WebDavRemoteListingPolicy {

    /**
     * Hard ceiling for a single WebDAV backup download (bytes). Deliberately
     * aligned with `ImportExportService.MAX_BACKUP_INPUT_BYTES` (400 MB): the
     * restore path already refuses larger archives, so downloading more would be
     * pure disk waste and the finding's disk-exhaustion vector.
     */
    const val MAX_DOWNLOAD_BYTES: Long = 400L * 1024 * 1024

    /** Copy buffer for the bounded download (bounds the over-read on abort). */
    const val COPY_BUFFER_BYTES: Int = 64 * 1024

    private const val BACKUP_PREFIX = "noteflow_vault_backup_"
    private val LEGACY_MILLIS_REGEX = Regex("${Regex.escape(BACKUP_PREFIX)}(\\d{10,})\\.nfb$")
    private val DAY_STAMP_REGEX = Regex("${Regex.escape(BACKUP_PREFIX)}(\\d{4}-\\d{2}-\\d{2})_.*\\.nfb$")

    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    private val HEX = "0123456789ABCDEF"

    /** Raised by [copyBounded] mid-stream when the download budget is exceeded. */
    class DownloadTooLargeException(message: String) : IOException(message)

    /**
     * The remote-listing href regex. Kept byte-identical to the pre-fix
     * `WebDavSyncService.kt` regex so both the legacy epoch-millis names and the
     * B2-CRYPTO-06 day-granular names keep matching.
     */
    val BACKUP_HREF_REGEX: Regex =
        Regex("<d:href>([^<]+noteflow_vault_backup_[^<]+\\.nfb)</d:href>", RegexOption.IGNORE_CASE)

    /**
     * Extracts the candidate backup hrefs from a `Depth: 1` PROPFIND response in
     * the order they appear in the document. Callers MUST NOT treat this order as
     * "newest" — use [newestBackupHref].
     */
    fun findBackupHrefs(xmlResponse: String): List<String> =
        BACKUP_HREF_REGEX.findAll(xmlResponse).map { it.groupValues[1] }.toList()

    /**
     * Picks the href whose remote filename carries the MAXIMUM timestamp —
     * never the last href in XML document order. Unparseable names score the
     * lowest (only selected when nothing else carries a timestamp), and
     * same-timestamp ties break deterministically on the full href (descending)
     * so the result is stable regardless of how the server orders the response.
     */
    fun newestBackupHref(hrefs: List<String>): String? {
        if (hrefs.isEmpty()) return null
        // Timestamps compared ASCENDING (newest = comparator-maximum) — combining
        // compareByDescending with maxWithOrNull would hand back the OLDEST file
        // (review-fix: phase-86 shipped that inversion, picking the minimum).
        val comparator = compareBy<Pair<String, Long?>> { it.second ?: Long.MIN_VALUE }
            .thenByDescending { it.first }
        return hrefs.map { href -> href to filenameTimestampMillis(href) }
            .maxWithOrNull(comparator)
            ?.first
    }

    /**
     * Epoch-millis timestamp of the backup FILENAME embedded in [href], or null
     * when it cannot be derived. Understands both name generations:
     *  - legacy `noteflow_vault_backup_<epochMillis>.nfb` (phase-06 format) →
     *    the digits ARE the millis;
     *  - current `noteflow_vault_backup_<yyyy-MM-dd>_<token>.nfb` (B2-CRYPTO-06
     *    day-granular) → midnight-UTC millis of the ISO date.
     */
    fun filenameTimestampMillis(href: String): Long? {
        val name = href.substringAfterLast('/')
        if (!name.startsWith(BACKUP_PREFIX)) return null
        LEGACY_MILLIS_REGEX.matchEntire(name)?.let { m ->
            return m.groupValues[1].toLongOrNull()
        }
        DAY_STAMP_REGEX.matchEntire(name)?.let { m ->
            return runCatching { LocalDate.parse(m.groupValues[1]) }
                .getOrNull()
                ?.atStartOfDay()
                ?.toInstant(ZoneOffset.UTC)
                ?.toEpochMilli()
        }
        return null
    }

    /**
     * Validates and RFC-3986 percent-encodes [config.remoteFolderName] as ONE
     * URL path segment. Rejects (throws [IllegalArgumentException]):
     *  - blank/whitespace-only names,
     *  - the `.`/`..` traversal segments,
     *  - any path separator (`/` or `\`) — a single segment may never imply a
     *    path escape, and
     *  - control characters (`\u0000`-`\u001f`, `\u007f`).
     * The returned string is a properly encoded segment safe to concatenate into
     * a URL path (e.g. `My Folder` → `My%20Folder`).
     */
    fun encodedRemoteFolderSegment(remoteFolderName: String): String {
        if (remoteFolderName.isBlank()) {
            throw IllegalArgumentException("WebDAV folder name cannot be empty.")
        }
        if (remoteFolderName == "." || remoteFolderName == "..") {
            throw IllegalArgumentException("WebDAV folder name cannot be '.' or '..'.")
        }
        for (c in remoteFolderName) {
            if (c == '/' || c == '\\') {
                throw IllegalArgumentException(
                    "WebDAV folder name must be a single path segment (no '/' or '\\')."
                )
            }
            if (c.code < 0x20 || c.code == 0x7F) {
                throw IllegalArgumentException(
                    "WebDAV folder name must not contain control characters."
                )
            }
        }
        return percentEncodePathSegment(remoteFolderName)
    }

    /**
     * Bounded download stream. Copies [input] into [output] while guaranteeing
     * at most [maxBytes] source bytes are consumed — the cap is enforced DURING
     * the read, so the target file can never exceed roughly [maxBytes]. Throws
     * [DownloadTooLargeException] on the first chunk that pushes the total over
     * the cap and [IOException] if the stream stalls for [IDLE_READ_LIMIT]
     * consecutive empty reads (a contract-breaking stream can never busy-spin).
     * Returns the total bytes copied.
     */
    fun copyBounded(
        input: InputStream,
        output: OutputStream,
        maxBytes: Long = MAX_DOWNLOAD_BYTES
    ): Long {
        require(maxBytes >= 0L) { "maxBytes must be non-negative" }
        val buf = ByteArray(COPY_BUFFER_BYTES)
        var total = 0L
        var idleReads = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (n == 0) {
                if (++idleReads > IDLE_READ_LIMIT) {
                    throw IOException("Download stream made no progress; aborting bounded download")
                }
                continue
            }
            idleReads = 0
            total += n
            if (total > maxBytes) {
                throw DownloadTooLargeException(
                    "Remote backup archive is too large — refusing to download more than " +
                        "${maxBytes / (1024L * 1024L)} MB (the restore budget)."
                )
            }
            output.write(buf, 0, n)
        }
        return total
    }

    internal const val IDLE_READ_LIMIT: Int = 16

    /** RFC 3986 percent-encoding of a single path segment (UTF-8 byte-based). */
    private fun percentEncodePathSegment(raw: String): String = buildString(raw.length * 3) {
        for (b in raw.toByteArray(Charsets.UTF_8)) {
            val v = b.toInt() and 0xFF
            val c = v.toChar()
            if (v < 0x80 && c in UNRESERVED) {
                append(c)
            } else {
                append('%')
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
    }
}