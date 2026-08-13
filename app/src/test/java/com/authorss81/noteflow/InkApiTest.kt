package com.authorss81.noteflow

import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.Stroke as InkStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import com.authorss81.noteflow.data.model.Stroke
import com.authorss81.noteflow.data.model.PointF
import com.authorss81.noteflow.data.model.StrokeTool
import org.junit.Test
import org.junit.Assert.*

class InkApiTest {
    @Test
    fun testStrokeConversion() {
        val points = listOf(
            PointF(10f, 20f, 0.5f, 15f, 1000L),
            PointF(12f, 25f, 0.7f, 15f, 1005L)
        )
        val stroke = Stroke(
            id = "test-123",
            tool = StrokeTool.FOUNTAIN_PEN,
            colorInt = 0xFFFF0000.toInt(),
            width = 4.5f,
            points = points
        )

        try {
            val family = StockBrushes.pressurePen()
            val brush = Brush.createWithColorIntArgb(
                family,
                stroke.colorInt,
                stroke.width,
                0.1f
            )

            val inputBatch = MutableStrokeInputBatch()
            for (pt in stroke.points) {
                inputBatch.add(
                    InputToolType.STYLUS,
                    pt.x,
                    pt.y,
                    pt.timestampMs ?: 0L,
                    pt.pressure ?: 0.5f,
                    pt.tilt ?: 0f,
                    0f
                )
            }

            val inkStroke = InkStroke(brush, inputBatch.toImmutable())
            assertNotNull(inkStroke)
            assertEquals(2, inkStroke.inputs.size)
        } catch (e: UnsatisfiedLinkError) {
            // androidx.ink native JNI libraries are unavailable in pure JVM local unit tests
            System.err.println("Skipping InkApiTest in pure JVM env: ${e.message}")
        }
    }
}
