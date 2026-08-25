package com.authorss81.noteflow.services

/**
 * Phase 206 (PERF/BATTERY): wake schedule for the lockout countdown ticker.
 *
 * Pre-206 `NoteflowViewModel.startLockoutTicker` woke EVERY SECOND purely to
 * animate a minute-granularity countdown — up to 900 wakes for a 15-minute
 * lockout (30 for even the shortest 30 s backoff). The LockScreen renders
 * "Xm Ys" from `lockoutRemainingMs` and its unlock gate is driven by
 * [NoteflowViewModel.lockoutActive] (which reads `settings.lockoutUntilEpochMs`
 * directly, never this flow), so waking only at TRUE MINUTE BOUNDARIES of the
 * remaining time keeps every consumer honest while cutting wakes ~60x. The
 * exact-zero transition — `_lockoutRemainingMs = 0` AND
 * `settings.lockoutUntilEpochMs = 0L` — still fires exactly once at expiry.
 */
object LockoutTickerPolicy {

    /** Emission granularity: the countdown updates once per minute boundary. */
    const val MILESTONE_GRANULARITY_MS: Long = 60_000L

    /**
     * Delay until the next emission for a live lockout: aligned to the next true
     * minute boundary of [remainingMs] (e.g. remaining 4m20s wakes in 20s;
     * remaining exactly 4m00s wakes in 60s; remaining 30s wakes in 30s).
     *
     * Returns `null` when [remainingMs] <= 0 — the lockout has expired and the
     * caller performs the final zero transition instead of scheduling a wake.
     */
    fun nextWakeDelayMs(remainingMs: Long): Long? {
        if (remainingMs <= 0L) return null
        val intoMinute = remainingMs % MILESTONE_GRANULARITY_MS
        return if (intoMinute == 0L) MILESTONE_GRANULARITY_MS else intoMinute
    }
}
