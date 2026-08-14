package com.authorss81.noteflow.plugins.inktos

import android.content.Context
import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.plugins.NoteflowPlugin
import com.authorss81.noteflow.plugins.PluginAvailability
import com.authorss81.noteflow.plugins.PluginCapability
import com.authorss81.noteflow.plugins.PluginManifest
import com.authorss81.noteflow.plugins.PluginSettings
import com.authorss81.noteflow.plugins.SemanticVersion
import com.authorss81.noteflow.plugins.ShapeFromInkOutcome
import com.authorss81.noteflow.plugins.ShapeFromInkPlugin
import com.authorss81.noteflow.plugins.ShapeKind

/**
 * Ink → Shape (Phase 25): convert a freehand ink stroke into a clean, crisp
 * geometric shape ON DEMAND in the canvas.
 *
 * A **free, lightweight compile-time plugin** under the hybrid architecture:
 * pure geometry only (no ML, no camera, no network, no new permissions, no
 * native deps) — safe to ship in the base APK, adding only a few KB. The heavy
 * math lives in [InkToShapeGeometry] (PURE JVM, Android-free); this wrapper is
 * a thin mapping from the app's [Stroke] model to the geometry core and back.
 *
 * - Serves [PluginCapability.ShapeFromInk] via [InkToShapePlugin].
 * - `availability()` is always `Ok` — geometry needs no device capability.
 * - **Opt-in off by default**; toggle in Settings → Plugins / the Plugin Store.
 * - Namespaced setting `plugins.<id>.keepOriginal` (default **false**):
 *   false = the raw freehand stroke is REPLACED by the clean shape;
 *   true = the original is kept and the shape is inserted alongside it.
 * - Honestly rejects non-shape strokes ([ShapeFromInkOutcome.NotAShape]) — a
 *   rough mark is left untouched, never silently faked into a shape.
 *
 * Distinct from the canvas's existing auto-snap-on-draw-end: that path snaps
 * during drawing (and is engine-coupled); this is an explicit, user-triggered
 * convert that routes through the plugin framework so the user can turn it off.
 */
class InkToShapePlugin : NoteflowPlugin, ShapeFromInkPlugin {

    override val manifest = PluginManifest(
        id = ID,
        name = "Ink to Shape",
        version = SemanticVersion(1, 0, 0),
        minSupportedApi = MIN_API,
        description = "Converts a freehand ink stroke into a clean line, rectangle, ellipse or arrow on demand.",
        capabilities = setOf(PluginCapability.ShapeFromInk)
    )

    @Volatile
    private var settings: PluginSettings? = null

    override fun availability(context: Context?): PluginAvailability = PluginAvailability.Ok

    override fun onEnable(context: Context?, settings: PluginSettings) {
        this.settings = settings
    }

    override fun onDisable(context: Context?, settings: PluginSettings) {
        this.settings = null
    }

    override fun onConfigChanged(context: Context?, settings: PluginSettings) {
        this.settings = settings
    }

    /** Whether the raw stroke should be kept alongside the converted shape. */
    fun keepOriginal(): Boolean = settings?.getBoolean(SETTING_KEEP_ORIGINAL, false) ?: false

    override fun convertToShape(rawStroke: Stroke): ShapeFromInkOutcome {
        if (rawStroke.points.size < 2) {
            return ShapeFromInkOutcome.NotAShape(
                "That stroke has no usable geometry — draw a line, circle, rectangle or arrow to convert."
            )
        }
        val inkPoints = rawStroke.points.map { InkPoint(it.x, it.y) }
        val detected = InkToShapeGeometry.detect(inkPoints)
            ?: return ShapeFromInkOutcome.NotAShape(
                "No clean shape detected — the stroke is too rough or not a line, circle, rectangle or arrow."
            )

        val snapped = buildStroke(rawStroke, detected)
        val keepOriginal = keepOriginal()
        return ShapeFromInkOutcome.Success(
            kind = detected.type.toShapeKind(),
            snappedStroke = snapped,
            replaceOriginal = !keepOriginal
        )
    }

    private fun buildStroke(raw: Stroke, detected: InkToShapeGeometry.DetectedShape): Stroke {
        val snappedPoints = detected.points.map { PointF(it.x, it.y) }
        return raw.copy(
            tool = detected.type.toStrokeTool(),
            start = detected.start.toPointF(),
            end = detected.end.toPointF(),
            points = snappedPoints
        )
    }

    private fun InkPoint.toPointF() = PointF(x, y)

    private fun InkToShapeGeometry.ShapeType.toStrokeTool(): StrokeTool = when (this) {
        InkToShapeGeometry.ShapeType.LINE -> StrokeTool.LINE
        InkToShapeGeometry.ShapeType.RECTANGLE -> StrokeTool.RECTANGLE
        InkToShapeGeometry.ShapeType.ELLIPSE -> StrokeTool.ELLIPSE
        InkToShapeGeometry.ShapeType.ARROW -> StrokeTool.ARROW
    }

    private fun InkToShapeGeometry.ShapeType.toShapeKind(): ShapeKind = when (this) {
        InkToShapeGeometry.ShapeType.LINE -> ShapeKind.LINE
        InkToShapeGeometry.ShapeType.RECTANGLE -> ShapeKind.RECTANGLE
        InkToShapeGeometry.ShapeType.ELLIPSE -> ShapeKind.ELLIPSE
        InkToShapeGeometry.ShapeType.ARROW -> ShapeKind.ARROW
    }

    companion object {
        const val MIN_API = 26

        /** Stable plugin id (reverse-DNS). UI reads namespaced settings with it. */
        const val ID = "com.authorss81.noteflow.plugins.inktos"

        /** Namespaced setting key: true = keep the raw stroke, insert the shape. */
        const val SETTING_KEEP_ORIGINAL = "keepOriginal"
    }
}
