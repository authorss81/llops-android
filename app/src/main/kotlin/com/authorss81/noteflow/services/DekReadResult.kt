package com.authorss81.noteflow.services

/**
 * B1-CRYPTO-05 (phase-64): the outcome of reading the device-wrapped DEK copy,
 * distinguishing "no copy stored at all" from "a copy is stored but cannot be
 * unwrapped". Pre-fix, [SecurityService.readDek] returned null on BOTH, and
 * [SecurityService.getOrCreateDek] then silently minted a brand-new DEK and
 * OVERWROTE the stored wrapper — the next SQLCipher open tried the new DEK
 * against the still-encrypted vault, tripped the phase-09 H2 quarantiner, and
 * the genuinely-survivable vault was reported as "corrupt" with no diagnostic
 * distinguishing keystore-key loss from data corruption.
 *
 * Pure JVM (no Android imports) so the decision is unit-testable in
 * `app/src/test` via the [DekDeviceStore] seam.
 */
sealed class DekReadResult {

    /** No device-wrapped copy is stored (true first run, or deliberately cleared). */
    object NoBlob : DekReadResult()

    /** The device copy unwrapped successfully; [dek] is the vault DEK. */
    data class Unlocked(val dek: ByteArray) : DekReadResult()

    /**
     * The device copy is the biometric-gated wrapper — it requires the biometric
     * unlock flow and must never be read (or re-wrapped) without a credential.
     */
    object AuthRequired : DekReadResult()

    /**
     * A device copy IS stored but could not be unwrapped: the AndroidKeyStore
     * key that wrapped it is gone (app-data restore / ROM migration / keystore
     * reset) or the blob itself is unreadable. The vault database is NOT corrupt
     * — only the device wrapper is lost. Callers MUST route to the explicit
     * recovery screen (restore-from-backup / explicit start-fresh) and MUST NEVER
     * mint a replacement key over this state (that is exactly the silent re-key
     * the finding forbids — it would destroy access to the still-encrypted vault).
     *
     * [wrapperAlias] is the non-secret marker persisted at wrap time saying which
     * keystore alias should hold the wrapping key (diagnostic + recovery copy).
     */
    data class KeyLost(val wrapperAlias: String?) : DekReadResult()
}

/**
 * B1-CRYPTO-05 (phase-64): thrown when a stored-but-undecryptable device DEK
 * copy is encountered at a mint site. The caller was about to silently generate
 * a fresh DEK and persist it over the unreadable wrapper; throwing instead
 * forces the explicit recovery flow. Never catches-and-returns-null at a mint
 * site — a null there still falls through to a mint in `getOrCreateDek`.
 */
class KeystoreKeyLostException(
    message: String,
    /** Non-secret alias marker persisted with the unreadable blob (may be null for legacy blobs). */
    val wrapperAlias: String? = null,
) : RuntimeException(message)
