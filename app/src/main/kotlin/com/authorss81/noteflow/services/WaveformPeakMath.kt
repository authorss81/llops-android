package com.authorss81.noteflow.services

/**
 * Phase 37 — PURE JVM waveform math for voice-note UI.
 *
 *  - [downsample]: collapses an arbitrary-length amplitude series into a fixed
 *    [maxBuckets]-entry budget using **min/max decimation**, so peaks are never
 *    lost (a moving-average can erase a single loud transient). This is the
 *    render-side half of the B2-DOS-03 fix: the UI only ever draws a bounded
 *    number of bars regardless of recording length.
 *  - [scrubTargetMs]: maps a tap/drag x-fraction on the waveform bar to a seek
 *    position in milliseconds (clamped to the duration).
 *  - [recordingLiveBuckets]: the bounded budget the recorder emits every tick,
 *    so amplitude appends are O(1) and the emitted snapshot is fixed-size.
 */
object WaveformPeakMath {

    /** Downsampled view the recorder publishes while recording (bounded). */
    const val recordingLiveBuckets = 160

    /** Downsampled view the UI draws for a stored waveform (bounded). */
    const val uiMaxBars = 220

    /**
     * R2-b2b5-FEA-06 (phase-152): replaces a non-finite sample (NaN/±Infinity)
     * with 0.0f so a crafted stored `waveformJson` can never propagate a NaN into
     * bar geometry. Applied at parse time ([NoteRepository.parseWaveformJson]) and
     * inside [downsample], where the pre-fix min/max comparisons were all false
     * for NaN, so NaN survived every decimation level untouched.
     */
    fun finiteOrZero(v: Float): Float = v.takeIf { it.isFinite() } ?: 0f

    /**
     * R2-b2b5-FEA-06 (phase-152): render-side amplitude clamp used by
     * `AudioPlaybackCard` — `coerceIn(0.1f, 1.0f)` alone does NOT remove NaN, so
     * `barHeight = canvasHeight * NaN` reached `drawRoundRect`. Returns a finite
     * value in [0.1f, 1.0f] for any input.
     */
    fun renderAmp(amp: Float): Float =
        amp.coerceIn(0.1f, 1.0f).takeIf { it.isFinite() } ?: 0.1f

    fun downsample(amplitudes: List<Float>, maxBuckets: Int = uiMaxBars): List<Float> {
        if (amplitudes.isEmpty()) return emptyList()
        if (maxBuckets <= 0) return emptyList()
        if (amplitudes.size <= maxBuckets) return amplitudes.map(::finiteOrZero)

        val out = ArrayList<Float>(maxBuckets)
        val bucketSize = amplitudes.size.toDouble() / maxBuckets.toDouble()
        for (b in 0 until maxBuckets) {
            val start = (b * bucketSize).toInt()
            val end = kotlin.math.min(((b + 1) * bucketSize).toInt(), amplitudes.size)
            if (end <= start) continue
            var max = finiteOrZero(amplitudes[start])
            var min = finiteOrZero(amplitudes[start])
            for (i in start until end) {
                val v = finiteOrZero(amplitudes[i])
                if (v > max) max = v
                if (v < min) min = v
            }
            out.add(max)
            out.add(min)
        }
        return if (out.size <= maxBuckets) out else out.subList(0, maxBuckets)
    }

    /** Fraction of the bar width (0..1) → seek position in ms, clamped to [durationMs]. */
    fun scrubTargetMs(xFraction: Float, durationMs: Long): Long {
        if (durationMs <= 0L) return 0L
        val ratio = xFraction.coerceIn(0f, 1f)
        return (ratio * durationMs.toFloat()).toLong().coerceIn(0L, durationMs)
    }

    /** Fraction of total duration [positionMs]/[durationMs], clamped to 0..1. */
    fun progressFraction(positionMs: Long, durationMs: Long): Float {
        if (durationMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
}