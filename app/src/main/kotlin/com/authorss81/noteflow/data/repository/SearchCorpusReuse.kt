package com.authorss81.noteflow.data.repository

import com.authorss81.noteflow.data.model.NotePageEntity

/**
 * Phase-207 (crypto/DB efficiency): rebuilds the decrypted search-corpus window
 * from a fresh raw (ciphertext) DAO read while REUSING the still-valid entries
 * of the previous window through the [DecryptedPageCache].
 *
 * The scaling problem: `NoteRepository` used to NULL the whole cached corpus on
 * EVERY page mutation (`updatePageBody`, rename, tag edit, ...), so the first
 * palette/search query afterwards re-ran the AES-GCM decrypt of up to
 * [com.authorss81.noteflow.services.VaultSearchPolicy.SEARCH_CORPUS_CAP] rows —
 * ~3000 GCM passes after a single keystroke save on a capped vault.
 *
 * The fix has two halves:
 *  1. LAZY invalidation — the repository no longer nulls the cache on mutation;
 *     it flips a dirty flag. A search that never happens after an edit pays
 *     nothing. (`NoteRepository.invalidateSearchCorpus` / `searchCorpusDirty`.)
 *  2. HASH REUSE on rebuild — when a query DOES arrive, this assembler walks the
 *     fresh raw window and, per row, consults the corpus cache keyed by
 *     `(pageId, sha256(title ciphertext), sha256(extracted ciphertext))`.
 *     Unchanged rows are served from the memoized plaintext; only genuinely
 *     rewritten/new rows go through [decryptOne]. Stale-body serving is
 *     structurally impossible: the key contains the ciphertext hashes, so any
 *     rewritten row misses and is re-decrypted.
 *
 * Rows whose decrypt yields null (undecryptable — dropped from the corpus by the
 * phase-88 review semantics) are NOT cached, so a later unlock/re-key naturally
 * retries them instead of pinning a permanent hole.
 *
 * Pure JVM; thread-safety comes from the cache's own synchronization plus the
 * repository's corpus lock around the committed-window swap.
 */
class SearchCorpusReuse(private val cache: DecryptedPageCache) {

    /**
     * Assembles the decrypted window for [rawWindow] (the bounded, newest-first
     * DAO read in CIPHERTEXT form). [decryptOne] decrypts a single row or
     * returns null to drop it (undecryptable). Output preserves [rawWindow]'s
     * order minus dropped rows; every output row carries its RAW row's fresh
     * metadata (updatedAt/pinned/...) with only title+extractedText replaced.
     */
    fun assemble(
        rawWindow: List<NotePageEntity>,
        decryptOne: (NotePageEntity) -> NotePageEntity?
    ): List<NotePageEntity> {
        if (rawWindow.isEmpty()) return emptyList()
        val result = ArrayList<NotePageEntity>(rawWindow.size)
        for (raw in rawWindow) {
            val titleKey = DecryptedPageCache.fieldKeyOf(raw.title)
            val extractedKey = DecryptedPageCache.fieldKeyOf(raw.extractedText)
            val cached = cache.lookup(raw.id, titleKey, extractedKey)
            if (cached != null) {
                // Same ciphertext identity → same plaintext. Fresh metadata rides
                // on the RAW row; only the two decrypted fields are substituted.
                result += raw.copy(title = cached.decryptedTitle, extractedText = cached.decryptedExtracted)
                continue
            }
            val decrypted = decryptOne(raw) ?: continue
            cache.put(raw.id, titleKey, extractedKey, decrypted.title, decrypted.extractedText)
            result += decrypted
        }
        return result
    }
}
