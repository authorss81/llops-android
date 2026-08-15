package com.authorss81.noteflow.services

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import com.authorss81.noteflow.utils.BackupFileNamePolicy
import com.authorss81.noteflow.utils.HttpUserAgent

/**
 * WebDAV sync engine: uploads/downloads ENCRYPTED VAULT BACKUP FILES to/from
 * the user's own WebDAV or Nextcloud server.
 *
 * Honest scope (NOT zero-knowledge): the files on the server are the app's
 * encrypted backup archives (AES-256-GCM + SQLCipher). They are transported
 * over HTTPS and encrypted at rest, but the server operator can read the
 * backup files themselves — they cannot read the note plaintext.
 *
 * Security:
 * - HTTPS is REQUIRED. `http://` is rejected unless the user explicitly opts
 *   in for a local-network-only server (loopback/private IP/mDNS host).
 * - Connection timeouts are enforced; no cleartext fallback is ever attempted.
 * - Only the Android INTERNET permission is required (this is its sole use).
 */
class WebDavSyncService(private val context: Context) {

    data class SyncConfig(
        val serverUrl: String,
        val username: String,
        val passwordOrToken: String,
        val remoteFolderName: String = "Noteflow_Vault",
        /**
         * Explicit user confirmation to allow an `http://` URL for a
         * local-network-only server. NEVER auto-enabled.
         */
        val allowInsecureHttp: Boolean = false
    )

    data class SyncResult(
        val success: Boolean,
        val message: String,
        val bytesTransferred: Long = 0L,
        val filesSyncedCount: Int = 0
    )

    companion object {
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /**
         * Parses and validates a WebDAV base URL.
         *
         * Rules:
         * - non-blank and well-formed (throws IllegalArgumentException otherwise)
         * - host is mandatory
         * - `https` is required; `http` is allowed ONLY when [allowInsecureHttp]
         *   is true AND the host is loopback/private/local (local-network only)
         * - returns the URL with any trailing `/` removed
         */
        fun validateServerUrl(rawUrl: String, allowInsecureHttp: Boolean = false): String {
            val trimmed = rawUrl.trim()
            if (trimmed.isEmpty()) {
                throw IllegalArgumentException("Server URL cannot be empty.")
            }
            val url = try {
                URL(if (trimmed.endsWith("/")) trimmed.dropLast(1) else trimmed)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid WebDAV server URL: ${e.message}")
            }
            if ((url.host ?: "").isBlank()) {
                throw IllegalArgumentException(
                    "Server URL must include a host, e.g. https://cloud.example.com/remote.php/dav"
                )
            }
            if (url.protocol != "https") {
                if (url.protocol == "http" && allowInsecureHttp && isLocalNetworkHost(url.host ?: "")) {
                    // Explicit user confirmation for a local-network-only server.
                } else {
                    throw IllegalArgumentException(
                        "WebDAV sync requires HTTPS. Your encrypted backups must not travel " +
                            "over cleartext HTTP. Use an https:// URL, or explicitly allow HTTP " +
                            "for a local-network-only server."
                    )
                }
            }
            return if (trimmed.endsWith("/")) trimmed.dropLast(1) else trimmed
        }

        /**
         * A host is "local-network-only" when it is loopback, a private/guard
         * RFC1918 or link-local address, or an mDNS `.local` name. Used to scope
         * the explicit HTTP opt-in so a public IP can never be reached in
         * cleartext.
         */
        fun isLocalNetworkHost(host: String): Boolean {
            val h = host.lowercase().trim().trimEnd('.')
            if (h == "localhost" || h == "127.0.0.1" || h == "::1") return true
            if (h.endsWith(".local")) return true
            val ipv4 = h.substringBefore(':')
            return when {
                ipv4.startsWith("10.") -> true
                ipv4.startsWith("192.168.") -> true
                ipv4.startsWith("169.254.") -> true
                ipv4.startsWith("172.") -> {
                    val second = ipv4.substringAfter('.', "").substringBefore('.')
                    val n = second.toIntOrNull() ?: return false
                    n in 16..31
                }
                else -> false
            }
        }

        /**
         * Scheme guard applied to EVERY connection URL (including download URLs
         * built from a server-supplied href): refuse anything but HTTPS, except
         * an explicitly-confirmed local-network-only HTTP server.
         */
        fun requireSecureUrl(url: URL, allowInsecureHttp: Boolean) {
            val protocol = url.protocol
            if (protocol == "https") return
            if (protocol == "http" && allowInsecureHttp && isLocalNetworkHost(url.host ?: "")) return
            throw IllegalStateException(
                "Refusing to connect over a non-HTTPS channel. WebDAV sync requires HTTPS " +
                    "(insecure HTTP is allowed only for an explicitly-confirmed local-network server)."
            )
        }

        /**
         * Convenience for the base-server URL only: strips any trailing `/` and
         * validates it, then appends the trailing `/` expected by WebDAV folder
         * operations.
         */
        fun normalizeBaseUrl(rawUrl: String, allowInsecureHttp: Boolean): String =
            validateServerUrl(rawUrl, allowInsecureHttp) + "/"
    }

