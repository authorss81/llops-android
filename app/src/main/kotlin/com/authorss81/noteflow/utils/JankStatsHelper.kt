package com.authorss81.noteflow.utils

import android.app.Activity
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

object JankStatsHelper {
    private const val TAG = "JankStats"

    @Composable
    fun MonitorJank(screenName: String) {
        val context = LocalContext.current
        val activity = context as? Activity ?: return

        DisposableEffect(screenName) {
            val handler = Handler(Looper.getMainLooper())
            val frameMetricsListener = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Window.OnFrameMetricsAvailableListener { _, frameMetrics, _ ->
                    val frameDurationNs = frameMetrics.getMetric(FrameMetrics.TOTAL_DURATION)
                    val frameDurationMs = frameDurationNs / 1_000_000f
                    
                    // Standard threshold: 16ms. Log anything above as slow/janky.
                    if (frameDurationMs > 16f) {
                        val cpuDurationNs = frameMetrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION) +
                                frameMetrics.getMetric(FrameMetrics.INPUT_HANDLING_DURATION) +
                                frameMetrics.getMetric(FrameMetrics.ANIMATION_DURATION) +
                                frameMetrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION) +
                                frameMetrics.getMetric(FrameMetrics.DRAW_DURATION)
                        val cpuDurationMs = cpuDurationNs / 1_000_000f
                        
                        Log.w(TAG, "Jank detected on $screenName! Frame duration: ${String.format("%.2f", frameDurationMs)}ms (CPU: ${String.format("%.2f", cpuDurationMs)}ms)")
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
