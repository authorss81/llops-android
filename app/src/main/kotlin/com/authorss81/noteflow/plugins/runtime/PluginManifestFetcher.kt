package com.authorss81.noteflow.plugins.runtime

import java.net.URL
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.authorss81.noteflow.utils.HttpUserAgent

/**
 * Outcome of fetching the hosted plugin version manifest.
 *
 * - [Loaded] — the manifest was fetched and parsed successfully.
 * - [Failed] — a user-facing failure (offline, non-2xx, size cap, non-HTTPS,
 *   malformed JSON). The store surfaces this directly; an update check NEVER
 *   silently degrades to "up to date".
 */
sealed class ManifestFetchResult {
    data class Loaded(val manifest: HostedPluginManifest) : ManifestFetchResult()
    data class Failed(val message: String) : ManifestFetchResult()
}

/**
 * The transport seam for [PluginManifestFetcher] — keeps the fetcher core PURE
 * JVM so the URL guard and parse wiring are unit-tested with a fake transport.
 * The production implementation ([HttpsManifestTransport]) performs a PINNED,
 * HTTPS-only, chain-validated fetch with a hard size cap.
 */
fun interface ManifestTransport {
    suspend fun fetch(url: String): ManifestFetchResult
}

/**
 * Fetches + parses the hosted plugin version manifest (Phase 24).
 *
 * Keyless and user-initiated: the store's "Check for updates" action is the
 * only trigger; there is no background/auto manifest polling and nothing is
 * installed from it without the per-update approval flow.
 *
 * Guards enforced here (in addition to the transport's TLS-only rule):
 * - **HTTPS only.** [DEFAULT_PLUGIN_MANIFEST_URL] and any overridden URL must
 *   be `https://` — a cleartext manifest is refused before the transport runs.
 * - **Parse strictness.** [PluginManifestParser] refuses a malformed/invalid
 *   manifest wholesale; a bad document surfaces as [ManifestFetchResult.Failed].
 *
 * @param transport where the manifest bytes come from (production:
 *   [HttpsManifestTransport]).
 * @param defaultUrl the manifest URL to fetch when [fetch] is called with none
 *   (defaults to [DEFAULT_PLUGIN_MANIFEST_URL]).
 */
class PluginManifestFetcher(
    private val transport: ManifestTransport,
    private val defaultUrl: String = DEFAULT_PLUGIN_MANIFEST_URL
) {

    /** Fetch + parse the manifest at [url] (defaults to [defaultUrl]). */
    suspend fun fetch(url: String = defaultUrl): ManifestFetchResult {
        if (!url.startsWith("https://")) {
            return ManifestFetchResult.Failed(
                "Refusing to fetch a plugin update manifest over non-TLS ('${url.take(12)}…'). HTTPS only."
            )
        }
        return when (val result = transport.fetch(url)) {
            is ManifestFetchResult.Failed -> result
            is ManifestFetchResult.Loaded -> result
        }
    }
}

/**
 * Production [ManifestTransport]: an HTTPS-only fetch of the version manifest
 * that is AUTHENTICATED by a COMPILE-TIME certificate pin.
 *
 * This transport is the fix for **B1-CRYPTO-01**: the update manifest carries
 * `downloadUrl` + `sha256` + `pinnedCertHash`, and every later verifier
 * ([HttpsPluginDownloadTransport], [ArtifactSignatureVerifier],
 * [SignatureVerifiedPluginRuntime]) trusts those values. A chain-validation-only
 * manifest fetch therefore let an attacker DEFINE the trust anchor. Here the
 * manifest itself is bound to the compile-time [PLUGIN_MANIFEST_CERT_PIN] plus
 * the compile-time [DEFAULT_MANIFEST_HOST] allow-list, so an update offer can
 * never come from an unauthenticated source and the artifact pins it carries
 * are as trustworthy as the pinned transport itself.
 *
 * Enforced regardless of caller:
 * - **Pinned TLS.** The connection reuses the [PinnedTlsConnector] machinery of
 *   the artifact transport: chain validation against the system trust store AND
 *   a leaf-certificate pin against [PLUGIN_MANIFEST_CERT_PIN] for [expectedHost].
 *   A mismatching certificate is refused before any manifest byte is trusted.
 * - **Host allow-list.** The fetch only ever talks to [expectedHost] (compile
 *   time [DEFAULT_MANIFEST_HOST]); a URL for any other host is refused before a
 *   connection opens.
 * - **Fail closed on a missing/malformed pin.** If [PLUGIN_MANIFEST_CERT_PIN]
 *   is not a well-formed 32-byte pin, the check is disabled with a clear
 *   user-facing message — it never degrades to chain-validation-only HTTPS.
 * - **No redirects.** `instanceFollowRedirects` is off; a 3xx (including an
 *   HTTPS→HTTP downgrade) is surfaced as a failed check — never followed.
 * - **HTTPS only**, a hard size-cap ([MAX_BYTES]), 2xx responses only.
 *
 * Never logs manifest contents.
 *
 * @param expectedCertPin the compile-time `sha256/<base64>` leaf pin for the
 *   manifest host (default [PLUGIN_MANIFEST_CERT_PIN]).
 * @param expectedHost the only host this transport talks to (default
 *   [DEFAULT_MANIFEST_HOST]).
 * @param trustManagerOverride for deterministic unit tests only (pin over a
 *   test trust anchor); production leaves it null (system trust store).
 */
