package com.authorss81.noteflow.services

/**
 * Phase 79 (B2-DOS-03) — fixed-budget, O(1)-amortized live waveform accumulator.
 *
 * The recorder feeds one normalized amplitude every [VoiceRecordingPolicy]
 * tick. This class turns that unbounded stream into a **bounded** running
 * representation:
 *
 *  - Appends are O(1) amortized: a sample lands in a pending accumulator and
 *    only occasionally seals a bucket into the preallocated [bucketAvg]
 *    `FloatArray`. There is NO `list + element` copy-on-write and NO unbounded
 *    growth — the pre-fix sampler's `_waveformAmplitudes.value = ... + amp`
 *    (a full list copy + boxed-Float allocations every 100 ms on the main
 *    thread, ~648M element copies after an hour) is what this replaces.
 *  - The emitted [snapshot] never exceeds [maxBuckets] entries. When the budget
 *    fills, adjacent buckets are FOLDED (pairwise average, halving the count)
 *    and the per-bucket span doubles, so the view keeps representing the WHOLE
 *    session at a fixed resolution instead of silently truncating the start.
 *
 * The stored / persisted waveform is therefore never larger than [maxBuckets]
 * (≤ 600), which also bounds what [NoteRepository] re-parses on load — the
 * "re-parsed whole" half of the finding.
 */
class LiveWaveformBuckets(
    private val maxBuckets: Int
) {
    private val bucketAvg = FloatArray(maxBuckets)
    private var bucketCount = 0
    private var pendingSum = 0f
    private var pendingCount = 0

    /** How many raw samples each sealed bucket currently represents. */
    private var bucketSize = 1

    init {
        require(maxBuckets > 0) { "maxBuckets must be positive" }
    }

    /** Number of buckets the current snapshot will contain (≤ [maxBuckets]). */
    val size: Int
        get() = minOf(bucketCount + (if (pendingCount > 0) 1 else 0), maxBuckets)

    /** Feed one normalized amplitude sample (0..1). O(1) amortized. */
    fun append(sample: Float) {
        pendingSum += sample
        pendingCount++
        while (pendingCount >= bucketSize) {
            sealBucket()
        }
    }

    /** Bounded view of the session so far (≤ [maxBuckets] entries). */
    fun snapshot(): List<Float> {
        val hasPending = pendingCount > 0
        val total = bucketCount + (if (hasPending) 1 else 0)
        if (total == 0) return emptyList()

        val out = ArrayList<Float>(minOf(total, maxBuckets))
        for (i in 0 until bucketCount) out.add(bucketAvg[i])
        if (hasPending) {
            val pendingAvg = pendingSum / pendingCount
            if (out.size < maxBuckets) {
                out.add(pendingAvg)
            } else {
                // Budget already full with a partial bucket pending — fold the
                // pending average into the last sealed bucket rather than ever
                // exceeding the budget.
                out[out.size - 1] = (out[out.size - 1] + pendingAvg) / 2f
            }
        }
        return out
    }

    private fun sealBucket() {
        val avg = pendingSum / pendingCount
        pendingSum = 0f
        pendingCount = 0
        if (bucketCount == maxBuckets) fold()
        bucketAvg[bucketCount++] = avg
    }

    /** Halve the bucket count (pairwise average) and double the per-bucket span. */
    private fun fold() {
        val newCount = bucketCount / 2
        var i = 0
        while (i < newCount) {
            bucketAvg[i] = (bucketAvg[2 * i] + bucketAvg[2 * i + 1]) / 2f
            i++
        }
        bucketCount = newCount
        bucketSize *= 2
    }
}
