package com.authorss81.noteflow.services.localsend

import android.content.Context
import com.authorss81.noteflow.services.SettingsManager

/**
 * Phase 173 (feature 1): host-side factory for the [FileTransferSender] seam.
 *
 * The plugin implementation (`LocalSendFileTransferPlugin`) must not reference
 * vault-handle types (`SettingsManager`, …) — the plugin-host surface under
 * `plugins.*` is what downloadable artifacts resolve against — so building the
 * production sender from an Android context lives HERE, in plain host code,
 * and the plugin defaults its injectable `senderFactory` to this seam.
 */
object LocalSendSenderFactory {

    /**
     * A real [LocalSendSender] bound to the same TOFU-paired-device store the
     * HomeScreen send dialog uses, or null when there is no Android context
     * (pure-JVM tests / background contexts) — fail-closed by construction.
     */
    fun defaultSenderOf(context: Context?): FileTransferSender? {
        if (context == null) return null
        return LocalSendSender(
            SettingsLocalSendPairedDeviceStore(SettingsManager(context.applicationContext))
        )
    }
}