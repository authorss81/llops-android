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

    // ---- Phase 214 (Stroke Smoothing v2): velocity-adaptive alpha --------------

    /**
     * Adaptive-alpha floor: slow strokes get at least this much damping
     * (slow writing exposes hand jitter that a static window rides through).
     */
    const val ALPHA_SLOW = 0.12f

    /**
     * Adaptive-alpha ceiling for fast strokes (responsive; a flick must not
     * smear). Chosen so the legacy default window 8 spans exactly this band.
     */
    const val ALPHA_FAST = 0.55f

    /** Velocity (px/ms) at which the alpha reaches [ALPHA_FAST]. */
    const val ADAPT_VELOCITY_FULL_PX_PER_MS = 6f

    /**
     * How far below/above the tuned base alpha (`2/(w+1)`) adaptation may
     * stray, as multiplicative bounds. With the default window 8
     * (base ≈ 0.222) the band is [0.111 .. 0.556], which CONTAINS the full
     * spec range [0.12 .. 0.55] — i.e. default-tuned sessions get the exact
     * documented behaviour. Extreme windows stay near their brush character:
     * window 2 never damps below ⅓ of raw responsiveness, window 12 never
     * jumps above ~2× its designed smoothness.
     */
    const val ALPHA_WINDOW_SLOW_GAIN = 0.5f
    const val ALPHA_WINDOW_FAST_GAIN = 2.5f

    /**
     * Velocity-adaptive EWMA alpha for one sample.
     *
     * Model: `t = clamp(v / 6, 0..1)`; target lerps [ALPHA_SLOW]..[ALPHA_FAST];
     * the result is clamped into `[max(0.12, base·0.5) .. min(0.55, base·2.5)]`
     * where `base = 2/(effectiveWindowSize+1)` — so slow→stable, fast→responsive,
     * and the user's per-brush/per-input tuning still bounds the response.
     *
     * A null/NaN velocity (no timestamp pair yet) falls back to the STATIC
     * base alpha — byte-identical to the pre-214 filter.
     */
    fun adaptiveAlpha(effectiveWindowSize: Int, velocityPxPerMs: Float?): Float {
        val w = effectiveWindowSize.coerceAtLeast(MIN_WINDOW_SIZE)
        val base = 2f / (w + 1f)
        if (velocityPxPerMs == null || velocityPxPerMs.isNaN()) return base
        val t = (velocityPxPerMs / ADAPT_VELOCITY_FULL_PX_PER_MS).coerceIn(0f, 1f)
        val target = ALPHA_SLOW + (ALPHA_FAST - ALPHA_SLOW) * t
        val loRaw = maxOf(ALPHA_SLOW, base * ALPHA_WINDOW_SLOW_GAIN)
        val hiRaw = minOf(ALPHA_FAST, base * ALPHA_WINDOW_FAST_GAIN)
        return target.coerceIn(minOf(loRaw, hiRaw), maxOf(loRaw, hiRaw))
    }

    // ---- Phase 214: pressure/tilt low-pass channel -----------------------------

    /** Dedicated pressure/tilt EWMA window bounds (light-touch width jitter). */
    const val PRESSURE_MIN_WINDOW = 2
    const val PRESSURE_MAX_WINDOW = 6

    /**
     * Pressure/tilt channel window, derived from the main x/y window so one
     * slider tunes everything: maps the main window range linearly onto
     * `PRESSURE_MIN_WINDOW..PRESSURE_MAX_WINDOW`. Pressure noise
     * is narrower-band than positional jitter, so its window saturates lower;
     * out-of-range inputs clamp to the band floor.
     */
    fun pressureWindowSize(effectiveWindowSize: Int): Int {
        val w = effectiveWindowSize.coerceIn(MIN_WINDOW_SIZE, MAX_WINDOW_SIZE)
        val span = MAX_WINDOW_SIZE - MIN_WINDOW_SIZE
        if (span <= 0) return PRESSURE_MIN_WINDOW
        val mapped = PRESSURE_MIN_WINDOW +
            roundHalfUp((w - MIN_WINDOW_SIZE) / span.toFloat() * (PRESSURE_MAX_WINDOW - PRESSURE_MIN_WINDOW))
        return mapped.coerceIn(PRESSURE_MIN_WINDOW, PRESSURE_MAX_WINDOW)
    }

    // ---- Phase 214: prediction ("tension") percent ------------------------------

    /** Slider/pref value meaning "the legacy 0.15 lag compensation". */
    const val DEFAULT_PREDICTION_PERCENT = 15

    /** Upper bound of the tension dial (0.35 — above this corners overshoot). */
    const val MAX_PREDICTION_PERCENT = 35

    fun sanitizePredictionPercent(raw: Int): Int = raw.coerceIn(0, MAX_PREDICTION_PERCENT)

    /** Maps the persisted 0..35 dial onto the retune prediction fraction. */
    fun predictionFromPercent(percent: Int): Float =
        sanitizePredictionPercent(percent) / 100f

    // ---- Phase 214: stabilizer model selection -----------------------------------

    /** Classic per-axis EWMA + velocity lag compensation (pre-214 model). */
    const val MODEL_EWMA = "ewma"

    /** One-Euro adaptive cutoff (speed-dependent smoothing, low lag at speed). */
    const val MODEL_ONE_EURO = "one_euro"

    private val KNOWN_MODELS = setOf(MODEL_EWMA, MODEL_ONE_EURO)

    /** Unknown/hand-edited keys fail safe to the classic EWMA model. */
    fun sanitizeModelKey(raw: String?): String =
        if (raw != null && raw in KNOWN_MODELS) raw else MODEL_EWMA

    /**
     * One-Euro speed coefficient for a configured main window: strongly
     * smoothed presets compensate MORE at high speed (higher beta), so their
     * low-speed stability does not cost lag while writing fast.
     */
    const val ONE_EURO_MIN_CUTOFF_HZ = 1.2f
    const val ONE_EURO_DCUTOFF = 1.0f
    const val ONE_EURO_BETA_AT_MIN_WINDOW = 0.004f
    const val ONE_EURO_BETA_AT_MAX_WINDOW = 0.03f

    fun oneEuroBetaFor(effectiveWindowSize: Int): Float {
        val w = effectiveWindowSize.coerceIn(MIN_WINDOW_SIZE, MAX_WINDOW_SIZE)
        val t = (w - MIN_WINDOW_SIZE) / (MAX_WINDOW_SIZE - MIN_WINDOW_SIZE).toFloat()
        return ONE_EURO_BETA_AT_MIN_WINDOW +
            t * (ONE_EURO_BETA_AT_MAX_WINDOW - ONE_EURO_BETA_AT_MIN_WINDOW)
    }
}
