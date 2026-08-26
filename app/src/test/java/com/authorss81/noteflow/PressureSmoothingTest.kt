package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.services.PressureCurve
import com.authorss81.noteflow.services.PressureCurveHelper
import com.authorss81.noteflow.services.StabilizerFilter
import com.authorss81.noteflow.services.StrokeSmoothingPolicy
import com.authorss81.noteflow.services.StrokeStabilizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import java.io.File

/**
 * Phase 214 (Stroke Smoothing v2) — Task 2: pressure/tilt low-pass.
 *
 * Pre-214, stylus pressure and tilt passed to storage UN-smoothed while only
 * x/y were EWMA-filtered, so light-touch strokes showed width jitter. Now
 * pressure (+tilt) ride their own narrower-band EWMA channel (window mapped
 * from the main window into the 2..6 band), and the pressure-curve remap runs
 * AFTER smoothing (smooth-then-remap) so gamma curves cannot amplify raw
 * digitizer jitter.
 */
class PressureSmoothingTest {

    /**
     * A 5 Hz pressure sinusoid at 60 Hz sampling with deterministic digitizer
     * noise — the DoD fixture. Amplitude is deliberately small relative to the
     * noise: light stylus contact IS a low-amplitude signal on a noisy channel.
     */
    private fun noisyPressureSinusoid(
        sampleRateHz: Int = 60,
        seconds: Int = 2,
        frequencyHz: Float = 5f,
        midPressure: Float = 0.35f,
        amplitude: Float = 0.10f,
        noiseAmp: Float = 0.20f,
        seed: Long = 7L
    ): Pair<List<Float>, List<Float>> {
        val random = java.util.Random(seed)
        val clean = mutableListOf<Float>()
        val noisy = mutableListOf<Float>()
        val n = sampleRateHz * seconds
        for (i in 0 until n) {
            val t = i.toDouble() / sampleRateHz
            val c = midPressure + amplitude * Math.sin(2.0 * Math.PI * frequencyHz * t)
            clean.add(c.toFloat())
            noisy.add((c + (random.nextDouble() - 0.5) * 2.0 * noiseAmp).toFloat().coerceIn(0f, 1f))
        }
        return clean to noisy
    }

    private fun rms(values: List<Float>): Double =
        sqrt(values.fold(0.0) { acc, v -> acc + v.toDouble() * v } / values.size)

    // ---- 1. The DoD case: RMS −40% on the 5 Hz sinusoid --------------------------

    @Test
    fun `pressure channel cuts RMS deviation by at least 40 percent on a 5Hz sinusoid`() {
        val (clean, noisy) = noisyPressureSinusoid()
        val filter = StabilizerFilter(
            StrokeStabilizer.DEFAULT_WINDOW_SIZE,
            StrokeSmoothingPolicy.PREDICTION
        )
        val smoothed = noisy.map { p ->
            // velocity/timestamp-less feed => static base alpha (pre-214 math).
            filter.next(0f, 0f, p, null, null, null).pressure!!
        }
        val rawError = rms(noisy.zip(clean).map { (a, b) -> a - b })
        val smoothedError = rms(smoothed.zip(clean).map { (a, b) -> a - b })
        assertTrue("fixture must actually be noisy (raw RMS=$rawError)", rawError > 0.05)
        val reduction = 1.0 - smoothedError / rawError
        assertTrue(
            "DoD requires >=40% RMS reduction, got ${(reduction * 100).toInt()}% (raw=$rawError smoothed=$smoothedError)",
            reduction >= 0.40
        )
    }

    @Test
    fun `tilt rides its own channel and damps jitter too`() {
        val random = java.util.Random(11L)
        val filter = StabilizerFilter(
            StrokeStabilizer.DEFAULT_WINDOW_SIZE,
            StrokeSmoothingPolicy.PREDICTION
        )
        val n = 240
        val rawTilt = FloatArray(n) { 30f + ((random.nextDouble() - 0.5) * 12.0).toFloat() }.toList()
        val smoothedTilt = rawTilt.map { t ->
            filter.next(0f, 0f, null, t, null, null).tilt!!
        }
        val meanRaw = rawTilt.sum() / n
        val rawDev = rms(rawTilt.map { it - meanRaw.toFloat() })
        val smDev = rms(smoothedTilt.map { it - meanRaw.toFloat() })
        assertTrue("tilt jitter must shrink ($smDev vs $rawDev)", smDev < rawDev * 0.75)
    }

    // ---- 2. Channel identity: the SMOOTHED value is what gets remapped ------------

    /*
     * Ordering note: the smooth-then-remap contract is pinned STRUCTURALLY (see
     * the wiring pin below — the canvas remaps `s.pressure`, never the raw
     * value). No statistical dominance test is attempted here on purpose: the
     * SMOOTH gamma is compressive at low pressure (d p^g/dp -> 0), so neither
     * order universally dominates in width space; the contract is about WHAT is
     * remapped, decided at capture time, per PressureCurveHelper's documented
     * capture-time semantics.
     */

