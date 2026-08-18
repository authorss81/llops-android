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
 * Security posture (B1-NET-02, phase-41):
 * - Nothing is sent until the user selects a file AND taps a discovered device
 *   AND confirms the per-send dialog ([LocalSendSendDialog]).
 * - The receiver must be PAIRED: its TLS certificate fingerprint must have been
 *   explicitly verified out-of-band and persisted (TOFU,
 *   [LocalSendPairedDeviceStore]) or [sendFile] refuses before any byte moves.
 * - TLS is REQUIRED for any payload: a receiver announcing `protocol:"http"`
 *   (or no fingerprint) can never receive bytes; `openConnection` refuses
 *   non-https URLs outright.
 * - The receiver's `/prepare-upload` 200 is treated as ZERO evidence of human
 *   consent (a fake receiver answers it immediately). Consent is the pairing +
 *   the user's explicit per-send confirmation only.
 * - HTTPS receivers are additionally verified against their announced
 *   certificate fingerprint (SHA-256 of the cert). A mismatched cert fails
 *   loudly.
 * - The current in-flight connection is exposed for cancellation.
 */
class LocalSendSender(
    private val pairedDevices: LocalSendPairedDeviceStore = InMemoryLocalSendPairedDeviceStore()
) {

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
     * B1-NET-06 (phase-85): a search only ever happens after the user explicitly
     * asked for it (the dialog's "Find nearby devices" action) — opening the
     * dialog transmits nothing. Discovery defaults to UDP announce/listen ONLY;
     * the `/24` HTTP register sweep (LocalSend "HTTP legacy mode") is gated by
     * [LocalSendDiscoveryPolicy.LEGACY_HTTP_SCAN_ENABLED_BY_DEFAULT] and runs
     * here only when the caller explicitly opted in for it (per-search checkbox),
     * so a plain search never blasts 254 HTTP POSTs across the subnet.
     */
    suspend fun discoverDevices(
        discoveryTimeoutMs: Long = 3_000L,
        includeLegacyHttpScan: Boolean = LocalSendDiscoveryPolicy.LEGACY_HTTP_SCAN_ENABLED_BY_DEFAULT
    ): List<LocalSendDevice> = withContext(Dispatchers.IO) {
        val found = LinkedHashMap<String, LocalSendDevice>()

        val udpResults = udpAnnounceAndListen(discoveryTimeoutMs)
        udpResults.forEach { putDevice(found, it) }

        if (LocalSendDiscoveryPolicy.mayRunLegacyHttpScan(includeLegacyHttpScan) && udpResults.isEmpty()) {
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
            val http = url.openConnection() as HttpURLConnection
            // B1-NET-05 (phase-52): this legacy-register probe must not
            // auto-follow a 3xx either — a redirecting LAN peer would
            // otherwise forward the probe POST off-target. Refuse the
            // hop so a 3xx surfaces as a failed probe.
            http.instanceFollowRedirects = false
            http
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
                // R2-B1N-01: the peer's response body is read CAPPED mid-stream —
                // an endless register-probe body can no longer be slurped whole.
                // An over-cap body fails the probe (null) rather than feeding a
                // truncated parse.
                val response = conn.inputStream.bufferedReader().use {
                    LocalSendBodyReadPolicy.readText(it, LocalSendBodyReadPolicy.REGISTER_BODY_LIMIT)
                }
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

        // B1-NET-02 gate: the ONLY way a payload may leave this device.
        // Consent is not the receiver's prepare-upload 200 (a fake receiver
        // answers that immediately). Consent is: (1) the receiver is paired —
        // its TLS cert fingerprint was verified out-of-band and persisted
        // (TOFU), and (2) the user confirmed this send in the dialog. This
        // gate also enforces HTTPS-only: a receiver that announces
        // `protocol:"http"` (or none) is refused before any byte moves.
        val gate = LocalSendPairing.gate(device, pairedDevices)
        if (gate is LocalSendGate.Denied) {
            return@withContext SendResult(false, gate.reason)
        }
        val allowed = gate as LocalSendGate.Allowed
        // The alias the user PAIRED — not the wire-supplied announce alias an
        // attacker could forge ("Galaxy S24") — is what we display going forward.
        val pairedAlias = allowed.paired.alias.ifBlank { device.alias }
        // Pin every payload connection to the STORED paired fingerprint (verified
        // out-of-band at pairing time), never to a value fetched from the wire
        // announce: a fingerprint from a later forged announce is refused by the
        // gate above, but this keeps the pin itself attacker-independent.
        val trustedFingerprint = allowed.paired.normalizedFingerprint

        val info = senderInfo()
        val fileId = UUID.randomUUID().toString()
        val total = file.length()

        val sha256 = try {
            LocalSendHashing.sha256HexOfFile(file)
        } catch (e: Exception) {
            return@withContext SendResult(false, "Could not read the file to send.")
        }

        // 1. /prepare-upload — the receiver's own accept/decline step. NOTE
        // (B1-NET-02): a `200` here is NOT treated as proof a human accepted —
        // a fake receiver answers it immediately. The security boundary is the
        // pairing + per-send confirmation above; this call only exists because
        // the protocol needs its sessionId/token to stream bytes.
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
            httpPost(prepareUrl, prepareBody, "application/json", PREPARE_READ_TIMEOUT_MS, trustedFingerprint)
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
            streamUpload(uploadUrl, file, total, onProgress, trustedFingerprint)
        } catch (e: CancellationException) {
            // Best-effort: tell the receiver we are done so it can clean up.
            runCatching {
                httpPost(
                    LocalSendMessages.buildCancelUrl(device.baseUrl(), prepared.sessionId),
                    "",
                    "application/json",
                    CONNECT_TIMEOUT_MS,
                    trustedFingerprint
                )
            }
            return@withContext SendResult(false, "Transfer cancelled.")
        } catch (e: Exception) {
            return@withContext SendResult(false, mapTransportError(e))
        }

        when (uploadResp.code) {
            in 200..299 -> SendResult(true, "Sent to $pairedAlias", uploadResp.bytesSent)
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
            // R2-B1N-01: both bodies come from an untrusted LAN peer and are read
            // CAPPED mid-stream (never slurped-then-truncated). An over-cap body
            // yields a null outcome body — the transfer is refused, fail closed.
            val responseBody = if (code in 200..299) {
                runCatching {
                    conn.inputStream.bufferedReader().use {
                        LocalSendBodyReadPolicy.readText(it, LocalSendBodyReadPolicy.SUCCESS_BODY_LIMIT)
                    }
                }.getOrNull()
            } else {
                runCatching {
                    conn.errorStream?.bufferedReader()?.use {
                        LocalSendBodyReadPolicy.readText(it, LocalSendBodyReadPolicy.ERROR_BODY_LIMIT)
                    }
                }.getOrNull()
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
        // B1-NET-02 (phase-41): TLS is REQUIRED for any payload. A `http:` URL
        // here is a bug or a downgrade attempt — refuse loudly, never fall back
        // to cleartext (the pairing gate already refuses `protocol:"http"`
        // receivers, this is defense-in-depth for every payload connection).
        if (url.protocol != "https") {
            throw IOExceptionCompat(
                "Refusing to send without TLS (the receiving device does not announce a secure connection)."
            )
        }
        if (expectedFingerprint.isNullOrBlank()) {
            throw IOExceptionCompat(
                "The device did not announce a TLS fingerprint, so a secure connection cannot be verified."
            )
        }
        val https = url.openConnection() as HttpsURLConnection
        https.sslSocketFactory = pinnedSslContext(expectedFingerprint).socketFactory
        // B1-NET-05 (phase-52): never auto-follow a 3xx — the receiving device's
        // payload endpoints are built app-side, so a redirect could only be a
        // server-side downgrade/forward of the transfer bytes. A redirecting peer
        // answers with its 3xx code, which the transfer path treats as a failure.
        https.instanceFollowRedirects = false
        // The certificate is pinned via the announced (paired) fingerprint; a
        // hostname check against a raw IP is meaningless — so we trust the pin.
        https.hostnameVerifier = TRUST_ALL_HOSTNAMES
        https.readTimeout = readTimeoutMs
        https.setRequestProperty("User-Agent", HttpUserAgent.GENERIC)
        return https
    }

    private fun verifyNotCancelled() {
        if (transferCancelled) throw CancellationException("user cancelled")
    }

    /**
     * Pins the receiver's TLS certificate to the fingerprint it announced
     * during discovery (SHA-256 of the cert). Because the [LocalSendPairing.gate]
     * already required that fingerprint to be PAIRED (user-verified out-of-band
     * and persisted, B1-NET-02), this pin authenticates the receiver — a fake
     * receiver announcing its own cert is refused at the pairing gate before
     * it ever reaches here, and a device that serves a different cert than it
     * announced fails loudly here.
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
