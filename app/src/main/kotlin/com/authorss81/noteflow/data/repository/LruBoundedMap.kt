package com.authorss81.noteflow.data.repository

import java.util.LinkedHashMap

/**
 * Access-order `LinkedHashMap` that evicts the least-recently-accessed entry once
 * [maxEntries] is exceeded (classic LRU cap).
 *
 * Phase 100 (finding B2-DOS-10): the previous plain `mutableMapOf` used by
 * `NoteRepository.lastSavedStrokeHash` grew without bound for the whole session —
 * one entry per stroke UUID ever loaded or saved, keyed globally across all pages
 * and never GC'd. Bounding it here caps the diff cache so a long editing session
 * on a tens-of-thousands-of-strokes vault stays at a fixed memory ceiling.
 *
 * Eviction is safe by construction for the caller's use (diffing stroke content
 * before save): a missing entry simply causes the next save to consider that
 * stroke "changed" and re-write it — a redundant write, never missing data.
 *
 * NOT thread-safe — matches the previous plain-map behaviour. B2-UI-3 (phase-73)
 * applied the required concurrent synchronization ON TOP of this: the single
 * call-site that holds it ([com.authorss81.noteflow.data.repository.NoteRepository.lastSavedStrokeHash])
 * wraps it in `Collections.synchronizedMap` and additionally serializes the
 * compound read-modify-write per page ([com.authorss81.noteflow.data.repository.NoteRepository.pageSaveLocks]).
 * The class itself stays a plain access-order LinkedHashMap.
 */
class LruBoundedMap<K, V>(
    private val maxEntries: Int,
    initialCapacity: Int = 16
) : LinkedHashMap<K, V>(initialCapacity, DEFAULT_LOAD_FACTOR, true) {

    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean =
        size > maxEntries

    companion object {
        private const val DEFAULT_LOAD_FACTOR = 0.75f
    }
}