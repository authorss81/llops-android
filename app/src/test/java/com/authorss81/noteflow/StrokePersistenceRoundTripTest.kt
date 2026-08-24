package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.StrokeColorMode
import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.EncryptionService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // ---- Phase 201 goldens: the simplified stroke IS the persisted artifact ----
    //
    // Since PERF 1.4 the canvas commits RamerDouglasPeucker.simplify output at a
    // PER-BRUSH epsilon; whatever survives simplification is exactly what
    // pointsJson stores. These pin that the kept geometry (including per-point
    // pressure, which now flows through the SMOOTH gamma curve at capture time)
    // round-trips byte-faithfully.

    private fun wiggleStroke(tool: StrokeTool, width: Float): Stroke {
        val raw = (0 until 80).map { i ->
            PointF(
                x = i.toFloat(),
                y = 50f + kotlin.math.sin(i * 0.4f),
                pressure = 0.05f + 0.01f * (i % 10)
            )
        }
        val epsilon = com.authorss81.noteflow.services.StrokeSimplifyPolicy.epsilonFor(tool, width)
        return Stroke(
            id = "wiggle",
            tool = tool,
            colorInt = 0xFF111111.toInt(),
            width = width,
            points = com.authorss81.noteflow.utils.RamerDouglasPeucker.simplify(raw, epsilon),
            start = PointF(raw.first().x, raw.first().y),
            end = PointF(raw.last().x, raw.last().y),
            pdfPage = 0
        )
    }

    @Test
    fun `hairline-epsilon simplified stroke round-trips every kept point and pressure`() {
        val committed = wiggleStroke(StrokeTool.FINELINER, 1.5f)
        // The hairline commit really did simplify something.
        assertTrue(committed.points.size in 2 until 80)
        val epsilon = com.authorss81.noteflow.services.StrokeSimplifyPolicy.epsilonFor(StrokeTool.FINELINER, 1.5f)
        assertTrue(
            "FINELINER @1.5px must use the tight hairline band, was $epsilon",
            epsilon >= com.authorss81.noteflow.services.StrokeSimplifyPolicy.HAIRLINE_MIN_EPSILON_PX &&
                epsilon <= com.authorss81.noteflow.services.StrokeSimplifyPolicy.HAIRLINE_MAX_EPSILON_PX
        )

        val restored = EncryptionService.deserializeStrokes(
            EncryptionService.serializeStrokes(listOf(committed))
        ).single()

        assertEquals(committed.points.size, restored.points.size)
        for ((i, expected) in committed.points.withIndex()) {
            val actual = restored.points[i]
            assertEquals("x@$i", expected.x, actual.x, 1e-5f)
            assertEquals("y@$i", expected.y, actual.y, 1e-5f)
            assertEquals("pressure@$i", expected.pressure!!, actual.pressure!!, 1e-6f)
        }
    }

    @Test
    fun `coarse-epsilon legacy behaviour still round-trips unchanged`() {
        // MARKER is not a fine-tip tool -> the legacy 1.3 px path, unchanged by 201.
        val committed = wiggleStroke(StrokeTool.MARKER, 12f)
        assertEquals(
            com.authorss81.noteflow.services.StrokeSimplifyPolicy.DEFAULT_EPSILON_PX,
            com.authorss81.noteflow.services.StrokeSimplifyPolicy.epsilonFor(StrokeTool.MARKER, 12f),
            0f
        )
        val restored = EncryptionService.deserializeStrokes(
            EncryptionService.serializeStrokes(listOf(committed))
        ).single()
        assertEquals(committed.points.size, restored.points.size)
        assertEquals(committed.points.first(), restored.points.first())
        assertEquals(committed.points.last(), restored.points.last())
    }
}