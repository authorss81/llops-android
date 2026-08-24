package com.authorss81.noteflow.services

/**
 * Phase 197 (PERF 1.2): per-brush / per-input stroke-stabilizer tuning.
 *
 * The pre-197 stabilizer ran every brush and every input source with the same
 * fixed tuning (`DEFAULT_WINDOW_SIZE=8`, `DEFAULT_PREDICTION=0.15f`). This
 * policy is the pure-JVM decision table that turns the three tuning inputs —
 * the active [BrushPreset]'s `smoothing` fraction, the user's 0–100% strength
 * slider, and the pointer's input source (finger vs stylus) — into the single
 * EWMA window size handed to `StrokeStabilizer.create`/`StabilizerFilter.retune`.
 *
 * Model (documented so tests and UI stay honest):
 *
 *  1. **Brush baseline** — a preset's `smoothing` in `[0..1]` maps linearly to
 *     a window in `[MIN_WINDOW_SIZE..MAX_WINDOW_SIZE]` (2 + round(s*10)).
 *     No active preset falls back to [DEFAULT_SMOOTHING] = 0.6 → window 8,
 *     which is EXACTLY the pre-197 default, so a stylus + no-preset + 100%
 *     session is byte-identical to the old behavior (legacy parity).
 *  2. **User trim** — the slider is a RELATIVE scale over the brush baseline:
 *     0% always means window 2 (effectively raw input), 100% means "the
 *     smoothing this brush was designed with". Intermediate values interpolate
 *     the delta above the minimum. This keeps per-brush character while still
 *     giving the user one global dial.
 *  3. **Input source** — finger pointers get [FINGER_EXTRA_WINDOW] extra
 *     smoothing (fat-finger jitter is worse than stylus jitter); stylus is the
 *     design baseline. Unknown/other tool types are treated like finger
 *     (fail toward MORE smoothing, never less).
 */
object StrokeSmoothingPolicy {

    /** Smallest EWMA window (raw-feeling, minimal lag). */
    const val MIN_WINDOW_SIZE = 2

    /** Largest EWMA window (very smooth, visibly damped). */
    const val MAX_WINDOW_SIZE = 12

    /**
     * Smoothing fraction used when NO brush preset is active. Maps to window 8,
     * the pre-197 `StrokeStabilizer.DEFAULT_WINDOW_SIZE`, so legacy behavior is
     * preserved bit-for-bit for stylus users at the default slider value.
     */
    const val DEFAULT_SMOOTHING = 0.6f

    /**
     * Slider value meaning "no trim" — each brush uses exactly its designed
     * smoothing. Persisted as an Int pref; sanitized on read AND write.
     */
    const val DEFAULT_SLIDER_PERCENT = 100

    /** Extra EWMA-window points added when the pointer is a finger. */
    const val FINGER_EXTRA_WINDOW = 2

    /** Coerces a preset/imported smoothing fraction into [0..1] (NaN → default). */
    fun sanitizeSmoothing(raw: Float?): Float {
        if (raw == null || raw.isNaN()) return DEFAULT_SMOOTHING
        return raw.coerceIn(0f, 1f)
    }

    /** Deterministic half-up rounding (kotlin.math.round ties-to-even is ambiguous). */
    private fun roundHalfUp(value: Float): Int = kotlin.math.floor(value + 0.5f).toInt()

    /** Linear map of a smoothing fraction onto the window range (deterministic). */
    fun windowSizeForSmoothing(smoothing: Float): Int {
        val s = sanitizeSmoothing(smoothing)
        return (MIN_WINDOW_SIZE + roundHalfUp(s * (MAX_WINDOW_SIZE - MIN_WINDOW_SIZE)))
            .coerceIn(MIN_WINDOW_SIZE, MAX_WINDOW_SIZE)
    }

    /** Clamps the persisted slider percent into [0..100]. */
    fun sanitizeSliderPercent(raw: Int): Int = raw.coerceIn(0, 100)

    /**
     * The single tuning output. See the class KDoc for the model.
     *
     * @param presetSmoothing the active preset's smoothing fraction, or null
     *   when no preset is active ([DEFAULT_SMOOTHING] baseline then).
     * @param sliderPercent user strength trim 0–100 ([DEFAULT_SLIDER_PERCENT]
     *   = neutral).
     * @param isStylus true when the pointer tool type reported STYLUS/ERASER;
     *   false (finger or unknown) widens the effective window by
     *   [FINGER_EXTRA_WINDOW]. The input-source adjustment is folded into the
     * *baseline* BEFORE the slider trims it, so 0% always means raw input
     * regardless of input source.
     */
    fun effectiveWindowSize(
        presetSmoothing: Float?,
        sliderPercent: Int,
        isStylus: Boolean
    ): Int {
        val base = windowSizeForSmoothing(presetSmoothing ?: DEFAULT_SMOOTHING)
        val baseline = if (isStylus) base else base + FINGER_EXTRA_WINDOW
        val sliderFrac = sanitizeSliderPercent(sliderPercent) / 100f
        val window = MIN_WINDOW_SIZE + roundHalfUp((baseline - MIN_WINDOW_SIZE) * sliderFrac)
        return window.coerceIn(MIN_WINDOW_SIZE, MAX_WINDOW_SIZE)
    }

    /** Prediction stays the pre-197 constant; only the window adapts. */
    const val PREDICTION = StrokeStabilizer.DEFAULT_PREDICTION
}
