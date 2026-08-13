package com.authorss81.noteflow.services

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LightingColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Bitmap & Shader Brush Engine for NoteFlow.
 *
 * Implements real texture-tiled brushes using `BitmapShader` (with `TileMode.REPEAT`),
 * Jetpack Compose `ShaderBrush` (`ImageShader`), and bitmap stamp sequences along stroke paths
 * with pressure, velocity, tilt, and scattering response.
 */
object BrushTextureEngine {

    enum class TextureType {
        PENCIL_GRAPHITE,
        CANVAS_WEAVE,
        WATERCOLOR_PAPER,
        AIRBRUSH_SPRAY,
        SPLATTER_DROPS,
        CHARCOAL_GRAIN,
        OIL_PASTEL_STREAK,
        GOUACHE_MATTE
    }

    // Cached procedural texture bitmaps
    private val textureBitmaps = mutableMapOf<TextureType, Bitmap>()
    private val textureImageBitmaps = mutableMapOf<TextureType, ImageBitmap>()

    init {
        generateAllTextures()
    }

    private fun generateAllTextures() {
        textureBitmaps[TextureType.PENCIL_GRAPHITE] = createGraphiteGrainBitmap()
        textureBitmaps[TextureType.CANVAS_WEAVE] = createCanvasWeaveBitmap()
        textureBitmaps[TextureType.WATERCOLOR_PAPER] = createWatercolorPaperBitmap()
        textureBitmaps[TextureType.AIRBRUSH_SPRAY] = createAirbrushSprayBitmap()
        textureBitmaps[TextureType.SPLATTER_DROPS] = createSplatterDropsBitmap()
        textureBitmaps[TextureType.CHARCOAL_GRAIN] = createCharcoalGrainBitmap()
        textureBitmaps[TextureType.OIL_PASTEL_STREAK] = createOilPastelStreakBitmap()
        textureBitmaps[TextureType.GOUACHE_MATTE] = createGouacheMatteBitmap()

        textureBitmaps.forEach { (type, bmp) ->
            textureImageBitmaps[type] = bmp.asImageBitmap()
        }
    }

    /**
     * Get an ImageBitmap texture asset for Compose ShaderBrush usage
     */
    fun getTextureImageBitmap(type: TextureType): ImageBitmap {
        return textureImageBitmaps[type] ?: createGraphiteGrainBitmap().asImageBitmap()
    }

    /**
     * Create a Jetpack Compose ShaderBrush wrapping an ImageShader with TileMode.Repeated
     */
    fun createShaderBrush(type: TextureType): ShaderBrush {
        val imgBmp = getTextureImageBitmap(type)
        return androidx.compose.ui.graphics.ShaderBrush(
            androidx.compose.ui.graphics.ImageShader(
                image = imgBmp,
                tileModeX = TileMode.Repeated,
                tileModeY = TileMode.Repeated
            )
        )
    }

    /**
     * Create a native android.graphics.BitmapShader for high-performance canvas path rendering
     */
    fun createBitmapShader(
        type: TextureType,
        color: Color,
        scale: Float = 1.0f
    ): BitmapShader {
        val bitmap = textureBitmaps[type] ?: textureBitmaps[TextureType.PENCIL_GRAPHITE]!!
        val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        if (scale != 1.0f) {
            val matrix = Matrix().apply { setScale(scale, scale) }
            shader.setLocalMatrix(matrix)
        }
        return shader
    }

    /**
     * Render a textured stroke path onto a native Android Canvas using BitmapShader and color filtering.
     * [seed] rotates the texture orientation per stroke so every stroke deposits its own grain (Phase 18).
     */
    fun drawTexturedStrokePath(
        nativeCanvas: Canvas,
        points: List<PointF>,
        offsetY: Float,
        strokeWidth: Float,
        color: Color,
        textureType: TextureType,
        seed: Float = 0f
    ) {
        if (points.isEmpty()) return

        val shader = createBitmapShader(textureType, color, scale = (strokeWidth / 32f).coerceIn(0.5f, 3.0f))
        if (seed != 0f) {
            shader.setLocalMatrix(Matrix().apply {
                setRotate(seed * 360f)
                postScale((strokeWidth / 32f).coerceIn(0.5f, 3.0f), (strokeWidth / 32f).coerceIn(0.5f, 3.0f))
            })
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            this.shader = shader
            colorFilter = PorterDuffColorFilter(color.toArgb(), PorterDuff.Mode.SRC_IN)
            alpha = (color.alpha * 255).toInt().coerceIn(0, 255)
        }

        if (points.size == 1) {
            val fillPaint = Paint(paint).apply { style = Paint.Style.FILL }
            nativeCanvas.drawCircle(points[0].x, points[0].y + offsetY, strokeWidth / 2f, fillPaint)
            return
        }

        val path = android.graphics.Path().apply {
            moveTo(points[0].x, points[0].y + offsetY)
            for (i in 1 until points.size) {
                val p0 = points[i - 1]
                val p1 = points[i]
                val midX = (p0.x + p1.x) / 2f
                val midY = (p0.y + p1.y) / 2f + offsetY
                quadTo(p0.x, p0.y + offsetY, midX, midY)
            }
            lineTo(points.last().x, points.last().y + offsetY)
        }

        nativeCanvas.drawPath(path, paint)
    }

