package com.authorss81.noteflow.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.authorss81.noteflow.data.model.CanvasTextStyle
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.utils.BitmapPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

/**
 * Phase 224 — MP4 timelapse export for a page's committed strokes.
 *
 * Strokes already carry a `timestampMs`; [TimelapsePolicy] maps them to an
 * accelerated (30×) timeline and this object renders the incremental prefix of
 * strokes to 720p frames, encodes them with the platform `MediaCodec` (H.264
 * baseline) and muxes the stream with `MediaMuxer`. No ffmpeg, no native deps —
 * only `android.media.*` on the API-26 floor.
 *
 * Rendering reuses the repo's minimal `android.graphics.Canvas` stroke renderer
 * (the same freehand/shape/text/FILL/GRADIENT path `AnnotationCanvas` uses for
 * the fill-tool sampler) — a self-contained rasterizer that does not depend on
 * the full Compose/DrawScope canvas subsystem, so it is safe on a background
 * thread. The page's world coordinates are fitted (uniform scale, centered with
 * padding) into the 720p frame.
 *
 * Each rendered ARGB frame is converted to the encoder's concrete YUV420 format
 * and queued to the codec's INPUT BUFFER path with an explicit presentation
 * timestamp (`fps`-quantized), so the video plays back at the intended speed
 * without real-time throttling — export runs as fast as the CPU can render.
 *
 * The output lands in app-private `cacheDir/timelapse_<page>.mp4`; the caller
 * sends it to the user's chosen destination via the SAF picker
 * ([com.authorss81.noteflow.ui.components.SaFExporter]); the staging copy is
 * deleted on transfer and never silently appears in shared storage
 * (B1-PLAT-3 phase-59 discipline).
 */

/** Minimal AVC encoder wrapper built directly on [MediaCodec] + [MediaMuxer]. */
object TimelapseExporter {

    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private const val I_FRAME_INTERVAL = 1

    /**
     * Encodes the [strokes] timelapse to `cacheDir/timelapse_<page>.mp4`.
     * Returns the file on success, or null on any failure. [onProgress] reports
     * 0f..1f completion from the IO thread (drives a LinearProgressIndicator).
     */
    suspend fun export(
        context: Context,
        title: String,
        strokes: List<Stroke>,
        onProgress: ((Float) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        val capped = TimelapsePolicy.capped(strokes)
        val totalFrames = TimelapsePolicy.totalFrames(capped)
        if (totalFrames <= 0) return@withContext null

        val w = TimelapsePolicy.WIDTH
        val h = TimelapsePolicy.HEIGHT

        // World→frame transform so the whole page fits (centered, padded).
        val transform = fitTransform(worldBounds(capped), w, h)

        val sanitizeTitle = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "Page" }
        val exportDir = File(context.cacheDir, "timelapse_exports").apply { if (!exists()) mkdirs() }
        val outFile = File(exportDir, "timelapse_$sanitizeTitle.mp4")

        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var frameBitmap: Bitmap? = null
        try {
            val format = MediaFormat.createVideoFormat(MIME, w, h).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, TimelapsePolicy.BITRATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, TimelapsePolicy.FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
            }
            encoder = MediaCodec.createEncoderByType(MIME)
            val colorFormat = chooseColorFormat(encoder.codecInfo)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var trackIndex = -1
            var muxerStarted = false

            frameBitmap = BitmapPool.acquire(w, h, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(frameBitmap)
            val pixels = IntArray(w * h)

            for (frame in 0 until totalFrames) {
                val visible = TimelapsePolicy.visibleStrokeCountAtFrame(capped, frame)
                canvas.drawColor(0xFFFBFBF7.toInt())
                drawPrefix(canvas, capped.take(visible), transform)

                val ptsUs = frame * 1_000_000L / TimelapsePolicy.FPS
                frameBitmap!!.getPixels(pixels, 0, w, 0, 0, w, h)
                val inIndex = encoder!!.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val input = encoder.getInputBuffer(inIndex)
                        ?: continue
                    input.clear()
                    fillYuv420(input, pixels, w, h, colorFormat)
                    encoder.queueInputBuffer(inIndex, 0, (w * h * 3) / 2, ptsUs, 0)
                } else {
                    continue
                }

                if (!muxerStarted) {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                drainEncoder(encoder!!, muxer, trackIndex, endOfStream = false)

                onProgress?.invoke((frame + 1).toFloat() / totalFrames)
            }

            // Signal EOS and drain the tail so the muxer writes the final moov.
            signalEndOfStream(encoder!!)
            drainEncoder(encoder!!, muxer, trackIndex, endOfStream = true)

            outFile
        } catch (e: Exception) {
            runCatching { outFile.delete() }
            null
        } finally {
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            frameBitmap?.let { BitmapPool.release(it) }
        }
    }

