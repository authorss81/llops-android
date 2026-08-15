package com.authorss81.noteflow.plugins.citation

import com.authorss81.noteflow.services.SsrfHostPolicy
import java.net.URI

/**
 * PURE JVM citation core for the Citation Formatter plugin (Phase 26).
 *
 * Builds a clean Markdown `[title](url)` link from a pasted URL and (optionally)
 * a title, plus a plain-text `<title>` extractor for HTML payloads. The whole
 * payload-building path is unit-tested with sample inputs — no network involved.
 */
object CitationFormatterCore {

    /** Outcome of URL validation. */
    sealed class UrlCheck {
        data class Valid(val url: String) : UrlCheck()
        data class Invalid(val reason: String) : UrlCheck()
    }

    /**
     * Normalise + validate a user-pasted URL. Only `http(s)` schemes are
     * accepted; a bare hostname (`example.com`) is upgraded to https. The host
     * is additionally checked against [SsrfHostPolicy], so loopback / private /
     * link-local / `.local` destinations are refused before any title fetch
     * (B1-NET-04) — same policy the title fetcher re-applies on every hop.
     */
    fun validateUrl(input: String): UrlCheck {
        var candidate = input.trim()
        if (candidate.isEmpty()) return UrlCheck.Invalid("Paste a URL first.")
        if (!candidate.contains("://")) {
            candidate = "https://$candidate"
        }
        val scheme = candidate.substringBefore("://").lowercase()
        if (scheme != "https" && scheme != "http") {
            return UrlCheck.Invalid("Only http(s) URLs can be cited (got \"$scheme://…\").")
        }
        val uri = runCatching { URI(candidate) }.getOrNull()
            ?: return UrlCheck.Invalid("That doesn't look like a valid URL.")
        val host = uri.host
        if (host.isNullOrBlank() || !host.contains('.')) {
            return UrlCheck.Invalid("That doesn't look like a valid URL.")
        }
        val blocked = SsrfHostPolicy.blockedReason(host)
        if (blocked != null) {
            return UrlCheck.Invalid(blocked)
        }
        return UrlCheck.Valid(candidate)
    }

    /**
     * Build the `[title](url)` link.
     *
     * The title is rendered as Markdown link text, so any `[ ] ( )` or `\`
     * characters are escaped backslash-first so a fetched/typed title can never
     * break or inject into the generated link. A destination that would confuse
     * the `(url)` grammar (spaces or unbalanced parentheses) is wrapped in
     * angle brackets — the CommonMark form `[title](<url>)`.
     *
     * @param title title to use; when blank, falls back to [hostLabel].
     */
    fun buildCitation(url: String, title: String?): String {
        val label = escapeLinkLabel(
            title?.trim()?.takeIf { it.isNotEmpty() }
                ?.replace(Regex("\\s+"), " ")?.take(200)?.trim()
                ?: hostLabel(url)
        )
        return "[$label](${citationDestination(url)})"
    }

    /**
     * Escape Markdown link-text syntax so a fetched/typed title renders as
     * intended and cannot break out of the `[...]` span.
     */
    fun escapeLinkLabel(text: String): String = text
        .replace("\\", "\\\\")
        .replace("[", "\\[")
        .replace("]", "\\]")
        .replace("(", "\\(")
        .replace(")", "\\)")

    /** A safe `(url)` destination: angle-bracket-wrapped when it has whitespace
     *  or parentheses that would otherwise terminate the link span early. */
    fun citationDestination(url: String): String =
        if (url.any { it.isWhitespace() || it == '(' || it == ')' }) "<$url>" else url

    /** A readable host-based label, e.g. `example.com` from the URL. */
    fun hostLabel(url: String): String {
        val withoutScheme = url.substringAfter("://")
        val host = withoutScheme.substringBefore("/").substringBefore("?").substringBefore("#")
        val hostOnly = host.substringBefore(":").takeIf { it.isNotEmpty() } ?: host
        return hostOnly.lowercase().removePrefix("www.")
    }

    /**
     * Extract the plain-text `<title>` from a raw HTML payload. Pure string
     * processing (no jsoup needed — keeps the plugin dependency-free).
     *
     * @return the decoded title, or null when no `<title>` is present.
     */
    fun extractHtmlTitle(html: String): String? {
        if (html.isBlank()) return null
        // Match <title ...>text</title>, case-insensitively, tag allowed attrs.
        val match = Regex("""(?is)<title\b[^>]*>(.*?)</title>""").find(html) ?: return null
        val raw = match.groupValues[1]
        return decodeEntities(stripTags(raw)).trim().takeIf { it.isNotEmpty() }
    }

    private fun stripTags(text: String): String = Regex("""(?is)<[^>]+>""").replace(text, " ")

    /** Decode the few HTML entities that commonly appear inside `<title>`. */
    private fun decodeEntities(text: String): String = text
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .replace(Regex("""&#\d+;""")) { match ->
            val code = match.value.removePrefix("&#").removeSuffix(";").toIntOrNull()
            code?.let { it.toChar().toString() } ?: match.value
        }
}