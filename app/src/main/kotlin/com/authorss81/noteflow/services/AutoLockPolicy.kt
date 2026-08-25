package com.authorss81.noteflow.services

/**
 * B1-PLAT-4 (phase-60): pure-JVM decision table for the foreground inactivity
 * auto-lock.
 *
 * Pre-fix state (`MainActivity.kt:189-199` = the pointerInput handler in the
 * new file, `SettingsManager.kt` autoLockTimeoutSeconds default `0`):
 *  - the idle window was evaluated ONLY on the NEXT user touch once it had
 *    elapsed, so a foregrounded, unattended vault stayed readable until someone
 *    touched it again — a no-keyguard/tablet device left on a desk is the exact
 *    attack;
 *  - the factory default was `0` = OFF, so out of the box there was no bound at
 *    all.
 *
 * This object owns the factory default the wiring consumes and the two
 * decisions. Phase-60 wired a continuous 1-second idle poll in `MainActivity`;
 * **Phase 206 (PERF/BATTERY) replaced that poll** with [nextCheckDelayMs]
 * DEADLINE SCHEDULING — one wake per idle window, re-computed from the latest
 * activity stamp on every wake, and NO timer at all when auto-lock is off. The
 * lock still fires AT the deadline without needing another touch (the phase-60
 * security posture is unchanged); only the perpetual 3600-wakes-per-hour
 * polling loop is gone.
 */
object AutoLockPolicy {

    /** Factory default: 5 minutes of foreground inactivity. 0 = disabled ("Off"). */
    const val DEFAULT_AUTO_LOCK_TIMEOUT_SECONDS: Int = 300

    /**
     * Lock decision for a foreground inactivity check.
     *
     * [timeoutSeconds] <= 0 means "off" and never locks. Otherwise the vault
     * locks once the elapsed time since [lastActivityAtMs] has reached the
     * timeout (>=, so a timer that wakes late still fires).
     */
    fun shouldAutoLock(nowMs: Long, lastActivityAtMs: Long, timeoutSeconds: Int): Boolean {
        if (timeoutSeconds <= 0) return false
        return nowMs - lastActivityAtMs >= timeoutSeconds * 1000L
    }

    /**
     * Phase 206 (PERF/BATTERY): event-driven scheduling for the idle check.
     *
     * Returns the delay until the NEXT check, aligned EXACTLY to the moment the
     * idle window elapses — one wake per idle window instead of a fixed-interval
     * poll — or `null` when auto-lock is disabled ([timeoutSeconds] <= 0), which
     * means "do not arm any timer at all". A deadline already reached yields `0`
     * (check immediately). The caller recomputes this from the LATEST
     * [lastActivityAtMs] stamp on every wake, so user activity naturally pushes
     * the deadline out with zero extra wakes.
     */
    fun nextCheckDelayMs(nowMs: Long, lastActivityAtMs: Long, timeoutSeconds: Int): Long? {
        if (timeoutSeconds <= 0) return null
        val deadlineMs = lastActivityAtMs + timeoutSeconds * 1000L
        return (deadlineMs - nowMs).coerceAtLeast(0L)
    }
}