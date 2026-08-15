package com.authorss81.noteflow.services

import java.net.URI

/**
 * B1-NET-04 (phase-51): the shared, pure-JVM host blocklist for every
 * user-influenced outbound fetch (Web Capture, Citation title-fetch).
 *
 * Returns a typed reason whenever a host is an internal/reserved destination:
 * loopback (`127.0.0.0/8`, `0.0.0.0/8`, `::1`, `::`), link-local / cloud
 * metadata (`169.254.0.0/16` incl. `169.254.169.254`, `fe80::/10`),
 * private RFC-1918 ranges (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`),
 * the RFC-6598 CGNAT block (`100.64.0.0/10`), IPv6 unique-local (`fc00::/7`
 * incl. `fd00:ec2::254` — the AWS IMDSv2 link-local v6), IPv4-mapped /
 * IPv4-compatible embedded-IPv4 forms, and the reserved mDNS/local hostnames
 * (`localhost`, `*.local`, `*.localhost`).
 *
 * All checks are textual/structural — **no DNS resolution** happens here, so
 * unit tests run without a network and a hostile DNS answer cannot silently
 * pass. Resolving a returned hostname and pinning the CONNECT to the resolved
 * address is the transport's job and is intentionally out of scope for this
 * literal blocklist (it would add a resolution round-trip to pure JVM tests).
 */
object SsrfHostPolicy {

    /** @return a human-readable reason when [rawHost] is an internal/reserved
     *  destination, or null when it is safe to connect to. Host must be the
     *  authority host as produced by [URI.getHost] (no port, IPv6 optionally
     *  bracketed); ports are handled by the caller, never by this object. */
    fun blockedReason(rawHost: String): String? {
        val host = normalize(rawHost)
        if (host.isEmpty()) return null

        when {
            host == "localhost" ||
                host.endsWith(".localhost") ||
                host.endsWith(".local") ->
                return "Internal/reserved hostnames (localhost, *.local) cannot be fetched."
        }

        parseIpv4(host)?.let { return ipv4Reason(it) }
        parseIpv6(host)?.let { return ipv6Reason(it.first, it.second) }
        return null
    }

    // ---- hostname normalisation -------------------------------------------

