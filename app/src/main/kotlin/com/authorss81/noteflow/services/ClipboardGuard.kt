package com.authorss81.noteflow.services

import android.content.ClipData
import android.content.Context
import android.os.Build

/**
 * N5/34.7 + B2-UI-2 (phase-72): tracks the last note-content copy made by this
 * app (code blocks, OCR text — see [recordCopy] call sites) so the lock path can
 * scrub the system clipboard. The clipboard is a shared, non-encrypted surface —
 * copied note content must not survive a lock.
 *
 * The decide → clear → forget decision is delegated to [ClipboardScrubPolicy]
 * (pure JVM). The Android-bound write — clearing the system primary clip — lives
 * here and is best-effort: any platform failure is swallowed so the lock path can
 * never break. A foreign (other-app) copy is never cleared: the guard only acts
 * on the app's own most recent copy within [ClipboardScrubPolicy.SCRUB_WINDOW_MS],
 * and after a successful scrub the timestamp is forgotten so the NEXT lock leaves
 * whatever the user copied since alone.
 */
object ClipboardGuard {
    @Volatile
    var mostRecentCopyAtMs: Long = 0L

    /**
     * B2-UI-2 test seam: when non-null, [scrubIfOwnCopy] routes the actual
     * primary-clip clear here instead of the system
     * [android.content.ClipboardManager], so the clear event is provable on the
     * pure JVM. Production leaves it null and always goes through the system
     * service.
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
