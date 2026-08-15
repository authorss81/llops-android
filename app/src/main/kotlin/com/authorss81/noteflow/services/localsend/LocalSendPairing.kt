package com.authorss81.noteflow.services.localsend

import com.authorss81.noteflow.utils.ConstantTime
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import java.util.Locale

/**
 * B1-NET-02 (phase-41) — confirmed-pairing gate for LocalSend sends.
 *
 * A receiver's `200` to `/prepare-upload` is ZERO evidence of human consent:
 * a same-LAN attacker's fake receiver answers 200 immediately, and the "human
 * must accept" step is receiver-side, never cryptographically bound. The only
 * consent that matters lives in this module: a device may only receive bytes
 * after its TLS certificate fingerprint was explicitly verified out-of-band
 * and persisted (TOFU), and every send is preceded by an explicit per-send
 * confirmation in the UI ([LocalSendSendDialog]).
 *
 * This module is pure JVM (no Android, no network) so the whole pairing
 * handshake, the TOFU store and the gate are unit-testable without a device.
 */
data class LocalSendPairedDevice(
    val fingerprint: String,
    val alias: String,
    val pairedAtMillis: Long
) {
    /** Normalized fingerprint (lowercase, no colons) — the store's lookup key. */
    val normalizedFingerprint: String = LocalSendPairingCodes.normalizeFingerprint(fingerprint)
}

/** Persistence abstraction for paired devices. Pure-JVM so it is testable. */
interface LocalSendPairedDeviceStore {
    fun find(fingerprint: String): LocalSendPairedDevice?
    fun put(device: LocalSendPairedDevice)
    fun remove(fingerprint: String): Boolean
    fun all(): List<LocalSendPairedDevice>
}

/** In-memory store (tests / fail-closed default). Not process-persistent. */
class InMemoryLocalSendPairedDeviceStore : LocalSendPairedDeviceStore {
    private val byFingerprint = LinkedHashMap<String, LocalSendPairedDevice>()

    override fun find(fingerprint: String): LocalSendPairedDevice? =
        byFingerprint[LocalSendPairingCodes.normalizeFingerprint(fingerprint)]

    override fun put(device: LocalSendPairedDevice) {
        byFingerprint[device.normalizedFingerprint] = device
    }

    override fun remove(fingerprint: String): Boolean {
        val key = LocalSendPairingCodes.normalizeFingerprint(fingerprint)
        return byFingerprint.remove(key) != null
    }

    override fun all(): List<LocalSendPairedDevice> = byFingerprint.values.toList()
}

/**
 * Human-friendly helpers over a receiver's announced TLS certificate
 * fingerprint (the SHA-256 of the cert, exactly what LocalSend announces).
 */
object LocalSendPairingCodes {

    /** Lowercase, separator-stripped fingerprint — the canonical store key. */
    fun normalizeFingerprint(fingerprint: String): String =
        fingerprint.replace(":", "").lowercase(Locale.ROOT)

    /** Displays the fingerprint the way LocalSend's own apps do (XXXX:XXXX:…). */
    fun formattedFingerprint(fingerprint: String): String {
        val normalized = fingerprint.replace(":", "").uppercase(Locale.ROOT)
        return normalized.chunked(4).joinToString(":")
    }

    /**
     * Deterministic 6-digit out-of-band pairing code derived from the
     * receiver's TLS certificate fingerprint.
     *
     * The receiving device shows its own fingerprint; this short code is a
     * stable, human-comparable digest of the SAME identity so a user checking
     * the receiver out-of-band can compare six digits instead of 64. It is
     * deterministic (same fingerprint → same code) and the pairing handshake
     * refuses to pair when the entered code does not match the fingerprint's
     * derived code (constant-time compare) — a device swap or a typo cannot
     * silently pair the wrong fingerprint.
     */
    fun pairingCode(fingerprint: String): String {
        val normalized = normalizeFingerprint(fingerprint)
        if (normalized.isBlank()) return ""
        val digest = LocalSendHashing.sha256Hex(normalized.toByteArray())
        val numeric = digest.takeLast(10).toLongOrNull(16) ?: 0L
        return String.format(Locale.ROOT, "%06d", numeric % 1_000_000L)
    }
}