    @Test
    fun `pressure channel runs its own narrower-band alpha`() {
        // Main window 8 => pressure window 4 => static channel alpha 2/(4+1) = 0.4.
        val filter = StabilizerFilter(
            StrokeStabilizer.DEFAULT_WINDOW_SIZE,
            StrokeSmoothingPolicy.PREDICTION
        )
        filter.next(0f, 0f, 0f, null, null, null) // warm-up passes through
        val stepped = filter.next(0f, 0f, 1f, null, null, null)
        assertEquals("one-step response must be exactly the channel alpha", 0.4f, stepped.pressure!!, 1e-6f)
        // Meanwhile the positional axes still use the MAIN window alpha (2/9),
        // plus the unchanged lag-compensation term.
        val posFilter = StabilizerFilter(
            StrokeStabilizer.DEFAULT_WINDOW_SIZE,
            StrokeSmoothingPolicy.PREDICTION
        )
        posFilter.next(0f, 0f)
        val steppedPos = posFilter.next(100f, 0f, null, null, null, null)
        val mainAlpha = 2f / 9f
        assertEquals(
            100f * mainAlpha * (1f + StrokeSmoothingPolicy.PREDICTION),
            steppedPos.x,
            1e-4f
        )
    }


    /** Mirrors the canvas capture order for one sample; returns the stored point. */
    private fun captureSample(
        filter: StabilizerFilter,
        rawPressure: Float,
        stabilizerEnabled: Boolean,
        prevTimestampMs: Long?,
        timestampMs: Long
    ): PointF {
        if (!stabilizerEnabled) {
            // Disabled path: raw coordinates + remapped RAW pressure (legacy).
            return PointF(0f, 0f, PressureCurveHelper.remapPressure(rawPressure, PressureCurve.SMOOTH))
        }
        val velocity = if (prevTimestampMs != null && timestampMs > prevTimestampMs) {
            0.5f // slow deliberate writing, px/ms
        } else null
        val s = filter.next(0f, 0f, rawPressure, null, velocity, timestampMs)
        // Contract: remap AFTER smoothing.
        return PointF(0f, 0f, PressureCurveHelper.remapPressure(s.pressure!!, PressureCurve.SMOOTH))
    }

    @Test
    fun `disabled stabilizer keeps the legacy raw-pressure path byte-for-byte`() {
        val raw = 0.37f
        val viaHelper = captureSample(StabilizerFilter(), raw, stabilizerEnabled = false, prevTimestampMs = null, timestampMs = 100L)
        assertEquals(PressureCurveHelper.remapPressure(raw, PressureCurve.SMOOTH), viaHelper.pressure!!, 0f)
    }

    @Test
    fun `enabled path stores remapped SMOOTHED pressure`() {
        val filter = StabilizerFilter()
        var prevTs: Long? = null
        // Feed identical raw pressure with real timestamps: the channel must
        // converge toward the remap of that SAME value (no drift, no inversion).
        var last: PointF? = null
        for (i in 0 until 60) {
            val ts = 1_000L + i * 16L
            last = captureSample(filter, 0.4f, true, prevTs, ts)
            prevTs = ts
        }
        assertEquals(
            PressureCurveHelper.remapPressure(0.4f, PressureCurve.SMOOTH),
            last!!.pressure!!,
            1e-3f
        )
    }

    // ---- 3. Dedicated pressure window (2 to 6 band) ----------------------------------

    @Test
    fun `pressure window maps the main window onto the 2 to 6 band`() {
        assertEquals(2, StrokeSmoothingPolicy.PRESSURE_MIN_WINDOW)
        assertEquals(6, StrokeSmoothingPolicy.PRESSURE_MAX_WINDOW)
        assertEquals(2, StrokeSmoothingPolicy.pressureWindowSize(2))
        assertEquals(6, StrokeSmoothingPolicy.pressureWindowSize(12))
        assertEquals(4, StrokeSmoothingPolicy.pressureWindowSize(8))
        // Out-of-range inputs clamp into the band.
        assertEquals(2, StrokeSmoothingPolicy.pressureWindowSize(0))
        assertEquals(2, StrokeSmoothingPolicy.pressureWindowSize(-5))
        assertEquals(6, StrokeSmoothingPolicy.pressureWindowSize(99))
        // Monotone across the main-window range.
        var prev = 0
        for (w in 2..12) {
            val p = StrokeSmoothingPolicy.pressureWindowSize(w)
            assertTrue("pressure window must not decrease as the main window grows", p >= prev)
            prev = p
        }
    }

    // ---- 4. Wiring pin: canvas order -------------------------------------------------

    private fun repoRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            if (File(d, "app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt").isFile) return d
            dir = d.parentFile
        }
        return start
    }

    @Test
    fun `canvas smooths pressure BEFORE the curve remap and feeds raw pressure into the filter`() {
        val src = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt").readText()
        val filterCallIdx = src.indexOf("val s = stabilizerFilter.next(")
        val remapIdx = src.indexOf("PressureCurveHelper.remapPressure(s.pressure ?: rawPressure, pressureCurve)")
        assertTrue("stabilizer call must exist", filterCallIdx >= 0)
        assertTrue("post-smooth remap must exist", remapIdx >= 0)
        assertTrue(
            "the remap of the SMOOTHED pressure must come after the filter call",
            remapIdx > filterCallIdx
        )
        assertTrue(src.contains("pressure = rawPressure,"))
    }
}
