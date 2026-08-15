package com.authorss81.noteflow.services

import java.io.IOException
import java.net.URI

/**
 * B1-NET-05 (phase-52): shared manual-redirect policy for every
 * `HttpURLConnection`-based TLS transport in the base app.
 *
 * Before phase-52 every such client relied on the platform default
 * `instanceFollowRedirects = true`, so an `https://` server answering
 * `307 Location: http://...` made the request continue over plaintext:
 * the "HTTPS only" gate (`requireSecureUrl` / `url.startsWith("https://")`)
 * ran *once* on the initial URL, never on the redirected connection.
 *
 * This helper is the single place a transport states its hop policy:
 * [checkTlsHop] validates a URL we are about to CONNECT to (the entry URL and
 * every resolved redirect target) — scheme must be `https` (a non-HTTPS hop is
 * refused, never downgraded) and the host must pass the B1-NET-04
 * [SsrfHostPolicy] blocklist (so a redirect can never land on `localhost`, a
 * LAN/private IP or the cloud-metadata ranges). [resolveNextTlsHop] resolves a
 * 3xx `Location` (RFC 3986 against the current hop) and validates it the same
 * way, rejecting redirect loops.
 *
 * All clients using it MUST run `conn.instanceFollowRedirects = false` (each
 * transport still sets it explicitly) and follow at most [MAX_REDIRECTS] hops.
 * Pure JVM — no `android.*`, no DNS — so unit tests run without a network.
 */
object StrictRedirectPolicy {

    /** Maximum manual redirect hops a transport will follow. */
    const val MAX_REDIRECTS: Int = 5

    /** Thrown when a hop (entry URL or a 3xx target) must not be connected to. */
    class RedirectRefusedException(message: String) : IOException(message)

    /**
     * Validate [uri] as a hop of a TLS-required transport: scheme MUST be
     * `https`, it MUST have a host, and that host MUST pass the B1-NET-04
     * SSRF blocklist. Throws [RedirectRefusedException] on any violation.
     */
    fun checkTlsHop(uri: URI) {
        if (uri.scheme?.lowercase() != "https") {
            throw RedirectRefusedException(
                "Refusing to connect over a non-HTTPS channel (HTTPS is required)."
            )
        }
        val host = uri.host
        if (host.isNullOrBlank()) {
            throw RedirectRefusedException("Refusing to connect to a URL without a host.")
        }
        val blocked = SsrfHostPolicy.blockedReason(host)
        if (blocked != null) {
            throw RedirectRefusedException("Connection blocked: $blocked")
        }
    }

    /**
     * Resolve a 3xx [location] against the current hop [cur] and validate the
     * result as a TLS hop via [checkTlsHop]. Returns null when [location] is
     * blank (the caller treats the response as a redirect without a usable
     * target). Throws [RedirectRefusedException] on any violation — including
     * a target that resolves back to [cur] (redirect loop).
     */
    fun resolveNextTlsHop(cur: URI, location: String?): URI? {
        if (location.isNullOrBlank()) return null
        val resolved = try {
            cur.resolve(location)
        } catch (e: Exception) {
            throw RedirectRefusedException("Refusing to follow a malformed redirect target.")
        }
        if (resolved.toString() == cur.toString()) {
            throw RedirectRefusedException("Refusing to follow a redirect loop.")
        }
        checkTlsHop(resolved)
        return resolved
    }
}