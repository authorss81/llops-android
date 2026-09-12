package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 273 — `FloatingZoomWidget.kt` was ported but never composed (dead code).
 * This suite pins the `EditorScreen` wiring so a future edit cannot silently
 * drop the widget again:
 *   - the import is present,
 *   - `FloatingZoomWidget(` is composed in `EditorScreen.kt` exactly once,
 *   - the callbacks write the editor's own `zoomScale`/`panOffset` states (the
 *     same states passed to `AnnotationCanvas` — no shadow zoom state),
 *   - the fit math (`screenW/1080f`, `screenH/(1528f+64f)`, clamp `0.25f..5.0f`)
 *     is present.
 */
class Phase273ZoomWidgetTest {

    private fun mainSource(rel: String): String {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            File(d, "src/main/kotlin/com/authorss81/noteflow/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "src/main/kotlin/$rel").takeIf { it.isFile }?.let { return it.readText() }
            File(d, "app/src/main/kotlin/com/authorss81/noteflow/$rel").takeIf { it.isFile }?.let { return it.readText() }
            dir = d.parentFile
        }
        throw AssertionError("could not locate app/src/main/kotlin/$rel from ${start.path}")
    }

    private fun editorSource(): String = mainSource("ui/screens/EditorScreen.kt")

    @Test
    fun `FloatingZoomWidget import is present`() {
        val src = editorSource()
        assertTrue(
            "EditorScreen must import FloatingZoomWidget",
            src.contains("import com.authorss81.noteflow.ui.components.FloatingZoomWidget")
        )
    }

    @Test
    fun `FloatingZoomWidget is composed exactly once`() {
        val src = editorSource()
        val count = Regex("FloatingZoomWidget\\(").findAll(src).count()
        assertEquals("FloatingZoomWidget( must be composed exactly once", 1, count)
    }

    @Test
    fun `zoom callbacks write the editor zoomScale and panOffset states`() {
        val src = editorSource()
        val widget = src.substring(src.indexOf("FloatingZoomWidget("))
        assertTrue(
            "onZoomChange must write zoomScale",
            widget.contains("onZoomChange = { newZoom -> zoomScale = newZoom }")
        )
        assertTrue(
            "onFitWidth must write zoomScale",
            widget.contains("zoomScale = targetZoom")
        )
        assertTrue(
            "fit/reset callbacks must reset pan X via panOffset",
            widget.contains("panOffset = Offset(0f, panOffset.y)")
        )
        assertTrue(
            "onResetZoom must restore zoomScale to 1",
            widget.contains("zoomScale = 1.0f")
        )
        assertTrue(
            "widget must read the editor isPdf flag",
            widget.contains("isPdfOrDocument = isPdf")
        )
    }

    @Test
    fun `fit math uses page stride with clamp`() {
        val src = editorSource()
        val widget = src.substring(src.indexOf("FloatingZoomWidget("))
        assertTrue("fit-width math must divide by 1080f", widget.contains("screenW / 1080f"))
        assertTrue("fit-page math must divide by 1528f + 64f", widget.contains("screenH / (1528f + 64f)"))
        assertTrue("fit math must clamp to 0.25f..5.0f", widget.contains("coerceIn(0.25f, 5.0f)"))
    }

    @Test
    fun `canvas consumes the same zoomScale and panOffset states`() {
        val src = editorSource()
        val canvas = src.substring(src.indexOf("AnnotationCanvas("))
        assertTrue("AnnotationCanvas must read zoomScale", canvas.contains("zoomScale = zoomScale"))
        assertTrue("AnnotationCanvas must read panOffset", canvas.contains("panOffset = panOffset"))
    }
}
