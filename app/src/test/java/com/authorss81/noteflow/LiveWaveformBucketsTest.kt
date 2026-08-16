package com.authorss81.noteflow

import com.authorss81.noteflow.services.LiveWaveformBuckets
import com.authorss81.noteflow.services.WaveformPeakMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 79 (B2-DOS-03): the live waveform accumulator must keep appends O(1)
 * amortized and its emitted view bounded, replacing the pre-fix
 * `_waveformAmplitudes.value + amp` full-list copy-on-write per 100 ms tick
 * (a full copy that grew with the session — ~648M element copies after 1 h on
 * the main thread).
 *
 * The pure-JVM behavioral half of the finding: every append lands in O(1)
 * amortized work inside a preallocated FloatArray, and `snapshot()` never
 * returns more than `maxBuckets` entries regardless of how many samples were
 * fed — so the StateFlow emission, the DB column and the render path all stay
 * bounded for a recording of ANY length.
 */
class LiveWaveformBucketsTest {

    private val recordingBudget = WaveformPeakMath.recordingLiveBuckets

    @Test
    fun `an empty accumulator yields an empty snapshot`() {
        val buckets = LiveWaveformBuckets(recordingBudget)
        assertEquals(0, buckets.size)
        assertTrue(buckets.snapshot().isEmpty())
    }

    @Test
    fun `a single sample is emitted exactly`() {
        val buckets = LiveWaveformBuckets(recordingBudget)
        buckets.append(0.42f)
        assertEquals(1, buckets.size)
        assertEquals(1, buckets.snapshot().size)
        assertEquals(0.42f, buckets.snapshot()[0], 1e-4f)
    }

    @Test
    fun `snapshot is never empty once samples arrive`() {
        val buckets = LiveWaveformBuckets(recordingBudget)
        buckets.append(0.1f)
        buckets.append(0.2f)
        assertTrue(buckets.snapshot().isNotEmpty())
    }

    @Test
    fun `the emitted view never exceeds maxBuckets no matter how many samples`() {
        val buckets = LiveWaveformBuckets(recordingBudget)
        val snapshotSizes = mutableListOf<Int>()
        // 200 000 samples == every 100 ms for ~5.5 h of recording (beyond ANY
        // real cap) fed through the same accumulator a long session would use.
        repeat(200_000) { i ->
            buckets.append((i % 100) / 100f)
            if (i % 1_000 == 999) {
                val snap = buckets.snapshot()
                assertTrue(
                    "snapshot grew to ${snap.size} entries beyond the $recordingBudget budget",
                    snap.size <= recordingBudget
                )
                snapshotSizes.add(snap.size)
            }
        }
        assertTrue(
            "the emission must have actually become a bounded downsampled view, not stayed tiny: $snapshotSizes",
            snapshotSizes.any { it > recordingBudget / 2 }
        )
    }

    @Test
    fun `size never exceeds the budget either`() {
        val buckets = LiveWaveformBuckets(recordingBudget)
        repeat(50_000) { buckets.append(0.5f) }
        assertTrue(buckets.size <= recordingBudget)
        assertEquals(buckets.size, buckets.snapshot().size)
    }

    @Test
    fun `constant input collapses to a near-constant bounded view`() {
        val buckets = LiveWaveformBuckets(16)
        repeat(1_000) { buckets.append(0.75f) }
        val snap = buckets.snapshot()
        assertTrue(snap.size <= 16)
        assertTrue(snap.isNotEmpty())
        snap.forEach { assertEquals("bucket drifted from the constant input", 0.75f, it, 1e-3f) }
    }

    @Test
    fun `the bounded view still represents the whole session -- its mean tracks the input mean`() {
        // Folding averages adjacent buckets, so the emitted view must preserve
        // the overall signal (nothing dropped, no drift). With 40 000 random
        // samples the snapshot mean must match the input mean.
        val buckets = LiveWaveformBuckets(recordingBudget)
        var sum = 0f
        val random = java.util.Random(42)
        repeat(40_000) {
            val v = random.nextFloat()
            buckets.append(v)
            sum += v
        }
        val snap = buckets.snapshot()
        assertTrue(snap.size <= recordingBudget)
        assertTrue(snap.isNotEmpty())
        val snapMean = snap.average().toFloat()
        val inputMean = sum / 40_000f
        assertEquals("folding must not bias the waveform", inputMean, snapMean, 0.02f)
    }

    @Test
    fun `a longest-possible 30 minute recording fits the budget in-linear work`() {
        // B2-DOS-03 ceiling: 30 min at 10 samples/s = 18 000 samples. Feeding all
        // of them must complete quickly (an O(n) copy per append would make this
        // quadratic and hopelessly slow) and still emit a bounded view.
        val buckets = LiveWaveformBuckets(recordingBudget)
        val start = System.nanoTime()
        repeat(18_000) { buckets.append(0.5f) }
        val elapsedUs = (System.nanoTime() - start) / 1_000
        assertTrue(
            "18 000 appends should be milliseconds of work, took ${elapsedUs}us",
            elapsedUs < 2_000_000
        )
        assertTrue(buckets.snapshot().size <= recordingBudget)
    }

    @Test
    fun `degenerate one-bucket budget still works`() {
        val buckets = LiveWaveformBuckets(1)
        repeat(1_000) { buckets.append(0.5f) }
        assertEquals(1, buckets.size)
        assertEquals(1, buckets.snapshot().size)
        assertEquals(0.5f, buckets.snapshot()[0], 1e-3f)
    }
}