    /**
     * Render bitmap stamp sequences along points with scattering, rotation, and pressure scaling.
     * [forceStampEvery] stamps at every supplied point regardless of spacing — used by the
     * airbrush dwell density so holding the brush still deposits a denser cloud (Phase 18).
     */
    fun drawBitmapStampSequence(
        nativeCanvas: Canvas,
        points: List<PointF>,
        offsetY: Float,
        baseSize: Float,
        color: Color,
        textureType: TextureType,
        spacingFactor: Float = 0.35f,
        scatterFactor: Float = 0.2f,
        forceStampEvery: Boolean = false
    ) {
        if (points.isEmpty()) return
        val stampBitmap = textureBitmaps[textureType] ?: return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = PorterDuffColorFilter(color.toArgb(), PorterDuff.Mode.SRC_IN)
            isFilterBitmap = true
        }

        var lastDrawX = -9999f
        var lastDrawY = -9999f
        val minDistance = (baseSize * spacingFactor).coerceAtLeast(1f)

        for (i in points.indices) {
            val pt = points[i]
            val px = pt.x
            val py = pt.y + offsetY
            val dist = sqrt((px - lastDrawX) * (px - lastDrawX) + (py - lastDrawY) * (py - lastDrawY))

            if (i == 0 || dist >= minDistance || forceStampEvery) {
                val pressure = pt.pressure ?: 1.0f
                val tilt = pt.tilt ?: 0f
                val currentSize = (baseSize * (0.6f + pressure * 0.8f)).coerceAtLeast(2f)

                val randomSeed = (px * 1000 + py * 100 + i * 37).toInt()
                val random = Random(randomSeed)

                val scatterX = if (scatterFactor > 0f) (random.nextFloat() - 0.5f) * currentSize * scatterFactor else 0f
                val scatterY = if (scatterFactor > 0f) (random.nextFloat() - 0.5f) * currentSize * scatterFactor else 0f
                val rotation = random.nextFloat() * 360f

                val drawX = px + scatterX
                val drawY = py + scatterY

                val matrix = Matrix().apply {
                    postTranslate(-stampBitmap.width / 2f, -stampBitmap.height / 2f)
                    postScale(currentSize / stampBitmap.width, currentSize / stampBitmap.height)
                    postRotate(rotation + tilt * 10f)
                    postTranslate(drawX, drawY)
                }

                paint.alpha = ((color.alpha * (0.4f + pressure * 0.6f)) * 255).toInt().coerceIn(10, 255)
                nativeCanvas.drawBitmap(stampBitmap, matrix, paint)

                lastDrawX = px
                lastDrawY = py
            }
        }
    }

    /**
     * CHARCOAL vector fallback: a soft grainy base pass plus a couple of offset,
     * narrower streak passes so the mark reads as rough powdery streaks rather
     * than a clean uniform pen line.
     */
    fun drawCharcoalStroke(
        nativeCanvas: Canvas,
        points: List<PointF>,
        offsetY: Float,
        strokeWidth: Float,
        color: Color,
        seed: Float = 0f
    ) {
        if (points.isEmpty()) return
        val base = color.copy(alpha = (color.alpha * 0.55f).coerceIn(0f, 1f))
        drawTexturedStrokePath(nativeCanvas, points, offsetY, strokeWidth, base, TextureType.CHARCOAL_GRAIN, seed)
        if (points.size > 1) {
            val (nx, ny) = perpendicular(points)
            for (k in floatArrayOf(-0.55f, 0.55f)) {
                val shifted = points.map { PointF(it.x + nx * strokeWidth * k, it.y + ny * strokeWidth * k, it.pressure, it.tilt, it.timestampMs) }
                drawTexturedStrokePath(
                    nativeCanvas, shifted, offsetY, strokeWidth * 0.42f,
                    color.copy(alpha = (color.alpha * 0.7f).coerceIn(0f, 1f)),
                    TextureType.CHARCOAL_GRAIN, seed * 1.7f
                )
            }
        }
    }

    /**
     * DRY_BRUSH vector fallback: three narrow parallel bristle clumps with gaps in
     * between (alpha-jittered) so the stroke is sparse and streaky, never a solid line.
     */
    fun drawDryBrushStroke(
        nativeCanvas: Canvas,
        points: List<PointF>,
        offsetY: Float,
        strokeWidth: Float,
        color: Color,
        seed: Float = 0f
    ) {
        if (points.isEmpty()) return
        if (points.size == 1) {
            drawTexturedStrokePath(nativeCanvas, points, offsetY, strokeWidth * 0.6f, color, TextureType.CHARCOAL_GRAIN, seed)
            return
        }
        val (nx, ny) = perpendicular(points)
        val clumpWidth = (strokeWidth * 0.26f).coerceAtLeast(0.8f)
        for (k in floatArrayOf(-0.66f, 0f, 0.66f)) {
            val shifted = points.map { PointF(it.x + nx * strokeWidth * k, it.y + ny * strokeWidth * k, it.pressure, it.tilt, it.timestampMs) }
            drawTexturedStrokePath(
                nativeCanvas, shifted, offsetY, clumpWidth,
                color.copy(alpha = (color.alpha * 0.5f).coerceIn(0f, 1f)),
                TextureType.CHARCOAL_GRAIN, seed * (2.0f + abs(k))
            )
        }
    }

    /**
     * INK_WASH vector fallback: a soft wide damp wash plus a concentrated darker
     * inner pass — approximates the shader's dark wet-edge pooling.
     */
    fun drawInkWashStroke(
        nativeCanvas: Canvas,
        points: List<PointF>,
        offsetY: Float,
        strokeWidth: Float,
        color: Color,
        seed: Float = 0f
    ) {
        if (points.isEmpty()) return
        drawTexturedStrokePath(
            nativeCanvas, points, offsetY, strokeWidth * 1.35f,
            color.copy(alpha = (color.alpha * 0.45f).coerceIn(0f, 1f)),
            TextureType.WATERCOLOR_PAPER, seed
        )
        drawTexturedStrokePath(
            nativeCanvas, points, offsetY, strokeWidth * 0.92f,
            color.copy(alpha = (color.alpha * 0.85f).coerceIn(0f, 1f)),
            TextureType.WATERCOLOR_PAPER, seed * 1.3f
        )
    }

    /**
     * PALETTE_KNIFE vector fallback: a wide flat smear pass (square cap, no round
     * pooling) plus narrow directional streak passes offset across the stroke.
     */
    fun drawPaletteKnifeStroke(
        nativeCanvas: Canvas,
        points: List<PointF>,
        offsetY: Float,
        strokeWidth: Float,
        color: Color,
        seed: Float = 0f
    ) {
        if (points.isEmpty()) return
        val (nx, ny) = perpendicular(points)
        val flatPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth * 1.6f
            strokeCap = Paint.Cap.SQUARE
            strokeJoin = Paint.Join.BEVEL
            colorFilter = PorterDuffColorFilter(color.copy(alpha = 0.9f).toArgb(), PorterDuff.Mode.SRC_IN)
        }
        val path = buildSmoothPath(points, offsetY)
        nativeCanvas.drawPath(path, flatPaint)
        // directional smear streaks
        for (k in floatArrayOf(-0.5f, 0.5f)) {
            val shifted = points.map { PointF(it.x + nx * strokeWidth * k, it.y + ny * strokeWidth * k, it.pressure, it.tilt, it.timestampMs) }
            drawTexturedStrokePath(
                nativeCanvas, shifted, offsetY, strokeWidth * 0.35f,
                color.copy(alpha = (color.alpha * 0.85f).coerceIn(0f, 1f)),
                TextureType.CANVAS_WEAVE, seed * 0.7f
            )
        }
    }

    /** Overall unit perpendicular of a polyline (globally consistent with the shader's [perpDir]). */
    private fun perpendicular(points: List<PointF>): Pair<Float, Float> {
        val first = points.first()
        val last = points.last()
        if (points.size < 2) return Pair(0f, 1f)
        var dx = (last.x - first.x)
        var dy = (last.y - first.y)
        val len = sqrt(dx * dx + dy * dy)
        if (len < 0.001f) return Pair(0f, 1f)
        dx /= len
        dy /= len
        return Pair(-dy, dx)
    }

    /** Shared smooth-path builder for the flat/knife passes. */
    private fun buildSmoothPath(points: List<PointF>, offsetY: Float): android.graphics.Path {
        val path = android.graphics.Path().apply {
            moveTo(points[0].x, points[0].y + offsetY)
            for (i in 1 until points.size) {
                val p0 = points[i - 1]
                val p1 = points[i]
                val midX = (p0.x + p1.x) / 2f
                val midY = (p0.y + p1.y) / 2f + offsetY
                quadTo(p0.x, p0.y + offsetY, midX, midY)
            }
            lineTo(points.last().x, points.last().y + offsetY)
        }
        return path
    }

    // --- Procedural Texture Generators ---

    private fun createGraphiteGrainBitmap(): Bitmap {
        val width = 64
        val height = 64
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val random = Random(12345)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val noise = random.nextInt(120) + 135 // 135..255 grayscale
                val alpha = if (random.nextFloat() > 0.35f) noise else 0
                pixels[y * width + x] = android.graphics.Color.argb(alpha, 255, 255, 255)
            }
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }

    private fun createCanvasWeaveBitmap(): Bitmap {
        val width = 64
        val height = 64
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val weaveHorizontal = (x % 4 < 2)
                val weaveVertical = (y % 4 < 2)
                val isThread = weaveHorizontal xor weaveVertical
                val valGray = if (isThread) 230 else 140
                pixels[y * width + x] = android.graphics.Color.argb(220, valGray, valGray, valGray)
            }
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }

    private fun createWatercolorPaperBitmap(): Bitmap {
        val width = 128
        val height = 128
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val random = Random(54321)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val nx = (sin(x * 0.15) * 40 + cos(y * 0.15) * 40).toInt()
                val grain = (200 + nx + random.nextInt(30)).coerceIn(100, 255)
                pixels[y * width + x] = android.graphics.Color.argb(grain, 255, 255, 255)
            }
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }

    private fun createAirbrushSprayBitmap(): Bitmap {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = size / 2f
        val maxRadius = size / 2f
        val random = Random(9876)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
        }

        for (i in 0 until 250) {
            val r = (random.nextFloat() * random.nextFloat()) * maxRadius
            val angle = random.nextFloat() * 2f * Math.PI
            val px = (center + r * cos(angle)).toFloat()
            val py = (center + r * sin(angle)).toFloat()
            val pRadius = (0.8f + random.nextFloat() * 1.4f)
            paint.alpha = ((1f - (r / maxRadius)) * 255).toInt().coerceIn(20, 255)
            canvas.drawCircle(px, py, pRadius, paint)
        }
        return bmp
    }

    private fun createSplatterDropsBitmap(): Bitmap {
        val size = 64
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val center = size / 2f
        val random = Random(112233)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
        }

        // Main center drop
        canvas.drawCircle(center, center, size * 0.22f, paint)

        // Peripheral droplets
        for (i in 0 until 16) {
            val dist = (0.25f + random.nextFloat() * 0.65f) * center
            val angle = random.nextFloat() * 2f * Math.PI
            val px = (center + dist * cos(angle)).toFloat()
            val py = (center + dist * sin(angle)).toFloat()
            val dropRadius = (1.0f + random.nextFloat() * 3.5f)
            canvas.drawCircle(px, py, dropRadius, paint)
        }
        return bmp
    }

    /**
     * Phase 18: charcoal — directional smudgy grain with soft dark flecks so the
     * textured strip reads as rough powdery strokes instead of flat ink.
     */
    private fun createCharcoalGrainBitmap(): Bitmap {
        val width = 80
        val height = 80
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val random = Random(20240818)

        for (y in 0 until height) {
            for (x in 0 until width) {
                // Rare heavy dark flecks + medium grain streaks
                val streak = when {
                    random.nextFloat() < 0.5f -> 150
                    random.nextFloat() < 0.14f -> 70
                    else -> 210
                }
                val alpha = if (random.nextFloat() > 0.18f) streak else 0
                pixels[y * width + x] = android.graphics.Color.argb(alpha, 255, 255, 255)
            }
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }

    /**
     * Phase 18: oil pastel — tight wax streak bands so waxy, chalky layering shows
     * visible stroke-direction striations.
     */
    private fun createOilPastelStreakBitmap(): Bitmap {
        val width = 64
        val height = 64
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val random = Random(557799)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val band = (x % 6)
                val bandNoise = random.nextInt(20)
                val luminance = when {
                    band < 2 -> 245 + bandNoise // wax ridge
                    band < 4 -> 210 + bandNoise // wax valley
                    else -> 170 // striation gap (visible wax streak)
                }
                val alpha = if (random.nextFloat() > 0.04f) luminance else 0
                pixels[y * width + x] = android.graphics.Color.argb(alpha, 255, 255, 255)
            }
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }

    /**
     * Phase 18: gouache — uniform matte white with a whisper of speckle so the flat
     * coat reads as dense paint rather than a plain path, but stays essentially even.
     */
    private fun createGouacheMatteBitmap(): Bitmap {
        val width = 64
        val height = 64
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        val random = Random(884422)

        for (y in 0 until height) {
            for (x in 0 until width) {
                val matte = if (random.nextFloat() < 0.08f) 235 else 250
                pixels[y * width + x] = android.graphics.Color.argb(matte, 255, 255, 255)
            }
        }
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }
}
