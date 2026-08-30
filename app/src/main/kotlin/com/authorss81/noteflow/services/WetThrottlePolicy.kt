package com.authorss81.noteflow.services

/**
 * Phase 249 (Bug 1): decision table for the wet-tool sample throttle, kept
 * pure JVM so it is unit-testable.
 *
 * The pre-249 canvas gate fabricated `lastTime = now() - 16L` wall-clock
 * stamps that were unrelated to the MotionEvent uptime clock, and measured
 * the distance on the STABILIZER-CURBED point, so a fast stroke whose EWMA
 * attenuated delta fell under the 6px floor dropped real ink ("dots far from
 * touch"). This policy consumes the REAL sample timeline — the
 * [MotionEvent.eventTime] uptime clock threaded through the passive
 * `pointerInteropFilter` bridge into [RawInputSample.timestampMs] and the
 * drag drain — and measures the RAW digitizer delta (pre-smoothing,
 * pre-clamping), keeping only a sub-pixel floor that swallows motionless
 * jitter without dropping ink.
 */
object WetThrottlePolicy {

    /**
     * Raw-digitizer distance floor (world px). A sample moved at least this
     * far is always accepted — the pre-249 6f floor (measured over smoothed
     * samples) dropped fast, small moves. 1.5px still swallows sub-pixel
     * digitizer jitter without losing real ink.
     */
    const val MIN_PX_FOR_WET_SAMPLE = 1.5f

    /**
     * Time floor on the MotionEvent uptime clock (ms): a sample arriving this
     * long after the last ACCEPTED sample is accepted regardless of distance,
     * so a stalled/paused pen still deposits a stamp.
     */
    const val MAX_MS_PER_WET_SAMPLE = 16L

    /**
     * Whether a raw wet-tool sample must be processed.
     *
     * - [lastRawX]/[lastRawY] — position of the previous ACCEPTED raw sample
     *   (world space, PRE-smoothing / pre-clamping)
     * - [lastSampleTimeMs] — its MotionEvent uptime timestamp
     * - [rawX]/[rawY] — the candidate raw sample position (same raw space)
     * - [sampleTimeMs] — the candidate's MotionEvent uptime timestamp
     *
     * Missing references (e.g. the first sample of a stroke) are FAIL-OPEN:
     * the sample is processed — raw ink is never dropped because a reference
     * is absent. The caller must NEVER feed smoothed (`drawPoint`) coordinates
     * to this gate; only pre-smoothing digitizer positions.
     */
    fun shouldProcess(
        lastRawX: Float?,
        lastRawY: Float?,
        lastSampleTimeMs: Long?,
        rawX: Float,
        rawY: Float,
        sampleTimeMs: Long?
    ): Boolean {
        if (lastRawX == null || lastRawY == null || lastSampleTimeMs == null || sampleTimeMs == null) {
            return true
        }
        val dx = rawX - lastRawX
        val dy = rawY - lastRawY
        if (dx * dx + dy * dy >= MIN_PX_FOR_WET_SAMPLE * MIN_PX_FOR_WET_SAMPLE) return true
        return sampleTimeMs - lastSampleTimeMs >= MAX_MS_PER_WET_SAMPLE
    }
}