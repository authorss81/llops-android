package com.authorss81.noteflow

import com.authorss81.noteflow.services.AutoLockPolicy
import com.authorss81.noteflow.services.SecureWindowPolicy
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-PLAT-4 (phase-60): the vault auto-lock and screen-off boundary.
 *
 * The finding (MEDIUM, `docs/security-report.md`):
 *  - `SettingsManager.autoLockTimeoutSeconds` shipped `0` = OFF, so out of the box
 *    a foregrounded vault left unattended on a no-keyguard/tablet device stayed
 *    readable indefinitely (any shoulder-surfer / casual physical attacker);
 *  - even with a timeout configured, the inactivity lock fired only on the NEXT
 *    touch after the window elapsed (`MainActivity` pointerInput), and display-off
 *    without a keyguard can pause the activity without stopping it — so ON_STOP
 *    alone never fired and the same unlocked notes were shown on resume;
 *  - FLAG_SECURE was only applied to non-debug builds.
 *
 * Fix (phase-60):
 *  - new pure-JVM [AutoLockPolicy] owns the decision + the factory default
 *    (5 min enabled) + the poll cadence;
 *  - `SettingsManager` reads the SAME default so fresh installs are locked-by-idle;
 *  - `MainActivity` locks on the runtime `ACTION_SCREEN_OFF` broadcast (display-off
 *    is no longer dependent on pause-vs-stop semantics) and runs a continuous 1 s
 *    idle poller while unlocked instead of consulting the timeout only on touch;
 *  - `FLAG_SECURE` was applied UNCONDITIONALLY (the phase-60 carve-out removal).
 *
 * Phase-130 (user-requested UI/UX, 2026-08-18) REVERSED the unconditional
 * FLAG_SECURE per the AGENTS.md hard rule ("FLAG_SECURE is applied in
 * non-debug builds"): the flag is applied ONLY when `!BuildConfig.DEBUG`, so
 * debug / cloud-emulator streaming environments (which mirror the display
 * buffer) render the UI instead of a black surface while release builds keep
 * the screenshot / recording / recents-thumbnail ban. The pure-JVM decision
 * lives in [SecureWindowPolicy.shouldApplySecureFlag]; `MainActivity` gates
 * `window.addFlags(FLAG_SECURE)` on it fed with `BuildConfig.DEBUG`.
 *
 * Behavior below is pure JVM (the decision table). The wiring of the activity /
 * settings is pinned at source level, same technique as B2Log01CrashReportingTest /
 * B1Crypto02DekAtRestTest (no Robolectric on this project).
 */
class B1Plat04AutoLockTest {

    // ---------- AutoLockPolicy behavior (pure JVM) ----------

    @Test
    fun `factory default is an enabled 5-minute auto-lock`() {
        assertEquals(300, AutoLockPolicy.DEFAULT_AUTO_LOCK_TIMEOUT_SECONDS)
        assertTrue(
            "the shipped default must be enabled (non-zero)",
            AutoLockPolicy.DEFAULT_AUTO_LOCK_TIMEOUT_SECONDS > 0
        )
    }

    @Test
    fun `zero and negative timeouts are disabled and never lock`() {
        val now = 1_700_000_000_000L
        val longIdle = now - AutoLockPolicy.DEFAULT_AUTO_LOCK_TIMEOUT_SECONDS * 1000L * 100
        assertFalse("0 = Off must never lock (the phase-30 default)", AutoLockPolicy.shouldAutoLock(now, longIdle, 0))
        assertFalse("negative timeout must never lock", AutoLockPolicy.shouldAutoLock(now, longIdle, -1))
        assertFalse("negative timeout must never lock", AutoLockPolicy.shouldAutoLock(now, longIdle, Int.MIN_VALUE))
        assertFalse("disabled also covers an enormous idle window", AutoLockPolicy.shouldAutoLock(Long.MAX_VALUE, 0L, 0))
    }

    @Test
    fun `locks exactly when the idle window elapses and not a moment before`() {
        val lastActivity = 1_700_000_000_000L
        val timeout = 300
        // 4m59.999s of idle — still under the 5-min window.
        assertFalse(
            "under the window must not lock",
            AutoLockPolicy.shouldAutoLock(lastActivity + 300_000L - 1L, lastActivity, timeout)
        )
        // exactly 5m idle — the >= decision must fire.
        assertTrue(
            "at exactly the window the lock must fire",
            AutoLockPolicy.shouldAutoLock(lastActivity + 300_000L, lastActivity, timeout)
        )
        // comfortably past it.
        assertTrue(
            "past the window must lock",
            AutoLockPolicy.shouldAutoLock(lastActivity + 300_000L + 1_000L, lastActivity, timeout)
        )
        // a full hour of idle.
        assertTrue(
            "an unattended vault must lock",
            AutoLockPolicy.shouldAutoLock(lastActivity + 3_600_000L, lastActivity, timeout)
        )
    }

    @Test
    fun `clock noise or a future last-activity never triggers a lock`() {
        val now = 1_700_000_000_000L
        // lastActivityAtMs stamped in the future relative to now (partial clock
        // rollback while the poller reads) must be treated as NOT idle.
        assertFalse(
            "future last-activity must not lock",
            AutoLockPolicy.shouldAutoLock(now, now + 60_000L, 300)
        )
        // A just-touched baseline is not idle regardless of further activity time.
        assertFalse(
            "a milliseconds-old touch must not lock",
            AutoLockPolicy.shouldAutoLock(now, now - 200L, 300)
        )
    }

    @Test
    fun `timeout conversions are exact at the second and do not truncate`() {
        // 300 * 1000 must equal the literal so the >= second boundary is stable.
        assertEquals(300_000L, 300L * 1000L)
        assertFalse(
            "999ms of a 1-second window must not lock (no early firing)",
            AutoLockPolicy.shouldAutoLock(1_000L - 1L, 0L, timeoutSeconds = 1)
        )
        // A 1-second timeout with exactly 1 second idle fires.
        assertTrue(AutoLockPolicy.shouldAutoLock(1_000L, 0L, timeoutSeconds = 1))
    }

    @Test
    fun `large timeouts overflow nothing and stay enabled`() {
        val huge = Int.MAX_VALUE
        assertTrue(
            "a large configured timeout is still a lock decision",
            AutoLockPolicy.shouldAutoLock(0L + huge.toLong() * 1000L, 0L, huge)
        )
        assertFalse(
            "below a large timeout is not idle",
            AutoLockPolicy.shouldAutoLock(huge.toLong() * 1000L - 1L, 0L, huge)
        )
    }

    // ---------- SettingsManager wiring (source pin) ----------

    @Test
    fun `SettingsManager ships the auto-lock default ENABLED via AutoLockPolicy`() {
        val source = readSettingsManagerSource()
        val key = "auto_lock_timeout_seconds"
        assertTrue(
            "the auto-lock key must read through the shared policy default",
            source.contains("getInt(\n            \"$key\",\n            AutoLockPolicy.DEFAULT_AUTO_LOCK_TIMEOUT_SECONDS\n        )")
        )
        assertFalse(
            "the phase-30 `0 = disabled` default must be gone",
            source.contains("getInt(\"$key\", 0)")
        )
    }

    // ---------- MainActivity wiring (source pins) ----------

    @Test
    fun `FLAG_SECURE is applied only under the non-debug BuildConfig guard`() {
        val source = readMainActivitySource()

        // The addFlags application appears EXACTLY ONCE in MainActivity — it must
        // be the one inside the non-debug guard (never an unconditional one).
        assertEquals(
            "window.addFlags must appear exactly once in MainActivity.kt (the guarded one)",
            1,
            source.split("window.addFlags").size - 1
        )
        // The guard routes the pure-JVM decision fed with BuildConfig.DEBUG.
        assertTrue(
            "the addFlags must be gated on the non-debug decision (BuildConfig.DEBUG)",
            source.contains(
                "if (com.authorss81.noteflow.services.SecureWindowPolicy.shouldApplySecureFlag(BuildConfig.DEBUG))"
            )
        )
        // The addFlags call must sit INSIDE that guarded block, never outside it.
        val guardBlock = source.substringAfter("shouldApplySecureFlag(BuildConfig.DEBUG))")
        assertTrue("the guard must open a code block", guardBlock.trimStart().startsWith("{"))
        val guardedBody = guardBlock.substringAfter("{").substringBefore("}")
        assertTrue(
            "the guarded block must contain the addFlags call",
            guardedBody.contains("window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)")
        )
        // No debug clearFlags carve-out may exist (release keeps no opposite path).
        assertFalse(
            "the debug clearFlags carve-out must stay gone",
            source.contains("clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)")
        )
    }

    @Test
    fun `SecureWindowPolicy decision is debug-absent and release-present`() {
        assertTrue(
            "release (BuildConfig.DEBUG = false) must apply FLAG_SECURE",
            SecureWindowPolicy.shouldApplySecureFlag(debug = false)
        )
        assertFalse(
            "debug (BuildConfig.DEBUG = true) must NOT apply FLAG_SECURE",
            SecureWindowPolicy.shouldApplySecureFlag(debug = true)
        )
    }

    @Test
    fun `screen-off broadcast is registered and locks while authenticated`() {
        val source = readMainActivitySource()
        val receiverBody = source.substringAfter("private val screenOffReceiver")
            .substringBefore("private var screenOffReceiverRegistered")

        assertTrue("a runtime BroadcastReceiver must exist", receiverBody.contains("BroadcastReceiver"))
        assertTrue("the receiver must watch screen-off", receiverBody.contains("Intent.ACTION_SCREEN_OFF"))
        assertFalse("it must not fire on other broadcasts", receiverBody.contains("ACTION_SCREEN_ON"))
        assertTrue(
            "it must lock only while the vault is authenticated",
            receiverBody.contains("if (intent?.action == Intent.ACTION_SCREEN_OFF && viewModel.authenticated.value)")
        )
        assertTrue("on screen-off it must invoke the vault lock", receiverBody.contains("viewModel.lock()"))

        // registration + teardown live in onCreate / onDestroy.
        assertTrue("the receiver must be registered", source.contains("registerReceiver(screenOffReceiver"))
        assertTrue("the receiver must be deregistered with the activity", source.contains("unregisterReceiver(screenOffReceiver)"))
        val onDestroy = source.substringAfter("override fun onDestroy()")
        assertTrue("unregister must happen in onDestroy", onDestroy.contains("unregisterReceiver(screenOffReceiver)"))
    }

    @Test
    fun `inactivity auto-lock is deadline-scheduled, not a next-touch check`() {
        val source = readMainActivitySource()
        val scheduler = source.substringAfter("LaunchedEffect(autoLockTimeoutSeconds, authenticated)")
            .substringBefore("// 22.5 + B1-PLAT-2")

        // Phase 206: one wake per idle window (deadline scheduling), never a
        // fixed-interval poll and never gated behind the next touch.
        assertTrue("the schedule must come from the shared policy", scheduler.contains("AutoLockPolicy.nextCheckDelayMs"))
        assertTrue("the decision must route through the policy", scheduler.contains("AutoLockPolicy.shouldAutoLock"))
        assertTrue("over the window the scheduler must lock", scheduler.contains("viewModel.lock()"))
        assertTrue("the idle baseline is refreshed at each start", source.contains("lastActivityAtMs = System.currentTimeMillis()"))
        assertFalse(
            "the phase-60 1 s fixed-interval poll must be gone (phase-206)",
            source.contains("IDLE_CHECK_INTERVAL_MS")
        )
        // Auto-lock OFF must not arm any timer at all.
        assertFalse(
            "the fixed-cadence delay loop must not remain",
            Regex("""delay\(\s*1_000L\s*\)""").containsMatchIn(scheduler)
        )

        // The pointerInput touch handler must ONLY stamp lastActivityAtMs.
        val touchHandler = source.substringAfter(".pointerInput(autoLockTimeoutSeconds)")
            .substringBefore("color = MaterialTheme.colorScheme.background")
        assertTrue("the touch handler must still timestamp activity", touchHandler.contains("lastActivityAtMs = System.currentTimeMillis()"))
        assertFalse(
            "auto-lock must NOT be gated behind the next touch (the phase-30 bug)",
            touchHandler.contains("viewModel.lock()")
        )
        assertFalse(
            "the touch handler must not re-declare the timeout decision",
            touchHandler.contains("timeoutMs")
        )
    }

    // ---------- helpers ----------

    private fun readSettingsManagerSource(): String {
        val file = File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/services/SettingsManager.kt"
        )
        assertTrue("SettingsManager.kt must exist", file.isFile)
        return file.readText()
    }

    private fun readMainActivitySource(): String {
        val file = File(
            repoRoot(),
            "app/src/main/kotlin/com/authorss81/noteflow/MainActivity.kt"
        )
        assertTrue("MainActivity.kt must exist", file.isFile)
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