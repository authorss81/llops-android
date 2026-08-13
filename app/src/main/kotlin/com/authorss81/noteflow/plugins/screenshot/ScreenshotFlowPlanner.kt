package com.authorss81.noteflow.plugins.screenshot

import com.authorss81.noteflow.plugins.ScreenshotCaptureMode
import com.authorss81.noteflow.plugins.ScreenshotCapturePlan
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PURE JVM — the screenshot→note flow decision logic: when OCR is requested it
 * only applies when an OCR plugin is actually available ([ocrReusable]); the
 * note gets a readable title + stable filename; nothing about rendering or OCR
 * lives here (that is the platform plugin's job, reusing the existing export
 * + OCR paths — nothing is duplicated).
 */
object ScreenshotFlowPlanner {

    /**
     * Decide the capture flow for a screenshot taken at [capturedAtMillis].
     *
     * @param shouldOcr the user's intent to OCR the captured image.
     * @param ocrPluginAvailable whether an enabled OCR plugin exists right now.
     */
    fun planCapture(
        capturedAtMillis: Long,
        shouldOcr: Boolean,
        ocrPluginAvailable: Boolean
    ): ScreenshotCapturePlan {
        val effectiveOcr = shouldOcr && ocrPluginAvailable
        return ScreenshotCapturePlan(
            capturedAtMillis = capturedAtMillis,
            mode = if (effectiveOcr) ScreenshotCaptureMode.IMAGE_WITH_OCR
            else ScreenshotCaptureMode.IMAGE_ONLY,
            title = titleFor(capturedAtMillis),
            fileName = fileNameFor(capturedAtMillis),
            shouldOcr = effectiveOcr,
            ocrReusable = bestEffortOcrSupported(shouldOcr, ocrPluginAvailable)
        )
    }

    /** Human-readable note title, e.g. "Screenshot · Aug 13, 2026". */
    fun titleFor(millis: Long): String {
        val date = SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(millis))
        return "Screenshot \u00b7 $date"
    }

    /** Stable, collision-resistant filename, e.g. `screenshot-20260813-104532.png`. */
    fun fileNameFor(millis: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(millis))
        return "screenshot-$stamp.png"
    }

    /**
     * Whether OCR remains useful when the user asked for it but no OCR plugin
     * is currently available: no — we never lie to the user by pretending OCR
     * will run, but we do allow the IMAGE note itself (the image is still
     * valuable without text). Returns exactly whether an OCR pass may be
     * applied after capture.
     */
    private fun bestEffortOcrSupported(requested: Boolean, available: Boolean): Boolean =
        requested && available
}