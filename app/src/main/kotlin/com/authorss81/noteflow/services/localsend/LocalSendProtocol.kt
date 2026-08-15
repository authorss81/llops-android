package com.authorss81.noteflow.services.localsend

import com.authorss81.noteflow.utils.ConstantTime
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.annotations.SerializedName
import java.io.File
import java.security.MessageDigest

/**
 * LocalSend Protocol v2.2 — pure JVM building blocks.
 *
 * Reference: https://github.com/localsend/protocol
 *
 * Only the SENDER side is implemented here (the official LocalSend app runs
 * the receiving HTTP server). The pieces in this file are deliberately free of
 * any Android dependency so the URL building, JSON bodies and response parsing
 * can be unit-tested on the JVM without a device or network.
 *
 * Flow implemented:
 *  1. UDP discovery: announce on 224.0.0.167:53317 (+ broadcast) and either
 *     catch the members' unicast UDP fallback responses (`announce:false`) or
 *     find them via the legacy HTTP register scan (`POST /register`).
 *  2. Upload: POST /prepare-upload (the HUMAN CONFIRM step — the receiver only
 *     answers 200 {sessionId, files} after its user accepts, 403 when declined)
 *     then POST /upload?sessionId=..&fileId=..&token=.. with the raw bytes.
 *  3. Cancel: POST /cancel?sessionId=..
 */
object LocalSendProtocol {
    const val DEFAULT_PORT = 53317
    const val MULTICAST_ADDRESS = "224.0.0.167"
    const val BROADCAST_ADDRESS = "255.255.255.255"
    const val PROTOCOL_VERSION = "2.0"

    const val PATH_REGISTER = "/api/localsend/v2/register"
    const val PATH_PREPARE_UPLOAD = "/api/localsend/v2/prepare-upload"
    const val PATH_UPLOAD = "/api/localsend/v2/upload"
    const val PATH_CANCEL = "/api/localsend/v2/cancel"
}

/** A LocalSend-capable device discovered on the local network. */
data class LocalSendDevice(
    val address: String,
    val port: Int,
    val protocol: String,
    val alias: String,
    val version: String,
    val deviceModel: String?,
    val deviceType: String?,
    val fingerprint: String?,
    val download: Boolean = false
) {
    /** http://<ip-or-[v6]>:<port> — the receiver's LocalSend HTTP server root. */
    fun baseUrl(): String {
        val host = if (address.contains(':') && !address.startsWith("[")) "[$address]" else address
        return "$protocol://$host:$port"
    }
}

object LocalSendMessages {

    private val gson = Gson()

    /**
     * Our identity inside the protocol ("info" block). `alias` is what the
     * receiving user sees when asked to accept. Only the alias + fingerprint
     * are "sensitive" in the sense of user-facing identity; no vault content
     * ever goes here.
     */
    data class Info(
        val alias: String,
        val version: String = LocalSendProtocol.PROTOCOL_VERSION,
        val deviceModel: String? = null,
        val deviceType: String? = "mobile",
        val fingerprint: String? = null,
        val port: Int = LocalSendProtocol.DEFAULT_PORT,
        val protocol: String = "https",
        val download: Boolean = false
    )

    /**
     * One file's metadata in the /prepare-upload request body.
     */
    data class FileMeta(
        val id: String,
        val fileName: String,
        val size: Long,
        @SerializedName("fileType") val mimeType: String?,
        val sha256: String?,
        val preview: String? = null
    )

    /**
     * The SENDER's identity announcement (B1-NET-09, phase-110): a fixed,
     * user-set `alias` with NO device-model disclosure. We deliberately do NOT
     * put `Build.MODEL` (or any OS/app/version fingerprint) into
     * `deviceModel`/`alias` — every LAN host that sees the announce, register
     * or prepare-upload bodies must not be able to fingerprint the exact handset.
     *
     * B1-NET-02 (phase-41): the announced `protocol` is NEVER `"http"` — this
     * app only ever speaks TLS for LocalSend (it is sender-only, but the field
     * must not advertise a cleartext endpoint), so a `"https"` value is
     * always announced.
     */
    fun senderIdentity(fingerprint: String): Info = Info(
        alias = "InkFlow",
        version = LocalSendProtocol.PROTOCOL_VERSION,
        deviceModel = null,
        deviceType = "mobile",
        fingerprint = fingerprint,
        port = LocalSendProtocol.DEFAULT_PORT,
        protocol = "https",
        download = false
    )

    // ---- protocol JSON shape (see README.md of localsend/protocol) ----

    private data class AnnounceWire(
        val alias: String,
        val version: String,
        val deviceModel: String?,
        val deviceType: String?,
        val fingerprint: String?,
        val port: Int,
        val protocol: String,
        val download: Boolean,
        val announce: Boolean
    )

    private data class PrepareUploadResponseWire(
        val sessionId: String,
        val files: Map<String, String>
    )

    /** JSON bytes for the UDP multicast/broadcast announcement. */
    fun buildAnnounce(info: Info): ByteArray {
        val wire = AnnounceWire(
            alias = info.alias,
            version = info.version,
            deviceModel = info.deviceModel,
            deviceType = info.deviceType,
            fingerprint = info.fingerprint,
            port = info.port,
            protocol = info.protocol,
            download = info.download,
            announce = true
        )
        return gson.toJson(wire).toByteArray(Charsets.UTF_8)
    }

