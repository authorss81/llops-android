package com.authorss81.noteflow.services

/**
 * B2-LOG-05 (phase-94): user-facing WebDAV sync failure text must NEVER echo raw
 * exception messages or raw URL-derived text into the sync-status UI.
 *
 * Pre-fix (`WebDavSyncService.kt`):
 *  - `validateServerUrl` wrapped the JVM `MalformedURLException` message — which
 *    contains the user's RAW typed input — into
 *    `Invalid WebDAV server URL: <e.message>`, so a paste like
 *    `https://user:pass@host/…` exported its embedded credentials as soon as the
 *    URL failed to parse, and it RETURNED the raw typed string (userinfo intact)
 *    as the "normalized" URL on the happy path;
 *  - the connection/upload/download blanket catches rendered
 *    `Connection/Upload/Download failed: <localizedMessage ?: e.message>` —
 *    arbitrary JVM exception text (MalformedURLException `no protocol:` dumps,
 *    SSL hostname errors, connect-refused reasons) shown verbatim in
 *    `WebDavSyncDialog`'s status surface — a shoulder-surf / screenshot
 *    disclosure of credentials typed into the URL field, and an echo channel for
 *    server-controlled PROPFIND href text.
 *
 * This policy is the single pure-JVM decision table (the `FailureLogPolicy`
 * B2-LOG-03 "never read the exception object" pattern applied to UI text):
 *  - FIXED, human messages for every failure category — never built from the
 *    exception (the three blanket catches drop to `CONNECT/_UPLOAD/_DOWNLOAD_`
 *    `_FAILURE_MESSAGE`); auth + HTTP-status failures were already fixed strings
 *    at their response-code branch and are untouched;
 *  - [stripUrlUserInfo] removes every `scheme://<userinfo>@` segment — applied
 *    by `validateServerUrl` so a pasted credential-bearing URL is never stored,
 *    rebuilt or echoed;
 *  - [scrubForDisplay] is the ONE sanitizer applied wherever URL-derived text
 *    must reach the UI: strips userinfo AND collapses `scheme://host/path` to
 *    the bare `host/...` (the path may be server-controlled PROPFIND text);
 *  - [refusalReason] is a FIXED reason token classifying a href/connection
 *    defusal (network-path / off-origin / malformed / non-HTTPS / other)
 *    without echoing the href or the configured URL.
 *
 * API 26+ floor, pure JVM (`java.util.regex` only) — no fallback needed.
 */
object WebDavFailurePolicy {

    /**
     * Fixed "connection" failure text — the pre-fix
     * `Connection failed: <localizedMessage ?: e.message>` is gone; a connect
     * refusal/SSL/IO failure maps HERE, never to the exception's own text.
     */
    const val CONNECT_FAILURE_MESSAGE: String =
        "Could not connect to your WebDAV server. Check the server address, your " +
            "credentials and your internet connection, then try again."

    /** Fixed "upload" failure text (was `Upload failed: <localizedMessage ?: e.message>`). */
    const val UPLOAD_FAILURE_MESSAGE: String =
        "Could not upload your encrypted backup to the WebDAV server. Check the " +
            "server and your connection, then try again."

    /** Fixed "download" failure text (was `Download failed: <localizedMessage ?: e.message>`). */
    const val DOWNLOAD_FAILURE_MESSAGE: String =
        "Could not download the remote backup from the WebDAV server. Check the " +
            "server and your connection, then try again."

    /** Fixed "archive too large" text (was the `DownloadTooLargeException` message). */
    const val TOO_LARGE_DOWNLOAD_MESSAGE: String =
        "The remote backup archive exceeded the download size limit; the download was stopped."

    /** Fixed "listing too large" text (B2-DOS-08 — never echoes size or URL text). */
    const val LISTING_TOO_LARGE_MESSAGE: String =
        "The WebDAV server's file listing was too large to process safely; the sync was stopped."

