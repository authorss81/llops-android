package com.authorss81.noteflow.services

import android.content.ClipData
import android.content.Context
import android.os.Build

/**
 * N5/34.7: tracks the last code-block copy made by this app so the lock path
 * can scrub the system clipboard. The clipboard is a shared, non-encrypted
 * surface — copied note content must not survive a lock.
 */
object ClipboardGuard {
    @Volatile
    var mostRecentCopyAtMs: Long = 0L

    fun recordCopy() {
        mostRecentCopyAtMs = System.currentTimeMillis()
    }

    /** Clears the primary clip if the last app copy is within [windowMs]. */
    fun scrubIfOwnCopy(context: Context, windowMs: Long = 60_000L) {
        val copiedAt = mostRecentCopyAtMs
        if (copiedAt == 0L || System.currentTimeMillis() - copiedAt > windowMs) return
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cm.clearPrimaryClip()
            } else {
                cm.setPrimaryClip(ClipData.newPlainText("", ""))
            }
            mostRecentCopyAtMs = 0L
        } catch (e: Exception) {
            // best-effort — never crash the lock path
        }
    }
}