    /**
     * JSON body for `POST /api/localsend/v2/prepare-upload`.
     * `fileId` must match the id sent back in the response's `files` map.
     */
    fun buildPrepareUploadBody(
        info: Info,
        fileId: String,
        fileName: String,
        sizeBytes: Long,
        mimeType: String?,
        sha256Hex: String?
    ): String {
        val files = mapOf(
            fileId to FileMeta(
                id = fileId,
                fileName = fileName,
                size = sizeBytes,
                mimeType = mimeType,
                sha256 = sha256Hex
            )
        )
        val wire = mapOf(
            "info" to info,
            "files" to files
        )
        return gson.toJson(wire)
    }

    /** JSON body for the legacy/HTTP `POST /api/localsend/v2/register` probe. */
    fun buildRegisterBody(info: Info): String = gson.toJson(info)

    /**
     * Parses a discovery response: either the unicast UDP fallback
     * (`announce:false`) or the legacy-HTTP register response.
     * Returns null when the payload is not a LocalSend discovery message.
     */
    fun parseDiscoveryResponse(
        json: String,
        sourceAddress: String,
        expectedSourcePort: Int = LocalSendProtocol.DEFAULT_PORT,
        sourcePortOverride: Int? = null
    ): LocalSendDevice? {
        if (json.isBlank()) return null
        val wire = try {
            gson.fromJson(json, AnnounceWire::class.java)
        } catch (e: JsonSyntaxException) {
            return null
        } ?: return null
        if (wire.alias.isNullOrBlank() || wire.port <= 0) return null
        val port = sourcePortOverride ?: wire.port.takeIf { it > 0 } ?: expectedSourcePort
        return LocalSendDevice(
            address = sourceAddress,
            port = port,
            protocol = wire.protocol ?: "http",
            alias = wire.alias,
            version = wire.version ?: "",
            deviceModel = wire.deviceModel,
            deviceType = wire.deviceType,
            fingerprint = wire.fingerprint,
            download = wire.download
        )
    }

    /**
     * Parses the `/prepare-upload` success response: `{sessionId, files: {<fileId>: <token>}}`.
     *
     * @throws LocalSendProtocolException when the shape is unusable.
     */
    fun parsePrepareUploadResponse(json: String): PrepareUploadResult {
        if (json.isBlank()) throw LocalSendProtocolException("Empty /prepare-upload response.")
        val wire = try {
            gson.fromJson(json, PrepareUploadResponseWire::class.java)
        } catch (e: JsonSyntaxException) {
            throw LocalSendProtocolException("Unreadable /prepare-upload response.")
        }
        if (wire == null || wire.sessionId.isNullOrBlank() || wire.files.isEmpty()) {
            throw LocalSendProtocolException("Unreadable /prepare-upload response.")
        }
        return PrepareUploadResult(sessionId = wire.sessionId, fileTokens = wire.files)
    }

    /** Result of a successful /prepare-upload: the session + per-file upload tokens. */
    data class PrepareUploadResult(val sessionId: String, val fileTokens: Map<String, String>) {
        fun tokenFor(fileId: String): String? = fileTokens[fileId]
    }

    /** `POST /upload?sessionId=..&fileId=..&token=..` */
    fun buildUploadUrl(baseUrl: String, sessionId: String, fileId: String, token: String): String =
        "$baseUrl${LocalSendProtocol.PATH_UPLOAD}?sessionId=${encode(sessionId)}&fileId=${encode(fileId)}&token=${encode(token)}"

    /** `POST /cancel?sessionId=..` */
    fun buildCancelUrl(baseUrl: String, sessionId: String): String =
        "$baseUrl${LocalSendProtocol.PATH_CANCEL}?sessionId=${encode(sessionId)}"

    private fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8")

    /** A malformed/unsupported protocol message. Message is user-facing and minimal. */
    class LocalSendProtocolException(message: String) : Exception(message)
}

object LocalSendHashing {
    /** Lowercase hex SHA-256. */
    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

    /** Streaming SHA-256 over a file (bounded memory for large exports). */
    fun sha256HexOfFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * LocalSend's HTTPS fingerprint is the SHA-256 of the certificate (hex).
     * Compare case-insensitively, ignoring any `:` separators LocalSend apps
     * may have formatted into their announced value.
     */
    fun fingerprintsMatch(announced: String?, certSha256Hex: String?): Boolean {
        if (announced.isNullOrBlank() || certSha256Hex.isNullOrBlank()) return false
        val a = announced.replace(":", "").lowercase()
        val b = certSha256Hex.replace(":", "").lowercase()
        // B2-CRYPTO-01 (Phase 102): certificate fingerprints are a pin-class
        // secret — `==` leaks the value through the first-mismatch early exit
        // (CWE-650). Both sides are normalized hex, so compare in constant time.
        return ConstantTime.hexEqual(a, b)
    }
}

/** Derives a mime type from a file extension; falls back to octet-stream. */
fun guessMimeType(file: File): String {
    return when (file.extension.lowercase()) {
        "txt" -> "text/plain"
        "md" -> "text/markdown"
        "html", "htm" -> "text/html"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "csv" -> "text/csv"
        "zip" -> "application/zip"
        "nfb" -> "application/octet-stream"
        "m4a", "mp4" -> "application/octet-stream"
        "apk" -> "application/vnd.android.package-archive"
        else -> "application/octet-stream"
    }
}