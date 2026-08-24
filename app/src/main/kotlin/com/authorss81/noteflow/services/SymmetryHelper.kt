package com.authorss81.noteflow.services

/**
 * Mirror/symmetry modes for the canvas.
 *
 * VERTICAL/HORIZONTAL mirror about an axis through a fixed center; RADIAL is
 * point symmetry (180° rotation about the center), NOT n-fold radial symmetry —
 * it mirrors both axes at once. The stored setting key for RADIAL stays "radial"
 * for backward compatibility with pre-existing prefs.
 *
 * Phase 203: symmetry is a CAPTURE-TIME drawing aid. A stroke COMMITTED while a
 * mode is active persists two independent rows — original + mirrored twin baked
 * by [SymmetryCommitPolicy.bakedTwin] about the frozen world-space axis center.
 * Toggling a mode afterwards never changes/adds/removes anything already on the
 * canvas; the only remaining VIEW-TIME mirror is the LIVE in-progress preview
 * stroke so the user sees the symmetric effect while drawing.
 *
 * Pure math, no Android dependencies, unit-testable on the JVM.
 */
enum class SymmetryMode(val label: String, val settingKey: String) {
    OFF("Off", "off"),
    VERTICAL("Vertical", "vertical"),
    HORIZONTAL("Horizontal", "horizontal"),
    RADIAL("Point", "radial");

    companion object {
        fun fromSettingKey(key: String?): SymmetryMode =
            entries.firstOrNull { it.settingKey == key } ?: OFF
    }
}

data class MirroredPoint(val x: Float, val y: Float)

object SymmetryHelper {

    /**
     * Mirrors a single point about the [centerX]/[centerY] axis/pivot selected by
     * [mode]. Returns the input unchanged when [mode] is OFF. Applying the mirror
     * twice (about the same center) returns the original point.
     */
    fun mirrorPoint(
        x: Float,
        y: Float,
        mode: SymmetryMode,
        centerX: Float,
        centerY: Float
    ): MirroredPoint = when (mode) {
        SymmetryMode.OFF -> MirroredPoint(x, y)
        SymmetryMode.VERTICAL -> MirroredPoint(2f * centerX - x, y)
        SymmetryMode.HORIZONTAL -> MirroredPoint(x, 2f * centerY - y)
        SymmetryMode.RADIAL -> MirroredPoint(2f * centerX - x, 2f * centerY - y)
    }

    /**
     * Mirrors a list of (x, y) points in one shot (pure helper; the stroke-level
     * twin baking works on [com.authorss81.noteflow.data.model.Stroke] via
     * [SymmetryCommitPolicy.bakedTwin]).
     */
    fun mirrorPoints(
        points: List<Pair<Float, Float>>,
        mode: SymmetryMode,
        centerX: Float,
        centerY: Float
    ): List<Pair<Float, Float>> = points.map { (x, y) ->
        val m = mirrorPoint(x, y, mode, centerX, centerY)
        m.x to m.y
    }

    /**
     * True when [mode] actually transforms coordinates (used to skip work and to
     * keep classic rendering identical when symmetry is OFF).
     */
    fun isActive(mode: SymmetryMode): Boolean = mode != SymmetryMode.OFF
}
