package com.authorss81.noteflow

import com.authorss81.noteflow.services.WaveformPeakMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Phase 37 waveform math (bounding the B2-DOS-03 O(n²)
 * waveform rendering: the UI must only ever draw a fixed bar budget, and peak
 * samples must never be lost to an averaging filter).
 *
 * Phase 152 (R2-b2b5-FEA-06): non-finite samples (NaN/±Infinity) from a crafted
 * stored `waveformJson` are replaced at parse/decimate/render time — they can
 * never propagate into bar geometry (`barHeight = canvasHeight * NaN`).
 */
class WaveformPeakMathTest {

    @Test
    fun `empty input yields empty output`() {
        assertEquals(0, WaveformPeakMath.downsample(emptyList()).size)
    }

    @Test
    fun `downsample is identity below the bucket budget`() {
        val amps = listOf(0.2f, 0.5f, 0.9f)
        assertEquals(amps, WaveformPeakMath.downsample(amps))
    }

    @Test
    fun `downsample output is always bounded by maxBuckets`() {
        val amps = List(10_000) { 0.5f }
        val out = WaveformPeakMath.downsample(amps, 220)
        assertEquals(220, out.size)
    }

    @Test
    fun `loud transient peaks survive min-max decimation`() {
        val amps = List(10_000) { 0.1f }.toMutableList()
        amps[4_999] = 1.0f
        val out = WaveformPeakMath.downsample(amps, 220)
        assertTrue("transient peak was lost to decimation", out.contains(1.0f))
    }

    // ---- R2-b2b5-FEA-06 (phase-152): non-finite sample gate ----

    @Test
    fun `finiteOrZero maps NaN and infinities to zero and keeps finite values`() {
        assertEquals(0f, WaveformPeakMath.finiteOrZero(Float.NaN), 0f)
        assertEquals(0f, WaveformPeakMath.finiteOrZero(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, WaveformPeakMath.finiteOrZero(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(0.5f, WaveformPeakMath.finiteOrZero(0.5f), 1e-6f)
        assertEquals(-0.25f, WaveformPeakMath.finiteOrZero(-0.25f), 1e-6f)
    }

    @Test
    fun `downsample sanitizes NaN in the identity pass`() {
        val out = WaveformPeakMath.downsample(listOf(Float.NaN, 0.5f, Float.NaN))
        assertEquals(3, out.size)
        assertTrue("NaN must not survive the identity pass", out.all { it.isFinite() })
        assertEquals(listOf(0f, 0.5f, 0f), out)
    }

    @Test
    fun `downsample sanitizes NaN and infinities during min max decimation`() {
        val amps = List(10_000) { 0.5f }.toMutableList()
        amps[0] = Float.NaN
        amps[1] = Float.POSITIVE_INFINITY
        amps[2] = Float.NEGATIVE_INFINITY
        val out = WaveformPeakMath.downsample(amps, 220)
        assertEquals(220, out.size)
        assertTrue(
            "every downsampled sample must be finite",
            out.all { it.isFinite() }
        )
    }

    @Test
    fun `renderAmp clamps and refuses non-finite inputs`() {
        // Per the phase-152 formula `coerceIn(0.1f, 1.0f).takeIf { isFinite } ?: 0.1f`:
        // NaN survives coerceIn → refused → 0.1f; +Inf clamps to 1.0f (finite, a
        // full-height bar — valid); -Inf clamps to 0.1f; everything is finite.
        assertEquals(0.1f, WaveformPeakMath.renderAmp(Float.NaN), 1e-6f)
        assertEquals(1f, WaveformPeakMath.renderAmp(Float.POSITIVE_INFINITY), 1e-6f)
        assertEquals(0.1f, WaveformPeakMath.renderAmp(Float.NEGATIVE_INFINITY), 1e-6f)
        assertEquals(0.1f, WaveformPeakMath.renderAmp(-5f), 1e-6f)
        assertEquals(1f, WaveformPeakMath.renderAmp(7f), 1e-6f)
        assertEquals(0.5f, WaveformPeakMath.renderAmp(0.5f), 1e-6f)
        assertTrue("renderAmp must never return a non-finite value",
            (0..100).map { WaveformPeakMath.renderAmp(it / 17f * 3f - 1f) }.all { it.isFinite() })
    }

    @Test
    fun `scrubTargetMs maps fraction to milliseconds and clamps`() {
        assertEquals(5_000L, WaveformPeakMath.scrubTargetMs(0.5f, 10_000L))
        assertEquals(0L, WaveformPeakMath.scrubTargetMs(-0.4f, 10_000L))
        assertEquals(10_000L, WaveformPeakMath.scrubTargetMs(2f, 10_000L))
        assertEquals(0L, WaveformPeakMath.scrubTargetMs(0.5f, 0L))
    }

    @Test
    fun `progressFraction is clamped between zero and one`() {
        assertEquals(0.5f, WaveformPeakMath.progressFraction(5_000L, 10_000L), 1e-3f)
        assertEquals(1f, WaveformPeakMath.progressFraction(50_000L, 10_000L), 1e-3f)
        assertEquals(0f, WaveformPeakMath.progressFraction(-5L, 10_000L), 1e-3f)
        assertEquals(0f, WaveformPeakMath.progressFraction(5_000L, 0L), 1e-3f)
    }
}