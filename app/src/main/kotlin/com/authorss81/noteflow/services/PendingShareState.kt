package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.SharedClip

/**
 * R2-B1P-05 (phase-140) — the two share-flow states, hoisted OFF the activity so
 * they survive rotation and are cleared at the lock boundary.
 *
 * Before this phase the states were activity `mutableStateOf` fields
 * (`MainActivity`): since MainActivity is `singleTask` with no `configChanges`,
 * a rotation recreates the activity with the ORIGINAL SEND intent, `onCreate`
 * re-parses it and RE-PROMPTS the "Clip into InkFlow?" confirm (dropping an
 * in-flight confirm), and the confirm `AlertDialog` was composed OUTSIDE the
 * lock branch — so it floated above `LockScreen` after a screen-off lock, with
 * the confirmed "Clip" auto-applying at the next unlock with no expiry.
 *
 * The fix hoists both states into the ViewModel (survives rotation), gates the
 * dialog under `authenticated`, and drops both at the lock boundary.
 */

/** A clip the user has NOT yet confirmed — held from any byte copy. */
data class PendingShareConfirmState(
    val clip: SharedClip,
    val uriStrings: List<String>
)

/** A clip the user HAS confirmed — staged for the post-unlock bounded copy. */
data class PendingShareState(
    val text: String?,
    val imagePaths: List<String>,
    val rawUris: List<String>
)

/**
 * Pure-JVM decision helpers for the share flow. The ViewModel owns the actual
 * `StateFlow` state; these functions make every transition testable without
 * an Android `ViewModel` instance.
 */
object PendingSharePolicy {

    /**
     * A fresh incoming share is staged into a confirm ONLY when no share is
     * already in flight — on a rotated-recreated activity the re-parsed SEND
     * intent must not clobber a confirm the user already dismissed or answered.
     */
    fun shouldStage(
        currentConfirm: PendingShareConfirmState?,
        currentPending: PendingShareState?
    ): Boolean = currentConfirm == null && currentPending == null

    /** The confirmed confirm becomes the deferred pending clip: no bytes move. */
    fun toPendingShare(request: PendingShareConfirmState): PendingShareState =
        PendingShareState(
            text = request.clip.text,
            imagePaths = emptyList(),
            rawUris = request.uriStrings
        )

    /**
     * R2-B1P-05: the lock boundary DROPS both the un-confirmed confirm and the
     * deferred clip (fail-closed) instead of letting a pre-lock "Clip" auto-apply
     * at the next unlock. Only vaults WITH a lock boundary (has master password)
     * drop the state; passwordless vaults have no lock boundary and keep the
     * deferral working.
     */
    fun clearOnLock(hasMasterPassword: Boolean): Boolean = hasMasterPassword
}