package com.authorss81.noteflow.services

/**
 * Phase 214 (Stroke Smoothing v2): one stabilized output sample.
 *
 * [x]/[y] are the smoothed canvas coordinates; [pressure]/[tilt] carry the
 * low-passed RAW channel values (pressure still in 0..1, tilt in degrees) —
 * the caller remaps pressure AFTER this point (smooth-then-remap contract,
 * `PressureCurveHelper` gamma must never amplify un-smoothed jitter).
 */
data class StabilizedSample(
    val x: Float,
    val y: Float,
    val pressure: Float?,
    val tilt: Float?
)

/**
 * Phase 214: the smoothing model abstraction behind stroke capture.
 *
 * Two implementations exist:
 *  - [StabilizerFilter] — the classic per-axis EWMA + velocity lag
 *    compensation (pre-214 behaviour, now with velocity-adaptive alpha and
 *    optional pressure/tilt channels);
 *  - [OneEuroStreamFilter] — a One-Euro adaptive-cutoff filter
 *    (`minCutoff = 1.2 Hz`, window-derived beta) whose damping eases off as
 *    writing speed rises, trading the EWMA's fixed lag for speed-dependent
 *    responsiveness.
 *
 * Selection is a user preference (`stroke_stabilizer_model_key`, sanitized by
 * [StrokeSmoothingPolicy.sanitizeModelKey]); unknown keys fail safe to EWMA.
 */
interface StrokeStreamFilter {
    fun reset()

    /** Live re-tune between strokes (same contract as StabilizerFilter.retune). */
    fun retune(windowSize: Int, prediction: Float)

    /**
     * Feeds one raw sample; returns the stabilized sample.
     *
     * @param pressure raw pointer pressure (0..1), NOT yet curve-remapped.
     * @param tiltDeg pointer tilt in DEGREES (the persisted unit), or null.
     * @param velocityPxPerMs segment velocity of this sample vs the previous
     *   accepted point (px/ms), or null when no timing pair exists yet.
     * @param timestampMs event uptime millis, or null (unknown clock).
     */
    fun next(
        x: Float,
        y: Float,
        pressure: Float?,
        tiltDeg: Float?,
        velocityPxPerMs: Float?,
        timestampMs: Long?
    ): StabilizedSample
}

/**
 * One-Euro filter (Casiez et al. 2012), adaptive low-pass per channel:
 *
 *   cutoff_i = minCutoff + beta · |dŷ/dt|
 *   alpha_i  = 1 / (1 + tau/te),  tau = 1/(2π·cutoff_i),  te = dt
 *
 * Slow movement → cutoff collapses toward [minCutoffHz] → heavy smoothing;
 * fast movement → beta raises the cutoff → the pen tracks nearly raw.
 * Pressure and tilt ride their OWN derivative-driven channels so light-touch
 * width jitter is damped without flattening deliberate presses.
 *
 * Degenerate inputs (null timestamps, zero/negative dt) fall back to a 60 Hz
 * nominal period; non-finite inputs pass through un-smoothed rather than
 * poisoning state. Pure JVM.
 */
