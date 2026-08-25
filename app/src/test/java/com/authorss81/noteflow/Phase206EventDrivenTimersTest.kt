package com.authorss81.noteflow

import com.authorss81.noteflow.services.AutoLockPolicy
import com.authorss81.noteflow.services.LockoutTickerPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 206 (PERF/BATTERY): kill the four perpetual pollers.
 *
 * 1. AnnotationCanvas Choreographer frame pump — was a SELF-REPOSTING callback
 *    never unregistered on teardown (stacking one immortal 60-120 Hz loop per
 *    editor visit). Now owned by `WetBrushFramePump` (DisposableEffect +
 *    removeFrameCallback), re-posting gated on an active stroke, thermal status
 *    sampled at <=1 Hz.
 * 2. MainActivity auto-lock — was a fixed 1 s poll that kept waking even when
 *    auto-lock was OFF. Now deadline-scheduled via
 *    [AutoLockPolicy.nextCheckDelayMs] (one wake per idle window; no timer when
 *    disabled).
 * 3. VoiceNoteManager playback position — was a 50 ms (20 Hz) StateFlow poll
 *    recomposing the seek bar all session. Now a documented 200 ms cadence with
 *    drag-seek accuracy preserved via the immediate `seekTo` publish.
 * 4. NoteflowViewModel lockout ticker — was a 1 Hz wake for up to 15 minutes to
 *    animate a minute-granularity countdown. Now minute-milestone wakes via
 *    [LockoutTickerPolicy.nextWakeDelayMs] plus the exact-zero transition.
 *
 * Behavior tests are pure JVM; wiring is pinned at source level (no Robolectric
 * on this project), same technique as B1Plat04AutoLockTest.
 */
class Phase206EventDrivenTimersTest {

    // ---------- AutoLockPolicy.nextCheckDelayMs (pure JVM) ----------

    @Test
    fun `auto-lock OFF arms no timer`() {
        val now = 1_700_000_000_000L
        assertNull("0 = Off must not schedule any wake", AutoLockPolicy.nextCheckDelayMs(now, now - 10L, 0))
        assertNull("negative timeout must not schedule any wake", AutoLockPolicy.nextCheckDelayMs(now, now - 10L, -1))
        assertNull(AutoLockPolicy.nextCheckDelayMs(now, now - 10L, Int.MIN_VALUE))
    }

    @Test
    fun `next check delay is exactly the remaining idle window`() {
        val lastActivity = 1_700_000_000_000L
        assertEquals(
            "one second in, a 5-minute window wakes in exactly 299s",
            299_000L,
            AutoLockPolicy.nextCheckDelayMs(lastActivity + 1_000L, lastActivity, 300)
        )
        assertEquals(
            "halfway through the window",
            150_000L,
            AutoLockPolicy.nextCheckDelayMs(lastActivity + 150_000L, lastActivity, 300)
        )
    }

    @Test
    fun `an elapsed deadline checks immediately instead of going negative`() {
        val lastActivity = 1_700_000_000_000L
        assertEquals(0L, AutoLockPolicy.nextCheckDelayMs(lastActivity + 300_000L, lastActivity, 300))
        assertEquals(0L, AutoLockPolicy.nextCheckDelayMs(lastActivity + 999_999L, lastActivity, 300))
    }

    @Test
    fun `future activity stamps never produce a negative delay and large timeouts do not overflow`() {
        val now = 1_700_000_000_000L
        val delay = AutoLockPolicy.nextCheckDelayMs(now, now + 60_000L, 300)!!
        assertTrue(
            "a future stamp (clock rollback race) must extend, never invert, the window",
            delay >= 0L
        )
        val huge = Int.MAX_VALUE
        assertEquals(
            huge.toLong() * 1000L,
            AutoLockPolicy.nextCheckDelayMs(0L, 0L, huge)
        )
    }

