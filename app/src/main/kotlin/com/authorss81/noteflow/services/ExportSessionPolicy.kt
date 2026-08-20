package com.authorss81.noteflow.services

/**
 * Phase-189: the exported session's DEK pin.
 *
 * A backup export hands the vault DEK around for its whole run, and its
 * staged-snapshot prunes must open the snapshot under the SAME key the export
 * used. Re-reading the mutable [VaultKeyHolder.dek] singleton at prune time
 * couples the backup to whatever state lock()/zeroize left in memory — a lock
 * landing mid-export (e.g. the SAF destination picker backgrounding the app via
 * ON_STOP) made the export fail with the fixed-text "vault is locked" message
 * and poisoned the immediately-following backup attempt.
 *
 * [pinnedPruneDek] resolves that key ONCE, at export start, and returns a COPY
 * so a mid-export zeroization of the source array (the live DEK or
 * [VaultKeyHolder.dek]) can never null out the snapshot passphrase. The caller
 * MUST zeroize the returned copy after use ([ExportSessionPolicy.ZEROIZE] is
 * the contract helper).
 */
object ExportSessionPolicy {

    const val LOCKED_SNAPSHOT_ERROR =
        "Backup failed: the vault is locked; cannot bound the snapshot."

    const val KEEP_CHANGING_ERROR =
        "Backup failed: the vault database kept changing during the snapshot copy. Please try again."

    /**
     * Returns a copy of the pinned export DEK, or null when neither the
     * export's own key nor the (non-zeroized) holder carries one — the locked-
     * vault refusal, surfaced by the caller with [LOCKED_SNAPSHOT_ERROR].
     */
    fun pinnedPruneDek(key: ByteArray?, holderDek: () -> ByteArray?): ByteArray? {
        val held = key ?: holderDek() ?: return null
        return held.copyOf()
    }

    fun zeroize(snapshot: ByteArray?) {
        snapshot?.fill(0.toByte())
    }
}