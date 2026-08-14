package com.authorss81.noteflow.plugins.runtime

import java.net.URL
import javax.net.ssl.HttpsURLConnection
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
 * The production implementation ([HttpsManifestTransport]) performs an
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
 * Production [ManifestTransport] (Phase 24): an HTTPS-only fetch of the version
 * manifest with standard system-chain TLS validation.
 *
 * Unlike the per-plugin artifact transport ([HttpsPluginDownloadTransport]),
 * the manifest is NOT pinned to a single certificate — it is a small, keyless,
 * user-initiated document whose only purpose is to point at the pinned+hashed
 * artifacts; the artifacts themselves are individually verified before any
 * code runs. The manifest's impact is bounded by a hard size cap and by the
 * fact that NOTHING in it is trusted at face value.
 *
 * Enforced regardless of caller: `https` scheme only (a cleartext URL is
 * refused before a connection opens), 2xx responses only, and a [MAX_BYTES]
 * cap on the response body. Never logs manifest contents.
 */
class HttpsManifestTransport : ManifestTransport {

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
                val connection = parsed.openConnection() as HttpsURLConnection
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", HttpUserAgent.GENERIC)
                connection.useCaches = false

                val responseCode = connection.responseCode
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
            } catch (e: Throwable) {
                ManifestFetchResult.Failed(
                    "Could not check for plugin updates (${e::class.java.simpleName}). Check your connection and try again."
                )
            } finally {
                connection?.disconnect()
            }
        }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 20_000
        const val READ_TIMEOUT_MS = 40_000
        const val MAX_BYTES: Int = 256 * 1024
    }
}
