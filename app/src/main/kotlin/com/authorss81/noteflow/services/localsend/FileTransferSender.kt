package com.authorss81.noteflow.services.localsend

import java.io.File

/**
 * Phase 173 (feature 1): the minimal seam the FileTransfer plugin talks through.
 *
 * The production implementation is the EXISTING [LocalSendSender] (Protocol
 * v2.2) — the plugin REUSES it, never forks it, and never constructs LocalSend
 * messages/endpoints itself. This interface exists so the plugin's capability
 * mapping (device + payload → exactly one `sendFile` call on [LocalSendSender])
 * is unit-testable with a recording fake and zero network.
 *
 * NOTE (review-fix, phase-173): this seam and its types (incl. [LocalSendDevice]
 * and [LocalSendSender.SendResult]) live in app, NODE in the plugin SDK, so the
 * `PluginFrameworkClassLoader` sandbox (downloadable artifacts may resolve only
 * `com.authorss81.noteflow.plugins.*`) means it is implementable by compile-time
 * / host plugins and used as a unit-test surface — NOT by a downloadable plugin.
 * A future downloadable `FileTransfer` server plugin needs a surface-neutral
 * seam with no `services.*` types in its signatures.
 *
 * Both methods run off the main thread and enforce the sender's security model
 * (TLS-only payloads, TOFU pairing gate, receiver-side `/prepare-upload` accept)
 * exactly as the production flow does.
 */
interface FileTransferSender {

    /** Discover LocalSend receivers on the local network. See
     *  [LocalSendSender.discoverDevices] for the B1-NET-06 consent rules. */
    suspend fun discoverDevices(
        discoveryTimeoutMs: Long = 3_000L,
        includeLegacyHttpScan: Boolean = LocalSendDiscoveryPolicy.LEGACY_HTTP_SCAN_ENABLED_BY_DEFAULT
    ): List<LocalSendDevice>

    /**
     * Send [file] to [device]. Returns the sender's typed [LocalSendSender.SendResult];
     * every failure state is a non-success (never a thrown exception).
     */
    suspend fun sendFile(
        device: LocalSendDevice,
        file: File,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit
    ): LocalSendSender.SendResult
}