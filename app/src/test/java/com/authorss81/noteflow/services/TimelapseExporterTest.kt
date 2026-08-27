package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 224 — pure-JVM tests for [TimelapsePolicy]. Verifies the timestamp →
 * accelerated-time → frame-index mapping and the 30× speed factor, plus the
 * stroke cap, with no Android types. (The MediaCodec/MediaMuxer export itself
 * is Android-only and is exercised by the CI `assembleDebug` + the on-device
 * path.)
 */
class TimelapseExporterTest {

    private fun stroke(idx: Int, tsMs: Long? = null): Stroke = Stroke(
        id = "s$idx",
        tool = StrokeTool.PEN,
        points = listOf(PointF(10f, 20f), PointF(30f, 40f)),
        timestampMs = tsMs
    )

    @Test
    fun speedFactorIsThirtyAndPlaybackIsFaster() {
        // A timelapse plays 30× FASTER than real time.
        assertEquals(30f, TimelapsePolicy.SPEED_FACTOR, 0f)
    }

    @Test
    fun emptyTimelineHasOneHoldingFrame() {
        assertEquals(1, TimelapsePolicy.totalFrames(emptyList()))
        assertEquals(0, TimelapsePolicy.visibleStrokeCountAtFrame(emptyList(), 0))
        assertEquals(0L, TimelapsePolicy.acceleratedDurationMs(emptyList()))
    }

    @Test
    fun firstStrokeLandsAtTimeZero() {
        val strokes = listOf(stroke(0, 1_000L), stroke(1, 7_000L))
        val accel = TimelapsePolicy.videoElapsedMs(strokes)
        // (7000-1000)=6000ms real → 6000/30 = 200ms video.
        assertEquals(0L, accel[0])
        assertEquals(200L, accel[1])
    }

    @Test
    fun nullTimestampsFallBackToFixedStep() {
        val strokes = listOf(stroke(0, null), stroke(1, null), stroke(2, null))
        val accel = TimelapsePolicy.videoElapsedMs(strokes)
        // source times 0/120/240 real → /30 → 0/4/8 ms video.
        assertEquals(0L, accel[0])
        assertEquals(4L, accel[1])
        assertEquals(8L, accel[2])
    }

    @Test
    fun frameIndexForElapsedUsesFpsQuantization() {
        // 30 fps → frame 0 at 0ms, frame 30 at 1000ms (video ms).
        assertEquals(0, TimelapsePolicy.frameForElapsedMs(0))
        assertEquals(30, TimelapsePolicy.frameForElapsedMs(1_000))
        assertEquals(15, TimelapsePolicy.frameForElapsedMs(500))
        assertEquals(0, TimelapsePolicy.frameForElapsedMs(-5))
    }

    @Test
    fun visibilityGrowsAsFramesAdvance() {
        // stroke1 at 3000ms real → 100ms video → frame 100*30/1000 = 3.
        val strokes = listOf(stroke(0, 0L), stroke(1, 3_000L))
        assertEquals(1, TimelapsePolicy.visibleStrokeCountAtFrame(strokes, 0))
        assertEquals(1, TimelapsePolicy.visibleStrokeCountAtFrame(strokes, 2))
        assertEquals(2, TimelapsePolicy.visibleStrokeCountAtFrame(strokes, 3))
        assertEquals(2, TimelapsePolicy.visibleStrokeCountAtFrame(strokes, 10))
    }

    @Test
    fun totalFramesIncludesHoldAndEndsWithAllStrokes() {
        val strokes = listOf(stroke(0, 0L), stroke(1, 3_000L))
        val total = TimelapsePolicy.totalFrames(strokes)
        // Last stroke video ms = 100 → frame 3, +2 hold frames = 5.
        assertEquals(5, total)
        // The final frame shows every stroke.
        assertEquals(2, TimelapsePolicy.visibleStrokeCountAtFrame(strokes, total - 1))
    }

    @Test
    fun totalFramesClampedToCapStillEndsComplete() {
        val strokes = listOf(stroke(0, 0L), stroke(1, 100_000_000L))
        val total = TimelapsePolicy.totalFrames(strokes)
        assertEquals(TimelapsePolicy.MAX_TOTAL_FRAMES, total)
        // Even compressed, the last emitted frame shows the full drawing.
        assertEquals(2, TimelapsePolicy.visibleStrokeCountAtFrame(strokes, total - 1))
    }

    @Test
    fun capLimitsRenderedStrokeCount() {
        val many = (0 until 5_000).map { stroke(it, it * 1_000L) }
        val capped = TimelapsePolicy.capped(many)
        assertEquals(TimelapsePolicy.MAX_STROKES, capped.size)
        // First stroke (oldest) kept.
        assertEquals("s0", capped.first().id)
        // Visibility is bounded to the capped set even at the far end.
        assertEquals(
            TimelapsePolicy.MAX_STROKES,
            TimelapsePolicy.visibleStrokeCountAtFrame(many, Int.MAX_VALUE)
        )
    }

    @Test
    fun totalFramesIsSanityBounded() {
        val strokes = listOf(stroke(0, 0L), stroke(1, 4_000L))
        val cap = TimelapsePolicy.totalFrames(strokes, maxFrames = 100)
        assertTrue(cap in 1..100)
    }
}
