package com.authorss81.noteflow.data.model

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

enum class StrokeTool {
    PEN, FOUNTAIN_PEN, PENCIL, AIRBRUSH, MARKER, HIGHLIGHTER, CALLIGRAPHIC, DOTTED, NEON, FINELINER, CHISEL_MARKER, LASER, ERASER, TEXT, RECTANGLE, LINE, ARROW, ELLIPSE, TRIANGLE, STAR, PENTAGON, HEXAGON, SELECT, PAN, EYEDROPPER, WATERCOLOR, OIL_PAINT, SMUDGE, SPLATTER, STICKER, CHARCOAL, OIL_PASTEL, INK_WASH, GOUACHE, DRY_BRUSH, PALETTE_KNIFE;

    val isShapeTool: Boolean
        get() = when (this) {
            RECTANGLE, LINE, ARROW, ELLIPSE, TRIANGLE, STAR, PENTAGON, HEXAGON -> true
            else -> false
        }

    val isFreehandTool: Boolean
        get() = when (this) {
            PEN, FOUNTAIN_PEN, PENCIL, AIRBRUSH, MARKER, HIGHLIGHTER, CALLIGRAPHIC, DOTTED, NEON, FINELINER, CHISEL_MARKER, LASER, WATERCOLOR, OIL_PAINT, SMUDGE, SPLATTER, CHARCOAL, OIL_PASTEL, INK_WASH, GOUACHE, DRY_BRUSH, PALETTE_KNIFE -> true
            else -> false
        }

    val label: String
        get() = when (this) {
            PEN -> "Pen"
            FOUNTAIN_PEN -> "Fountain Pen"
            PENCIL -> "Pencil & Charcoal"
            AIRBRUSH -> "Airbrush Spray"
            MARKER -> "Watercolor Marker"
            HIGHLIGHTER -> "Highlighter"
            CALLIGRAPHIC -> "Calligraphic"
            DOTTED -> "Dotted / Dashed Line"
            NEON -> "Neon Glow Pen"
            FINELINER -> "Precision Fineliner"
            CHISEL_MARKER -> "Flat Chisel Marker"
            LASER -> "Laser Pointer"
            ERASER -> "Eraser"
            TEXT -> "Text"
            RECTANGLE -> "Rectangle"
            LINE -> "Line"
            ARROW -> "Arrow"
            ELLIPSE -> "Ellipse"
            TRIANGLE -> "Triangle"
            STAR -> "5-Point Star"
            PENTAGON -> "Pentagon"
            HEXAGON -> "Hexagon"
            SELECT -> "Select"
            PAN -> "Pan / Scroll"
            EYEDROPPER -> "Eyedropper"
            WATERCOLOR -> "Real Watercolor (Wet)"
            OIL_PAINT -> "Oil Paint (Wet)"
            SMUDGE -> "Smudge / Finger Blend"
            SPLATTER -> "Paint Splatter & Drops"
            STICKER -> "Sticker"
            CHARCOAL -> "Charcoal (Grainy)"
            OIL_PASTEL -> "Oil Pastel (Waxy)"
            INK_WASH -> "Ink Wash / Sumi-e"
            GOUACHE -> "Gouache (Matte)"
            DRY_BRUSH -> "Dry Brush (Bristle)"
            PALETTE_KNIFE -> "Palette Knife"
        }
}

data class CanvasTextStyle(
    val fontStyle: String = "SANS", // SANS, SERIF, MONO, BOLD, ITALIC, SCRIPT
    val fontSizeSp: Float = 20f,
    val bgHex: String? = null, // e.g. "#FEF08A", "#A7F3D0", "#E0E7FF", null
    val align: String = "LEFT" // LEFT, CENTER, RIGHT
) {
    fun encodeToString(rawText: String): String {
        return "CONFIG:$fontStyle|$fontSizeSp|${bgHex ?: "NONE"}|$align:$rawText"
    }

    companion object {
        fun parse(encoded: String): Pair<CanvasTextStyle, String> {
            if (!encoded.startsWith("CONFIG:")) {
                return Pair(CanvasTextStyle(), encoded)
            }
            return try {
                val configEnd = encoded.indexOf(':', 7)
                if (configEnd == -1) return Pair(CanvasTextStyle(), encoded)
                val configStr = encoded.substring(7, configEnd)
                val text = encoded.substring(configEnd + 1)
                val parts = configStr.split('|')
                val style = CanvasTextStyle(
                    fontStyle = parts.getOrNull(0) ?: "SANS",
                    fontSizeSp = parts.getOrNull(1)?.toFloatOrNull() ?: 20f,
                    bgHex = parts.getOrNull(2)?.takeIf { it != "NONE" },
                    align = parts.getOrNull(3) ?: "LEFT"
                )
                Pair(style, text)
            } catch (e: Exception) {
                Pair(CanvasTextStyle(), encoded)
            }
        }
    }
}

data class PointF(
    val x: Float,
    val y: Float,
    val pressure: Float? = null,
    val tilt: Float? = null,
    val timestampMs: Long? = null
) {
    fun toOffset() = Offset(x, y)
    companion object {
        fun fromOffset(offset: Offset) = PointF(offset.x, offset.y)
        fun fromOffset(offset: Offset, pressure: Float? = null, tilt: Float? = null, timestampMs: Long? = null) =
            PointF(offset.x, offset.y, pressure, tilt, timestampMs)
    }
}

data class Stroke(
    val id: String,
    val tool: StrokeTool = StrokeTool.PEN,
    val colorInt: Int = Color(0xFF1B365D).toArgb(),
    val width: Float = 3f,
    val filled: Boolean = false,
    val text: String = "",
    val points: List<PointF> = emptyList(),
    val start: PointF? = null,
    val end: PointF? = null,
    val pdfPage: Int = 0,
    val timestampMs: Long? = null,
    val isAdvanced: Boolean = false,
    val layerId: String? = null
) {
    val color: Color
        get() = Color(colorInt)
}

enum class MediaEmbedType {
    PHOTO, CODE_BLOCK, AUDIO_NOTE, STICKY_NOTE, STICKER
}

data class CanvasMediaEmbed(
    val id: String = java.util.UUID.randomUUID().toString(),
    val pageId: String,
    val type: MediaEmbedType,
    val x: Float,
    val y: Float,
    val width: Float = 320f,
    val height: Float = 220f,
    val contentUrlOrPath: String? = null,
    val textContent: String? = null,
    val codeLanguage: String? = "Kotlin",
    val durationMs: Long = 0L,
    val waveformAmplitudes: List<Float> = emptyList(),
    val pdfPage: Int = 0,
    val isCollapsed: Boolean = type == MediaEmbedType.AUDIO_NOTE,
    val rotationDegrees: Float = 0f
)

data class CanvasStickyNote(
    val id: String = java.util.UUID.randomUUID().toString(),
    val x: Float,
    val y: Float,
    val width: Float = 220f,
    val height: Float = 180f,
    val text: String = "",
    val colorHex: String = "#FEF08A",
    val pdfPage: Int = 0,
    val isCollapsed: Boolean = false,
    val rotationDegrees: Float = 0f
)

data class PenPreset(
    val id: Int,
    val name: String,
    val tool: StrokeTool,
    val colorInt: Int,
    val width: Float
) {
    val color: Color
        get() = Color(colorInt)
}

