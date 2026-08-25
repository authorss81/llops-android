package com.authorss81.noteflow.services

import java.util.Locale

/**
 * Phase 208 (fix #2): client-side page-list sort.
 *
 * The DAO hard-codes `ORDER BY pinned DESC, updatedAt DESC` (`Daos.kt`) and no
 * sort control exists anywhere; Table/Gallery/Kanban/Calendar all inherit it.
 * This policy sorts the ALREADY-COLLECTED lists client-side (no schema change,
 * no new SQL) keyed by the persisted [SettingsManager.pageSortModeKey] pref.
 *
 * Ordering contract (pinned by [com.authorss81.noteflow.PageSortPolicyTest]):
 *  - Pinned notes always float to the top (primary key `pinned DESC`) — this
 *    preserves the user-visible invariant the DB query established, in every
 *    mode. A "pinned sinks under Title A-Z" list would read as a bug.
 *  - UPDATED_DESC = `updatedAt DESC` inside each pin group (the legacy order,
 *    and the default mode).
 *  - CREATED_DESC = `createdAt DESC` inside each pin group.
 *  - TITLE_ASC = case-insensitive lexicographic title inside each pin group;
 *    ties fall through to `updatedAt DESC` so same-titled notes stay recency
 *    ordered instead of arbitrary.
 *  - [kotlin.collections.sortedWith] is a STABLE merge sort: rows equal under
 *    the chosen keys keep the DAO's input order exactly.
 *
 * Selector lambdas keep this pure JVM — no Room types, unit-testable anywhere.
 */
object PageSortPolicy {

    enum class Mode(val persistenceKey: String, val label: String) {
        UPDATED_DESC("updated_desc", "Updated \u25BC"),
        CREATED_DESC("created_desc", "Created \u25BC"),
        TITLE_ASC("title_asc", "Title A-Z");

        companion object {
            val DEFAULT = UPDATED_DESC

            /** Fail-closed decode: unknown/blank/legacy keys resolve to DEFAULT. */
            fun fromKey(key: String?): Mode =
                entries.firstOrNull { it.persistenceKey == key?.trim()?.lowercase(Locale.US) }
                    ?: DEFAULT
        }
    }

    /** Sanitize a persisted pref value on read AND write (same fail-closed map). */
    fun sanitizePersistenceKey(key: String?): String = Mode.fromKey(key).persistenceKey

    /**
     * Sort an already-collected page list. Never mutates [pages]; returns a new
     * list (stable for equal elements).
     */
    fun <T> sorted(
        pages: List<T>,
        mode: Mode,
        pinned: (T) -> Boolean,
        title: (T) -> String,
        createdAt: (T) -> Long,
        updatedAt: (T) -> Long
    ): List<T> = pages.sortedWith(
        compareByDescending(pinned)
            .then(
                when (mode) {
                    Mode.UPDATED_DESC -> compareByDescending(updatedAt)
                    Mode.CREATED_DESC -> compareByDescending(createdAt)
                    Mode.TITLE_ASC -> compareBy(
                        { title(it).lowercase(Locale.US) },
                        { -updatedAt(it) } // tie-break: newest first among equal titles
                    )
                }
            )
    )
}
