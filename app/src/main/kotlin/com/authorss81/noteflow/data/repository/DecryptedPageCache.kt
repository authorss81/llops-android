package com.authorss81.noteflow.data.repository

import java.security.MessageDigest

/**
 * Phase-207 (crypto/DB efficiency): a bounded memoization cache for the
 * DECRYPTED display fields (`title` + `extractedText`) of [com.authorss81.noteflow.data.model.NotePageEntity]
 * rows, keyed by `(pageId, ciphertext-hash)` so an unchanged row never pays its
 * AES-GCM decryption twice.
 *
 * The scaling problem this fixes: Room's invalidation tracker is TABLE-granular.
 * Every debounced keystroke save (`updatePageBody`) re-emits ALL FOUR page flows
 * (`getPagesForSection`, `getAllActivePagesFlow`, `getRecentPages`,
 * `getTrashedPages`) at once — collected concurrently by HomeScreen and the
 * editor host — and each emission re-decrypted title+extractedText of EVERY row
 * in the window. On a 500-row vault that is ~4000 GCM passes per autosave to
 * rediscover 4992 unchanged fields.
 *
 * Correctness contract (why the key includes the ciphertext hashes):
 *  - a row whose stored ciphertext changed (edited body/title) hash-MISMATCHES
 *    and is re-decrypted — memoization can never serve stale content;
 *  - the field keys are SHA-256 digests truncated to 128 bits over the exact
 *    stored string, so two different ciphertexts colliding into one entry is
 *    ~2^-128 per comparison — not a shortcut `hashCode` collision risk;
 *  - entries hold PLAINTEXT, so the whole cache MUST be dropped when the vault
 *    key epoch ends: `NoteRepository.clearPlaintextCaches()` calls [clear] on
 *    lock / key replacement. No decrypted content survives a lock boundary
 *    (same rule as `_pages`/`_sections` StateFlow clears in `lock()`).
 *
 * Two SEPARATE instances are used by the repository:
 *  - the DISPLAY cache (fed by [decryptPageIfNeeded]-shaped reads) whose misses
 *    record B1-DB-8 ledger failures;
 *  - the CORPUS cache (search window) whose decrypt path never records failures
 *    (phase-88 review semantics). Sharing one instance would let a corpus-first
 *    read suppress a display-side failure report and weaken the persistent
 *    corruption escalation, so they are deliberately isolated.
 *
 * Pure JVM (MessageDigest only) and thread-safe — the four page flows collect
 * concurrently on Dispatchers.Default. Synchronization is coarse but the work
 * under the lock is O(1) map bookkeeping; actual AES-GCM decryption happens OUTSIDE
 * it in the caller.
 */
class DecryptedPageCache(
    maxEntries: Int = DEFAULT_MAX_ENTRIES,
    maxTotalChars: Long = DEFAULT_MAX_TOTAL_CHARS
) {

    /** One cached row: the ciphertext identity + the plaintext it decrypts to. */
    class Entry(
        val decryptedTitle: String,
        val decryptedExtracted: String?
    )

    private inner class Node(
        val pageId: String,
        val titleKey: String,
        val extractedKey: String,
        val entry: Entry
    )

    private val maxEntries: Int = if (maxEntries < 1) 1 else maxEntries
    private val maxTotalChars: Long = if (maxTotalChars < 1L) 1L else maxTotalChars

    // Access-order LRU keyed by pageId. Guarded by `this`.
    private val nodes = object : LinkedHashMap<String, Node>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Node>): Boolean =
            size > this@DecryptedPageCache.maxEntries
    }
    private var totalChars = 0L

    /**
     * Returns the memoized plaintext for [pageId] when BOTH ciphertext keys
     * still match (i.e. neither field was rewritten since caching), or null on
     * any miss — a null return always means "decrypt again", never "trust me".
     */
    @Synchronized
    fun lookup(pageId: String, titleKey: String, extractedKey: String): Entry? {
        val node = nodes[pageId] ?: return null
        if (node.titleKey != titleKey || node.extractedKey != extractedKey) {
            // Ciphertext changed under the same id — drop the stale plaintext now
            // instead of waiting for the caller's put() to overwrite it.
            nodes.remove(pageId)
            totalChars -= charCountOf(node)
            return null
        }
        return node.entry
    }

    /** Memoizes a freshly decrypted row, evicting LRU rows beyond either bound. */
    @Synchronized
    fun put(pageId: String, titleKey: String, extractedKey: String, decryptedTitle: String, decryptedExtracted: String?) {
        val previous = nodes.remove(pageId)
        if (previous != null) totalChars -= charCountOf(previous)
        val node = Node(pageId, titleKey, extractedKey, Entry(decryptedTitle, decryptedExtracted))
        nodes[pageId] = node
        totalChars += charCountOf(node)
        // Walk coldest→warmest until both bounds hold. At least one entry always
        // survives even if it alone busts the char budget (thrash guard).
        val iterator = nodes.entries.iterator()
        while ((nodes.size > maxEntries || totalChars > maxTotalChars) && nodes.size > 1) {
            val eldest = iterator.next()
            iterator.remove()
            totalChars -= charCountOf(eldest.value)
        }
    }

    /** Drops every memoized plaintext — the lock()/re-key security boundary. */
    @Synchronized
    fun clear() {
        nodes.clear()
        totalChars = 0L
    }

    @Synchronized
    fun size(): Int = nodes.size

    private fun charCountOf(node: Node): Long =
        (node.pageId.length.toLong() * 2L) + node.entry.decryptedTitle.length.toLong() +
            (node.entry.decryptedExtracted?.length?.toLong() ?: 0L)

    companion object {
        /**
         * Default bounds. The entry cap comfortably covers the Home/editor
         * windows plus the capped palette corpus; beyond it the LRU simply
         * falls back to re-decrypting cold rows (a slowdown, never a
         * correctness issue). The char budget (~32 MB of plaintext) stops a few
         * huge note bodies from monopolizing the whole cache.
         */
        const val DEFAULT_MAX_ENTRIES = 1024
        const val DEFAULT_MAX_TOTAL_CHARS = 32L * 1024L * 1024L

        /**
         * Stable identity of one stored ciphertext: first 16 bytes of SHA-256
         * over its UTF-8 bytes, hex-encoded (32 chars). Null/empty fields share
         * the empty-string sentinel — there is nothing to decrypt.
         */
        fun fieldKeyOf(storedValue: String?): String {
            if (storedValue.isNullOrEmpty()) return ""
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(storedValue.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(32)
            for (i in 0 until 16) {
                val b = digest[i].toInt() and 0xFF
                sb.append("0123456789abcdef"[b ushr 4])
                sb.append("0123456789abcdef"[b and 0x0F])
            }
            return sb.toString()
        }
    }
}
