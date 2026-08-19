package com.authorss81.noteflow.plugins.filetransfer

import android.content.Context
import com.authorss81.noteflow.plugins.FileTransferOutcome
import com.authorss81.noteflow.plugins.FileTransferPlugin
import com.authorss81.noteflow.plugins.FileTransferRequest
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginPermission
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.services.UiFailureTextPolicy
import com.authorss81.noteflow.services.localsend.FileTransferSender
import com.authorss81.noteflow.services.localsend.LocalSendDevice
import com.authorss81.noteflow.services.localsend.LocalSendSender
import com.authorss81.noteflow.services.localsend.LocalSendSenderFactory

/**
 * Phase 173 (feature 1): the compile-time plugin that serves the
 * [PluginCapability.FileTransfer] capability over the EXISTING LocalSend
 * Protocol v2.2 sender (`services/localsend/LocalSendSender.kt`).
 *
 * It sends a note (HTML), the encrypted vault backup, or an Obsidian/HTML
 * export to a nearby device — exactly the payload kinds HomeScreen's ⋮ menu
 * already offers via [LocalSendSender].
 *
 * ## Reuse, not fork
 *
 * The plugin does NOT re-implement the protocol. It talks through the
 * [FileTransferSender] seam whose production implementation IS
 * [LocalSendSender] — the same sender the built-in HomeScreen dialog uses.
 * Every security property therefore holds unchanged: TLS-only payloads, the
 * TOFU pairing gate ([LocalSendPairing.gate] runs inside the sender BEFORE any
 * byte leaves the device), and the receiver's own `/prepare-upload` human-accept
 * step. A device that is not paired can never receive bytes through this
 * plugin, and the plugin never constructs a raw LocalSend message.
 *
 * ## Opt-in
 *
 * Like every compile-time plugin this one is OFF by default. Enabling it
 * (Settings → Plugins, or the Plugin Store) is what routes FileTransfer
 * requests to it — until then requests fail loudly with the manager's
 * `NONE_ENABLED`/`NO_PLUGIN_INSTALLED` semantics.
 *
 * ## Fail-closed
 *
 * - `availability()` is `Ok` (INTERNET is a normal, always-granted permission
 *   and the sender needs no runtime permission — the real consent gates live
 *   inside the sender).
 * - `sendFile`/`discover` with no sender in this context (JVM tests, background
 *   contexts) return a typed [FileTransferOutcome.Error] / null — never a
 *   silent no-op.
 * - Every sender failure maps to [FileTransferOutcome.Rejected] and every
 *   description passes [UiFailureTextPolicy.scrubForUi] before it may reach the
 *   user (R2-b2b3-LOG-03 precedent) — the sender's own messages are already
 *   path-free, this is defense-in-depth.
 *
 * Pure-JVM-testable: tests inject a recording [FileTransferSender] fake and the
 * plugin's mapping (device + payload → one `sendFile` call) is asserted with
 * zero network.
 */
class LocalSendFileTransferPlugin(
    private val senderFactory: (Context?) -> FileTransferSender? = LocalSendSenderFactory::defaultSenderOf
) : NoteflowPlugin, FileTransferPlugin {

    override val manifest = PluginManifest(
        id = "plugins.filetransfer",
        name = "File Transfer",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "Send this note, the encrypted vault backup, or an Obsidian/HTML export to a nearby " +
            "device over LocalSend (local network only; the receiving device must accept before any bytes move).",
        capabilities = setOf(PluginCapability.FileTransfer),
        permissions = setOf(PluginPermission.Internet)
    )

    override fun availability(context: Context?): PluginAvailability =
        // INTERNET is a normal (always-granted) permission — the sender itself
        // needs no runtime permission. The real gates (TOFU pairing, TLS-only,
        // receiver accept) are enforced inside LocalSendSender on every transfer.
        PluginAvailability.Ok

    override fun onEnable(context: Context?, settings: PluginSettings) {
        // No per-config state; the sender's TOFU pairing store and the
        // per-send consent dialogs are the real gates and live in the app.
    }

    override suspend fun sendFile(
        context: Context?,
        request: FileTransferRequest,
        onProgress: (sentBytes: Long, totalBytes: Long) -> Unit
    ): FileTransferOutcome {
        val sender = senderFactory(context)
            ?: return FileTransferOutcome.Error(
                "File transfer is unavailable here (no LocalSend sender in this context). " +
                    "Open InkFlow and try again from the app."
            )
        if (!request.file.exists() || request.file.length() == 0L) {
            return FileTransferOutcome.Error("The file to send is empty or missing.")
        }
        val result = sender.sendFile(request.device, request.file) { sent, total ->
            // Forward the sender's own progress (same numbers the HomeScreen
            // dialog shows); a caller without a progress UI passes a no-op.
            onProgress(sent, total)
        }
        return if (result.success) {
            FileTransferOutcome.Sent(result.bytesSent, scrub(result.description) ?: "The file was sent.")
        } else {
            // Fail-closed: the transfer did not happen (pairing gate, receiver
            // decline, transport failure). The sender's reason is fixed-label or
            // scrubbed on the way out.
            FileTransferOutcome.Rejected(scrub(result.description) ?: "The transfer was not performed.")
        }
    }

    override suspend fun discover(context: Context?, timeoutMillis: Long): List<LocalSendDevice>? {
        val sender = senderFactory(context) ?: return null
        // B1-NET-06: the legacy /24 HTTP register sweep stays OFF unless the
        // human explicitly opts in for it — a capability discovery is a plain,
        // cheap UDP announce/listen only.
        return sender.discoverDevices(timeoutMillis, includeLegacyHttpScan = false)
    }

    private fun scrub(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val scrubbed = UiFailureTextPolicy.scrubForUi(text).trim()
        return scrubbed.takeIf { it.isNotEmpty() }
    }
}