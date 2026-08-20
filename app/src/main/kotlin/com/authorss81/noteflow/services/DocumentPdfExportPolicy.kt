package com.authorss81.noteflow.services

/**
 * Phase-182: the page-count decision for the document-PDF exporter
 * ([ImportExportService.exportDocumentAsPdf]).
 *
 * Regression the policy closes (user-reported 2026-08-20: "Export Document as PDF"
 * produced content on page 1 and BLANK pages after it): the pre-fix call site
 * computed `totalPages = maxOf(1, pdfPageBitmaps.size, ...)` where
 * `pdfPageBitmaps` is the MEMORY-BOUNDED visible render window (the editor only
 * rasterizes `visiblePageWindow` / `currentPdfPage`), so every page beyond that
 * window was silently dropped from the export — and because no `sourceFilePath`
 * was passed, the per-page source-background fallback inside the export loop
 * (`renderPdfPageToBitmap` / `decodeImageSampled`) never ran for them either.
 *
 * The count must come from the KNOWN REAL source page count (`pdfTotalPages`,
 * derived via `getPdfPageCount` for PDFs and `pageCountNeeded` for tall images)
 * plus the highest stroke page — never from how many bitmaps happen to be cached
 * in the visible window. The export loop then renders EVERY `0 until count`
 * index with a background: the pre-rendered cached bitmap when present, else the
 * per-page source re-render, else the page template — so no page can ever come
 * out blank.
 */
object DocumentPdfExportPolicy {

    /**
     * The number of PDF pages a document export must contain: at least as many as
     * the source document (`sourcePdfTotalPages`), and at least one page beyond the
     * highest stroke index (`maxStrokePageToExport` = strokes' `pdfPage` levels =
     * zero-based), each capping at a floor of 1. The windowed bitmap cache size is
     * deliberately NOT an input — it undercounts by construction.
     */
    fun pageCountForExport(sourcePdfTotalPages: Int, maxStrokePageToExport: Int): Int {
        val strokesRequired = maxOf(0, maxStrokePageToExport + 1)
        return maxOf(1, sourcePdfTotalPages, strokesRequired)
    }
}