package com.authorss81.noteflow.services

/**
 * R2-B1A-01 / R2-B1A-02 / R2-b2b1-UI-01 (phase-134): single pure-JVM decision
 * table for "did this DAO failure happen BECAUSE the vault locked mid-flight?"
 *
 * `lock()` zeroizes the DEK and disposes the Room/SQLCipher pool. Any in-flight
 * read or write that was launched while unlocked then fails with one of:
 *  - [VaultLockedWriteException] — the write gate's fail-closed throw, when the
 *    DEK was already gone before the repository reached a field-encrypted write;
 *  - `IllegalStateException("connection pool has been closed")` — when the pool
 *    was disposed between the guard check and the DAO round trip (the canonical
 *    noteflow.sqlite closed-pool throw from Room/SQLCipher).
 *
 * A lock race must NEVER escape to the process-crash handler. Instead the caller
 * degrades (armed empty list / null result + a non-alarming one-time notice). A
 * genuine, unrelated error must NEVER be swallowed as a lock race — everything
 * that does not match the three signals above is RETHROWN, so real DAO/corruption
 * failures still surface loudly.
 *
 * Pure JVM (no Android/Room references) so the decision itself is unit-testable
 * on the CI runner.
 */
object LockedPoolGuard {

    /** Substring Room/SQLCipher throw after [com.authorss81.noteflow.data.NoteflowDatabase].dispose(). */
    const val CLOSED_POOL_MESSAGE_SUBSTRING = "connection pool has been closed"

    /**
     * True when [exception] is a lock-vs-inflight race that must degrade instead
     * of crash. [keyPresent] is `repository.encryptionKey != null`: a zeroized
     * DEK alone means "the vault locked (or is locked)" and the pool is unsafe —
     * fail closed. Mirrors the historical write-side predicate while adding the
     * closed-pool classification on top of [VaultLockedWriteException].
     */
    fun isLockRace(exception: Throwable, keyPresent: Boolean): Boolean {
        if (!keyPresent) return true
        if (exception is VaultLockedWriteException) return true
        return isClosedPool(exception)
    }

    /**
     * Walks [exception] and its causes looking for the closed-pool
     * [IllegalStateException]. A cause-traversal matters because Room may wrap
     * the SQLCipher throw inside an `android.arch`/runtime layer.
     */
    fun isClosedPool(exception: Throwable): Boolean {
        var current: Throwable? = exception
        while (current != null) {
            if (current is IllegalStateException &&
                current.message?.contains(CLOSED_POOL_MESSAGE_SUBSTRING, ignoreCase = true) == true
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Lift of [isLockRace] for callers that discriminate. Kept so the decision
     * table is inspectable in a single place.
     */
    fun isLockRaceOrRethrow(exception: Throwable, keyPresent: Boolean): LockedPoolDecision =
        if (isLockRace(exception, keyPresent)) LockedPoolDecision.Degrade else LockedPoolDecision.Rethrow
}

/** Outcome of [LockedPoolGuard.isLockRaceOrRethrow]. */
sealed interface LockedPoolDecision {
    /** Lock raced the inflight DAO call — degrade (empty result + notice). */
    data object Degrade : LockedPoolDecision

    /** Unrelated error — must propagate to the caller, never swallowed. */
    data object Rethrow : LockedPoolDecision
}