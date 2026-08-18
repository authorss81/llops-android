package com.authorss81.noteflow.services

/**
 * R2-b2b1-UI-02 (phase-140) — pure-JVM decision gate for per-dialog FLAG_SECURE.
 *
 * The finding: `FLAG_SECURE` is applied to the ACTIVITY window only
 * (`MainActivity` `window.addFlags`), but every Compose `Dialog`/`AlertDialog`
 * renders in a SEPARATE `WindowManager` window that does NOT inherit the
 * activity's window flags — so a screencap captures the Command Palette's
 * decrypted note-title list, the OCR dialog's full note text, and the
 * MarkdownPreviewScreen plugin dialogs even though the main window is
 * protected.
 *
 * The fix reuses [SecureWindowPolicy]'s exact build gate — release builds get
 * `SecureFlagPolicy.SecureOn` on every content dialog window, debug / emulator
 * streaming environments keep `Inherit` (renderable, mirroring the activity).
 * The `SecureFlagPolicy` mapping itself lives in the Compose helper; this
 * object stays pure JVM so the decision is unit-pinned.
 */
object SecureDialogPolicy {

    /** Whether dialog windows must carry FLAG_SECURE for this build. */
    fun dialogWindowsAreSecure(debug: Boolean): Boolean =
        SecureWindowPolicy.shouldApplySecureFlag(debug)
}