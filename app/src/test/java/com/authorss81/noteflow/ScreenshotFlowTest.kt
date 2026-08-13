package com.authorss81.noteflow

import com.authorss81.noteflow.plugins.ScreenshotCaptureMode
import com.authorss81.noteflow.plugins.ScreenshotCapturePlan
import com.authorss81.noteflow.plugins.screenshot.ScreenshotFlowPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 16 Screenshot→note pure-JVM tests: the flow planner decides the mode
 * (IMAGE_ONLY vs IMAGE_WITH_OCR), the title, the filename and OCR reusability.
 */
class ScreenshotFlowTest {

    private val t = 1_753_000_000_000L // fixed instant for determinism

    @Test
    fun `image-only when the user did not ask for OCR`() {
        val plan = ScreenshotFlowPlanner.planCapture(t, shouldOcr = false, ocrPluginAvailable = true)
        assertEquals(ScreenshotCaptureMode.IMAGE_ONLY, plan.mode)
        assertFalse(plan.shouldOcr)
        assertFalse(plan.ocrReusable)
    }

    @Test
    fun `image-only when OCR is requested but no plugin is available`() {
        val plan = ScreenshotFlowPlanner.planCapture(t, shouldOcr = true, ocrPluginAvailable = false)
        assertEquals(ScreenshotCaptureMode.IMAGE_ONLY, plan.mode)
        // The image note is still created; OCR simply can't silently run.
        assertFalse(plan.ocrReusable)
    }

    @Test
    fun `image with OCR when both requested and available`() {
        val plan = ScreenshotFlowPlanner.planCapture(t, shouldOcr = true, ocrPluginAvailable = true)
        assertEquals(ScreenshotCaptureMode.IMAGE_WITH_OCR, plan.mode)
        assertTrue(plan.shouldOcr)
        assertTrue(plan.ocrReusable)
    }

    @Test
    fun `title contains a readable date`() {
        val title = ScreenshotFlowPlanner.titleFor(t)
        assertTrue(title.startsWith("Screenshot"))
        assertTrue(title.length > 12)
    }

    @Test
    fun `filename is stable and collision-friendly`() {
        val a = ScreenshotFlowPlanner.fileNameFor(t)
        val b = ScreenshotFlowPlanner.fileNameFor(t)
        assertEquals(a, b)
        assertTrue(a.matches(Regex("screenshot-\\d{8}-\\d{6}\\.png")))
    }

    @Test
    fun `different instants produce different filenames`() {
        assertTrue(
            ScreenshotFlowPlanner.fileNameFor(1L) != ScreenshotFlowPlanner.fileNameFor(1L + 60_000)
        )
    }

    @Test
    fun `plan fields are consistent with the decided mode`() {
        val plan: ScreenshotCapturePlan =
            ScreenshotFlowPlanner.planCapture(t, shouldOcr = true, ocrPluginAvailable = true)
        assertEquals(plan.shouldOcr, plan.mode == ScreenshotCaptureMode.IMAGE_WITH_OCR)
        assertTrue(plan.fileName.isNotBlank())
        assertTrue(plan.title.isNotBlank())
    }
}