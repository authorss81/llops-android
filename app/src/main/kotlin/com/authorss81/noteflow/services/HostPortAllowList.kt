package com.authorss81.noteflow.services

import com.authorss81.noteflow.services.WebDavHrefResolver.Origin

/**
 * R2-B1N-05 (phase-142): the plugin-chains' host allow-lists are normalized to
 * `(scheme, host, effective-port)` [Origin] triples — a URL target is allowed
 * only when its scheme, host AND port all match an allow-list entry. The
 * port-ignoring host-only gates this replaces
 * (`CompileTimePluginPinStore.isHostAllowListed`,
 * `HttpsManifestTransport`'s host compare, `PluginDownloader`'s gate) let
 * `https://<allowed-host>:8443/...` slip through "only this host".
 *
 * Entries are kept additive (host-only list entries still work) and interpreted
 * as:
 *  - a full `http(s)://host[:port]` URL → its own `(scheme, host, effective-port)`
 *    (`https://host` == `https://host:443`, `http://host` == `http://host:80`);
 *  - a bare `host` or `host:port` name → `(https, host, port)`, defaulting to
 *    HTTPS *and* the default port 443 — plugin fetches are TLS-only, so an entry
 *    never silently widens to another scheme or port.
 *
 * A target URL matches only when it normalizes to the exact same triple; an
 * unparseable target or entry never matches (fail closed). Scheme too, so an
 * `https` entry never admits an `http` target.
 *
 * Pure JVM (`java.net` only), sharing the `WebDavHrefResolver.Origin` shape.
 */
object HostPortAllowList {

    /** Default port materialized for a scheme-less entry (HTTPS-only area). */
    const val DEFAULT_HTTPS_PORT = 443

    /**
     * Normalizes one allow-list [entry] (full URL, `host`, or `host:port`) into
     * an [Origin] triple, or null when it cannot be parsed (fail closed: an
     * unparseable entry is simply not allowed).
     */
    fun normalizeEntry(entry: String): Origin? {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.contains("://")) {
            // Full URL form — use the URL's own scheme/host/effective port
            // (http and https both have implied default ports).
            return WebDavHrefResolver.originOfOrNull(trimmed)
        }
        // Bare `host` or `host:port` form → HTTPS + default port (plugin
        // fetches are TLS-only; the default-port target is the only one held).
        // (This branch is reached only when the entry has no "://", so the
        // scheme prefixes can never appear and are not stripped.)
        val hostPort = trimmed
        val host = hostPort.substringBefore(':').trim()
        val portText = hostPort.substringAfter(':', "").trim()
        if (host.isBlank()) return null
        // A bare host name must not smuggle a path/scheme/whitespace; the URL
        // form above already routes real URLs through URL parsing.
        if (host.any { it.isWhitespace() } || host.contains('/') || host.startsWith("[")) return null
        val port = if (portText.isEmpty()) {
            DEFAULT_HTTPS_PORT
        } else {
            portText.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        }
        return Origin("https", host.lowercase().trimEnd('.'), port)
    }

    /**
     * Normalized origin of a target [url], or null when it does not parse —
     * keep-or-nothing for the gate below.
     */
    fun originOf(url: String): Origin? = WebDavHrefResolver.originOfOrNull(url)

    /**
     * True iff [url]'s `(scheme, host, effective-port)` triple equals that of
     * ANY normalized [entries] entry. An unparseable [url] or out-of-set/port-
     * mismatched target is false (fail closed).
     */
    fun matches(url: String, entries: Collection<String>): Boolean {
        val urlOrigin = originOf(url) ?: return false
        return entries.any { entry ->
            val allowed = normalizeEntry(entry) ?: return@any false
            WebDavHrefResolver.sameOrigin(allowed, urlOrigin)
        }
    }
}
