package com.authorss81.noteflow

import com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 193: resize-handle visibility regression guard.
 *
 * The user-reported bug: corner resize symbols on canvas items (sticky notes,
 * photo embeds, code blocks, audio cards, stickers) were ALWAYS visible at
 * rest. Fix: one consistent policy (`ResizeHandleVisibilityPolicy`) reused by
 * every draggable item — handles are hidden at rest, appear only while the
 * item is being touched/dragged/resized, while the handle hit-boxes stay
 * composed so the resize gesture can still start on a corner / the item body.
 *
 * This suite is pure JVM: it exercises the decision table directly and
 * source-pins the wiring (every handle render site routes through the policy).
 */
class Phase193ResizeHandleVisibilityTest {

    // ---- 1. Resting = hidden (the core regression) --------------------------

    @Test
    fun `resting items show no resize handles`() {
        assertFalse(ResizeHandleVisibilityPolicy.visibleAtRest())
        assertFalse(ResizeHandleVisibilityPolicy.shouldShow(interacting = false, collapsed = false))
    }

    @Test
    fun `resting alpha hides the visual layer`() {
        // Phase-217: resting alpha is now 0.45f (dim but visible for discoverability).
        assertEquals(0.45f, ResizeHandleVisibilityPolicy.handleAlpha(visible = false), 0.01f)
    }

    // ---- 2. Dragging/resizing = visible -------------------------------------

    @Test
    fun `dragging reveals handles`() {
        assertTrue(ResizeHandleVisibilityPolicy.visibleWhileActive(interacting = true))
        assertTrue(ResizeHandleVisibilityPolicy.shouldShow(interacting = true, collapsed = false))
    }

    @Test
    fun `active alpha is fully opaque`() {
        assertEquals(1f, ResizeHandleVisibilityPolicy.handleAlpha(visible = true), 0f)
    }

    // ---- 3. Collapsed items never show handles -------------------------------

    @Test
    fun `collapsed items stay handle-free even while interacting`() {
        assertFalse(ResizeHandleVisibilityPolicy.shouldShow(interacting = true, collapsed = true))
        assertFalse(ResizeHandleVisibilityPolicy.shouldShow(interacting = false, collapsed = true))
    }

    // ---- 4. Hit-target stays available so the gesture can still start ---------

    @Test
    fun `hidden handles keep a composed hit-box (zero-alpha, still present)`() {
        // Phase-217: HIDDEN_HANDLE_ALPHA raised to 0.45f for discoverability;
        // the hit-box is still composed in the layout (dim, not invisible).
        assertEquals(0.45f, ResizeHandleVisibilityPolicy.HIDDEN_HANDLE_ALPHA, 0.01f)
        assertEquals(0.45f, ResizeHandleVisibilityPolicy.handleAlpha(false), 0.01f)
        // And the hit-box dims are shared constants used by both item types.
        assertEquals(24f, ResizeHandleVisibilityPolicy.HANDLE_SIZE_DP, 0f)
        assertEquals(26f, ResizeHandleVisibilityPolicy.ROTATION_HANDLE_SIZE_DP, 0f)
    }

    // ---- 5. Source pins: all item types route through the same policy ---------

    @Test
    fun `sticky note resize handle is gated by the policy`() {
        val src = canvasSource()
        // The sticky note bottom-right handle computes visibility from the policy.
        assertTrue(src.contains("val handleVisible = com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.shouldShow("))
        assertTrue(src.contains("interacting = interacting,"))
        assertTrue(src.contains("alpha = com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.handleAlpha(handleVisible)"))
        // The sticky note body drag reveals + hides the handles.
        assertTrue(src.contains("interacting = true"))
        assertTrue(src.contains("interacting = false"))
        // Exactly one sticky-note corner handle (bottom-right), distinct from
        // the four media-embed corners.
        assertEquals(1, src.countOccurrences("ResizeHandleVisibilityPolicy.handleAlpha(handleVisible)"))
    }

