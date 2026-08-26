package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 213 — structural pins for the stroke drop-shadow ("paper elevation")
 * pipeline. Layout Inspector / Paparazzi-on-device verification is unavailable
 * on this runner, so the wiring contract is pinned at source level:
 *
 *  1. `drawSingleStroke` draws a BrushShadowPolicy-planned shadow UNDER the
 *     ink BEFORE both render paths (advanced androidx.ink path included).
 *  2. The pooled renderer allocates NO Paint/Path/BlurMaskFilter per draw and
 *     never inside any per-segment loop.
 *  3. Both layer-raster cache keys include the shadow flag so toggling the
 *     setting cannot serve stale flat/shadowed ink.
 *  4. The user setting exists (default ON), reaches AnnotationCanvas from
 *     EditorScreen, and is togglable from Canvas & Paper Options.
 *  5. Low-end devices are auto-offed ONCE with an honest message (never silent
 *     degradation) and a re-enable is honored — no hidden tier gate in the
 *     renderer path.
 */
class Phase213BrushShadowTest {

    private fun source(rel: String): String = File(repoRoot(), rel).readText()

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

    private fun canvasSource(): String =
        source("app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt")

    // ---- 1. Shadow underlay inside drawSingleStroke -----------------------------

    @Test
    fun `drawSingleStroke plans through BrushShadowPolicy and draws via the pooled renderer`() {
        val src = canvasSource()
        val fnStart = src.indexOf("private fun DrawScope.drawSingleStroke(")
        assertTrue(fnStart >= 0)
        val fnEnd = src.indexOf("when (stroke.tool)", fnStart)
        assertTrue(fnEnd > fnStart)
        val head = src.substring(fnStart, fnEnd)
        assertTrue(
            "shadow plan must come from the policy decision table",
            head.contains("BrushShadowPolicy.plan(")
        )
        assertTrue(
            "the underlay must draw through StrokeShadowRenderer",
            head.contains("StrokeShadowRenderer.drawStrokeShadow(")
        )
        // Theme awareness: the paper decides the tint, not the ink.
        assertTrue(head.contains("isDarkPaper = isDarkPaper"))
    }

    @Test
    fun `shadow gate threads through every render chain`() {
        val src = canvasSource()
        assertEquals(
            "drawSingleStroke must carry the shadowEnabled param exactly once",
            1,
            src.split("shadowEnabled: Boolean = false").size - 1
        )
        // Committed + live closures inside drawCompositedLayersStrokes.
        assertTrue(src.contains("vibrancy = vibrancyBoost, shadowEnabled = strokeShadowEnabled"))
        // The AGSL wet pass receives and re-threads the flag.
        assertTrue(
            src.contains(
                "vibrancyBoost = vibrancyBoost,\n" +
                    "                strokeShadowEnabled = strokeShadowEnabled\n" +
                    "            )"
            )
        )
        assertTrue(
            src.contains(
                "vibrancyBoost: Float = 0f,\n" +
                    "    strokeShadowEnabled: Boolean = false\n" +
                    ")"
            )
        )
        // LiveStrokePreview casts the same shadow as the committed stroke will.
        assertEquals(
            1,
            src.split("strokeShadowEnabled: Boolean,").size - 1
        )
    }

    // ---- 2. Pooled renderer: zero hot-path allocation ---------------------------

    @Test
    fun `renderer pools paint path and blur filter - no allocation per draw`() {
        val src = source("app/src/main/kotlin/com/authorss81/noteflow/ui/components/StrokeShadowRenderer.kt")
        assertEquals(
            "exactly ONE shared Paint for the whole process",
            1,
            src.split("Paint(Paint.ANTI_ALIAS_FLAG)").size - 1
        )
        assertEquals(
            "exactly ONE reusable Path",
            1,
            src.split("val path = Path()").size - 1
        )
        assertEquals(
            "BlurMaskFilter is constructed at ONE cached site only (radius change), never per segment",
            1,
            src.split("BlurMaskFilter(").size - 1
        )
        assertTrue("blur must be NORMAL soft blur", src.contains("BlurMaskFilter.Blur.NORMAL"))
        assertTrue("geometry resets into the shared path", src.contains("path.reset()"))
    }

    // ---- 3. Cache keys ----------------------------------------------------------

    @Test
    fun `both layer-raster cache keys include the shadow flag`() {
        val src = canvasSource()
        assertEquals(
            2,
            src.split("_s\${if (strokeShadowEnabled) 1 else 0}").size - 1
        )
        assertTrue(src.contains("\"\${pageIdx}_\${defaultLayerId}_v\${vibrancyBoost}_s"))
        assertTrue(src.contains("\"\${pageIdx}_\${layer.id}_v\${vibrancyBoost}_s"))
    }

    // ---- 4. Setting surface ------------------------------------------------------

    @Test
    fun `paper elevation setting defaults ON and persists prefs-only`() {
        val settings = source("app/src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt")
        assertTrue(settings.contains("prefs.getBoolean(\"paper_elevation_enabled\", true)"))
    }

    @Test
    fun `editor wires the setting into the canvas and the options sheet`() {
        val editor = source("app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt")
        assertTrue(editor.contains("var paperElevationEnabled by remember { mutableStateOf(viewModel.settings.paperElevationEnabled) }"))
        assertTrue(editor.contains("paperElevationEnabled = paperElevationEnabled"))
        assertTrue(editor.contains("viewModel.settings.paperElevationEnabled = enabled"))
        assertTrue(editor.contains("\"Paper Elevation\""))
    }

    // ---- 5. Honest low-end degradation --------------------------------------------

    @Test
    fun `low-end devices get a one-time auto-off with message and can re-enable`() {
        val editor = source("app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt")
        assertTrue(editor.contains("lowEndPaperElevationWarningShown"))
        assertTrue(
            editor.contains("Paper elevation shadows turned off for low-end device performance.")
        )
        // The renderer reads ONLY the user flag — no hidden tier gate that would
        // silently defeat a deliberate re-enable.
        val canvas = canvasSource()
        assertTrue(
            "the shadow gate must be exactly the user setting",
            canvas.contains("val strokeShadowEnabled = paperElevationEnabled")
        )
        assertFalse(
            "no shadow-specific device-tier probe may exist in the canvas",
            canvas.contains("deviceIsLowEnd")
        )
    }

    @Test
    fun `policy documents and pins the GPU carrier tier table`() {
        val policy = source("app/src/main/kotlin/com/authorss81/noteflow/services/BrushShadowPolicy.kt")
        assertTrue(policy.contains("fun gpuCarrierPreferred(sdkInt: Int, lowEndDevice: Boolean): Boolean"))
        assertTrue("AGSL shader uniforms stay untouched this phase", !policy.contains("uShadow"))
    }
}
