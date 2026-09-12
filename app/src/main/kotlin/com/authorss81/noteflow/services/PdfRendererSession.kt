package com.authorss81.noteflow.services

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.authorss81.noteflow.utils.BitmapPool
import java.io.Closeable
import java.io.File

/**
 * High-performance, thread-safe PDF page renderer session.
 * Keeps a single PdfRenderer instance open across page render requests,
 * eliminating the expensive (300ms-1500ms) re-parsing of large PDFs on every page.
 */
class PdfRendererSession(val filePath: String?) : Closeable {
    private val lock = Any()
    private var pfd: ParcelFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    var isClosed: Boolean = false
        private set

    init {
        if (!filePath.isNullOrEmpty()) {
            try {
                val file = File(filePath)
                if (file.exists()) {
                    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    pfd = descriptor
                    renderer = PdfRenderer(descriptor)
                }
            } catch (e: Exception) {
                close()
            }
        }
    }

    val pageCount: Int
        get() = synchronized(lock) { renderer?.pageCount ?: 0 }

    val isValid: Boolean
        get() = synchronized(lock) { !isClosed && renderer != null }

    fun getPageAspectRatio(pageIndex: Int = 0): Float? = synchronized(lock) {
        if (isClosed || renderer == null) return null
        val r = renderer ?: return null
        if (pageIndex < 0 || pageIndex >= r.pageCount) return null
        try {
            r.openPage(pageIndex).use { page ->
                if (page.width > 0) page.height.toFloat() / page.width.toFloat() else null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun renderPage(
        pageIndex: Int,
        targetWidth: Int = 1080,
        context: Context? = null
    ): Bitmap? = synchronized(lock) {
        if (isClosed || renderer == null) return null
        val r = renderer ?: return null
        if (pageIndex < 0 || pageIndex >= r.pageCount) return null

        try {
            r.openPage(pageIndex).use { pdfPage ->
                val displayWidth = context?.resources?.displayMetrics?.widthPixels ?: 1080
                val maxAllowedWidth = (displayWidth * 1.5f).toInt().coerceAtMost(2048)
                val width = targetWidth.coerceAtMost(maxAllowedWidth)
                val height = (width * (pdfPage.height.toFloat() / pdfPage.width.toFloat())).toInt().coerceAtLeast(1)
                val bitmap = BitmapPool.acquire(width, height)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } catch (t: Throwable) {
                    BitmapPool.release(bitmap)
                    throw t
                }
                bitmap
            }
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {
        synchronized(lock) {
            if (isClosed) return
            isClosed = true
            try {
                renderer?.close()
            } catch (_: Exception) {}
            try {
                pfd?.close()
            } catch (_: Exception) {}
            renderer = null
            pfd = null
        }
    }
}
