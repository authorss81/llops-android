package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 205: Canvas Commit Integrity.
 *
 * Three verified canvas-pipeline defects are pinned shut at source level
 * (behavior models for #1's ordering/no-resurrection live in
 * [com.authorss81.noteflow.services.CanvasCommitListPolicyTest] and for #2's
 * wave batching in [com.authorss81.noteflow.services.LaserTrailPolicyTest]):
 *
 *  1. STROKE COMMIT RACES — the pre-205 commit launched on Dispatchers.Default
 *     then hopped Main inside rememberCoroutineScope. Editor disposal before
 *     the hop silently DROPPED the finished stroke; two fast strokes could land
 *     out of order; and the emit rebuilt its payload from the `strokes`
 *     captured at drag-end, resurrecting erased strokes. Post-205 the commit is
 *     synchronous on Main and derives other-page strokes from
 *     currentStrokesProvider() AT APPLY TIME via CanvasCommitListPolicy.
 *
 *  2. LASER FADE — the pre-205 expiry path was a 25 Hz `delay(40)` poll that
 *     pushed a full-list undo entry + armed a Room write per expired stroke.
 *     Post-205 the fade is render-side (one frame clock while trails exist,
 *     draw-phase subscription) and removal is ONE batched ephemeral wave per
 *     fade (no undo push, no redo clear, one autosave arm).
 *
 *  3. AGSL WET-MIX CARRIER — the pre-201 reflective
 *     `Paint::class.java.getMethod("setRenderEffect")` catch-all failed
 *     silently every frame (Paint never had that method). Phase-201 replaced it
 *     with a direct RenderNode.setRenderEffect call behind explicit capability
 *     gates; this phase pins that NO reflective setRenderEffect lookup ever
 *     comes back repo-wide.
 */
class Phase205CanvasCommitIntegrityTest {

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

    // ---- 1. Stroke commits are synchronous and derive from CURRENT state ------

    @Test
    fun `the stroke commit no longer launches async or hops dispatchers`() {
        val src = canvas()
        assertEquals(
            "Dispatchers.Default must be gone from AnnotationCanvas — the commit runs inline on Main",
            0,
            count(src, "Dispatchers.Default")
        )
        assertEquals(
            "withContext(Dispatchers.Main) hop must be gone — nothing is pending after pen-up",
            0,
            count(src, "withContext(kotlinx.coroutines.Dispatchers.Main)")
        )
        val dragEndRegion = src
            .substringAfter("onDragEnd = {")
            .substringBefore("onDragCancel = {")
        assertTrue(
            "the drag-end handler must emit through CanvasCommitListPolicy synchronously",
            dragEndRegion.contains("com.authorss81.noteflow.services.CanvasCommitListPolicy.emittedList")
        )
        assertFalse(
            "the drag-end handler must not launch a coroutine around the commit",
            dragEndRegion.contains("coroutineScope.launch")
        )
    }

    @Test
    fun `every full-list stroke emission derives other pages from the apply-time provider`() {
        val src = canvas()
        assertEquals(
            "the frozen-capture derivation must be gone everywhere (commit, erase, text dialog)",
            0,
            count(src, "strokes.filter { it.pdfPage != pdfPageFilter }")
        )
        val emissionCount = count(src, "CanvasCommitListPolicy.emittedList")
        assertTrue(
            "expected >=3 emission sites routed through CanvasCommitListPolicy (commit/erase/text), found $emissionCount",
            emissionCount >= 3
        )
        val providerReads = count(src, "currentStrokesProvider()")
        assertTrue(
            "the provider must actually be read at the emission sites (+ laser loop), found $providerReads",
            providerReads >= 4
        )
    }

    @Test
    fun `EditorScreen hands the canvas its live strokes state as the provider`() {
        val src = editorScreen()
        assertTrue(
            "EditorScreen must pass currentStrokesProvider = { strokes } so apply-time reads see committed state",
            src.contains("currentStrokesProvider = { strokes }")
        )
    }

    // ---- 2. Laser fades: render-side clock + one batched ephemeral wave --------

