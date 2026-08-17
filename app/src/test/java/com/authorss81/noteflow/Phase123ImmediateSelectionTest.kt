package com.authorss81.noteflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.floor

/**
 * Phase 123 — Immediate effect when selecting colour / layer / tool.
 *
 * The reported bug: a new colour/layer/tool selection does NOT take effect for
 * the very next stroke — the user had to switch pens (or perform another
 * gesture) first. The trace found three real deferral roots:
 *
 *  1. `AnnotationCanvas.kt:634` — the drawing `pointerInput` restart-key list
 *     contained `currentTool`/`currentColor`/`currentWidth`/… but NOT
 *     `activeLayerId` (nor `layers`). Compose only re-launches a `pointerInput`
 *     block when one of its keys changes, so after a layer switch (unlocked →
 *     unlocked) the stroke-commit closure kept CAPTURING THE PREVIOUS layer
 *     until some other key (tool/colour/width) forced a restart — exactly the
 *     "must switch pens first" symptom. Fix: add `activeLayerId` + `layers` to
 *     the key list.
 *
 *  2. `AnnotationCanvas.kt:247` — the TEXT-tool colour `textSelectedColorInt`
 *     was a keyless `remember` snapshot of the brush colour at FIRST
 *     composition, so a newly selected brush colour never reached the next text
 *     stroke. Fix: key the `remember` on `currentColor`.
 *
 *  3. `EditorScreen.kt:3004-3047` — the three HSV sliders called
 *     `onColorSelect(derivedColor)` where `derivedColor` was remembered from the
 *     PREVIOUS composition, so the applied colour always lagged one slider event
 *     behind and the final slider position never reached the stroke. Fix: each
 *     slider converts the JUST-changed channel inline.
 *
 * What is provable on the pure JVM: a faithful model of the Compose pointerInput
 * restart-key semantics (a block is relaunched iff one of its keys changes;
 * the commit closure then re-captures the live selection) — proving a selection
 * is effective for the very next stroke — plus the pre-fix reproducer (the
 * stale-layer capture healed only by a pen switch) and an HSV immediate-apply
 * model. The Android wiring is pinned at source level.
 */
class Phase123ImmediateSelectionTest {

    private val POST_FIX_KEYS = setOf("tool", "color", "width", "layer")
    private val PRE_FIX_KEYS = setOf("tool", "color", "width")

    private data class Committed(val tool: String, val color: Int, val layer: String?)

    /**
     * Pure model of the Compose `pointerInput` restart-key semantics constrained
     * to this phase's selection dimensions. The block is (re)launched only when
     * one of its KEYS changes; the stroke-commit closure captures the selection
     * values current AT LAUNCH and keeps them until the next restart.
     */
    private class GestureCommitModel(private val restartKeys: Set<String>) {
        var tool: String = "PEN"
        var color: Int = 0x111111
        var layer: String? = "layer_default"

        private var capturedTool: String = tool
        private var capturedColor: Int = color
        private var capturedLayer: String? = layer

        fun selectTool(value: String) {
            tool = value
            if ("tool" in restartKeys) relaunch()
        }

        fun selectColor(value: Int) {
            color = value
            if ("color" in restartKeys) relaunch()
        }

        fun selectLayer(value: String?) {
            layer = value
            if ("layer" in restartKeys) relaunch()
        }

        private fun relaunch() {
            capturedTool = tool
            capturedColor = color
            capturedLayer = layer
        }

        fun commit(): Committed = Committed(capturedTool, capturedColor, capturedLayer)
    }

    // ------------------------------------------------------------------
    // 1. Post-fix: every selection is effective for the very next stroke
    // ------------------------------------------------------------------

    @Test
    fun `selecting a colour is effective for the very next stroke with no intermediate action`() {
        val m = GestureCommitModel(POST_FIX_KEYS)
        val red = 0xFFFF0000.toInt()
        m.selectColor(red)
        assertEquals("the very next commit carries the new colour", Committed("PEN", red, "layer_default"), m.commit())
    }

    @Test
    fun `switching layer is effective for the very next stroke with no intermediate action`() {
        val m = GestureCommitModel(POST_FIX_KEYS)
        val layerB = "layer_B"
        m.selectLayer(layerB)
        assertEquals("the very next commit lands on the newly selected layer", layerB, m.commit().layer)
    }

