package com.authorss81.noteflow.llm.policy

import java.net.URI
import java.security.MessageDigest

/**
 * PURE JVM — B2-DEPS-05 (phase-77): the download trust model for the assistant
 * LLM model. The model is the one non-code artifact in the trust chain, so it
 * gets the same pin treatment the plugin code artifact gets: a fixed host
 * family, no silent redirects, and a published SHA-256 + exact size that the
 * downloaded bytes MUST match. No Android dependencies, fully unit-tested.
 *
 * The default model URL is `https://huggingface.co/.../resolve/main/...gguf`.
 * HuggingFace's `resolve` endpoint answers 3xx to its own CDN family
 * (`*.hf.co`, `*.huggingface.co` — e.g. `us.aws.cdn.hf.co`,
 * `cdn-lfs.huggingface.co`, `cas-bridge.xethub.hf.co`), so:
 *  - the ENTRY host must be exactly [ENTRY_HOST] (huggingface.co);
 *  - every redirect HOP must stay inside that family (defense in depth — even
 *    a same-family CDN cannot serve different bytes without failing the
 *    SHA-256 pin, and an off-family redirect is refused outright);
 *  - a hop must be `https` (never an HTTPS→HTTP downgrade), carry no host-less
 *    URL and no embedded credentials;
 *  - the downloaded bytes are accepted only when the exact [byte count] AND
 *    the [SHA-256] match the published pin.
 */
object ModelDownloadPolicy {

    /** Maximum manual redirect hops a model download will follow. */
    const val MAX_REDIRECTS: Int = 5

    const val CONNECT_TIMEOUT_MS: Int = 20_000

    const val READ_TIMEOUT_MS: Int = 40_000

    /** The entry URL of the pinned model must live on exactly this host. */
    const val ENTRY_HOST: String = "huggingface.co"

    /** Thrown when a hop (the entry URL or a 3xx target) must not be connected to. */
    class HopRefusedException(message: String) : java.io.IOException(message)

    /** Outcome of validating a URL before connecting to it. */
    sealed class HopVerdict {
        data object Ok : HopVerdict()
        data class Refused(val reason: String) : HopVerdict()
    }

    /**
     * Host allow-list: the entry host itself plus the HuggingFace CDN family a
     * `resolve` URL may redirect to. `*.hf.co` / `*.huggingface.co` are
     * HF-controlled domains (an attacker cannot register a subdomain under
     * them), which structurally excludes localhost/LAN/cloud-metadata SSRF
     * targets; a user-controlled host can never match.
     */
    fun isAllowedDownloadHost(host: String?): Boolean {
        val h = host?.trim()?.lowercase() ?: return false
        return h == ENTRY_HOST || h.endsWith(".huggingface.co") || h.endsWith(".hf.co")
    }

    /** Validate the ENTRY URL of the download: https + exactly [ENTRY_HOST]
     *  (huggingface.co — a CDN host may be a redirect HOP but never the entry)
     *  + no credentials. */
    fun validateEntry(url: String): HopVerdict {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            return HopVerdict.Refused("Refusing to download from a malformed URL.")
        }
        if (uri.scheme?.lowercase() != "https") {
            return HopVerdict.Refused("Refusing to connect to a non-HTTPS URL (HTTPS is required).")
        }
        val host = uri.host
        if (host.isNullOrBlank()) {
            return HopVerdict.Refused("Refusing to connect to a URL without a host.")
        }
        if (uri.userInfo != null) {
            return HopVerdict.Refused("Refusing to connect to a URL with embedded credentials.")
        }
        if (!host.equals(ENTRY_HOST, ignoreCase = true)) {
            return HopVerdict.Refused(
                "Refusing to download from a host other than the pinned $ENTRY_HOST."
            )
        }
        return HopVerdict.Ok
    }

    /**
     * Validate a hop (entry or redirect target) before connecting. A non-https
     * scheme, a blank host, embedded `user:pass@` credentials or a host outside
     * the allow-list is refused.
     */
    fun validateHop(uri: URI, verb: String = "follow a redirect to"): HopVerdict {
        if (uri.scheme?.lowercase() != "https") {
            return HopVerdict.Refused("Refusing to $verb a non-HTTPS URL (HTTPS is required).")
        }
        val host = uri.host
        if (host.isNullOrBlank()) {
            return HopVerdict.Refused("Refusing to $verb a URL without a host.")
        }
        if (uri.userInfo != null) {
            return HopVerdict.Refused("Refusing to $verb a URL with embedded credentials.")
        }
        if (!isAllowedDownloadHost(host)) {
            return HopVerdict.Refused(
                "Refusing to $verb a host outside the HuggingFace download infrastructure."
            )
        }
        return HopVerdict.Ok
    }

    /**
     * Resolve a 3xx [location] against the current hop [cur] (RFC 3986) and
     * validate the result via [validateHop]. Returns null when [location] is
     * blank (redirect without a usable target). Throws [HopRefusedException] on
     * any violation — including a target that resolves back to [cur].
     */
    fun resolveNextHop(cur: URI, location: String?): URI? {
        if (location.isNullOrBlank()) return null
        val resolved = try {
            cur.resolve(location)
        } catch (e: Exception) {
            throw HopRefusedException("Refusing to follow a malformed redirect target.")
        }
        if (resolved.toString() == cur.toString()) {
            throw HopRefusedException("Refusing to follow a redirect loop.")
        }
        when (val verdict = validateHop(resolved)) {
            is HopVerdict.Refused -> throw HopRefusedException(verdict.reason)
            is HopVerdict.Ok -> {}
        }
        return resolved
    }

    /** Outcome of verifying downloaded bytes against the published pin. */
    sealed class DownloadVerdict {
        data object Match : DownloadVerdict()
        data class SizeMismatch(val expectedBytes: Long, val actualBytes: Long) : DownloadVerdict()
        data class HashMismatch(val expectedSha256: String, val actualSha256: String) : DownloadVerdict()
    }

    /**
     * Verify a completed download against the pin. Size is checked first (the
     * cheap, decisive gate), then the SHA-256. Both must match exactly —
     * B2-DEPS-05: `expectedSizeBytes` was previously never compared and no hash
     * existed at all.
     */
    fun verifyDownload(
        actualBytes: Long,
        actualSha256Hex: String,
        expectedBytes: Long = AssistantStoragePolicy.DEFAULT_MODEL_SIZE_BYTES,
        expectedSha256Hex: String = AssistantStoragePolicy.DEFAULT_MODEL_SHA256
    ): DownloadVerdict {
        if (actualBytes != expectedBytes) {
            return DownloadVerdict.SizeMismatch(expectedBytes, actualBytes)
        }
        if (!hexEqual(actualSha256Hex, expectedSha256Hex)) {
            return DownloadVerdict.HashMismatch(expectedSha256Hex, actualSha256Hex)
        }
        return DownloadVerdict.Match
    }

    /** True for a well-formed 64-lowercase-hex SHA-256 digest. */
    fun isValidSha256Hex(text: String): Boolean =
        text.length == 64 && text.all { it in HEX_CHARS }

    /** Case-insensitive, constant-time (full-length) compare of two hex digests. */
    fun hexEqual(a: String, b: String): Boolean {
        val normA = a.lowercase().toByteArray(Charsets.US_ASCII)
        val normB = b.lowercase().toByteArray(Charsets.US_ASCII)
        return MessageDigest.isEqual(normA, normB)
    }

    private const val HEX_CHARS = "0123456789abcdef"
}
