package com.authorss81.noteflow.services.localsend

import com.authorss81.noteflow.services.SettingsManager

/**
 * Production [LocalSendPairedDeviceStore] — TOFU pairing anchors persist in the
 * same SharedPreferences as every other app setting (via [SettingsManager]), so
 * a device the user paired stays paired across restarts and an attacker's
 * forged re-announce with a different fingerprint is refused.
 *
 * Values are keyed by the NORMALIZED TLS fingerprint and serialized with
 * [LocalSendPairedDeviceCodec] (pure JVM) so the blob is a plain string.
 */
class SettingsLocalSendPairedDeviceStore(
    private val settings: SettingsManager
) : LocalSendPairedDeviceStore {

    override fun find(fingerprint: String): LocalSendPairedDevice? =
        settings.getLocalSendPairedDeviceJson(fingerprint)?.let(LocalSendPairedDeviceCodec::decode)

    override fun put(device: LocalSendPairedDevice) {
        settings.setLocalSendPairedDeviceJson(
            device.fingerprint,
            LocalSendPairedDeviceCodec.encode(device)
        )
    }

    override fun remove(fingerprint: String): Boolean {
        val existed = find(fingerprint) != null
        settings.setLocalSendPairedDeviceJson(fingerprint, null)
        return existed
    }

    override fun all(): List<LocalSendPairedDevice> =
        settings.allLocalSendPairedFingerprints().mapNotNull { fingerprint ->
            settings.getLocalSendPairedDeviceJson(fingerprint)?.let(LocalSendPairedDeviceCodec::decode)
        }
}