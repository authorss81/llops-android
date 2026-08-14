package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeColorMode
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.EncryptionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 27: the color mode + seed + gradient end color must round-trip through
 * the stroke's existing serialized payload (pointsJson) with NO DB schema change.
 * Old strokes (payloads without the fields) must deserialize as SOLID / seed 0 —
 * bit-identical to the pre-phase-27 behaviour.
 */
class StrokePersistenceRoundTripTest {

    private fun points(vararg xs: Float): List<PointF> =
        xs.mapIndexed { i, x -> PointF(x, i * 2f) }

    @Test
    fun `color mode seed and gradient end round-trip through serializeStrokes`() {
        val stroke = Stroke(
            id = "abc",
            tool = StrokeTool.PEN,
            colorInt = 0xFF224488.toInt(),
            width = 5f,
            points = points(0f, 10f, 20f),
            start = PointF(0f, 0f),
            end = PointF(20f, 4f),
            pdfPage = 0,
            colorMode = StrokeColorMode.RAINBOW,
            colorSeed = 123,
            gradientToColorInt = 0xFFFFAA00.toInt()
        )
        val json = EncryptionService.serializeStrokes(listOf(stroke))
        val restored = EncryptionService.deserializeStrokes(json).first()

        assertEquals(StrokeColorMode.RAINBOW, restored.colorMode)
        assertEquals(123, restored.colorSeed)
        assertEquals(0xFFFFAA00.toInt(), restored.gradientToColorInt)
        assertEquals(stroke.colorInt, restored.colorInt)
        assertEquals(stroke.width, restored.width, 1e-5f)
        assertEquals(stroke.points.size, restored.points.size)
    }

    @Test
    fun `gradient mode persists a null gradient end as a null`() {
        val stroke = Stroke(
            id = "g",
            tool = StrokeTool.PEN,
            colorInt = 0xFF111111.toInt(),
            width = 3f,
            points = points(1f, 2f),
            pdfPage = 0,
            colorMode = StrokeColorMode.GRADIENT,
            colorSeed = 7,
            gradientToColorInt = null
        )
        val restored = EncryptionService.deserializeStrokes(
            EncryptionService.serializeStrokes(listOf(stroke))
        ).first()
        assertEquals(StrokeColorMode.GRADIENT, restored.colorMode)
        assertNull(restored.gradientToColorInt)
    }

    @Test
    fun `old strokes without the new fields deserialize as SOLID and seed zero`() {
        val black = 0xFF000000.toInt() // -16777216
        val legacyJson = """[{"id":"old","tool":"PEN","colorInt":$black,"width":4.0,"points":[{"x":1.0,"y":2.0}],"pdfPage":0}]"""
        val restored = EncryptionService.deserializeStrokes(legacyJson).first()
        // Gson allocates without running Kotlin constructor defaults, so the raw
        // stroke carries a null mode; the app normalizes it via fromKey exactly
        // like NoteRepository.getStrokesForPage does for old strokes.
        assertEquals(StrokeColorMode.SOLID, StrokeColorMode.fromKey(restored.colorMode?.persistenceKey))
        assertEquals(0, restored.colorSeed ?: 0)
        assertNull(restored.gradientToColorInt)
        assertEquals(black, restored.colorInt)
        assertEquals(1.0f, restored.points[0].x, 1e-5f)
    }

    @Test
    fun `fromKey falls back to SOLID for unknown keys`() {
        assertEquals(StrokeColorMode.SOLID, StrokeColorMode.fromKey("SOLID"))
        assertEquals(StrokeColorMode.RAINBOW, StrokeColorMode.fromKey("RAINBOW"))
        assertEquals(StrokeColorMode.SOLID, StrokeColorMode.fromKey("WEIRD"))
        assertEquals(StrokeColorMode.SOLID, StrokeColorMode.fromKey(null))
    }

    @Test
    fun `default stroke is SOLID seed zero with no gradient end`() {
        val plain = Stroke(
            id = "p",
            tool = StrokeTool.PEN,
            colorInt = 0xFF000000.toInt(),
            width = 2f,
            points = points(0f, 5f),
            pdfPage = 0
        )
        assertEquals(StrokeColorMode.SOLID, plain.colorMode)
        assertEquals(0, plain.colorSeed)
        assertNull(plain.gradientToColorInt)
    }
}