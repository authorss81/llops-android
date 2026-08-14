package com.authorss81.noteflow.utils

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.authorss81.noteflow.BuildConfig

object JankStatsHelper {
    private const val TAG = "JankStats"
    private const val JANK_THRESHOLD_MS = 16f

    /**
     * B2-LOG-07 (phase-111): jank diagnostics are developer tooling only.
     * When `debugBuild` is false this returns false and [MonitorJank] returns
     * before attaching the frame-metrics listener, so a release APK can never
     * emit a jank line. Leaving the monitor live in release would let any
     * logcat observer (adb, dumpstate, device-owner) reconstruct an
     * activity/timeline profile — which screen is foregrounded and when frames
     * drop — and the constant 16ms-threshold spam on low-end devices feeds
     * B2-LOG-02's unbounded log growth.
     */
    fun jankLoggingEnabled(debugBuild: Boolean): Boolean = debugBuild

    /** Pure-JVM formatting so tests can pin the exact logcat payload. */
    fun jankFrameMessage(screenName: String, frameDurationMs: Float, cpuDurationMs: Float): String =
        "Jank detected on $screenName! Frame duration: ${String.format("%.2f", frameDurationMs)}ms (CPU: ${String.format("%.2f", cpuDurationMs)}ms)"

    @Composable
    fun MonitorJank(screenName: String) {
        if (!jankLoggingEnabled(BuildConfig.DEBUG)) return
        val context = LocalContext.current
        val activity = context as? Activity ?: return

        DisposableEffect(screenName) {
            val handler = Handler(Looper.getMainLooper())
            val frameMetricsListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
                    val frameDurationNs = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
                    val frameDurationMs = frameDurationNs / 1_000_000f
                    
                    // Standard threshold: 16ms. Log anything above as slow/janky.
                    if (frameDurationMs > JANK_THRESHOLD_MS) {
                        val cpuDurationNs = frameMetrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION) +
                                frameMetrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) +
                                frameMetrics.getMetric(FrameMetrics.ANIMATION_DURATION) +
                                frameMetrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) +
                                frameMetrics.getMetric(FrameMetrics.DRAW_DURATION)
                        val cpuDurationMs = cpuDurationNs / 1_000_000f
                        
                        Log.w(TAG, jankFrameMessage(screenName, frameDurationMs, cpuDurationMs))
                    }
                }
            } else null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && frameMetricsListener != null) {
                activity.window.addOnFrameMetricsAvailableListener(frameMetricsListener, handler)
            }

            onDispose {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && frameMetricsListener != null) {
                    try {
                        activity.window.removeOnFrameMetricsAvailableListener(frameMetricsListener)
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
