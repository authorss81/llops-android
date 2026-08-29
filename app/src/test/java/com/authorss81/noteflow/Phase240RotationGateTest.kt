package com.authorss81.noteflow

import com.authorss81.noteflow.services.CanvasRotationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase 240 — two-finger pinch must never rotate the page.
 *
 * Root cause: `PointerInputScope.calculateRotation()` reports the per-event
 * rotation delta in DEGREES, and even a pure radial pinch yields a small
 * non-zero delta every frame (the two fingers are never exactly equidistant
 * from the centroid between events). Pre-240 those sub-threshold deltas were
 * accumulated unconditionally, so a slow pinch eventually spun the page.
 *
 * The fix gates every 2-finger event through [CanvasRotationPolicy]:
 *   1. a 2° dead-zone suppresses micro-jitter;
 *   2. a dominant ZOOM (separation changing >3% per event) means spread/squeeze,
 *      never twist — rotation suppressed;
 *   3. a dominant PAN (centroid travelling >12px per event) means drag, never
 *      twist — rotation suppressed;
 * a genuine twist (stable separation, stationary centroid, real angular delta)
 * still clears all three gates and rotates.
 */
class Phase240RotationGateTest {

    // ---- 1. Dead-zone ---------------------------------------------------------

    @Test
    fun `pure pinch micro-jitter under the dead zone never rotates`() {
        // Sub-threshold per-event deltas — exactly the accumulating case.
        val deltas = listOf(0.4f, -0.6f, 0.9f, -0.3f, 0.7f, -0.2f)
        for (d in deltas) {
            assertEquals("jitter $d must be suppressed", 0f, CanvasRotationPolicy.gatedRotationDelta(d), 0f)
        }
    }

    @Test
    fun `dead zone is boundary inclusive`() {
        assertEquals(
            "delta exactly at the threshold counts as intentional",
            2f,
            CanvasRotationPolicy.gatedRotationDelta(2f),
            1e-5f
        )
        assertEquals(
            "negative delta at the threshold counts too",
            -2f,
            CanvasRotationPolicy.gatedRotationDelta(-2f),
            1e-5f
        )
        assertEquals(
            "just above the threshold passes",
            2.5f,
            CanvasRotationPolicy.gatedRotationDelta(2.5f),
            1e-5f
        )
    }

    @Test
    fun `non finite deltas are suppressed not accumulated`() {
        assertEquals(0f, CanvasRotationPolicy.gatedRotationDelta(Float.NaN), 0f)
        assertEquals(0f, CanvasRotationPolicy.gatedRotationDelta(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, CanvasRotationPolicy.gatedRotationDelta(Float.NEGATIVE_INFINITY), 0f)
    }

    // ---- 2. Zoom dominance (radial pinch) --------------------------------------

    @Test
    fun `radial pinch is suppress even when its rotation delta clears the dead zone`() {
        // A pinch spreads fingers: zoom deviates >3% from 1f per event, and the
        // two points can still wobble enough to report a real-ish delta.
        assertEquals(
            "deviation above 3% is a pinch, not a twist",
            0f,
            CanvasRotationPolicy.intentionalRotationDelta(
                rawDeltaDeg = 3.2f, zoomChange = 1.05f, panDistancePx = 1f
            ),
            0f
        )
        assertEquals(
            "squeezing has the same penalty",
            0f,
            CanvasRotationPolicy.intentionalRotationDelta(
                rawDeltaDeg = -3.1f, zoomChange = 0.94f, panDistancePx = 0.5f
            ),
            0f
        )
    }

    @Test
    fun `zoom close to one keeps the twist intent alive`() {
        val delta = CanvasRotationPolicy.intentionalRotationDelta(
            rawDeltaDeg = 4f, zoomChange = 1.01f, panDistancePx = 1f
        )
        assertEquals("<=3% zoom drift is finger wobble", 4f, delta, 1e-5f)
    }

    // ---- 3. Pan dominance -------------------------------------------------------

    @Test
    fun `large centroid travel is a drag not a twist`() {
        assertEquals(
            "drag past 12px suppresses rotation",
            0f,
            CanvasRotationPolicy.intentionalRotationDelta(
                rawDeltaDeg = 5f, zoomChange = 1f, panDistancePx = 60f
            ),
            0f
        )
    }

    @Test
    fun `small centroid drift coexists with a twist`() {
        val delta = CanvasRotationPolicy.intentionalRotationDelta(
            rawDeltaDeg = 5f, zoomChange = 1f, panDistancePx = 3f
        )
        assertEquals("<=12px drift is sub-threshold", 5f, delta, 1e-5f)
    }

    // ---- 4. The genuine twist ----------------------------------------------------

    @Test
    fun `real twist clears all three gates and rotates`() {
        var rotation = 0f
        // Finger separation steady (1f), centroid fixed, but angle-to-centroid
        // genuinely sweeps ~4-5° per event: the page MUST rotate.
        for (deg in listOf(4.5f, 4.1f, 4.8f, 4.3f)) {
            val delta = CanvasRotationPolicy.intentionalRotationDelta(deg, 1f, 2f)
            assertTrue("real twist must pass the gates ($deg)", delta != 0f)
            rotation = CanvasRotationPolicy.accumulate(rotation, delta)
        }
        assertTrue("accumulated twist must exceed the dead zone", rotation > 15f)
    }

    @Test
    fun `long pinch leaves the accumulated rotation untouched`() {
        var rotation = 107f
        for (d in listOf(0.5f, -0.8f, 0.4f, 0.9f, -0.6f)) {
            rotation = CanvasRotationPolicy.accumulate(
                rotation, CanvasRotationPolicy.gatedRotationDelta(d)
            )
        }
        assertEquals("page never drifts under a long pinch", 107f, rotation, 0f)
    }

    @Test
    fun `non finite zoom or pan distances suppress rotation`() {
        assertEquals(
            0f,
            CanvasRotationPolicy.intentionalRotationDelta(4f, Float.NaN, 0f),
            0f
        )
        assertEquals(
            0f,
            CanvasRotationPolicy.intentionalRotationDelta(4f, 1f, Float.POSITIVE_INFINITY),
            0f
        )
    }

    // ---- 5. Canvas wiring pins -----------------------------------------------------

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
        File(repoRoot(), "app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt").readText()

    @Test
    fun `two finger handler gates rotation through the intentional policy`() {
        val src = canvasSource()
        val v = src.indexOf("if (event.changes.size > 1)")
        assertTrue("two-finger handler must exist", v >= 0)
        val handler = src.substring(v, src.indexOf("event.changes.forEach { it.consume() }", v))
        assertTrue("handler must consult the twist setting", handler.contains("currentCanvasTwistEnabled"))
        assertTrue(
            "rotation must route through the policy gate",
            handler.contains("CanvasRotationPolicy.intentionalRotationDelta(")
        )
        assertTrue(
            "gate must see the same-event zoom",
            handler.contains("zoomChange = zoomChange")
        )
        assertTrue(
            "gate must see the same-event pan distance",
            handler.contains("panDistancePx = panChange.getDistance()")
        )
        assertTrue(
            "rotation must never apply before the gate",
            handler.contains("if (currentCanvasTwistEnabled && rotationChange != 0f)")
        )
    }
}