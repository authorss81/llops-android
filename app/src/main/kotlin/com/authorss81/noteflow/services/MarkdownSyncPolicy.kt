package com.authorss81.noteflow.services

/**
 * Phase 258 — pure-JVM decision logic for the whole-document markdown editor's
 * external-content sync and at-caret insertion.
 *
 * Everything here is off the UI thread and side-effect free so the
 * reload/dirty/blank-reopen rules are unit-testable without an emulator
 * (mirroring the ReaderModePolicy / WikiSuggestionPolicy pattern).
 */
object MarkdownSyncPolicy {

    /**
     * True when an externally-arriving [initialContent] (the async decrypted-body
     * read landing after first composition, a re-read after a version restore,
     * a re-keyed body, ...) MAY replace the editor's [contentText].
     *
     * The rule is a version-token compare: adopt only when the editor is
     * PRISTINE (no local unsaved edits — [contentText] still equals the last
     * committed [savedContent]) AND the arriving value actually differs from
     * that committed baseline. This is what upgrades the old empty→non-empty
     * only heal (which left a NON-empty stale snapshot on screen and let the
     * next [flushSave] write it back over newer DB content) into a heal that
     * covers every direction — while a keystroke that landed before a late DB
     * echo (contentText ≠ savedContent) can never be clobbered.
     */
    fun shouldAdoptExternal(contentText: String, savedContent: String, initialContent: String): Boolean =
        contentText == savedContent && initialContent != savedContent

    /**
     * Insert [body] at [caretOffset] in [text], replacing the old append-at-end
     * behavior of the slash/wiki insert flows.
     *
     * When [caretOffset] is null or out of range the body is appended at the
     * document end — exactly the legacy semantics — so an editor that has not
     * reported a caret yet cannot corrupt anything. Returns the patched document
     * plus the caret offset just past the inserted [body].
     */
    fun insertAtCaret(text: String, caretOffset: Int?, body: String): AtCaretInsert {
        var end = (caretOffset ?: text.length).coerceIn(0, text.length)
        // Never split an astral (surrogate-pair) character: if the caret landed
        // between a pair's high and low surrogate halves, push the splice past
        // the whole pair so the emoji survives intact.
        if (end > 0 && end < text.length &&
            Character.isHighSurrogate(text[end - 1]) &&
            Character.isLowSurrogate(text[end])
        ) {
            end += 1
        }
        if (end >= text.length) {
            return AtCaretInsert(text + body, text.length + body.length)
        }
        return AtCaretInsert(
            text.substring(0, end) + body + text.substring(end),
            end + body.length
        )
    }

    data class AtCaretInsert(val text: String, val caretAfter: Int)
}