    @Test
    fun `switching tool is effective for the very next stroke with no intermediate action`() {
        val m = GestureCommitModel(POST_FIX_KEYS)
        m.selectTool("MARKER")
        assertEquals("the very next commit uses the new tool", "MARKER", m.commit().tool)
    }

    @Test
    fun `a selection sequence applies each choice to its very next stroke`() {
        val m = GestureCommitModel(POST_FIX_KEYS)
        val teal = 0xFF008080.toInt()

        m.selectLayer("layer_2")
        assertEquals("layer_2", m.commit().layer)

        m.selectTool("PEN")
        assertEquals("PEN", m.commit().tool)

        m.selectColor(teal)
        assertEquals(teal, m.commit().color)

        // Change all three, then one stroke: it must carry all three latest choices.
        m.selectLayer("layer_3")
        m.selectTool("HIGHLIGHTER")
        m.selectColor(teal)
        val all = m.commit()
        assertEquals("layer_3", all.layer)
        assertEquals("HIGHLIGHTER", all.tool)
        assertEquals(teal, all.color)
    }

    // ------------------------------------------------------------------
    // 2. Pre-fix reproducer: the stale-layer capture + pen-switch heal
    // ------------------------------------------------------------------

    @Test
    fun `pre-fix missing layer key reproduces the stale-layer bug and the pen-switch heal`() {
        val m = GestureCommitModel(PRE_FIX_KEYS)
        val layerB = "layer_B"

        m.selectLayer(layerB)

        // The very next stroke still commits to the OLD layer — this is the bug.
        assertEquals(
            "without the layer key the closure keeps capturing the previous layer",
            "layer_default",
            m.commit().layer
        )

        // Switching the tool (a key) restarts the block and re-captures the live
        // layer — the reported "only works after I switch pens" symptom.
        m.selectTool("MARKER")
        assertEquals("the layer is healed only after a pen switch", layerB, m.commit().layer)
    }

    @Test
    fun `post-fix layer key makes the very next stroke land on the new layer`() {
        val m = GestureCommitModel(POST_FIX_KEYS)
        val layerB = "layer_B"
        m.selectLayer(layerB)
        assertEquals(layerB, m.commit().layer)
    }

    // ------------------------------------------------------------------
    // 3. HSV sliders: the just-moved channel is applied immediately
    // ------------------------------------------------------------------

