package com.authorss81.noteflow.services

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * B1-DB-5 (phase-55): bounded accounting for attacker-controlled ZIP imports
 * (HTML zip + Obsidian vault). Mirrors the restore path's `copyWithLimit`
 * budgets (per-entry cap, total cap, declared-vs-actual expansion-ratio guard)
 * and adds a hard entry-count cap plus an archive-input size cap applied to the
 * originating `readUriBytes` stream.
 *
 * Everything here is pure JVM so the zip-bomb behavior is unit-testable without
 * Android. All rejections raise [ImportSizeLimitException] (an
 * [IllegalStateException]) so the caller can surface ONE clear, non-alarming
 * message instead of letting the malformed archive be silently skipped.
 */
object ImportArchivePolicy {

    /** Compressed archive input cap (max bytes `readUriBytes` may allocate). */
    const val MAX_IMPORT_ARCHIVE_INPUT_BYTES: Int = 200 * 1024 * 1024 // 200 MB

    /** Per-entry uncompressed cap (a single markdown/image entry). */
    const val MAX_IMPORT_ENTRY_BYTES: Long = 50L * 1024 * 1024 // 50 MB

    /** Total uncompressed cap across every entry read from one archive. */
    const val MAX_IMPORT_TOTAL_BYTES: Long = 200L * 1024 * 1024 // 200 MB

    /** Maximum entries scanned per archive (bounds `nextEntry` + object count). */
    const val MAX_IMPORT_ENTRY_COUNT: Int = 10_000

    /** Declared-size vs actual-bytes expansion-ratio trigger (defense in depth). */
    const val MAX_IMPORT_RATIO: Long = 100L

    /** Do not apply the ratio guard to entries smaller than this (tiny entries have noisy ratios). */
    const val RATIO_FLOOR_BYTES: Long = 4 * 1024L

    /**
     * Raised whenever an attacker-controlled archive exceeds a budget. An
     * [IllegalStateException] so legacy `catch (Exception)` callers still get a
     * safe failure, but catchable by its own type so import paths can surface a
     * clean message instead of silently skipping.
     */
    class ImportSizeLimitException(message: String) : IllegalStateException(message)

    /** Running accounting for one archive read (shared across all passes). */
    class Accounting constructor(
        var totalBytes: Long = 0L,
        var entryCount: Int = 0
    )

    /** True when the compressed archive input exceeds [maxInputBytes]. */
    fun inputArchiveOverLimit(archiveBytes: Int, maxInputBytes: Int = MAX_IMPORT_ARCHIVE_INPUT_BYTES): Boolean =
        archiveBytes > maxInputBytes

    /**
     * Claims one entry against the per-archive entry-count budget. Must be
     * called for EVERY entry (directories included) before any per-entry work;
     * a zip bomb built from millions of tiny entries can otherwise ANR the
     * scan itself even though no single entry is large.
     */
    fun claimEntry(accounting: Accounting, maxEntries: Int = MAX_IMPORT_ENTRY_COUNT) {
        if (accounting.entryCount >= maxEntries) {
            throw ImportSizeLimitException(
                "Import rejected: archive contains more than $maxEntries entries " +
                    "(possible zip bomb)."
            )
        }
        accounting.entryCount++
    }

    /**
     * Applies the per-entry cap and the expansion-ratio guard (both declared
     * uncompressed size and declared compressed size are cross-checked against
     * the ACTUAL bytes read, exactly like the restore path's `copyWithLimit`),
     * then the total cap against `entryBytes` (this entry's progress) plus the
     * sum of already COMPLETED entries. Throws [ImportSizeLimitException] on the
     * first violation. Does NOT mutate [accounting.totalBytes] — the caller
     * settles it once per completed entry via [settleEntryRead] (mutation here
     * would double-count the cumulative [entryBytes] across every chunk).
     */
    fun checkEntryChunk(entry: ZipEntry, entryBytes: Long, accounting: Accounting) {
        if (entryBytes > MAX_IMPORT_ENTRY_BYTES) {
            throw ImportSizeLimitException(
                "Import rejected: single file is too large (max " +
                    "${MAX_IMPORT_ENTRY_BYTES / (1024 * 1024)}MB per file)."
            )
        }
        val declaredUncompressed = entry.size
        val declaredCompressed = entry.compressedSize
        val ratioTriggered = when {
            declaredUncompressed > 0 && entryBytes > RATIO_FLOOR_BYTES &&
                entryBytes > declaredUncompressed * MAX_IMPORT_RATIO -> true
            declaredCompressed > 0 && entryBytes > RATIO_FLOOR_BYTES &&
                entryBytes > declaredCompressed * MAX_IMPORT_RATIO -> true
            else -> false
        }
        if (ratioTriggered) {
            throw ImportSizeLimitException(
                "Import rejected: suspicious compression ratio detected " +
                    "(possible zip bomb)."
            )
        }
        if (accounting.totalBytes + entryBytes > MAX_IMPORT_TOTAL_BYTES) {
            throw ImportSizeLimitException(
                "Import rejected: total archive size exceeds " +
                    "${(MAX_IMPORT_TOTAL_BYTES / (1024 * 1024)).toInt()}MB."
            )
        }
    }

    /** Records a fully read entry's uncompressed size into the shared total. */
    fun settleEntryRead(accounting: Accounting, entryBytes: Long) {
        accounting.totalBytes += entryBytes
    }

    /**
     * Reads exactly one zip entry from [zis] into memory under the per-entry /
     * total / ratio budget of [accounting] (whose [entryCount] must already
     * have been claimed via [claimEntry]). The first violation throws
     * [ImportSizeLimitException]; on completion the entry is settled into
     * [accounting.totalBytes] exactly once.
     */
    fun readEntryBounded(zis: ZipInputStream, entry: ZipEntry, accounting: Accounting): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var entryBytes = 0L
        var read: Int
        while (zis.read(buffer).also { read = it } != -1) {
            entryBytes += read
            checkEntryChunk(entry, entryBytes, accounting)
            out.write(buffer, 0, read)
        }
        settleEntryRead(accounting, entryBytes)
        return out.toByteArray()
    }
}