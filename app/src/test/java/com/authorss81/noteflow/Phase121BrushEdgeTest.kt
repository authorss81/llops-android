package com.authorss81.noteflow

import com.authorss81.noteflow.data.model.StrokeTool
import com.authorss81.noteflow.services.BrushEdgePolicy
import com.authorss81.noteflow.services.BrushEdgePolicy.LineCap
import com.authorss81.noteflow.services.BrushEdgePolicy.LineJoin
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Phase 121 — rounder, smoother non-pen brush edges.
 *
 * What this proves on the pure JVM:
 *  1. The edge-geometry decision table in [BrushEdgePolicy] says EVERY freehand
 *     tool renders round caps/joins except the deliberately-flat palette knife
 *     (documented character, docs/brush-styles.md style 10) — so any future
 *     sharp-edge preview or render is a policy violation, caught here.
 *  2. The actual stroke pipeline agrees with the policy: source pins read the
 *     render files and assert there is NO hard-coded non-round cap/join on any
 *     freehand brush path (AnnotationCanvas `drawSingleStroke` branches use
 *     StrokeCap.Round / StrokeJoin.Round) and the texture engine's shared path
 *     is Paint.Cap.ROUND / Paint.Join.ROUND.
 *  3. The only intentional non-round site in the whole paint/render pipeline is
 *     the palette knife smear (`BrushTextureEngine` Paint.Cap.SQUARE +
 *     Paint.Join.BEVEL), exactly once, matching policy + docs.
 *  4. The palette previews (`PenNibVisualPreview`) derive their cap from
 *     [BrushEdgePolicy] instead of hard-coding StrokeCap.Square, so a sharp
 *     preview can never silently diverge from a round stroke again.
 */
class Phase121BrushEdgeTest {

    // ---------- policy table: cap/join roundness per tool ----------

    private val freehandTools = listOf(
        StrokeTool.PEN,
        StrokeTool.FOUNTAIN_PEN,
        StrokeTool.PENCIL,
        StrokeTool.AIRBRUSH,
        StrokeTool.MARKER,
        StrokeTool.HIGHLIGHTER,
        StrokeTool.CALLIGRAPHIC,
        StrokeTool.DOTTED,
        StrokeTool.NEON,
        StrokeTool.FINELINER,
        StrokeTool.CHISEL_MARKER,
        StrokeTool.LASER,
        StrokeTool.WATERCOLOR,
        StrokeTool.OIL_PAINT,
        StrokeTool.SMUDGE,
        StrokeTool.SPLATTER,
        StrokeTool.CHARCOAL,
        StrokeTool.OIL_PASTEL,
        StrokeTool.INK_WASH,
        StrokeTool.GOUACHE,
        StrokeTool.DRY_BRUSH,
        StrokeTool.PALETTE_KNIFE
    )

    @Test
    fun `every freehand tool has a policy entry with no sharp edges except the documented flat knife`() {
        for (tool in freehandTools) {
            val style = BrushEdgePolicy.edgeStyleFor(tool)
            val msg = "tool=$tool style=$style"
            if (tool == StrokeTool.PALETTE_KNIFE) {
                assertEquals("$msg : palette knife is the documented flat smear", LineCap.SQUARE, style.cap)
                assertEquals("$msg : palette knife smear uses bevel joins", LineJoin.BEVEL, style.join)
            } else {
                assertEquals("$msg : $tool renders with round caps", LineCap.ROUND, style.cap)
                assertEquals("$msg : $tool renders with round joins", LineJoin.ROUND, style.join)
            }
        }
    }

    @Test
    fun `velocity-taper tools are flagged smooth-width, constant-width tools are not`() {
        for (tool in freehandTools) {
            val smooth = BrushEdgePolicy.usesSmoothWidthTransitions(tool)
            if (tool == StrokeTool.PEN || tool == StrokeTool.FOUNTAIN_PEN ||
                tool == StrokeTool.FINELINER || tool == StrokeTool.CALLIGRAPHIC ||
                tool == StrokeTool.CHISEL_MARKER
            ) {
                assertTrue("$tool modulates width along the stroke", smooth)
            } else {
                // constant-width stamped / textured tools: single smooth width, n/a
                assertFalse("$tool has a single-width stroke", smooth)
            }
        }
    }

