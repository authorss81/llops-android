package com.authorss81.noteflow.ui.components

import android.os.Build
import android.view.Choreographer

/**
 * Phase 206 (PERF/BATTERY): event-driven owner of the wet-engine Choreographer
 * frame pump.
 *
 * Pre-206 the pump was posted from a `LaunchedEffect(Unit)` in AnnotationCanvas
 * as a SELF-REPOSTING [Choreographer.FrameCallback] that was NEVER unregistered
 * (there was no `removeFrameCallback` call-site anywhere in the repo): cancelling
 * the effect did not stop it, so EVERY editor visit stacked another immortal
 * 60-120 Hz loop doing frame-time sampling + a thermal-status service call +
 * a wet-engine tier re-evaluation PER FRAME even on a static untouched note.
 *
 * Now:
 *  - the callback is OWNED by this class and unregistered via
 *    [Choreographer.removeFrameCallback] on canvas teardown (`onDispose`);
 *  - the callback re-posts itself ONLY while [active] is set — i.e. while a
 *    stroke is actively being drawn ([start] on drag-start, [stop] on
 *    drag-end/cancel) — so an open, idle editor costs ZERO frame wakes;
 *  - the thermal status service call + tier re-evaluation run at most once per
 *    [THERMAL_SAMPLE_INTERVAL_MS] instead of once per frame.
 *
 * All methods must be called from the main thread (Choreographer requirement);
 * [active] is an AtomicBoolean only so the doFrame gate needs no lock against
 * gesture-handler writes on the same thread.
 */
class WetBrushFramePump(
    private val wetBrushEngine: com.authorss81.noteflow.services.WetBrushEngine,
    private val isAgslSupported: Boolean,
    private val manualOverrideProvider: () -> Boolean,
    private val thermalStatusProvider: () -> Int
) {

    companion object {
        /**
         * Thermal/tier re-evaluation cadence (≤1 Hz). Pre-206 this ran per FRAME;
         * the tier decision changes at human (thermal) timescales, so 1 Hz is
         * strictly sufficient.
         */
        const val THERMAL_SAMPLE_INTERVAL_MS: Long = 1_000L
    }

    /** True exactly while a stroke is being drawn — gates the self-repost. */
    val active = java.util.concurrent.atomic.AtomicBoolean(false)

    private var choreographer: Choreographer? = null
    private var lastFrameTimeNanos = 0L
    private var lastThermalSampleMs = 0L

    val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            // Phase 206: GATED re-post — when the flag drops (stroke ended or the
            // canvas was disposed) this callback runs at most once more and then
            // the loop dies instead of reposting itself forever.
            if (!active.get()) return

            if (lastFrameTimeNanos != 0L) {
                val elapsedMs = (frameTimeNanos - lastFrameTimeNanos) / 1_000_000f
                wetBrushEngine.recordFrameTime(elapsedMs)
            }
            lastFrameTimeNanos = frameTimeNanos

            val nowMs = System.currentTimeMillis()
            if (nowMs - lastThermalSampleMs >= THERMAL_SAMPLE_INTERVAL_MS) {
                lastThermalSampleMs = nowMs
                wetBrushEngine.updateTierAndFallback(
                    isAgslSupported = isAgslSupported,
                    thermalStatus = thermalStatusProvider(),
                    manualOverrideEnabled = manualOverrideProvider(),
                    currentTimeMs = nowMs
                )
            }

            choreographer?.postFrameCallback(this)
        }
    }

    /** Arms the pump for an in-progress stroke. No-op below API 33 or if already armed. */
    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (!active.compareAndSet(false, true)) return
        // Skip the stale first delta after any idle gap: a huge elapsed value
        // would poison the wet engine's frame-time EMA.
        lastFrameTimeNanos = 0L
        lastThermalSampleMs = 0L
        val choreo = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        choreo.postFrameCallback(frameCallback)
    }

    /**
     * Disarms the pump and UNREGISTERS the callback. Called on stroke end AND on
     * canvas disposal — the disposal call is the phase-206 fix for the immortal
     * leaked loop (pre-206 nothing ever called removeFrameCallback).
     */
    fun stop() {
        active.set(false)
        choreographer?.removeFrameCallback(frameCallback)
    }
}
