package com.authorss81.noteflow.services

/**
 * B1-AUTH-02 (phase-47): the single source of truth for whether a database open
 * may proceed WITHOUT key material in memory.
 *
 * `NoteflowSqlcipherFactory.create` is the funnel every fresh SQLCipher open
 * passes through. Pre-fix it re-materialized a persisted DEK whenever
 * `VaultKeyHolder.dek == null`, so ANY open while the vault was locked
 * independently resurrected key material with no credential (combined with the
 * pre-phase-45 non-auth device copy, that was the B1-CRYPTO-02 bypass). The
 * invariant this class enforces:
 *
 *  - DEK present in memory     -> the vault was explicitly unlocked/opened this
 *    session: the open is legitimate.
 *  - DEK absent + master password set -> this is a LOCKED open. It MUST fail
 *    closed (the factory throws / the view model routes to the lock/recovery
 *    state) and MUST NEVER touch [SecurityService.getOrCreateDek] or any
 *    persisted wrapper.
 *  - DEK absent + no master password -> a passwordless vault: the device-wrapped
 *    copy IS the boot credential by design, so re-reading it is legitimate.
 *
 * Pure JVM (no Android imports) so the decision table is unit-testable in
 * `app/src/test`. Wired in `data/db/NoteflowDatabase.kt`.
 */
internal object LockedOpenGuard {
    fun isOpenAllowed(dekInMemory: Boolean, hasMasterPassword: Boolean): Boolean {
        if (dekInMemory) return true
        return !hasMasterPassword
    }
}