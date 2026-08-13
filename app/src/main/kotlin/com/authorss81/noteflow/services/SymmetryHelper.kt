package com.authorss81.noteflow.services

/**
 * Mirror/symmetry modes for the canvas.
 *
 * Symmetry is strictly a VIEW-TIME transform: the stored stroke data keeps the
 * real (unmirrored) points so saved notes remain portable and export correctly.
 * The mirror is applied while rendering and, optionally, while capturing input.
 *
 * Pure math, no Android dependencies, unit-testable on the JVM.
 */
enum class SymmetryMode(val label: String, val settingKey: String) {
    OFF("Off", "off"),
    VERTICAL("Vertical", "vertical"),
    HORIZONTAL("Horizontal", "horizontal"),
    RADIAL("Radial", "radial");

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
     * Mirrors a list of points in one shot (used for whole-stroke rendering).
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