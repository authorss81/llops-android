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
     * Sanitise a rotation value: coerce to [-MAX_ABS, +MAX_ABS] and squeeze
     * anything within a hair of ±360 down to 0 (so spinning full turns reads as
     * upright). Non-finite input falls back to the default.
     */
    fun sanitize(degrees: Float): Float {
        if (!degrees.isFinite()) return DEFAULT_DEGREES
        var d = degrees.coerceIn(-MAX_ABS_DEGREES, MAX_ABS_DEGREES)
        if (d in -0.5f..0.5f || abs(d) >= 359.5f) d = 0f
        return d
    }

    private fun abs(v: Float): Float = if (v < 0f) -v else v

    /**
     * Rotate a point about the origin by [degreesDeg] (counter-clockwise for
     * positive degrees, matching Compose's rotationZ convention). Used purely by
     * the unit tests to confirm the gesture-driven degrees are applied as the
     * same rotation the renderer applies.
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
     * [gestureRotationDeltaDeg] is what Compose's detectTransformGestures reports
     * (it is already the net angular change of the gesture). We simply accumulate;
     * no magnitude clamp here — the caller sums into a sanitized total.
     */
    fun accumulate(currentDeg: Float, gestureDeltaDeg: Float): Float =
        sanitize(currentDeg + gestureDeltaDeg)
}
