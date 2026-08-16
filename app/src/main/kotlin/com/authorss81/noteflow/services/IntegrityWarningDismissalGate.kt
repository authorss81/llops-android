package com.authorss81.noteflow.services

/**
 * B1-DB-6 (phase-87): per-session dismissal gate for the database-integrity
 * tripwire banner.
 *
 * Pre-fix, the banner's "Don't show again" checkbox permanently flipped
 * `settings.databaseIntegrityCheckEnabled = false` and even a plain OK stamped
 * `settings.databaseIntegrityWarningDismissed = true`, so a single tap could
 * permanently knock out the vault's only tamper-evidence tripwire — the
 * "the app walks the user into reducing protection" downgrade in the finding.
 * This gate scopes the dismissal to the CURRENT session: the ViewModel holds
 * ONE instance for the process lifetime, a fresh launch starts a new
 * undismissed session (the tripwire re-arms at every startup), and the
 * persisted check-enable state is never touched from here.
 *
 * Pure JVM (a plain volatile latch) so the lifecycle is unit-testable.
 */
class IntegrityWarningDismissalGate {

    @Volatile
    private var sessionDismissed = false

    /** The banner may show in the current session. */
    fun mayShow(): Boolean = !sessionDismissed

    /**
     * Carries the user's banner decision. `dontShowAgain` ONLY means "not again
     * in THIS session" — it never alters the persisted check-enable state. A
     * plain OK (`dontShowAgain = false`) suppresses nothing: a later in-session
     * re-verification may surface the banner again.
     */
    fun onDismiss(dontShowAgain: Boolean) {
        if (dontShowAgain) sessionDismissed = true
    }

    /** Re-enabling the integrity check clears any in-session dismissal. */
    fun onReenable() {
        sessionDismissed = false
    }
}