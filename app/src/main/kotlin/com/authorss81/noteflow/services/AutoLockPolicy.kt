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
 * This object owns the two constants the wiring consumes and the one decision.
 * The activity polls [shouldAutoLock] on [IDLE_CHECK_INTERVAL_MS]; the factory
 * default becomes [DEFAULT_AUTO_LOCK_TIMEOUT_SECONDS], so a fresh install locks
 * after 5 minutes of foreground inactivity instead of never.
 */
object AutoLockPolicy {

    /** Poll cadence for the idle check; also the resolution of the timer. */
    const val IDLE_CHECK_INTERVAL_MS: Long = 1_000L

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
}