package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeTool

/**
 * Phase 203: capture-time symmetry baking.
 *
 * Symmetry used to be a pure VIEW-TIME transform: [com.authorss81.noteflow.ui.components.AnnotationCanvas]
 * re-drew EVERY committed stroke a second time, mirrored, whenever a symmetry mode
 * was active. Consequence (user report): enabling symmetry retroactively showed
 * mirror copies of old strokes; disabling made them vanish — "toggling deletes my
 * mirrored strokes".
 *
 * The fix: symmetry became a CAPTURE-TIME decision. A stroke committed while a
 * mode is active persists as TWO independent stroke rows — the original and its
 * mirrored twin ([bakedTwin], fresh id, same visual attributes). Toggling the
 * mode afterwards never changes, adds, or removes anything already on the canvas;
 * it only affects strokes drawn AFTER the toggle.
 *
 * Coordinate contract: stroke points are stored in WORLD coordinates and the
 * caller must resolve [centerX]/[centerY] in that SAME world space — exactly the
 * center the live preview showed during the gesture (AnnotationCanvas freezes
 * `symmetryCenterFor(...)` at drag end). Mirroring about the world centre keeps
 * the twin inside the same page slab on every page >= 1 (the phase-202 finding).
 *
 * Pure JVM: only data-model geometry, no Android imports.
 */
object SymmetryCommitPolicy {

    /**
     * True when a stroke committed with [mode] active must gain a baked mirrored
     * twin. TEXT is excluded — text cannot sensibly reflect and was never
     * mirrored by the old view-time pass either.
     */
    fun shouldBakeMirror(mode: SymmetryMode, tool: StrokeTool): Boolean =
        mode != SymmetryMode.OFF && tool != StrokeTool.TEXT

    /**
     * The mirrored twin of [stroke]: every point/start/end reflected through
     * [SymmetryHelper.mirrorPoint] about ([centerX], [centerY]) using [mode],
     * with a FRESH unique id so the twin is an independent row (erasing either
     * copy leaves the other). All visual attributes — tool, colorInt, width,
     * filled, text, pdfPage, timestampMs, isAdvanced, layerId, colorMode,
     * colorSeed, gradientToColorInt — are carried over unchanged, and per-point
     * pressure/tilt/timestamp are preserved by mirroring each [PointF] via
     * [PointF.copy] (only x/y change).
     *
     * Geometry note: applying the mirror twice about the SAME center returns the
     * original point, so `bakedTwin(bakedTwin(s, m, c), m, c)` has the source's
     * geometry again (with different ids).
     */
    fun bakedTwin(stroke: Stroke, mode: SymmetryMode, centerX: Float, centerY: Float): Stroke {
        fun mirror(p: PointF): PointF {
            val m = SymmetryHelper.mirrorPoint(p.x, p.y, mode, centerX, centerY)
            return p.copy(x = m.x, y = m.y)
        }
        return stroke.copy(
            id = java.util.UUID.randomUUID().toString(),
            points = stroke.points.map(::mirror),
            start = stroke.start?.let(::mirror),
            end = stroke.end?.let(::mirror)
        )
    }
}
