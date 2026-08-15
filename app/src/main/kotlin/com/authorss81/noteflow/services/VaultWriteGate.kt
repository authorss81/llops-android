package com.authorss81.noteflow.services

/**
 * B2-UI-1 (phase-49): fail-closed gate for the repository's field-encrypted
 * write paths.
 *
 * A write that is declared "encrypted at rest" must NEVER persist its raw
 * plaintext when the vault is locked. The old code did exactly that:
 * `encryptionKey?.let { EncryptionService.encryptField(...) } ?: rawText` — so
 * an autosave / dispose-flush coroutine that outlived a `lock()` wrote plaintext
 * rows (stroke textContent/pointsJson, sticky-note/embed textContent, full
 * note_versions bodies) into the SQLCipher DB. When the DEK is zeroized/absent
 * we THROW here instead of falling back to the raw string.
 *
 * Pure JVM (no Android/Room/SQLCipher references) so the decision itself is
 * unit-testable on the CI runner.
 */
object VaultWriteGate {

    /**
     * Returns [dek] when the vault is unlocked, or throws
     * [VaultLockedWriteException] when it is not. Nothing is ever written and no
     * caller beyond the gate can observe a null key on a write path.
     */
    fun requireKey(dek: ByteArray?): ByteArray = dek ?: throw VaultLockedWriteException()

    /**
     * B2-UI-1 decision for a flush: a DEK present ⇒ the save can be persisted
     * now; a zeroized/absent DEK ⇒ the save must be deferred and written
     * encrypted after the next unlock. There is no "write plaintext anyway" state.
     */
    fun persistNow(keyIsPresent: Boolean): Boolean = keyIsPresent
}

/** B2-UI-1 (phase-49): thrown when a field-encrypted write races a vault lock. */
class VaultLockedWriteException :
    IllegalStateException("B2-UI-1: vault locked - refusing to write a plaintext row")