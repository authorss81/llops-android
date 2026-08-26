package com.authorss81.noteflow.services

/**
 * Pure-math stroke stabilizer (Procreate-style "streamline").
 *
 * Implements a per-axis exponentially-weighted moving average with a small
 * lag-compensation term. Each axis (x and y) is filtered independently with its
 * own EWMA state, which smooths touch/stylus jitter while the prediction term
 * keeps the path responsive instead of introducing a mushy constant lag.
 *
 * Phase 214 (Stroke Smoothing v2) additions, all behind the SAME instance:
 *  - velocity-adaptive alpha ([StrokeSmoothingPolicy.adaptiveAlpha]) — slow
 *    strokes damp harder, fast strokes stay responsive;
 *  - pressure/tilt low-pass channels fed BEFORE the pressure-curve remap
 *    (smooth-then-remap contract), so light-touch width jitter is damped;
 *  - selectable smoothing model ([StrokeStabilizer.selectModel]) — classic
 *    EWMA or [OneEuroStreamFilter] behind the [StrokeStreamFilter] interface.
 *
 * Legacy parity: the two-argument [next]/[StrokeStabilizer.next] entry point
 * and [StrokeStabilizer.smooth] keep producing BYTE-IDENTICAL output to
 * pre-214 (no velocity/timestamp data → static base alpha → identical float
 * ops in the same order).
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
class StrokeStabilizer private constructor(
    private var filter: StrokeStreamFilter,
    private var windowSize: Int,
    private var prediction: Float
) {
    fun reset() = filter.reset()
    fun next(x: Float, y: Float): StabilizerPoint {
        val s = filter.next(x, y, null, null, null, null)
        return StabilizerPoint(s.x, s.y)
    }

    /** Phase 214 full-channel sample (canvas capture path). */
    fun next(
        x: Float,
        y: Float,
        pressure: Float?,
        tiltDeg: Float?,
        velocityPxPerMs: Float?,
        timestampMs: Long?
    ): StabilizedSample = filter.next(x, y, pressure, tiltDeg, velocityPxPerMs, timestampMs)

    /**
     * Phase 197: live re-tune between strokes (delegates to the active model).
     * The canvas calls this at stroke start with the per-brush/per-input window
     * from [StrokeSmoothingPolicy].
     */
    fun retune(windowSize: Int, prediction: Float) {
        this.windowSize = windowSize.coerceAtLeast(2)
        this.prediction = prediction
        filter.retune(windowSize, prediction)
    }

    /**
     * Phase 214: switches the smoothing model at stroke start. Unknown keys
     * fail safe to EWMA; switching resets the new model so no state bleeds.
     * Re-selecting the CURRENT key is a no-op (never drops warm-up state).
     */
    fun selectModel(modelKey: String) {
        val model = StrokeSmoothingPolicy.sanitizeModelKey(modelKey)
        val wantsOneEuro = model == StrokeSmoothingPolicy.MODEL_ONE_EURO
        val hasOneEuro = filter is OneEuroStreamFilter
        if (wantsOneEuro == hasOneEuro) return
        filter = if (wantsOneEuro) {
            OneEuroStreamFilter(
                minCutoffHz = StrokeSmoothingPolicy.ONE_EURO_MIN_CUTOFF_HZ,
                beta = StrokeSmoothingPolicy.oneEuroBetaFor(windowSize)
            )
        } else {
            StabilizerFilter(windowSize, prediction)
        }
        filter.reset()
    }

    val activeModelKey: String
        get() = if (filter is OneEuroStreamFilter) {
            StrokeSmoothingPolicy.MODEL_ONE_EURO
        } else {
            StrokeSmoothingPolicy.MODEL_EWMA
        }

    companion object {
        /** Creates a stabilizer; pass the same instance for one continuous stroke. */
        fun create(windowSize: Int = DEFAULT_WINDOW_SIZE, prediction: Float = DEFAULT_PREDICTION): StrokeStabilizer =
            StrokeStabilizer(StabilizerFilter(windowSize, prediction), windowSize.coerceAtLeast(2), prediction)

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
 *
 * Phase 214: also implements [StrokeStreamFilter], adding velocity-adaptive
 * alpha plus optional pressure/tilt low-pass channels. The channel windows are
 * derived from the main window via [StrokeSmoothingPolicy.pressureWindowSize]
 * (2..6 band); all three alphas follow [StrokeSmoothingPolicy.adaptiveAlpha]
 * when a velocity pair exists, else the static pre-214 base alpha.
 */
class StabilizerFilter(
    private var windowSize: Int = StrokeStabilizer.DEFAULT_WINDOW_SIZE,
    private var prediction: Float = StrokeStabilizer.DEFAULT_PREDICTION
) : StrokeStreamFilter {

    private var initialized = false
    private var smoothedX = 0f
    private var smoothedY = 0f
    private var prevSmX = 0f
    private var prevSmY = 0f

    // Phase 214 scalar channels (pressure / tilt). Null until first fed.
    private var smoothedP: Float? = null
    private var smoothedT: Float? = null

    override fun reset() {
        initialized = false
        smoothedP = null
        smoothedT = null
    }

    /**
     * Phase 197: live re-tune between strokes. The EWMA alpha is derived from
     * [windowSize] on EVERY [next] call, so updating it here takes effect from
     * the next sample without discarding the instance (the canvas calls this at
     * stroke start, right after [reset], with the per-brush/per-input window
     * from [StrokeSmoothingPolicy]).
     */
    override fun retune(windowSize: Int, prediction: Float) {
        this.windowSize = windowSize.coerceAtLeast(2)
        this.prediction = prediction
    }

    /** Legacy two-axis entry point — output stays byte-identical to pre-214. */
    fun next(x: Float, y: Float): StabilizerPoint {
        val s = next(x, y, null, null, null, null)
        return StabilizerPoint(s.x, s.y)
    }

    override fun next(
        x: Float,
        y: Float,
        pressure: Float?,
        tiltDeg: Float?,
        velocityPxPerMs: Float?,
        timestampMs: Long?
    ): StabilizedSample {
        val alpha = StrokeSmoothingPolicy.adaptiveAlpha(windowSize, velocityPxPerMs)
        if (!initialized) {
            smoothedX = x
            smoothedY = y
            prevSmX = x
            prevSmY = y
            smoothedP = pressure
            smoothedT = tiltDeg
            initialized = true
            return StabilizedSample(x, y, pressure, tiltDeg)
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

        // Pressure / tilt channels run through their own narrower-band EWMA
        // (window clamped into the 2..6 band) BEFORE the caller remaps the
        // pressure curve — gamma curves must never amplify un-smoothed jitter.
        val pAlpha = StrokeSmoothingPolicy.adaptiveAlpha(
            StrokeSmoothingPolicy.pressureWindowSize(windowSize),
            velocityPxPerMs
        )
        val outP = smoothScalar(smoothedP, pressure, pAlpha)
        val outT = smoothScalar(smoothedT, tiltDeg, pAlpha)
        smoothedP = outP
        smoothedT = outT

        return StabilizedSample(outX, outY, outP, outT)
    }

    private fun smoothScalar(prev: Float?, raw: Float?, alpha: Float): Float? {
        if (raw == null || !raw.isFinite()) return prev
        val cur = prev ?: return raw
        return cur + alpha * (raw - cur)
    }
}