    @Test
    fun `the 25Hz laser expiry poll is gone`() {
        val src = canvas()
        assertEquals(
            "delay(40) poll must not come back",
            0,
            count(src, "delay(40")
        )
        assertEquals(
            "inline 1800f alpha math must route through LaserTrailPolicy.fadeFraction",
            0,
            count(src, "/ 1800f")
        )
        assertTrue(
            "laser removal must go through LaserTrailPolicy.stripExpired",
            src.contains("LaserTrailPolicy.stripExpired(")
        )
        assertTrue(
            "the laser branch renders through the shared fade envelope",
            src.contains("LaserTrailPolicy.fadeFraction(")
        )
    }

    @Test
    fun `laser fade animates via a frame clock subscribed in the draw phase`() {
        val src = canvas()
        val laserEffect = src.substringAfter("val currentOnLaserTrailsExpiredState")
        assertTrue(
            "the fade loop must tick on the frame clock (withFrameNanos), not delay()",
            laserEffect.contains("withFrameNanos { }")
        )
        assertTrue(
            "the main draw pass must subscribe to the fade tick (draw-phase read only)",
            src.contains("if (hasLaserStrokes) {") && src.contains("laserFadeTickState.longValue")
        )
        assertTrue(
            "expiry emissions must use the EPHEMERAL channel",
            laserEffect.contains("currentOnLaserTrailsExpiredState(wave.remaining)")
        )
    }

    @Test
    fun `laser expiry never touches undo or redo history and arms exactly one autosave`() {
        val src = editorScreen()
        val body = src
            .substringAfter("fun handleLaserTrailsExpired(newStrokes: List<Stroke>)")
            .substringBefore("fun handleLayersChange(")
        assertEquals(
            "handleLaserTrailsExpired must NOT push undo entries",
            0,
            count(body, "undoStack")
        )
        assertEquals(
            "handleLaserTrailsExpired must NOT clear redo",
            0,
            count(body, "redoStack")
        )
        assertEquals(
            "exactly ONE debounced autosave arm per fade wave",
            1,
            count(body, "triggerAutoSave(")
        )
        assertTrue(
            "the canvas must be wired to the ephemeral handler",
            src.contains("onLaserTrailsExpired = { remainingAfterWave ->")
        )
        assertEquals(
            "normal stroke edits keep pushing undo history via handleStrokesChange",
            true,
            src.substringAfter("fun handleStrokesChange(newStrokes: List<Stroke>)")
                .substringBefore("fun handleLaserTrailsExpired")
                .contains("newUndo.add(strokes)")
        )
    }

    // ---- 3. The AGSL wet-mix carrier stays direct — reflection banned ----------

    @Test
    fun `no reflective setRenderEffect lookup exists anywhere in the app sources`() {
        val root = File(repoRoot(), "app/src/main/kotlin")
        val offenders = mutableListOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            if (text.contains(".getMethod(\"setRenderEffect\"") ||
                text.contains(".getDeclaredMethod(\"setRenderEffect\"") ||
                text.contains("getMethod(\"setRenderEffect\"")
            ) {
                offenders.add(file.relativeTo(repoRoot()).path)
            }
        }
        assertTrue(
            "reflective setRenderEffect lookups must never come back (offenders: $offenders)",
            offenders.isEmpty()
        )
    }

    @Test
    fun `wet mixing rides the direct RenderNode carrier behind explicit gates`() {
        val src = canvas()
        assertTrue(
            "direct RenderNode.setRenderEffect attach must stay",
            src.contains("node.setRenderEffect(wetMixingEffect.androidEffect)")
        )
        val gpuGate = src
            .substringAfter("val canUseGpuEffect = hasEffect &&")
            .substringBefore("fun drawStrokes()")
        assertTrue(
            "the GPU carrier requires a hardware-accelerated canvas",
            gpuGate.contains("nativeCanvas.isHardwareAccelerated")
        )
        val creationGate = src
            .substringAfter("val wetMixingEffect = remember {")
            .take(300)
        assertTrue(
            "the effect object itself is created ONLY on the AGSL tier",
            creationGate.contains("ShaderCapabilityHelper.isAgslSupported") &&
                creationGate.contains("AgslShaders.WetMixingEffect()")
        )
    }
}
