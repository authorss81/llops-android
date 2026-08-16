package com.authorss81.noteflow.services

/**
 * B1-DB-8 (phase-88): the single decision table for decrypt-failure handling.
 *
 * Finding: every decrypt-failure fallback in [com.authorss81.noteflow.data.repository.NoteRepository]
 * returned the RAW base64 AES-GCM blob as if it were the note's real content —
 * the stroke text fallback (`catch { rawText }`), the page title/body fallback
 * (`decryptPageIfNeeded` returning the page unchanged), the embed text fallback
 * (`catch { text }`) and the version-title/body fallback (`?: v.title`). After a
 * re-key, a mismatched-DEK cross-device restore or partial DB manipulation, the
 * user saw ciphertext garbage as the note title/text, and because the failure
 * looked like legitimate content the incident was never surfaced as the decrypt
 * failure it was — masking the tamper/re-key problem the integrity checks exist
 * to catch.
 *
 * Rules (single source of truth):
 *  - [isStructuralCiphertext] classifies the stored value purely structurally
 *    (an [EncryptionService] payload shape, never its content). A stored value
 *    that is NOT structurally a payload is legacy plaintext and MUST render
 *    as-is — it must never be replaced by the marker (a regression for the
 *    pre-field-encryption rows the sweep still supports).
 *  - [render] is the ONLY render decision: the authenticated decrypted text,
 *    the stored value when it is genuine plaintext, or [UNREADABLE_MARKER] when
 *    a genuine ciphertext failed authentication. A raw ciphertext blob can never
 *    be rendered as note content.
 *  - [isPersistent] escalates a session in which [PERSISTENT_FAILURE_THRESHOLD]
 *    DISTINCT NOTES (deduped `note:<pageId>` — one note counting once no matter
 *    how many of its rows/fields fail) failed to decrypt — the DEK is present
 *    and correct by construction there, so this is a re-key/restore mismatch or
 *    a manipulated DB, not an isolated note — to a corruption/restore event:
 *    the caller raises the corruption flag so the existing recovery
 *    screen offers restore/re-key instead of silently degrading to a vault full
 *    of markers.
 *
 * Pure JVM (java.util.Base64 + [EncryptionService]), API 26+ floor, no fallback
 * needed; the failure notices are non-alarming and never log content.
 */
object DecryptFailurePolicy {

    /** The ONLY display value a genuine ciphertext auth failure may render as. */
    const val UNREADABLE_MARKER = "Unreadable (decryption failed)"

    /**
     * Distinct records failing to decrypt in one session at/after which the
     * failure is judged PERSISTENT (escalate to the corruption/restore event).
     * Deliberately an ID-count, never an attempt-count: one broken row is read
     * by several flows (Home list, search window, editor), so counting attempts
     * would false-trigger on a single corrupted note.
     */
    const val PERSISTENT_FAILURE_THRESHOLD = 10

    /**
     * Non-alarming first-failure notice (optional surface; the marker itself is
     * never silent degradation either — the unreadable note visibly renders the
     * marker, not garbage).
     */
    const val DECRYPT_FAILURE_NOTICE =
        "Some notes could not be decrypted and are marked \u201C$UNREADABLE_MARKER\u201D. " +
            "Restore a recent backup or re-key your vault if this keeps happening."

    /** Non-alarming promotion surfaced when the persistent threshold is crossed. */
    const val PERSISTENT_DECRYPT_FAILURE_NOTICE =
        "Many notes could not be decrypted \u2014 your vault may be damaged or was restored with a " +
            "different key. Use the recovery screen to restore a recent backup or start fresh."

    /** A session with >= [PERSISTENT_FAILURE_THRESHOLD] distinct failed records is persistent. */
    fun isPersistent(distinctFailedRecords: Int): Boolean =
        distinctFailedRecords >= PERSISTENT_FAILURE_THRESHOLD

    /**
     * Structural ciphertext classifier — a value that is not structurally an
     * AEAD payload is legacy plaintext and must never be replaced by the marker.
     */
    fun isStructuralCiphertext(value: String): Boolean = EncryptionService.isEncryptedPayload(value)

    /**
     * Decides what a stored value may render as — the ONLY three outcomes:
     *  - genuine plaintext (not structurally ciphertext) → [storedValue] verbatim;
     *  - structurally ciphertext that decrypted → [decrypted];
     *  - structurally ciphertext whose authentication failed ([decrypted]==null) →
     *    [UNREADABLE_MARKER], NEVER the raw blob.
     */
    fun render(storedValue: String, decrypted: String?, isCiphertext: Boolean): String = when {
        !isCiphertext -> storedValue
        decrypted != null -> decrypted
        else -> UNREADABLE_MARKER
    }
}