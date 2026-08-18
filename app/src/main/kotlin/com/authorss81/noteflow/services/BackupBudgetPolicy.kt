package com.authorss81.noteflow.services

/**
 * R2-B1D-04 (phase-138): the SINGLE source of truth for the backup budget, so
 * the export packer and the restore extractor can never drift out of parity.
 *
 * Pre-fix asymmetry (the finding): `exportBackup` packed the whole vault archive
 * with NO per-entry or total budget, while `restoreFromZip`'s extractor enforced
 * 50 MB per entry and 200 MB total. A vault whose DB copy (or archive) exceeded
 * those caps could be EXPORTED successfully but could never be RESTORED — an
 * "exportable and unrestorable" backup — and the decrypt side held the encrypted
 * archive AND the decrypted zip in heap at once (~800 MB peak on a large vault).
 *
 * The fix:
 *  - the DECOMPRESSED budget is the restore-side contract: any single entry over
 *    [MAX_ENTRY_BYTES], or any archive whose total decompressed bytes exceed
 *    [MAX_TOTAL_BYTES], is rejected on EITHER side — the export packer refuses
 *    to ship it ([claimPackFile]) and the restore extractor refuses to unpack it
 *    ([claimRestoreChunk] / [claimRestoreEntry]);
 *  - [MAX_TOTAL_BYTES] equals the wire-level input cap [MAX_INPUT_BYTES], which
 *    is the strongest possible binding: AES-GCM output is exactly input + 16
 *    bytes, so any archive that passed the input cap can decompress to at most
 *    ~[MAX_INPUT_BYTES] — a restorable archive is never rejected by the total
 *    budget, and an archive that passed the input cap cannot silently decompress
 *    beyond it;
 *  - the 100x compression-ratio seal and the [MAX_ENTRY_COUNT] entry-count belt
 *    still trip a zip bomb long before the byte budgets ever matter.
 *
 * Pure JVM so the thresholds, the parity decisions and the pack accumulator are
 * all unit-testable without Android.
 */
object BackupBudgetPolicy {

    /** Wire-level input cap, unchanged since phase-83: applied BEFORE any
     *  decrypt/decompress work on every restore entry point AND mirrored by the
     *  WebDAV download cap. (Kept as its own declaration so the existing pins
     *  stay valid; [MAX_TOTAL_BYTES] is deliberately equal to it.) */
    const val MAX_INPUT_BYTES: Long = 400L * 1024 * 1024

    /** Largest single decompressed entry either side will ship/unpack. */
    const val MAX_ENTRY_BYTES: Long = 100L * 1024 * 1024

    /** Largest total decompressed archive either side will ship/unpack. Equal to
     *  [MAX_INPUT_BYTES]: GCM output is plaintext + 16, so the input cap already
     *  bounds a decryptable archive's decompressed size — any archive under the
     *  wire cap is accepted by the total budget, never "exportable-unrestorable". */
    const val MAX_TOTAL_BYTES: Long = MAX_INPUT_BYTES

    /** Largest number of entries either side will walk (a many-tiny-entries bomb
     *  belt that bounds the archive scan itself). */
    const val MAX_ENTRY_COUNT: Long = 40_000

    /** Zip-bomb expansion-ratio seal (> 100x declared against actual bytes read),
     *  floor-ignored below this many bytes so tiny legitimate entries never trip. */
    const val MAX_EXPANSION_RATIO: Long = 100L
    const val RATIO_FLOOR_BYTES: Long = 4 * 1024L

    /** A single decompressed entry over the per-entry budget (fail-closed message). */
    fun entryOverBudget(bytes: Long): Boolean = bytes > MAX_ENTRY_BYTES

    /** Cumulative decompressed bytes over the total budget. */
    fun totalOverBudget(totalBytes: Long): Boolean = totalBytes > MAX_TOTAL_BYTES

    /** More entries than the archive-scan belt allows. */
    fun countOverBudget(entryCount: Long): Boolean = entryCount > MAX_ENTRY_COUNT

    fun singleEntryLimitMessage(): String =
        "Backup entry extraction limit exceeded (max ${MAX_ENTRY_BYTES / (1024L * 1024L)}MB)."

