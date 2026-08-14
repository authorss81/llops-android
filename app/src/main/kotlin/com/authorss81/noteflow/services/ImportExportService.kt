@file:android.annotation.SuppressLint("RestrictedApi")
package com.authorss81.noteflow.services

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import com.authorss81.noteflow.data.model.CanvasMediaEmbed
import com.authorss81.noteflow.data.model.CanvasStickyNote
import com.authorss81.noteflow.data.model.LayerEntity
import com.authorss81.noteflow.data.model.MediaEmbedType
import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool

object ImportExportService {

    /**
     * Bitmap (non-PDF) export encoders for the annotated-page export.
     * WEBP_LOSSY requires API 30+; older devices fall back to the (deprecated
     * but API 14+) WEBP constant so API 26 stays supported.
     */
    enum class ExportImageFormat {
        PNG,
        WEBP
    }

    fun compressBitmap(format: ExportImageFormat, bitmap: android.graphics.Bitmap, stream: java.io.OutputStream) {
        when (format) {
            ExportImageFormat.PNG -> bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)
            ExportImageFormat.WEBP -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP_LOSSY, 90, stream)
                } else {
                    @Suppress("DEPRECATION")
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.WEBP, 90, stream)
                }
            }
        }
    }

    fun getImportsDir(context: Context): File {
        val dir = File(context.filesDir, "noteflow/imports")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun sanitizeImportFileName(fileName: String): String {
        var safe = fileName.replace('\\', '/')
        if (safe.contains('\u0000') || safe.contains("..")) return "untitled"
        safe = safe.substringAfterLast('/')
        safe = safe.replace(Regex("[\\/:*?\"<>|\\x00-\\x1f]"), "_").trim()
        return safe.ifBlank { "untitled" }
    }

    suspend fun persistFile(context: Context, fileName: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val importsDir = getImportsDir(context)
        val safeName = sanitizeImportFileName(fileName)
        val file = File(importsDir, safeName)
        FileOutputStream(file).use { it.write(bytes) }
        file.absolutePath
    }

    suspend fun readUriBytes(context: Context, uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    fun extensionOf(fileName: String): String {
        val index = fileName.lastIndexOf('.')
        return if (index != -1) fileName.substring(index + 1).lowercase() else ""
    }

    fun isPdf(ext: String) = ext.lowercase() == "pdf"
    fun isImage(ext: String) = ext.lowercase() in listOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "svg", "heic", "heif")

    fun getPdfPageCount(filePath: String): Int {
        return try {
            val file = File(filePath)
            if (!file.exists()) return 0
            val pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            val count = renderer.pageCount
            renderer.close()
            pfd.close()
            count
        } catch (e: Exception) {
            0
        }
    }

    fun renderPdfPageToBitmap(pdfFilePath: String, pageIndex: Int, targetWidth: Int = 1080, targetHeight: Int = 1528): android.graphics.Bitmap? {
        return try {
            val file = File(pdfFilePath)
            if (!file.exists()) return null
            val pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = android.graphics.pdf.PdfRenderer(pfd)
            if (pageIndex < 0 || pageIndex >= renderer.pageCount) {
                renderer.close()
                pfd.close()
                return null
            }
            val pdfPage = renderer.openPage(pageIndex)
            val bitmap = android.graphics.Bitmap.createBitmap(targetWidth, targetHeight, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            pdfPage.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            pdfPage.close()
            renderer.close()
            pfd.close()
            bitmap
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decodes an image with an inSampleSize so the resulting bitmap's long edge
     * never exceeds maxLongEdge. Prevents OOM when a large-capacity camera image
     * (e.g. 48MP) is embedded or used as an export source.
     */
    private fun decodeImageSampled(sourceFilePath: String, maxLongEdge: Int): android.graphics.Bitmap? {
        return try {
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(sourceFilePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val longEdge = bounds.outWidth.coerceAtLeast(bounds.outHeight)
            var inSampleSize = 1
            while (longEdge / inSampleSize > maxLongEdge) {
                inSampleSize *= 2
            }
            val opts = android.graphics.BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            android.graphics.BitmapFactory.decodeFile(sourceFilePath, opts)
        } catch (e: Exception) {
            null
        }
    }

    fun renderPageInkToSvg(strokes: List<com.authorss81.noteflow.data.model.Stroke>, width: Int = 1080, height: Int = 1528): String {
        val sb = StringBuilder()
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 $width $height\" width=\"$width\" height=\"$height\">\n")
        sb.append("  <rect width=\"100%\" height=\"100%\" fill=\"none\"/>\n")
        for (stroke in strokes) {
            if (stroke.points.size < 2) continue
            val hexColor = String.format("#%06X", (stroke.colorInt and 0xFFFFFF))
            val alpha = ((stroke.colorInt ushr 24) and 0xFF) / 255.0
            sb.append("  <path d=\"M ${stroke.points[0].x} ${stroke.points[0].y}")
            for (i in 1 until stroke.points.size) {
                sb.append(" L ${stroke.points[i].x} ${stroke.points[i].y}")
            }
            sb.append("\" stroke=\"$hexColor\" stroke-opacity=\"$alpha\" stroke-width=\"${stroke.width}\" fill=\"none\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n")
        }
        sb.append("</svg>")
        return sb.toString()
    }

    suspend fun exportAnnotatedPage(
        context: Context,
        title: String,
        strokes: List<com.authorss81.noteflow.data.model.Stroke>,
        bgBitmap: android.graphics.Bitmap?,
        template: String,
        exportAsPdf: Boolean,
        layers: List<com.authorss81.noteflow.data.model.LayerEntity> = emptyList(),
        stickyNotes: List<CanvasStickyNote> = emptyList(),
        mediaEmbeds: List<CanvasMediaEmbed> = emptyList(),
        pageIndex: Int = 0,
        sourceFilePath: String? = null,
        exportImageFormat: ExportImageFormat = ExportImageFormat.PNG
    ): File? = withContext(Dispatchers.IO) {
        try {
            val resolvedBg = bgBitmap ?: if (!sourceFilePath.isNullOrBlank()) {
                if (sourceFilePath.lowercase().endsWith(".pdf")) {
                    renderPdfPageToBitmap(sourceFilePath, pageIndex)
                } else {
                    decodeImageSampled(sourceFilePath, maxLongEdge = 4096)
                }
            } else null

            val width = resolvedBg?.width ?: 1080
            val height = resolvedBg?.height ?: 1528
            val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)

            canvas.drawColor(android.graphics.Color.WHITE)

            if (resolvedBg == null) {
                drawTemplateBackground(canvas, template, width, height)
            } else {
                canvas.drawBitmap(resolvedBg, 0f, 0f, null)
            }

            drawEmbedsAndStickyNotesToCanvas(canvas, stickyNotes.filter { it.pdfPage == pageIndex }, mediaEmbeds.filter { it.pdfPage == pageIndex }, context)

            val inkRenderer = try {
                androidx.ink.rendering.android.canvas.CanvasStrokeRenderer.create(false)
            } catch (e: Exception) {
                null
            }

            renderLayersAndStrokesToCanvas(
                canvas = canvas,
                width = width,
                height = height,
                pageIdx = pageIndex,
                strokes = strokes,
                layers = layers,
                inkRenderer = inkRenderer
            )

            val sanitizeTitle = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "Page_Export" }
            val exportDir = File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }

            val outFile = if (exportAsPdf) {
                val pdfDoc = android.graphics.pdf.PdfDocument()
                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(width, height, 1).create()
                val pdfPage = pdfDoc.startPage(pageInfo)
                pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                pdfDoc.finishPage(pdfPage)

                val file = File(exportDir, "$sanitizeTitle.pdf")
                FileOutputStream(file).use { pdfDoc.writeTo(it) }
                pdfDoc.close()
                file
            } else {
                val file = File(exportDir, "$sanitizeTitle.${if (exportImageFormat == ExportImageFormat.WEBP) "webp" else "png"}")
                FileOutputStream(file).use { compressBitmap(exportImageFormat, bitmap, it) }
                file
            }
            bitmap.recycle()

            try {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir.exists()) {
                    val publicFile = File(downloadsDir, outFile.name)
                    outFile.copyTo(publicFile, overwrite = true)
                }
            } catch (e: Exception) {
                Log.w("ImportExportService", "Failed to copy export file to Downloads", e)
            }

            outFile
        } catch (e: Exception) {
            Log.e("ImportExportService", "Failed to export annotated page", e)
            null
        }
    }

    suspend fun exportDocumentAsPdf(
        context: Context,
        title: String,
        totalPages: Int,
        strokes: List<Stroke>,
        bgBitmaps: Map<Int, android.graphics.Bitmap> = emptyMap(),
        template: String,
        layers: List<LayerEntity> = emptyList(),
        stickyNotes: List<CanvasStickyNote> = emptyList(),
        mediaEmbeds: List<CanvasMediaEmbed> = emptyList(),
        sourceFilePath: String? = null
    ): File? = withContext(Dispatchers.IO) {
        try {
            val sanitizeTitle = title.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "NoteDocument" }
            val exportDir = File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
            val outFile = File(exportDir, "$sanitizeTitle.pdf")

            val pdfDoc = android.graphics.pdf.PdfDocument()
            val inkRenderer = try {
                androidx.ink.rendering.android.canvas.CanvasStrokeRenderer.create(false)
            } catch (e: Exception) {
                null
            }

            val count = maxOf(1, totalPages)
            for (pageIdx in 0 until count) {
                val bg = bgBitmaps[pageIdx] ?: if (!sourceFilePath.isNullOrBlank()) {
                    if (sourceFilePath.lowercase().endsWith(".pdf")) {
                        renderPdfPageToBitmap(sourceFilePath, pageIdx)
                    } else {
                        decodeImageSampled(sourceFilePath, maxLongEdge = 4096)
                    }
                } else null

                val width = bg?.width ?: 1080
                val height = bg?.height ?: 1528

                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawColor(android.graphics.Color.WHITE)

                if (bg == null) {
                    drawTemplateBackground(canvas, template, width, height)
                } else {
                    canvas.drawBitmap(bg, 0f, 0f, null)
                }

                drawEmbedsAndStickyNotesToCanvas(canvas, stickyNotes.filter { it.pdfPage == pageIdx }, mediaEmbeds.filter { it.pdfPage == pageIdx }, context)

                renderLayersAndStrokesToCanvas(
                    canvas = canvas,
                    width = width,
                    height = height,
                    pageIdx = pageIdx,
                    strokes = strokes,
                    layers = layers,
                    inkRenderer = inkRenderer
                )

                val pageInfo = android.graphics.pdf.PdfDocument.PageInfo.Builder(width, height, pageIdx + 1).create()
                val pdfPage = pdfDoc.startPage(pageInfo)
                pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                pdfDoc.finishPage(pdfPage)
                bitmap.recycle()
                // The source background is a fresh (possibly sampled) decode per
                // page; release it so multi-page exports hold only one at a time.
                bg?.recycle()
            }

            FileOutputStream(outFile).use { pdfDoc.writeTo(it) }
            pdfDoc.close()

            try {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir.exists()) {
                    val publicFile = File(downloadsDir, outFile.name)
                    outFile.copyTo(publicFile, overwrite = true)
                }
            } catch (e: Exception) {
                Log.w("ImportExportService", "Failed to copy document PDF to Downloads", e)
            }

            outFile
        } catch (e: Exception) {
            Log.e("ImportExportService", "Failed to export document as PDF", e)
            null
        }
    }

    private fun drawTemplateBackground(
        canvas: android.graphics.Canvas,
        template: String,
        width: Int,
        height: Int
    ) {
        val gridPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.LTGRAY
            strokeWidth = 2f
            style = android.graphics.Paint.Style.STROKE
            isAntiAlias = true
        }
        when (template) {
            "lined" -> {
                var y = 100f
                while (y < height) {
                    canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
                    y += 100f
                }
            }
            "grid" -> {
                var x = 80f
                while (x < width) {
                    canvas.drawLine(x, 0f, x, height.toFloat(), gridPaint)
                    x += 80f
                }
                var y = 80f
                while (y < height) {
                    canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
                    y += 80f
                }
            }
            "dots" -> {
                val dotPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.LTGRAY
                    style = android.graphics.Paint.Style.FILL
                    isAntiAlias = true
                }
                var x = 80f
                while (x < width) {
                    var y = 80f
                    while (y < height) {
                        canvas.drawCircle(x, y, 4f, dotPaint)
                        y += 80f
                    }
                    x += 80f
                }
            }
            "cornell" -> {
                val accentPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#0284C7")
                    strokeWidth = 5f
                    style = android.graphics.Paint.Style.STROKE
                    isAntiAlias = true
                }
                val headerY = 180f
                val summaryY = height - 220f
                val cueX = width * 0.30f

                canvas.drawLine(0f, headerY, width.toFloat(), headerY, accentPaint)
                canvas.drawLine(0f, summaryY, width.toFloat(), summaryY, accentPaint)
                canvas.drawLine(cueX, headerY, cueX, summaryY, accentPaint)

                var y = headerY + 60f
                while (y < summaryY) {
                    canvas.drawLine(0f, y, width.toFloat(), y, gridPaint)
                    y += 60f
                }
                var sumY = summaryY + 60f
                while (sumY < height) {
                    canvas.drawLine(0f, sumY, width.toFloat(), sumY, gridPaint)
                    sumY += 60f
                }
            }
            "meeting" -> {
                val accentPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#7E22CE")
                    strokeWidth = 4f
                    style = android.graphics.Paint.Style.STROKE
                    isAntiAlias = true
                }
                val headerY = 200f
                val splitX = width * 0.58f

                canvas.drawRect(30f, 30f, width - 30f, 170f, accentPaint)
                canvas.drawLine(splitX, headerY, splitX, height - 30f, accentPaint)

                var y = headerY + 60f
                while (y < height - 30f) {
                    canvas.drawLine(30f, y, splitX - 20f, y, gridPaint)
                    y += 60f
                }

                var ay = headerY + 60f
                while (ay < height - 30f) {
                    canvas.drawRect(splitX + 30f, ay - 24f, splitX + 54f, ay, accentPaint)
                    canvas.drawLine(splitX + 70f, ay, width - 30f, ay, gridPaint)
                    ay += 60f
                }
            }
            "todo" -> {
                val accentPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#059669")
                    strokeWidth = 5f
                    style = android.graphics.Paint.Style.STROKE
                    isAntiAlias = true
                }
                val topY = 120f
                val bottomY = height - 240f
                val col2X = width * 0.5f

                canvas.drawLine(30f, topY, width - 30f, topY, accentPaint)
                canvas.drawLine(col2X, topY, col2X, bottomY, accentPaint)
                canvas.drawLine(30f, bottomY, width - 30f, bottomY, accentPaint)

                var y1 = topY + 70f
                while (y1 < bottomY) {
                    canvas.drawRect(40f, y1 - 24f, 68f, y1, accentPaint)
                    canvas.drawLine(80f, y1, col2X - 20f, y1, gridPaint)
                    y1 += 70f
                }

                var y2 = topY + 70f
                while (y2 < bottomY) {
                    canvas.drawRect(col2X + 30f, y2 - 24f, col2X + 58f, y2, accentPaint)
                    canvas.drawLine(col2X + 70f, y2, width - 40f, y2, gridPaint)
                    y2 += 70f
                }

                var ny = bottomY + 70f
                while (ny < height) {
                    canvas.drawLine(40f, ny, width - 40f, ny, gridPaint)
                    ny += 70f
                }
            }

        }
    }

    private fun drawEmbedsAndStickyNotesToCanvas(
        canvas: android.graphics.Canvas,
        stickyNotes: List<CanvasStickyNote>,
        mediaEmbeds: List<CanvasMediaEmbed>,
        context: Context
    ) {
        for (embed in mediaEmbeds) {
            if (embed.type == MediaEmbedType.PHOTO && embed.contentUrlOrPath != null) {
                val file = File(embed.contentUrlOrPath)
                if (file.exists()) {
                    val bmp = decodeImageSampled(file.absolutePath, maxLongEdge = 2048)
                    if (bmp != null) {
                        val rect = android.graphics.RectF(
                            embed.x,
                            embed.y,
                            embed.x + embed.width,
                            embed.y + embed.height
                        )
                        canvas.drawBitmap(bmp, null, rect, null)
                        bmp.recycle()
                    }
                }
            }
        }

        for (note in stickyNotes) {
            val notePaint = android.graphics.Paint().apply {
                color = try {
                    android.graphics.Color.parseColor(note.colorHex)
                } catch (e: Exception) {
                    android.graphics.Color.YELLOW
                }
                style = android.graphics.Paint.Style.FILL
            }
            val rect = android.graphics.RectF(
                note.x,
                note.y,
                note.x + note.width,
                note.y + note.height
            )
            canvas.drawRoundRect(rect, 12f, 12f, notePaint)

            if (note.text.isNotBlank()) {
                val textPaint = android.graphics.Paint().apply {
                    color = android.graphics.Color.BLACK
                    textSize = 32f
                    isAntiAlias = true
                }
                val padding = 16f
                canvas.drawText(note.text, note.x + padding, note.y + padding + 32f, textPaint)
            }
        }
    }

    private fun renderLayersAndStrokesToCanvas(
        canvas: android.graphics.Canvas,
        width: Int,
        height: Int,
        pageIdx: Int,
        strokes: List<com.authorss81.noteflow.data.model.Stroke>,
        layers: List<com.authorss81.noteflow.data.model.LayerEntity>,
        inkRenderer: androidx.ink.rendering.android.canvas.CanvasStrokeRenderer?
    ) {
        val pageStrokes = strokes.filter { it.pdfPage == pageIdx }
        if (pageStrokes.isEmpty()) return

        val resolvedLayers = if (layers.isEmpty()) {
            listOf(
                com.authorss81.noteflow.data.model.LayerEntity(
                    id = "layer_default",
                    pageId = "",
                    name = "Layer 1",
                    zOrder = 0,
                    opacity = 1f,
                    blendMode = "NORMAL",
                    visible = true,
                    locked = false
                )
            )
        } else {
            layers.sortedBy { it.zOrder }
        }

        for (layer in resolvedLayers) {
            if (!layer.visible) continue

            val layerStrokes = pageStrokes.filter { (it.layerId ?: "layer_default") == layer.id }
            if (layerStrokes.isEmpty()) continue

            val layerBmp = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val layerCanvas = android.graphics.Canvas(layerBmp)

            for (stroke in layerStrokes) {
                drawSingleStrokeToCanvas(layerCanvas, stroke, inkRenderer)
            }

            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                alpha = (layer.opacity * 255).toInt()
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                paint.blendMode = when (layer.blendMode.uppercase()) {
                    "NORMAL" -> android.graphics.BlendMode.SRC_OVER
                    "MULTIPLY" -> android.graphics.BlendMode.MULTIPLY
                    "SCREEN" -> android.graphics.BlendMode.SCREEN
                    "OVERLAY" -> android.graphics.BlendMode.OVERLAY
                    "DARKEN" -> android.graphics.BlendMode.DARKEN
                    "LIGHTEN" -> android.graphics.BlendMode.LIGHTEN
                    "COLOR_DODGE" -> android.graphics.BlendMode.COLOR_DODGE
                    "COLOR_BURN" -> android.graphics.BlendMode.COLOR_BURN
                    "HARD_LIGHT" -> android.graphics.BlendMode.HARD_LIGHT
                    "SOFT_LIGHT" -> android.graphics.BlendMode.SOFT_LIGHT
                    "DIFFERENCE" -> android.graphics.BlendMode.DIFFERENCE
                    "EXCLUSION" -> android.graphics.BlendMode.EXCLUSION
                    else -> android.graphics.BlendMode.SRC_OVER
                }
            } else {
                val pMode = when (layer.blendMode.uppercase()) {
                    "NORMAL" -> android.graphics.PorterDuff.Mode.SRC_OVER
                    "MULTIPLY" -> android.graphics.PorterDuff.Mode.MULTIPLY
                    "SCREEN" -> android.graphics.PorterDuff.Mode.SCREEN
                    "DARKEN" -> android.graphics.PorterDuff.Mode.DARKEN
                    "LIGHTEN" -> android.graphics.PorterDuff.Mode.LIGHTEN
                    else -> android.graphics.PorterDuff.Mode.SRC_OVER
                }
                paint.xfermode = android.graphics.PorterDuffXfermode(pMode)
            }

            canvas.drawBitmap(layerBmp, 0f, 0f, paint)
            layerBmp.recycle()
        }
    }

    private fun drawSingleStrokeToCanvas(
        canvas: android.graphics.Canvas,
        stroke: com.authorss81.noteflow.data.model.Stroke,
        inkRenderer: androidx.ink.rendering.android.canvas.CanvasStrokeRenderer?
    ) {
        if (stroke.isAdvanced && inkRenderer != null) {
            val inkStroke = convertToInkStroke(stroke)
            if (inkStroke != null) {
                inkRenderer.draw(canvas, inkStroke, android.graphics.Matrix())
                return
            }
        }

        val basePaint = android.graphics.Paint().apply {
            color = stroke.colorInt
            strokeWidth = stroke.width
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            isAntiAlias = true
        }

        when (stroke.tool) {
            com.authorss81.noteflow.data.model.StrokeTool.PEN,
            com.authorss81.noteflow.data.model.StrokeTool.CALLIGRAPHIC -> {
                if (stroke.points.size > 1) {
                    val pts = stroke.points
                    val path = android.graphics.Path()
                    path.moveTo(pts[0].x, pts[0].y)
                    if (pts.size == 2) {
                        path.lineTo(pts[1].x, pts[1].y)
                    } else {
                        val firstMidX = (pts[0].x + pts[1].x) / 2f
                        val firstMidY = (pts[0].y + pts[1].y) / 2f
                        path.lineTo(firstMidX, firstMidY)
                        for (i in 1 until pts.size - 1) {
                            val p1 = pts[i]
                            val p2 = pts[i + 1]
                            val midX = (p1.x + p2.x) / 2f
                            val midY = (p1.y + p2.y) / 2f
                            path.quadTo(p1.x, p1.y, midX, midY)
                        }
                        path.lineTo(pts.last().x, pts.last().y)
                    }
                    canvas.drawPath(path, basePaint)
                } else if (stroke.points.size == 1) {
                    canvas.drawCircle(stroke.points[0].x, stroke.points[0].y, stroke.width / 2f, basePaint.apply { style = android.graphics.Paint.Style.FILL })
                }
            }
            com.authorss81.noteflow.data.model.StrokeTool.HIGHLIGHTER -> {
                val paint = android.graphics.Paint(basePaint).apply {
                    alpha = 90
                }
                if (stroke.points.size > 1) {
                    val pts = stroke.points
                    val path = android.graphics.Path()
                    path.moveTo(pts[0].x, pts[0].y)
                    if (pts.size == 2) {
                        path.lineTo(pts[1].x, pts[1].y)
                    } else {
                        val firstMidX = (pts[0].x + pts[1].x) / 2f
                        val firstMidY = (pts[0].y + pts[1].y) / 2f
                        path.lineTo(firstMidX, firstMidY)
                        for (i in 1 until pts.size - 1) {
                            val p1 = pts[i]
                            val p2 = pts[i + 1]
                            val midX = (p1.x + p2.x) / 2f
                            val midY = (p1.y + p2.y) / 2f
                            path.quadTo(p1.x, p1.y, midX, midY)
                        }
                        path.lineTo(pts.last().x, pts.last().y)
                    }
                    canvas.drawPath(path, paint)
                } else if (stroke.points.size == 1) {
                    canvas.drawCircle(stroke.points[0].x, stroke.points[0].y, stroke.width / 2f, paint.apply { style = android.graphics.Paint.Style.FILL })
                }
            }
            com.authorss81.noteflow.data.model.StrokeTool.FOUNTAIN_PEN -> {
                val paint = android.graphics.Paint(basePaint)
                if (stroke.points.size > 1) {
                    for (i in 0 until stroke.points.size - 1) {
                        val p1 = stroke.points[i]
                        val p2 = stroke.points[i + 1]
                        val dx = p2.x - p1.x
                        val dy = p2.y - p1.y
                        val dist = kotlin.math.sqrt(dx * dx + dy * dy)
                        val dynamicWidth = (stroke.width * (1.7f - (dist / 14f).coerceIn(0f, 1.1f))).coerceAtLeast(1f)
                        paint.strokeWidth = dynamicWidth
                        canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)
                    }
                } else if (stroke.points.size == 1) {
                    canvas.drawCircle(stroke.points[0].x, stroke.points[0].y, stroke.width / 2f, paint.apply { style = android.graphics.Paint.Style.FILL })
                }
            }
            com.authorss81.noteflow.data.model.StrokeTool.PENCIL -> {
                val paint = android.graphics.Paint(basePaint).apply {
                    alpha = 190
                }
                if (stroke.points.size > 1) {
                    val path = android.graphics.Path()
                    path.moveTo(stroke.points[0].x, stroke.points[0].y)
                    for (i in 1 until stroke.points.size) {
                        path.lineTo(stroke.points[i].x, stroke.points[i].y)
                    }
                    canvas.drawPath(path, paint)
                    val grainPaint = android.graphics.Paint(paint).apply {
                        alpha = 75
                        style = android.graphics.Paint.Style.FILL
                    }
                    for (i in stroke.points.indices step 2) {
                        val p = stroke.points[i]
                        val hash = (p.x * 1000 + p.y * 100).toInt()
                        val jx = ((hash % 7) - 3) * 0.4f
                        val jy = (((hash / 7) % 7) - 3) * 0.4f
                        canvas.drawCircle(p.x + jx, p.y + jy, (stroke.width * 0.6f).coerceAtLeast(1f), grainPaint)
                    }
                } else if (stroke.points.size == 1) {
                    canvas.drawCircle(stroke.points[0].x, stroke.points[0].y, stroke.width / 2f, paint.apply { style = android.graphics.Paint.Style.FILL })
                }
            }
            com.authorss81.noteflow.data.model.StrokeTool.AIRBRUSH -> {
                val paint = android.graphics.Paint(basePaint).apply {
                    alpha = 70
                    style = android.graphics.Paint.Style.FILL
                }
                val radius = stroke.width * 2.2f
                for (p in stroke.points) {
                    val seed = (p.x * 1000 + p.y).toInt()
                    val particleCount = 12
                    for (k in 0 until particleCount) {
                        val angle = ((seed + k * 137) % 360) * Math.PI / 180.0
                        val dist = (((seed * 31 + k * 97) % 100) / 100f) * radius
                        val px = p.x + (dist * kotlin.math.cos(angle)).toFloat()
                        val py = p.y + (dist * kotlin.math.sin(angle)).toFloat()
                        canvas.drawCircle(px, py, (1.2f + (k % 3) * 0.5f), paint)
                    }
                }
            }
            com.authorss81.noteflow.data.model.StrokeTool.OIL_PAINT -> {
                if (stroke.points.size > 1) {
                    val pts = stroke.points
                    val path = android.graphics.Path()
                    path.moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) {
                        val midX = (pts[i - 1].x + pts[i].x) / 2f
                        val midY = (pts[i - 1].y + pts[i].y) / 2f
                        path.quadTo(pts[i - 1].x, pts[i - 1].y, midX, midY)
                    }
                    path.lineTo(pts.last().x, pts.last().y)

                    val paintMain = android.graphics.Paint(basePaint).apply {
                        color = stroke.colorInt
                        strokeWidth = stroke.width * 1.3f
                        style = android.graphics.Paint.Style.STROKE
                        strokeCap = android.graphics.Paint.Cap.ROUND
                        strokeJoin = android.graphics.Paint.Join.ROUND
                    }
                    canvas.drawPath(path, paintMain)

                    val bristleOffset = stroke.width * 0.22f
                    val bristlePath1 = android.graphics.Path()
                    bristlePath1.moveTo(pts[0].x - bristleOffset, pts[0].y - bristleOffset)
                    for (i in 1 until pts.size) {
                        bristlePath1.lineTo(pts[i].x - bristleOffset, pts[i].y - bristleOffset)
                    }
                    val paintHl = android.graphics.Paint(paintMain).apply {
                        color = android.graphics.Color.WHITE
                        alpha = 75
                        strokeWidth = stroke.width * 0.35f
                    }
                    canvas.drawPath(bristlePath1, paintHl)

                    val bristlePath2 = android.graphics.Path()
                    bristlePath2.moveTo(pts[0].x + bristleOffset, pts[0].y + bristleOffset)
                    for (i in 1 until pts.size) {
                        bristlePath2.lineTo(pts[i].x + bristleOffset, pts[i].y + bristleOffset)
                    }
                    val paintSh = android.graphics.Paint(paintMain).apply {
                        color = android.graphics.Color.BLACK
                        alpha = 55
                        strokeWidth = stroke.width * 0.30f
                    }
                    canvas.drawPath(bristlePath2, paintSh)
                } else if (stroke.points.size == 1) {
                    val pt = stroke.points.first()
                    canvas.drawCircle(pt.x, pt.y, stroke.width * 0.7f, basePaint)
                }
            }
            com.authorss81.noteflow.data.model.StrokeTool.WATERCOLOR -> {
                if (stroke.points.size > 1) {
                    val pts = stroke.points
                    val path = android.graphics.Path()
                    path.moveTo(pts[0].x, pts[0].y)
                    for (i in 1 until pts.size) {
                        val midX = (pts[i - 1].x + pts[i].x) / 2f
                        val midY = (pts[i - 1].y + pts[i].y) / 2f
                        path.quadTo(pts[i - 1].x, pts[i - 1].y, midX, midY)
                    }
                    path.lineTo(pts.last().x, pts.last().y)

                    val paintWash = android.graphics.Paint(basePaint).apply {
                        color = stroke.colorInt
                        alpha = 50
                        strokeWidth = stroke.width * 2.0f
                        style = android.graphics.Paint.Style.STROKE
                        strokeCap = android.graphics.Paint.Cap.ROUND
                        strokeJoin = android.graphics.Paint.Join.ROUND
                    }
                    canvas.drawPath(path, paintWash)

                    val paintBody = android.graphics.Paint(paintWash).apply {
                        alpha = 110
                        strokeWidth = stroke.width * 1.3f
                    }
                    canvas.drawPath(path, paintBody)

                    val paintEdge = android.graphics.Paint(paintWash).apply {
                        alpha = 180
                        strokeWidth = stroke.width * 0.45f
                    }
                    canvas.drawPath(path, paintEdge)
                } else if (stroke.points.size == 1) {
                    val pt = stroke.points.first()
                    val p = android.graphics.Paint(basePaint).apply { alpha = 100 }
                    canvas.drawCircle(pt.x, pt.y, stroke.width * 0.8f, p)
                }
            }
            com.authorss81.noteflow.data.model.StrokeTool.MARKER -> {
                val paint = android.graphics.Paint().apply {
                    color = stroke.colorInt
                    alpha = 30
                    style = android.graphics.Paint.Style.FILL
                    isAntiAlias = true
                    maskFilter = android.graphics.BlurMaskFilter(stroke.width * 0.8f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                }
                for (p in stroke.points) {
                    canvas.drawCircle(p.x, p.y, stroke.width, paint)

                    val seed = (p.x * 1234 + p.y * 567).toInt()
                    val bleedsCount = 3
                    val bleedPaint = android.graphics.Paint(paint).apply {
                        alpha = 10
                        maskFilter = android.graphics.BlurMaskFilter(stroke.width * 1.5f, android.graphics.BlurMaskFilter.Blur.NORMAL)
                    }
                    for (k in 0 until bleedsCount) {
                        val angle = ((seed + k * 120) % 360) * Math.PI / 180.0
                        val dist = (((seed * 17 + k * 23) % 100) / 100f) * (stroke.width * 0.6f)
                        val px = p.x + (dist * kotlin.math.cos(angle)).toFloat()
                        val py = p.y + (dist * kotlin.math.sin(angle)).toFloat()
                        canvas.drawCircle(px, py, stroke.width * 0.9f, bleedPaint)
                    }
                }
            }
            com.authorss81.noteflow.data.model.StrokeTool.LINE -> {
                if (stroke.start != null && stroke.end != null) {
                    canvas.drawLine(stroke.start.x, stroke.start.y, stroke.end.x, stroke.end.y, basePaint)
                }
            }
            com.authorss81.noteflow.data.model.StrokeTool.RECTANGLE -> {
                if (stroke.start != null && stroke.end != null) {
                    val left = minOf(stroke.start.x, stroke.end.x)
                    val top = minOf(stroke.start.y, stroke.end.y)
                    val right = maxOf(stroke.start.x, stroke.end.x)
                    val bottom = maxOf(stroke.start.y, stroke.end.y)
                    canvas.drawRect(left, top, right, bottom, basePaint)
                }
            }
            com.authorss81.noteflow.data.model.StrokeTool.ELLIPSE -> {
                if (stroke.start != null && stroke.end != null) {
                    val left = minOf(stroke.start.x, stroke.end.x)
                    val top = minOf(stroke.start.y, stroke.end.y)
                    val right = maxOf(stroke.start.x, stroke.end.x)
                    val bottom = maxOf(stroke.start.y, stroke.end.y)
                    canvas.drawOval(left, top, right, bottom, basePaint)
                }
            }
            com.authorss81.noteflow.data.model.StrokeTool.ARROW -> {
                if (stroke.start != null && stroke.end != null) {
                    val p1x = stroke.start.x
                    val p1y = stroke.start.y
                    val p2x = stroke.end.x
                    val p2y = stroke.end.y
                    canvas.drawLine(p1x, p1y, p2x, p2y, basePaint)

                    val angle = kotlin.math.atan2(p2y - p1y, p2x - p1x)
                    val arrowSize = 24f
                    val arrowAngle = Math.toRadians(30.0)

                    val x1 = p2x - arrowSize * kotlin.math.cos(angle - arrowAngle).toFloat()
                    val y1 = p2y - arrowSize * kotlin.math.sin(angle - arrowAngle).toFloat()
                    val x2 = p2x - arrowSize * kotlin.math.cos(angle + arrowAngle).toFloat()
                    val y2 = p2y - arrowSize * kotlin.math.sin(angle + arrowAngle).toFloat()

                    canvas.drawLine(p2x, p2y, x1, y1, basePaint)
                    canvas.drawLine(p2x, p2y, x2, y2, basePaint)
                }
            }
            com.authorss81.noteflow.data.model.StrokeTool.TEXT -> {
                if (stroke.start != null && stroke.text.isNotBlank()) {
                    val parsedTextResult = com.authorss81.noteflow.data.model.CanvasTextStyle.parse(stroke.text)
                    val textStyle = parsedTextResult.first
                    val plainText = parsedTextResult.second
                    val lines = plainText.split("\n")
                    val textPaint = android.graphics.Paint().apply {
                        color = stroke.colorInt
                        textSize = textStyle.fontSizeSp * 1.5f
                        isAntiAlias = true
                        typeface = when (textStyle.fontStyle) {
                            "SERIF" -> android.graphics.Typeface.SERIF
                            "MONO" -> android.graphics.Typeface.MONOSPACE
                            "BOLD" -> android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
                            "ITALIC" -> android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.ITALIC)
                            "SCRIPT" -> android.graphics.Typeface.create("sans-serif-cursive", android.graphics.Typeface.NORMAL)
                            else -> android.graphics.Typeface.SANS_SERIF
                        }
                        textAlign = when (textStyle.align) {
                            "CENTER" -> android.graphics.Paint.Align.CENTER
                            "RIGHT" -> android.graphics.Paint.Align.RIGHT
                            else -> android.graphics.Paint.Align.LEFT
                        }
                    }
                    val lineHeight = textPaint.textSize * 1.25f
                    val startX = stroke.start.x
                    val startY = stroke.start.y

                    if (!textStyle.bgHex.isNullOrBlank()) {
                        val maxLineWidth = lines.maxOfOrNull { textPaint.measureText(it) } ?: 50f
                        val bgWidth = maxLineWidth + 24f
                        val bgHeight = lines.size * lineHeight + 12f
                        val bgPaint = android.graphics.Paint().apply {
                            color = try { android.graphics.Color.parseColor(textStyle.bgHex) } catch (e: Exception) { android.graphics.Color.YELLOW }
                            style = android.graphics.Paint.Style.FILL
                        }
                        val leftX = when (textStyle.align) {
                            "CENTER" -> startX - bgWidth / 2f
                            "RIGHT" -> startX - bgWidth + 12f
                            else -> startX - 12f
                        }
                        val bgRect = android.graphics.RectF(leftX, startY - textPaint.textSize, leftX + bgWidth, startY - textPaint.textSize + bgHeight)
                        canvas.drawRoundRect(bgRect, 16f, 16f, bgPaint)
                    }

                    lines.forEachIndexed { i, line ->
                        canvas.drawText(line, startX, startY + (i * lineHeight), textPaint)
                    }
                }
            }
            else -> {}
        }
    }

    fun getUriFileName(context: Context, uri: Uri): String {
        var name = ""
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        val displayName = cursor.getString(nameIndex)
                        if (!displayName.isNullOrBlank()) {
                            name = displayName
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
        if (name.isBlank()) {
            val lastSegment = uri.lastPathSegment
            if (!lastSegment.isNullOrBlank()) {
                name = lastSegment.substringAfterLast('/')
            }
        }
        if (name.isBlank()) {
            name = "imported_doc"
        }
        val ext = extensionOf(name)
        if (ext.isBlank()) {
            val mimeType = context.contentResolver.getType(uri)
            if (mimeType != null) {
                val resolvedExt = when {
                    mimeType == "application/pdf" -> "pdf"
                    mimeType.startsWith("image/") -> {
                        val sub = mimeType.substringAfter("/")
                        if (sub in listOf("png", "jpg", "jpeg", "webp")) sub else "png"
                    }
                    mimeType == "text/plain" -> "txt"
                    mimeType == "text/markdown" -> "md"
                    mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
                    else -> ""
                }
                if (resolvedExt.isNotEmpty()) {
                    name = if (name.endsWith(".")) "$name$resolvedExt" else "$name.$resolvedExt"
                }
            }
        }
        return name
    }

    /**
     * Converts DOCX bytes to Markdown by parsing `word/document.xml` inside the DOCX ZIP archive.
     */
    suspend fun convertDocxToMarkdown(docxBytes: ByteArray): String = withContext(Dispatchers.Default) {
        try {
            val sb = StringBuilder()
            ZipInputStream(ByteArrayInputStream(docxBytes)).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    if (entry.name == "word/document.xml") {
                        val factory = XmlPullParserFactory.newInstance()
                        factory.isNamespaceAware = true
                        val parser = factory.newPullParser()
                        parser.setInput(zis, "UTF-8")

                        var eventType = parser.eventType
                        var inParagraph = false
                        val currentLine = StringBuilder()

                        while (eventType != XmlPullParser.END_DOCUMENT) {
                            val name = parser.name
                            when (eventType) {
                                XmlPullParser.START_TAG -> {
                                    if (name == "p") {
                                        inParagraph = true
                                        currentLine.clear()
                                    } else if (name == "t") {
                                        currentLine.append(parser.nextText())
                                    }
                                }
                                XmlPullParser.END_TAG -> {
                                    if (name == "p") {
                                        if (currentLine.isNotBlank()) {
                                            sb.append(currentLine.toString()).append("\n\n")
                                        }
                                        inParagraph = false
                                    }
                                }
                            }
                            eventType = parser.next()
                        }
                        break
                    }
                    entry = zis.nextEntry
                }
            }
            if (sb.isBlank()) "# Imported Document\n\n(No text content found in document)" else sb.toString()
        } catch (e: Exception) {
            Log.e("ImportExportService", "Failed to parse docx", e)
            "# Document Import Error\n\nFailed to convert DOCX file."
        }
    }

    fun safeImportRelativePath(raw: String): String? {
        val normalized = raw.replace('\\', '/')
        if (normalized.isEmpty() || normalized.startsWith('/') || normalized.contains("\u0000")) return null
        if (Regex("^[a-zA-Z]:").containsMatchIn(normalized)) return null

        val segments = normalized.split('/')
        for (segment in segments) {
            if (segment.isEmpty() || segment == "..") return null
        }
        return segments.filter { it != "." }.joinToString(File.separator)
    }

    private const val BACKUP_MAGIC = "NFLB2"
    private const val BACKUP_SALT_SIZE = 16
    private const val BACKUP_IV_SIZE = 12
    // EncryptionService.encrypt/encryptAad output: 1 version byte + 12-byte IV + 32-byte
    // ciphertext + 16-byte tag.
    private const val BACKUP_WRAPPED_DEK_SIZE = 61
    private const val MAX_BACKUP_INPUT_BYTES = 400L * 1024 * 1024 // hard cap before any decrypt/decompress

    // B2-CRYPTO-03: domain separation for the two KEK uses in backup v2. The DEK
    // wrap and the zip-payload GCM now authenticate DIFFERENT AAD domains, so a
    // ciphertext produced in one role can never be accepted in the other even if
    // a salt+IV pair is ever reused.
    //
    // Deliberate asymmetry: the wrap domain is a bare constant whose salt/header
    // binding is TRANSITIVE — the salt is bound through KEK derivation, and the
    // payload tag authenticates the whole header (magic|salt|iv|wrappedDek). The
    // payload domain intentionally binds the header bytes in addition to its own
    // constant, because the payload tag is what finally ties the ciphertext to
    // its own IV/DEK/header.
    internal val BACKUP_DEK_WRAP_AAD: ByteArray = "backup/dek-wrap".toByteArray(Charsets.UTF_8)
    internal val BACKUP_PAYLOAD_AAD: ByteArray = "backup/payload".toByteArray(Charsets.UTF_8)

    /** Serialized v2 header: [magic "NFLB2"][salt][payloadIv][wrappedDek]. */
    internal fun buildBackupHeader(salt: ByteArray, payloadIv: ByteArray, wrappedDek: ByteArray): ByteArray {
        val magic = BACKUP_MAGIC.toByteArray(Charsets.US_ASCII)
        return ByteArray(magic.size + salt.size + payloadIv.size + wrappedDek.size).also { out ->
            var off = 0
            System.arraycopy(magic, 0, out, off, magic.size); off += magic.size
            System.arraycopy(salt, 0, out, off, salt.size); off += salt.size
            System.arraycopy(payloadIv, 0, out, off, payloadIv.size); off += payloadIv.size
            System.arraycopy(wrappedDek, 0, out, off, wrappedDek.size)
        }
    }

    /**
     * B2-CRYPTO-03 fix: the payload GCM authenticates the whole header
     * (magic|salt|payloadIv|wrappedDek) under the 'backup/payload' domain AAD,
     * binding every payload to its own header. Splice another export's header
     * onto a payload and the tag fails.
     */
    internal fun encryptBackupPayload(zipData: ByteArray, kek: ByteArray, payloadIv: ByteArray, header: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.ENCRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(kek, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, payloadIv)
        )
        cipher.updateAAD(BACKUP_PAYLOAD_AAD)
        cipher.updateAAD(header)
        return cipher.doFinal(zipData)
    }

    /**
     * Inverse of [encryptBackupPayload]. Tries the bounded decrypt first
     * (BACKUP_PAYLOAD_AAD + the exact header); on a tag mismatch it retries the
     * legacy zero-AAD decrypt so backups exported before the B2-CRYPTO-03 binding
     * still restore. The retry only ever rescues a genuinely pre-fix payload: a
     * spliced or corrupt NEW-format payload's tag was computed over the
     * AAD+header, so the zero-AAD retry cannot verify it, and a wrong password
     * fails both attempts (the GCM tag also covers the key). Every mismatch is
     * therefore rejected on both paths — the retry is never a way around the
     * binding.
     */
    internal fun decryptBackupPayload(cipherText: ByteArray, kek: ByteArray, payloadIv: ByteArray, header: ByteArray): ByteArray {
        try {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(kek, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, payloadIv)
            )
            cipher.updateAAD(BACKUP_PAYLOAD_AAD)
            cipher.updateAAD(header)
            return cipher.doFinal(cipherText)
        } catch (e: javax.crypto.AEADBadTagException) {
            val legacy = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            legacy.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(kek, "AES"),
                javax.crypto.spec.GCMParameterSpec(128, payloadIv)
            )
            return legacy.doFinal(cipherText)
        }
    }

    /**
     * C1: every column that carries DEK-based field ciphertext (i.e. every
     * column the decrypt path reads on load). migrateFieldCiphertexts re-keys
     * exactly these on a cross-device restore; missing one leaves that column
     * under the old DEK and the decrypt fallback silently returns raw
     * ciphertext — stroke geometry, titles and version text would all vanish.
     */
    internal val fieldEncryptedColumns: Map<String, List<String>> = mapOf(
        "pages" to listOf("title", "extractedText"),
        "strokes" to listOf("textContent", "pointsJson"),
        "media_embeds" to listOf("textContent"),
        "note_versions" to listOf("title", "extractedText")
    )

    /**
     * C1: re-keys a single field-ciphertext value from the backup DEK to the
     * current DEK. Returns null (leave the value alone) when the value is
     * plaintext, blank, or already keyed with the new DEK.
     */
    internal fun reencryptFieldValue(value: String?, oldDek: ByteArray, newDek: ByteArray): String? {
        if (value.isNullOrBlank()) return null
        return try {
            val plain = EncryptionService.decrypt(value, oldDek)
            EncryptionService.encrypt(plain, newDek)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * H3: rejects a backup whose SQLCipher schema (PRAGMA user_version) is newer
     * than the app's Room schema. Older backups are allowed so Room migrations
     * can run forward; a newer schema would later be destroyed by
     * fallbackToDestructiveMigration. Checked BEFORE files are swapped.
     */
    internal fun checkRestoredSchemaNotNewer(userVersion: Long, currentSchemaVersion: Int) {
        if (userVersion > currentSchemaVersion) {
            throw IllegalStateException(
                "Restore rejected: this backup was created by a newer version of the app " +
                    "(database schema $userVersion, this app supports $currentSchemaVersion). " +
                    "Update the app first, then restore."
            )
        }
    }

    /**
     * Backup format v2 (password-derived, portable):
     * [magic "NFLB2"][16B salt][12B iv][wrapped DEK (AES-GCM by KEK)][AES-GCM-encrypted zip payload]
     *
     * The KEK is PBKDF2(password, salt). The DEK travels inside the backup so the
     * SQLCipher database can be re-keyed to the restoring device's key.
     * Legacy backups (plain zip, or zip AES-GCM-encrypted with the device DEK)
     * remain importable through importBackup's fallback paths.
     */
    suspend fun exportBackup(context: Context, key: ByteArray?, backupPassword: String? = null): File = withContext(Dispatchers.IO) {
        val dbFile = context.getDatabasePath("noteflow.sqlite")
        val importsDir = getImportsDir(context)

        val tempBackupFile = File(context.cacheDir, "noteflow_backup_${System.currentTimeMillis()}.noteflow")
        val zipData: ByteArray = ByteArrayOutputStream().use { baos ->
            ZipOutputStream(baos).use { zos ->
                if (dbFile.exists()) {
                    zos.putNextEntry(ZipEntry("noteflow.sqlite"))
                    FileInputStream(dbFile).use { fis -> fis.copyTo(zos) }
                    zos.closeEntry()
                }

                if (importsDir.exists()) {
                    importsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                        val relativePath = "imports/" + file.relativeTo(importsDir).path.replace('\\', '/')
                        zos.putNextEntry(ZipEntry(relativePath))
                        FileInputStream(file).use { fis -> fis.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            baos.toByteArray()
        }

        if (backupPassword != null && key != null) {
            // v2: password-derived portable backup carrying the wrapped DEK.
            require(backupPassword.length >= 6) { "Backup password must be at least 6 characters" }
            val salt = EncryptionService.generateSalt()
            val kek = EncryptionService.deriveKey(backupPassword, salt)
            try {
                val wrappedDek = EncryptionService.encryptAad(key, kek, BACKUP_DEK_WRAP_AAD)
                val payloadIv = ByteArray(BACKUP_IV_SIZE)
                java.security.SecureRandom().nextBytes(payloadIv)
                val header = buildBackupHeader(salt, payloadIv, wrappedDek)
                val cipherText = encryptBackupPayload(zipData, kek, payloadIv, header)

                FileOutputStream(tempBackupFile).use { fos ->
                    fos.write(header)
                    fos.write(cipherText)
                }
            } finally {
                kek.fill(0.toByte())
            }
        } else {
            // H4: a backup must never silently be a plain zip containing
            // journal/voice/image files. It is either password-encrypted (v2
            // NFLB2 above) or device-keyed (legacy) — no unencrypted fallback.
            require(key != null) {
                "Backup rejected: no encryption key is available and no backup password was provided. Unlock the vault before exporting."
            }
            val encryptedBase64 = EncryptionService.encrypt(zipData, key)
            FileOutputStream(tempBackupFile).use { fos -> fos.write(encryptedBase64.toByteArray(Charsets.UTF_8)) }
        }
        tempBackupFile
    }

    private fun copyWithLimit(
        zis: ZipInputStream,
        fos: FileOutputStream,
        maxBytes: Long,
        totalWrittenSoFar: Long,
        maxTotalBytes: Long,
        entry: ZipEntry
    ): Long {
        val buffer = ByteArray(8192)
        var entryBytes = 0L
        var bytesRead: Int
        val maxExpansionRatio = 100L
        val ratioFloor = 4 * 1024L // don't trigger on tiny entries
        while (zis.read(buffer).also { bytesRead = it } != -1) {
            entryBytes += bytesRead
            if (entryBytes > maxBytes) {
                throw IllegalStateException("Single file extraction limit exceeded (max 50MB).")
            }
            // B5/34.5: declared compressedSize can be forged (data-descriptor/zip64) —
            // the ACTUAL bytes read (entryBytes) are the source of truth for the
            // ratio, and both declared size and declared compressedSize are cross-checked.
            val declaredUncompressed = entry.size
            val declaredCompressed = entry.compressedSize
            val ratioTriggered = when {
                declaredUncompressed > 0 && entryBytes > ratioFloor && entryBytes > declaredUncompressed * maxExpansionRatio -> true
                declaredCompressed > 0 && entryBytes > ratioFloor && entryBytes > declaredCompressed * maxExpansionRatio -> true
                else -> false
            }
            if (ratioTriggered) {
                throw IllegalStateException("Suspicious compression ratio detected — backup rejected (possible zip bomb).")
            }
            if (totalWrittenSoFar + entryBytes > maxTotalBytes) {
                throw IllegalStateException("Total backup extraction limit exceeded (max 200MB).")
            }
            fos.write(buffer, 0, bytesRead)
        }
        return entryBytes
    }

    private data class BackupV2Payload(val zipBytes: ByteArray, val dekHex: String?, val kek: ByteArray?)

    /**
     * Payload decrypt with the B2-CRYPTO-03 diagnostics: a tag failure is
     * reported as CORRUPTION when a probe unwrap of the wrapped DEK proves the
     * password was correct (header/payload tampering or a splice), and as a
     * wrong password only when the DEK does not open either. A one-size-fits-all
     * 'Incorrect backup password' would mislead on a spliced/corrupt file.
     */
    private fun decryptBackupPayloadOrThrow(
        cipherText: ByteArray,
        kek: ByteArray,
        payloadIv: ByteArray,
        header: ByteArray,
        wrappedDek: ByteArray
    ): ByteArray {
        try {
            return decryptBackupPayload(cipherText, kek, payloadIv, header)
        } catch (e: Exception) {
            val passwordCorrect = try {
                EncryptionService.decryptAad(wrappedDek, kek, BACKUP_DEK_WRAP_AAD).also { it.fill(0.toByte()) }
                true
            } catch (t: Exception) {
                false
            }
            if (passwordCorrect) {
                throw IllegalArgumentException(
                    "Backup appears corrupted: the header and the encrypted payload do not match.", e
                )
            }
            throw IllegalArgumentException("Incorrect backup password.", e)
        }
    }

    private fun tryParseBackupV2(rawBytes: ByteArray, backupPassword: String?): BackupV2Payload? {
        val magic = BACKUP_MAGIC.toByteArray(Charsets.US_ASCII)
        val headerSize = magic.size + BACKUP_SALT_SIZE + BACKUP_IV_SIZE + BACKUP_WRAPPED_DEK_SIZE
        if (rawBytes.size <= headerSize) return null
        if (!rawBytes.copyOfRange(0, magic.size).contentEquals(magic)) return null

        val salt = rawBytes.copyOfRange(magic.size, magic.size + BACKUP_SALT_SIZE)
        val iv = rawBytes.copyOfRange(magic.size + BACKUP_SALT_SIZE, magic.size + BACKUP_SALT_SIZE + BACKUP_IV_SIZE)
        val wrappedDek = rawBytes.copyOfRange(
            magic.size + BACKUP_SALT_SIZE + BACKUP_IV_SIZE,
            headerSize
        )
        val cipherText = rawBytes.copyOfRange(headerSize, rawBytes.size)

        if (backupPassword == null) {
            throw IllegalStateException("This backup is protected by a password. Enter the backup password to restore.")
        }
        var kek: ByteArray? = null
        try {
            val derivedKek = EncryptionService.deriveKey(backupPassword, salt)
            kek = derivedKek
            val header = rawBytes.copyOfRange(0, headerSize)
            val zipBytes = decryptBackupPayloadOrThrow(cipherText, derivedKek, iv, header, wrappedDek)
            // The SAME derived KEK also unwraps the backup DEK — the restore path
            // runs a single PBKDF2 in this method instead of one pass per step.
            val dekHex = try {
                EncryptionService.decryptAad(wrappedDek, derivedKek, BACKUP_DEK_WRAP_AAD)
                    .also { it.fill(0.toByte()) }
                    .toHexString()
            } catch (e: Exception) {
                null
            }
            // KEK ownership hands off to importBackup, which zeroizes it on every
            // outcome; the failure paths below zeroize it before rethrowing.
            return BackupV2Payload(zipBytes, dekHex, derivedKek)
        } catch (e: Exception) {
            kek?.fill(0.toByte())
            throw e
        }
    }

    /**
     * H1: rejects a wrong backup password BEFORE the live vault is closed or
     * touched. The wrapped DEK in the v2 header can only be opened with the
     * correct password (GCM tag), so this is a cheap, side-effect-free check.
     * Legacy backups carry no password and are skipped (they are validated by
     * the device DEK inside importBackup).
     */
    fun validateBackupPassword(rawBytes: ByteArray, backupPassword: String?) {
        val magic = BACKUP_MAGIC.toByteArray(Charsets.US_ASCII)
        if (rawBytes.size <= magic.size) return
        if (!rawBytes.copyOfRange(0, magic.size).contentEquals(magic)) return

        if (backupPassword == null) {
            throw IllegalStateException("This backup is protected by a password. Enter the backup password to restore.")
        }
        val headerSize = magic.size + BACKUP_SALT_SIZE + BACKUP_IV_SIZE + BACKUP_WRAPPED_DEK_SIZE
        if (rawBytes.size <= headerSize) {
            throw IllegalArgumentException("Backup appears corrupted: header is truncated.")
        }
        val salt = rawBytes.copyOfRange(magic.size, magic.size + BACKUP_SALT_SIZE)
        val wrappedDek = rawBytes.copyOfRange(magic.size + BACKUP_SALT_SIZE + BACKUP_IV_SIZE, headerSize)
        var kek: ByteArray? = null
        try {
            kek = EncryptionService.deriveKey(backupPassword, salt)
            EncryptionService.decryptAad(wrappedDek, kek, BACKUP_DEK_WRAP_AAD)
        } catch (e: Exception) {
            throw IllegalArgumentException("Incorrect backup password.")
        } finally {
            kek?.fill(0.toByte())
        }
    }

    private fun rekeySqlcipherDb(context: Context, dbFile: File, oldDekHex: String, newDekHex: String) {
        if (oldDekHex == newDekHex) return
        System.loadLibrary("sqlcipher")
        val raw = net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
            dbFile, oldDekHex, null, null, null
        )
        try {
            raw.rawExecSQL("PRAGMA rekey = '$newDekHex'")
        } finally {
            raw.close()
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    suspend fun importBackup(
        context: Context,
        backupBytes: ByteArray,
        key: ByteArray?,
        backupPassword: String? = null
    ) = withContext(Dispatchers.IO) {
        var rawBytes = backupBytes

        // B5/34.5: hard cap on input size before any decryption/decompression work.
        if (rawBytes.size > MAX_BACKUP_INPUT_BYTES) {
            throw IllegalStateException("Backup file too large (max 400MB).")
        }

        // v2 password-derived format (portable across devices).
        tryParseBackupV2(rawBytes, backupPassword)?.let { v2 ->
            try {
                val currentDek = key
                    ?: throw IllegalStateException("Cannot restore: no data key available on this device.")
                val currentDekHex = currentDek.toHexString()

                if (v2.dekHex == null) {
                    // Zip decrypted but the backup's DEK did not — backup is corrupt.
                    throw IllegalStateException("Backup appears corrupted: could not unlock the backup key.")
                }

                rawBytes = v2.zipBytes
                restoreFromZip(context, rawBytes, v2.dekHex, currentDekHex)
            } finally {
                // The derived KEK is zeroized on every restore outcome (success,
                // corrupt-DEK early throw, no-data-key early throw, restore failure).
                v2.kek?.fill(0.toByte())
            }
            return@withContext
        }

        // Legacy paths: plain zip, or zip encrypted with the device DEK.
        val isPkZip = rawBytes.size >= 4 && rawBytes[0] == 'P'.code.toByte() && rawBytes[1] == 'K'.code.toByte()
        if (!isPkZip) {
            if (key == null) {
                throw IllegalStateException("This backup is encrypted. Please set and verify your Master Password first.")
            }
            val encryptedStr = String(rawBytes, Charsets.UTF_8)
            rawBytes = EncryptionService.decrypt(encryptedStr, key)
        }

        val currentDekHex = key?.toHexString()
        restoreFromZip(context, rawBytes, null, currentDekHex)
    }

    private data class RestoredEntries(val tempRoot: File, val dbFile: File, val importsDir: File)

    /**
     * 34.1: Restore is now transactional. The backup is fully extracted to a
     * temp dir, the SQLCipher database copy is integrity-checked, re-keyed and
     * field-re-encrypted BEFORE the live vault is touched. Only then are files
     * swapped into place and the HMAC baseline re-armed to the restored DB.
     */
    private fun restoreFromZip(context: Context, rawBytes: ByteArray, backupDekHex: String?, currentDekHex: String?) {
        val tempRoot = File(context.cacheDir, "restore_tmp_${System.currentTimeMillis()}")
        tempRoot.mkdirs()
        val tempDb = File(tempRoot, "noteflow.sqlite")
        val tempImports = File(tempRoot, "imports")
        try {
            extractBackupEntriesTo(rawBytes, tempDb, tempImports)
            validateAndPrepareRestoredDb(context, tempDb, backupDekHex, currentDekHex)
            // B2/34.1 + 34.8: re-arm the tamper baseline to the restored DB copy
            // BEFORE it swaps into place. A DB we cannot checksum must never
            // become the live vault — escalate to the hard restore-block.
            if (!DatabaseSecurityHelper.rearmBaselineFromFile(context, tempDb)) {
                DatabaseSecurityHelper.setRestoreBlock(context)
                throw IllegalStateException("Restore rejected: could not verify the restored database.")
            }
            commitRestoredFiles(context, tempDb, tempImports)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private fun extractBackupEntriesTo(rawBytes: ByteArray, tempDb: File, tempImports: File) {
        val maxSingleFileBytes = 50 * 1024 * 1024L // 50 MB
        val maxTotalBytes = 200 * 1024 * 1024L // 200 MB
        var totalWritten = 0L
        var sawDatabase = false

        ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val entryName = entry.name
                if (!entry.isDirectory) {
                    if (entryName == "noteflow.sqlite") {
                        sawDatabase = true
                        tempDb.parentFile?.mkdirs()
                        FileOutputStream(tempDb).use { fos ->
                            totalWritten += copyWithLimit(zis, fos, maxSingleFileBytes, totalWritten, maxTotalBytes, entry)
                        }
                    } else if (entryName.startsWith("imports/")) {
                        val relPath = safeImportRelativePath(entryName.substring("imports/".length))
                            ?: throw IllegalStateException("Backup contains unsafe relative path: $entryName")
                        val targetFile = File(tempImports, relPath)
                        targetFile.parentFile?.mkdirs()
                        FileOutputStream(targetFile).use { fos ->
                            totalWritten += copyWithLimit(zis, fos, maxSingleFileBytes, totalWritten, maxTotalBytes, entry)
                        }
                    }
                }
                entry = zis.nextEntry
            }
        }
        if (!sawDatabase) {
            throw IllegalStateException("Backup contains no noteflow.sqlite database entry.")
        }
    }

    /**
     * Opens a copy of the restored DB, runs PRAGMA integrity_check, re-keys the
     * SQLCipher layer to the current DEK and migrates field-level ciphertexts
     * (34.2) so cross-device restores never double-encrypt.
     */
    private fun validateAndPrepareRestoredDb(context: Context, tempDb: File, backupDekHex: String?, currentDekHex: String?) {
        val candidates = listOfNotNull(backupDekHex, currentDekHex, "").distinct()
        var openedWith: String? = null
        var userVersion: Long = -1L
        for (candidate in candidates) {
            try {
                System.loadLibrary("sqlcipher")
                val db = net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
                    tempDb, candidate, null, null, null
                )
                try {
                    val cursor = db.rawQuery("PRAGMA integrity_check", null)
                    val ok = cursor.moveToFirst() && cursor.getString(0) == "ok"
                    if (ok) {
                        // H3: read the schema version now, while the DB is open.
                        val versionCursor = db.rawQuery("PRAGMA user_version", null)
                        if (versionCursor.moveToFirst()) userVersion = versionCursor.getLong(0)
                        versionCursor.close()
                    }
                    cursor.close()
                    if (ok) { openedWith = candidate; break }
                } finally {
                    db.close()
                }
            } catch (e: Exception) {
                // wrong key or corrupt file — try next candidate
            }
        }
        if (openedWith == null) {
            throw IllegalStateException("Restore rejected: the backup database is corrupt or was created on a different device.")
        }
        // H3: a newer-schema backup must never swap into the live path — a later
        // fallbackToDestructiveMigration would wipe it. Kept OUTSIDE the candidate
        // loop so the rejection is not swallowed as a wrong-key retry.
        checkRestoredSchemaNotNewer(userVersion, com.authorss81.noteflow.data.db.NoteflowDatabase.SCHEMA_VERSION)

        if (currentDekHex != null && openedWith != currentDekHex) {
            rekeySqlcipherDb(context, tempDb, openedWith, currentDekHex)
            if (!openedWith.isNullOrEmpty() && openedWith != currentDekHex) {
                migrateFieldCiphertexts(context, tempDb, currentDekHex, openedWith)
            }
        }
    }

    /**
     * 34.2: field-level AES-GCM ciphertexts are keyed by the DEK bytes, not the
     * SQLCipher layer key — re-keying SQLCipher alone leaves them under the old
     * DEK, and a later reencrypt pass would double-encrypt. Decrypt each field
     * with the backup DEK and re-encrypt with the current DEK, in place.
     */
    private fun migrateFieldCiphertexts(context: Context, tempDb: File, newDekHex: String, oldDekHex: String) {
        val oldDek = oldDekHex.fromHex()
        val newDek = newDekHex.fromHex()
        try {
            System.loadLibrary("sqlcipher")
            val db = net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
                tempDb, newDekHex, null, null, null
            )
            try {
                // C1: iterate over ALL field-encrypted columns — including
                // strokes.pointsJson and note_versions.{title,extractedText} —
                // so cross-device restores never strand ciphertext under the
                // old DEK (which the read path then returns raw, losing data).
                for ((table, columns) in fieldEncryptedColumns) {
                    migrateTable(db, table, columns, oldDek, newDek)
                }
            } finally {
                db.close()
            }
        } finally {
            oldDek.fill(0.toByte())
            newDek.fill(0.toByte())
        }
    }

    private fun migrateTable(
        db: net.zetetic.database.sqlcipher.SQLiteDatabase,
        table: String,
        columns: List<String>,
        oldDek: ByteArray,
        newDek: ByteArray
    ) {
        for (column in columns) {
            val cursor = db.rawQuery("SELECT id, $column FROM $table", null)
            val idIdx = cursor.getColumnIndex("id")
            val colIdx = cursor.getColumnIndex(column)
            val updates = mutableListOf<Pair<String, String>>()
            while (cursor.moveToNext()) {
                val id = cursor.getString(idIdx)
                val value = cursor.getString(colIdx)
                val reencrypted = reencryptFieldValue(value, oldDek, newDek)
                if (reencrypted != null) updates.add(id to reencrypted)
            }
            cursor.close()
            updates.forEach { (id, newValue) ->
                db.execSQL("UPDATE $table SET $column = ? WHERE id = ?", arrayOf(newValue, id))
            }
        }
    }

    /**
     * Atomic-ish swap: live DB and imports are replaced only after validation
     * succeeded. WAL/SHM are deleted just before the swap, and the restored DB
     * is moved into place with Files.move (atomic rename on the same filesystem).
     */
    private fun commitRestoredFiles(context: Context, tempDb: File, tempImports: File) {
        val dbFile = context.getDatabasePath("noteflow.sqlite")
        val walFile = context.getDatabasePath("noteflow.sqlite-wal")
        val shmFile = context.getDatabasePath("noteflow.sqlite-shm")
        val importsDir = getImportsDir(context)

        walFile.delete()
        shmFile.delete()

        if (importsDir.exists()) importsDir.deleteRecursively()
        importsDir.mkdirs()
        if (tempImports.exists()) {
            tempImports.walkTopDown().filter { it.isFile }.forEach { file ->
                val relPath = file.relativeTo(tempImports).path.replace('\\', '/')
                val target = File(importsDir, relPath)
                target.parentFile?.mkdirs()
                file.copyTo(target, overwrite = true)
            }
        }

        java.nio.file.Files.move(
            tempDb.toPath(),
            dbFile.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
    }

    private fun String.fromHex(): ByteArray =
        ByteArray(length / 2) { i -> (substring(i * 2, i * 2 + 2).toInt(16)).toByte() }

    private fun convertToInkStroke(stroke: com.authorss81.noteflow.data.model.Stroke, context: Context? = null): androidx.ink.strokes.Stroke? {
        if (stroke.points.isEmpty()) return null
        val family = com.authorss81.noteflow.services.ProtobufBrushLoader.getBrushFamilyForTool(context, stroke.tool)
        val brush = androidx.ink.brush.Brush.createWithColorIntArgb(
            family,
            stroke.colorInt,
            stroke.width,
            0.1f
        )
        val inputBatch = androidx.ink.strokes.MutableStrokeInputBatch()
        stroke.points.forEachIndexed { index, pt ->
            val tMs = pt.timestampMs ?: (index * 5L)
            inputBatch.add(
                androidx.ink.brush.InputToolType.STYLUS,
                pt.x,
                pt.y,
                tMs,
                pt.pressure ?: 0.5f,
                pt.tilt ?: 0f,
                0f
            )
        }
        return androidx.ink.strokes.Stroke(brush, inputBatch.toImmutable())
    }

    suspend fun exportVaultToZip(
        context: Context,
        vaultTitle: String,
        pages: List<com.authorss81.noteflow.data.model.NotePageEntity>,
        repository: com.authorss81.noteflow.data.repository.NoteRepository
    ): File? = withContext(Dispatchers.IO) {
        try {
            val sanitizeVaultTitle = vaultTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "NoteFlow_Vault" }
            val exportDir = File(context.cacheDir, "vault_exports").apply { if (!exists()) mkdirs() }
            val zipFile = File(exportDir, "${sanitizeVaultTitle}_Vault.zip")

            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                val indexSb = StringBuilder()
                indexSb.append("# $vaultTitle - Vault Index\n\n")
                indexSb.append("Exported on: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}\n\n")
                indexSb.append("## Pages Included:\n\n")

                pages.forEachIndexed { idx, page ->
                    val sanitizeTitle = page.title.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "Page_${idx + 1}" }
                    val pagePrefix = "${idx + 1}_$sanitizeTitle"

                    val mdContent = buildString {
                        append("# ${page.title}\n\n")
                        append("- **Tags:** ${page.tags.ifBlank { "None" }}\n")
                        append("- **Created:** ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(page.createdAt))}\n")
                        append("- **Template:** ${page.template ?: "blank"}\n\n")
                        append("--- \n\n")
                        append(page.extractedText ?: "*No text content recorded.*")
                        append("\n\n---\n\n*Exported vector ink overlay: `${pagePrefix}_ink.png`*\n")
                        append("*Exported full page PDF: `${pagePrefix}_doc.pdf`*\n")
                    }
                    val mdEntry = ZipEntry("$pagePrefix/$pagePrefix.md")
                    zos.putNextEntry(mdEntry)
                    zos.write(mdContent.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()

                    val strokes = repository.getStrokesForPage(page.id)
                    val layers = repository.getLayersForPage(page.id)
                    val (stickyNotes, mediaEmbeds) = repository.getCanvasItemsForPage(page.id)

                    val bgBitmap = if (!page.sourceFilePath.isNullOrBlank()) {
                        decodeImageSampled(page.sourceFilePath, maxLongEdge = 4096)
                    } else null

                    val pngFile = exportAnnotatedPage(
                        context = context,
                        title = "${pagePrefix}_ink",
                        strokes = strokes,
                        bgBitmap = bgBitmap,
                        template = page.template ?: "blank",
                        exportAsPdf = false,
                        layers = layers,
                        stickyNotes = stickyNotes,
                        mediaEmbeds = mediaEmbeds
                    )
                    pngFile?.let { pFile ->
                        if (pFile.exists()) {
                            val pngEntry = ZipEntry("$pagePrefix/${pagePrefix}_ink.png")
                            zos.putNextEntry(pngEntry)
                            pFile.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }

                    val pdfFile = exportAnnotatedPage(
                        context = context,
                        title = "${pagePrefix}_doc",
                        strokes = strokes,
                        bgBitmap = bgBitmap,
                        template = page.template ?: "blank",
                        exportAsPdf = true,
                        layers = layers,
                        stickyNotes = stickyNotes,
                        mediaEmbeds = mediaEmbeds
                    )
                    pdfFile?.let { pFile ->
                        if (pFile.exists()) {
                            val pdfEntry = ZipEntry("$pagePrefix/${pagePrefix}_doc.pdf")
                            zos.putNextEntry(pdfEntry)
                            pFile.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }

                    indexSb.append("${idx + 1}. [${page.title}]($pagePrefix/$pagePrefix.md) - `${page.template ?: "blank"}`\n")
                }

                val indexEntry = ZipEntry("00_VAULT_INDEX.md")
                zos.putNextEntry(indexEntry)
                zos.write(indexSb.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            try {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir.exists()) {
                    val publicFile = File(downloadsDir, zipFile.name)
                    zipFile.copyTo(publicFile, overwrite = true)
                }
            } catch (e: Exception) {
                Log.w("ImportExportService", "Failed to copy vault ZIP to Downloads", e)
            }

            zipFile
        } catch (e: Exception) {
            Log.e("ImportExportService", "Failed to export vault to ZIP", e)
            null
        }
    }

    // --- PHASE 24: HTML IMPORT & EXPORT ---

    suspend fun importHtmlFile(
        context: Context,
        uri: Uri,
        repository: com.authorss81.noteflow.data.repository.NoteRepository,
        notebookId: String,
        sectionId: String
    ): com.authorss81.noteflow.data.model.NotePageEntity? = withContext(Dispatchers.IO) {
        try {
            val bytes = readUriBytes(context, uri) ?: return@withContext null
            val htmlContent = String(bytes, Charsets.UTF_8)
            val (title, markdown) = HtmlToMarkdownConverter.convertHtmlToMarkdown(htmlContent)

            val safeTitle = sanitizeImportFileName(title)
            val mdFileName = "${safeTitle}_${System.currentTimeMillis()}.md"
            val savedPath = persistFile(context, mdFileName, markdown.toByteArray(Charsets.UTF_8))

            val page = repository.createPage(
                sectionId = sectionId,
                title = title,
                sourceFilePath = savedPath,
                sourceFileType = "text",
                extractedText = markdown,
                tags = "imported_html"
            )
            page
        } catch (e: Exception) {
            Log.e("ImportExportService", "Failed to import HTML file", e)
            null
        }
    }

    suspend fun importHtmlZipOrFolder(
        context: Context,
        uri: Uri,
        repository: com.authorss81.noteflow.data.repository.NoteRepository,
        notebookId: String,
        sectionId: String
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            val bytes = readUriBytes(context, uri) ?: return@withContext 0
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && (entry.name.endsWith(".html", ignoreCase = true) || entry.name.endsWith(".htm", ignoreCase = true))) {
                        val htmlBytes = zis.readBytes()
                        val htmlContent = String(htmlBytes, Charsets.UTF_8)
                        val (title, markdown) = HtmlToMarkdownConverter.convertHtmlToMarkdown(htmlContent)

                        val entryName = entry.name.substringAfterLast('/').substringBeforeLast('.')
                        val finalTitle = title.ifBlank { entryName }
                        val safeTitle = sanitizeImportFileName(finalTitle)
                        val mdFileName = "${safeTitle}_${System.currentTimeMillis()}.md"
                        val savedPath = persistFile(context, mdFileName, markdown.toByteArray(Charsets.UTF_8))

                        repository.createPage(
                            sectionId = sectionId,
                            title = finalTitle,
                            sourceFilePath = savedPath,
                            sourceFileType = "text",
                            extractedText = markdown,
                            tags = "imported_html"
                        )
                        count++
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e("ImportExportService", "Failed to import HTML ZIP folder", e)
        }
        count
    }

    suspend fun exportNoteToHtml(
        context: Context,
        page: com.authorss81.noteflow.data.model.NotePageEntity,
        repository: com.authorss81.noteflow.data.repository.NoteRepository
    ): File? = withContext(Dispatchers.IO) {
        try {
            val sanitizeTitle = page.title.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "Note" }
            val exportDir = File(context.cacheDir, "html_exports").apply { if (!exists()) mkdirs() }
            val htmlFile = File(exportDir, "$sanitizeTitle.html")

            val strokes = repository.getStrokesForPage(page.id)
            val svgInk = renderPageInkToSvg(strokes)

            val bodyHtml = (page.extractedText ?: "").lines().joinToString("\n") { line ->
                when {
                    line.startsWith("# ") -> "<h1>${line.substring(2)}</h1>"
                    line.startsWith("## ") -> "<h2>${line.substring(3)}</h2>"
                    line.startsWith("### ") -> "<h3>${line.substring(4)}</h3>"
                    line.startsWith("- ") -> "<li>${line.substring(2)}</li>"
                    line.isBlank() -> "<br/>"
                    else -> "<p>$line</p>"
                }
            }

            val htmlDocument = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta charset="UTF-8">
                    <title>${page.title}</title>
                    <style>
                        body { font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; margin: 40px; background: #fafafa; color: #1a1a1a; line-height: 1.6; }
                        .container { max-width: 900px; margin: 0 auto; background: #ffffff; padding: 40px; border-radius: 12px; box-shadow: 0 4px 12px rgba(0,0,0,0.08); }
                        h1 { color: #1e3a8a; border-bottom: 2px solid #e2e8f0; padding-bottom: 8px; }
                        .metadata { color: #64748b; font-size: 0.9em; margin-bottom: 24px; }
                        .content { margin-top: 20px; }
                        .ink-overlay { margin-top: 30px; border: 1px solid #e2e8f0; border-radius: 8px; background: #ffffff; padding: 12px; }
                    </style>
                </head>
                <body>
                    <div class="container">
                        <h1>${page.title}</h1>
                        <div class="metadata">
                            <strong>Tags:</strong> ${page.tags.ifBlank { "None" }} | 
                            <strong>Template:</strong> ${page.template ?: "blank"}
                        </div>
                        <div class="content">
                            $bodyHtml
                        </div>
                        <div class="ink-overlay">
                            <h3>Ink Canvas Drawings</h3>
                            $svgInk
                        </div>
                    </div>
                </body>
                </html>
            """.trimIndent()

            FileOutputStream(htmlFile).use { it.write(htmlDocument.toByteArray(Charsets.UTF_8)) }

            try {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir.exists()) {
                    val publicFile = File(downloadsDir, htmlFile.name)
                    htmlFile.copyTo(publicFile, overwrite = true)
                }
            } catch (_: Exception) {}

            htmlFile
        } catch (e: Exception) {
            Log.e("ImportExportService", "Failed to export note to HTML", e)
            null
        }
    }

    suspend fun exportVaultToHtmlZip(
        context: Context,
        vaultTitle: String,
        pages: List<com.authorss81.noteflow.data.model.NotePageEntity>,
        repository: com.authorss81.noteflow.data.repository.NoteRepository
    ): File? = withContext(Dispatchers.IO) {
        try {
            val sanitizeVaultTitle = vaultTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "SmoothNotes_HTML" }
            val exportDir = File(context.cacheDir, "html_vault_exports").apply { if (!exists()) mkdirs() }
            val zipFile = File(exportDir, "${sanitizeVaultTitle}_HTML_Site.zip")

            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                val indexSb = StringBuilder()
                indexSb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>$vaultTitle - Index</title>")
                indexSb.append("<style>body{font-family:sans-serif;margin:40px;background:#f8fafc;color:#0f172a;} .card{background:#fff;padding:16px;border-radius:8px;margin-bottom:12px;box-shadow:0 2px 4px rgba(0,0,0,0.05);}</style></head><body>")
                indexSb.append("<h1>$vaultTitle Index</h1><ul>")

                pages.forEachIndexed { idx, page ->
                    val safeTitle = page.title.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "Page_${idx + 1}" }
                    val htmlFileName = "${idx + 1}_$safeTitle.html"

                    val htmlFile = exportNoteToHtml(context, page, repository)
                    if (htmlFile != null && htmlFile.exists()) {
                        val entry = ZipEntry(htmlFileName)
                        zos.putNextEntry(entry)
                        htmlFile.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }

                    indexSb.append("<li><a href=\"$htmlFileName\">${page.title}</a> (${page.tags.ifBlank { "No tags" }})</li>")
                }

                indexSb.append("</ul></body></html>")
                val indexEntry = ZipEntry("index.html")
                zos.putNextEntry(indexEntry)
                zos.write(indexSb.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            try {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir.exists()) {
                    val publicFile = File(downloadsDir, zipFile.name)
                    zipFile.copyTo(publicFile, overwrite = true)
                }
            } catch (_: Exception) {}

            zipFile
        } catch (e: Exception) {
            Log.e("ImportExportService", "Failed to export vault HTML site", e)
            null
        }
    }

    // --- PHASE 24: OBSIDIAN VAULT IMPORT & EXPORT ---

    suspend fun importObsidianVaultZip(
        context: Context,
        uri: Uri,
        repository: com.authorss81.noteflow.data.repository.NoteRepository,
        notebookId: String,
        sectionId: String
    ): Int = withContext(Dispatchers.IO) {
        var count = 0
        try {
            val bytes = readUriBytes(context, uri) ?: return@withContext 0
            val attachmentMap = mutableMapOf<String, String>()

            // Pass 1: Extract attachments/images to imports folder
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val ext = extensionOf(entry.name)
                    if (!entry.isDirectory && isImage(ext)) {
                        val fileBytes = zis.readBytes()
                        val fileName = entry.name.substringAfterLast('/')
                        val savedPath = persistFile(context, fileName, fileBytes)
                        attachmentMap[fileName] = savedPath
                    }
                    entry = zis.nextEntry
                }
            }

            // Pass 2: Parse Markdown files
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory && entry.name.endsWith(".md", ignoreCase = true)) {
                        val mdBytes = zis.readBytes()
                        val rawContent = String(mdBytes, Charsets.UTF_8)

                        val title = entry.name.substringAfterLast('/').substringBeforeLast('.')
                        val tags = WikiLinkParser.extractTags(rawContent).joinToString(",")

                        val safeTitle = sanitizeImportFileName(title)
                        val mdFileName = "${safeTitle}_${System.currentTimeMillis()}.md"
                        val savedPath = persistFile(context, mdFileName, rawContent.toByteArray(Charsets.UTF_8))

                        repository.createPage(
                            sectionId = sectionId,
                            title = title,
                            sourceFilePath = savedPath,
                            sourceFileType = "text",
                            extractedText = rawContent,
                            tags = tags.ifBlank { "obsidian_import" }
                        )
                        count++
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            Log.e("ImportExportService", "Failed to import Obsidian Vault ZIP", e)
        }
        count
    }

    suspend fun exportObsidianVaultZip(
        context: Context,
        vaultTitle: String,
        pages: List<com.authorss81.noteflow.data.model.NotePageEntity>,
        repository: com.authorss81.noteflow.data.repository.NoteRepository
    ): File? = withContext(Dispatchers.IO) {
        try {
            val sanitizeVaultTitle = vaultTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "Obsidian_Vault" }
            val exportDir = File(context.cacheDir, "obsidian_exports").apply { if (!exists()) mkdirs() }
            val zipFile = File(exportDir, "${sanitizeVaultTitle}_Obsidian.zip")

            ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { zos ->
                val indexSb = StringBuilder()
                indexSb.append("# $vaultTitle (Obsidian Vault Index)\n\n")

                pages.forEachIndexed { idx, page ->
                    val sanitizeTitle = page.title.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "Note_${idx + 1}" }
                    val pagePath = "$sanitizeTitle.md"

                    val mdContent = buildString {
                        append("---\n")
                        append("tags: [${page.tags.split(",").filter { it.isNotBlank() }.joinToString(", ") { "\"${it.trim()}\"" }}]\n")
                        append("title: \"${page.title}\"\n")
                        append("template: \"${page.template ?: "blank"}\"\n")
                        append("---\n\n")
                        append("# ${page.title}\n\n")
                        append(page.extractedText ?: "")
                        append("\n\n---\n")
                        append("![[${sanitizeTitle}_ink.svg]]\n")
                    }

                    val mdEntry = ZipEntry(pagePath)
                    zos.putNextEntry(mdEntry)
                    zos.write(mdContent.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()

                    // SVG Vector Ink Export
                    val strokes = repository.getStrokesForPage(page.id)
                    val svgContent = renderPageInkToSvg(strokes)
                    val svgEntry = ZipEntry("${sanitizeTitle}_ink.svg")
                    zos.putNextEntry(svgEntry)
                    zos.write(svgContent.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()

                    indexSb.append("- [[${page.title}]]\n")
                }

                val indexEntry = ZipEntry("00_OBSIDIAN_INDEX.md")
                zos.putNextEntry(indexEntry)
                zos.write(indexSb.toString().toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }

            try {
                val downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (downloadsDir.exists()) {
                    val publicFile = File(downloadsDir, zipFile.name)
                    zipFile.copyTo(publicFile, overwrite = true)
                }
            } catch (_: Exception) {}

            zipFile
        } catch (e: Exception) {
            Log.e("ImportExportService", "Failed to export Obsidian Vault ZIP", e)
            null
        }
    }

    // --- PHASE 24: LAYERED PSD EXPORT ---

    suspend fun exportPageToPsd(
        context: Context,
        page: com.authorss81.noteflow.data.model.NotePageEntity,
        repository: com.authorss81.noteflow.data.repository.NoteRepository
    ): File? = withContext(Dispatchers.IO) {
        try {
            val strokes = repository.getStrokesForPage(page.id)
            val layers = repository.getLayersForPage(page.id)

            val width = 1080
            val height = 1528

            val psdLayers = mutableListOf<PsdExportService.PsdLayer>()

            // Background layer
            val bgBitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val bgCanvas = android.graphics.Canvas(bgBitmap)
            bgCanvas.drawColor(android.graphics.Color.WHITE)
            drawTemplateBackground(bgCanvas, page.template ?: "blank", width, height)
            psdLayers.add(PsdExportService.PsdLayer("Background", bgBitmap))

            val inkRenderer = try {
                androidx.ink.rendering.android.canvas.CanvasStrokeRenderer.create(false)
            } catch (e: Exception) {
                null
            }

            if (layers.isEmpty()) {
                val layerBitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(layerBitmap)
                renderLayersAndStrokesToCanvas(canvas, width, height, 0, strokes, emptyList(), inkRenderer)
                psdLayers.add(PsdExportService.PsdLayer("Drawing Layer 1", layerBitmap))
            } else {
                layers.sortedBy { it.zOrder }.forEachIndexed { idx, layerEntity ->
                    val layerBitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(layerBitmap)
                    val layerStrokes = strokes.filter { it.layerId == layerEntity.id }
                    renderLayersAndStrokesToCanvas(canvas, width, height, 0, layerStrokes, listOf(layerEntity), inkRenderer)
                    psdLayers.add(PsdExportService.PsdLayer(layerEntity.name.ifBlank { "Layer ${idx + 1}" }, layerBitmap, layerEntity.visible))
                }
            }

            PsdExportService.exportLayersToPsd(
                context = context,
                title = page.title,
                width = width,
                height = height,
                layers = psdLayers
            )
        } catch (e: Exception) {
            Log.e("ImportExportService", "Failed to export page to PSD", e)
            null
        }
    }

}