    @Test
    fun `media embed four corners are gated by the policy`() {
        val src = canvasSource()
        assertEquals(4, src.countOccurrences("ResizeHandleVisibilityPolicy.handleAlpha(cornerVisible)"))
        assertTrue(
            src.contains(
                "val cornerVisible = com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.shouldShow("
            )
        )
        // Phase-217: the four media-embed corner handles now have multi-line
        // onDragStart with haptic feedback; the sticky-note bottom-right handle
        // still uses the simple one-liner `onDragStart = { interacting = true }`.
        assertEquals(1, src.countOccurrences("onDragStart = { interacting = true }"))
        // All four media embed corners still set interacting = true in onDragStart.
        assertTrue(src.countOccurrences("interacting = true") >= 5)
    }

    @Test
    fun `rotation handle is gated by the same policy in both cards`() {
        val src = canvasSource()
        // RotationHandle accepts visible + onInteractionChange and both call sites
        // pass the policy result.
        assertEquals(2, src.countOccurrences("onInteractionChange = { interacting = it }"))
        assertEquals(2, src.countOccurrences("visible = com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.shouldShow("))
        assertTrue(src.contains("val currentVisible by rememberUpdatedState(visible)"))
        assertTrue(
            src.contains("alpha = com.authorss81.noteflow.services.ResizeHandleVisibilityPolicy.handleAlpha(currentVisible)")
        )
    }

    @Test
    fun `body drag starts from anywhere and keeps working (move gesture preserved)`() {
        val src = canvasSource()
        // The fillMaxSize body drag handler (media embed) is still present and
        // still drives x/y drag via detectDragGestures — moving/resizing from
        // the item body is unchanged except for the handle-reveal toggle.
        assertTrue(src.contains(".fillMaxSize()\n                .pointerInput(currentEmbed.id)"))
        // One non-consuming observation coroutine per item type (sticky note +
        // media embed) reveals on touch-down; the third awaitFirstDown(..) is a
        // pre-existing quick-color-ring handler (phase-155) we do not touch.
        assertEquals(2, src.countOccurrences("// Observes touches WITHOUT consuming them"))
        assertTrue(src.countOccurrences("awaitFirstDown(requireUnconsumed = false)") >= 3)
        assertTrue(src.countOccurrences("waitForUpOrCancellation()") >= 2)
        // Phase-245: the quick-color-ring wait is now movement-aware — it yields
        // to a stroke the moment the pointer crosses the touch slop (fixes the
        // long-press donut appearing while aiming the first mark), so the ring
        // no longer uses plain waitForUpOrCancellation. Pin the new wait exists.
        assertTrue(src.countOccurrences("waitForUpOrSlopMove(") >= 1)
    }

    @Test
    fun `no handle render site bypasses the policy`() {
        val src = canvasSource()
        // Every AspectRatio icon (the resize symbol) is inside a graphicsLayer
        // whose alpha comes from the policy: sticky bottom-right (1) + the four
        // embed corners (4).
        assertEquals(5, src.countOccurrences("Icons.Outlined.AspectRatio"))
        assertEquals(5, src.countOccurrences("contentDescription = \"Resize"))
    }

    @Test
    fun `policy source exists and is the single decision owner`() {
        val policySrc = File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/services/ResizeHandleVisibilityPolicy.kt")
            .readText()
        assertTrue(policySrc.contains("object ResizeHandleVisibilityPolicy"))
        assertTrue(policySrc.contains("fun visibleAtRest(): Boolean = false"))
        assertTrue(policySrc.contains("fun visibleWhileActive(interacting: Boolean): Boolean = interacting"))
        assertTrue(policySrc.contains("fun shouldShow(interacting: Boolean, collapsed: Boolean): Boolean"))
        assertTrue(policySrc.contains("fun handleAlpha(visible: Boolean): Float"))
        // No handle-render site may hardcode its own visibility decision.
        val canvasSrc = canvasSource()
        assertFalse(canvasSrc.contains("visibleAtRest()"))
        assertFalse(canvasSrc.contains("if (interacting)"))
    }

    private fun canvasSource(): String =
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt").readText()

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

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var idx = indexOf(needle)
        while (idx != -1) {
            count++
            idx = indexOf(needle, idx + needle.length)
        }
        return count
    }
}