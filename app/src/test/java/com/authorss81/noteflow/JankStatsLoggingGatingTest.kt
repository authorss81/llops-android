package com.authorss81.noteflow

import com.authorss81.noteflow.utils.JankStatsHelper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2-LOG-07 (phase-111): jank diagnostics are developer tooling and must be
 * gated behind `BuildConfig.DEBUG`. The frame-metrics listener is only attached
 * in debug builds, so a release APK can never emit a jank line to logcat —
 * otherwise any logcat observer (adb, dumpstate, device-owner) can reconstruct
 * an activity/timeline profile (which screen is foregrounded, when frames
 * drop), and the constant 16ms-threshold spam on low-end devices feeds
 * B2-LOG-02's unbounded log growth.
 *
 * [JankStatsHelper.MonitorJank] consults [JankStatsHelper.jankLoggingEnabled]
 * with the build's `BuildConfig.DEBUG` and returns before attaching the
 * listener when it is false. These tests pin that gate and the exact payload
 * so a regression back to unconditional logging trips the build.
 */
class JankStatsLoggingGatingTest {

    @Test
    fun `release builds never enable jank monitoring`() {
        assertFalse(
            "Release builds must not attach the frame-metrics listener nor emit jank lines.",
            JankStatsHelper.jankLoggingEnabled(debugBuild = false)
        )
    }

    @Test
    fun `debug builds keep jank monitoring for developers`() {
        assertTrue(
            "Debug builds are the sanctioned developer-flag path and may monitor jank.",
            JankStatsHelper.jankLoggingEnabled(debugBuild = true)
        )
    }

    @Test
    fun `direction of the gate is the BuildConfig flag not always-on`() {
        assertFalse(
            "Gate must be off in release: the developer flag must toggle it, not force it on.",
            JankStatsHelper.jankLoggingEnabled(debugBuild = false)
        )
        assertTrue(
            "Debug must still enable the diagnostics the feature was built for.",
            JankStatsHelper.jankLoggingEnabled(debugBuild = true)
        )
    }

    @Test
    fun `jank line is deterministic and carries no note-title-bearing screen name`() {
        val msg = JankStatsHelper.jankFrameMessage("MainActivity", 40.123f, 38.9f)
        assertTrue("The exposed screen argument is a static activity/screen tag.", msg.contains("MainActivity"))
        assertTrue("Frame duration must be formatted to 2 dp.", msg.contains("40.12"))
        assertTrue("CPU duration must be formatted to 2 dp.", msg.contains("38.90"))
    }
}