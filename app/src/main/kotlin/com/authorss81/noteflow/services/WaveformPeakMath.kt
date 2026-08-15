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

    fun downsample(amplitudes: List<Float>, maxBuckets: Int = uiMaxBars): List<Float> {
        if (amplitudes.isEmpty()) return emptyList()
        if (maxBuckets <= 0) return emptyList()
        if (amplitudes.size <= maxBuckets) return amplitudes.toList()

        val out = ArrayList<Float>(maxBuckets)
        val bucketSize = amplitudes.size.toDouble() / maxBuckets.toDouble()
        for (b in 0 until maxBuckets) {
            val start = (b * bucketSize).toInt()
            val end = kotlin.math.min(((b + 1) * bucketSize).toInt(), amplitudes.size)
            if (end <= start) continue
            var max = amplitudes[start]
            var min = amplitudes[start]
            for (i in start until end) {
                val v = amplitudes[i]
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