/** Outcome of the pre-send gate. */
sealed interface LocalSendGate {
    data class Allowed(val paired: LocalSendPairedDevice) : LocalSendGate
    data class Denied(val reason: String) : LocalSendGate
}

/** Serde for the persisted pairing blob (fingerprint key → alias + timestamp). */
object LocalSendPairedDeviceCodec {
    private val gson = Gson()

    private data class Blob(val fingerprint: String, val alias: String, val t: Long)

    fun encode(device: LocalSendPairedDevice): String {
        require(device.fingerprint.isNotBlank()) { "cannot persist a blank fingerprint" }
        return gson.toJson(Blob(device.normalizedFingerprint, device.alias, device.pairedAtMillis))
    }

    fun decode(json: String): LocalSendPairedDevice? = try {
        val b = gson.fromJson(json, Blob::class.java) ?: return null
        if (b.fingerprint.isNullOrBlank()) null
        else LocalSendPairedDevice(fingerprint = b.fingerprint, alias = b.alias, pairedAtMillis = b.t)
    } catch (e: JsonSyntaxException) {
        null
    }
}

/**
 * The pairing handshake + pre-send gate. Every method is a pure function of
 * its inputs (no network, no I/O) — the only side effect is [LocalSendPairing.pair]
 * persisting through the caller-supplied [LocalSendPairedDeviceStore].
 */
object LocalSendPairing {

    /**
     * Hard gate evaluated BEFORE any payload byte leaves this device. Refuses
     * receivers that are not TLS-capable, that announce no fingerprint, or that
     * were never paired.
     */
    fun gate(device: LocalSendDevice, store: LocalSendPairedDeviceStore): LocalSendGate {
        // NOTE: denial messages deliberately do NOT embed `device.alias` — the
        // alias arrives on the wire from the receiver and is attacker-controlled
        // (a forged announce can set it); the device row already shows it.
        if (!device.protocol.equals("https", ignoreCase = true)) {
            return LocalSendGate.Denied(
                "Refusing to send: the receiving device does not announce a secure (HTTPS) connection."
            )
        }
        val fingerprint = device.fingerprint
        if (fingerprint.isNullOrBlank()) {
            return LocalSendGate.Denied(
                "Refusing to send: the receiving device did not announce a TLS certificate fingerprint."
            )
        }
        val paired = store.find(fingerprint)
            ?: return LocalSendGate.Denied(
                "Refusing to send: the receiving device is not paired yet. Verify its fingerprint and pair it first."
            )
        return LocalSendGate.Allowed(paired)
    }

    /** A pairing request can only start for a TLS-capable device with a fingerprint. */
    fun startPairing(device: LocalSendDevice): LocalSendPairingRequest? {
        if (!device.protocol.equals("https", ignoreCase = true)) return null
        val fingerprint = device.fingerprint ?: return null
        if (fingerprint.isBlank()) return null
        return LocalSendPairingRequest(
            fingerprint = LocalSendPairingCodes.normalizeFingerprint(fingerprint),
            alias = device.alias,
            code = LocalSendPairingCodes.pairingCode(fingerprint)
        )
    }

    /**
     * Confirms a pairing only when [enteredCode] matches the code derived from
     * the receiver's fingerprint (constant-time compare). Returns the paired
     * device on success, null on mismatch.
     */
    fun confirmPairing(request: LocalSendPairingRequest, enteredCode: String): LocalSendPairedDevice? {
        if (!ConstantTime.stringEqual(request.code, enteredCode)) return null
        return LocalSendPairedDevice(
            fingerprint = request.fingerprint,
            alias = request.alias,
            pairedAtMillis = System.currentTimeMillis()
        )
    }

    /** Confirms the pairing handshake and persists the TOFU anchor. */
    fun pair(store: LocalSendPairedDeviceStore, request: LocalSendPairingRequest, enteredCode: String): Boolean {
        val device = confirmPairing(request, enteredCode) ?: return false
        store.put(device)
        return true
    }
}

/** A pending pairing: the receiver's fingerprint + the out-of-band code shown to the user. */
data class LocalSendPairingRequest(
    val fingerprint: String,
    val alias: String,
    val code: String
)
