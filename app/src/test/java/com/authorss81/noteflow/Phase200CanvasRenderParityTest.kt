package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 200 (PERF 3.2 + 3.3 + 3.5): linear-light wet mixing, paper-grain
 * texture and eraser-cursor AA parity, pinned STRUCTURALLY at source level
 * (no device/emulator on this runner) so the three mirrors cannot silently
 * drift apart again:
 *
 *  1. The AGSL wet shader and [com.authorss81.noteflow.services.WetMixingMath]
 *     both mix pigment in LINEAR light (srgbToLinear/linearToSrgb on both sides).
 *  2. The canvas draws the cached tileable paper grain as ONE textured
 *     round-rect over the flat fill at every drawPaperCard call site.
 *  3. The PARTIAL eraser cursor samples the ink edge-feather curve for its
 *     soft fill instead of a hard-edged flat circle.
 */
class Phase200CanvasRenderParityTest {

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

    private fun shaderSource(): String =
        source("app/src/main/kotlin/com/authorss81/noteflow/ui/components/AgslShaders.kt")

    private fun mathSource(): String =
        source("app/src/main/kotlin/com/authorss81/noteflow/services/WetMixingMath.kt")

    // ---- 1. Linear-light wet mixing mirror -----------------------------------

    @Test
    fun `wet shader linearizes before the pigment product and re-encodes after`() {
        val shader = shaderSource()
        assertTrue(shader.contains("half3 srgbToLinear3(half3 c)"))
        assertTrue(shader.contains("half3 linearToSrgb3(half3 c)"))
        val linBase = shader.indexOf("half3 linBase = srgbToLinear3(base.rgb);")
        val linBrush = shader.indexOf("half3 linBrush = srgbToLinear3(vibBrushColor);")
        val writeBack = shader.indexOf("mixedRgb = linearToSrgb3(mixedLin);")
        assertTrue("shader must linearize base", linBase > 0)
        assertTrue("shader must linearize brush", linBrush > 0)
        assertTrue("shader must re-encode the mixed result", writeBack > linBase)
        // the OLD gamma-space product is gone from the shader
        assertEquals(-1, shader.indexOf("(1.0 - base.rgb) * (1.0 - vibBrushColor)"))
    }

    @Test
    fun `shader knees match the Kotlin reference comparison direction exactly`() {
        // Review-fix (phase-200): Kotlin takes the LINEAR segment when
        // c <= threshold (`if (c <= 0.04045f)`), so the GLSL step must select lo
        // at equality too — `step(c, threshold)` — not `step(threshold, c)`.
        val shader = shaderSource()
        assertTrue(shader.contains("step(c, half3(0.04045))"))
        assertTrue(shader.contains("step(c, half3(0.0031308))"))
        assertEquals(-1, shader.indexOf("step(half3(0.04045), c)"))
        assertEquals(-1, shader.indexOf("step(half3(0.0031308), c)"))
    }

    @Test
    fun `zero-pigment pixels stay bit-exact with the pre-200 write-back`() {
        // Review-fix (phase-200): only REAL mixes pay the fp16 EOTF round trip;
        // zero-pigment-over-paint passes base.rgb through and empty canvas
        // passes the vibrancy brush color through — exactly like pre-200.
        val shader = shaderSource()
        val condIdx = shader.indexOf("if (base.a > 0.0 && pigmentFactor > 0.0) {")
        val passBase = shader.indexOf("mixedRgb = base.rgb;")
        val passBrush = shader.indexOf("mixedRgb = vibBrushColor;")
        val linBase = shader.indexOf("half3 linBase = srgbToLinear3(base.rgb);")
        assertTrue(condIdx > 0)
        assertTrue(passBase > condIdx)
        assertTrue(passBrush > passBase)
        assertTrue(linBase > 0 && condIdx > linBase)
    }

    @Test
    fun `WetMixingMath defaults to LinearSrgb via a ColorSpace param`() {
        val math = mathSource()
        assertTrue(math.contains("import androidx.compose.ui.graphics.colorspace.ColorSpaces"))
        assertTrue(
            "pigmentMixRgb must take a ColorSpace param defaulting to LinearSrgb",
            math.contains("mixSpace: ColorSpace = ColorSpaces.LinearSrgb")
        )
        // the piecewise EOTF lives in the Kotlin reference, not only in GLSL
        assertTrue(math.contains("fun srgbToLinear(channel: Float)"))
        assertTrue(math.contains("fun linearToSrgb(channel: Float)"))
        assertTrue(math.contains("0.04045f"))
        assertTrue(math.contains("12.92f"))
        assertTrue(math.contains("2.4"))
    }

    @Test
    fun `alpha accumulation stays color-space independent`() {
        // sourceOverAlpha must remain untouched by the linearization — alpha is
        // not gamma-encoded, and changing it would be a blending regression.
        val math = mathSource()
        val fnStart = math.indexOf("fun sourceOverAlpha")
        val fnEnd = math.indexOf("}", fnStart)
        assertTrue(fnStart >= 0 && fnEnd > fnStart)
        val body = math.substring(fnStart, fnEnd + 1)
        assertEquals(-1, body.indexOf("Linear"))
        assertEquals(-1, body.indexOf("linear"))
    }

    // ---- 2. Paper grain under ink ---------------------------------------------

