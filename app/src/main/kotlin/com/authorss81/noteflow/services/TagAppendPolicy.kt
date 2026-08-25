package com.authorss81.noteflow.services

import java.util.Locale

/**
 * Phase 208 review-fix (finding 3): pure-JVM merge rule for the multi-select
 * bulk tag APPEND.
 *
 * Existing entries are preserved VERBATIM and first; an addition is appended
 * only when no existing (or already-appended) entry matches it
 * case-insensitively — appending never duplicates and never rewrites the
 * note's own tagging order.
 *
 * Returns `null` when nothing would change, so the caller can skip the DB
 * write entirely (no pointless updatedAt churn). Pure JVM; no Android imports.
 */
object TagAppendPolicy {

    fun merge(existingCsv: String?, additions: List<String>): String? {
        val existingTags = existingCsv?.split(',') ?: emptyList()
        val seen = HashSet<String>()
        for (tag in existingTags) {
            val key = tag.trim().lowercase(Locale.US)
            if (key.isNotEmpty()) seen.add(key)
        }
        val appended = ArrayList<String>()
        for (raw in additions) {
            val tag = raw.trim()
            // A comma inside an addition would corrupt the CSV column round-trip.
            if (tag.isEmpty() || tag.contains(',')) continue
            val key = tag.lowercase(Locale.US)
            if (key in seen) continue
            seen.add(key)
            appended.add(tag)
        }
        if (appended.isEmpty()) return null
        // Normalize only stray join-spacing on the existing side; entries stay
        // verbatim otherwise.
        val base = existingTags.joinToString(",").trim()
        return if (base.isEmpty()) {
            appended.joinToString(",")
        } else {
            "$base,${appended.joinToString(",")}"
        }
    }
}
