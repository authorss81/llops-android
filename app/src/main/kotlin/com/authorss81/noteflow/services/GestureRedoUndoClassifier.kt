package com.authorss81.noteflow.services

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Phase 155: pure-JVM classifier that decides whether a two-pointer canvas
 * gesture is (a) a two-finger horizontal SWIPE (left = undo, right = redo),
 * (b) the SECOND tap of a two-finger double TAP (undo), or (c) nothing -- most
 * importantly a PINCH-ZOOM or a non-horizontal PAN, which must never be hijacked.
 *
 * The classifier is deliberately cheap ("pointer counts only" per AGENTS.md):
 * it consumes simple 2D coordinates + a timestamp per frame and keeps a handful
 * of scalars. It never inspects pointer tool types or accumulates anything
 * unbounded.
 *
 * **Distinguishing swipe/tap from pinch:** while two pointers are down we track
 * the separation between them relative to their separation at gesture start. A
 * real pinch changes that separation dramatically (fingers spread or close);
 * a parallel two-finger swipe keeps it within [MIN_SEPARATION_RATIO]..
 * [MAX_SEPARATION_RATIO]. When the separation leaves that band at ANY point, the
 * session is flagged as a pinch and no swipe/tap action is ever reported for it
 * -- this is a second layer of defense on top of Compose pointer consumption,
 * and it is exactly what the round-2 finding asked for ("require the second
 * finger's tap/swipe to be distinct from an active pinch").
 *
 * The caller feeds one observation per pointer frame via [onFrame]. The state
 * machine starts a session when the pointer count rises to >= 2, keeps updating
 * while >= 2, and CLOSES (returning the decided action) when the count drops
 * below 2. Session state is reset by [reset].
 *
 * All UI-facing feel constants live here so the tuning is unit-testable.
 */
class GestureRedoUndoClassifier {

    /** The gesture decision the classifier closes a session with. */
    enum class Action {
        /** No undo/redo action (still drawing, pinched, too slow, one tap, ...). */
        NONE,

        /** Two-finger swipe LEFT, or the second of a two-finger double tap. */
        UNDO,

        /** Two-finger swipe RIGHT. */
        REDO
    }

    // ---- feel / geometry constants ----------------------------------------

    /** Minimum x displacement the centroid must travel for a swipe to fire. */
    val SWIPE_DISTANCE_PX = 90f

    /** Maximum PERPENDICULAR (vertical) centroid travel allowed in a swipe. */
    val MAX_SWIPE_VERTICAL_PX = 120f

    /**
     * Finger separation at gesture start below which the session is treated as
     * degenerate (two nearly-coincident pointers) and never produces a swipe.
     */
    val MIN_START_SEPARATION_PX = 24f

    /**
     * Separation ratio band. A swipe keeps the two fingers roughly parallel, so
     * currentSeparation / startSeparation must stay within [MIN, MAX]; anything
     * outside is taken as an active pinch (spread or close). 1/1.65 and 1.65 are
     * deliberately generous so a slightly-natural swipe never trips the pinch
     * guard, while a genuine pinch-open (ratio well past 1.65) always does.
     */
    val MIN_SEPARATION_RATIO = 0.6f
    val MAX_SEPARATION_RATIO = 1.65f

    /**
     * Radius of the "resting" zone around the gesture-start centroid. A session
     * whose centroid stays inside this radius for its whole lifetime is a TAP
     * (no swipe travelled). Two taps within [DOUBLE_TAP_INTERVAL_MS] fire UNDO.
     */
    val TAP_STAY_RADIUS_PX = 48f

    /** Two-finger taps closer than this fire one UNDO (double tap). */
    val DOUBLE_TAP_INTERVAL_MS = 350L

    /** Absolute cap on accumulated two-finger centroid travel before cancellation. */
    val MAX_SESSION_TRAVEL_PX = 600f

    // ---- session state -----------------------------------------------------

    private var sessionActive = false
    private var sceneTimeMs = 0L
    private var startTimeMs = 0L

    private var startCx = 0f
    private var startCy = 0f
    private var startSeparation = 0f

    private var lastCx = 0f
    private var lastCy = 0f

    private var minSeparationRatio = 1f
    private var maxSeparationRatio = 1f
    private var pinchDetected = false

    private var lastTapUpTimeMs = -1L

    /** Resets all session state (call between gesture streams / on cancel). */
    fun reset() {
        sessionActive = false
        pinchDetected = false
        startSeparation = 0f
        startCx = 0f
        startCy = 0f
        lastCx = 0f
        lastCy = 0f
        minSeparationRatio = 1f
        maxSeparationRatio = 1f
        lastTapUpTimeMs = -1L
        sceneTimeMs = 0L
        startTimeMs = 0L
    }

    /**
     * Feeds one pointer frame.
     *
     * @param fingerCount number of pressed pointers this frame.
     * @param coords     a flattened [x0, y0, x1, y1] pair for the FIRST TWO
     *                   pressed pointers when [fingerCount] >= 2. Order is not
     *                   meaningful (either finger can land first), which is fine
     *                   because only centroid + separation are used.
     * @param timeMs     frame time (monotonic millis).
     * @return the [Action] decided when this frame ENDS a session, else [Action.NONE].
     */
    fun onFrame(fingerCount: Int, coords: FloatArray?, timeMs: Long): Action {
        sceneTimeMs = timeMs

        if (fingerCount >= 2 && coords != null && coords.size >= 4) {
            val x0 = coords[0]; val y0 = coords[1]
            val x1 = coords[2]; val y1 = coords[3]
            val cx = (x0 + x1) / 2f
            val cy = (y0 + y1) / 2f
            val separation = hypot(x1 - x0, y1 - y0)

            if (!sessionActive) {
                if (separation < MIN_START_SEPARATION_PX) return Action.NONE
                // A brand-new tap coalesces with a previous tap only if it lands
                // within the double-tap window; otherwise forget the old tap so a
                // long-ago tap can never pair with a new one.
                if (lastTapUpTimeMs >= 0L && timeMs - lastTapUpTimeMs > DOUBLE_TAP_INTERVAL_MS) {
                    lastTapUpTimeMs = -1L
                }
                sessionActive = true
                startTimeMs = timeMs
                startCx = cx
                startCy = cy
                lastCx = cx
                lastCy = cy
                startSeparation = separation
                minSeparationRatio = 1f
                maxSeparationRatio = 1f
                pinchDetected = false
                return Action.NONE
            }

            if (startSeparation > 0f) {
                val ratio = separation / startSeparation
                if (ratio < minSeparationRatio) minSeparationRatio = ratio
                if (ratio > maxSeparationRatio) maxSeparationRatio = ratio
                if (ratio < MIN_SEPARATION_RATIO || ratio > MAX_SEPARATION_RATIO) {
                    pinchDetected = true
                }
            }
            lastCx = cx
            lastCy = cy
            return Action.NONE
        }

        // fingerCount < 2 -> the session (if any) just ended.
        if (!sessionActive) return Action.NONE

        sessionActive = false
        if (pinchDetected) {
            // A pinch never counts as a tap for double-tap purposes.
            lastTapUpTimeMs = -1L
            return Action.NONE
        }

        var action = Action.NONE
        val ddx = abs(lastCx - startCx)
        val ddy = abs(lastCy - startCy)
        val travelled = hypot(lastCx - startCx, lastCy - startCy)

        if (travelled <= MAX_SESSION_TRAVEL_PX) {
            if (travelled > TAP_STAY_RADIUS_PX) {
                // A real swipe. A swipe is NOT a tap, so it must never be paired
                // with a previous tap.
                lastTapUpTimeMs = -1L
                if (ddx >= SWIPE_DISTANCE_PX && ddy <= MAX_SWIPE_VERTICAL_PX) {
                    action = if (lastCx < startCx) Action.UNDO else Action.REDO
                }
            } else {
                // A clean two-pointer tap: count it, and a second within the
                // interval fires one UNDO (two-finger double tap).
                val now = sceneTimeMs
                if (lastTapUpTimeMs >= 0L && now - lastTapUpTimeMs <= DOUBLE_TAP_INTERVAL_MS) {
                    action = Action.UNDO
                    lastTapUpTimeMs = -1L
                } else {
                    lastTapUpTimeMs = now
                }
            }
        } else {
            lastTapUpTimeMs = -1L
        }
        return action
    }

    /** True while the classifier is in the middle of a two-finger session. */
    val isSessionActive: Boolean get() = sessionActive

    /** Milliseconds of continuous two-finger contact (for the UI to cancel long holds). */
    fun sessionAgeMs(): Long =
        if (sessionActive) (sceneTimeMs - startTimeMs).coerceAtLeast(0L) else 0L
}