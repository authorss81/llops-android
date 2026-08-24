package com.authorss81.noteflow.services

/**
 * Phase 202 review-fix (finding 3): dimension math for the split-PDF-import
 * rasterizer (`ImportExportService.renderPdfPageToPngFile`).
 *
 * The pre-review fix rendered EVERY page into one fixed 1080x1528 portrait
 * bitmap; PdfRenderer fits-and-centers into that destination, so landscape
 * pages baked permanent white letterbox bars into the stored slice image (and
 * every backup/export of it). The slice must instead adopt the SOURCE PAGE's
 * own aspect ratio, bounded by the same 1080-wide / 1528-tall budget box the
 * fixed path used — no distortion, no baked bars.
 *
 * Pure JVM so the contract is unit-testable on CI.
 */
object PdfSliceFitPolicy {

    /** Fitted raster dimensions (pixels), always >= 1. */
    data class Dimensions(val width: Int, val height: Int)

    /**
     * Scales [pageWidth]x[pageHeight] (a PDF page's point size) to the largest
     * dimensions that satisfy BOTH bounds — `min(scaleX, scaleY)`, exactly how
     * PdfRenderer maps a page into a destination — while PRESERVING the page's
     * aspect ratio. Upscaling small pages is intentional: vector rendering
     * benefits from it, and the pre-review fixed-size behaviour filled the box
     * the same way (minus the distortion/bars this policy removes).
     */
    fun fit(
        pageWidth: Int,
        pageHeight: Int,
        maxWidth: Int = DEFAULT_MAX_WIDTH,
        maxHeight: Int = DEFAULT_MAX_HEIGHT
    ): Dimensions {
        if (pageWidth <= 0 || pageHeight <= 0 || maxWidth <= 0 || maxHeight <= 0) {
            return Dimensions(DEFAULT_MAX_WIDTH, DEFAULT_MAX_HEIGHT)
        }
        val scale = minOf(
            maxWidth.toFloat() / pageWidth,
            maxHeight.toFloat() / pageHeight
        )
        val w = (pageWidth * scale).toInt().coerceIn(1, maxWidth)
        val h = (pageHeight * scale).toInt().coerceIn(1, maxHeight)
        return Dimensions(w, h)
    }

    const val DEFAULT_MAX_WIDTH: Int = 1080
    const val DEFAULT_MAX_HEIGHT: Int = 1528
}