    @Test
    fun `the old fixed-interval constant is gone from the policy`() {
        val source = readSource("app/src/main/kotlin/com/authorss81/noteflow/services/AutoLockPolicy.kt")
        assertFalse(
            "IDLE_CHECK_INTERVAL_MS must be deleted so no fixed poll can return",
            source.contains("IDLE_CHECK_INTERVAL_MS")
        )
    }

    // ---------- LockoutTickerPolicy.nextWakeDelayMs (pure JVM) ----------

    @Test
    fun `an expired lockout schedules nothing`() {
        assertNull(LockoutTickerPolicy.nextWakeDelayMs(0L))
        assertNull(LockoutTickerPolicy.nextWakeDelayMs(-1L))
    }

    @Test
    fun `wakes align to true minute boundaries of the remaining time`() {
        assertEquals(
            "4m20s remaining wakes in 20s",
            20_000L,
            LockoutTickerPolicy.nextWakeDelayMs(4 * 60_000L + 20_000L)
        )
        assertEquals(
            "59m59s remaining wakes in 59s",
            59_000L,
            LockoutTickerPolicy.nextWakeDelayMs(59 * 60_000L + 59_000L)
        )
        assertEquals(
            "exactly one whole minute left wakes a full minute later",
            60_000L,
            LockoutTickerPolicy.nextWakeDelayMs(60_000L)
        )
        assertEquals(
            "the shortest 30s backoff wakes once at expiry",
            30_000L,
            LockoutTickerPolicy.nextWakeDelayMs(30_000L)
        )
        assertEquals(1L, LockoutTickerPolicy.nextWakeDelayMs(1L))
    }

    @Test
    fun `a 15-minute lockout costs milestone wakes, not 900`() {
        var remaining = 15 * 60_000L
        var wakes = 0
        while (true) {
            val delayMs = LockoutTickerPolicy.nextWakeDelayMs(remaining) ?: break
            wakes++
            remaining -= delayMs
            assertTrue("the simulated countdown must never go negative", remaining >= 0L)
            assertTrue("wake count must stay bounded", wakes < 900)
        }
        assertEquals(
            "15 minute-milestone wakes, the last one performing the zero transition (pre-206: ~901 loop passes)",
            15,
            wakes
        )
        assertEquals(0L, remaining)
    }

    // ---------- Source pins: frame pump ----------

    @Test
    fun `frame pump unregisters its callback and gates the self-repost`() {
        val pump = readSource("app/src/main/kotlin/com/authorss81/noteflow/ui/components/WetBrushFramePump.kt")

        assertTrue(
            "stop() must call removeFrameCallback (the pre-206 leak: zero call-sites repo-wide)",
            pump.contains("choreographer?.removeFrameCallback(frameCallback)")
        )
        // The doFrame body's FIRST statement must be the active gate, BEFORE the
        // trailing re-post — so a disarmed pump can never repost itself.
        val doFrame = pump.substringAfter("override fun doFrame(frameTimeNanos: Long)")
            .substringBefore("choreographer?.postFrameCallback(this)")
        assertTrue(
            "doFrame must bail out when inactive (gated repost)",
            doFrame.contains("if (!active.get()) return")
        )
        assertTrue(
            "start() must no-op below API 33 (parity with the pre-206 gate)",
            pump.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU")
        )
        assertTrue(
            "thermal sampling must be throttled to <=1 Hz, not per-frame",
            pump.contains("THERMAL_SAMPLE_INTERVAL_MS") &&
                pump.contains("nowMs - lastThermalSampleMs >= THERMAL_SAMPLE_INTERVAL_MS")
        )
    }