    @Test
    fun `rationale exists for every freehand tool`() {
        for (tool in freehandTools) {
            assertTrue(BrushEdgePolicy.rationaleFor(tool).isNotBlank())
        }
    }

    // ---------- source pins: render pipeline matches the policy ----------

private fun renderFile(name: String): String {
    // CI runs `gradle testDebugUnitTest` from the repo root; a scoped `-p app` run
    // has the working dir inside app/. Resolve either layout like the other source-pin tests.
    val candidates = listOf(
        File("app/src/main/kotlin/com/authorss81/noteflow/$name"),
        File("src/main/kotlin/com/authorss81/noteflow/$name")
    )
    return candidates.firstOrNull { it.exists() }?.readText()
        ?: error("cannot locate render file $name from ${File(".").absolutePath}")
}

    private val annotationCanvas = renderFile("ui/components/AnnotationCanvas.kt")
    private val textureEngine = renderFile("services/BrushTextureEngine.kt")
    private val nibPreview = renderFile("ui/components/PenNibVisualPreview.kt")

    @Test
    fun `AnnotationCanvas draws every freehand branch with round caps and joins`() {
        // drawSingleStroke spans roughly lines 2841..3639. No hard-coded square/bevel
        // may appear in the stroke render; the only legit uses of these names are in
        // this new policy-aware preview file, not the render pipeline.
        assertFalse(
            "no StrokeCap.Square in AnnotationCanvas render pipeline\n" +
                extract("StrokeCap.Square", annotationCanvas),
            annotationCanvas.contains("StrokeCap.Square")
        )
        assertFalse(
            "no StrokeJoin.Bevel in AnnotationCanvas render pipeline",
            annotationCanvas.contains("StrokeJoin.Bevel")
        )
        assertTrue(
            "PEN/HIGHLIGHTER branch pins StrokeCap.Round/StrokeJoin.Round",
            annotationCanvas.contains("cap = StrokeCap.Round, join = StrokeJoin.Round")
        )
        assertTrue(
            "textured tools route through BrushTextureEngine.round; MARKER/DOTTED/NEON/FINELINER/LASER use StrokeJoin.Round",
            annotationCanvas.lines().count { it.contains("StrokeJoin.Round") } >= 10
        )
    }

    @Test
    fun `BrushTextureEngine shared path is round and square knife smear appears exactly once`() {
        assertTrue(
            "drawTexturedStrokePath paints Paint.Cap.ROUND + Paint.Join.ROUND",
            textureEngine.contains("strokeCap = Paint.Cap.ROUND") &&
                textureEngine.contains("strokeJoin = Paint.Join.ROUND")
        )
        val squareCapCount = Regex("strokeCap = Paint\\.Cap\\.SQUARE").findAll(textureEngine).count()
        val bevelJoinCount = Regex("strokeJoin = Paint\\.Join\\.BEVEL").findAll(textureEngine).count()
        assertEquals("square cap appears exactly once (palette knife)", 1, squareCapCount)
        assertEquals("bevel join appears exactly once (palette knife)", 1, bevelJoinCount)
        // and that single site is the palette-knife smear function
        val knifeSection = textureEngine.substringAfter("fun drawPaletteKnifeStroke", "MISSING")
            .substringBefore("fun perpendicular")
        assertTrue("SQUARE/BEVEL sit inside drawPaletteKnifeStroke", knifeSection.contains("Paint.Cap.SQUARE"))
    }