    fun totalLimitMessage(): String =
        "Total backup extraction limit exceeded (max ${MAX_TOTAL_BYTES / (1024L * 1024L)}MB)."

    /** Compression-ratio message shared by the export packer and restore extractor. */
    fun ratioViolationMessage(): String =
        "Suspicious compression ratio detected — backup rejected (possible zip bomb)."

    /** Message for the pack side so an export failure names the offending file. */
    fun exportEntryTooLargeMessage(entryName: String): String =
        "Backup rejected: '$entryName' is too large to be restored (max ${MAX_ENTRY_BYTES / (1024L * 1024L)}MB per entry)."

    fun exportTotalTooLargeMessage(entryName: String): String =
        "Backup rejected: '$entryName' would push the vault past the restoreable size (max ${MAX_TOTAL_BYTES / (1024L * 1024L)}MB total)."

    /**
     * Running accounting for a bounded pack or unpack pass. Mutable on purpose —
     * the consumer streams entries and settles each one exactly once.
     */
    class Accounting {
        var totalBytes: Long = 0L
            private set
        var entryCount: Long = 0L
            private set

        /** Claims one entry against the count belt (throws over budget). */
        fun claimEntry() {
            entryCount++
            if (countOverBudget(entryCount)) {
                throw IllegalStateException("Backup contains more than $MAX_ENTRY_COUNT entries — possible zip bomb.")
            }
        }

        /** Registers [entryBytes] toward the total (throws over budget). */
        fun addEntryBytes(entryBytes: Long) {
            totalBytes += entryBytes
            if (totalOverBudget(totalBytes)) {
                throw IllegalStateException(totalLimitMessage())
            }
        }
    }

    /**
     * EXPORT-side parity gate: checks a single file about to be packed into the
     * archive (known length at pack time, so it is a cheap pre-copy length
     * check). Refuses a file that the restore extractor could never unpack —
     * this is what kills the "exportable but unrestorable" backup. [packTotal]
     * is the running sum of source-file lengths already packed.
     */
    fun claimPackFile(packing: Accounting, entryName: String, fileLength: Long) {
        if (entryOverBudget(fileLength)) {
            throw IllegalStateException(exportEntryTooLargeMessage(entryName))
        }
        if (totalOverBudget(packing.totalBytes + fileLength)) {
            throw IllegalStateException(exportTotalTooLargeMessage(entryName))
        }
        packing.claimEntry()
        // Len is settled AFTER the checks so a rejected file never advances the total.
        packing.addEntryBytes(fileLength)
    }

    /**
     * RESTORE-side parity gate: mirrors [claimPackFile] over ACTUAL decompressed
     * bytes read, so a forged zip header cannot bypass the budget; the ratio
     * seal uses the actual byte count exactly like the pre-fix copyWithLimit.
     * Returns the accumulated entry bytes, throwing over any budget.
     */
    fun claimRestoreChunk(accounting: Accounting, entryBytes: Long, declaredUncompressed: Long, declaredCompressed: Long) {
        if (entryOverBudget(entryBytes)) {
            throw IllegalStateException(singleEntryLimitMessage())
        }
        val ratioTriggered = when {
            declaredUncompressed > 0 && entryBytes > RATIO_FLOOR_BYTES && entryBytes > declaredUncompressed * MAX_EXPANSION_RATIO -> true
            declaredCompressed > 0 && entryBytes > RATIO_FLOOR_BYTES && entryBytes > declaredCompressed * MAX_EXPANSION_RATIO -> true
            else -> false
        }
        if (ratioTriggered) {
            throw IllegalStateException(ratioViolationMessage())
        }
        // The total is charged chunk-by-chunk so an oversized total is caught
        // BEFORE the entry finishes (mirrors restoreFromZip's earlier behaviour).
        if (totalOverBudget(accounting.totalBytes + entryBytes)) {
            throw IllegalStateException(totalLimitMessage())
        }
    }

    /** Adds an ENTIRE entry's byte count after [claimRestoreChunk] accepted it. */
    fun settleRestoreEntry(accounting: Accounting, entryBytes: Long) {
        accounting.addEntryBytes(entryBytes)
    }
}