package com.authorss81.noteflow.services

import java.net.MalformedURLException
import java.net.URI
import java.net.URISyntaxException
import java.net.URL

/**
 * B1-NET-01 fix: resolves a server-supplied PROPFIND `href` into a download URL
 * WITHOUT ever leaving the user's configured WebDAV origin, and gates every
 * connection so the Basic credentials are only ever attached to the configured
 * server itself.
 *
 * Pure JVM (java.net only), so the exact behavior is unit-testable on desktop.
 *
 * Threat (docs/security-report.md B1-NET-01, HIGH): a compromised or malicious
 * WebDAV server answers the PROPFIND listing with
 * `<d:href>https://attacker.example/…nfb</d:href>`. The old code accepted the
 * absolute URL verbatim and `createConnection` attached
 * `Authorization: Basic <user:pass>` to whatever host that URL named — incl.
 * `http://169.254.169.254/…` when the user had opted into insecure HTTP for a
 * *local* server. Here every href is re-resolved against the configured origin
 * (scheme + host + port) and anything that escapes it is rejected.
 */
object WebDavHrefResolver {

    /** Normalized (scheme, host, port) triple used for origin comparison. */
    data class Origin(val scheme: String, val host: String, val port: Int)

    private fun effectivePort(url: URL): Int {
        if (url.port != -1) return url.port
        return when (url.protocol.lowercase()) {
            "https" -> 443
            "http" -> 80
            else -> throw IllegalArgumentException("Unsupported URL scheme: ${url.protocol}")
        }
    }

    /**
     * Origin of [urlString], normalized: scheme and host lowercased, host
     * trailing-dot stripped, and the implied default port materialized so
     * `https://host` == `https://host:443`.
     */
    fun originOf(urlString: String): Origin {
        val url = try {
            URL(urlString)
        } catch (e: MalformedURLException) {
            throw IllegalArgumentException("Malformed URL: $urlString")
        }
        val host = url.host ?: throw IllegalArgumentException("URL has no host: $urlString")
        return Origin(url.protocol.lowercase(), host.lowercase().trimEnd('.'), effectivePort(url))
    }

    fun sameOrigin(a: Origin, b: Origin): Boolean =
        a.scheme == b.scheme && a.host == b.host && a.port == b.port

    /**
     * Rejects [urlString] unless it lives on the same origin (scheme + host +
     * port) as [configuredServerUrl]. Runs inside `createConnection` so the
     * Basic Authorization header can never be attached to — or a connection
     * opened to — any host other than the user's configured WebDAV server.
     *
     * @throws IllegalStateException when the origins differ
     */
    fun requireConfiguredServerOrigin(urlString: String, configuredServerUrl: String) {
        if (!sameOrigin(originOf(urlString), originOf(configuredServerUrl))) {
            val expected = originOf(configuredServerUrl)
            throw IllegalStateException(
                "Refusing to send WebDAV credentials to a host that is not the configured " +
                    "server (${expected.scheme}://${expected.host}:${expected.port})."
            )
        }
    }

    /**
     * Resolves a PROPFIND `href` to an absolute download URL that stays inside
     * the configured origin.
     *
     * - absolute `href` (http/https) whose normalized origin differs from the
     *   configured server → rejected
     * - absolute `href` on the configured origin → returned unchanged
     * - relative `href` (incl. root-relative) → resolved per RFC 3986 against
     *   [requestUrl] (the URL the PROPFIND was issued against), then the final
     *   origin is re-checked so a network-path reference (`//evil.example/…`),
     *   a `../` escape or a scheme swap can never reach outside the configured
     *   origin. RFC 3986 resolution normalizes dot-segments of relative hrefs,
     *   and absolute hrefs keep their raw path — either way the same-origin
     *   gate below is what bounds the downloadable target to the configured
     *   server, so dot-segments are not separately rejected (rejecting them
     *   would false-break legitimate absolute hrefs that still stay in-origin).
     *
     * @param serverBaseUrl the user's configured server URL (origin is used)
     * @param requestUrl    the absolute URL the PROPFIND request was sent to
     * @param href          the raw `d:href` value from the server XML
     * @throws IllegalArgumentException when the href must not be followed
     */
    fun resolveDownloadHref(serverBaseUrl: String, requestUrl: String, href: String): String {
        val origin = originOf(serverBaseUrl)
        val raw = href.trim()
        if (raw.isEmpty()) {
            throw IllegalArgumentException("Empty href returned by WebDAV server.")
        }
        if (raw.startsWith("//")) {
            throw IllegalArgumentException(
                "Refusing network-path reference `$raw` — it would resolve to a host that is " +
                    "not the configured WebDAV server."
            )
        }
        val uri = try {
            URI(raw)
        } catch (e: URISyntaxException) {
            throw IllegalArgumentException("Malformed href returned by WebDAV server: $raw")
        }
        val resolved = if (uri.isAbsolute) {
            uri
        } else {
            try {
                URI(requestUrl).resolve(uri)
            } catch (e: Exception) {
                throw IllegalArgumentException("Could not resolve href `$raw` against base `$requestUrl`.")
            }
        }
        val resolvedUrl = try {
            resolved.toURL()
        } catch (e: MalformedURLException) {
            throw IllegalArgumentException("Href `$raw` does not resolve to a usable URL.")
        }
        val protocol = resolvedUrl.protocol.lowercase()
        if (protocol != "http" && protocol != "https") {
            throw IllegalArgumentException("Refusing non-HTTP(S) href from WebDAV server: $raw")
        }
        if (!sameOrigin(originOf(resolvedUrl.toString()), origin)) {
            throw IllegalArgumentException(
                "Refusing WebDAV href that resolves outside the configured server " +
                    "(${origin.scheme}://${origin.host}:${origin.port}): $raw"
            )
        }
        return resolved.toASCIIString()
    }
}