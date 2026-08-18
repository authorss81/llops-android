package com.authorss81.noteflow.services

import android.content.ClipData
import android.content.Context
import android.os.Build

/**
 * N5/34.7 + B2-UI-2 (phase-72) + R2-B1P-01 (phase-139): tracks the last
 * note-content copy made by this app (code blocks, OCR text — see
 * [recordCopy] call sites) and clears the system clipboard on lock. The
 * clipboard is a shared, non-encrypted surface — copied note content must not
 * survive a lock.
 *
 * The decide → clear → forget decision is delegated to [ClipboardScrubPolicy]
 * (pure JVM). The Android-bound write — clearing the system primary clip —
 * lives here and is best-effort: any platform failure is swallowed so the lock
 * path can never break.
 *
 * There are TWO scrub modes:
 *
 *  - [scrubIfOwnCopy] — the WINDOWED mode. Clears the primary clip only when
 *    the app's own most recent copy is inside [ClipboardScrubPolicy.SCRUB_WINDOW_MS]
 *    and forgets the timestamp after the scrub, so a foreign (other-app) copy is
 *    never wiped and a later lock leaves whatever the user copied since alone.
 *    Used by the ON_PAUSE lifecycle hook as defense-in-depth (a brief app switch
 *    must never wipe a foreign copy).
 *
 *  - [scrubUnconditionally] — the LOCK mode (R2-B1P-01, phase-139). Clears the
 *    primary clip no matter what is on it and no matter whether this app copied
 *    it. A vault lock is an explicit security boundary in this app's threat
 *    model, and several note-content copy surfaces are PLATFORM-native (the
 *    markdown editor's selection Copy, the OCR dialog's `SelectionContainer`
 *    Copy) that no `recordCopy()` stamp can observe — so the lock path cannot
 *    reliably know whether the current primary clip holds decrypted note body.
 *    Fail-closed: the clip is wiped in full. The stamp is forgotten afterwards.
 */
object ClipboardGuard {
    @Volatile
    var mostRecentCopyAtMs: Long = 0L

    /**
     * B2-UI-2 test seam: when non-null, [scrubIfOwnCopy] and
     * [scrubUnconditionally] route the actual primary-clip clear here instead of
     * the system [android.content.ClipboardManager], so the clear event is
     * provable on the pure JVM. Production leaves it null and always goes
     * through the system service.
     */
    @Volatile
    internal var clearPrimaryClipOverride: (() -> Unit)? = null

    fun recordCopy() {
        mostRecentCopyAtMs = System.currentTimeMillis()
    }

    /**
     * Clears the primary clip when the last app copy is within [windowMs].
     *
     * [context] is only consulted when [clearPrimaryClipOverride] is null; if
     * both are absent there is no way to reach the system ClipboardManager, so
     * nothing is touched and false is returned. Unit tests exercising the
     * decision through the seam may pass null. Returns true when the primary
     * clip was actually cleared (and the copy timestamp forgotten), false when
     * nothing was touched — a foreign or expired/no app copy on the clipboard
     * is always left alone.
     */
    fun scrubIfOwnCopy(context: Context?, windowMs: Long = ClipboardScrubPolicy.SCRUB_WINDOW_MS): Boolean {
        val copiedAt = mostRecentCopyAtMs
        if (!ClipboardScrubPolicy.shouldScrub(copiedAtMs = copiedAt, nowMs = System.currentTimeMillis(), windowMs = windowMs)) {
            return false
        }
        return clearPrimaryClip(context)
    }

    /**
     * Clears the primary clip UNCONDITIONALLY — the lock-time scrub
     * (R2-B1P-01). No stamp check, no window: any primary clip (an app copy, an
     * untracked platform-native note-body copy, or a foreign copy) is wiped, so
     * decrypted note content can never survive a lock through an unobserved copy
     * surface. Best-effort: a platform failure returns false and is swallowed —
     * the lock path never breaks on it. Forgetting the stamp after a successful
     * clear keeps the windowed [scrubIfOwnCopy] decision consistent (a later
     * lock has no app-copy timestamp to act on).
     */
    fun scrubUnconditionally(context: Context?): Boolean {
        return clearPrimaryClip(context)
    }

    private fun clearPrimaryClip(context: Context?): Boolean {
        return try {
            val override = clearPrimaryClipOverride
            when {
                override != null -> override()
                context != null -> {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        cm.clearPrimaryClip()
                    } else {
                        cm.setPrimaryClip(ClipData.newPlainText("", ""))
                    }
                }
                else -> return false
            }
            mostRecentCopyAtMs = 0L
            true
        } catch (e: Exception) {
            // best-effort — never crash the lock path
            false
        }
    }
}
