package com.authorss81.noteflow.services

/**
 * R2-b2b1-UI-04 + R2-b2b1-UI-05 (phase-153): the root SnackbarHost is composed
 * OUTSIDE the `LockScreen` conditional (`MainActivity.kt`), so the decision of
 * whether a snackbar message may be queued — and whether it must SURVIVE a
 * lock — is centralized here. Pure JVM, unit-pinned.
 *
 * While the vault is locked (or the pre-unlock LockScreen is up), vault-content-
 * bearing messages (restore/import outcomes, note titles, plugin results) must
 * never wait in the snackbar queue where the next unlock would present them,
 * and must never render over the LockScreen. Only [messageSurvivesLock] notices
 * — the honest voice-discard notice — are held across the lock boundary for
 * replay after unlock (R2-b2b1-UI-05).
 */
object SnackbarLockPolicy {

    /**
     * Fixed, honest notice for a finished recording destroyed by a lock. The
     * fail-closed at-rest behavior (plaintext swept, nothing persisted without
     * the DEK) is B1-DB-3's intent and stays byte-intact; this only makes the
     * discard VISIBLE instead of silent.
     */
    const val VOICE_RECORD_DISCARDED_NOTICE: String =
        "Recording discarded — the vault locked before it could be saved securely."

    /**
     * Upper bound of the root snackbar queue — replaces the 16-slot
     * `extraBufferCapacity` of the SharedFlow this pipeline superseded.
     */
    const val MAX_PENDING: Int = 16

    /**
     * Whether a message may be APPENDED to the root snackbar queue with the
     * vault in its current auth state.
     * Unlocked: everything is buffered (normal flow).
     * Locked: only [messageSurvivesLock] notices are buffered — every other
     * message is DROPPED at the boundary, so it can never render over the
     * LockScreen nor surface stale after unlock.
     */
    fun mayBufferWhileLocked(isAuthenticated: Boolean, text: String): Boolean =
        isAuthenticated || messageSurvivesLock(text)

    /** Whether [text] is a notice that must survive the lock boundary. */
    fun messageSurvivesLock(text: String): Boolean = text == VOICE_RECORD_DISCARDED_NOTICE
}