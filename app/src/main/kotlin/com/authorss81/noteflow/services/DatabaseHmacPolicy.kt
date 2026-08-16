package com.authorss81.noteflow.services

import java.io.File
import javax.crypto.Mac

/**
 * B1-DB-6 (phase-87): WAL-aware streaming for the on-disk vault tamper baseline.
 *
 * The vault runs WRITE_AHEAD_LOGGING (`NoteflowDatabase.kt`
 * `JournalMode.WRITE_AHEAD_LOGGING`), so committed-but-uncheckpointed
 * transactions live in the `noteflow.sqlite-wal` frame file, NOT in the main
 * `noteflow.sqlite` file. The pre-fix baseline streamed only the main file
 * (`DatabaseSecurityHelper.computeDatabaseHmac` inline loop), so a WAL-only
 * mutation committed between two checkpoints — an added frame carrying forged
 * or altered bytes that never reached the main file — was invisible to the
 * integrity check until a checkpoint promoted it, and a naive checkpoint+verify
 * then flagged the *legitimate* replayed state as tampered (the B1-DB-6 gap).
 *
 * This pure-JVM helper deliberately makes the `-wal` file part of the
 * authenticated state: it streams the main file then, when present, its `-wal`
 * companion through the SAME initialised [Mac], so the baseline covers both.
 * Because every baseline-arming site checkpoints the WAL first or reads a
 * closed raw file (the export/migration/restore stamps — see
 * `workspace/phase-87/REPORT.md` for the site-by-site audit), a freshly armed
 * baseline covers (main + empty/absent wal) and ANY post-arm WAL frame —
 * legitimate or injected — is captured: a WAL-only mutation is detected at the
 * next verification instead of silently evading the tripwire.
 *
 * Pure `java.io` + `javax.crypto` (API 26+ floor, no platform calls, no
 * fallback needed) and unit-testable on the JVM with a `SecretKeySpec`.
 */
object DatabaseHmacPolicy {

    /** The SQLite write-ahead-log companion file of [dbFile]. */
    fun walFile(dbFile: File): File = File(dbFile.path + "-wal")

    /**
     * Streams the main database file and, when present, its `-wal` companion
     * into the already-initialised [mac]. Returns the total bytes consumed;
     * `0` only when neither file could be read (an absent/empty main file).
     */
    fun streamDbAndWal(mac: Mac, dbFile: File): Long {
        var total = 0L
        val buffer = ByteArray(8192)
        val main = dbFile
        if (main.isFile) {
            main.inputStream().use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    mac.update(buffer, 0, bytesRead)
                    total += bytesRead
                }
            }
        }
        val wal = walFile(dbFile)
        if (wal.isFile) {
            wal.inputStream().use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    mac.update(buffer, 0, bytesRead)
                    total += bytesRead
                }
            }
        }
        return total
    }
}