package com.authorss81.noteflow.services

/**
 * B2-UI-2 (phase-72): pure-JVM decision table for the clipboard-scrub-on-lock
 * policy. The system clipboard is a shared plaintext surface — decrypted note
 * content copied by this app (code blocks, OCR results) must not survive a vault
 * lock. The decision is split from the Android clipboard write ([ClipboardGuard])
 * so the decide → clear → forget sequence is provable on the pure JVM.
 *
 * Foreign copies (made by other apps) are NEVER tracked, so a lock can never wipe
 * them: the guard only clears the primary clip when the app's own most recent copy
 * is inside the window, and a successful scrub forgets the timestamp so whatever
 * the user copies afterwards is left alone by the next lock.
 */
object ClipboardScrubPolicy {
    /** How long after an app copy a lock may still clear the primary clip. */
    const val SCRUB_WINDOW_MS: Long = 60_000L

    /**
     * True only when [copiedAtMs] is a genuine (non-zero) app copy still inside
     * [windowMs] as of [nowMs] — the single condition under which a lock clears
     * the primary clip. A zero timestamp means "no app copy, or already scrubbed"
     * and is always left alone.
     */
    fun shouldScrub(copiedAtMs: Long, nowMs: Long, windowMs: Long = SCRUB_WINDOW_MS): Boolean =
        copiedAtMs != 0L && nowMs - copiedAtMs <= windowMs
}