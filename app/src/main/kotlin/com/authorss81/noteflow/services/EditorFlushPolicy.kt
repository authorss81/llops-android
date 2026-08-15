package com.authorss81.noteflow.services

import com.authorss81.noteflow.data.model.CanvasMediaEmbed
import com.authorss81.noteflow.data.model.CanvasStickyNote
import com.authorss81.noteflow.data.model.LayerEntity
import com.authorss81.noteflow.data.model.Stroke

/**
 * B2-UI-1 (phase-49): pure-JVM model of an editor page flush that must be safe
 * against a vault lock racing it. The Android-binding ([com.authorss81.noteflow.ui.viewmodel.NoteflowViewModel] /
 * EditorScreen) drives the actual repository writes through this decision +
 * deferral table; the model itself has no Android references and is fully
 * unit-testable on the CI runner.
 *
 * Rules (fail closed, via [VaultWriteGate]):
 *  1. A save attempted while the DEK is present is PERSISTED NOW.
 *  2. A save attempted while the DEK is absent (vault locked) is DEFERRED —
 *     never written plaintext, never dropped.
 *  3. Deferred saves are drained after the next successful unlock and re-written
 *     with the live key (idempotent; latest-wins per page).
 */
class EditorFlushPolicy {

    /** A full page snapshot that could not be written while the vault was locked. */
    data class DeferredSave(
        val pageId: String,
        val strokes: List<Stroke>,
        val stickyNotes: List<CanvasStickyNote>,
        val embeds: List<CanvasMediaEmbed>,
        val layers: List<LayerEntity>
    )

    /**
     * A markdown/text note body that locked mid-save. The legacy companion
     * plaintext file (if any) is deleted only AFTER the encrypted-column write,
     * so it is carried here and deleted after the next unlock's flush.
     */
    data class DeferredBody(
        val pageId: String,
        val body: String,
        val legacySourceFilePath: String?,
        val legacySourceFileType: String?
    )

    private val deferred = LinkedHashMap<String, DeferredSave>()
    private val deferredBodies = LinkedHashMap<String, DeferredBody>()

    /** Unlocked ⇔ `repository.encryptionKey != null` at the call site. */
    fun isUnlocked(keyIsPresent: Boolean): Boolean = VaultWriteGate.persistNow(keyIsPresent)

    /**
     * Registers [save] as deferred (latest-wins per page — an older snapshot for
     * the same page is replaced). Returns true if it replaced an existing entry.
     */
    fun defer(save: DeferredSave): Boolean = synchronized(this) {
        val replaced = deferred.containsKey(save.pageId)
        deferred[save.pageId] = save
        replaced
    }

    /** Number of page snapshots currently deferred (stashed for a future unlock). */
    val deferredCount: Int
        get() = synchronized(this) { deferred.size }

    /**
     * Registers [body] as deferred (latest-wins per page). Returns true if it
     * replaced an existing entry.
     */
    fun deferBody(body: DeferredBody): Boolean = synchronized(this) {
        val replaced = deferredBodies.containsKey(body.pageId)
        deferredBodies[body.pageId] = body
        replaced
    }

    /** Number of note bodies currently deferred (stashed for a future unlock). */
    val deferredBodyCount: Int
        get() = synchronized(this) { deferredBodies.size }

    /**
     * Drains (snapshots + clears) every deferred note body. Must only be called
     * once the vault is re-unlocked — otherwise the flush would immediately
     * re-defer the body write.
     */
    fun drainBodies(): List<DeferredBody> = synchronized(this) {
        if (deferredBodies.isEmpty()) emptyList()
        else deferredBodies.values.toList().also { deferredBodies.clear() }
    }

    /**
     * Drains (snapshots + clears) every deferred page save so the unlock handler
     * can flush them with the live key. Must only be called once the vault is
     * re-unlocked — otherwise the flush would immediately re-defer.
     */
    fun drain(): List<DeferredSave> = synchronized(this) {
        if (deferred.isEmpty()) emptyList()
        else deferred.values.toList().also { deferred.clear() }
    }
}