    @Test
    fun `AnnotationCanvas owns the pump in a DisposableEffect and drives it from gestures`() {
        val canvas = readSource("app/src/main/kotlin/com/authorss81/noteflow/ui/components/AnnotationCanvas.kt")

        assertFalse(
            "the canvas must not post raw Choreographer callbacks itself anymore",
            canvas.contains("Choreographer.getInstance()")
        )
        assertTrue(
            "the pump must be created through WetBrushFramePump",
            canvas.contains("WetBrushFramePump(")
        )
        // Teardown ownership: the DisposableEffect's onDispose stops the pump.
        val teardown = canvas.substringAfter("DisposableEffect(wetFramePump)")
            .substringBefore("var showBrushStudio")
        assertTrue(
            "onDispose must unregister the frame callback",
            teardown.contains("wetFramePump.stop()")
        )
        // Event-driven arming: ink start arms it; BOTH end paths disarm it.
        assertTrue(canvas.contains("wetFramePump.start()"))
        val dragEnd = canvas.substringAfter("onDragEnd = {").substringBefore("onDragCancel = {")
        assertTrue("drag end must stop the pump first", dragEnd.contains("wetFramePump.stop()"))
        val dragCancel = canvas.substringAfter("onDragCancel = {")
        assertTrue("drag cancel must stop the pump", dragCancel.contains("wetFramePump.stop()"))
    }

    // ---------- Source pins: auto-lock scheduler ----------

    @Test
    fun `no unconditional idle-interval poll remains anywhere in the app source`() {
        val mainActivity = readSource("app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt")
        assertFalse(
            "MainActivity must not reference the deleted fixed interval",
            mainActivity.contains("IDLE_CHECK_INTERVAL_MS")
        )
        val scheduler = mainActivity.substringAfter("LaunchedEffect(autoLockTimeoutSeconds, authenticated)")
            .substringBefore("// 22.5 + B1-PLAT-2")
        assertTrue(
            "the sleep duration must come from AutoLockPolicy.nextCheckDelayMs",
            scheduler.contains("AutoLockPolicy.nextCheckDelayMs")
        )
        assertTrue(
            "auto-lock OFF must exit the scheduler without arming (null -> return)",
            scheduler.contains("?: return@LaunchedEffect")
        )
    }

    // ---------- Source pins: voice playback tick ----------

    @Test
    fun `playback position tick is a documented 200 ms or more constant`() {
        val source = readSource("app/src/main/kotlin/com/authorss81/noteflow/services/VoiceNoteManager.kt")
        assertTrue(
            "the poll must use the named constant",
            source.contains("delay(PLAYBACK_POSITION_POLL_MS)")
        )
        assertTrue(
            "the constant must be >=200 ms",
            source.contains("PLAYBACK_POSITION_POLL_MS: Long = 200L")
        )
        assertFalse(
            "the pre-206 50 ms tick must be gone",
            source.contains("delay(50)")
        )
    }

    // ---------- Source pins: lockout ticker ----------

    @Test
    fun `lockout ticker wakes at minute milestones and keeps the exact-zero clearing`() {
        val vm = readSource("app/src/main/kotlin/com/authorss81/noteflow/ui/viewmodel/NoteflowViewModel.kt")
        val ticker = vm.substringAfter("private fun startLockoutTicker()")
            .substringBefore("fun lockoutActive()", "END")

        assertTrue(
            "the ticker schedule must come from LockoutTickerPolicy",
            ticker.contains("LockoutTickerPolicy.nextWakeDelayMs(remaining)")
        )
        assertFalse(
            "the 1 Hz delay(1000) poll must be gone",
            ticker.contains("delay(1000)")
        )
        assertTrue(
            "the exact-zero clearing behavior must be preserved",
            ticker.contains("settings.lockoutUntilEpochMs = 0L")
        )
        assertTrue(
            "zero must still be published to the StateFlow before breaking",
            ticker.indexOf("_lockoutRemainingMs.value = remaining") < ticker.indexOf("settings.lockoutUntilEpochMs = 0L")
        )
    }

    // ---------- helpers ----------

    private fun readSource(relativePath: String): String {
        val file = File(repoRoot(), relativePath)
        assertTrue("$relativePath must exist", file.isFile)
        return file.readText()
    }

    private fun repoRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        var dir = cwd
        repeat(8) {
            if (File(dir, "gradle/libs.versions.toml").isFile &&
                File(dir, "app").isDirectory
            ) {
                return dir
            }
            dir = dir.parentFile ?: return cwd
        }
        return cwd
    }
}
