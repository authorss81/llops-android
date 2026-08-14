package com.authorss81.noteflow.services.localsend

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager
import com.authorss81.noteflow.utils.HttpUserAgent

/**
 * LocalSend sender — real, interoperable implementation (Protocol v2.2).
 *
 * This class talks ONLY to the app's local network (receiver's own HTTP server
 * on port 53317, multicast/broadcast discovery). No internet, no cloud, no
 * SDK. It requires only the already-declared `INTERNET` permission: on Android
 * 13+ we deliberately do NOT use WifiManager scan APIs (which would need
 * NEARBY_WIFI_DEVICES) — discovery goes through raw UDP sockets and HTTP
 * probes that need INTERNET alone (same permission WebDAV already holds).
 *
 * Security posture:
 * - Nothing is sent until the user selects a file AND taps a discovered device.
 * - The receiving device must human-accept: `/prepare-upload` only returns 200
 *   after the receiver's user confirms (403 = declined). We never auto-accept,
 *   and we never serve/receive anything — this is sender-only.
 * - HTTPS receivers are verified against their announced certificate
 *   fingerprint (SHA-256 of the cert). A mismatched cert fails loudly.
 * - The current in-flight connection is exposed for cancellation.
 */
class LocalSendSender {

    companion object {
        private const val TAG = "LocalSendSender"

        /** UDP announce rounds, spaced ~1.1s apart (the spec re-announces). */
        private const val ANNOUNCE_ROUNDS = 2
        private const val ANNOUNCE_INTERVAL_MS = 1_100L

        /** Wall-clock budget for the UDP listen phase per round. */
        private const val UDP_LISTEN_MS_PER_ROUND = 700L

        /** /prepare-upload is long-polling: the receiver holds the connection
         *  open until its user accepts/rejects (which may take a while). */
        private const val PREPARE_READ_TIMEOUT_MS = 180_000
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val UPLOAD_READ_TIMEOUT_MS = 60_000

        private const val LEGACY_SCAN_TIMEOUT_MS = 500
        private const val LEGACY_SCAN_CONCURRENCY = 24
    }

    private class CancellationException(message: String) : Exception(message)

    // Identity shown to receiving devices.
    private val senderFingerprint = "inkflow-" + UUID.randomUUID().toString().replace("-", "")

    private fun senderInfo(): LocalSendMessages.Info =
        LocalSendMessages.senderIdentity(fingerprint = senderFingerprint)

    // ---------------------------------------------------------------------
    // Discovery
    // ---------------------------------------------------------------------

    /**
     * Discovers LocalSend devices on the same LAN. Runs fully off the main
     * thread. Returns de-duplicated devices.
     *
     * [includeLegacyHttpScan] additionally probes every IPv4 in the /24 of the
     * active interface with a tiny `POST /register` (LocalSend "HTTP legacy
     * mode"): this keeps discovery working on networks where wireless AP
     * isolation drops UDP broadcasts but still allows directed TCP.
     */
    suspend fun discoverDevices(
        discoveryTimeoutMs: Long = 3_000L,
        includeLegacyHttpScan: Boolean = true
    ): List<LocalSendDevice> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, LocalSendDevice>()

        val udpResults = udpAnnounceAndListen(discoveryTimeoutMs)
        udpResults.forEach { putDevice(found, it) }

        if (includeLegacyHttpScan && udpResults.isEmpty()) {
            legacyHttpScan().forEach { putDevice(found, it) }
        }