    @Test
    fun `Palette previews derive their cap from the policy instead of hard-coding Square`() {
        assertTrue(
            "preview maps BrushEdgePolicy.LineCap -> StrokeCap",
            nibPreview.contains("BrushEdgePolicy.edgeStyleFor(tool).cap")
        )
        val hardCodedSquares = Regex("StrokeCap\\.Square").findAll(nibPreview).count()
        // exactly one: the policy-mapping line "(LineCap.SQUARE -> StrokeCap.Square)",
        // consumed only when the policy says a tool is flat — never a hard-coded swatch cap.
        assertEquals("only the policy mapping may mention StrokeCap.Square", 1, hardCodedSquares)
        assertTrue(
            "the policy Round->StrokeCap.Round mapping exists",
            nibPreview.contains("LineCap.ROUND -> StrokeCap.Round")
        )
        // every draw site uses the derived previewCap, never a literal
        assertTrue(nibPreview.contains("cap = previewCap"))
        assertEquals("5 draw-sites consume previewCap (fineliner, highlighter, pastel/gouache x2, knife)",
            5, Regex("cap = previewCap").findAll(nibPreview).count())
    }

    @Test
    fun `the single-square exception is referenced from docs as palette-knife character`() {
        val docsPath = listOf("docs/brush-styles.md", "../docs/brush-styles.md")
            .firstOrNull { File(it).exists() } ?: error("cannot locate docs/brush-styles.md")
        val stylesDocs = File(docsPath).readText()
        assertTrue(
            "brush-styles.md documents the flat squared knife",
            stylesDocs.contains("Flat, squared, directional")
        )
        assertTrue(
            "policy rational quotes the docs anchor",
            BrushEdgePolicy.rationaleFor(StrokeTool.PALETTE_KNIFE).contains("flat")
        )
    }

    /** Guard against silent changes: roundness count must stay honest, not drift. */
    @Test
    fun `round cap and join occurrences do not accidentally disappear during refactors`() {
        val roundCapCount = Regex("StrokeCap\\.Round").findAll(annotationCanvas).count()
        val roundJoinCount = Regex("StrokeJoin\\.Round").findAll(annotationCanvas).count()
        // every freehand branch + velocity segment path pins these explicitly
        assertTrue("round caps wired in render pipeline (got $roundCapCount)", roundCapCount >= 12)
        assertTrue("round joins wired in render pipeline (got $roundJoinCount)", roundJoinCount >= 10)
        // texture engine paints every shared textured path round
        assertEquals("exactly 1 ROUND cap paint in texture engine (drawTexturedStrokePath)", 1,
            Regex("Paint\\.Cap\\.ROUND").findAll(textureEngine).count())
    }

    @Test
    fun `velocity width interpolation is smooth and non-stepped`() {
        val intensities = listOf(1f, 0.5f)
        for (intensity in intensities) {
            var prev = com.authorss81.noteflow.services.BrushStrokeMath.velocityWidthFactor(0f, intensity)
            var step = 0.001f
            while (step < 20f) {
                val next = com.authorss81.noteflow.services.BrushStrokeMath.velocityWidthFactor(step, intensity)
                val delta = prev - next
                assertTrue("multiplier never increases with velocity (intensity=$intensity, v=$step)", delta >= -0.0001f)
                assertTrue("no step larger than the max slope (intensity=$intensity)", delta <= 0.09f * 0.001f + 0.0001f)
                prev = next
                step += 0.001f
            }
        }
        assertEquals("identity at slow threshold", 1f, com.authorss81.noteflow.services.BrushStrokeMath.velocityWidthFactor(0.5f, 1f), 1e-6f)
        assertEquals("thinnest at fast threshold", 0.55f, com.authorss81.noteflow.services.BrushStrokeMath.velocityWidthFactor(6f, 1f), 1e-6f)
    }

    private fun extract(needle: String, source: String): String {
        val out = source.lineSequence()
            .mapIndexed { i, line -> Triple(i + 1, line, line.contains(needle)) }
            .filter { it.third }
            .joinToString("\n") { "${it.first}: ${it.second}" }
        return if (out.isBlank()) "(none)" else out
    }
}