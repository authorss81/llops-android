package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.Stroke

/**
 * Phase 224 — pure-JVM decision table for timelapse replay.
 *
 * Strokes already carry a `timestampMs` (epoch ms from `System.currentTimeMillis`
 * for LASER / most tools, or the voice-note `elapsedMs` base for captured audio
 * strokes — see `AnnotationCanvas` candidate build). Replay plays the committed
 * stroke list back in timestamp order as a prefix; export turns that stream into
 * an MP4 (see [com.authorss81.noteflow.services.TimelapseExporter]).
 *
 * This object owns every input→time→frame mapping so the exporter and the UI
 * player share ONE timing model and the math is unit-testable without Android:
 *
 *  - a stroke whose `timestampMs` is null falls back to `index * FALLBACK_STEP_MS`
 *    (120 ms per committed stroke, matching the UI player);
 *  - times are shifted so the FIRST stroke lands at t=0;
 *  - the video plays 30× FASTER than real time: video ms = real ms / [SPEED_FACTOR]
 *    (a 10-minute drawing → 20 s of video);
 *  - frames are [FPS]-quantized with a hold so the finished drawing is visible;
 *  - [MAX_TOTAL_FRAMES] caps the export; when the natural accelerated timeline
 *    would exceed it the timeline is COMPRESSED (not truncated) so the final
 *    emitted frame still shows every rendered stroke;
 *  - [MAX_STROKES] caps how many strokes a replay/export renders (a page is
 *    already capped at that count by [StrokeGeometryPolicy.MAX_STROKES_PER_PAGE]).
 */
object TimelapsePolicy {

    /** MP4 target: 720p landscape, 30 fps. */
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val FPS = 30

    /**
     * 30× timelapse: video plays this many times faster than real time. A
     * 10-minute drawing → 20 s of video. (video ms = real ms / SPEED_FACTOR).
     */
    const val SPEED_FACTOR = 30f

    /** ~2 Mbps H.264 is plenty for a stroke-replay video. */
    const val BITRATE = 2_000_000

    /** Hard ceiling on how many strokes a replay/export renders. */
    const val MAX_STROKES = 2_000

    /**
     * Worst-case cap on the EXPORTED video length in frames (30 fps → 60 s of
     * video). At 30× speed that is ~30 real minutes of drawing; beyond it the
     * timeline is compressed so the final frame still shows the whole page.
     */
    const val MAX_TOTAL_FRAMES = 60 * FPS

    /** Fallback inter-stroke gap (ms) when a stroke has no timestamp. */
    const val FALLBACK_STEP_MS = 120L

    /**
     * Bounds [strokes] to at most [MAX_STROKES], keeping the head (oldest) —
     * mirrors the page/geometry caps so replay never renders unbounded rows.
     * Pure: never mutates the input.
     */
    fun capped(strokes: List<Stroke>): List<Stroke> = strokes.take(MAX_STROKES)

    /**
     * Per-stroke real source time (ms): `timestampMs` when present, else the
     * fallback `index * FALLBACK_STEP_MS` so an un-timestamped page still has a
     * monotonic, sensible order. The caller should pass the capped list so the
     * fallback indices line up.
     */
    fun sourceTimeMs(strokes: List<Stroke>): List<Long> {
        val capped = capped(strokes)
        return capped.mapIndexed { i, s -> s.timestampMs ?: (i * FALLBACK_STEP_MS) }
    }

    /**
     * Per-stroke VIDEO duration (ms), shifted so the first stroke is at 0 and
     * scaled so the video plays [SPEED_FACTOR]× faster than real time. This is
     * the timeline the MP4 uses and what the player mirrors.
     */
    fun videoElapsedMs(strokes: List<Stroke>): List<Long> {
        val capped = capped(strokes)
        if (capped.isEmpty()) return emptyList()
        val times = sourceTimeMs(capped)
        val t0 = times.minOrNull() ?: 0L
        return times.map { ((it - t0) / SPEED_FACTOR.toDouble()).toLong() }
    }

    /**
     * The effective video timeline used for rendering: [videoElapsedMs] when the
     * natural length fits the [maxFrames] budget, else COMPRESSED (every video
     * time scaled down proportionally) so the last stroke finishes by
     * `maxFrames - 2` and every frame `0..maxFrames-1` can be rendered. This
     * guarantees the final MP4 frame shows the completed drawing even on an
     * extreme/old page (never silent truncation).
     */
    fun effectiveElapsedMs(strokes: List<Stroke>, maxFrames: Int = MAX_TOTAL_FRAMES): List<Long> {
        val budget = maxFrames.coerceAtLeast(2)
        val video = videoElapsedMs(strokes)
        if (video.isEmpty()) return video
        val maxMs = video.maxOrNull() ?: 0L
        val naturalTotal = (frameForElapsedMs(maxMs) + 2).coerceAtLeast(2)
        if (naturalTotal <= budget) return video
        if (maxMs <= 0) return video
        // Compress so maxMs lands at the time value of frame (budget - 2).
        val targetMs = ((budget - 2).toLong() * 1000L) / FPS.toLong()
        val scale = targetMs.toDouble() / maxMs
        return video.map { (it * scale).toLong() }
    }

    /**
     * Total accelerated duration (ms) of the effective timeline, at least 0.
     */
    fun acceleratedDurationMs(strokes: List<Stroke>): Long {
        val eff = effectiveElapsedMs(strokes)
        return if (eff.isEmpty()) 0L else (eff.maxOrNull() ?: 0L)
    }

    /**
     * The video frame index a given video elapsed (millisecond) lands on.
     * Non-negative, FPS-quantized.
     */
    fun frameForElapsedMs(elapsedVideoMs: Long): Int {
        if (elapsedVideoMs < 0) return 0
        return ((elapsedVideoMs * FPS) / 1000L).toInt()
    }

    /**
     * Total number of frames to render for an export. Always ≥ 1 (an empty page
     * yields a single blank frame), bounded by [maxFrames]. When the natural
     * accelerated timeline exceeds the budget, [effectiveElapsedMs] compresses
     * it so the last emitted frame still shows every rendered stroke — never
     * silent truncation.
     */
    fun totalFrames(strokes: List<Stroke>, maxFrames: Int = MAX_TOTAL_FRAMES): Int {
        val budget = maxFrames.coerceAtLeast(1)
        if (capped(strokes).isEmpty()) return 1
        val naturalVideo = videoElapsedMs(strokes)
        val lastMs = naturalVideo.maxOrNull() ?: 0L
        val natural = (frameForElapsedMs(lastMs) + 2).coerceAtLeast(2)
        return natural.coerceAtMost(budget)
    }

    /**
     * How many strokes are visible at [frameIndex] (a prefix of the capped list
     * in timestamp order). A stroke is visible once its effective video time is
     * ≤ the frame's time; frame 0 always shows the first stroke.
     */
    fun visibleStrokeCountAtFrame(
        strokes: List<Stroke>,
        frameIndex: Int,
        fps: Int = FPS,
        maxFrames: Int = MAX_TOTAL_FRAMES
    ): Int {
        val capped = capped(strokes)
        if (capped.isEmpty()) return 0
        if (frameIndex <= 0) return 1.coerceAtMost(capped.size)
        val eff = effectiveElapsedMs(capped, maxFrames)
        val frameTimeMs = frameIndex * 1000L / fps.toLong()
        var count = 0
        for (a in eff) {
            if (a <= frameTimeMs) count++ else break
        }
        if (count == 0) count = 1
        return count.coerceAtMost(capped.size)
    }
}
