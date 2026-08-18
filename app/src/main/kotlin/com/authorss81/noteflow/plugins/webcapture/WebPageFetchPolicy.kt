package com.authorss81.noteflow.plugins.webcapture

import com.authorss81.noteflow.services.SsrfHostPolicy
import java.net.URI

/**
 * Pure-JVM, testable network-fetch behaviour for Web Capture.
 *
 * Splits the no-CI-callable URL fetching into these pure decisions so the
 * plugin's wiring (scheme allow-list, SSRF-ish host guards, size cap) is fully
 * unit-testable without touching the network.
 */
object WebPageFetchPolicy {

    const val MAX_RESPONSE_BYTES = 5 * 1024 * 1024
    private const val MAX_URI_LENGTH = 2 * 1024

    private const val HTTPS_SCHEME = "https"
    private const val HTTP_SCHEME = "http"

    // R2-B1N-04 (phase-143): HTTPS-by-default. Clear-text http is NOT in the
    // allow-list; it is tolerated only with an explicit per-fetch opt-in
    // ([allowInsecureHttp]). Modeled on WebDAV's `allowInsecureHttp` UX, with
    // one intentional difference: WebDAV confines cleartext to local-network
    // hosts, whereas Web Capture's opt-in applies to any host (the [SsrfHostPolicy]
    // blocklist still applies either way).
    private val ALLOWED_SCHEMES = setOf(HTTPS_SCHEME)

    /** User-facing explanation for an http:// URL that was not opted into. */
    const val INSECURE_HTTP_REFUSED_MESSAGE =
        "Insecure HTTP is disabled for Web Capture — use an https:// address, " +
            "or tick \"Allow insecure HTTP\" to fetch this page once over cleartext."

    /** R2-B1N-04: http is allowed only with the explicit per-fetch opt-in. */
    private fun schemeAllowed(scheme: String, allowInsecureHttp: Boolean): Boolean =
        scheme in ALLOWED_SCHEMES || (scheme == HTTP_SCHEME && allowInsecureHttp)

    /** Matches a URL that already names a scheme, exactly as [validateUrl] uses it. */
    private val SCHEME_PREFIX = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):")

    /**
     * R2-B1N-04 (phase-143): true exactly when [validateUrl] would treat
     * [input] as naming the `http` scheme (and therefore need the per-fetch
     * cleartext opt-in). The Web Capture dialog uses this to decide whether to
     * show its "allow insecure HTTP" checkbox, so the checkbox can never
     * disagree with [validateUrl]. Pure-JVM.
     */
    fun namesHttpScheme(input: String): Boolean {
        val trimmed = input.trim()
        return SCHEME_PREFIX.find(trimmed)?.groupValues?.get(1)?.lowercase() == HTTP_SCHEME
    }

    data class Validation(
        val url: String,
        val host: String,
        val scheme: String
    )

    /**
     * Normalizes and validates a user-typed URL for fetching. Enforces the
     * https-only scheme allow-list AND [SsrfHostPolicy] (loopback/private/
     * link-local/metadata/.local destinations are refused here, before any
     * connection is made) — B1-NET-04. Clear-text http is refused unless
     * [allowInsecureHttp] opts in for this one fetch — R2-B1N-04.
     *
     * @param allowInsecureHttp explicit per-fetch cleartext opt-in, the same
     *   model as WebDAV's `allowInsecureHttp` (WebDavSyncService.kt). Defaults
     *   to false; a bare/host-only input always defaults to https.
     * @return a [Validation] when the URL is safe to fetch, otherwise an error
     *   message.
     */
    fun validateUrl(input: String, allowInsecureHttp: Boolean = false): Either =
        when {
            input.isBlank() -> Either.Error("Please enter a web address.")
            input.length > MAX_URI_LENGTH -> Either.Error("That address is too long.")
            else -> {
                val trimmed = input.trim()
                // A URL that already names a scheme must be https (or http with
                // the explicit opt-in); a bare host/domain gets the https:// prefix.
                val protocolMatch = SCHEME_PREFIX.find(trimmed)
                val candidate = when {
                    protocolMatch == null -> "https://$trimmed"
                    protocolMatch.groupValues[1].lowercase() in ALLOWED_SCHEMES -> trimmed
                    protocolMatch.groupValues[1].lowercase() == HTTP_SCHEME && allowInsecureHttp -> trimmed
                    else -> {
                        val scheme = protocolMatch.groupValues[1].lowercase()
                        return if (scheme == HTTP_SCHEME) {
                            Either.Error(INSECURE_HTTP_REFUSED_MESSAGE)
                        } else {
                            Either.Error("Only https:// addresses are supported.")
                        }
                    }
                }
                runCatching { URI(candidate) }
                    .map { uri ->
                        val scheme = uri.scheme?.lowercase()
                        val host = uri.host
                        when {
                            scheme == null || !schemeAllowed(scheme, allowInsecureHttp) -> {
                                if (scheme == HTTP_SCHEME) {
                                    Either.Error(INSECURE_HTTP_REFUSED_MESSAGE)
                                } else {
                                    Either.Error("Only https:// addresses are supported.")
                                }
                            }
                            host.isNullOrBlank() ->
                                Either.Error("That address does not include a host.")
                            else -> {
                                val blocked = SsrfHostPolicy.blockedReason(host)
                                if (blocked != null) {
                                    Either.Error(blocked)
                                } else {
                                    Either.Valid(Validation(uri.toString(), host, scheme))
                                }
                            }
                        }
                    }.getOrElse { Either.Error("That address could not be understood.") }
            }
        }

    /**
     * Re-validates a single hop in a redirect chain — B1-NET-04. [absoluteUrl]
     * must already be resolved against the current hop (the caller resolves
     * relative 3xx `Location` values with [URI.resolve] before calling). The
     * https scheme allow-list (http allowed only with the explicit opt-in,
     * R2-B1N-04) and the [SsrfHostPolicy] blocklist are applied to the
     * redirect target exactly as they are to the original URL, so a redirect
     * escape-hatch can never be wider than the entry validation.
     *
     * @param allowInsecureHttp per-fetch cleartext opt-in, must match the
     *   original entry validation so a redirect can never widen the policy.
     * @return an error message when the hop must be refused, else null.
     */
    fun rejectHop(absoluteUrl: String, allowInsecureHttp: Boolean = false): String? {
        val uri = runCatching { URI(absoluteUrl) }.getOrNull()
            ?: return "That address could not be understood."
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || !schemeAllowed(scheme, allowInsecureHttp)) {
            return if (scheme == HTTP_SCHEME) {
                INSECURE_HTTP_REFUSED_MESSAGE
            } else {
                "Redirected to a non-https address — blocked."
            }
        }
        val host = uri.host
        if (host.isNullOrBlank()) {
            return "That address does not include a host."
        }
        return SsrfHostPolicy.blockedReason(host)?.let { "Redirect blocked: $it" }
    }

    sealed class Either {
        data class Valid(val validation: Validation) : Either()
        data class Error(val message: String) : Either()
    }
}
