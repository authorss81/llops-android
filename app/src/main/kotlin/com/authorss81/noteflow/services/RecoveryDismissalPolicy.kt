package com.authorss81.noteflow.services

/**
 * Phase-163: the decision table for "Don't show again" on the two data-recovery
 * screens (`CorruptionRecoveryScreen`, `KeystoreKeyLostScreen`).
 *
 * A bare boolean ever suppress would be wrong: it would let a dismissal the user
 * made for ONE recovery event silently hide a DIFFERENT, newer event — and the
 * security baseline refuses to hide real corruption forever (H2 phase-09
 * quarantine + B1-CRYPTO-05 phase-64 key-lost recovery both demand the screen
 * re-present while the underlying condition is live). Every dismissal is
 * therefore keyed to the recovery EVENT's own identity: the quarantine /
 * event timestamp the storage layer stamps when the condition is first
 * detected (see [DatabaseSecurityHelper]).
 *
 * The decision table:
 *  - not blocking                                  =>  nothing to show
 *  - blocking && event timestamp unknown (<= 0)    =>  MUST show (fail closed:
 *      an un-keyable legacy event can never be permanently silenced, because we
 *      cannot prove it is the SAME event the user already handled)
 *  - blocking && event == dismissed timestamp      =>  suppressed (same event)
 *  - blocking && event != dismissed timestamp      =>  MUST show (a new event,
 *      e.g. a fresh quarantine stamp or a re-recorded keystore-lost key)
 *
 * Pure JVM (no Android imports) so the decision table is unit-tested directly in
 * `app/src/test` and the Compose/ViewModel wiring stays a thin call through it.
 */
object RecoveryDismissalPolicy {

    /** True when the recovery screen must still be presented for this event. */
    fun mayShow(
        blocking: Boolean,
        eventTimestamp: Long,
        dismissedTimestamp: Long,
    ): Boolean {
        if (!blocking) return false
        if (eventTimestamp <= 0L) return true
        return dismissedTimestamp != eventTimestamp
    }

    /** A dismissal may only be persisted when the event is positively keyable. */
    fun isDismissible(blocking: Boolean, eventTimestamp: Long): Boolean =
        blocking && eventTimestamp > 0L
}