package com.authorss81.noteflow.services

/**
 * Phase 223 — canvas rotation matrix math. Pure JVM.
 *
 * The canvas rotate feature applies rotationZ through Compose's graphicsLayer;
 * the ONLY state AnnotationCanvas needs is a rotation in degrees. This policy
 * holds the sanitisation (normalise/clamp range) plus the 2D rotation mapping
 * used by tests to verify a world point transforms the way graphicsLayer's
 * rotationZ (about the transform origin) does. Keeping the math here means the
 * gesture handler and the tests agree on one audited source.
 *
 * Two-finger twist is restricted to a bounded arc (so a stray two-finger drag
 * can't spin the page to a random angle).
 */
object CanvasRotationPolicy {

    /** Soft-guard against a stray or malicious stored rotation value. */
    const val MAX_ABS_DEGREES = 360f

    const val DEFAULT_DEGREES = 0f

    /**
     * Phase 240 dead-zone: a per-event rotation delta below this magnitude is
     * treated as finger/sensor micro-jitter and NEVER applied. `calculateRotation()`
     * returns the angular change between the PREVIOUS and CURRENT event in DEGREES,
     * so a pure pinch (fingers spread radially, angle to centroid ~constant) yields
     * sub-threshold deltas across many frames that previously ACCUMULATED into a
     * slowly-rotating page. A deliberate (reasonably brisk) twist clears the
     * dead-zone within one or two events.
     *
     * Known trade-off (documented, deliberate): because the dead-zone gates each
     * per-event delta in isolation, an extremely slow twist producing <2° per
     * pointer event never accumulates and therefore never rotates the page. It is
     * kept this way on purpose — a time-windowed accumulator for sub-threshold
     * deltas would re-expose the original Bug 1, where random-walk jitter under a
     * stationary two-finger hold drifted the page. The zoom-dominance and
     * pan-dominance gates below are the reliable pinch/pan discriminators; the
     * dead-zone is the deliberate cost of not re-introducing that drift.
     */
    const val ROTATION_DEAD_ZONE_DEGREES = 2f

    /**
     * Phase 240: an event whose per-frame zoom deviates from 1f by more than this
     * is a radial spread/squeeze (a PINCH), not a twist — rotation is suppressed.
     * A true twist keeps the two fingers at roughly constant separation.
     */
    const val ZOOM_DOMINANCE_THRESHOLD = 0.03f

    /**
     * Phase 240: an event whose centroid translates more than this many pixels is
     * a PAN, not a twist — rotation is suppressed. A symmetric twist keeps the
     * centroid (roughly) stationary.
     */
    const val PAN_DOMINANCE_PX = 12f

    /**
     * Sanitise a rotation value: coerce to [-MAX_ABS, +MAX_ABS] (non-finite input
     * falls back to the default). Full turns never snap — a rotation of ±360 is
     * visually identical to upright, so there is no discontinuous mid-gesture
     * jump when a twist crosses the bound (it simply clamps at the edge).
     */
    fun sanitize(degrees: Float): Float {
        if (!degrees.isFinite()) return DEFAULT_DEGREES
        return degrees.coerceIn(-MAX_ABS_DEGREES, MAX_ABS_DEGREES)
    }

    /**
     * Rotate a point about the origin by [degreesDeg] using the standard
     * counter-clockwise (math / y-up) rotation matrix. NOTE: this is a pure math
     * helper used by the unit tests to relate gesture degrees to the applied
     * transform; Compose's graphicsLayer rotationZ lives in y-down screen space,
     * where a positive angle appears clockwise. The SAME degrees value is used in
     * both places, so the tests' y-up check remains a faithful sanity probe.
     */
    fun rotatePoint(x: Float, y: Float, degreesDeg: Float): Pair<Float, Float> {
        val rad = java.lang.Math.toRadians(degreesDeg.toDouble()).toFloat()
        val c = kotlin.math.cos(rad)
        val s = kotlin.math.sin(rad)
        return (x * c - y * s) to (x * s + y * c)
    }

    /** Whether rotation differs meaningfully from upright. */
    fun isRotated(degreesDeg: Float): Boolean = sanitize(degreesDeg) != 0f

    /**
     * Two-finger twist delta → page-rotation delta, applying the bounded arc.
     * [gestureRotationDeltaDeg] is what Compose's calculateRotation() reports for
     * an event (the net angular change between the PREVIOUS and CURRENT event —
     * a per-event delta, not a cumulative gesture angle), so it is accumulated
     * straight into the sanitized total.
     */
    fun accumulate(currentDeg: Float, gestureDeltaDeg: Float): Float =
        sanitize(currentDeg + gestureDeltaDeg)

    /**
     * Phase 240: dead-zone gate for one event's rotation delta.
     * `calculateRotation()` returns degrees; pure-pinch micro-jitter is typically
     * well under [ROTATION_DEAD_ZONE_DEGREES] per event. Returns the original
     * delta when it clears the dead-zone (a real twist), 0f otherwise so a pinch
     * can never drift the page.
     */
    fun gatedRotationDelta(rawDeltaDeg: Float): Float =
        if (rawDeltaDeg.isFinite() && kotlin.math.abs(rawDeltaDeg) >= ROTATION_DEAD_ZONE_DEGREES) {
            rawDeltaDeg
        } else {
            0f
        }

    /**
     * Phase 240: full rotation-intent gate for one 2-finger event.
     *
     * `calculateRotation()` returns the per-event rotation delta in DEGREES; even
     * a pure radial pinch reports a small non-zero value each frame (the two
     * fingers are never exactly equidistant from the centroid between events), so
     * a dead-zone alone is not sufficient — the same event ALSO has to NOT be a
     * dominant zoom or pan. A true twist keeps the fingers at roughly constant
     * separation (zoom ~ 1f) and the centroid ~ stationary (tiny pan). A pinch
     * spreads the fingers (zoom deviates) and a pan translates the centroid.
     *
     * Returns the gated delta when the user is genuinely twisting, 0f otherwise.
     */
    fun intentionalRotationDelta(
        rawDeltaDeg: Float,
        zoomChange: Float,
        panDistancePx: Float
    ): Float {
        val gated = gatedRotationDelta(rawDeltaDeg)
        if (gated == 0f) return 0f
        if (!zoomChange.isFinite() || kotlin.math.abs(zoomChange - 1f) > ZOOM_DOMINANCE_THRESHOLD) return 0f
        if (!panDistancePx.isFinite() || panDistancePx > PAN_DOMINANCE_PX) return 0f
        return gated
    }
}
