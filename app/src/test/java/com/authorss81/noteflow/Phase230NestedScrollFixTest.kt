package com.authorss81.noteflow

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 230 (2026-08-28): nested-scroll bounding guard.
 *
 * A composable with an unbounded `verticalScroll` (no explicit height bound)
 * measures at intrinsic content height, so the scrollState never activates and
 * the scroll is dead. The fix pattern is ALWAYS to place an explicit height
 * bound (heightIn/height) BEFORE `verticalScroll`.
 *
 * This pins the two known sites:
 *   - TutorialDemos.kt: the layer demo panel now has `.heightIn(max = 420.dp)`
 *     placed BEFORE `.verticalScroll(...)`.
 *   - EditorScreen.kt: the ColorPicker inner Column has `.heightIn(max = 430.dp)`
 *     BEFORE `.verticalScroll(...)` (the confirmed crash fixed in c972b23).
 *
 * A future edit that reorders (scroll before bound) or drops the bound fails
 * this suite.
 */
class Phase230NestedScrollFixTest {

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

    /// Asserts a `.heightIn(...)` appears textually BEFORE a `.verticalScroll(...)`
    /// within the given source slice.
    private fun assertBoundBeforeScroll(region: String, label: String) {
        val h = region.indexOf("heightIn(")
        val v = region.indexOf("verticalScroll(")
        assertTrue(
            "$label: must declare heightIn BEFORE verticalScroll. heightIn@=$h verticalScroll@=$v",
            h >= 0 && v >= 0 && h < v
        )
    }

    // --- TutorialDemos.kt -------------------------------------------

    @Test
    fun `TutorialDemos layer demo panel bounds height BEFORE scroll`() {
        val src = mainSource("ui/components/TutorialDemos.kt")

        // The "Layers" demo panel: the Column carrying the Layers header Row.
        val open = src.indexOf("text = \"Layers\"")
        assertTrue("Layers header not found", open >= 0)
        // find the enclosing Column modifier: walk back to the nearest "Column("
        // before the header and verify the whole region above it.
        val col = src.lastIndexOf("Column(", open)
        assertTrue("Column not found before Layers", col >= 0)

        val region = src.substring(col, open + "text = \"Layers\"".length)
        assertBoundBeforeScroll(region, "TutorialDemos layers demo panel")

        // The bound value must be 420.dp for the layer panel.
        assertTrue(
            "TutorialDemos layer demo panel must use heightIn(max = 420.dp)",
            src.contains("heightIn(max = 420.dp)")
        )
    }

    // --- EditorScreen.kt --------------------------------------------

    @Test
    fun `EditorScreen ColorPicker inner Column bounds height BEFORE scroll`() {
        val src = mainSource("ui/screens/EditorScreen.kt")

        // The ColorPicker scrollable column starts at the Phase 19 comment.
        val marker = "// Phase 19: scrollable, organized color picker"
        val m = src.indexOf(marker)
        assertTrue("ColorPicker marker not found", m >= 0)

        val region = src.substring(m, src.indexOf("if (advancedBrushesEnabled) {", m))
        assertBoundBeforeScroll(region, "EditorScreen ColorPicker inner Column")

        assertTrue(
            "EditorScreen ColorPicker column must use heightIn(max = 430.dp)",
            region.contains("heightIn(max = 430.dp)")
        )
    }
}
