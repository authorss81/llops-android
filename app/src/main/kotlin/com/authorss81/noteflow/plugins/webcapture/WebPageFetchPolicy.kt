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

    private val ALLOWED_SCHEMES = setOf("http", "https")

    data class Validation(
        val url: String,
        val host: String,
        val scheme: String
    )

    /**
     * Normalizes and validates a user-typed URL for fetching. Enforces the
     * http(s) scheme allow-list AND [SsrfHostPolicy] (loopback/private/
     * link-local/metadata/.local destinations are refused here, before any
     * connection is made) — B1-NET-04.
     *
     * @return a [Validation] when the URL is safe to fetch, otherwise an error
     *   message.
     */
    fun validateUrl(input: String): Either =
        when {
            input.isBlank() -> Either.Error("Please enter a web address.")
            input.length > MAX_URI_LENGTH -> Either.Error("That address is too long.")
            else -> {
                val trimmed = input.trim()
                // A URL that already names a scheme must be http(s) only; a bare
                // host/domain gets the https:// prefix.
                val protocolMatch = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(trimmed)
                val candidate = when {
                    protocolMatch == null -> "https://$trimmed"
                    protocolMatch.groupValues[1].lowercase() in ALLOWED_SCHEMES -> trimmed
                    else -> return Either.Error("Only http:// and https:// addresses are supported.")
                }
                runCatching { URI(candidate) }
                    .map { uri ->
                        val scheme = uri.scheme?.lowercase()
                        val host = uri.host
                        when {
                            scheme == null || scheme !in ALLOWED_SCHEMES ->
                                Either.Error("Only http:// and https:// addresses are supported.")
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
     * scheme allow-list and the [SsrfHostPolicy] blocklist are applied to the
     * redirect target exactly as they are to the original URL, so a redirect
     * escape-hatch can never be wider than the entry validation.
     *
     * @return an error message when the hop must be refused, else null.
     */
    fun rejectHop(absoluteUrl: String): String? {
        val uri = runCatching { URI(absoluteUrl) }.getOrNull()
            ?: return "That address could not be understood."
        val scheme = uri.scheme?.lowercase()
        if (scheme == null || scheme !in ALLOWED_SCHEMES) {
            return "Redirected to a non-http(s) address — blocked."
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