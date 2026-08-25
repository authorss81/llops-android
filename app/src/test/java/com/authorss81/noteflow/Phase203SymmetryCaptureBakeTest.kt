package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 203: symmetry mirrors are BAKED AT CAPTURE TIME — toggling a mode never
 * rewrites canvas history.
 *
 * User report: enabling symmetry retroactively showed mirrored copies of old
 * strokes and disabling made them vanish ("deletes my previously mirrored
 * strokes"), because AnnotationCanvas re-drew EVERY committed stroke a second
 * time, mirrored, whenever a mode was active.
 *
 * UI tests cannot run on this CI runner (no device/emulator), so the behavior
 * contract is pinned STRUCTURALLY at source level:
 *
 *  1. Committed strokes render EXACTLY ONCE — no draw path applies a mirror to
 *     committed strokes anymore; SymmetryHelper.mirrorPoint is reachable ONLY
 *     from live-preview paths (in-progress classic overlay + wet preview).
 *  2. The commit site bakes original + twin in ONE update (single undo step),
 *     using the FROZEN axis center of the gesture.
 *  3. The toggle handler writes ONLY settings/local state — never strokes.
 *  4. Eraser + symmetry compose through plain per-stroke deletion: erasing one
 *     twin leaves the other (no view-time mirror hit-test special-casing).
 */
class Phase203SymmetryCaptureBakeTest {

    private fun repoRoot(): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val d: File = dir
            if (File(d, "app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt").isFile) return d
            dir = d.parentFile
        }
        return start
    }

    private fun source(rel: String): String {
        val f = File(repoRoot(), rel)
        assertTrue("$rel must exist", f.isFile)
        return f.readText()
    }

    /** Source with comment lines removed so pins never trip on their own docs. */
    private fun codeOnly(raw: String): String =
        raw.lineSequence()
            .filterNot { line ->
                val t = line.trimStart()
                t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") || t.startsWith("*/")
            }
            .joinToString("\n")

    private fun canvas(): String =
        codeOnly(source("app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt"))

    private fun editorScreen(): String =
        codeOnly(source("app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt"))

    private fun count(haystack: String, needle: String): Int = haystack.split(needle).size - 1

    // ---- 1. Renderer honesty: committed ink renders once ------------------------

    @Test
    fun `the committed-stroke render mirror is gone for good`() {
        val src = canvas()
        assertEquals(
            "drawStrokeWithSymmetry must not resurrect - it re-mirrored ALL committed strokes every frame",
            0,
            count(src, "drawStrokeWithSymmetry")
        )
        val fn = src
            .substringAfter("fun DrawScope.drawCommittedStrokeOnce(stroke: Stroke, offsetY: Float)")
            .substringBefore("fun DrawScope.drawLivePreviewWithSymmetry")
        assertEquals(
            "the committed helper draws exactly once - no mirror pass",
            1,
            count(fn, "drawSingleStroke(")
        )
        assertFalse(fn.contains("mirrorPoint"))
    }

    @Test
    fun `every committed-stroke loop uses the single-pass helper`() {
        val src = canvas()
        // Definition + 5 call sites: no-layers cache, no-layers direct,
        // layer cache, layer normal/direct, layer saveLayer.
        assertEquals(
            "all committed call sites must be single-pass",
            6,
            count(src, "drawCommittedStrokeOnce(")
        )
    }

    @Test
    fun `the live preview keeps its mirror so drawing still shows the symmetric effect`() {
        val src = canvas()
        val previewFn = src
            .substringAfter("fun DrawScope.drawLivePreviewWithSymmetry")
            .substringBefore("if (layers.isEmpty())")
        assertTrue(
            "preview mirror gate must survive",
            previewFn.contains("sMode != SymmetryMode.OFF && stroke.tool != StrokeTool.TEXT")
        )
        assertTrue("preview mirror must survive", previewFn.contains("mirrorPoint"))
        // Preview call sites keep the mirror; committed loops do not.
        assertEquals(
            "all five preview sites keep the view-time mirror",
            5,
            count(src, "drawLivePreviewWithSymmetry(previewStroke")
        )
    }

    @Test
    fun `mirrorPoint is reachable only from live-preview paths inside the canvas`() {
        val src = canvas()
        // 1x classic LiveStrokePreview overlay + 3x drawLivePreviewWithSymmetry.
        assertEquals(
            "an unexpected mirrorPoint reference appeared - a committed path may be mirroring again",
            4,
            count(src, "SymmetryHelper.mirrorPoint")
        )
        val liveOverlayStart = src.indexOf("private fun LiveStrokePreview(")
        assertTrue(liveOverlayStart > 0)
        var searchFrom = 0
        while (true) {
            val idx = src.indexOf("SymmetryHelper.mirrorPoint", searchFrom)
            if (idx < 0) break
            assertTrue(
                "a mirrorPoint reference exists BEFORE the live-preview region - a committed-stroke path is mirroring again",
                idx > liveOverlayStart
            )
            searchFrom = idx + 1
        }
        // And the policy owns every non-UI bake.
        assertTrue(src.contains("com.authorss81.noteflow.services.SymmetryCommitPolicy.bakedTwin("))
    }

    // ---- 2. Capture-time baking at the commit site -------------------------------

    @Test
    fun `commit freezes the mode and axis center before the stroke build`() {
        // Phase 205: the background build became a synchronous inline build —
        // the freeze invariant is unchanged: mode + exact axis center are
        // captured BEFORE the committed geometry is constructed.
        val src = canvas()
        val freezeIdx = src.indexOf(
            "val bakeMirrorTwin = com.authorss81.noteflow.services.SymmetryCommitPolicy.shouldBakeMirror(symmetryMode, tool)"
        )
        val buildIdx = src.indexOf("val candidateStroke = Stroke(", freezeIdx)
        assertTrue(freezeIdx > 0 && buildIdx > freezeIdx)
        val frozen = src.substring(freezeIdx, buildIdx)
        assertTrue(
            "the axis center must be FROZEN from the same resolver the live preview used",
            frozen.contains("symmetryCenterFor(size.width.toFloat(), calculatePageYOffset(targetPage))")
        )
        assertTrue(frozen.contains("val commitSymmetryMode = symmetryMode"))
    }

    @Test
    fun `original plus twin are added in ONE onStrokesChanged update - single undo step`() {
        val src = canvas()
        val batchIdx = src.indexOf("val commitBatch = if (bakeMirrorTwin && symmetryAxisCenter != null)")
        val twinIdx = src.indexOf(".bakedTwin(", batchIdx)
        val addAllIdx = src.indexOf("activeStrokeList.addAll(commitBatch)", batchIdx)
        val changedIdx = src.indexOf("onStrokesChanged(", addAllIdx)
        assertTrue(batchIdx > 0 && twinIdx > batchIdx && addAllIdx > twinIdx && changedIdx > addAllIdx)
        // The twin is built FROM the finalized stroke (post shape-snap / RDP).
        val twinCall = src.substring(twinIdx, twinIdx + 120)
        assertTrue(twinCall.contains("stroke = newStroke"))
        // No bare single-stroke commit remains ON THE FREEHAND PATH (the TEXT
        // dialog legitimately adds its own non-baked row elsewhere).
        val freehandCommitRegion = src.substring(batchIdx).substringBefore("val hapticGate")
        assertEquals(
            "the old single-add commit must not resurrect alongside the batch",
            0,
            count(freehandCommitRegion, "activeStrokeList.add(newStroke)")
        )
    }

    @Test
    fun `TEXT commits gain no twin - policy gate covers the dialog path`() {
        val src = canvas()
        val textCommit = src.indexOf("tool = StrokeTool.TEXT,")
        assertTrue(textCommit > 0)
        val dialogRegion = src.substring(textCommit, src.indexOf("showTextInputDialog = false", textCommit))
        assertFalse(dialogRegion.contains("bakedTwin"))
        assertFalse(dialogRegion.contains("SymmetryCommitPolicy"))
    }

    // ---- 3. Toggle handler writes only settings state -----------------------------

    @Test
    fun `symmetry toggle handler touches only local state and settings - never strokes`() {
        val src = editorScreen()
        val handler = src
            .substringAfter("onSymmetryModeSelect = { mode ->")
            .substringBefore("onContinuousModeToggle")
        assertTrue(handler.contains("symmetryMode = mode"))
        assertTrue(handler.contains("viewModel.settings.symmetryModeKey = mode.settingKey"))
        assertFalse("toggling must NEVER mutate the strokes list", handler.contains("strokes ="))
        assertFalse(handler.contains("onStrokesChanged"))
        assertFalse(handler.contains("undoStack"))
        assertFalse(handler.contains("redoStack"))
    }

    @Test
    fun `initial editor state still reads the persisted mode only`() {
        val src = editorScreen()
        val line = src.lineSequence().firstOrNull { it.contains("var symmetryMode by remember") }
        assertTrue(line != null)
        assertTrue(line!!.contains("SymmetryMode.fromSettingKey(viewModel.settings.symmetryModeKey)"))
    }

    // ---- 4. Eraser composes through plain deletion ---------------------------------

    @Test
    fun `eraser hit-test has no view-time mirror special-case left`() {
        val src = canvas()
        val lambda = src
            .substringAfter("val erasesStroke: (Stroke, Offset) -> Boolean = { stroke, offset ->")
            .substringBefore("fun applyEraser(")
        assertTrue(lambda.contains("strokeContainsPoint(stroke, offset)"))
        assertFalse("erase-through-mirror must not resurrect", lambda.contains("mirror"))
        assertFalse(lambda.contains("SymmetryHelper"))
    }

    @Test
    fun `eraser cursor highlight predicts plain row deletion too`() {
        val src = canvas()
        val overlayStart = src.indexOf("private fun LiveStrokePreview(")
        val highlightIdx = src.indexOf("val hits = strokeContainsPoint(stroke, cursorPos)", overlayStart)
        assertTrue(highlightIdx > overlayStart)
        val highlightRegion = src.substring(highlightIdx, highlightIdx + 200)
        assertFalse(highlightRegion.contains("mirror"))
    }

    // ---- 5. Policy purity ------------------------------------------------------------

    @Test
    fun `SymmetryCommitPolicy is pure JVM - no Android imports`() {
        val src = source("app/src/main/kotlin/com/authorss81/noteflow/services/SymmetryCommitPolicy.kt")
        val imports = src.lineSequence()
            .filter { it.trimStart().startsWith("import ") }
            .map { it.trim() }
            .toList()
        assertTrue(imports.isNotEmpty())
        imports.forEach { imp ->
            assertFalse(
                "policy must stay pure JVM (offending import $imp)",
                imp.startsWith("import androidx") || imp.startsWith("import android")
            )
        }
        assertTrue(src.contains("object SymmetryCommitPolicy"))
        assertTrue(src.contains("fun shouldBakeMirror(mode: SymmetryMode, tool: StrokeTool): Boolean"))
        assertTrue(src.contains("fun bakedTwin(stroke: Stroke, mode: SymmetryMode, centerX: Float, centerY: Float): Stroke"))
    }

    // ---- 6. Cache-key decoupling (phase-203 review fixes) ----------------------------

    @Test
    fun `committed-raster cache keys no longer embed the symmetry mode`() {
        val src = canvas()
        assertFalse(
            "a toggle must not invalidate committed-stroke bitmaps - the mode is out of every cache key",
            src.contains("_\${symmetryMode}_v")
        )
        assertEquals(
            "both raster caches (no-layers + per-layer) keep the page_layer_v format",
            2,
            count(src, "cacheKey = \"\${pageIdx}")
        )
    }

    @Test
    fun `pointerInput keys RETAIN symmetryMode - the drag-end freeze reads the captured param`() {
        val src = canvas()
        val keyLine = src.lineSequence().firstOrNull {
            it.trimStart().startsWith(".pointerInput(currentTool, currentColor")
        }
        assertTrue("the freehand gesture pointerInput block must exist", keyLine != null)
        assertTrue(
            "symmetryMode must stay in the pointerInput keys: shouldBakeMirror(symmetryMode, tool) at drag end " +
                "reads the CAPTURED parameter, so dropping the key would bake twins with a stale mode after a toggle",
            keyLine!!.contains("symmetryMode")
        )
    }
}