class OneEuroStreamFilter(
    private var minCutoffHz: Float = StrokeSmoothingPolicy.ONE_EURO_MIN_CUTOFF_HZ,
    private var beta: Float = StrokeSmoothingPolicy.oneEuroBetaFor(StrokeStabilizer.DEFAULT_WINDOW_SIZE),
    private val dCutoffHz: Float = StrokeSmoothingPolicy.ONE_EURO_DCUTOFF
) : StrokeStreamFilter {

    private var initialized = false

    // Low-passed channel states.
    private var sx = 0f; private var sy = 0f
    private var sp: Float? = null; private var st: Float? = null
    // Derivative low-pass states (per position axis).
    private var dxPrev = 0f; private var dyPrev = 0f
    private var lastTimestampMs: Long? = null

    override fun reset() {
        initialized = false
        sp = null; st = null
        dxPrev = 0f; dyPrev = 0f
        lastTimestampMs = null
    }

    override fun retune(windowSize: Int, prediction: Float) {
        // Model mapping: the slider/window tuning survives the migration by
        // re-deriving beta from the effective window; minCutoff stays at the
        // documented constant (phase-214 spec).
        this.beta = StrokeSmoothingPolicy.oneEuroBetaFor(windowSize)
    }

    private fun alphaFor(cutoffHz: Float, teSeconds: Float): Float {
        val tau = 1f / (2f * Math.PI.toFloat() * cutoffHz.coerceAtLeast(1e-6f))
        return 1f / (1f + tau / teSeconds.coerceAtLeast(1e-6f))
    }

    override fun next(
        x: Float,
        y: Float,
        pressure: Float?,
        tiltDeg: Float?,
        velocityPxPerMs: Float?,
        timestampMs: Long?
    ): StabilizedSample {
        if (!(x.isFinite() && y.isFinite())) {
            return StabilizedSample(x, y, pressure, tiltDeg)
        }
        val dtSec = if (timestampMs != null && lastTimestampMs != null) {
            ((timestampMs - lastTimestampMs!!).coerceAtLeast(0L)) / 1000f
        } else 0f
        val te = if (dtSec > 0f) dtSec else NOMINAL_TE_SECONDS

        if (!initialized) {
            initialized = true
            sx = x; sy = y
            sp = pressure?.takeIf { it.isFinite() }
            st = tiltDeg?.takeIf { it.isFinite() }
            dxPrev = 0f; dyPrev = 0f
            lastTimestampMs = timestampMs ?: lastTimestampMs
            return StabilizedSample(sx, sy, sp, st)
        }

        // Position axes: derivative of the RAW signal drives the cutoff.
        val dxRaw = if (te > 0f) (x - sx) / te else 0f
        val dyRaw = if (te > 0f) (y - sy) / te else 0f
        val aD = alphaFor(dCutoffHz, te)
        dxPrev = lerp(dxPrev, dxRaw, aD)
        dyPrev = lerp(dyPrev, dyRaw, aD)

        // Prefer the caller-supplied segment velocity (already computed against
        // the accepted stroke geometry); fall back to our own estimate.
        val speedPxPerSec = (velocityPxPerMs?.takeIf { it.isFinite() }?.let { it * 1000f })
            ?: kotlin.math.sqrt(dxPrev * dxPrev + dyPrev * dyPrev)

        val aPos = alphaFor(minCutoffHz + beta * kotlin.math.abs(speedPxPerSec), te)
        sx = lerp(sx, x, aPos)
        sy = lerp(sy, y, aPos)

        // Pressure / tilt channels: same adaptive rule on their own deltas.
        sp = smoothScalarChannel(sp, pressure, te)
        st = smoothScalarChannel(st, tiltDeg, te)

        lastTimestampMs = timestampMs ?: lastTimestampMs
        return StabilizedSample(sx, sy, sp, st)
    }

    private fun smoothScalarChannel(prev: Float?, raw: Float?, te: Float): Float? {
        if (raw == null || !raw.isFinite()) return prev
        val cur = prev ?: return raw
        val speed = kotlin.math.abs(raw - cur) / te
        // Scalar channels use half the positional beta gain: width jitter is
        // narrower-band than path jitter, and over-reactive cutoffs would let
        // digitizer spikes through into visible width steps.
        val a = alphaFor(minCutoffHz + (beta * SCALAR_BETA_GAIN) * speed, te)
        return lerp(cur, raw, a)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + t * (b - a)

    companion object {
        /** Sampling period assumed until two real timestamps are seen. */
        const val NOMINAL_TE_SECONDS = 1f / 60f

        /** Beta gain reduction for the scalar (pressure/tilt) channels. */
        const val SCALAR_BETA_GAIN = 0.5f
    }
}