    /** Pure-JVM reference HSV→ARGB (the classic Android algorithm, unsigned alpha). */
    private fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val hh = (((h % 360f) + 360f) % 360f) / 60f
        val i = floor(hh).toInt() % 6
        val f = hh - floor(hh)
        val p = v * (1f - s)
        val q = v * (1f - f * s)
        val t = v * (1f - (1f - f) * s)
        val (r, g, b) = when (i) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return 0xFF000000.toInt() or
            (((r * 255f).toInt() and 0xFF) shl 16) or
            (((g * 255f).toInt() and 0xFF) shl 8) or
            ((b * 255f).toInt() and 0xFF)
    }

    /**
     * Model of one HSV slider drag. Pre-fix the handler applied the colour
     * remembered from the previous composition (`derivedColor`); post-fix it
     * converts the just-moved channel value inline.
     */
    private class HsvSliderModel(private val applyChangedValueImmediately: Boolean, private val convert: (Float, Float, Float) -> Int) {
        var h = 60f
        var s = 0.5f
        var v = 0.75f
        var applied: Int = convert(h, s, v)
            private set

        fun dragHueTo(value: Float) {
            val previousH = h
            h = value
            applied = convert(if (applyChangedValueImmediately) h else previousH, s, v)
        }

        fun dragSatTo(value: Float) {
            val previousS = s
            s = value
            applied = convert(h, if (applyChangedValueImmediately) s else previousS, v)
        }

        fun dragValTo(value: Float) {
            val previousV = v
            v = value
            applied = convert(h, s, if (applyChangedValueImmediately) v else previousV)
        }
    }

    @Test
    fun `pre-fix hsv slider applies the previous composition's colour`() {
        val m = HsvSliderModel(applyChangedValueImmediately = false, convert = ::hsvToArgb)
        m.dragHueTo(120f)
        assertEquals("pre-fix the applied colour still derives from the OLD hue", hsvToArgb(60f, 0.5f, 0.75f), m.applied)
    }

    @Test
    fun `post-fix hsv slider applies the just-moved channel immediately`() {
        val m = HsvSliderModel(applyChangedValueImmediately = true, convert = ::hsvToArgb)
        m.dragHueTo(120f)
        assertEquals("the very next stroke uses the slider's final position", hsvToArgb(120f, 0.5f, 0.75f), m.applied)
        m.dragSatTo(0.9f)
        assertEquals(hsvToArgb(120f, 0.9f, 0.75f), m.applied)
        m.dragValTo(0.2f)
        assertEquals(hsvToArgb(120f, 0.9f, 0.2f), m.applied)
    }

    @Test
    fun `pre-fix slider never lands on the final position across a drag sequence`() {
        val m = HsvSliderModel(applyChangedValueImmediately = false, convert = ::hsvToArgb)
        m.dragHueTo(120f)
        assertNotEquals("the final position is never applied", hsvToArgb(120f, 0.5f, 0.75f), m.applied)
        m.dragHueTo(180f)
        assertEquals("each event lands one step behind", hsvToArgb(120f, 0.5f, 0.75f), m.applied)
    }

    // ------------------------------------------------------------------
    // 4. Source-level wiring pins (pure JVM, no Compose needed)
    // ------------------------------------------------------------------

    private fun annotationCanvasSource(): String =
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt").readText()

    private fun editorScreenSource(): String =
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/screens/EditorScreen.kt").readText()

    @Test
    fun `canvas drawing pointerInput keys include the layer selection`() {
        val canvas = annotationCanvasSource()
        val pointerInputLine = canvas.lines().first {
            it.contains(".pointerInput(") && it.contains("currentTool") && it.contains("eraserMode")
        }
        assertTrue(
            "the drawing gesture block must restart when the active layer changes (was missing, the bug)",
            pointerInputLine.contains("activeLayerId")
        )
        assertTrue(
            "the drawing gesture block must restart when the layer list itself changes",
            pointerInputLine.contains("layers")
        )
        // The commit closure must still stamp strokes with the (now live) layer.
        assertTrue(canvas.contains("val actLayerId = activeLayerId"))
        assertTrue(canvas.contains("layerId = actLayerId ?: \"layer_default\""))
    }

    @Test
    fun `text tool colour follows the current brush colour`() {
        val canvas = annotationCanvasSource()
        assertTrue(
            "the text colour must re-initialise whenever the brush colour changes (was a keyless snapshot, the bug)",
            canvas.contains("var textSelectedColorInt by remember(currentColor) { mutableIntStateOf(currentColor.toArgb()) }")
        )
        assertTrue("the text commit must consume the synced colour", canvas.contains("colorInt = textSelectedColorInt"))
    }

    @Test
    fun `hsv sliders apply the just-changed channel inline, never the stale derived colour`() {
        val editor = editorScreenSource()
        val sliderRegion = editor.substringAfter("HSV Color Customization").substringBefore("Save to Custom Swatches")

        // The pre-fix `onColorSelect(derivedColor)` (previous composition) is gone
        // from the slider region; `derivedColor` may still be used by the swatch
        // save button outside this region.
        assertFalse(
            "no slider may apply the stale previous-composition colour",
            sliderRegion.contains("onColorSelect(derivedColor)")
        )

        // Each of the three sliders converts its JUST-changed channel inline.
        assertEquals(
            "exactly three inline HSV conversions must exist in the slider region",
            3,
            Regex("onColorSelect\\(Color\\(android\\.graphics\\.Color\\.HSVToColor").findAll(sliderRegion).toList().size
        )
        for (triple in listOf("floatArrayOf(it, s, v)", "floatArrayOf(h, it, v)", "floatArrayOf(h, s, it)")) {
            assertTrue("the slider must pass the changed channel first: $triple", sliderRegion.contains(triple))
        }
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile && File(dir, "app").isDirectory) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}