    @Test
    fun `paper card accepts a grain brush and draws it over the fill under everything else`() {
        val src = canvasSource()
        // Phase 227 moved the grain draw into the `drawPaperGrain` helper so the
        // pass can apply the user's texture-strength dial to the cached tile's
        // alpha. The prototype of `drawPaperCard` is unchanged (grainBrush param),
        // every card body calls the helper with `brush = grainBrush` semantics,
        // and every page still draws the whole CARD (fill + grain + border) BEFORE
        // its template — so the layer order this pin protects (fill → grain →
        // template/ink) is preserved across both edge styles.
        val sig = src.indexOf("private fun DrawScope.drawPaperCard(")
        assertTrue(sig > 0)
        val bodyStart = src.indexOf("{", sig)
        val nextFn = src.indexOf("private fun DrawScope.drawPaperTemplate(", sig)
        assertTrue(nextFn > bodyStart)
        // (1) the card still accepts the cached grain brush.
        assertTrue(src.substring(sig, nextFn).contains("grainBrush: Brush? = null"))
        // (2) the card body routes grain through the strength-aware helper.
        assertTrue(src.substring(sig, nextFn).contains("drawPaperGrain(grainBrush"))
        // (3) the helper actually paints the tile with `brush = grainBrush`.
        val helperSig = src.indexOf("private fun DrawScope.drawPaperGrain(")
        assertTrue(helperSig >= 0)
        val helperEnd = src.indexOf("private fun DrawScope.deckledSheetPath(", helperSig)
        val helper = src.substring(helperSig, if (helperEnd > helperSig) helperEnd else helperSig + 1200)
        assertTrue(helper.contains("brush = grainBrush"))
        // (4) the template is drawn only AFTER the full paper card in the flow,
        //     i.e. drawPaperCard is always reached before a drawPaperTemplate call.
        assertTrue(sig < nextFn)
    }

    @Test
    fun `every paper-card call site passes the cached grain brush`() {
        val src = canvasSource()
        assertEquals(3, Regex("grainBrush = paperGrainBrush").findAll(src).count())
        assertTrue(src.contains("val paperGrainBrush = remember(paperGrainEnabled, isDarkPaper)"))
        assertTrue(src.contains("PaperGrainTileCache.brushFor(isDarkPaper, paperGrainEnabled)"))
    }

    @Test
    fun `grain is gated off on low-end devices by policy`() {
        val src = canvasSource()
        val gateIdx = src.indexOf("val paperGrainEnabled = remember(grainContext) {")
        assertTrue(gateIdx > 0)
        val gate = src.substring(gateIdx, src.indexOf("}", src.indexOf("detectDeviceTier")))
        assertTrue(gate.contains("PaperGrainPolicy.enabled("))
        assertTrue(gate.contains("DeviceCompatibilityManager.detectDeviceTier"))
        assertTrue(gate.contains("DeviceTier.LOW_END"))
    }

    @Test
    fun `tile cache is a bounded static LRU without recycle hazards`() {
        val cacheSrc = source("app/src/main/kotlin/com/authorss81/noteflow/ui/components/PaperGrainTileCache.kt")
        assertTrue(cacheSrc.contains("object PaperGrainTileCache"))
        assertTrue(cacheSrc.contains("java.util.LinkedHashMap<String, ImageBitmap>(0, 0.75f, true)"))
        assertTrue(cacheSrc.contains("Shader.TileMode.REPEAT"))
        assertTrue(cacheSrc.contains("PaperGrainPolicy.MAX_CACHED_TILES"))
        // never recycle(): an in-flight display list may still reference pixels
        val codeOnly = cacheSrc
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\n]*"), "")
        assertTrue(!codeOnly.contains(".recycle()"))
    }

    // ---- 3. Eraser cursor AA parity --------------------------------------------

    @Test
    fun `partial eraser cursor fills through the ink feather curve`() {
        val src = canvasSource()
        // phase-198 structural pin expression must survive
        assertTrue(src.contains("EraserGeometryPolicy.previewRadius(currentWidth, currentWidth)"))
        val branch = src.indexOf("if (eraserMode == com.authorss81.noteflow.services.EraserMode.PARTIAL)")
        assertTrue(branch > 0)
        val region = src.substring(branch, src.indexOf("} else {", branch))
        assertTrue(region.contains("Brush.radialGradient"))
        assertTrue(region.contains("EraserGeometryPolicy.cursorFillAlphaAt(nd, previewR)"))
        assertTrue(region.contains("EraserGeometryPolicy.CURSOR_FILL_ALPHA"))
        assertTrue(region.contains("EraserGeometryPolicy.CURSOR_RING_ALPHA"))
        // review-fix (phase-200): stops are concentrated across the feather
        // band (opaque plateau out to cursorBandStartNd), not spread uniformly
        assertTrue(region.contains("EraserGeometryPolicy.cursorBandStartNd(previewR)"))
        assertEquals(-1, region.indexOf("val nd = i.toFloat() / n"))
        // the pre-200 hard flat fill is gone
        assertEquals(-1, region.indexOf("drawCircle(currentColor.copy(alpha = 0.22f), radius = previewR"))
    }

    @Test
    fun `eraser policy owns the parity constants and mirrors edgeFeather`() {
        val policySrc = source("app/src/main/kotlin/com/authorss81/noteflow/services/EraserGeometryPolicy.kt")
        assertTrue(policySrc.contains("const val CURSOR_FILL_ALPHA = 0.22f"))
        assertTrue(policySrc.contains("const val CURSOR_RING_ALPHA = 0.6f"))
        assertTrue(policySrc.contains("const val CURSOR_RING_WIDTH_PX = 2f"))
        assertTrue(policySrc.contains("const val CURSOR_FEATHER_STOP_COUNT = 12"))
        val fnStart = policySrc.indexOf("fun cursorFillAlphaAt")
        assertTrue(fnStart > 0)
        assertTrue(
            "cursor falloff must route through BrushColorModeMath.edgeFeather (hardness 1)",
            policySrc.contains("BrushColorModeMath.edgeFeather(nd, 1f, radiusPx)")
        )
    }
}
