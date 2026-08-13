package com.authorss81.noteflow.services

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * Real WebDAV & Nextcloud E2EE Vault Sync Engine (Joplin Model).
 * Enables zero-knowledge encrypted backups to user-owned WebDAV or Nextcloud servers.
 * "Your Vault on Your Server".
 */
class WebDavSyncService(private val context: Context) {

    data class SyncConfig(
        val serverUrl: String,
        val username: String,
        val passwordOrToken: String,
        val remoteFolderName: String = "Noteflow_Vault"
    )

    data class SyncResult(
        val success: Boolean,
        val message: String,
        val bytesTransferred: Long = 0L,
        val filesSyncedCount: Int = 0
    )

    private fun createConnection(urlString: String, config: SyncConfig, method: String): HttpURLConnection {
        val url = URL(if (urlString.endsWith("/")) urlString else "$urlString/")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.useCaches = false

        val auth = "${config.username}:${config.passwordOrToken}"
        val encodedAuth = Base64.encodeToString(auth.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        conn.setRequestProperty("Authorization", "Basic $encodedAuth")
        conn.setRequestProperty("User-Agent", "Noteflow-Android-E2EE-Sync/2026")
        return conn
    }

    /**
     * Tests connection to WebDAV / Nextcloud server and creates remote folder if missing.
     */
    suspend fun testAndPrepareConnection(config: SyncConfig): SyncResult = withContext(Dispatchers.IO) {
        try {
            val serverUrlClean = config.serverUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
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
        } catch (e: Exception) {
            SyncResult(false, "Connection failed: ${e.localizedMessage ?: e.message}")
        }
    }

    /**
     * Uploads an E2EE encrypted vault backup to the WebDAV server.
     */
    suspend fun uploadEncryptedVault(config: SyncConfig, backupZipFile: File): SyncResult = withContext(Dispatchers.IO) {
        try {
            val prep = testAndPrepareConnection(config)
            if (!prep.success) return@withContext prep

            val serverUrlClean = config.serverUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
            val remoteFileName = "noteflow_vault_backup_${System.currentTimeMillis()}.zip"
            val targetUrl = "$serverUrlClean${config.remoteFolderName}/$remoteFileName"

            val conn = createConnection(targetUrl, config, "PUT")
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/zip")
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
                    message = "Encrypted vault uploaded successfully to Nextcloud/WebDAV server!",
                    bytesTransferred = backupZipFile.length(),
                    filesSyncedCount = 1
                )
            } else {
                SyncResult(false, "Upload failed with HTTP response $responseCode")
            }
        } catch (e: Exception) {
            SyncResult(false, "Upload failed: ${e.localizedMessage ?: e.message}")
        }
    }

    /**
     * Downloads the latest E2EE encrypted vault backup from the WebDAV server.
     */
    suspend fun downloadLatestEncryptedVault(config: SyncConfig, targetLocalFile: File): SyncResult = withContext(Dispatchers.IO) {
        try {
            val prep = testAndPrepareConnection(config)
            if (!prep.success) return@withContext prep

            val serverUrlClean = config.serverUrl.trim().let { if (it.endsWith("/")) it else "$it/" }
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

            // Find remote zip file names from XML response
            val zipRegex = Regex("<d:href>([^<]+noteflow_vault_backup_[^<]+\\.zip)</d:href>", RegexOption.IGNORE_CASE)
            val matches = zipRegex.findAll(xmlResponse).map { it.groupValues[1] }.toList()

            if (matches.isEmpty()) {
                return@withContext SyncResult(false, "No remote vault backup archives found on WebDAV server.")
            }

            val latestRemotePath = matches.last()
            val downloadUrl = if (latestRemotePath.startsWith("http")) latestRemotePath else {
                val base = URL(config.serverUrl)
                "${base.protocol}://${base.host}${if (base.port != -1) ":${base.port}" else ""}$latestRemotePath"
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
                    message = "Downloaded remote encrypted vault archive (${targetLocalFile.length()} bytes)!",
                    bytesTransferred = targetLocalFile.length(),
                    filesSyncedCount = 1
                )
            } else {
                downloadConn.disconnect()
                SyncResult(false, "Download failed with HTTP response $downCode")
            }
        } catch (e: Exception) {
            SyncResult(false, "Download failed: ${e.localizedMessage ?: e.message}")
        }
    }
}
