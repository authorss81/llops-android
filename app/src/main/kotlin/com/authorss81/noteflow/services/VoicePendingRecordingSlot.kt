package com.authorss81.noteflow.services

/**
 * Phase 204: ViewModel-scoped relay for a voice recording that was finalized
 * and SAVED (the `.enc` blob is written) but whose audio embed was never
 * attached because the editor composition died between finalize and attach —
 * a rotation mid-recording (no `configChanges` in the manifest ⇒ the editor is
 * disposed, `release()` finalizes inside `stopRecording()`), or a ceiling abort
 * racing the dispose.
 *
 * Pre-fix the finished result was returned from `finalizeRecording` into
 * `release()` which discarded it: the blob was orphaned on disk with no DB row,
 * no embed, and no notice (the discard notice only fires for FAILED saves).
 * Now the editor teardown publishes the unattached result HERE (keyed by page)
 * and the NEXT editor instance for that page consumes it once and attaches the
 * embed, surfacing an honest "recording recovered" notice.
 *
 * Pure JVM, no Android imports — unit-testable lifecycle. The slot lives as a
 * plain ViewModel property so it survives configuration changes (rotation);
 * process death loses it, which is acceptable: the encrypted blob itself stays
 * on disk either way.
 */
class VoicePendingRecordingSlot {

    private val pending = java.util.concurrent.ConcurrentHashMap<String, VoiceRecordingResult>()

    /** Stores [result] for [pageId], replacing any earlier unconsumed entry. */
    fun publish(pageId: String, result: VoiceRecordingResult) {
        pending[pageId] = result
    }

    /** One-shot take: removes and returns the pending result for [pageId], or null. */
    fun consume(pageId: String): VoiceRecordingResult? = pending.remove(pageId)

    /** Whether an unconsumed result is waiting for [pageId]. */
    fun hasPending(pageId: String): Boolean = pending.containsKey(pageId)

    companion object {
        /**
         * Fixed, honest, non-alarming notice shown when a recovered recording is
         * attached to the note by the next editor instance (UiFailureTextPolicy
         * fixed-text discipline — never exception text).
         */
        const val RECOVERED_NOTICE: String =
            "Saved voice recording restored to this note."
    }
}
