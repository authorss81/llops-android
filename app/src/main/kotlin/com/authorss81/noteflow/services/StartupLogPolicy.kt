package com.authorss81.noteflow.services

import java.io.File

/**
 * B2-LOG-02 (phase-70): pure-JVM budget + rotation policy for the startup-event log.
 *
 * Pre-fix state (`AppStartupLogger.appendToFile`): `FileWriter(logFile, true)` — append-only,
 * no length check, no rotation, no delete — so weeks of events grew `app_startup.log`
 * unboundedly on the same partition as the encrypted vault, and pre-phase-48 raw crash dumps
 * were retained indefinitely. `PrivacyCrashReporter.writeLogToFile` (contrast) at least wiped
 * its file once it passed 500KB.
 *
 * This object owns the shared budget (the same ~500KB cap as `PrivacyCrashReporter`), the log
 * file name, and the three write-cycle operations the Android wrapper consumes:
 *  - [wouldExceedCap] — the rotate decision, taken BEFORE a line is appended so an event
 *    line is never split across a rotation boundary;
 *  - [rotateForAppend] — rotate-on-size with keep-last-N semantics (N = [MAX_LOG_FILES]):
 *    the oldest rotation is dropped and the active file moves into the single backup slot;
 *  - [pruneOnInit] — prune-on-init, clearing any leftover file that exceeds the cap (a
 *    process killed mid-rotation must not leave an over-budget file behind).
 *
 * Every operation is plain `java.io.File` work, so the whole write cycle is unit-testable on
 * the pure JVM. There are no platform APIs involved and no fallback needed on the API 26+
 * floor (AGENTS.md hardware reality).
 *
 * NOTE (the finding's dead-code clause): `AppStartupLogger` used to expose `getLogs` /
 * `clearLogs` — dead code with no caller. Those are removed by this phase. If any
 * "export/share logs" UI is ever added, the log text MUST be sanitized before it leaves the
 * device: today the file holds only timestamped EVENT lines of fixed strings, but a future
 * feature still has to route any outbound log text through the `PrivacyCrashReporter`
 * sanitizer, which this phase does not add.
 */
object StartupLogPolicy {

    /** Active log file name — the only log file `AppStartupLogger` owns. */
    const val LOG_FILE_NAME = "app_startup.log"

    /** Rotated-backup file name suffix: `app_startup.log.1` holds the previous generation. */
    const val BACKUP_SUFFIX = ".1"

    /**
     * Cap for the ACTIVE log file, byte-exact. This intentionally reuses the ~500KB budget
     * `PrivacyCrashReporter.writeLogToFile` already caps `noteflow_sanitized_crash.log` at.
     */
    const val MAX_LOG_BYTES = 500_000L

    /**
     * Keep-last-N retention: the active file plus the last [MAX_LOG_FILES] rotated backups.
     * With N = 2 the retained set is {active, backup}, so the total on-disk startup log is
     * bounded by 2 * (cap + one line), independent of how long the app runs.
     */
    const val MAX_LOG_FILES = 2

    /** The active log file inside [dir]. */
    fun activeFile(dir: File): File = File(dir, LOG_FILE_NAME)

    /** The single rotated-backup file inside [dir] (keep-last-N with N = 2 ⇒ one slot). */
    fun backupFile(dir: File): File = File(dir, LOG_FILE_NAME + BACKUP_SUFFIX)

    /**
     * Rotation decision: appending [incomingBytes] to a file currently [currentBytes] long
     * would push the ACTIVE log past [MAX_LOG_BYTES]. Called BEFORE the write. As long as no
     * single line exceeds the cap on its own, this strict check guarantees the active file
     * NEVER grows past the cap (an event line triggering the gate lands in a fresh file).
     */
    fun wouldExceedCap(currentBytes: Long, incomingBytes: Long): Boolean =
        currentBytes + incomingBytes > MAX_LOG_BYTES

    /**
     * Rotate-on-size with keep-last-N semantics: drop whatever currently occupies the backup
     * slot (the oldest retained generation), then move the active file into it. Afterwards a
     * fresh active file exists to receive the new line. Idempotent-safe when no active file
     * exists yet (first ever write).
     */
    fun rotateForAppend(dir: File) {
        val backup = backupFile(dir)
        val active = activeFile(dir)
        if (backup.exists()) backup.delete()
        if (active.exists()) active.renameTo(backup)
    }

    /**
     * Prune on init: any leftover file (active or backup) that somehow exceeds the cap is
     * dropped so retention stays bounded even when an earlier process died between rotation
     * steps. Runs on the background log executor, never the main thread.
     */
    fun pruneOnInit(dir: File) {
        val backup = backupFile(dir)
        if (backup.exists() && backup.length() > MAX_LOG_BYTES) backup.delete()
        val active = activeFile(dir)
        if (active.exists() && active.length() > MAX_LOG_BYTES) active.delete()
    }
}