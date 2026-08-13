package com.authorss81.noteflow.services

/**
 * Pure-math stroke stabilizer (Procreate-style "streamline").
 *
 * Implements a per-axis exponentially-weighted moving average with a small
 * lag-compensation term. Each axis (x and y) is filtered independently with its
 * own EWMA state, which smooths touch/stylus jitter while the prediction term
 * keeps the path responsive instead of introducing a mushy constant lag.
 *
 * The class is intentionally free of Android dependencies so it is directly
 * unit-testable on the JVM.
 */
data class StabilizerPoint(
    val x: Float,
    val y: Float
) {
    companion object {
        val Zero = StabilizerPoint(0f, 0f)
    }
}

/**
 * Stateful stabilizer. Create one per continuous stroke and call [reset] when a
 * new stroke starts so stale window state does not bleed into the next stroke.
 */
class StrokeStabilizer private constructor(private val filter: StabilizerFilter) {
    fun reset() = filter.reset()
    fun next(x: Float, y: Float) = filter.next(x, y)

    companion object {
        /** Creates a stabilizer; pass the same instance for one continuous stroke. */
        fun create(windowSize: Int = DEFAULT_WINDOW_SIZE, prediction: Float = DEFAULT_PREDICTION): StrokeStabilizer =
            StrokeStabilizer(StabilizerFilter(windowSize, prediction))

        const val DEFAULT_WINDOW_SIZE = 8
        const val DEFAULT_PREDICTION = 0.15f

        /**
         * One-shot convenience for callers that do not need persistent state.
         * [values] are the raw input points in draw order; returns the stabilized
         * path (same length).
         */
        fun smooth(values: List<StabilizerPoint>): List<StabilizerPoint> {
            val filter = StabilizerFilter(DEFAULT_WINDOW_SIZE, DEFAULT_PREDICTION)
            return values.map { filter.next(it.x, it.y) }
        }
    }
}

/**
 * EWMA state machine backing [StrokeStabilizer]. Kept public so it can be unit
 * tested directly; callers normally use [StrokeStabilizer.create].
 */
class StabilizerFilter(
    private val windowSize: Int = StrokeStabilizer.DEFAULT_WINDOW_SIZE,
    private val prediction: Float = StrokeStabilizer.DEFAULT_PREDICTION
) {
    private var initialized = false
    private var smoothedX = 0f
    private var smoothedY = 0f
    private var prevSmX = 0f
    private var prevSmY = 0f

    fun reset() {
        initialized = false
    }

    fun next(x: Float, y: Float): StabilizerPoint {
        val alpha = 2f / (windowSize.coerceAtLeast(2) + 1f).toFloat()
        if (!initialized) {
            smoothedX = x
            smoothedY = y
            prevSmX = x
            prevSmY = y
            initialized = true
            return StabilizerPoint(x, y)
        }

        // Per-axis EWMA: independent state for x and y.
        val newSmX = smoothedX + alpha * (x - smoothedX)
        val newSmY = smoothedY + alpha * (y - smoothedY)

        // Lag compensation: push the output slightly along the smoothed velocity.
        val outX = newSmX + (newSmX - prevSmX) * prediction
        val outY = newSmY + (newSmY - prevSmY) * prediction

        prevSmX = newSmX
        prevSmY = newSmY
        smoothedX = newSmX
        smoothedY = newSmY
        return StabilizerPoint(outX, outY)
    }
}
