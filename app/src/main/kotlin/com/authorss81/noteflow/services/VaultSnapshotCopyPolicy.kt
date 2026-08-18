package com.authorss81.noteflow.services

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * R2-B1D-05 / R2-B1D-03 (phase-137): checkpoint-then-copy DB snapshot discipline.
 *
 * Pre-fix, `ImportExportService.exportBackup` copied the live SQLCipher main file
 * with a plain unbounded `FileInputStream` — no checkpoint and no verification:
 *  - R2-B1D-05: a WAL auto-checkpoint (1000-page threshold) firing DURING the
 *    copy rewrote the main file mid-stream, yielding a main-file snapshot that
 *    never existed — a silently broken archive that only surfaces at restore time;
 *  - R2-B1D-03: callers that skipped the pre-export `wal_checkpoint(FULL)` shipped
 *    archives missing every committed-but-uncheckpointed frame still resident in
 *    the `-wal` (LocalSend's VAULT_BACKUP called `exportBackup` directly).
 *
 * This object is the single, unit-testable "checkpoint-then-verified-copy" path
 * every DB-file producer shares:
 *  - [checkpointThenCopy] runs the (injectable) FULL checkpoint BEFORE any source
 *    byte is read, so the snapshot starts from a state where the WAL is folded
 *    into the main file;
 *  - the copy is then VERIFIED: the source is digested, copied to [destination],
 *    the source digested again, and the destination digested. The copy is accepted
 *    only when both source reads AND the destination produce the SAME digest —
 *    proof the source held the copied state for the entire copy window, so a torn
 *    byte can never be silently shipped in an archive. A source that keeps
 *    changing (a concurrent writer auto-checkpointing) is retried up to
 *    [MAX_VERIFY_ATTEMPTS] and then FAILS CLOSED with the torn staging deleted —
 *    a backup is never quietly broken.
 *
 * Pure JVM (`java.io` + `java.security`) so the whole contract is unit-testable.
 */
object VaultSnapshotCopyPolicy {

    /** Bounded retries for a racing source; exhaustion fails the backup loudly. */
    const val MAX_VERIFY_ATTEMPTS: Int = 3

    /** Suffix of the transient staged DB snapshot; deleted once the archive is built. */
    const val SNAPSHOT_SUFFIX: String = ".sqlite-snapshot"

    /** Seam so a fake repository can be driven in pure-JVM tests. */
    fun interface DbCheckpoint {
        fun checkpointFull()
    }

    /** Seam so a test can emulate a WAL auto-checkpoint racing the copy. */
    fun interface DbCopy {
        fun copy(source: File, destination: File)
    }

    /** A staged DB snapshot file name derived from the encrypted backup's public name. */
    fun snapshotStagingFile(backupName: String): String = "$backupName$SNAPSHOT_SUFFIX"

    /** Streaming SHA-256 over [file] (bounded heap — never the whole file in one array). */
    fun sha256Digest(file: File): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { ins ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buffer)
                if (n < 0) break
                if (n > 0) md.update(buffer, 0, n)
            }
        }
        return md.digest()
    }

    /**
     * Runs [checkpoint] (when supplied) then produces a VERIFIED copy of [source]
     * at [destination]. Returns true only when the destination is byte-identical to
     * a source state that held for the whole copy window; false after
     * [maxAttempts] racing retries, with any torn staging deleted.
     */
    fun checkpointThenCopy(
        source: File,
        destination: File,
        checkpoint: DbCheckpoint? = null,
        copy: DbCopy = DbCopy { src, dst -> src.copyTo(dst) },
        maxAttempts: Int = MAX_VERIFY_ATTEMPTS
    ): Boolean {
        checkpoint?.checkpointFull()
        var attempts = 0
        while (attempts < maxAttempts) {
            attempts++
            val before = sha256Digest(source)
            copy.copy(source, destination)
            val after = sha256Digest(source)
            if (!before.contentEquals(after)) {
                // The source changed mid-copy — a torn snapshot. Discard and retry.
                destination.delete()
                continue
            }
            if (before.contentEquals(sha256Digest(destination))) {
                return true
            }
            destination.delete()
        }
        destination.delete()
        return false
    }
}
