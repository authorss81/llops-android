package com.authorss81.noteflow

import com.authorss81.noteflow.services.WaveformPeakMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the Phase 37 waveform math (bounding the B2-DOS-03 O(n²)
 * waveform rendering: the UI must only ever draw a fixed bar budget, and peak
 * samples must never be lost to an averaging filter).
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