    /**
     * Fixed "unusable URL" text. The pre-fix
     * `Invalid WebDAV server URL: <e.message>` wrapped the `MalformedURLException`
     * dump of the user's RAW input; this fixed string never echoes the input.
     */
    const val INVALID_URL_MESSAGE: String =
        "Invalid WebDAV server URL. It must start with https:// and name your " +
            "server host, e.g. https://cloud.example.com/remote.php/dav."

    /**
     * `scheme://<userinfo>@` — matches a URL-scheme separator followed by an
     * (optional) userinfo run that contains neither `/` nor `@`, then `@`. The
     * scheme prefix is captured so it can be re-emitted.
     */
    private val URL_USERINFO_REGEX = Regex("([A-Za-z][A-Za-z0-9+.-]*://)[^/@\\s]*@")

    /**
     * `scheme://host[:port][/path…]` — the authority (`host[:port]`) is captured
     * and any `/path` suffix is captured separately so it can be collapsed.
     */
    private val URL_TOKEN_REGEX = Regex("([A-Za-z][A-Za-z0-9+.-]*://)([^/\\s]+)(/\\S*)?")

    /**
     * Removes EVERY `scheme://<userinfo>@` segment from [url], leaving
     * `scheme://host[:port][/path]`. Applied by `WebDavSyncService.validateServerUrl`
     * so a URL pasted as `https://user:pass@host/…` is never stored, rebuilt or
     * echoed with its embedded credentials — the app's own credentials ride
     * ONLY in the separate username/password fields (`Authorization` header).
     */
    fun stripUrlUserInfo(url: String): String =
        URL_USERINFO_REGEX.replace(url) { m -> m.groupValues[1] }

    /**
     * The single sanitizer for URL-derived text that reaches the UI: strips any
     * `scheme://<userinfo>@` segment and collapses every `scheme://host/path`
     * token to the bare `host/...` form. The path — which may be
     * server-controlled PROPFIND text or carry credentials — is never echoed. The
     * host name is kept (it is the user's own configuration, not a secret), so
     * the resulting message stays diagnostic.
     */
    fun scrubForDisplay(text: String): String {
        if (text.isEmpty()) return text
        val noUserInfo = stripUrlUserInfo(text)
        return URL_TOKEN_REGEX.replace(noUserInfo) { m ->
            val authority = m.groupValues[2]
            if (m.groupValues[3].isEmpty()) authority else "$authority/..."
        }
    }

    /**
     * Safe text for a configuration/validation [IllegalArgumentException]: the
     * message is run through [scrubForDisplay] (defense-in-depth against any
     * URL-derived fragment) and falls back to [fallback] when empty — the caller
     * passes a fixed constant, never a secret-bearing value.
     */
    fun configFailureText(e: Exception, fallback: String): String =
        scrubForDisplay(e.message.orEmpty()).ifBlank { fallback }

    /**
     * A FIXED, human reason token classifying a href/connection defusal, so a
     * "Sync refused" result explains the cause without echoing the href or any
     * URL-derived text (the `WebDavHrefResolver` messages that carried `$raw` /
     * `$requestUrl` / `$urlString` do not reach the UI anymore).
     */
    fun refusalReason(e: Exception): String {
        val msg = scrubForDisplay(e.message.orEmpty()).lowercase()
        return when {
            msg.contains("network-path") ->
                "the server returned a network-path reference for the backup file."
            msg.contains("outside the configured server") ||
                msg.contains("not the configured") ->
                "the server returned a backup-file link that points outside your " +
                    "configured WebDAV server."
            msg.contains("non-http") ->
                "the server returned a backup-file link that is not HTTPS."
            msg.contains("malformed") ||
                msg.contains("no host") ||
                msg.contains("does not resolve") ||
                msg.contains("could not resolve") ->
                "the server returned an unusable backup-file link."
            else ->
                "the server returned an unexpected backup-file link."
        }
    }
}