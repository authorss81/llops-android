@file:android.annotation.SuppressLint("RestrictedApi")
package com.authorss81.noteflow.services

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.UUID
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
import com.authorss81.noteflow.services.StrokeGeometryPolicy
import com.authorss81.noteflow.utils.BackupFileNamePolicy

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

    /**
     * B1-DB-5 (phase-55): the stream is read under a hard byte cap so an
     * attacker-controlled share/download can never `readBytes()` unbounded
     * megabtyes→gigabytes into heap. An oversized stream raises
     * [ImportArchivePolicy.ImportSizeLimitException] with a clean message (the
     * caller surfaces it); genuine read failures still return null.
     *
     * The default cap is the import budget; the backup-restore callers pass the
     * (larger) backup input cap explicitly so legitimate large vaults restore.
     */
    suspend fun readUriBytes(
        context: Context,
        uri: Uri,
        maxBytes: Long = ImportArchivePolicy.MAX_IMPORT_ARCHIVE_INPUT_BYTES.toLong()
    ): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val stream = context.contentResolver.openInputStream(uri) ?: return@withContext null
            stream.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                var read = input.read(buffer)
                while (read != -1) {
                    total += read
                    if (total > maxBytes) {
                        throw ImportArchivePolicy.ImportSizeLimitException(
                            "File is too large to import (max ${maxBytes / (1024L * 1024L)}MB)."
                        )
                    }
                    out.write(buffer, 0, read)
                    read = input.read(buffer)
                }
                out.toByteArray()
            }
        } catch (e: ImportArchivePolicy.ImportSizeLimitException) {
            throw e
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

    /**
     * Phase 202 (bug batch): raised when a PDF source cannot be opened at all
     * (missing file, corrupt xref, encrypted document). Pre-fix the page count
     * silently degraded to 0 and the import produced a blank template-only page
     * with NO user-facing explanation. The import loop catches this and skips
     * the file with an honest snackbar instead.
     */
    class PdfImportException(message: String) : java.io.IOException(message)

    /**
     * Page count of [filePath]. Throws [PdfImportException] when the document
     * cannot be opened — a corrupt PDF must never masquerade as a 0/1-page note.
     *
     * Phase 202 (bug batch): renderer + file descriptor are closed via `use{}`
     * so an exception mid-open can no longer leak the ParcelFileDescriptor FD
     * (the pre-fix sequential close only ran on the success path).
     */
    fun getPdfPageCount(filePath: String): Int {
        val file = File(filePath)
        if (!file.exists()) {
            throw PdfImportException("PDF import failed: the selected document could not be read.")
        }
        return try {
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                    renderer.pageCount
                }
            }
        } catch (e: PdfImportException) {
            throw e
        } catch (e: java.io.IOException) {
            throw PdfImportException("PDF import failed: the document appears corrupted or is password-protected.")
        } catch (e: Exception) {
            throw PdfImportException("PDF import failed: the document appears corrupted or is password-protected.")
        }
    }

    /**
     * Renders [pageIndex] of [pdfFilePath] to a bitmap, or null when the index is
     * out of range / the page cannot be rendered (callers fall back to the paper
     * template — rendering one bad page must not fail a whole export).
     *
     * Phase 202 (bug batch): `use{}` on both the descriptor and the renderer so
     * a render failure cannot leak FDs across a long continuous-mode session.
     */
    fun renderPdfPageToBitmap(pdfFilePath: String, pageIndex: Int, targetWidth: Int = 1080, targetHeight: Int = 1528): android.graphics.Bitmap? {
        return try {
            val file = File(pdfFilePath)
            if (!file.exists()) return null
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                    if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
                    val pdfPage = renderer.openPage(pageIndex)
                    pdfPage.use { page ->
                        val bitmap = android.graphics.Bitmap.createBitmap(targetWidth, targetHeight, android.graphics.Bitmap.Config.ARGB_8888)
                        val canvas = android.graphics.Canvas(bitmap)
                        canvas.drawColor(android.graphics.Color.WHITE)
                        page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Phase 202 (bug batch): renders ONE PDF page to a standalone PNG inside the
     * app-private imports dir, for the split-into-separate-pages import path.
     *
     * Pre-fix every split page entity referenced the SAME full multi-page PDF,
     * and since no per-page index exists on the page row, EVERY created page
     * rendered slice 0 in the editor ("Page 3" showed PDF page 1). Rasterizing
     * each page to its own image makes every created page a correct, standalone
     * slice with no schema change — and deleting one page can no longer delete
     * the shared source out from under its siblings.
     *
     * Phase 202 review-fix: the raster adopts the PAGE's own aspect ratio
     * ([PdfSliceFitPolicy]) inside the same width/height budget box. The
     * first cut rendered everything into one fixed portrait bitmap, which made
     * PdfRenderer letterbox landscape pages into permanently-baked white bars.
     */
    fun renderPdfPageToPngFile(
        context: Context,
        pdfFilePath: String,
        pageIndex: Int,
        baseName: String,
        targetWidth: Int = PdfSliceFitPolicy.DEFAULT_MAX_WIDTH,
        targetHeight: Int = PdfSliceFitPolicy.DEFAULT_MAX_HEIGHT
    ): String? {
        val bitmap = renderPdfPageToFittedBitmap(pdfFilePath, pageIndex, targetWidth, targetHeight) ?: return null
        return try {
            val importsDir = getImportsDir(context)
            // Short random token: two imports of same-named documents must never
            // clobber each other's page images (persistFile's plain-name rule is
            // intentionally untouched for single-file imports).
            val token = UUID.randomUUID().toString().substring(0, 8)
            val safeName = sanitizeImportFileName("${baseName}_p${pageIndex + 1}_$token.png")
            val outFile = File(importsDir, safeName)
            FileOutputStream(outFile).use { compressBitmap(ExportImageFormat.PNG, bitmap, it) }
            if (!outFile.exists() || outFile.length() == 0L) {
                runCatching { outFile.delete() }
                null
            } else {
                outFile.absolutePath
            }
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** Split-import cap: beyond this the import truncates honestly (see HomeScreen). */
    const val PDF_SPLIT_MAX_PAGES = 50

    /**
     * Renders [pageIndex] at the page's OWN aspect ratio, bounded by
     * ([maxWidth] x [maxHeight]) via [PdfSliceFitPolicy] — no letterboxing.
     * Null on the same conditions as [renderPdfPageToBitmap] (missing file,
     * out-of-range index, render failure); that function's fixed-size contract
     * is untouched for its export callers.
     */
    private fun renderPdfPageToFittedBitmap(
        pdfFilePath: String,
        pageIndex: Int,
        maxWidth: Int,
        maxHeight: Int
    ): android.graphics.Bitmap? {
        return try {
            val file = File(pdfFilePath)
            if (!file.exists()) return null
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                android.graphics.pdf.PdfRenderer(pfd).use { renderer ->
                    if (pageIndex < 0 || pageIndex >= renderer.pageCount) return null
                    renderer.openPage(pageIndex).use { pdfPage ->
                        val dims = PdfSliceFitPolicy.fit(pdfPage.width, pdfPage.height, maxWidth, maxHeight)
                        val bitmap = android.graphics.Bitmap.createBitmap(
                            dims.width, dims.height, android.graphics.Bitmap.Config.ARGB_8888
                        )
                        val canvas = android.graphics.Canvas(bitmap)
                        canvas.drawColor(android.graphics.Color.WHITE)
                        pdfPage.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
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

    /**
     * Phase-182 review-fix (#3): renders the [pageIdx]'th slice of a tall
     * multi-page IMAGE source (the source of `pageCountNeeded` > 1 in the editor),
     * mirroring the editor canvas's own slice (AnnotationCanvas:1803-1823) instead
     * of stamping the full image onto every page. The page count is computed from
     * the source normalized to 1080-wide / 1528-tall pages
     * (EditorScreen:793-801), so the per-page band is exactly one page height of
     * image content starting at `pageIdx * band`, in the same homogeneous
     * coordinate space regardless of the decode's resolution. Page 0 keeps the
     * historical whole-image behavior (the caller owns and recycles the returned
     * bitmap). A page beyond the image's last band returns null — the export loop
     * then falls back to the template background, so no page is ever blank and no
     * page repeats the whole image.
     */
    private fun renderImageSliceForPage(sourceFilePath: String, pageIdx: Int): android.graphics.Bitmap? {
        if (pageIdx < 0) return null
        val full = decodeImageSampled(sourceFilePath, maxLongEdge = 4096) ?: return null
        val imgW = full.width
        val imgH = full.height
        if (imgW <= 0 || imgH <= 0) {
            full.recycle()
            return null
        }
        if (pageIdx == 0) return full
        return try {
            val pageWidth = 1080
            val pageHeight = 1528
            val scale = pageWidth.toFloat() / imgW
            val bandHeightInImgPx = pageHeight / scale
            val srcY = pageIdx * bandHeightInImgPx
            if (srcY >= imgH) {
                null
            } else {
                val bandH = minOf(bandHeightInImgPx, imgH - srcY)
                val page = android.graphics.Bitmap.createBitmap(
                    pageWidth, pageHeight, android.graphics.Bitmap.Config.ARGB_8888
                )
                val canvas = android.graphics.Canvas(page)
                canvas.drawColor(android.graphics.Color.WHITE)
                val src = android.graphics.Rect(0, srcY.toInt(), imgW, (srcY + bandH).toInt())
                val dstH = (bandH * scale).toInt()
                canvas.drawBitmap(full, src, android.graphics.Rect(0, 0, pageWidth, dstH), null)
                page
            }
        } catch (e: Exception) {
            null
        } finally {
            full.recycle()
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
        exportImageFormat: ExportImageFormat = ExportImageFormat.PNG,
        // Phase 227: flat PNG/WEBP with a TRANSPARENT background (the white
        // paper fill is skipped; the template grid + ink draw directly on
        // alpha). Ignored for PDF (a PDF page is always opaque) and for pages
        // that carry a background image (the image is opaque anyway).
        transparentBackground: Boolean = false
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

            // Phase 227: transparent-background PNG/WEBP skips the opaque white
            // paper fill (a resolved background image is opaque regardless).
            if (!transparentBackground) {
                canvas.drawColor(android.graphics.Color.WHITE)
            }

            // Phase 227: a deckled-edge transparent export clips the ENTIRE page
            // (template grid + ink + layers) to the same wavy sheet silhouette
            // the editor shows — corners export as clean alpha, never a white
            // fringe. PDF pages stay rectangular (opaque by nature); RECT and
            // ROUNDED edges leave the canvas untouched (legacy files).
            val deckleClip = transparentBackground &&
                !exportAsPdf &&
                com.authorss81.noteflow.services.DeckleExportHelper.deckledEnabled(context)
            if (deckleClip) {
                canvas.save()
                canvas.clipPath(
                    com.authorss81.noteflow.services.DeckleExportHelper.sheetPath(
                        width.toFloat(), height.toFloat(), width / 360f
                    )
                )
            }

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

            if (deckleClip) {
                canvas.restore()
            }

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

            // B1-PLAT-3 (phase-59): NO auto-copy to public Downloads. The file stays
            // app-private in cacheDir; the UI delivers it to a user-picked SAF
            // destination (see SaFExporter + ExportDestinationPolicy).
            outFile
        } catch (e: Exception) {
            // B2-LOG-03 (phase-71): class name only, never the throwable.
            Log.e("ImportExportService", FailureLogPolicy.safeLogMessage(e, "Failed to export annotated page"))
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
                // Phase-182 review-fix: keep a reference to the CALLER's cached
                // bitmap so the loop never recycles it — those objects are the same
                // instances the editor is still displaying (pdfPageBitmaps /
                // activeRawBitmapMap), so a recycle would corrupt the on-screen
                // pages after the export returns. Only loop-ALLOCATED per-page
                // source re-renders are recycled below.
                val cachedBg = bgBitmaps[pageIdx]
                val bg = cachedBg ?: if (!sourceFilePath.isNullOrBlank()) {
                    if (sourceFilePath.lowercase().endsWith(".pdf")) {
                        renderPdfPageToBitmap(sourceFilePath, pageIdx)
                    } else {
                        renderImageSliceForPage(sourceFilePath, pageIdx)
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
                // Recycle ONLY the bitmaps THIS loop allocated (per-page source
                // re-renders). The caller's in-window cached bitmaps are still live
                // on the editor screens and must never be recycled here.
                if (bg != null && bg !== cachedBg) {
                    bg.recycle()
                }
            }

            FileOutputStream(outFile).use { pdfDoc.writeTo(it) }
            pdfDoc.close()

            // B1-PLAT-3 (phase-59): no auto-copy to public Downloads — the document
            // PDF stays app-private in cacheDir until the user picks a destination.
            outFile
        } catch (e: Exception) {
            // B2-LOG-03 (phase-71): class name only, never the throwable.
            Log.e("ImportExportService", FailureLogPolicy.safeLogMessage(e, "Failed to export document as PDF"))
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

    // Phase 228 mask-based wet partial erase: the on-canvas renderer (and
    // therefore the COMMITTED stroke row) keeps wet strokes whole and hides
    // erased regions via a list of EraseMask circles punched with a CLEAR
    // xfermode from a per-stroke layer. Exporters must apply the SAME punch,
    // or a partially-erased wet stroke would come back in full in the
    // exported PNG/PDF/PSD/flattened bitmap. Non-wet strokes are never masked
    // (they are split into run fragments by StrokeSegmenter instead).
    // Lazy: android.graphics.Paint is a native-backed class — instantiating it
    // during OBJECT INIT would break every pure-JVM unit test that merely
    // touches ImportExportService (UnsatisfiedLinkError on the JVM). Both are
    // only needed on the export-draw hot path, which is never exercised on the
    // unit-test JVM, so lazy keeps class loading safe while staying
    // one-allocation-per-process on device.
    private val eraseLayerPaint: android.graphics.Paint by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    }
    private val erasePunchPaint: android.graphics.Paint by lazy {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        }
    }

    private fun drawSingleStrokeToCanvas(
        canvas: android.graphics.Canvas,
        stroke: com.authorss81.noteflow.data.model.Stroke,
        inkRenderer: androidx.ink.rendering.android.canvas.CanvasStrokeRenderer?
    ) {
        val masks = stroke.eraseMask
        val hasEraseMasks = !masks.isNullOrEmpty() &&
            com.authorss81.noteflow.services.BrushStrokeMath.isWetRenderedTool(stroke.tool)
        var layerSaveCount = -1
        if (hasEraseMasks) {
            // Full-canvas layer is fine here: exports rasterize once per stroke
            // (not per frame), and the CLEAR punch must be able to reach every
            // mask circle while confined to this stroke's own alpha layer.
            layerSaveCount = canvas.saveLayer(null, eraseLayerPaint)
        }
        fun punchEraseMasks() {
            if (!hasEraseMasks) return
            for (m in masks!!) {
                canvas.drawCircle(m.x, m.y, m.radius, erasePunchPaint)
            }
        }

        if (stroke.isAdvanced && inkRenderer != null) {
            val inkStroke = convertToInkStroke(stroke)
            if (inkStroke != null) {
                inkRenderer.draw(canvas, inkStroke, android.graphics.Matrix())
                punchEraseMasks()
                if (layerSaveCount >= 0) canvas.restoreToCount(layerSaveCount)
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
            com.authorss81.noteflow.data.model.StrokeTool.FILL -> {
                if (stroke.points.size >= 3) {
                    val paint = android.graphics.Paint(basePaint).apply {
                        style = android.graphics.Paint.Style.FILL
                    }
                    val path = android.graphics.Path()
                    path.moveTo(stroke.points[0].x, stroke.points[0].y)
                    for (i in 1 until stroke.points.size) {
                        path.lineTo(stroke.points[i].x, stroke.points[i].y)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
            }
            com.authorss81.noteflow.data.model.StrokeTool.GRADIENT -> {
                if (stroke.start != null && stroke.end != null) {
                    val left = minOf(stroke.start.x, stroke.end.x)
                    val top = minOf(stroke.start.y, stroke.end.y)
                    val right = maxOf(stroke.start.x, stroke.end.x)
                    val bottom = maxOf(stroke.start.y, stroke.end.y)
                    val fromColor = stroke.colorInt
                    val toColor = stroke.gradientToColorInt
                        ?: com.authorss81.noteflow.services.BrushColorModeMath.complementaryArgb(fromColor)
                    val sx = if (stroke.points.size >= 2) stroke.points[0].x else left
                    val sy = if (stroke.points.size >= 2) stroke.points[0].y else top
                    val ex = if (stroke.points.size >= 2) stroke.points[1].x else right
                    val ey = if (stroke.points.size >= 2) stroke.points[1].y else top
                    val shader = android.graphics.LinearGradient(
                        sx, sy, ex, ey,
                        fromColor, toColor,
                        android.graphics.Shader.TileMode.CLAMP
                    )
                    val paint = android.graphics.Paint().apply {
                        this.shader = shader
                        isAntiAlias = true
                    }
                    canvas.drawRect(left, top, right, bottom, paint)
                }
            }
            else -> {}
        }
        // Phase 228: apply the wet partial-erase punch (CLEAR) inside this
        // stroke's layer, then restore so later strokes composite normally.
        punchEraseMasks()
        if (layerSaveCount >= 0) canvas.restoreToCount(layerSaveCount)
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
            // B2-LOG-03 (phase-71): class name only, never the throwable.
            Log.e("ImportExportService", FailureLogPolicy.safeLogMessage(e, "Failed to parse docx"))
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
    // B2-CRYPTO-04 (phase-84): v3 splits the DEK-wrapping key — the header still
    // carries salt + payload IV + wrapped DEK, but the DEK is now wrapped by a
    // random key whose SECOND half only exists inside the encrypted payload, so
    // the public header alone no longer contains a cheap offline-crack target.
    private const val BACKUP_MAGIC_V3 = "NFLB3"
    private const val BACKUP_SALT_SIZE = 16
    private const val BACKUP_IV_SIZE = 12
    // B2-CRYPTO-04: half of the 32-byte DEK-wrapping key that rides in the v3
    // header; the other half is stored only inside the (password-encrypted) payload
    // and recovered at restore time. 16 bytes in the header + 16 in the payload.
    private const val BACKUP_WRAP_KEY_HALF_SIZE = 16
    // EncryptionService.encrypt/encryptAad output: 1 version byte + 12-byte IV + 32-byte
    // ciphertext + 16-byte tag.
    private const val BACKUP_WRAPPED_DEK_SIZE = 61
    // Restore-path input cap. Larger than the import budget (200MB) so a
    // legitimate vault backup (DB + media + voice blobs) still restores; the
    // import callers use ImportArchivePolicy.MAX_IMPORT_ARCHIVE_INPUT_BYTES.
    // R2-B1D-04 (phase-138): still THE single wire-level cap (the WebDAV download
    // cap and every restore entry point keep passing it); the DECOMPRESSED budget
    // it feeds is now the shared BackupBudgetPolicy (== this value), so the
    // export packer and the restore extractor can never drift out of parity.
    const val MAX_BACKUP_INPUT_BYTES = 400L * 1024 * 1024 // 400MB hard cap before any decrypt/decompress
    // Phase 190: self-update APK staging cap — a legitimate APK is well under
    // 200 MB; the cap is large enough for real builds, bounded enough to fail
    // loudly on a garbage 1 GB "APK" instead of filling the app's partition.
    const val MAX_APK_INPUT_BYTES = 256L * 1024 * 1024 // 256MB hard cap for APK upload/update staging

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
     * B2-CRYPTO-04 (phase-84): Serialized v3 header:
     * [magic "NFLB3"][salt][payloadIv][wrapKeyPart1][wrappedDek].
     *
     * [wrapKeyPart1] is the FIRST half of the random DEK-wrapping key; its
     * sibling [wrapKeyPart2] is embedded at the start of the encrypted payload
     * (see [exportBackup]), so the public header alone can never form the
     * wrapping key and an offline brute-force attempt cannot test password
     * guesses against a small wrapped-DEK blob — every candidate pays a full
     * payload decrypt on top of PBKDF2 and requires the payload itself.
     */
    internal fun buildBackupHeaderV3(salt: ByteArray, payloadIv: ByteArray, wrapKeyPart1: ByteArray, wrappedDek: ByteArray): ByteArray {
        val magic = BACKUP_MAGIC_V3.toByteArray(Charsets.US_ASCII)
        return ByteArray(magic.size + salt.size + payloadIv.size + wrapKeyPart1.size + wrappedDek.size).also { out ->
            var off = 0
            System.arraycopy(magic, 0, out, off, magic.size); off += magic.size
            System.arraycopy(salt, 0, out, off, salt.size); off += salt.size
            System.arraycopy(payloadIv, 0, out, off, payloadIv.size); off += payloadIv.size
            System.arraycopy(wrapKeyPart1, 0, out, off, wrapKeyPart1.size); off += wrapKeyPart1.size
            System.arraycopy(wrappedDek, 0, out, off, wrappedDek.size)
        }
    }

    /**
     * B2-CRYPTO-03 fix: the payload GCM authenticates the whole header
     * (magic|salt|payloadIv|wrappedDek) under the 'backup/payload' domain AAD,
     * binding every payload to its own header. Splice another export's header
     * onto a payload and the tag fails.
     *
     * B2-DOS-07 (phase-83): production export no longer calls this one-shot
     * path — [BackupExportPolicy.encryptStreamGcm] streams the same AES-GCM
     * payload file-to-file (whose output is byte-identical to this function's),
     * so this helper survives ONLY as the single-shot format-compat reference
     * pinned by the format tests.
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
     * Phase-169: the three possible outcomes of re-keying one stored field value
     * on a cross-key restore.
     *
     * This is the fail-closed seam for the "pages become Unreadable (decryption
     * failed) after export/import" class: while the old DEK is still in hand
     * (before any file swap), a value that cannot be migrated is still perfectly
     * recoverable. Once the DB is re-keyed and swapped into the live vault,
     * reading that same value with the NEW DEK fails GCM authentication and
     * renders [DecryptFailurePolicy.UNREADABLE_MARKER] forever. Therefore a
     * genuine ciphertext row that fails to migrate must be treated as a RESTORE
     * FAILURE, never silently left behind.
     */
    internal sealed class FieldReencryptOutcome {
        /** The value was a genuine ciphertext and now lives under [value] (new DEK, per-record AAD). */
        data class Migrated(val value: String) : FieldReencryptOutcome()

        /** The value is blank or genuine plaintext (not structurally a payload) — leave it as-is. */
        object LeavePlaintext : FieldReencryptOutcome()

        /** Structural ciphertext that fails to decrypt under the OLD DEK — would be orphaned by the re-key. */
        object AuthFailed : FieldReencryptOutcome()
    }

    /**
     * C1/B2-CRYPTO-09 (phase-107) + phase-169: re-keys a single field-ciphertext
     * value from the backup DEK to the current DEK and re-binds it to its
     * per-record AAD (`table|recordId|fieldName`). The source may be a legacy
     * global-AAD row or an already-record-bound row — [EncryptionService.decryptField]'s
     * fallback reads both under the backup DEK — and the result is always a
     * per-record-bound ciphertext under the new DEK. Returns the outcome rather
     * than a nullable String so the caller can distinguish "leave this plaintext
     * alone" (legitimate) from "this ciphertext could not be re-keyed" (a
     * restore that must fail loudly instead of installing unreadable pages).
     */
    internal fun reencryptFieldOutcome(
        value: String?,
        oldDek: ByteArray,
        newDek: ByteArray,
        table: String,
        recordId: String,
        fieldName: String
    ): FieldReencryptOutcome {
        if (value.isNullOrBlank()) return FieldReencryptOutcome.LeavePlaintext
        if (!DecryptFailurePolicy.isStructuralCiphertext(value)) return FieldReencryptOutcome.LeavePlaintext
        return try {
            val plain = EncryptionService.decryptField(value, oldDek, table, recordId, fieldName)
            FieldReencryptOutcome.Migrated(
                EncryptionService.encryptField(plain, newDek, table, recordId, fieldName)
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // A malformed payload (IllegalArgumentException) and an auth failure
            // (AEADBadTagException) are BOTH fail-closed here — either way the row
            // cannot be re-keyed and the restore must fail loudly, not strand it.
            FieldReencryptOutcome.AuthFailed
        }
    }

    /**
     * Compatibility wrapper (kept for existing callers/tests): returns the
     * migrated value, or null when the value is plaintext/blank or could not be
     * re-keyed. Prefer [reencryptFieldOutcome] on the restore path so an
     * [FieldReencryptOutcome.AuthFailed] result fails the restore loudly instead
     * of silently stranding the row under the old DEK.
     */
    internal fun reencryptFieldValue(value: String?, oldDek: ByteArray, newDek: ByteArray, table: String, recordId: String, fieldName: String): String? {
        return when (val outcome = reencryptFieldOutcome(value, oldDek, newDek, table, recordId, fieldName)) {
            is FieldReencryptOutcome.Migrated -> outcome.value
            FieldReencryptOutcome.LeavePlaintext,
            FieldReencryptOutcome.AuthFailed -> null
        }
    }

    /**
     * Phase-169: a restore is REJECTED (before any file swap) when one or more
     * structurally-ciphertext rows failed to re-key to the restoring device's
     * DEK. After the SQLCipher-layer re-key those rows could never authenticate
     * again, so installing them would guarantee permanently unreadable pages —
     * exactly the "pages become unreadable after export/import" report. Surfaced
     * through [com.authorss81.noteflow.services.UiFailureTextPolicy.restoreFailureMessage]
     * as fixed text (never the raw message).
     */
    internal class RestoreReEncryptionException(
        val table: String,
        val column: String,
        val failedRowCount: Int,
        cause: Throwable? = null
    ) : Exception(
        "Restore rejected: $failedRowCount stored rows could not be re-encrypted for this device " +
            "($table.$column). The backup may be damaged or contain content that cannot be decrypted. " +
            "Your vault was left unchanged.",
        cause
    )

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
     *
     * R2-B1D-05/03 (phase-137): this is the SINGLE disciplined DB-file producer —
     * it runs a full WAL checkpoint + HMAC re-stamp BEFORE copying the main file
     * (so no committed-but-uncheckpointed frame silently misses the archive), then
     * copies through [VaultSnapshotCopyPolicy.checkpointThenCopy]'s verified
     * snapshot (a concurrent WAL auto-checkpoint can never tear the staged copy).
     * Every exporter — HomeScreen backup / password backup, WebDAV, LocalSend —
     * routes through this one checkpoint-then-copy, so [repository] is REQUIRED.
     *
     * Phase 202 (bug batch): the handed DEK ([vaultDek]) is SNAPSHOT-COPIED at
     * entry and that copy — zeroized at exit — is what the whole export uses.
     * The callers hand over the LIVE [VaultKeyHolder.dek] array; an auto-lock
     * landing mid-export (screen-off, ON_STOP) fills that array with zeros in
     * place, which previously poisoned the prune key AND the archive-encryption
     * key mid-run. The copy keeps the export on the SAME key it started with;
     * its lifetime is bounded to this call and it is zeroized in the finally,
     * preserving the lock-time zeroization discipline.
     *
     * Phase 252 (HIGH 4/5): the passwordless-portability gate. The snapshot
     * copy is taken BEFORE the gate reads the key, and the gate can never mint
     * a key — it only decides whether a device-keyed export (a PASSWORDLESS
     * vault called with `backupPassword == null` and `requireBackupPassword`
     * left at its default `true`) is allowed to proceed. It is not: the archive
     * would carry the AndroidKeyStore-wrapped DEK blob (B1-CRYPTO-05), which no
     * other device can unwrap — a silent data-loss trap. The HomeScreen UI is
     * the first gate (it refuses to export for a passwordless vault until a
     * master password is set); this throws [IllegalArgumentException] as
     * defense-in-depth against any future caller that bypasses the UI.
     *
     * The device-keyed path itself is PRESERVED for callers that explicitly
     * opt in via `requireBackupPassword = false` — the documented
     * B1-CRYPTO-05 WebDAV/LocalSend sync producers and any future
     * "device-locked backup" feature (see [BackupPortabilityPolicy]).
     */
    suspend fun exportBackup(
        context: Context,
        vaultDek: ByteArray?,
        backupPassword: String? = null,
        requireBackupPassword: Boolean = true,
        repository: com.authorss81.noteflow.data.repository.NoteRepository
    ): File = withContext(Dispatchers.IO) {
        val key = vaultDek?.copyOf()
        try {
            // Phase 252: a passwordless vault + no backup password = a
            // device-DEK-encrypted archive that is unreadable on any other
            // device. Blocked unless the caller explicitly opted into the
            // device-keyed model. `requirePortableBackup` never mints/exposes a
            // key — it only throws or passes through on the boolean table.
            BackupPortabilityPolicy.requirePortableBackup(
                requireBackupPassword = requireBackupPassword,
                backupPassword = backupPassword,
                keyAvailable = key != null,
                hasMasterPassword = SettingsManager(context.applicationContext).hasMasterPassword
            )
            exportBackupInternal(context, key, backupPassword, repository)
        } finally {
            ExportSessionPolicy.zeroize(key)
        }
    }

    private suspend fun exportBackupInternal(
        context: Context,
        key: ByteArray?,
        backupPassword: String?,
        repository: com.authorss81.noteflow.data.repository.NoteRepository
    ): File = withContext(Dispatchers.IO) {
        val dbFile = context.getDatabasePath("noteflow.sqlite")
        val importsDir = getImportsDir(context)

        // B2-CRYPTO-06 (phase-106): the temp name becomes the public Downloads
        // name verbatim (HomeScreen copies it with cacheFile.name), so it must
        // NOT carry epoch-millis — use the day-granular + random-token policy.
        val backupName = BackupFileNamePolicy.localBackupFileName()
        val tempBackupFile = File(context.cacheDir, backupName)
        // B2-DOS-07 (phase-83): the zip is staged to a transient app-private
        // file (never a full in-heap archive), then encrypted file-to-file.
        val stagingZip = File(context.cacheDir, BackupExportPolicy.stagingFileName(backupName))
        // R2-B1D-05 (phase-137): the DB snapshot is staged + verified before it is
        // packed — a torn copy must never reach the archive.
        val stagedDb = File(context.cacheDir, VaultSnapshotCopyPolicy.snapshotStagingFile(backupName))
        try {
            // R2-B1D-05/03 (phase-137): checkpoint FIRST so the snapshot holds every
            // committed frame (a -wal resident write must never silently miss the
            // archive), then re-stamp the tamper baseline (the checkpoint just
            // rewrote the main file). The verified copy then guarantees the staged
            // bytes are a main-file state the source held for the whole copy.
            if (dbFile.exists()) {
                repository.checkpointWal()
                repository.stampDatabaseChecksum(context)
                if (!VaultSnapshotCopyPolicy.checkpointThenCopy(dbFile, stagedDb)) {
                    throw IllegalStateException(ExportSessionPolicy.KEEP_CHANGING_ERROR)
                }
                // Phase-189: pin the DEK this export was HANDED (snapshot-at-entry
                // COPY) for the staged-snapshot prunes — never a re-read of the
                // mutable VaultKeyHolder.dek singleton at prune time. A lock that
                // lands mid-export (SAF picker ON_STOP, screen-off) zeroizes the
                // live array; the pinned copy survives, so the backup runs under
                // the SAME key the export used and the next backup/restore is not
                // poisoned. The copy is zeroized immediately after both prunes.
                val pruneDek = ExportSessionPolicy.pinnedPruneDek(key) { VaultKeyHolder.dek }
                    ?: throw IllegalStateException(ExportSessionPolicy.LOCKED_SNAPSHOT_ERROR)
                try {
                    // R2-b2b4-DOS-01 (phase-149): the version-history retention prune
                    // runs on the STAGED SNAPSHOT — never the live vault — so every
                    // page's newest retained window is what the archive serializes
                    // (a legacy vault that outgrew the cap before this deploy stops
                    // inflating every export forever) and a backup that later fails
                    // (copy teardown, budget rejection, encryption) can never
                    // permanently delete the user's older version history.
                    pruneStagedSnapshotVersions(stagedDb, pruneDek)
                    // R2-b2b4-DOS-02 (phase-150): the live layer-count cap prune runs
                    // on the SAME staged snapshot, so the archive never serializes a
                    // page's retained-but-oversized layers backlog. Mirrors the
                    // version-history trim's safety: a backup that later fails can
                    // never permanently delete a user's extra layers.
                    pruneStagedSnapshotLayers(stagedDb, pruneDek)
                } finally {
                    ExportSessionPolicy.zeroize(pruneDek)
                }
            }
            // R2-B1D-04 (phase-138): the same BackupBudgetPolicy that bounds the
            // restore extractor now bounds the packer, so a backup is only ever
            // SHIPPED if it can be RESTORED — no more "exportable but
            // unrestorable" archives (a DB/import/blob over the per-entry cap, or
            // a vault whose decompressed total exceeds the wire-level 400MB, is
            // rejected loudly at export time, never silently shipped).
            // R2-B1D-04 review (phase-138): this is a DELIBERATE fail-closed trade
            // — a vault holding a single artifact over the 100MB per-entry cap (or
            // whose total exceeds 400MB) becomes un-exportable as-is; the only
            // remedy is shrinking that artifact (e.g. trimming imports/voice
            // blobs). Shipping the archive was previously the "exportable but
            // unrestorable" trap; refusing loudly is preferred.
            val packAccounting = BackupBudgetPolicy.Accounting()
            BackupExportPolicy.zipVaultEntriesToStream(FileOutputStream(stagingZip)) { zos ->
                fun packFile(entryName: String, source: File) {
                    BackupBudgetPolicy.claimPackFile(packAccounting, entryName, source.length())
                    zos.putNextEntry(ZipEntry(entryName))
                    FileInputStream(source).use { fis -> fis.copyTo(zos) }
                    zos.closeEntry()
                }

                if (dbFile.exists()) {
                    packFile("noteflow.sqlite", stagedDb)
                }

                if (importsDir.exists()) {
                    importsDir.walkTopDown().filter { it.isFile }.forEach { file ->
                        packFile("imports/" + file.relativeTo(importsDir).path.replace('\\', '/'), file)
                    }
                }

                // B1-DB-3 (phase-54): the ENCRYPTED voice-note blobs ride in the
                // backup too, so a restore round-trip carries the audio. Only
                // `.enc` blobs are packed — any surviving plaintext `.m4a` (a
                // not-yet-migrated pre-fix recording) is explicitly EXCLUDED so a
                // backup never transports raw audio bytes.
                val voiceNotesDir = File(context.filesDir, "voice_notes")
                if (voiceNotesDir.exists()) {
                    voiceNotesDir.listFiles()?.filter { it.isFile && VoiceNoteCrypto.isEncryptedBlobName(it.name) }
                        ?.sortedBy { it.name }
                        ?.forEach { file ->
                            packFile("voice_notes/${file.name}", file)
                        }
                }
            }

            if (backupPassword != null && key != null) {
                // v3 (NFLB3, B2-CRYPTO-04): password-derived portable backup.
                // B1-CRYPTO-04/phase-63 + B2-CRYPTO-04 (phase-84): the backup password
                // rides the vault to PUBLIC Downloads/WebDAV, so it must clear the SAME
                // strength bar as the vault master password (was: bare `length >= 6`,
                // no complexity — crackable offline in hours-days on a GPU PBKDF2 rig).
                // The verdict message + the offline-backup warning are surfaced loudly;
                // a weak-credential backup is rejected, never written silently.
                BackupPasswordPolicy.requireStrongBackupPassword(backupPassword)
                val salt = EncryptionService.generateSalt()
                val kek = EncryptionService.deriveKey(backupPassword, salt)
                try {
                    // B2-CRYPTO-04 (phase-84): split the DEK-wrapping key. A fresh
                    // random 32-byte wrap key is halved: part1 rides in the (public)
                    // header, part2 is embedded at the start of the password-encrypted
                    // payload. An offline attacker holding the header alone has HALF a
                    // key — they can neither form the wrap key nor test a password
                    // guess against the wrapped DEK; every crack attempt must fully
                    // decrypt the payload (a full-vault GCM on top of PBKDF2) before
                    // the DEK can even be attempted. v2 backups (NFLB2) remain fully
                    // restoreable — the parse/verify paths below read both magics.
                    val wrapKey = EncryptionService.generateDek()
                    val wrapKeyPart1 = ByteArray(BACKUP_WRAP_KEY_HALF_SIZE)
                    val wrapKeyPart2 = ByteArray(BACKUP_WRAP_KEY_HALF_SIZE)
                    System.arraycopy(wrapKey, 0, wrapKeyPart1, 0, BACKUP_WRAP_KEY_HALF_SIZE)
                    System.arraycopy(wrapKey, BACKUP_WRAP_KEY_HALF_SIZE, wrapKeyPart2, 0, BACKUP_WRAP_KEY_HALF_SIZE)
                    try {
                        val wrappedDek = EncryptionService.encryptAad(key, wrapKey, BACKUP_DEK_WRAP_AAD)
                        val payloadIv = EncryptionService.newIv()
                        val header = buildBackupHeaderV3(salt, payloadIv, wrapKeyPart1, wrappedDek)
                        // B2-DOS-07 (phase-83) + phase-84: streamed file-to-file
                        // encryption. The plaintext stream is [16B part2] || zip, so
                        // the payload-derived key half rides INSIDE the ciphertext.
                        BackupExportPolicy.encryptStreamGcm(
                            java.io.SequenceInputStream(
                                java.io.ByteArrayInputStream(wrapKeyPart2),
                                FileInputStream(stagingZip)
                            ),
                            FileOutputStream(tempBackupFile),
                            kek,
                            payloadIv,
                            header,
                            BACKUP_PAYLOAD_AAD
                        )
                    } finally {
                        wrapKey.fill(0.toByte())
                        wrapKeyPart1.fill(0.toByte())
                        wrapKeyPart2.fill(0.toByte())
                    }
                } finally {
                    kek.fill(0.toByte())
                }
            } else {
                // H4: a backup must never silently be a plain zip containing
                // journal/voice/image files. It is either password-encrypted (v3
                // NFLB3 above) or device-keyed (legacy) — no unencrypted fallback.
                require(key != null) {
                    "Backup rejected: no encryption key is available and no backup password was provided. Unlock the vault before exporting."
                }
                // B2-DOS-07 (phase-83): streamed device-keyed encryption + Base64
                // write with the SAME wire format (legacy `EncryptionService.encrypt`
                // output), never the ~1.37x in-heap Base64 expansion.
                BackupExportPolicy.encryptStreamDeviceKeyedBase64(
                    FileInputStream(stagingZip),
                    FileOutputStream(tempBackupFile),
                    key
                )
            }
            // R2-B1D-04 review (phase-138): the pack budget is the SUM OF THE
            // SOURCE lengths, but the RESTORE wire gate rejects any archive whose
            // ENCRYPTED output grew past the 400 MB input cap (Base64 ~1.37x for
            // legacy, or incompressible media near the ceiling plus zip header +
            // AAD + GCM-tag overhead). Enforce the SAME cap on the finished
            // encrypted file so export never ships an archive restore would
            // refuse — closes the last "exportable but unrestorable" band.
            if (tempBackupFile.length() > MAX_BACKUP_INPUT_BYTES) {
                tempBackupFile.delete()
                throw IllegalStateException(
                    "Backup rejected: the encrypted backup is larger than the restoreable size " +
                        "(max ${MAX_BACKUP_INPUT_BYTES / (1024L * 1024L)}MB)."
                )
            }
        } finally {
            stagingZip.delete()
            stagedDb.delete()
        }
        // Phase 156: this is the SINGLE success chokepoint for every backup
        // producer (Home menu, WebDAV, LocalSend) — record the last-backup
        // timestamp here so the home "days since backup" chip + ⋮ nudge stay
        // truthful across all paths.
        SettingsManager(context.applicationContext).lastBackupTimestamp = System.currentTimeMillis()
        tempBackupFile
    }

    private fun copyWithLimit(
        zis: ZipInputStream,
        fos: FileOutputStream,
        accounting: BackupBudgetPolicy.Accounting,
        entry: ZipEntry
    ): Long {
        val buffer = ByteArray(8192)
        var entryBytes = 0L
        var bytesRead: Int
        while (zis.read(buffer).also { bytesRead = it } != -1) {
            entryBytes += bytesRead
            // R2-B1D-04 (phase-138): the shared budget — per-entry cap, the
            // 100x expansion-ratio seal (keyed off ACTUAL bytes read, so a
            // forged declared size can never bypass it) and the ≤400MB total
            // ceiling, all from BackupBudgetPolicy so export and restore stay
            // in parity.
            BackupBudgetPolicy.claimRestoreChunk(accounting, entryBytes, entry.size, entry.compressedSize)
            fos.write(buffer, 0, bytesRead)
        }
        BackupBudgetPolicy.settleRestoreEntry(accounting, entryBytes)
        return entryBytes
    }

    /**
     * R2-B1D-04 (phase-138): the decrypted v2/v3 payload now lives as a FILE (a
     * staging zip under cacheDir) instead of a ByteArray — [zipFile] is at
     * [offsetBytes] into that file ([BACKUP_WRAP_KEY_HALF_SIZE] for v3, 0 for
     * v2), [dek] is the unwrapped backup vault DEK as zeroizable BYTES (phase-145
     * R2-B1C-03: never an immutable hex String, so the backup DEK does not linger
     * as hex across the restore), and [kek] is the KEK handed to importBackup for
     * zeroization (never held past that call).
     */
    internal data class BackupV2Payload(
        val zipFile: File,
        val offsetBytes: Int,
        val dek: ByteArray?,
        val kek: ByteArray?
    )

    /** A unique transient staging file next to the input (deleted after use). */
    private fun newRestoreStagingFile(dir: File?): File {
        val base = dir ?: File(System.getProperty("java.io.tmpdir") ?: ".")
        return File(base, "restore_decrypt_${System.nanoTime()}_${(Math.random() * 1_000_000).toInt()}.zip")
    }

    /** True iff [wrappedDek] opens with [kek] under the DEK-wrap AAD (cheap probe). */
    private fun passwordProbe(wrappedDek: ByteArray, kek: ByteArray): Boolean {
        return try {
            EncryptionService.decryptAad(wrappedDek, kek, BACKUP_DEK_WRAP_AAD).also { it.fill(0.toByte()) }
            true
        } catch (t: Exception) {
            false
        }
    }

    /**
     * R2-B1D-04 (phase-138): AES-GCM payload decrypt FILE-to-FILE, mirroring
     * [BackupExportPolicy.encryptStreamGcm] — [headerSize] bytes are skipped off
     * the encrypted file, then the bounded chunk loop writes the plaintext
     * directly into [destFile]. The encrypted archive and the decrypted zip are
     * never BOTH in heap (the old `ByteArrayOutputStream` + `doFinal` peak).
     *
     * v2 retries the pre-B2-CRYPTO-03 zero-AAD layout on an AEADBadTag exactly
     * like the one-shot [decryptBackupPayload]; v3 (which only ever existed in
     * the AAD-bound form) does not. Returns true on success; a wrong key or a
     * corrupt/forged payload surfaces via the final `doFinal` tag check.
     */
    private fun decryptPayloadToFile(
        backupFile: File,
        headerSize: Int,
        destFile: File,
        kek: ByteArray,
        payloadIv: ByteArray,
        header: ByteArray,
        payloadAad: ByteArray,
        allowZeroAadRetry: Boolean
    ): Boolean {
        fun openCipherStream(): InputStream {
            val raw = FileInputStream(backupFile)
            BackupExportPolicy.skipFully(raw, headerSize.toLong())
            return raw
        }
        try {
            BackupExportPolicy.decryptStreamGcm(openCipherStream(), FileOutputStream(destFile), kek, payloadIv, header, payloadAad)
            return true
        } catch (e: javax.crypto.AEADBadTagException) {
            if (!allowZeroAadRetry) throw e
            // The AAD-bound attempt already closed destFile; a fresh FileOutputStream
            // truncates the partial plaintext before the legacy retry re-decrypts.
            BackupExportPolicy.decryptStreamGcmLegacyZeroAad(openCipherStream(), FileOutputStream(destFile), kek, payloadIv)
            return true
        }
    }

    /**
     * B2-CRYPTO-04 (phase-84): v3 (NFLB3) payload decrypt — the AAD-bound
     * format is the ONLY format v3 has ever been written in, so unlike the v2
     * reader there is deliberately NO legacy zero-AAD retry (a genuine v3
     * payload's tag covers BACKUP_PAYLOAD_AAD + the exact header, so an
     * un-AADed retry can never rescue it and would only mask a splice).
     */
    internal fun decryptBackupPayloadV3(cipherText: ByteArray, kek: ByteArray, payloadIv: ByteArray, header: ByteArray): ByteArray {
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            javax.crypto.Cipher.DECRYPT_MODE,
            javax.crypto.spec.SecretKeySpec(kek, "AES"),
            javax.crypto.spec.GCMParameterSpec(128, payloadIv)
        )
        cipher.updateAAD(BACKUP_PAYLOAD_AAD)
        cipher.updateAAD(header)
        return cipher.doFinal(cipherText)
    }

    /** Parsed backup header, format-agnostic (v2 NFLB2 / v3 NFLB3). */
    private data class BackupHeaderParts(
        val version: Int, // 2 (NFLB2) or 3 (NFLB3)
        val magic: ByteArray,
        val salt: ByteArray,
        val iv: ByteArray,
        val wrapKeyPart1: ByteArray?, // NFLB3 only: first half of the DEK-wrap key
        val wrappedDek: ByteArray,
        val header: ByteArray,
        val headerSize: Int
    ) {
        /** Bytes of payload-derived key material a v3 payload prefixes (v2: none). */
        val payloadKeyPrefix: Int get() = if (version == 3) BACKUP_WRAP_KEY_HALF_SIZE else 0
    }

    /** Assembles a 32-byte wrap key from its two 16-byte halves (zeroize after use). */
    private fun combineWrapKey(firstHalf: ByteArray, secondHalf: ByteArray): ByteArray {
        val out = ByteArray(firstHalf.size + secondHalf.size)
        System.arraycopy(firstHalf, 0, out, 0, firstHalf.size)
        System.arraycopy(secondHalf, 0, out, firstHalf.size, secondHalf.size)
        return out
    }

    /**
     * Parses a v2 ([NFLB2]) or v3 ([NFLB3]) header, or returns null when
     * [rawBytes] does not start with either magic (a legacy/device-keyed file).
     */
    private fun parseBackupHeader(rawBytes: ByteArray): BackupHeaderParts? {
        val magicV3 = BACKUP_MAGIC_V3.toByteArray(Charsets.US_ASCII)
        val magicV2 = BACKUP_MAGIC.toByteArray(Charsets.US_ASCII)
        val isV3 = rawBytes.size > magicV3.size && rawBytes.copyOfRange(0, magicV3.size).contentEquals(magicV3)
        val magic = if (isV3) magicV3 else magicV2
        if (rawBytes.size <= magic.size) return null
        if (!rawBytes.copyOfRange(0, magic.size).contentEquals(magic)) return null

        val part1Size = if (isV3) BACKUP_WRAP_KEY_HALF_SIZE else 0
        val headerSize = magic.size + BACKUP_SALT_SIZE + BACKUP_IV_SIZE + part1Size + BACKUP_WRAPPED_DEK_SIZE
        if (rawBytes.size <= headerSize) return null

        var off = magic.size
        val salt = rawBytes.copyOfRange(off, off + BACKUP_SALT_SIZE); off += BACKUP_SALT_SIZE
        val iv = rawBytes.copyOfRange(off, off + BACKUP_IV_SIZE); off += BACKUP_IV_SIZE
        val part1 = if (isV3) rawBytes.copyOfRange(off, off + BACKUP_WRAP_KEY_HALF_SIZE) else null
        if (isV3) off += BACKUP_WRAP_KEY_HALF_SIZE
        val wrappedDek = rawBytes.copyOfRange(off, headerSize)
        return BackupHeaderParts(
            if (isV3) 3 else 2, magic, salt, iv, part1, wrappedDek,
            rawBytes.copyOfRange(0, headerSize), headerSize
        )
    }

    /** Bytes of the backup HEAD probe this file reads — enough for either magic,
     *  the full v2/v3 header fields and the PK signature. */
    private const val BACKUP_HEAD_READ_BYTES = 128

    /**
     * R2-B1D-04 (phase-138): reads up to [maxBytes] bytes from the head of
     * [file] (fewer on short files) — a small, constant-memory probe. Never a
     * whole-file read; the reason no restore step needs the archive in heap.
     */
    internal fun readFileHead(file: File, maxBytes: Int = BACKUP_HEAD_READ_BYTES): ByteArray {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(maxBytes)
            var total = 0
            var idle = 0
            while (total < maxBytes) {
                val n = input.read(buffer, total, maxBytes - total)
                if (n < 0) break
                if (n == 0) {
                    if (++idle > 8) break
                    continue
                }
                idle = 0
                total += n
            }
            return if (total == maxBytes) buffer else buffer.copyOf(total)
        }
    }

    /**
     * R2-B1D-04 (phase-138): the file-based restore parse. Reads only the 128-byte
     * head to classify the format, then streams EACH candidate's payload decrypt
     * file-to-file ([decryptPayloadToFile]) — the decrypted zip lands in a
     * transient staging file, never a full in-heap byte array. The KEK ownership
     * contract is unchanged: the returned payload carries the elected KEK and
     * importBackup zeroizes it on every outcome.
     */
    internal fun tryParseBackupV2File(backupFile: File, backupPassword: String?): BackupV2Payload? {
        val h = parseBackupHeader(readFileHead(backupFile)) ?: return null

        if (backupPassword == null) {
            throw IllegalStateException("This backup is protected by a password. Enter the backup password to restore.")
        }
        var kek: ByteArray? = null
        var currentStaging: File? = null
        try {
            // B2-CRYPTO-07 (phase-113): try the NFKC-normalized password first
            // (the form used to WRITE keys since phase-113); if that yields a
            // plain wrong-password outcome and the raw input is not already
            // normalized, retry the legacy raw bytes so a pre-fix backup whose
            // password was set with a non-NFKC byte sequence still restores.
            for (derivedKek in EncryptionService.deriveKeyCandidates(backupPassword, h.salt)) {
                kek = derivedKek
                val staging = newRestoreStagingFile(backupFile.parentFile)
                currentStaging = staging
                var payloadGood = try {
                    decryptPayloadToFile(
                        backupFile, h.headerSize, staging, derivedKek, h.iv, h.header, BACKUP_PAYLOAD_AAD,
                        allowZeroAadRetry = h.version == 2
                    )
                } catch (e: Exception) {
                    staging.delete()
                    // R2-B1D-04 review (phase-138): the wire decrypt never raises a
                    // human "corrupted…" message (it surfaces AEADBadTagException /
                    // IOException), so corruption is decided HERE, per candidate:
                    // v3 re-tries the other byte form (no cheap DEK probe to prove
                    // which candidate was right), v2 uses the wrapped-DEK probe.
                    if (h.version == 3) {
                        // v3 has no cheap DEK probe, so a payload tag failure is
                        // re-tried against the other candidate form and only
                        // reported as 'Incorrect backup password' once both fail
                        // (see B2Crypto04 REPORT).
                        false
                    } else {
                        // v2: distinguish a wrong password from a corrupt payload
                        // exactly like the old decryptBackupPayloadOrThrow — the
                        // wrapped-DEK probe proves whether THIS candidate was right.
                        if (passwordProbe(h.wrappedDek, derivedKek)) {
                            throw IllegalArgumentException(
                                "Backup appears corrupted: the header and the encrypted payload do not match.", e
                            )
                        }
                        false
                    }
                }
                if (!payloadGood) {
                    staging.delete()
                    currentStaging = null
                    // Wrong-password candidate — zeroize it before trying the next.
                    kek?.fill(0.toByte())
                    kek = null
                    continue
                }
                // B2-CRYPTO-04: v3 payload = [16B wrapKeyPart2][zip...]; the
                // header alone never forms the wrap key, the payload-derived
                // half must be recovered from the decrypted bytes first.
                var wrapKey: ByteArray? = null
                var part2: ByteArray? = null
                val offsetBytes: Int
                if (h.version == 3) {
                    if (staging.length() <= h.payloadKeyPrefix) {
                        staging.delete()
                        throw IllegalArgumentException(
                            "Backup appears corrupted: the encrypted payload is too short."
                        )
                    }
                    part2 = readFileHead(staging, h.payloadKeyPrefix)
                    wrapKey = combineWrapKey(h.wrapKeyPart1!!, part2!!)
                    offsetBytes = h.payloadKeyPrefix
                } else {
                    offsetBytes = 0
                }
                val dek = try {
                    val unwrapKey = wrapKey ?: derivedKek
                    // R2-B1C-03 (phase-145): the unwrapped backup DEK travels the
                    // restore as zeroizable BYTES (the pre-fix code hex-encoded it
                    // into an immutable String that survived the whole pipeline).
                    // Ownership hands to importBackup, which zeroizes it.
                    EncryptionService.decryptAad(h.wrappedDek, unwrapKey, BACKUP_DEK_WRAP_AAD)
                } catch (e: Exception) {
                    staging.delete()
                    // v3: correct password + untangled payload, but the DEK did
                    // not open with the reassembled wrap key — a header/payload
                    // splice. v2 never reaches here (its probe ran inside the
                    // payload decrypt). Treat as corruption so the user is not
                    // told their (correct) password is wrong.
                    throw IllegalArgumentException(
                        "Backup appears corrupted: the header and the encrypted payload do not match.", e
                    )
                } finally {
                    wrapKey?.fill(0.toByte())
                    part2?.fill(0.toByte())
                }
                // KEK ownership hands off to importBackup, which zeroizes it
                // on every outcome; the failure paths below zeroize it too.
                return BackupV2Payload(staging, offsetBytes, dek, derivedKek)
            }
            throw IllegalArgumentException("Incorrect backup password.")
        } catch (e: Exception) {
            currentStaging?.delete()
            kek?.fill(0.toByte())
            throw e
        }
    }

    /**
     * H1: rejects a wrong backup password BEFORE the live vault is closed or
     * touched. v2's wrapped DEK opens only with the correct password (a cheap
     * GCM-tag probe). v3 has no such cheap authenticator by design (the header
     * carries only half of the wrap key), so a v3 check performs ONE full
     * FILE-streamed payload decrypt + DEK unwrap (R2-B1D-04: written to a
     * transient staging file, never an in-heap array — same memory budget and
     * cost class as the import itself, and the only way the password can be
     * tested at all). Legacy backups carry no password and are skipped.
     */
    fun validateBackupPasswordFile(backupFile: File, backupPassword: String?) {
        val h = parseBackupHeader(readFileHead(backupFile)) ?: return

        if (backupPassword == null) {
            throw IllegalStateException("This backup is protected by a password. Enter the backup password to restore.")
        }
        var kek: ByteArray? = null
        var staging: File? = null
        try {
            // B2-CRYPTO-07 (phase-113): normalized password first, then the raw
            // legacy bytes when it differs — see tryParseBackupV2File.
            for (derivedKek in EncryptionService.deriveKeyCandidates(backupPassword, h.salt)) {
                kek = derivedKek
                if (h.version == 2) {
                    // v2 cheap probe: a 61-byte wrapped-DEK GCM-tag check.
                    try {
                        EncryptionService.decryptAad(h.wrappedDek, derivedKek, BACKUP_DEK_WRAP_AAD)
                            .also { it.fill(0.toByte()) }
                        return
                    } catch (e: Exception) {
                        kek?.fill(0.toByte())
                        kek = null
                        continue
                    }
                }
                // v3: ONE full streaming payload decrypt, then unwrap the DEK
                // with the reassembled split key.
                val s = newRestoreStagingFile(backupFile.parentFile)
                staging = s
                var payloadGood = try {
                    decryptPayloadToFile(
                        backupFile, h.headerSize, s, derivedKek, h.iv, h.header, BACKUP_PAYLOAD_AAD,
                        allowZeroAadRetry = false
                    )
                } catch (e: Exception) {
                    false
                }
                if (!payloadGood) {
                    s.delete()
                    staging = null
                    kek?.fill(0.toByte())
                    kek = null
                    continue
                }
                try {
                    if (s.length() <= h.payloadKeyPrefix) {
                        throw IllegalArgumentException("Backup appears corrupted: the encrypted payload is too short.")
                    }
                    val part2 = readFileHead(s, h.payloadKeyPrefix)
                    val wrapKey = combineWrapKey(h.wrapKeyPart1!!, part2)
                    try {
                        EncryptionService.decryptAad(h.wrappedDek, wrapKey, BACKUP_DEK_WRAP_AAD)
                            .also { it.fill(0.toByte()) }
                        return
                    } finally {
                        wrapKey.fill(0.toByte())
                        part2.fill(0.toByte())
                    }
                } catch (e: IllegalArgumentException) {
                    throw e
                } catch (e: Exception) {
                    throw IllegalArgumentException(
                        "Backup appears corrupted: the header and the encrypted payload do not match.", e
                    )
                }
            }
            throw IllegalArgumentException("Incorrect backup password.")
        } finally {
            staging?.delete()
            kek?.fill(0.toByte())
        }
    }

    private fun rekeySqlcipherDb(context: Context, dbFile: File, oldDekHex: String, newDekHex: String) {
        if (oldDekHex == newDekHex) return
        System.loadLibrary("sqlcipher")
        // R2-B1C-03 (phase-145): open with the old passphrase as ASCII bytes —
        // the String-typed overload would clone it internally. Zeroized after use.
        val oldKeyBytes = oldDekHex.toAsciiBytes()
        try {
            val raw = net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
                dbFile, oldKeyBytes, null, null, null
            )
            try {
                raw.rawExecSQL("PRAGMA rekey = '$newDekHex'")
            } finally {
                raw.close()
            }
        } finally {
            oldKeyBytes.fill(0.toByte())
        }
    }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    /** R2-B1C-03 (phase-145): ASCII bytes of a hex passphrase string, so the
     * SQLCipher open gets a zeroizable ByteArray instead of a String the JVM
     * keeps around immutably. The CALLER zeroizes the returned array. */
    private fun String.toAsciiBytes(): ByteArray = toByteArray(Charsets.US_ASCII)

    /** A unique transient staging file under the app-private cache dir. */
    private fun newRestoreStagingFile(context: Context, tag: String): File =
        File(context.cacheDir, "restore_${tag}_${System.nanoTime()}_${(Math.random() * 1_000_000).toInt()}")

    /**
     * R2-B1D-04 (phase-138): stages a ContentResolver URI into an app-private
     * cache file with the SAME bounded, fail-closed read the old restore paths
     * applied in-heap — so the HomeScreen/CorruptionRecovery pickers no longer
     * hold the whole archive in memory just to hand it to importBackup. Returns
     * null when the URI cannot be opened; an over-budget file throws loudly
     * (never silently truncated). The caller owns and deletes the returned file.
     */
    suspend fun stageBackupUriToFile(context: Context, uri: Uri, maxBytes: Long = MAX_BACKUP_INPUT_BYTES): File? =
        withContext(Dispatchers.IO) {
            val stream = try {
                context.contentResolver.openInputStream(uri)
            } catch (e: Exception) {
                return@withContext null
            } ?: return@withContext null
            val staged = newRestoreStagingFile(context, "input")
            try {
                staged.outputStream().use { out ->
                    stream.use { input ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        var read = input.read(buffer)
                        while (read != -1) {
                            total += read
                            if (total > maxBytes) {
                                throw IllegalStateException("Backup file too large (max ${maxBytes / (1024L * 1024L)}MB).")
                            }
                            out.write(buffer, 0, read)
                            read = input.read(buffer)
                        }
                    }
                }
                staged
            } catch (e: Throwable) {
                staged.delete()
                throw e
            }
        }

    /**
     * Phase 190: stages a ContentResolver URI into an app-private cacheDir file
     * as a `*.apk` with the SAME bounded, fail-closed streaming the restore
     * path uses — the APK picker previously read the whole package through
     * [readUriBytes] into a heap `ByteArray` and copied it AGAIN with
     * `writeBytes` (a 100+ MB APK was 2-3x in heap at once → OOM/ANR on
     * low-RAM devices right as the user tried to update).
     *
     * The result is a DIRECT child of `cacheDir` — `checkForDownloadedUpdates`
     * scans exactly `filesDir` + `cacheDir` direct children through the
     * B1-PLAT-7 `UpdateTrustPolicy.isScanSafeDirectory` filter, so a staged
     * file is found by the very next "Scan App Storage". The APK never lands
     * in a publicly writable directory (B1-PLAT-7) and never a note.
     *
     * Returns null when the URI cannot be opened; an over-budget file throws
     * loudly (never silently truncated). The caller owns and may delete the
     * returned file. The staged name is a fresh UUID so concurrent/multi-file
     * stagings (a multi-APK share) can never collide and overwrite each other.
     */
    suspend fun stageApkUriToFile(
        context: Context,
        uri: Uri,
        maxBytes: Long = MAX_APK_INPUT_BYTES
    ): File? = withContext(Dispatchers.IO) {
        val stream = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            return@withContext null
        } ?: return@withContext null
        val staged = File(context.cacheDir, "inkflow_update_${UUID.randomUUID()}.apk")
        try {
            staged.outputStream().use { out ->
                stream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    var read = input.read(buffer)
                    while (read != -1) {
                        total += read
                        if (total > maxBytes) {
                            throw IllegalStateException(
                                "APK file too large (max ${maxBytes / (1024L * 1024L)}MB)."
                            )
                        }
                        out.write(buffer, 0, read)
                        read = input.read(buffer)
                    }
                }
            }
            staged
        } catch (e: Throwable) {
            staged.delete()
            throw e
        }
    }

    suspend fun importBackup(
        context: Context,
        backupFile: File,
        key: ByteArray?,
        backupPassword: String? = null,
        // R2-B1D-02 (phase-135): ONLY set after the user explicitly confirmed the
        // "start fresh" prompt for a zero-row (empty) vault. Never bypasses the
        // structural gate.
        allowEmptyVault: Boolean = false
    ) = withContext(Dispatchers.IO) {

        // B5/34.5: hard cap on input size before any decryption/decompression work.
        if (backupFile.length() > MAX_BACKUP_INPUT_BYTES) {
            throw IllegalStateException("Backup file too large (max 400MB).")
        }

        // v2/v3 password-derived format (portable across devices). R2-B1D-04:
        // the payload decrypt is FILE-TO-FILE — the decrypted zip is a staging
        // file next to the input, deleted on every outcome, never a full in-heap
        // ByteArrayOutputStream + decrypted array (the old ~800MB restore peak).
        tryParseBackupV2File(backupFile, backupPassword)?.let { v2 ->
            try {
                val currentDek = key
                    ?: throw IllegalStateException("Cannot restore: no data key available on this device.")

                if (v2.dek == null) {
                    // Zip decrypted but the backup's DEK did not — backup is corrupt.
                    throw IllegalStateException("Backup appears corrupted: could not unlock the backup key.")
                }

                // R2-B1C-03 (phase-145): both DEKs travel the restore as zeroizable
                // ByteArrays. No hex String is created here — hex is scoped to the
                // smallest SQLCipher-touching function (validateAndPrepareRestoredDb).
                restoreFromZip(context, v2.zipFile, v2.offsetBytes, v2.dek, currentDek, allowEmptyVault)
            } finally {
                // The derived KEK is zeroized on every restore outcome (success,
                // corrupt-DEK early throw, no-data-key early throw, restore failure).
                v2.kek?.fill(0.toByte())
                // R2-B1C-03 (phase-145): the backup DEK is owned by the restore
                // (never the live vault key) — zeroize it here too. Double-zeroize
                // with validateAndPrepareRestoredDb is harmless.
                v2.dek?.fill(0.toByte())
                // The decrypted zip staging file is transient — never persists.
                v2.zipFile.delete()
            }
            return@withContext
        }

        // Legacy path: zip encrypted with the device DEK.
        // B1-DB-7 (phase-56): a raw PK-headed payload is a legacy PLAIN
        // (unencrypted, unsigned) backup and is REJECTED outright. The app has
        // not produced keyless plain backups since the H4 fix, and the inner
        // SQLCipher DB of such a zip is openable with the empty passphrase —
        // exactly the vector that let an attacker-crafted vault swap through
        // (validate-pass, re-key to the victim's DEK, HMAC-rearm, move over the
        // live vault). Only NFLB2 password-protected (v2) or device-DEK-encrypted
        // backups are restoreable; both are authenticated by unguessable keys.
        if (isPlainPkBackupFile(backupFile)) {
            throw IllegalStateException(
                "Restore rejected: this is an unencrypted (unsigned) backup. " +
                    "Only password-protected or device-keyed backups can be restored."
            )
        }
        if (key == null) {
            throw IllegalStateException("This backup is encrypted. Please set and verify your Master Password first.")
        }
        // R2-B1D-04: the legacy device-keyed payload (Base64 of
        // [version][iv][GCM ciphertext+tag] under FIELD_AAD) is decrypted
        // FILE-TO-FILE through a streaming Base64 decoder + bounded chunk loop —
        // never `String(bytes) + doFinal` (a ~1.37x Base64 amplification AND the
        // whole decrypted zip in heap).
        val stagingZip = newRestoreStagingFile(context, "decrypted")
        try {
            decryptDeviceKeyedToFile(backupFile, stagingZip, key)
            restoreFromZip(context, stagingZip, 0, null, key, allowEmptyVault)
        } finally {
            stagingZip.delete()
        }
    }

    /**
     * R2-B1D-04 (phase-138): decrypts the legacy device-keyed backup file
     * ([version byte][12-byte IV][FIELD_AAD GCM ciphertext+tag], Base64 text on
     * disk) file-to-file into [destFile]. Streams through a `Base64.Decoder` so
     * the ~1.37x Base64 expansion and the decrypted zip are never both in heap;
     * memory is one 64 KiB chunk at a time.
     */
    private fun decryptDeviceKeyedToFile(backupFile: File, destFile: File, key: ByteArray) {
        val decoded = java.util.Base64.getDecoder().wrap(BufferedInputStream(FileInputStream(backupFile)))
        try {
            val version = decoded.read()
            if (version != EncryptionService.PAYLOAD_VERSION.toInt()) {
                throw IllegalArgumentException("Unsupported payload format: missing version marker")
            }
            val iv = ByteArray(EncryptionService.GCM_IV_LENGTH)
            var ivRead = 0
            while (ivRead < iv.size) {
                val n = decoded.read(iv, ivRead, iv.size - ivRead)
                if (n < 0) throw IllegalArgumentException("Invalid encrypted payload")
                if (n == 0) continue
                ivRead += n
            }
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                javax.crypto.Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                javax.crypto.spec.GCMParameterSpec(EncryptionService.GCM_TAG_LENGTH, iv)
            )
            cipher.updateAAD(EncryptionService.FIELD_AAD)
            FileOutputStream(destFile).use { out ->
                val buffer = ByteArray(64 * 1024)
                var idle = 0
                while (true) {
                    val n = decoded.read(buffer)
                    if (n < 0) break
                    if (n == 0) {
                        if (++idle > 16) {
                            throw java.io.IOException("Decrypt stream made no progress; aborting restore decryption")
                        }
                        continue
                    }
                    idle = 0
                    out.write(cipher.update(buffer, 0, n))
                }
                // doFinal verifies the GCM tag — a corrupt/forged backup throws here.
                out.write(cipher.doFinal())
            }
        } finally {
            runCatching { decoded.close() }
        }
    }

    /**
     * 34.1: Restore is now transactional. The backup is fully extracted to a
     * temp dir, the SQLCipher database copy is integrity-checked, re-keyed and
     * field-re-encrypted BEFORE the live vault is touched. Only then are files
     * swapped into place and the HMAC baseline re-armed to the restored DB.
     *
     * R2-B1D-02 (phase-135): [allowEmptyVault] is the escape hatch for the
     * zero-row-but-real-schema case — the caller has already shown the user the
     * empty-vault "start fresh" confirmation. It NEVER bypasses the structural
     * gate (missing tables / blank user_version are rejected regardless).
     *
     * R2-B1D-04 (phase-138): the archive is now a FILE ([zipFile] at byte
     * [offsetBytes] — 0 for v2/legacy, [BACKUP_WRAP_KEY_HALF_SIZE] for v3 whose
     * payload prefixes the split key half) plus a streamed extraction; no caller
     * ever hands a whole-archive ByteArray here anymore.
     */
     private fun restoreFromZip(context: Context, zipFile: File, offsetBytes: Int, backupDek: ByteArray?, currentDek: ByteArray?, allowEmptyVault: Boolean = false) {
        val tempRoot = File(context.cacheDir, "restore_tmp_${System.currentTimeMillis()}")
        tempRoot.mkdirs()
        val tempDb = File(tempRoot, "noteflow.sqlite")
        val tempImports = File(tempRoot, "imports")
        val tempVoiceNotes = File(tempRoot, "voice_notes")
        try {
            extractBackupEntriesTo(zipFile, offsetBytes, tempDb, tempImports, tempVoiceNotes)
            validateAndPrepareRestoredDb(context, tempDb, backupDek, currentDek, tempVoiceNotes, allowEmptyVault)
            // B2/34.1 + 34.8: re-arm the tamper baseline to the restored DB copy
            // BEFORE it swaps into place. A DB we cannot checksum must never
            // become the live vault — escalate to the hard restore-block.
            if (!DatabaseSecurityHelper.rearmBaselineFromFile(context, tempDb)) {
                DatabaseSecurityHelper.setRestoreBlock(context)
                throw IllegalStateException("Restore rejected: could not verify the restored database.")
            }
            commitRestoredFiles(context, tempDb, tempImports, tempVoiceNotes)
        } finally {
            tempRoot.deleteRecursively()
        }
    }

    private data class RestoredEntries(val tempRoot: File, val dbFile: File, val importsDir: File, val voiceNotesDir: File)

    /**
     * R2-B1D-04 (phase-138): streams the zip FILE through a bounded extractor.
     * [BackupBudgetPolicy] now owns the restorable-size contracts (100MB per
     * entry, 400MB total, 40k entry-count belt, 100x ratio seal), and the SAME
     * policy gates the export packer — an archive is only ever shipped if it can
     * be unpacked here.
     */
    private fun extractBackupEntriesTo(zipFile: File, offsetBytes: Int, tempDb: File, tempImports: File, tempVoiceNotes: File) {
        var sawDatabase = false
        val accounting = BackupBudgetPolicy.Accounting()

        FileInputStream(zipFile).use { raw ->
            if (offsetBytes > 0) BackupExportPolicy.skipFully(raw, offsetBytes.toLong())
            ZipInputStream(raw).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name
                    // R2-B1D-04 review (phase-138): the entry-count belt charges
                    // LEAF entries only, exactly mirroring the export packer's
                    // claimPackFile (which packs files, never directory records) —
                    // a directory record that writes no bytes cannot push an
                    // app-exported archive over the count budget on restore.
                    if (!entry.isDirectory) {
                        accounting.claimEntry()
                        if (entryName == "noteflow.sqlite") {
                            sawDatabase = true
                            tempDb.parentFile?.mkdirs()
                            FileOutputStream(tempDb).use { fos ->
                                copyWithLimit(zis, fos, accounting, entry)
                            }
                        } else if (entryName.startsWith("imports/")) {
                            val relPath = safeImportRelativePath(entryName.substring("imports/".length))
                                ?: throw IllegalStateException("Backup contains an unsafe relative path in the archive.")
                            val targetFile = File(tempImports, relPath)
                            targetFile.parentFile?.mkdirs()
                            FileOutputStream(targetFile).use { fos ->
                                copyWithLimit(zis, fos, accounting, entry)
                            }
                        } else if (entryName.startsWith("voice_notes/")) {
                            // B1-DB-3 (phase-54): encrypted voice blobs ride in the
                            // backup by name; same traversal/zip-bomb protection as
                            // imports.
                            val relPath = safeImportRelativePath(entryName.substring("voice_notes/".length))
                                ?: throw IllegalStateException("Backup contains an unsafe relative path in the archive.")
                            val targetFile = File(tempVoiceNotes, relPath)
                            targetFile.parentFile?.mkdirs()
                            FileOutputStream(targetFile).use { fos ->
                                copyWithLimit(zis, fos, accounting, entry)
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }
        if (!sawDatabase) {
            throw IllegalStateException("Backup contains no noteflow.sqlite database entry.")
        }
        // R2-B1D-02 (phase-135): a 0-byte database entry is a freshly-initialized
        // EMPTY database (or a hostile stub). The candidate-open below would
        // silently initialize it to a blank vault with integrity_check = ok and
        // user_version = 0, so reject it BEFORE any open/rekey/rearm/swap can run.
        if (tempDb.length() == 0L) {
            throw IllegalStateException("Restore rejected: the backup's database is empty.")
        }
    }

    /**
     * Opens a copy of the restored DB, runs PRAGMA integrity_check, re-keys the
     * SQLCipher layer to the current DEK and migrates field-level ciphertexts
     * (34.2) so cross-device restores never double-encrypt.
     *
     * R2-B1D-02 (phase-135): BEFORE the re-key/migrate/swap steps, the copy is
     * classified by [RestoredDbPolicy] — required Room schema tables present,
     * `user_version` >= [RestoredDbPolicy.MIN_USER_VERSION], and a non-zero page
     * row count. A structurally-invalid copy (missing tables / blank DB) aborts
     * the restore and is QUARANTINED (`*.restore-rejected-<ts>`); a valid-schema
     * zero-page copy aborts with [EmptyVaultRestoreDecisionException] so the
     * caller can surface the "start fresh" confirmation and re-run with
     * [allowEmptyVault] only after the user confirmed. The live vault is NEVER
     * swapped or HMAC-re-armed on any of these paths.
     */
    private fun validateAndPrepareRestoredDb(context: Context, tempDb: File, backupDek: ByteArray?, currentDek: ByteArray?, tempVoiceNotes: File, allowEmptyVault: Boolean = false) {
        // R2-B1C-03 (phase-145): the DEKs arrive as zeroizable ByteArrays — hex
        // Strings are created ONLY inside this function, the last point that must
        // hand the SQLCipher String-typed API (PRAGMA rekey, the candidate opens)
        // its passphrases. They are never held across the rest of the restore.
        // `currentDek` is the LIVE vault key held by the repository, so it is
        // COPIED before hex — the caller's array is never zeroized — and both
        // local byte copies are zeroized before this function returns.
        val backupDekOwned = backupDek?.copyOf()
        val currentDekOwned = currentDek?.copyOf()
        val backupDekHex = backupDekOwned?.toHexString()
        val currentDekHex = currentDekOwned?.toHexString()
        try {
            // B1-DB-7 (phase-56): the empty-passphrase SQLCipher candidate is GONE.
            // A plaintext/keyless SQLite is only openable with `""` — with just the
            // backup's own wrapped DEK (v2) or this device's DEK (device-keyed)
            // permitted, an attacker-chosen DB can never pass integrity_check and be
            // re-keyed + HMAC-rearmed into the live vault. The helper also strips any
            // empty string a future caller might pass in, fail-closed.
            val candidates = backupRestoreOpenCandidates(backupDekHex, currentDekHex)
            var openedWith: String? = null
            var userVersion: Long = -1L
            var presentTableCount = 0
            var pageCount = 0L
            for (candidate in candidates) {
                // R2-B1C-03 (phase-145): feed SQLCipher the candidate passphrase as
                // ASCII bytes — converted once here and zeroized after the open, so
                // the String-typed overload never clones it.
                val candidateBytes = candidate.toAsciiBytes()
                try {
                    System.loadLibrary("sqlcipher")
                    val db = net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
                        tempDb, candidateBytes, null, null, null
                    )
                    try {
                        val cursor = db.rawQuery("PRAGMA integrity_check", null)
                        val ok = cursor.moveToFirst() && cursor.getString(0) == "ok"
                        if (ok) {
                            // B2-DOS-01 (phase-50): strip oversized stroke geometry
                            // NOW, while this candidate key can open the backup — an
                            // attacker-planted stroke row whose encrypted pointsJson
                            // (base64 ciphertext ≈ plaintext length, AES-GCM does not
                            // compress) exceeds the budget must never migrate or swap
                            // into the live vault.
                            sanitizeRestoredStrokeGeometry(db)
                            // R2-b2b4-DOS-01 (phase-149): cap a crafted backup's
                            // note_versions table to the newest retained window per
                            // page NOW, while this candidate key can open the file —
                            // the oversized rows never reach the re-key /
                            // field-migration steps nor the live vault, so a
                            // ~5,000-row × ~50 KB-body archive can no longer OOM
                            // the process on version-history open or restore.
                            sanitizeRestoredNoteVersions(db)
                            // R2-b2b4-DOS-02 (phase-150): cap a crafted backup's
                            // `layers` table to the top
                            // LayerRenderBudgetPolicy.MAX_LIVE_LAYER_COUNT rows per
                            // page NOW, while this candidate key can open the file —
                            // the renderer materializes one full-page ARGB bitmap per
                            // visible layer (~10.4 MB at 1080x2400), so an
                            // attacker-planted 40-layer page (~416 MB native) must
                            // never migrate or swap into the live vault and make the
                            // canvas OOM on open.
                            sanitizeRestoredLayerCounts(db)
                            // B1-AUTH-05 (phase-69): strip sourceFilePath rows that
                            // escape the imports root BEFORE they can migrate or swap
                            // into the live vault — the zip entry-names validation
                            // never re-checked this column.
                            sanitizeRestoredSourceFilePaths(db, getImportsDir(context))
                            // H3: read the schema version now, while the DB is open.
                            val versionCursor = db.rawQuery("PRAGMA user_version", null)
                            if (versionCursor.moveToFirst()) userVersion = versionCursor.getLong(0)
                            versionCursor.close()
                            // R2-B1D-02: capture the Room schema identity + note count
                            // under the SAME open, so the decision can never be gamed
                            // between the gate and the swap.
                            presentTableCount = countPresentRestoredTables(db)
                            pageCount = countRestoredRows(db, "pages")
                        }
                        cursor.close()
                        if (ok) { openedWith = candidate; break }
                    } finally {
                        db.close()
                    }
                } catch (e: Exception) {
                    // wrong key or corrupt file — try next candidate
                } finally {
                    candidateBytes.fill(0.toByte())
                }
            }
            if (openedWith == null) {
                throw IllegalStateException("Restore rejected: the backup database is corrupt or was created on a different device.")
            }
            // H3: a newer-schema backup must never swap into the live path — a later
            // fallbackToDestructiveMigration would wipe it. Kept OUTSIDE the candidate
            // loop so the rejection is not swallowed as a wrong-key retry.
            checkRestoredSchemaNotNewer(userVersion, com.authorss81.noteflow.data.db.NoteflowDatabase.SCHEMA_VERSION)

            // R2-B1D-02 (phase-135): structural/content gate — a freshly-initialized
            // EMPTY SQLCipher DB passes candidate-open + integrity_check + user_version
            // <= 9 but is NOT a vault. Refuse it here, never re-arm + swap.
            when (val decision = RestoredDbPolicy.decide(userVersion, presentTableCount, pageCount, allowEmptyVault)) {
                is RestoredDbPolicy.Decision.Reject -> {
                    quarantineRejectedRestoredDb(context, tempDb)
                    throw IllegalStateException(decision.reason)
                }
                RestoredDbPolicy.Decision.EmptyVault -> {
                    quarantineRejectedRestoredDb(context, tempDb)
                    throw EmptyVaultRestoreDecisionException()
                }
                RestoredDbPolicy.Decision.Pass -> Unit
            }

            if (currentDekHex != null && openedWith != currentDekHex) {
                rekeySqlcipherDb(context, tempDb, openedWith, currentDekHex)
                if (!openedWith.isNullOrEmpty() && openedWith != currentDekHex) {
                    try {
                        migrateFieldCiphertexts(context, tempDb, currentDekHex, openedWith)
                    } catch (e: RestoreReEncryptionException) {
                        // Phase-169: rows that failed to re-key would be PERMANENTLY
                        // unreadable after the swap — quarantine the rejected copy for
                        // forensic evidence (same as RestoredDbPolicy rejections) and
                        // let the restore fail loudly with the fixed UI text. The live
                        // vault is never swapped (RestoreFailSafe reopens it).
                        quarantineRejectedRestoredDb(context, tempDb)
                        throw e
                    }
                    // B1-DB-3 (phase-54): the voice `.enc` blobs were encrypted with
                    // the BACKUP device's DEK — re-encrypt them in place to the
                    // restoring device's DEK so the retargeted media_embeds rows keep
                    // playing after a cross-device restore.
                    rekeyVoiceNoteBlobs(tempVoiceNotes, openedWith.fromHex(), currentDekHex.fromHex())
                }
            }
        } finally {
            // R2-B1C-03 (phase-145): every local byte copy of a DEK is zeroized.
            // (The live currentDek the caller passed in is untouched — only the copy.)
            backupDekOwned?.fill(0.toByte())
            currentDekOwned?.fill(0.toByte())
        }
    }

    /**
     * R2-B1D-02 (phase-135): how many of [RestoredDbPolicy.REQUIRED_TABLES]
     * actually exist as `sqlite_master` tables in the restored copy. A real Room
     * vault (schema >= 1) carries all of them; a freshly-initialized EMPTY
     * SQLCipher DB carries none. Queried under the candidate open so the result
     * can never describe a different database than the one being swapped in.
     */
    private fun countPresentRestoredTables(db: net.zetetic.database.sqlcipher.SQLiteDatabase): Int {
        var present = 0
        for (table in RestoredDbPolicy.REQUIRED_TABLES) {
            try {
                val cursor = db.rawQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name=?",
                    arrayOf<Any>(table)
                )
                try {
                    if (cursor.moveToFirst() && cursor.getLong(0) > 0) present++
                } finally {
                    cursor.close()
                }
            } catch (e: Exception) {
                // sqlite_master always resolves; any failure means the copy is not
                // a usable vault DB — count the table as missing (fail closed).
            }
        }
        return present
    }

    /**
     * R2-B1D-02 (phase-135): row count of [table] in the restored copy. A vault
     * with zero `pages` rows is an EMPTY vault — the only case allowed through by
     * explicit user confirmation. A missing table (schema not applied) reads as
     * zero rows; the schema-identity gate above already fails those copies.
     */
    private fun countRestoredRows(db: net.zetetic.database.sqlcipher.SQLiteDatabase, table: String): Long {
        return try {
            val cursor = db.rawQuery("SELECT COUNT(*) FROM $table", null)
            try {
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            } finally {
                cursor.close()
            }
        } catch (e: Exception) {
            // "no such table" — schema not applied; zero rows (empty vault).
            0L
        }
    }

    /**
     * R2-B1D-02 (phase-135): preserves the byte-exact rejected incoming database
     * next to the live vault as `noteflow.sqlite.restore-rejected-<ts>` so a
     * hostile/empty archive leaves forensic evidence and is NEVER the only copy.
     * Best-effort by design — aborting the restore is the guarantee, not the
     * forensic copy.
     */
    private fun quarantineRejectedRestoredDb(context: Context, tempDb: File) {
        runCatching {
            val dbFile = context.getDatabasePath("noteflow.sqlite")
            val parent = dbFile.parentFile ?: return
            val target = File(parent, "noteflow.sqlite.restore-rejected-${System.currentTimeMillis()}")
            tempDb.copyTo(target, overwrite = true)
        }
    }

    /**
     * B1-DB-3 (phase-54): re-keys every encrypted `.enc` blob under [tempVoiceNotes]
     * from [oldDek] to [newDek] in place (pure [VoiceNoteCrypto.reencryptAudioBlobInPlace],
     * blobs whose AAD/DEK no longer opens are left untouched — the embed row keeps
     * the raw path either way and fails back to a playback error rather than data
     * loss). Zeroizes both keys on exit.
     */
    private fun rekeyVoiceNoteBlobs(dir: File, oldDek: ByteArray, newDek: ByteArray) {
        try {
            if (!dir.isDirectory) return
            for (file in dir.listFiles()?.filter { it.isFile && VoiceNoteCrypto.isEncryptedBlobName(it.name) } ?: emptyList()) {
                VoiceNoteCrypto.reencryptAudioBlobInPlace(file, oldDek, newDek)
            }
        } finally {
            oldDek.fill(0.toByte())
            newDek.fill(0.toByte())
        }
    }

    /**
     * B2-DOS-01 (phase-50): drops every stroke row whose stored `pointsJson`
     * column exceeds [StrokeGeometryPolicy.MAX_STORED_POINTS_JSON_CHARS].
     *
     * The stored value is base64 AES-256-GCM ciphertext (or a legacy plaintext
     * JSON) whose length is an EXACT proxy for the plaintext geometry size —
     * GCM does not compress, so a row cannot be "small on disk, huge when
     * decrypted". Dropping here means the oversized row never reaches the
     * re-key / field-migration / transplant steps and never lands in the live
     * vault, so a crafted backup cannot OOM the app on page open. Pages and
     * their other strokes are untouched — only the pathological rows go.
     */
    private fun sanitizeRestoredStrokeGeometry(db: net.zetetic.database.sqlcipher.SQLiteDatabase) {
        try {
            db.execSQL(
                "DELETE FROM strokes WHERE length(pointsJson) > ?",
                arrayOf<Any>(StrokeGeometryPolicy.MAX_STORED_POINTS_JSON_CHARS)
            )
        } catch (e: Exception) {
            // A strokes table that doesn't exist (schema not yet applied) is not
            // an error here — there is nothing to strip. Any real failure is
            // re-thrown so the restore-abort path handles it.
            if (shouldPropagateRestoreStripFailure(e)) throw e
        }
    }

    private fun shouldPropagateRestoreStripFailure(e: Exception): Boolean {
        val msg = e.message?.lowercase() ?: return true
        return !msg.contains("no such table")
    }

    /**
     * R2-b2b4-DOS-01 (phase-149): caps a `note_versions` table to the newest
     * [com.authorss81.noteflow.services.NoteVersionRetentionPolicy.MAX_VERSIONS_PER_PAGE]
     * rows per page, oldest dropped. The SINGLE retention-prune implementation
     * shared by the restore sanitizer ([sanitizeRestoredNoteVersions]) and the
     * export-time staged-snapshot trim ([pruneStagedSnapshotVersions]).
     *
     * The row bodies are encrypted at rest and their base64 length is an EXACT
     * proxy for the plaintext size (AES-GCM does not compress), so the uncapped
     * table is the DOS vector: a crafted backup with ~5,000 rows × ~50 KB bodies
     * becomes ~250 MB in heap the moment Version History opens or the table is
     * re-encrypted on restore. Running under the key that can open the DB, the
     * table is trimmed BEFORE the re-key / [migrateFieldCiphertexts] steps (restore)
     * or BEFORE the archive is packed (export) — those then walk a bounded history
     * and the archive never serializes a page's retained-but-oversized history.
     * The statement is the policy's single raw SQL literal, bound [keepNewest],
     * never interpolated, and ordered by `timestampMs DESC, rowid DESC` so
     * same-millisecond snapshots prune deterministically.
     */
    private fun pruneVersionPagesToRetention(db: net.zetetic.database.sqlcipher.SQLiteDatabase) {
        val keepNewest = com.authorss81.noteflow.services.NoteVersionRetentionPolicy.MAX_VERSIONS_PER_PAGE
        val pageIds = mutableListOf<String>()
        val cursor = db.rawQuery("SELECT DISTINCT pageId FROM note_versions", null)
        try {
            while (cursor.moveToNext()) pageIds.add(cursor.getString(0))
        } finally {
            cursor.close()
        }
        for (pageId in pageIds) {
            db.execSQL(
                com.authorss81.noteflow.services.NoteVersionRetentionPolicy.PRUNE_KEEP_NEWEST_SQL,
                arrayOf<Any>(pageId, pageId, keepNewest)
            )
        }
    }

    /**
     * R2-b2b4-DOS-01 (phase-149): restore-time version-table sanitizer — runs
     * under the candidate key that can open a crafted backup (see
     * [pruneVersionPagesToRetention] for the SQL). A missing `note_versions`
     * table (schema not yet applied) is not an error here — there is nothing to
     * strip; any real failure is re-thrown so the restore-abort path handles it.
     */
    private fun sanitizeRestoredNoteVersions(db: net.zetetic.database.sqlcipher.SQLiteDatabase) {
        try {
            pruneVersionPagesToRetention(db)
        } catch (e: Exception) {
            if (shouldPropagateRestoreStripFailure(e)) throw e
        }
    }

    /**
     * R2-b2b4-DOS-02 (phase-150): caps a `layers` table to the top
     * [com.authorss81.noteflow.services.LayerRenderBudgetPolicy.MAX_LIVE_LAYER_COUNT]
     * rows per page (highest `zOrder`, ties by `rowid`), the lower rows dropped.
     * The SINGLE prune implementation shared by the restore sanitizer
     * ([sanitizeRestoredLayerCounts]) and the export-time staged-snapshot trim
     * ([pruneStagedSnapshotLayers]).
     *
     * The canvas caches one full-page ARGB_8888 bitmap per visible layer
     * (~10.4 MB at 1080x2400) with no per-layer cap, so the uncapped table is a
     * DOS vector: a crafted backup spreading strokes across 40 layers on one
     * page becomes ~416 MB native the moment the editor opens it. Running under
     * the key that can open the DB, the table is trimmed BEFORE the re-key /
     * [migrateFieldCiphertexts] steps (restore) or BEFORE the archive is packed
     * (export) — those then walk a bounded layer list, the live save on open
     * persists only what the editor can render, and the archive never
     * serializes a page's retained-but-oversized backlog. The statement is the
     * policy's single raw SQL literal, bound [keepTop], never interpolated, and
     * ordered by `zOrder DESC, rowid DESC` so crafted equal-`zOrder` rows prune
     * deterministically. A missing `layers` table (schema not yet applied) is
     * not an error here — there is nothing to strip.
     */
    private fun pruneLayerPagesToLiveCap(db: net.zetetic.database.sqlcipher.SQLiteDatabase) {
        val keepTop = com.authorss81.noteflow.services.LayerRenderBudgetPolicy.MAX_LIVE_LAYER_COUNT
        val pageIds = mutableListOf<String>()
        val cursor = db.rawQuery("SELECT DISTINCT pageId FROM layers", null)
        try {
            while (cursor.moveToNext()) pageIds.add(cursor.getString(0))
        } finally {
            cursor.close()
        }
        for (pageId in pageIds) {
            db.execSQL(
                com.authorss81.noteflow.services.LayerRenderBudgetPolicy.KEEP_HIGHEST_Z_LAYERS_RAW_SQL,
                arrayOf<Any>(pageId, pageId, keepTop)
            )
        }
    }

    /**
     * R2-b2b4-DOS-02 (phase-150): restore-time layer-table sanitizer — runs
     * under the candidate key that can open a crafted backup (see
     * [pruneLayerPagesToLiveCap] for the SQL). A missing `layers` table is not
     * an error here — any real failure is re-thrown so the restore-abort path
     * handles it.
     */
    private fun sanitizeRestoredLayerCounts(db: net.zetetic.database.sqlcipher.SQLiteDatabase) {
        try {
            pruneLayerPagesToLiveCap(db)
        } catch (e: Exception) {
            if (shouldPropagateRestoreStripFailure(e)) throw e
        }
    }

    /**
     * R2-b2b4-DOS-02 (phase-150): the export-time layer trim, mirroring the
     * phase-149 version-history trim. Runs on the STAGED SNAPSHOT copy — opened
     * with the PINNED export DEK (phase-189: a snapshot-at-entry copy passed in,
     * never a re-read of the mutable singleton) and pruned via
     * [pruneLayerPagesToLiveCap] — and NEVER on the live vault, so a backup that
     * later fails can never permanently delete a user's extra layers. The staged
     * file is a standalone snapshot by then (post
     * [com.authorss81.noteflow.utils.VaultSnapshotCopyPolicy.checkpointThenCopy]),
     * so writing it does not touch the live WAL connection.
     */
    private fun pruneStagedSnapshotLayers(stagedDb: File, dek: ByteArray) {
        val passphrase = dek.toSqlcipherPassphraseBytes()
        try {
            System.loadLibrary("sqlcipher")
            val db = net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
                stagedDb, passphrase, null, null, null
            )
            try {
                // Phase-150 review fix 3: a missing `layers` table (a vault from
                // before the table existed, or a crafted archive) must not abort
                // the whole backup — nothing to strip. Mirrors the restore
                // sanitizer's tolerance; any REAL failure still aborts.
                try {
                    pruneLayerPagesToLiveCap(db)
                } catch (e: Exception) {
                    if (shouldPropagateRestoreStripFailure(e)) throw e
                }
            } finally {
                db.close()
            }
        } finally {
            passphrase.fill(0.toByte())
        }
    }

    /**
     * R2-b2b4-DOS-01 (phase-149 review fix): the export-time version-history
     * trim. Runs on the STAGED SNAPSHOT copy — opened with the PINNED export DEK
     * (phase-189: a snapshot-at-entry copy passed in, never a re-read of the
     * mutable singleton) and pruned via [pruneVersionPagesToRetention] — and
     * NEVER on the live vault, so a backup that later fails (copy teardown,
     * budget rejection, encryption error) can never permanently delete the
     * user's older version history. The staged file is a standalone snapshot by
     * then (post [VaultSnapshotCopyPolicy.checkpointThenCopy]), so writing it
     * does not touch the live WAL connection.
     */
    private fun pruneStagedSnapshotVersions(stagedDb: File, dek: ByteArray) {
        val passphrase = dek.toSqlcipherPassphraseBytes()
        try {
            System.loadLibrary("sqlcipher")
            val db = net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
                stagedDb, passphrase, null, null, null
            )
            try {
                pruneVersionPagesToRetention(db)
            } finally {
                db.close()
            }
        } finally {
            passphrase.fill(0.toByte())
        }
    }

    /**
     * R2-B1C-03 (phase-145) / phase-149 review fix: build the SQLCipher passphrase
     * (the DEK's lowercase hex) DIRECTLY as ASCII bytes with no intermediate
     * immutable hex [String], so no unzeroizable heap residue is ever created
     * ([pruneStagedSnapshotVersions] zeroizes the returned array after use).
     */
    private fun ByteArray.toSqlcipherPassphraseBytes(): ByteArray {
        val hex = "0123456789abcdef".toByteArray(Charsets.US_ASCII)
        val out = ByteArray(size * 2)
        for (i in indices) {
            val b = this[i].toInt() and 0xFF
            out[i * 2] = hex[b ushr 4]
            out[i * 2 + 1] = hex[b and 0x0F]
        }
        return out
    }

    /**
     * B1-AUTH-05 (phase-69): a crafted vault backup can transplant
     * `pages.sourceFilePath` rows that point anywhere the process can read/write
     * (voice-note blobs, the crash log, shared/exported artifacts) — the zip
     * entry-NAMES were validated but this column never was. Running here, under
     * the candidate key that can open the backup, every non-blank
     * [SourceFilePathPolicy] value that is NOT confined under the restored
     * imports root ([getImportsDir]) has BOTH `sourceFilePath` and
     * `sourceFileType` set to NULL, so the malicious reference never reaches the
     * re-key / field-migration / transplant steps nor the live vault — no later
     * reader (editor preview, WikiLinkParser, legacy body migration) can touch
     * the referenced file. A page whose source file legitimately lived inside
     * the imports directory is untouched.
     */
    private fun sanitizeRestoredSourceFilePaths(db: net.zetetic.database.sqlcipher.SQLiteDatabase, importsRoot: java.io.File) {
        try {
            val offenders = mutableListOf<String>()
            val cursor = db.rawQuery("SELECT id, sourceFilePath FROM pages WHERE sourceFilePath IS NOT NULL", null)
            try {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0)
                    val stored = cursor.getString(1)
                    if (stored.isNullOrBlank()) continue
                    if (!SourceFilePathPolicy.isConfined(stored, importsRoot)) {
                        offenders.add(id)
                    }
                }
            } finally {
                cursor.close()
            }
            for (id in offenders) {
                db.execSQL("UPDATE pages SET sourceFilePath = NULL, sourceFileType = NULL WHERE id = ?", arrayOf<Any>(id))
            }
        } catch (e: Exception) {
            // A pages table that doesn't exist (schema not yet applied) is not an
            // error here — there is nothing to strip. Any real failure is
            // re-thrown so the restore-abort path handles it.
            if (shouldPropagateRestoreStripFailure(e)) throw e
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
            // R2-B1C-03 (phase-145): open with the new passphrase as ASCII bytes —
            // zeroized after the open; the hex String is scoped to the caller.
            val newDekBytes = newDekHex.toAsciiBytes()
            try {
                val db = net.zetetic.database.sqlcipher.SQLiteDatabase.openOrCreateDatabase(
                    tempDb, newDekBytes, null, null, null
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
                newDekBytes.fill(0.toByte())
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
            // Phase-169: a genuine ciphertext row that fails to re-key would be
            // irrecoverably unreadable after the SQLCipher re-key — count it so
            // the restore fails loudly (never silently installs the rows).
            var failed = 0
            try {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idIdx)
                    val value = cursor.getString(colIdx)
                    when (val outcome = reencryptFieldOutcome(value, oldDek, newDek, table, id, column)) {
                        is FieldReencryptOutcome.Migrated -> updates.add(id to outcome.value)
                        FieldReencryptOutcome.LeavePlaintext -> Unit
                        FieldReencryptOutcome.AuthFailed -> failed++
                    }
                }
            } finally {
                cursor.close()
            }
            if (failed > 0) {
                throw RestoreReEncryptionException(table, column, failed)
            }
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
    private fun commitRestoredFiles(context: Context, tempDb: File, tempImports: File, tempVoiceNotes: File) {
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

        // B1-DB-3 (phase-54): swap the encrypted voice blobs into place too —
        // a restore that carries the audio must actually land it, or the
        // retargeted media_embeds rows would point at nothing.
        val voiceNotesDir = File(context.filesDir, "voice_notes")
        if (voiceNotesDir.exists()) voiceNotesDir.deleteRecursively()
        voiceNotesDir.mkdirs()
        if (tempVoiceNotes.exists()) {
            tempVoiceNotes.walkTopDown().filter { it.isFile }.forEach { file ->
                val relPath = file.relativeTo(tempVoiceNotes).path.replace('\\', '/')
                val target = File(voiceNotesDir, relPath)
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

            // B1-PLAT-3 (phase-59): no auto-copy to public Downloads — the vault zip
            // stays app-private in cacheDir until the user picks a destination.
            zipFile
        } catch (e: Exception) {
            // B2-LOG-03 (phase-71): class name only, never the throwable.
            Log.e("ImportExportService", FailureLogPolicy.safeLogMessage(e, "Failed to export vault to ZIP"))
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

            // B1-DB-4 (phase-44): the body is stored ONLY in the field-encrypted
            // extractedText column — never written to a plaintext .md file under
            // filesDir/noteflow/imports. sourceFilePath stays null for text pages
            // (imported images are still files and reference via their own paths).
            repository.createPage(
                sectionId = sectionId,
                title = title,
                sourceFilePath = null,
                sourceFileType = "text",
                extractedText = markdown,
                tags = "imported_html"
            )
        } catch (e: ImportArchivePolicy.ImportSizeLimitException) {
            // B1-DB-5: an oversized share must surface, never be silently skipped.
            throw e
        } catch (e: Exception) {
            // B2-LOG-03 (phase-71): class name only, never the throwable.
            Log.e("ImportExportService", FailureLogPolicy.safeLogMessage(e, "Failed to import HTML file"))
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
            // B1-DB-5 (phase-55): the compressed archive input is capped before
            // any decompression, and every entry is read under the per-entry /
            // total / expansion-ratio / entry-count budgets.
            val bytes = readUriBytes(context, uri) ?: return@withContext 0
            if (ImportArchivePolicy.inputArchiveOverLimit(bytes.size)) {
                throw ImportArchivePolicy.ImportSizeLimitException(
                    "Import rejected: archive is too large (max " +
                        "${ImportArchivePolicy.MAX_IMPORT_ARCHIVE_INPUT_BYTES / (1024 * 1024)}MB)."
                )
            }
            val accounting = ImportArchivePolicy.Accounting()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    ImportArchivePolicy.claimEntry(accounting)
                    if (!entry.isDirectory && (entry.name.endsWith(".html", ignoreCase = true) || entry.name.endsWith(".htm", ignoreCase = true))) {
                        val htmlBytes = ImportArchivePolicy.readEntryBounded(zis, entry, accounting)
                        val htmlContent = String(htmlBytes, Charsets.UTF_8)
                        val (title, markdown) = HtmlToMarkdownConverter.convertHtmlToMarkdown(htmlContent)

                        val entryName = entry.name.substringAfterLast('/').substringBeforeLast('.')
                        val finalTitle = title.ifBlank { entryName }
                        // B1-DB-4 (phase-44): body stored ONLY in the field-encrypted
                        // extractedText column — no plaintext .md companion file.
                        repository.createPage(
                            sectionId = sectionId,
                            title = finalTitle,
                            sourceFilePath = null,
                            sourceFileType = "text",
                            extractedText = markdown,
                            tags = "imported_html"
                        )
                        count++
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: ImportArchivePolicy.ImportSizeLimitException) {
            // B1-DB-5: a zip bomb (or any budget breach) fails with a clean
            // error — never a half-imported silent skip, never an OOM.
            throw e
        } catch (e: Exception) {
            // B2-LOG-03 (phase-71): class name only, never the throwable.
            Log.e("ImportExportService", FailureLogPolicy.safeLogMessage(e, "Failed to import HTML ZIP folder"))
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

            // B1-PLAT-3 (phase-59): no auto-copy to public Downloads — the HTML note
            // stays app-private in cacheDir until the user picks a destination.
            htmlFile
        } catch (e: Exception) {
            // B2-LOG-03 (phase-71): class name only, never the throwable.
            Log.e("ImportExportService", FailureLogPolicy.safeLogMessage(e, "Failed to export note to HTML"))
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

            // B1-PLAT-3 (phase-59): no auto-copy to public Downloads — the HTML site
            // zip stays app-private in cacheDir until the user picks a destination.
            zipFile
        } catch (e: Exception) {
            // B2-LOG-03 (phase-71): class name only, never the throwable.
            Log.e("ImportExportService", FailureLogPolicy.safeLogMessage(e, "Failed to export vault HTML site"))
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
            // B1-DB-5 (phase-55): the compressed archive input is capped before
            // any decompression, and every entry is read under a SINGLE shared
            // accounting budget (per-entry / total / expansion-ratio /
            // entry-count). The old two-pass scan is now one pass so the budget
            // is exact and every entry is parsed at most once.
            val bytes = readUriBytes(context, uri) ?: return@withContext 0
            if (ImportArchivePolicy.inputArchiveOverLimit(bytes.size)) {
                throw ImportArchivePolicy.ImportSizeLimitException(
                    "Import rejected: archive is too large (max " +
                        "${ImportArchivePolicy.MAX_IMPORT_ARCHIVE_INPUT_BYTES / (1024 * 1024)}MB)."
                )
            }
            val accounting = ImportArchivePolicy.Accounting()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    ImportArchivePolicy.claimEntry(accounting)
                    if (!entry.isDirectory) {
                        val ext = extensionOf(entry.name)
                        when {
                            isImage(ext) -> {
                                val fileBytes = ImportArchivePolicy.readEntryBounded(zis, entry, accounting)
                                val fileName = entry.name.substringAfterLast('/')
                                persistFile(context, fileName, fileBytes)
                            }
                            entry.name.endsWith(".md", ignoreCase = true) -> {
                                val mdBytes = ImportArchivePolicy.readEntryBounded(zis, entry, accounting)
                                val rawContent = String(mdBytes, Charsets.UTF_8)

                                val title = entry.name.substringAfterLast('/').substringBeforeLast('.')
                                val tags = WikiLinkParser.extractTags(rawContent).joinToString(",")

                                // B1-DB-4 (phase-44): the markdown body is stored ONLY in
                                // the field-encrypted extractedText column — never as a
                                // plaintext .md file. Attachments imported above remain
                                // real files (they are media, not note bodies).
                                repository.createPage(
                                    sectionId = sectionId,
                                    title = title,
                                    sourceFilePath = null,
                                    sourceFileType = "text",
                                    extractedText = rawContent,
                                    tags = tags.ifBlank { "obsidian_import" }
                                )
                                count++
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: ImportArchivePolicy.ImportSizeLimitException) {
            // B1-DB-5: a zip bomb (or any budget breach) fails with a clean
            // error — never a half-imported silent skip, never an OOM.
            throw e
        } catch (e: Exception) {
            // B2-LOG-03 (phase-71): class name only, never the throwable.
            Log.e("ImportExportService", FailureLogPolicy.safeLogMessage(e, "Failed to import Obsidian Vault ZIP"))
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

            // B1-PLAT-3 (phase-59): no auto-copy to public Downloads — the Obsidian
            // vault zip stays app-private in cacheDir until the user picks a destination.
            zipFile
        } catch (e: Exception) {
            // B2-LOG-03 (phase-71): class name only, never the throwable.
            Log.e("ImportExportService", FailureLogPolicy.safeLogMessage(e, "Failed to export Obsidian Vault ZIP"))
            null
        }
    }

    // --- PHASE 24: LAYERED PSD EXPORT ---

    suspend fun exportPageToPsd(
        context: Context,
        page: com.authorss81.noteflow.data.model.NotePageEntity,
        repository: com.authorss81.noteflow.data.repository.NoteRepository
    ): PsdExportService.PsdExportOutcome = withContext(Dispatchers.IO) {
        try {
            val strokes = repository.getStrokesForPage(page.id)
            val layers = repository.getLayersForPage(page.id)

            val width = 1080
            val height = 1528

            // Phase 227: when the deckled edge is the user's paper style, EVERY
            // PSD layer — Background, per-entity sheet, merged-preview — is
            // clipped to the same wavy silhouette so a re-opened PSD preserves
            // the hand-cut sheet instead of a white-edged rectangle. RECT /
            // ROUNDED leave the layers untouched (legacy files unchanged).
            val psdDeckleClip = com.authorss81.noteflow.services.DeckleExportHelper.deckledEnabled(context)

            val psdLayers = mutableListOf<PsdExportService.PsdLayer>()

            // Background layer
            val bgBitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val bgCanvas = android.graphics.Canvas(bgBitmap)
            bgCanvas.drawColor(android.graphics.Color.WHITE)
            if (psdDeckleClip) {
                bgCanvas.save()
                bgCanvas.clipPath(
                    com.authorss81.noteflow.services.DeckleExportHelper.sheetPath(width.toFloat(), height.toFloat(), width / 360f)
                )
            }
            drawTemplateBackground(bgCanvas, page.template ?: "blank", width, height)
            if (psdDeckleClip) bgCanvas.restore()
            psdLayers.add(PsdExportService.PsdLayer("Background", bgBitmap))

            val inkRenderer = try {
                androidx.ink.rendering.android.canvas.CanvasStrokeRenderer.create(false)
            } catch (e: Exception) {
                null
            }

            // B2-DOS-06 (phase-82): the exported drawing-layer count is budgeted by
            // PsdExportPolicy BEFORE any per-layer bitmap is created — a restored
            // vault or heavy Layers panel can no longer make the export materialize
            // N full-page ARGB bitmaps at once. `omittedLayerCount` drives the
            // one-time non-alarming notice shown by the caller.
            var exportedDataLayers = 0
            var omittedDataLayers = 0
            var compositeExtras: List<PsdExportService.PsdLayer> = emptyList()

            if (layers.isEmpty()) {
                val layerBitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(layerBitmap)
                if (psdDeckleClip) {
                    canvas.save()
                    canvas.clipPath(com.authorss81.noteflow.services.DeckleExportHelper.sheetPath(width.toFloat(), height.toFloat(), width / 360f))
                }
                renderLayersAndStrokesToCanvas(canvas, width, height, 0, strokes, emptyList(), inkRenderer)
                if (psdDeckleClip) canvas.restore()
                psdLayers.add(PsdExportService.PsdLayer("Drawing Layer 1", layerBitmap))
                exportedDataLayers = 1
            } else {
                // Keep the TOP `exportedDataLayers` layers (highest zOrder = the
                // visually front-most, most recently worked-on layers) but write
                // them in bottom->top PSD record order. The omitted BOTTOM layers
                // are folded into a single bounded merged-preview bitmap that feeds
                // the PSD's flattened composite, so the exported preview still
                // matches the on-canvas page (one extra 6.6 MB bitmap, never one
                // per omitted layer).
                val sortedAsc = layers.sortedBy { it.zOrder }
                exportedDataLayers = PsdExportPolicy.capLayerCount(sortedAsc.size)
                omittedDataLayers = PsdExportPolicy.omittedLayerCount(sortedAsc.size)
                val exportedEntities = sortedAsc.takeLast(exportedDataLayers)
                exportedEntities.forEachIndexed { idx, layerEntity ->
                    val layerBitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(layerBitmap)
                    if (psdDeckleClip) {
                        canvas.save()
                        canvas.clipPath(com.authorss81.noteflow.services.DeckleExportHelper.sheetPath(width.toFloat(), height.toFloat(), width / 360f))
                    }
                    val layerStrokes = strokes.filter { it.layerId == layerEntity.id }
                    renderLayersAndStrokesToCanvas(canvas, width, height, 0, layerStrokes, listOf(layerEntity), inkRenderer)
                    if (psdDeckleClip) canvas.restore()
                    // Phase 227: the per-layer record now carries the entity's REAL
                    // opacity (Float 0..1 -> PSD 0..255) and blend key, so a PSD
                    // re-opened in an editor composites exactly like the on-canvas
                    // sheet (previously every layer reopened at 100% / NORMAL).
                    psdLayers.add(
                        PsdExportService.PsdLayer(
                            name = layerEntity.name.ifBlank { "Layer ${idx + 1}" },
                            bitmap = layerBitmap,
                            isVisible = layerEntity.visible,
                            opacity = (layerEntity.opacity * 255f).toInt().coerceIn(0, 255),
                            blendSignature = PsdExportPolicy.psdBlendSignature(layerEntity.blendMode)
                        )
                    )
                }
                if (omittedDataLayers > 0 && exportedDataLayers < sortedAsc.size) {
                    val omittedEntities = sortedAsc.dropLast(exportedDataLayers)
                    val previewBitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                    val previewCanvas = android.graphics.Canvas(previewBitmap)
                    if (psdDeckleClip) {
                        previewCanvas.save()
                        previewCanvas.clipPath(com.authorss81.noteflow.services.DeckleExportHelper.sheetPath(width.toFloat(), height.toFloat(), width / 360f))
                    }
                    renderLayersAndStrokesToCanvas(previewCanvas, width, height, 0, strokes, omittedEntities, inkRenderer)
                    if (psdDeckleClip) previewCanvas.restore()
                    compositeExtras = listOf(
                        PsdExportService.PsdLayer("Merged preview (omitted layers)", previewBitmap)
                    )
                }
            }

            val file = PsdExportService.exportLayersToPsd(
                context = context,
                title = page.title,
                width = width,
                height = height,
                layers = psdLayers,
                compositeExtras = compositeExtras
            )
            PsdExportService.PsdExportOutcome(
                file = file,
                exportedLayerCount = exportedDataLayers,
                omittedLayerCount = omittedDataLayers
            )
        } catch (e: Exception) {
            // B2-LOG-03 (phase-71): class name only, never the throwable.
            Log.e("ImportExportService", FailureLogPolicy.safeLogMessage(e, "Failed to export page to PSD"))
            PsdExportService.PsdExportOutcome(file = null, exportedLayerCount = 0, omittedLayerCount = 0)
        }
    }

}

/**
 * R2-B1D-02 (phase-135): thrown by the pre-swap gate when a restored backup holds
 * a REAL Room schema but zero `pages` rows. Silently restoring it would replace
 * a populated vault with an empty one. The caller surfaces an explicit "start
 * fresh" confirmation and only then re-runs the import with
 * `allowEmptyVault = true`; any other outcome leaves the live vault untouched.
 */
internal class EmptyVaultRestoreDecisionException :
    IllegalStateException(
        "This backup contains an EMPTY vault (no notes). Restoring it would replace " +
            "everything with an empty vault. Confirm you really want to start fresh."
    )

/**
 * B1-DB-7 (phase-56): true when [bytes] look like a raw PK zip — the signature
 * of a legacy PLAIN (unencrypted, unsigned) backup. Pure JVM so the restore
 * gate and the picker UI share one check and the unit tests can pin it.
 *
 * The full 4-byte signature is validated (not just the ASCII "PK" prefix), so
 * a text/base64 payload that merely begins with the letters "PK" can never be
 * misclassified as a plain zip — device-DEK ciphertext (which can start with
 * any base64 char) always passes the entry gate into the decrypt path, while
 * real zip files are still rejected outright.
 */
internal fun isPlainPkBackupBytes(bytes: ByteArray): Boolean {
    if (bytes.size < 4) return false
    if (bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return false
    // Little-endian 16-bit value of bytes[2..3] compared against the valid
    // ZIP record signatures that can appear at offset 0:
    //   PK\x03\x04 local file header, PK\x05\x06 end-of-central-directory,
    //   PK\x06\x06 ZIP64 EOCD record, PK\x07\x08 data descriptor.
    val sig = (((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF))
    return sig == 0x0304 || sig == 0x0506 || sig == 0x0606 || sig == 0x0708
}

/**
 * R2-B1D-04 (phase-138): file-form of [isPlainPkBackupBytes] — reads only the
 * 4-byte head so the picker/restore gate can classify a backup without ever
 * loading it into heap.
 */
internal fun isPlainPkBackupFile(file: File): Boolean {
    val head = ImportExportService.readFileHead(file, 4)
    return head.size == 4 && isPlainPkBackupBytes(head)
}

/**
 * R2-B1D-04 (phase-138): true when [file] starts with a v2 (NFLB2) or v3
 * (NFLB3) backup magic — the password-protected, portable format. Reads only
 * the 5-byte head. (Fixed a pre-existing gap on this path: the picker only
 * recognized NFLB2 and routed a v3 backup to the legacy "UNTRUSTED" dialog,
 * which then failed with "This backup is protected by a password" — a v3
 * backup is now offered the password dialog like a v2 one.)
 */
internal fun isNflbBackupFile(file: File): Boolean {
    val head = ImportExportService.readFileHead(file, 5)
    if (head.size < 5) return false
    val magic = String(head, Charsets.US_ASCII)
    return magic == "NFLB2" || magic == "NFLB3"
}

/**
 * B1-DB-7 (phase-56): the ONLY keys allowed to open a restored backup database.
 *
 * The historical candidate list was `listOfNotNull(backupDekHex, currentDekHex, "")`
 * — the `""` empty-passphrase SQLCipher entry let an attacker-crafted plaintext
 * `noteflow.sqlite` pass PRAGMA integrity_check, get re-keyed to the victim's
 * DEK and swapped over the live vault. Only the backup's own wrapped DEK (v2)
 * or this device's DEK (device-keyed legacy) may open a backup; both are
 * unguessable. Any empty string is stripped fail-closed so a future caller can
 * never re-introduce the empty-key candidate.
 */
internal fun backupRestoreOpenCandidates(backupDekHex: String?, currentDekHex: String?): List<String> =
    listOfNotNull(backupDekHex, currentDekHex).filter { it.isNotBlank() }.distinct()