        found.values.toList()
    }

    private fun putDevice(map: LinkedHashMap<String, LocalSendDevice>, device: LocalSendDevice) {
        // Self-discovery guard: never list ourselves.
        if (device.fingerprint != null && device.fingerprint == senderFingerprint) return
        val key = device.fingerprint ?: "${device.alias}@${device.address}"
        map.putIfAbsent(key, device)
    }

    private fun udpAnnounceAndListen(totalBudgetMs: Long): List<LocalSendDevice> {
        val results = ArrayList<LocalSendDevice>()
        val socket = DatagramSocket()
        try {
            socket.broadcast = true
            socket.reuseAddress = true
            val announce = LocalSendMessages.buildAnnounce(senderInfo())

            val deadline = System.currentTimeMillis() + totalBudgetMs
            var round = 0
            var lastAnnounceAt = 0L

            // Listen in small slices; re-announce between slices so members whose
            // first packet was lost still hear us, and we still read their replies.
            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                if (round == 0 || now - lastAnnounceAt >= ANNOUNCE_INTERVAL_MS) {
                    if (round < ANNOUNCE_ROUNDS) {
                        sendAnnounce(socket, announce)
                        lastAnnounceAt = now
                        round++
                    }
                }
                socket.soTimeout = 250
                val response = try {
                    val packet = DatagramPacket(ByteArray(4096), 4096)
                    socket.receive(packet)
                    packet
                } catch (e: SocketTimeoutException) {
                    null
                }
                if (response != null) {
                    parseUdpPacket(response)?.let { results.add(it) }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "UDP discovery failed: ${e::class.java.simpleName}")
        } finally {
            runCatching { socket.close() }
        }
        return results
    }

    private fun sendAnnounce(socket: DatagramSocket, announce: ByteArray) {
        val targets = mutableListOf(LocalSendProtocol.MULTICAST_ADDRESS, LocalSendProtocol.BROADCAST_ADDRESS)
        // Android's NetworkInterface has no getBroadcast(): derive the standard
        // /24 subnet broadcast for each non-loopback IPv4 link (home/office Wi-Fi
        // is /24 in practice — exactly what LocalSend's own legacy scan assumes).
        localIpv4Addresses().forEach { ip ->
            val octets = ip.split('.')
            if (octets.size == 4) {
                targets.add(octets.take(3).joinToString(".") + ".255")
            }
        }
        targets.distinct().forEach { host ->
            runCatching {
                val packet = DatagramPacket(announce, announce.size, InetAddress.getByName(host), LocalSendProtocol.DEFAULT_PORT)
                socket.send(packet)
            }
        }
    }

    private fun parseUdpPacket(packet: DatagramPacket): LocalSendDevice? {
        val body = String(packet.data, 0, packet.length, Charsets.UTF_8)
        return LocalSendMessages.parseDiscoveryResponse(
            json = body,
            sourceAddress = packet.address.hostAddress ?: return null
        )
    }

    /** POST /api/localsend/v2/register to every /24 neighbour. Bounded
     *  concurrency; each probe has a 500ms timeout. This is the protocol's
     *  documented "HTTP legacy mode" discovery fallback. */
    private suspend fun legacyHttpScan(): List<LocalSendDevice> = coroutineScope {
        val localIps = localIpv4Addresses()
        val queries = mutableListOf<Pair<String, String>>()
        localIps.forEach { localIp ->
            val parts = localIp.split('.')
            if (parts.size == 4) {
                val subnet = parts.take(3).joinToString(".")
                for (i in 1..254) {
                    val target = "$subnet.$i"
                    if (target != localIp) queries.add(localIp to target)
                }
            }
        }

        val results = mutableListOf<LocalSendDevice>()
        val batches = queries.chunked(LEGACY_SCAN_CONCURRENCY)
        batches.take(40).forEach { batch ->
            val scan = batch.map { (_, target) ->
                async(Dispatchers.IO) { httpRegisterProbe(target) }
            }
            results.addAll(scan.awaitAll().filterNotNull())
        }
        results
    }

    private fun localIpv4Addresses(): List<String> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList() }
            .filter { it is java.net.Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .map { it.hostAddress }
    } catch (_: Exception) {
        emptyList()
    }

    private fun httpRegisterProbe(targetIp: String): LocalSendDevice? {
        val conn: HttpURLConnection = try {
            val url = URL("http://$targetIp:${LocalSendProtocol.DEFAULT_PORT}${LocalSendProtocol.PATH_REGISTER}")
            (url.openConnection() as HttpURLConnection)
        } catch (e: Exception) {
            return null
        }
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = LEGACY_SCAN_TIMEOUT_MS
            conn.readTimeout = LEGACY_SCAN_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", HttpUserAgent.GENERIC)
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            val body = LocalSendMessages.buildRegisterBody(senderInfo()).toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code in 200..202) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }.take(2048)
                LocalSendMessages.parseDiscoveryResponse(response, targetIp)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ---------------------------------------------------------------------
    // Sending
    // ---------------------------------------------------------------------

    data class SendResult(
        val success: Boolean,
        val description: String,
        val bytesSent: Long = 0L
    )

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    @Volatile
    private var transferCancelled = false

    /** Sets the cancellation flag and force-closes the in-flight HTTP call. */
    fun cancelActiveTransfer() {
        transferCancelled = true
        runCatching { activeConnection?.disconnect() }
    }

    fun clearCancellation() {
        transferCancelled = false
        activeConnection = null
    }

    /**
     * Sends [file] to [device]. Runs on a background dispatcher.
     *
     * The receiver's `/prepare-upload` returns only after its user accepts
     * (200 + sessionId) or declines (403). We surface exactly those states.
     */
    suspend fun sendFile(
        device: LocalSendDevice,
        file: File,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit
    ): SendResult = withContext(Dispatchers.IO) {
        clearCancellation()
        if (!file.exists() || file.length() == 0L) {
            return@withContext SendResult(false, "The file to send is empty or missing.")
        }

        val info = senderInfo()
        val fileId = UUID.randomUUID().toString()
        val total = file.length()

        val sha256 = try {
            LocalSendHashing.sha256HexOfFile(file)
        } catch (e: Exception) {
            return@withContext SendResult(false, "Could not read the file to send.")
        }

        // 1. /prepare-upload — the human-confirm gate.
        val prepareBody = LocalSendMessages.buildPrepareUploadBody(
            info = info,
            fileId = fileId,
            fileName = file.name,
            sizeBytes = total,
            mimeType = guessMimeType(file),
            sha256Hex = sha256
        )
        val prepareUrl = device.baseUrl() + LocalSendProtocol.PATH_PREPARE_UPLOAD

        val prepareResp = try {
            httpPost(prepareUrl, prepareBody, "application/json", PREPARE_READ_TIMEOUT_MS, device.fingerprint)
        } catch (e: CancellationException) {
            return@withContext SendResult(false, "Transfer cancelled.")
        } catch (e: SocketTimeoutException) {
            return@withContext SendResult(false, "Timed out waiting for the receiving device. Is it online and on the same Wi-Fi?")
        } catch (e: Exception) {
            return@withContext SendResult(false, mapTransportError(e))
        }

        if (!prepareResp.success) {
            return@withContext SendResult(false, describePrepareHttpCode(prepareResp.code))
        }

        val prepared = try {
            LocalSendMessages.parsePrepareUploadResponse(prepareResp.body.orEmpty())
        } catch (e: Exception) {
            return@withContext SendResult(false, e.message ?: "Unexpected response from the receiving device.")
        }
        val token = prepared.tokenFor(fileId)
        if (token.isNullOrBlank()) {
            return@withContext SendResult(false, "The receiving device did not accept this file.")
        }

        // 2. /upload — stream the bytes with progress + cancellation.
        val uploadUrl =
            LocalSendMessages.buildUploadUrl(device.baseUrl(), prepared.sessionId, fileId, token)

        val uploadResp = try {
            streamUpload(uploadUrl, file, total, onProgress, device.fingerprint)
        } catch (e: CancellationException) {
            // Best-effort: tell the receiver we are done so it can clean up.
            runCatching {
                httpPost(
                    LocalSendMessages.buildCancelUrl(device.baseUrl(), prepared.sessionId),
                    "",
                    "application/json",
                    CONNECT_TIMEOUT_MS,
                    device.fingerprint
                )
            }
            return@withContext SendResult(false, "Transfer cancelled.")
        } catch (e: Exception) {
            return@withContext SendResult(false, mapTransportError(e))
        }

        when (uploadResp.code) {
            in 200..299 -> SendResult(true, "Sent to ${device.alias}", uploadResp.bytesSent)
            403 -> SendResult(false, "The receiving device rejected the transfer.")
            409 -> SendResult(false, "The receiving device is already busy with another transfer.")
            422 -> SendResult(false, "File verification failed on the receiving device (checksum mismatch).")
            400 -> SendResult(false, "The receiving device rejected the request details.")
            500 -> SendResult(false, "The receiving device reported an internal error.")
            else -> SendResult(false, "The receiving device returned HTTP ${uploadResp.code}.")
        }
    }

    /** Minimal HTTP response carrier. */
    private class HttpOutcome(
        val success: Boolean,
        val code: Int,
        val body: String?,
        val bytesSent: Long = 0L
    )

    private fun httpPost(
        urlString: String,
        body: String,
        contentType: String,
        readTimeoutMs: Int,
        expectedFingerprint: String? = null
    ): HttpOutcome {
        val conn = openConnection(urlString, readTimeoutMs, expectedFingerprint) ?: throw IOExceptionCompat("Could not reach the device.")
        try {
            activeConnection = conn
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.setRequestProperty("Accept", "application/json")
            val bytes = body.toByteArray(Charsets.UTF_8)
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            val responseBody = if (code in 200..299) {
                runCatching { conn.inputStream.bufferedReader().use { it.readText() }.take(8192) }.getOrNull()
            } else {
                runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(512) }.getOrNull()
            }
            return HttpOutcome(code in 200..299, code, responseBody)
        } finally {
            runCatching { conn.disconnect() }
            activeConnection = null
        }
    }

    private fun streamUpload(
        urlString: String,
        file: File,
        total: Long,
        onProgress: (Long, Long) -> Unit,
        expectedFingerprint: String? = null
    ): HttpOutcome {
        verifyNotCancelled()
        val conn = openConnection(urlString, UPLOAD_READ_TIMEOUT_MS, expectedFingerprint)
            ?: throw IOExceptionCompat("Could not reach the device.")
        try {
            activeConnection = conn
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/octet-stream")
            conn.setRequestProperty("Content-Length", total.toString())
            BufferedInputStream(file.inputStream()).use { input ->
                conn.outputStream.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var sent = 0L
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        verifyNotCancelled()
                        output.write(buffer, 0, read)
                        sent += read
                        onProgress(sent, total)
                    }
                }
            }
            verifyNotCancelled()
            val code = conn.responseCode
            return HttpOutcome(code in 200..299, code, null, bytesSent = total)
        } finally {
            runCatching { conn.disconnect() }
            activeConnection = null
        }
    }

    private fun openConnection(
        urlString: String,
        readTimeoutMs: Int,
        expectedFingerprint: String?
    ): HttpURLConnection? {
        val url = URL(urlString)
        val conn: HttpURLConnection = if (url.protocol == "https") {
            if (expectedFingerprint.isNullOrBlank()) {
                throw IOExceptionCompat(
                    "The device did not announce a TLS fingerprint, so a secure connection cannot be verified."
                )
            }
            val https = url.openConnection() as HttpsURLConnection
            https.sslSocketFactory = pinnedSslContext(expectedFingerprint).socketFactory
            // The certificate is pinned via the announced fingerprint below;
            // hostname verification is meaningless against a raw IP.
            https.hostnameVerifier = TRUST_ALL_HOSTNAMES
            https
        } else {
            url.openConnection() as HttpURLConnection
        }
        conn.readTimeout = readTimeoutMs
        conn.setRequestProperty("User-Agent", HttpUserAgent.GENERIC)
        return conn
    }

    private fun verifyNotCancelled() {
        if (transferCancelled) throw CancellationException("user cancelled")
    }

    /**
     * Pins the receiver's TLS certificate to the fingerprint it announced
     * during discovery (SHA-256 of the cert). A device that serves a different
     * cert than it announced fails loudly — never a silent bypass.
     */
    private fun pinnedSslContext(expectedFingerprint: String): SSLContext {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf(LocalSendTrustManager(expectedFingerprint)), SecureRandom())
        return context
    }

    private class LocalSendTrustManager(private val expectedFingerprint: String) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {
            chain?.firstOrNull()?.let { validate(it) }
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {
            if (chain == null || chain.isEmpty()) {
                throw CertificateException("No TLS certificate presented by the receiving device.")
            }
            validate(chain[0])
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

        /** Accepts a certificate only if its SHA-256 matches what the device
         *  announced as its LocalSend fingerprint. */
        private fun validate(cert: X509Certificate) {
            val certFingerprint = LocalSendHashing.sha256Hex(cert.encoded)
            if (!LocalSendHashing.fingerprintsMatch(expectedFingerprint, certFingerprint)) {
                throw CertificateException("LocalSend TLS certificate fingerprint mismatch.")
            }
        }
    }

    private object TRUST_ALL_HOSTNAMES : HostnameVerifier {
        override fun verify(hostname: String?, session: SSLSession?) = true
    }

    private fun describePrepareHttpCode(code: Int): String = when (code) {
        401 -> "The receiving device requires a PIN to accept transfers."
        403 -> "The receiving device declined the transfer request."
        409 -> "The receiving device is already busy with another transfer."
        429 -> "The receiving device is busy — try again in a moment."
        400 -> "The receiving device rejected the request details."
        204 -> "The receiving device reported nothing to transfer."
        500 -> "The receiving device reported an internal error."
        else -> "The receiving device returned HTTP $code."
    }

    private fun mapTransportError(e: Exception): String = when (e) {
        is java.net.ConnectException -> "Could not connect to the device. Check that it is online and on the same Wi-Fi."
        is SocketTimeoutException -> "Connection to the device timed out."
        else -> {
            val message = e.message
            if (message.isNullOrBlank()) "Transfer failed: ${e::class.java.simpleName}" else message
        }
    }

    private class IOExceptionCompat(message: String) : java.io.IOException(message)
}