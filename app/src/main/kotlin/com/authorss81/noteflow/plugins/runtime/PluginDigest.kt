package com.authorss81.noteflow.plugins.runtime

import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale

/**
 * SHA-256 helpers for the downloadable-plugin runtime (Phase 23).
 *
 * Everything here is PURE JVM so the integrity gates are unit-testable without
 * Android. Two representations are used:
 *
 * - **hex** for the artifact digest carried in [PluginEntry.sha256]
 *   (lowercase, 64 chars).
 * - **base64** for the pinned certificate hash carried in
 *   [PluginEntry.pinnedCertHash] (`sha256/<base64>` — the Android network-
 *   security pin format, reused here as the trust anchor for BOTH the TLS
 *   session and the artifact's signing certificate).
 *
 * Never logs anything — it only hashes.
 */
object PluginDigest {

    /** Raw SHA-256 of [bytes]. */
    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    /** Lowercase hex SHA-256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String =
        sha256(bytes).joinToString("") { String.format(Locale.US, "%02x", it) }

    /** Base64 of the raw SHA-256 of [bytes]. */
    fun sha256Base64(bytes: ByteArray): String =
        Base64.getEncoder().encodeToString(sha256(bytes))

    /**
     * Lowercase hex SHA-256 of a file's bytes, or null when the file cannot be
     * read (missing, unreadable, or a read error). Streamed — never loads the
     * whole artifact into memory.
     */
    fun sha256Hex(file: File): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { String.format(Locale.US, "%02x", it) }
    } catch (_: Throwable) {
        null
    }
}
