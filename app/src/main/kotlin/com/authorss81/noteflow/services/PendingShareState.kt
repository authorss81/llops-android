package com.authorss81.noteflow.services

import com.authorss81.noteflow.plugins.SharedClip

/**
 * R2-B1P-05 (phase-140) + Phase 158 (deferred ROADMAP 22.5) — the share-flow
 * states, hoisted OFF the activity so they survive rotation and are cleared at
 * the lock boundary, plus the phase-158 capture-policy extensions.
 *
 * Before phase-140 the states were activity `mutableStateOf` fields
 * (`MainActivity`): since MainActivity is `singleTask` with no `configChanges`,
 * a rotation recreates the activity with the ORIGINAL SEND intent, `onCreate`
 * re-parses it and RE-PROMPTS the "Clip into InkFlow?" confirm (dropping an
 * in-flight confirm), and the confirm `AlertDialog` was composed OUTSIDE the
 * lock branch — so it floated above `LockScreen` after a screen-off lock, with
 * the confirmed "Clip" auto-applying at the next unlock with no expiry.
 *
 * The phase-140 fix hoisted both states into the ViewModel (survives rotation),
 * gated the dialog under `authenticated`, and dropped both at the lock boundary.
 *
 * Phase 158 (this file) adds the honest per-session policy on top:
 *  - a "capture as NEW NOTE vs APPEND TO CURRENT" choice flows through the
 *    confirm into the deferred clip ([ShareCaptureMode]);
 *  - the UN-confirmed hold expires after [CONFIRM_HOLD_EXPIRY_MS] (this session
 *    dimensioned in wall-clock; a stale confirm left on screen is dropped, and
 *    it is cleared at lock regardless — see [R2-B1P-05 clearOnLock]);
 *  - the confirmed/clip apply decision is a pure function
 *    ([resolveAppendTarget]) so "append needs a text-only clip and an active
 *    note, otherwise it degrades honestly to a new note" is unit-pinned;
 *  - a NON-SECRET persisted "captured" marker ([capturedMarkerKey], a flag plus
 *    milliseconds-since-epoch stamp — NEVER the clip content) lets a later
 *    session know "you had a pending capture" without ever persisting plaintext
 *    clipped content on disk. The clip itself is never persisted; it either
 *    applies (bytes move) or is dropped, so nothing secret survives a process kill.
 */

/** The user's "how should this clipped content land?" choice at confirm time. */
enum class ShareCaptureMode {
    /** Clip becomes a brand-new note (the pre-22.5 behavior). */
    NEW_NOTE,

    /** Clip is appended to the currently-open markdown note. */
    APPEND_TO_ACTIVE;

    companion object {
        /** Parse a persisted/argument token back to a mode (fails closed to NEW_NOTE). */
        fun fromToken(token: String?): ShareCaptureMode =
            entries.firstOrNull { it.name == token } ?: NEW_NOTE
    }
}

/** A clip the user has NOT yet confirmed — held from any byte copy. */
data class PendingShareConfirmState(
    val clip: SharedClip,
    val uriStrings: List<String>,
    val stagedAtMs: Long = 0L
)

/** A clip the user HAS confirmed — staged for the post-unlock bounded copy. */
data class PendingShareState(
    val text: String?,
    val imagePaths: List<String>,
    val rawUris: List<String>,
    val captureMode: ShareCaptureMode = ShareCaptureMode.NEW_NOTE
)

/**
 * Pure-JVM decision helpers for the share flow. The ViewModel owns the actual
 * `StateFlow` state; these functions make every transition testable without
 * an Android `ViewModel` instance.
 */
object PendingSharePolicy {

    /** How long an un-confirmed hold may wait before it expires (10 min). */
    const val CONFIRM_HOLD_EXPIRY_MS = 10 * 60 * 1000L

    /** Shared-preferences keys used ONLY for the non-secret captured marker. */
    const val CAPTURED_MARKER_KEY = "captured_share_pending"
    const val CAPTURED_MARKER_AT_MS_KEY = "captured_share_pending_at_ms"

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
    fun toPendingShare(
        request: PendingShareConfirmState,
        captureMode: ShareCaptureMode = ShareCaptureMode.NEW_NOTE
    ): PendingShareState =
        PendingShareState(
            text = request.clip.text,
            imagePaths = emptyList(),
            rawUris = request.uriStrings,
            captureMode = captureMode
        )

    /**
     * R2-B1P-05: the lock boundary DROPS both the un-confirmed confirm and the
     * deferred clip (fail-closed) instead of letting a pre-lock "Clip" auto-apply
     * at the next unlock. Only vaults WITH a lock boundary (has master password)
     * drop the state; passwordless vaults have no lock boundary and keep the
     * deferral working.
     */
    fun clearOnLock(hasMasterPassword: Boolean): Boolean = hasMasterPassword

    /**
     * Per-session expiry for the UN-CONFIRMED hold (phase 158): a confirm the
     * user left on screen stops being offered after [CONFIRM_HOLD_EXPIRY_MS].
     * The CONFIRMED clip is exempt by design — it was explicitly human-approved
     * and applies instantly at the next authenticated frame, so it never lingers
     * "above the lock screen" (both states are cleared on lock for password
     * vaults anyway). This expiry only stops a stale, unnoticed dialog.
     */
    fun isExpired(stagedAtMs: Long, nowMs: Long): Boolean =
        stagedAtMs > 0L && nowMs - stagedAtMs > CONFIRM_HOLD_EXPIRY_MS

    /**
     * Honest "deferred applies now" posture (phase 158): a confirmed clip is
     * copied ONLY on an authenticated frame. There is never a "pending clip
     * waiting inside the app" that could surface above the LockScreen — the
     * state is either consumed on the authenticated frame where it becomes
     * visible, or it is dropped at the lock boundary by [clearOnLock] (no
     * content survives a password-vault lock). Passwordless vaults have no
     * lock boundary, but the apply still waits for `authenticated` (their boot
     * credential is already present).
     */
    fun deferredAppliesNow(authenticated: Boolean): Boolean = authenticated

    /**
     * Phase 158: the clip-apply target decision. Appending to the active note
     * is a TEXT-only operation (placing raster/shared images at a sensible spot
     * inside an arbitrary markdown body is not well-defined); when the clip
     * carries images -- or there is no active note -- the mode degrades honestly
     * to a new-note capture instead of silently misplacing content.
     */
    fun resolveAppendTarget(
        hasActivePage: Boolean,
        clipHasImages: Boolean,
        mode: ShareCaptureMode
    ): AppendResolution = when {
        mode != ShareCaptureMode.APPEND_TO_ACTIVE -> AppendResolution.CREATE_NEW_NOTE
        !hasActivePage || clipHasImages -> AppendResolution.CREATE_NEW_NOTE
        else -> AppendResolution.APPEND_TO_ACTIVE
    }

    /**
     * Non-secret captured marker: the ONLY thing a capture may persist.
     * [markerPayload] is `true` + the wall-clock stamp the clip was held — never
     * any clip content (that would be plaintext at rest, which every encryption
     * finding forbids). A future session reads it as "you had a shared clip
     * pending" (informational) with zero decrypted material involved.
     */
    fun capturedMarkerPayload(stagedAtMs: Long): CapturedMarker =
        CapturedMarker(pending = true, stagedAtMs = stagedAtMs)
}

/** The parsed form of the non-secret captured marker. */
data class CapturedMarker(val pending: Boolean, val stagedAtMs: Long)

/** Phase 158: the honest target of a confirmed clip apply. */
enum class AppendResolution {
    CREATE_NEW_NOTE,
    APPEND_TO_ACTIVE
}