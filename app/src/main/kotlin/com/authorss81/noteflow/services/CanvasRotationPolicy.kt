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
}