    private fun createConnection(urlString: String, config: SyncConfig, method: String): HttpURLConnection {
        val url = URL(urlString)
        requireSecureUrl(url, config.allowInsecureHttp)
        // B1-NET-01 (phase-40): the Basic Authorization header may be attached to
        // — and a connection opened to — ONLY the user's configured server origin
        // (scheme+host+port). This closes the server-supplied-href SSRF where a
        // PROPFIND body steered the download to an attacker/private-IP host.
        WebDavHrefResolver.requireConfiguredServerOrigin(urlString, config.serverUrl)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.useCaches = false

        // B1-NET-01/B1-NET-05 (phase-40): never follow server-driven redirects.
        // 3xx responses surface as their HTTP status and fail the sync, so the
        // credentials and the backup bytes can never be forwarded to a hop that
        // the server re-hosts elsewhere.
        conn.instanceFollowRedirects = false

        // On Android the default HttpsURLConnection negotiates TLS 1.2+ and
        // validates the certificate chain against the system trust store; the
        // protocol gate above guarantees we never downgrade to cleartext.

        val auth = "${config.username}:${config.passwordOrToken}"
        val encodedAuth = Base64.encodeToString(auth.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        conn.setRequestProperty("Authorization", "Basic $encodedAuth")
        conn.setRequestProperty("User-Agent", HttpUserAgent.GENERIC)
        return conn
    }

    /**
     * Tests connection to WebDAV / Nextcloud server and creates remote folder if missing.
     */
    suspend fun testAndPrepareConnection(config: SyncConfig): SyncResult = withContext(Dispatchers.IO) {
        try {
            val serverUrlClean = normalizeBaseUrl(config.serverUrl, config.allowInsecureHttp)
            val conn = createConnection(serverUrlClean, config, "PROPFIND")
            conn.setRequestProperty("Depth", "0")
            val responseCode = conn.responseCode

            if (responseCode in 200..299 || responseCode == 207) {
                // Ensure target folder exists
                val targetUrl = "$serverUrlClean${config.remoteFolderName}/"
                val folderConn = createConnection(targetUrl, config, "PROPFIND")
                folderConn.setRequestProperty("Depth", "0")
                if (folderConn.responseCode == 404) {
                    val mkcolConn = createConnection(targetUrl, config, "MKCOL")
                    mkcolConn.responseCode
                    mkcolConn.disconnect()
                }
                folderConn.disconnect()
                conn.disconnect()
                SyncResult(true, "Successfully connected to WebDAV server!")
            } else if (responseCode == 401) {
                SyncResult(false, "Authentication failed (401 Unauthorized). Please check username or app password.")
            } else {
                SyncResult(false, "Server returned HTTP response $responseCode")
            }
        } catch (e: IllegalArgumentException) {
            SyncResult(false, e.message ?: "Invalid WebDAV server URL.")
        } catch (e: Exception) {
            SyncResult(false, "Connection failed: ${e.localizedMessage ?: e.message}")
        }
    }

    /**
     * Uploads an encrypted vault backup archive to the WebDAV server.
     */
    suspend fun uploadEncryptedVault(config: SyncConfig, backupZipFile: File): SyncResult = withContext(Dispatchers.IO) {
        try {
            val prep = testAndPrepareConnection(config)
            if (!prep.success) return@withContext prep

            val serverUrlClean = normalizeBaseUrl(config.serverUrl, config.allowInsecureHttp)
            // B2-CRYPTO-06 (phase-106): the remote filename is visible to any
            // party who can list the WebDAV folder — never embed epoch-millis.
            // Day-granular + random token (prefix/suffix kept so the download
            // listing regex `noteflow_vault_backup_[^<]+\.nfb` still matches).
            val remoteFileName = BackupFileNamePolicy.remoteVaultBackupFileName()
            val targetUrl = "$serverUrlClean${config.remoteFolderName}/$remoteFileName"

            val conn = createConnection(targetUrl, config, "PUT")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("Content-Length", backupZipFile.length().toString())

            backupZipFile.inputStream().use { input ->
                conn.outputStream.use { output ->
                    input.copyTo(output)
                }
            }

            val responseCode = conn.responseCode
            conn.disconnect()

            if (responseCode in 200..299 || responseCode == 201) {
                SyncResult(
                    success = true,
                    message = "Encrypted backup uploaded to your WebDAV/Nextcloud server.",
                    bytesTransferred = backupZipFile.length(),
                    filesSyncedCount = 1
                )
            } else {
                SyncResult(false, "Upload failed with HTTP response $responseCode")
            }
        } catch (e: IllegalArgumentException) {
            SyncResult(false, e.message ?: "Invalid WebDAV server URL.")
        } catch (e: Exception) {
            SyncResult(false, "Upload failed: ${e.localizedMessage ?: e.message}")
        }
    }

    /**
     * Downloads the latest encrypted vault backup from the WebDAV server into
     * [targetLocalFile]. The caller must then restore it through the app's
     * transactional restore path (never a blind file copy).
     */
    suspend fun downloadLatestEncryptedVault(config: SyncConfig, targetLocalFile: File): SyncResult = withContext(Dispatchers.IO) {
        try {
            val prep = testAndPrepareConnection(config)
            if (!prep.success) return@withContext prep

            val serverUrlClean = normalizeBaseUrl(config.serverUrl, config.allowInsecureHttp)
            val folderUrl = "$serverUrlClean${config.remoteFolderName}/"

            // List files via PROPFIND
            val listConn = createConnection(folderUrl, config, "PROPFIND")
            listConn.setRequestProperty("Depth", "1")
            val listCode = listConn.responseCode

            if (listCode !in 200..299 && listCode != 207) {
                listConn.disconnect()
                return@withContext SyncResult(false, "Failed to list remote folder (HTTP $listCode)")
            }

            val xmlResponse = listConn.inputStream.bufferedReader().use { it.readText() }
            listConn.disconnect()

            // Find remote backup file names from XML response
            val zipRegex = Regex("<d:href>([^<]+noteflow_vault_backup_[^<]+\\.nfb)</d:href>", RegexOption.IGNORE_CASE)
            val matches = zipRegex.findAll(xmlResponse).map { it.groupValues[1] }.toList()

            if (matches.isEmpty()) {
                return@withContext SyncResult(false, "No remote vault backup archives found on WebDAV server.")
            }

            val latestRemotePath = matches.last()
            // B1-NET-01 (phase-40): never trust a server-supplied href. Every
            // href is re-resolved against the configured server origin
            // (scheme+host+port); anything that escapes it is rejected here
            // before any connection or credential is ever involved.
            val downloadUrl = try {
                WebDavHrefResolver.resolveDownloadHref(
                    serverBaseUrl = config.serverUrl,
                    requestUrl = folderUrl,
                    href = latestRemotePath
                )
            } catch (e: IllegalArgumentException) {
                return@withContext SyncResult(
                    false,
                    "Sync refused: the server returned a link that points outside your " +
                        "configured WebDAV server. ${e.message}"
                )
            }

            val downloadConn = createConnection(downloadUrl, config, "GET")
            val downCode = downloadConn.responseCode

            if (downCode in 200..299) {
                downloadConn.inputStream.use { input ->
                    targetLocalFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                downloadConn.disconnect()
                SyncResult(
                    success = true,
                    message = "Downloaded remote encrypted backup archive (${targetLocalFile.length()} bytes)!",
                    bytesTransferred = targetLocalFile.length(),
                    filesSyncedCount = 1
                )
            } else {
                downloadConn.disconnect()
                SyncResult(false, "Download failed with HTTP response $downCode")
            }
        } catch (e: IllegalArgumentException) {
            SyncResult(false, e.message ?: "Invalid WebDAV server URL.")
        } catch (e: Exception) {
            SyncResult(false, "Download failed: ${e.localizedMessage ?: e.message}")
        }
    }
}