package com.authorss81.noteflow

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Phase-272 (pan fling with exponential decay): panning used to stop dead on
 * finger lift. PAN / black-space drags now track release velocity and keep
 * panning with exponentialDecay(0.8f) per axis; any new gesture cancels.
 *
 * Pure-JVM source pins (AnimationCanvas can't compose on the JVM) plus a
 * threshold/decay-math spot check computed without compose imports.
 */
class Phase272PanFlingTest {

    private fun canvasSource(): String {
        val candidates = listOf(
            java.io.File("app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt"),
            java.io.File("src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt")
        )
        val file = candidates.firstOrNull { it.exists() }
        assertTrue("AnnotationCanvas.kt found", file != null)
        return file!!.readText()
    }

    // ---- 1. state -----------------------------------------------------------

    @Test
    fun `fling state declared`() {
        val src = canvasSource()
        assertTrue(
            "velocityTracker state present",
            src.contains("velocityTracker") && src.contains("VelocityTracker()")
        )
        assertTrue(
            "flingJob state present",
            src.contains("flingJob") && src.contains("mutableStateOf<Job?>")
        )
    }

    // ---- 2. cancel sites ----------------------------------------------------

    @Test
    fun `fling cancelled on two-finger gesture`() {
        val src = canvasSource()
        val twoFinger = src.indexOf("if (event.changes.size > 1)")
        assertTrue("two-finger block present", twoFinger >= 0)
        val window = src.substring(twoFinger, (twoFinger + 400).coerceAtMost(src.length))
        assertTrue("two-finger cancels fling", window.contains("flingJob?.cancel()"))
    }

    @Test
    fun `drag start cancels fling and restarts tracking`() {
        val src = canvasSource()
        val start = src.indexOf("onDragStart = { offset ->")
        assertTrue("onDragStart present", start >= 0)
        val window = src.substring(start, (start + 600).coerceAtMost(src.length))
        assertTrue("drag start cancels fling", window.contains("flingJob?.cancel()"))
        assertTrue("drag start resets velocity", window.contains("velocityTracker.resetTracking()"))
    }

    @Test
    fun `drag cancel never flings`() {
        val src = canvasSource()
        val cancel = src.indexOf("onDragCancel = {")
        assertTrue("onDragCancel present", cancel >= 0)
        val window = src.substring(cancel, (cancel + 400).coerceAtMost(src.length))
        assertTrue("drag cancel cancels fling", window.contains("flingJob?.cancel()"))
    }

    // ---- 3. velocity sampling + decay wiring --------------------------------

    @Test
    fun `pan branch samples velocity`() {
        val src = canvasSource()
        assertTrue(
            "PAN branch feeds the tracker before panning",
            src.contains("velocityTracker.addPosition(change.uptimeMillis, change.position)")
        )
    }

    @Test
    fun `release fling wired with exponential decay`() {
        val src = canvasSource()
        assertTrue("release reads velocity", src.contains("velocityTracker.calculateVelocity()"))
        assertTrue("80px/s threshold", src.contains("> 80f"))
        assertTrue("exponential decay at 0.8 friction", src.contains("exponentialDecay") && src.contains("0.8f"))
        assertTrue("per-axis decay animation", src.contains("animateDecay"))
        assertTrue(
            "fling keeps panning through the shared setter",
            src.contains("flingJob = coroutineScope.launch")
        )
    }

    @Test
    fun `zoom limits untouched`() {
        val src = canvasSource()
        assertTrue("zoom clamp 0.5..4.0 intact", src.contains("coerceIn(0.5f, 4.0f)"))
    }

    // ---- 4. decay math spot-check (import-free) ------------------------------

    @Test
    fun `threshold boundary semantics`() {
        // Mirrors the release gate: abs(v) > 80px/s on either axis flings.
        fun shouldFling(vx: Float, vy: Float) = abs(vy) > 80f || abs(vx) > 80f
        assertTrue("fast diagonal flings", shouldFling(500f, -300f))
        assertTrue("fast horizontal flings", shouldFling(81f, 0f))
        assertTrue("slow release stops dead", !shouldFling(80f, 79f))
        assertTrue("idle release stops dead", !shouldFling(0f, 0f))
    }

    @Test
    fun `exponential decay settles monotonically`() {
        // Import-free replica of FloatExponentialDecay with frictionMultiplier
        // 0.8: v(t) = v0 * 0.8^t, x(t) = v0 * (1 - 0.8^t) / -ln(0.8).
        // Settles (velocity -> 0, displacement bounded) — fast scroll, slow end.
        val friction = 0.8f
        val v0 = 1000f
        fun velocityAt(t: Float) = v0 * Math.pow(friction.toDouble(), t.toDouble()).toFloat()
        fun displacementAt(t: Float) =
            (v0 * (1f - Math.pow(friction.toDouble(), t.toDouble()).toFloat()) / -Math.log(friction.toDouble()).toFloat())
        assertTrue("velocity decays", velocityAt(1f) < v0 && velocityAt(2f) < velocityAt(1f))
        assertTrue("velocity settles", velocityAt(60f) < 1f)
        val settle = displacementAt(60f)
        assertTrue("displacement bounded", settle.isFinite() && settle > 0f)
        assertTrue(
            "bulk of travel happens early (fast scroll, slow settle)",
            displacementAt(2f) / settle > 0.3f
        )
    }
}