    /**
     * Queues a zero-length buffer with the EOS flag to flush the encoder tail.
     */
    private fun signalEndOfStream(encoder: MediaCodec) {
        val inIndex = encoder.dequeueInputBuffer(10_000)
        if (inIndex >= 0) {
            val input = encoder.getInputBuffer(inIndex) ?: return
            input.clear()
            encoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        }
    }

    /**
     * Drains encoded output buffers into the muxer. [endOfStream] drains until
     * BUFFER_FLAG_END_OF_STREAM (bounded) so the encoder's tail flushes.
     */
    private fun drainEncoder(
        encoder: MediaCodec,
        muxer: MediaMuxer,
        trackIndex: Int,
        endOfStream: Boolean
    ) {
        val bufferInfo = MediaCodec.BufferInfo()
        var idleWaits = 0
        while (true) {
            val outIndex = encoder.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    idleWaits++
                    if (idleWaits > 200) return // ~2 s of idle — stop draining.
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    // Output format is consumed via encoder.outputFormat before
                    // muxer.start(); nothing to do here.
                }
                outIndex >= 0 -> {
                    val encoded = encoder.getOutputBuffer(outIndex)
                    val isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                    if (encoded != null && !isConfig && bufferInfo.size > 0 && trackIndex >= 0) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        return
                    }
                }
            }
        }
    }

    /**
     * Selects a concrete, widely-supported YUV420 color format the AVC encoder
     * actually supports (preferring planar/semi-planar), falling back to
     * semi-planar. [fillYuv420] then converts ARGB into that arrangement.
     */
    private fun chooseColorFormat(codecInfo: MediaCodecInfo): Int {
        val capabilities = codecInfo.getCapabilitiesForType(MIME)
        val formats = capabilities.colorFormats
        val preferred = intArrayOf(
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        )
        for (p in preferred) {
            for (f in formats) {
                if (f == p) return p
            }
        }
        return MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    }

    /**
     * Fills [dest] with the [pixels] (ARGB_8888) frame as YUV420. [colorFormat]
     * is one of [MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar]
     * (I420) or COLOR_FormatYUV420SemiPlanar (NV12). Y plane is w×h; then either
     * three planar U/V planes (I420) or interleaved U,V (NV12) of (w/2)×(h/2).
     */
    private fun fillYuv420(dest: ByteBuffer, pixels: IntArray, w: Int, h: Int, colorFormat: Int) {
        val isPlanar = colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        val ySize = w * h
        val uvCount = (w / 2) * (h / 2)
        // Lay the U/V data to temp positions, then it may need re-ordering.
        val y = ByteArray(ySize)
        val u = ByteArray(uvCount)
        val v = ByteArray(uvCount)
        var uvIdx = 0
        for (j in 0 until h) {
            val rowBase = j * w
            for (i in 0 until w) {
                val argb = pixels[rowBase + i]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                // BT.601 full-ish conversion (SD range to match common encoders).
                val yy = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).coerceIn(16, 235)
                y[rowBase + i] = yy.toByte()
                if ((j % 2 == 0) && (i % 2 == 0)) {
                    val uu = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).coerceIn(16, 240)
                    val vv = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).coerceIn(16, 240)
                    u[uvIdx] = uu.toByte()
                    v[uvIdx] = vv.toByte()
                    uvIdx++
                }
            }
        }
        dest.put(y, 0, ySize)
        if (isPlanar) {
            dest.put(u, 0, uvCount)
            dest.put(v, 0, uvCount)
        } else {
            // NV12: interleave U,V per row pair.
            for (k in 0 until uvCount) {
                dest.put(u[k])
                dest.put(v[k])
            }
        }
        dest.position(0)
    }

    /**
     * Union bounds (world coords) of every stroke's points (and shape anchors).
     * Falls back to a sane 1080×1528 page box so an empty/point-only page still
     * maps to a valid, bounded frame.
     */
    internal fun worldBounds(strokes: List<Stroke>): RectF {
        if (strokes.isEmpty()) return RectF(0f, 0f, 1080f, 1528f)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (s in strokes) {
            if (s.start != null) {
                minX = minOf(minX, s.start.x); minY = minOf(minY, s.start.y)
                maxX = maxOf(maxX, s.start.x); maxY = maxOf(maxY, s.start.y)
            }
            if (s.end != null) {
                minX = minOf(minX, s.end.x); minY = minOf(minY, s.end.y)
                maxX = maxOf(maxX, s.end.x); maxY = maxOf(maxY, s.end.y)
            }
            for (p in s.points) {
                minX = minOf(minX, p.x); minY = minOf(minY, p.y)
                maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y)
            }
        }
        if (minX == Float.MAX_VALUE) return RectF(0f, 0f, 1080f, 1528f)
        if (maxX - minX < 1f) { maxX += 1f; minX -= 1f }
        if (maxY - minY < 1f) { maxY += 1f; minY -= 1f }
        return RectF(minX, minY, maxX, maxY)
    }

    /**
     * A uniform fit of [world] into [w]×[h] with a small margin, centered.
     * Returns [FrameTransform] mapping world→frame pixels.
     */
    internal fun fitTransform(world: RectF, w: Int, h: Int): FrameTransform {
        val pad = 0.96f
        val worldW = (world.right - world.left).coerceAtLeast(1f)
        val worldH = (world.bottom - world.top).coerceAtLeast(1f)
        val scale = minOf((w * pad) / worldW, (h * pad) / worldH)
        val dw = worldW * scale
        val dh = worldH * scale
        val dx = (w - dw) / 2f - world.left * scale
        val dy = (h - dh) / 2f - world.top * scale
        return FrameTransform(scale, dx, dy)
    }

    /** World→frame affine: `frame = world * scale + (dx, dy)`. */
    internal data class FrameTransform(val scale: Float, val dx: Float, val dy: Float) {
        fun x(worldX: Float): Float = worldX * scale + dx
        fun y(worldY: Float): Float = worldY * scale + dy
    }

    /**
     * Draws [strokes] onto [canvas] applying [t] to world coords. Mirrors
     * `AnnotationCanvas.drawSingleStrokeToCanvas` (the fill-tool sampler) so the
     * timelapse looks like the page: freehand polylines, single-point dots,
     * shape boxes, TEXT labels, FILL polygons and GRADIENT rects. Wet/tiled/AGSL
     * multi-pass brushes fall back to their flat polyline — an accepted, bounded
     * approximation for a replay export.
     */
    internal fun drawPrefix(canvas: Canvas, strokes: List<Stroke>, t: FrameTransform) {
        for (stroke in strokes) {
            drawStroke(canvas, stroke, t)
        }
    }

    private fun drawStroke(canvas: Canvas, stroke: Stroke, t: FrameTransform) {
        when (stroke.tool) {
            StrokeTool.FILL -> {
                if (stroke.points.size >= 3) {
                    val paint = paintFor(stroke).apply { style = android.graphics.Paint.Style.FILL }
                    val path = android.graphics.Path()
                    path.moveTo(t.x(stroke.points[0].x), t.y(stroke.points[0].y))
                    for (i in 1 until stroke.points.size) path.lineTo(t.x(stroke.points[i].x), t.y(stroke.points[i].y))
                    path.close()
                    canvas.drawPath(path, paint)
                }
            }
            StrokeTool.GRADIENT -> {
                if (stroke.start != null && stroke.end != null) {
                    val paint = paintFor(stroke).apply { style = android.graphics.Paint.Style.FILL }
                    canvas.drawRect(
                        minOf(t.x(stroke.start.x), t.x(stroke.end.x)),
                        minOf(t.y(stroke.start.y), t.y(stroke.end.y)),
                        maxOf(t.x(stroke.start.x), t.x(stroke.end.x)),
                        maxOf(t.y(stroke.start.y), t.y(stroke.end.y)),
                        paint
                    )
                }
            }
            StrokeTool.TEXT -> {
                if (stroke.start != null && stroke.text.isNotEmpty()) {
                    val parsed = CanvasTextStyle.parse(stroke.text)
                    val textPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        color = stroke.colorInt
                        textSize = parsed.first.fontSizeSp * 1.5f * t.scale
                    }
                    canvas.drawText(parsed.second, t.x(stroke.start.x), t.y(stroke.start.y), textPaint)
                }
            }
            else -> {
                val paint = paintFor(stroke)
                if (stroke.points.size > 1) {
                    val path = android.graphics.Path()
                    path.moveTo(t.x(stroke.points[0].x), t.y(stroke.points[0].y))
                    for (i in 1 until stroke.points.size) {
                        path.lineTo(t.x(stroke.points[i].x), t.y(stroke.points[i].y))
                    }
                    canvas.drawPath(path, paint)
                } else if (stroke.points.size == 1) {
                    val dot = paintFor(stroke).apply { style = android.graphics.Paint.Style.FILL }
                    canvas.drawCircle(t.x(stroke.points[0].x), t.y(stroke.points[0].y), (stroke.width / 2f).coerceAtLeast(1f), dot)
                }
                if (stroke.start != null && stroke.end != null && stroke.tool.isShapeTool) {
                    val shapePaint = paintFor(stroke).apply { style = android.graphics.Paint.Style.STROKE }
                    canvas.drawRect(
                        minOf(t.x(stroke.start.x), t.x(stroke.end.x)),
                        minOf(t.y(stroke.start.y), t.y(stroke.end.y)),
                        maxOf(t.x(stroke.start.x), t.x(stroke.end.x)),
                        maxOf(t.y(stroke.start.y), t.y(stroke.end.y)),
                        shapePaint
                    )
                }
            }
        }
    }

    private fun paintFor(stroke: Stroke): android.graphics.Paint =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = stroke.colorInt
            strokeWidth = stroke.width.coerceAtLeast(1f)
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
}