    private fun normalize(raw: String): String {
        var host = raw.trim().lowercase()
        if (host.endsWith(".")) host = host.dropLast(1) // FQDN trailing dot
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length - 1)
        }
        return host
    }

    // ---- IPv4 -------------------------------------------------------------

    private const val MESSAGE =
        "Private or internal IP addresses cannot be fetched."

    /**
     * Parses an IPv4 literal in the textual forms Java's [java.net.InetAddress]
     * constructs from a host string — dotted-quad and the short 1-3 segment
     * forms where the final segment carries the remaining bits (`127.1`,
     * `2130706433`, `127.0.1`), plus `0x…` hex — so every encoding a user pastes
     * lands on the same 32-bit value before the ranges are checked. Non-numeric
     * input returns null (treated as a hostname by the caller).
     */
    private fun parseIpv4(host: String): Long? {
        if (host.isEmpty()) return null
        if (host.startsWith("0x") || host.startsWith("0X")) {
            val hex = host.drop(2)
            if (hex.isEmpty() || hex.length > 8 || !hex.all { it in '0'..'9' || it in 'a'..'f' }) {
                return null
            }
            return hex.toLongOrNull(16)?.and(0xFFFFFFFFL)
        }
        val segments = host.split('.')
        if (segments.isEmpty() || segments.size > 4) return null
        for (segment in segments) {
            if (segment.isEmpty() || segment.length > 10) return null
            if (segment.any { it !in '0'..'9' }) return null
        }
        // Classic short form: the LAST segment is the low-order 32/24/16/8 bits.
        val lastWidth = when (segments.size) {
            1 -> 32
            2 -> 24
            3 -> 16
            else -> 8
        }
        var value = 0L
        for (i in 0 until segments.size - 1) {
            val part = segments[i].toLongOrNull() ?: return null
            if (part > 255L) return null
            value = (value shl 8) or part
        }
        val last = segments.last().toLongOrNull() ?: return null
        if (last >= (1L shl lastWidth)) return null
        value = (value shl lastWidth) or last
        return value
    }

    /** @return [MESSAGE] when the 32-bit address falls in an internal range. */
    private fun ipv4Reason(value: Long): String? {
        val blocked = when {
            // 0.0.0.0/8 — "this network"
            value >= 0x00000000L && value <= 0x00FFFFFFL -> true
            // 10.0.0.0/8 — RFC 1918
            value >= 0x0A000000L && value <= 0x0AFFFFFFL -> true
            // 100.64.0.0/10 — RFC 6598 carrier-grade NAT
            value >= 0x64400000L && value <= 0x647FFFFFL -> true
            // 127.0.0.0/8 — loopback
            value >= 0x7F000000L && value <= 0x7FFFFFFFL -> true
            // 169.254.0.0/16 — link-local / cloud metadata (169.254.169.254)
            value >= 0xA9FE0000L && value <= 0xA9FEFFFFL -> true
            // 172.16.0.0/12 — RFC 1918
            value >= 0xAC100000L && value <= 0xAC1FFFFFL -> true
            // 192.168.0.0/16 — RFC 1918
            value >= 0xC0A80000L && value <= 0xC0A8FFFFL -> true
            else -> false
        }
        return if (blocked) MESSAGE else null
    }

    // ---- IPv6 -------------------------------------------------------------

    /**
     * Parses an IPv6 literal (brackets and zone id already stripped) into
     * `(high64, low64)`. Handles compressed `::`, embedded dotted-quad IPv4 in
     * the final position, IPv4-mapped (`::ffff:a.b.c.d`) and IPv4-compatible
     * (`::a.b.c.d`) forms.
     */
    private fun parseIpv6(host: String): Pair<Long, Long>? {
        var s = host
        val zone = s.indexOf('%')
        if (zone >= 0) s = s.substring(0, zone)
        if (s.isEmpty()) return null

        val hextets = IntArray(8)
        val doubled = s.indexOf("::")
        if (doubled >= 0) {
            val leftText = s.substring(0, doubled)
            val rightText = s.substring(doubled + 2)
            val left = ipv6Hextets(leftText) ?: return null
            val right = ipv6Hextets(rightText) ?: return null
            val fill = 8 - left.size - right.size
            if (fill < 1) return null
            left.forEachIndexed { i, v -> hextets[i] = v }
            val base = left.size + fill
            right.forEachIndexed { i, v -> hextets[base + i] = v }
        } else {
            val segments = ipv6Hextets(s) ?: return null
            if (segments.size != 8) return null
            segments.forEachIndexed { i, v -> hextets[i] = v }
        }

        var high = 0L
        var low = 0L
        for (i in 0 until 4) high = (high shl 16) or hextets[i].toLong().and(0xFFFF)
        for (i in 4 until 8) low = (low shl 16) or hextets[i].toLong().and(0xFFFF)
        return Pair(high, low)
    }

    /** Converts one side of a `::` split (or the full address) to hextets. */
    private fun ipv6Hextets(text: String): List<Int>? {
        if (text.isEmpty()) return emptyList()
        val parts = text.split(':')
        if (parts.isEmpty()) return null
        val out = ArrayList<Int>(parts.size)
        for (i in parts.indices) {
            val part = parts[i]
            if (part.isEmpty()) {
                // An empty segment outside "::" is malformed here.
                if (i == 0 && parts.size == 1) return null
                return null
            }
            if (i == parts.lastIndex && part.contains('.')) {
                // embedded IPv4 in the final position (IPv4-mapped/compatible)
                val ipv4 = parseIpv4(part) ?: return null
                out.add(((ipv4 ushr 16) and 0xFFFF).toInt())
                out.add((ipv4 and 0xFFFF).toInt())
            } else {
                if (part.length > 4) return null
                out.add(part.toIntOrNull(16) ?: return null)
            }
        }
        if (out.size > 8) return null
        return out
    }

    /** @return [MESSAGE] when the IPv6 value is internal/reserved. */
    private fun ipv6Reason(high: Long, low: Long): String? {
        if (high == 0L && low == 0L) return MESSAGE      // :: unspecified
        if (high == 0L && low == 1L) return MESSAGE      // ::1 loopback

        // IPv4-mapped (::ffff:0:0/96) and IPv4-compatible (::/96) embed a v4
        // address — if that v4 is private, treat the whole literal as internal
        // (an attacker can write ::ffff:192.168.0.1 and reach the LAN). The
        // pure `::` and `::1` cases already returned above.
        if (high == 0L && (low ushr 32) == 0xFFFFL) {
            return ipv4Reason(low and 0xFFFFFFFFL) ?: return null
        }
        if (high == 0L && (low ushr 32) == 0L) {
            return ipv4Reason(low and 0xFFFFFFFFL) ?: return null
        }

        // fe80::/10 — link-local
        if ((high ushr 54) == 0x3FAL) return MESSAGE
        // fc00::/7 — unique-local addressing (incl. fd00:ec2::254 IMDSv2)
        if ((high ushr 57) == 0x7EL) return MESSAGE
        return null
    }
}