class HttpsManifestTransport(
    private val expectedCertPin: String = PLUGIN_MANIFEST_CERT_PIN,
    private val expectedHost: String = DEFAULT_MANIFEST_HOST,
    private val trustManagerOverride: X509TrustManager? = null
) : ManifestTransport {

    override suspend fun fetch(url: String): ManifestFetchResult =
        withContext(Dispatchers.IO) {
            var connection: HttpsURLConnection? = null
            try {
                val parsed = URL(url)
                if (parsed.protocol != "https") {
                    return@withContext ManifestFetchResult.Failed(
                        "Refusing a non-TLS update-manifest fetch (got '${parsed.protocol}://'). HTTPS only."
                    )
                }
                if (!parsed.host.equals(expectedHost, ignoreCase = true)) {
                    return@withContext ManifestFetchResult.Failed(
                        "Refusing an update-manifest fetch to '${parsed.host}' — only '$expectedHost' is a trusted manifest host."
                    )
                }
                if (PinnedCertHash.parse(expectedCertPin) == null) {
                    return@withContext ManifestFetchResult.Failed(
                        "This build does not carry a valid pinned certificate for '$expectedHost', so plugin update checks are disabled."
                    )
                }
                val connection = PinnedTlsConnector.open(
                    parsed,
                    expectedCertPin,
                    trustManagerOverride ?: PinnedTlsConnector.systemTrustManager()
                )
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", HttpUserAgent.GENERIC)

                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    return@withContext ManifestFetchResult.Failed(
                        "The update manifest endpoint answered with an HTTP redirect ($responseCode), which is never followed."
                    )
                }
                if (responseCode !in 200..299) {
                    return@withContext ManifestFetchResult.Failed(
                        "Could not check for plugin updates (HTTP $responseCode)."
                    )
                }
                val contentLength = connection.contentLengthLong
                if (contentLength > MAX_BYTES) {
                    return@withContext ManifestFetchResult.Failed(
                        "Update manifest is unexpectedly large and was refused."
                    )
                }
                val body = connection.inputStream.use { input ->
                    val bytes = input.readNBytes(MAX_BYTES + 1)
                    if (bytes.size > MAX_BYTES) {
                        return@withContext ManifestFetchResult.Failed(
                            "Update manifest exceeds the ${MAX_BYTES / 1024} KB cap and was refused."
                        )
                    }
                    bytes
                }
                if (body.isEmpty()) {
                    return@withContext ManifestFetchResult.Failed(
                        "The update manifest was empty."
                    )
                }
                when (val parsed = PluginManifestParser().parse(String(body, Charsets.UTF_8))) {
                    is ManifestParseResult.Valid -> ManifestFetchResult.Loaded(parsed.manifest)
                    is ManifestParseResult.Invalid ->
                        ManifestFetchResult.Failed(
                            "The update manifest is invalid: ${parsed.errors.joinToString("; ")}"
                        )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: SSLHandshakeException) {
                // The pin gate throws CertificateException inside the handshake,
                // which surfaces wrapped as SSLHandshakeException by the JRE.
                if (isPinnedCertFailure(e)) {
                    ManifestFetchResult.Failed(
                        "Could not check for plugin updates: the manifest host's certificate does not match the pinned hash."
                    )
                } else {
                    ManifestFetchResult.Failed(
                        "Could not check for plugin updates (${e::class.java.simpleName}). Check your connection and try again."
                    )
                }
            } catch (e: java.security.cert.CertificateException) {
                ManifestFetchResult.Failed(
                    "Could not check for plugin updates: the manifest host's certificate does not match the pinned hash."
                )
            } catch (e: Throwable) {
                ManifestFetchResult.Failed(
                    "Could not check for plugin updates (${e::class.java.simpleName}). Check your connection and try again."
                )
            } finally {
                connection?.disconnect()
            }
        }

    /** True when [throwable]'s cause chain contains a [CertificateException] —
     *  i.e. the TLS handshake was refused by the pinned-certificate gate. */
    private fun isPinnedCertFailure(throwable: Throwable): Boolean {
        var cause: Throwable? = throwable
        while (cause != null) {
            if (cause is java.security.cert.CertificateException) return true
            cause = cause.cause
        }
        return false
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 40_000
        const val MAX_BYTES: Int = 256 * 1024
    }
}
