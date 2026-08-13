package com.authorss81.noteflow.plugins.screenshot

import android.content.Context
import com.authorss81.noteflow.data.model.CanvasMediaEmbed
import com.authorss81.noteflow.data.model.CanvasStickyNote
import com.authorss81.noteflow.data.model.LayerEntity
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.ScreenshotCaptureOutcome
import com.authorss81.noteflow.plugins.ScreenshotCapturePlan
import com.authorss81.noteflow.plugins.ScreenshotNotePlugin
import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.services.ImportExportService
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

/**
 * The real screenshot→note plugin (Phase 16).
 *
 * Serves [PluginCapability.ScreenshotNote] via [ScreenshotNotePlugin]. It turns
 * the CURRENT canvas page into an image note by REUSING the app's existing
 * annotated-page export renderer ([ImportExportService.exportAnnotatedPage]) —
 * nothing about rendering ink/layers/sticky-notes/media is duplicated here.
 * The optional OCR pass is NOT performed inside the plugin: the caller (the
 * ViewModel, which already owns the OCR plugin route) applies it and writes the
 * extracted text into the new image page, so the plugin never hard-wires a
 * second OCR path.
 *
 * Decision/metadata logic is PURE JVM and unit-tested ([ScreenshotFlowPlanner]);
 * the render+persist below is platform-only and reached with a real Context.
 */
class ScreenshotNotePluginImpl : NoteflowPlugin, ScreenshotNotePlugin {

    override val manifest = PluginManifest(
        id = "com.authorss81.noteflow.plugins.screenshot",
        name = "Screenshot to Note",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = 26,
        description = "Captures the current canvas as an image note — optionally " +
            "OCR's into a searchable note via the existing on-device OCR plugin.",
        capabilities = setOf(PluginCapability.ScreenshotNote)
    )

    override fun availability(context: Context?): PluginAvailability = PluginAvailability.Ok

    override fun onEnable(context: Context?, settings: PluginSettings) {
        // No settings yet — a PNG-vs-WebP option would migrate here.
    }

    override fun onDisable(context: Context?, settings: PluginSettings) {
        // Nothing to release — captures are stateless.
    }

    override fun selfCheck(context: Context?): PluginAvailability {
        val ctx = context ?: return PluginAvailability.Unknown
        return try {
            // The renderer is exercised lazily at capture time; here we verify
            // the imports dir is writable so selfCheck gives a real answer.
            ImportExportService.getImportsDir(ctx).mkdirs()
            PluginAvailability.Ok
        } catch (e: Throwable) {
            PluginAvailability.Unavailable(
                "Screenshot capture could not be verified (${e::class.java.simpleName})."
            )
        }
    }

    // ---- ScreenshotNotePlugin serving interface -----------------------------

    override fun planCapture(
        capturedAtMillis: Long,
        shouldOcr: Boolean,
        ocrPluginAvailable: Boolean
    ): ScreenshotCapturePlan =
        ScreenshotFlowPlanner.planCapture(capturedAtMillis, shouldOcr, ocrPluginAvailable)

    override suspend fun captureAnnotatedPage(
        context: Context?,
        pageTitle: String,
        strokes: List<Stroke>,
        layers: List<LayerEntity>,
        stickyNotes: List<CanvasStickyNote>,
        mediaEmbeds: List<CanvasMediaEmbed>,
        bgBitmap: android.graphics.Bitmap?,
        template: String,
        pageIndex: Int,
        shouldOcr: Boolean,
        ocrPluginAvailable: Boolean
    ): ScreenshotCaptureOutcome {
        val ctx = context ?: return ScreenshotCaptureOutcome.Error(
            "Screenshot capture needs a device context."
        )
        val plan = planCapture(System.currentTimeMillis(), shouldOcr, ocrPluginAvailable)
        return try {
            val rendering = ImportExportService.exportAnnotatedPage(
                context = ctx,
                title = plan.title,
                strokes = strokes,
                bgBitmap = bgBitmap,
                template = template,
                exportAsPdf = false,
                layers = layers,
                stickyNotes = stickyNotes,
                mediaEmbeds = mediaEmbeds,
                pageIndex = pageIndex
            ) ?: return ScreenshotCaptureOutcome.Error(
                "Couldn't render the current page into an image. Try again."
            )
            val imagePath = withContext(Dispatchers.IO) {
                val bytes = rendering.readBytes()
                ImportExportService.persistFile(ctx, plan.fileName, bytes)
            }
            runCatching { rendering.delete() }
            ScreenshotCaptureOutcome.Success(plan = plan, imagePath = imagePath, extractedText = null)
        } catch (e: Throwable) {
            ScreenshotCaptureOutcome.Error(
                "Screenshot failed (${e::class.java.simpleName}). Try again."
            )
        }
    }
}