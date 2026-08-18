package com.authorss81.noteflow.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.authorss81.noteflow.BuildConfig
import com.authorss81.noteflow.services.SecureDialogPolicy

/**
 * R2-b2b1-UI-02 (phase-140): Compose `Dialog`/`AlertDialog` windows do NOT
 * inherit the activity window's FLAG_SECURE, so decrypted content shown inside
 * them (the Command Palette note-title list, OCR text, MarkdownPreviewScreen /
 * plugin dialogs over an open note) could still be captured by a screencap
 * despite the protected main window.
 *
 * Every content-bearing dialog call site passes this helper as its
 * `properties` — the caller's `DialogProperties` defaults are preserved and the
 * `FLAG_SECURE`-bearing [SecureFlagPolicy.SecureOn] is applied for EXACTLY the
 * same builds that protect the activity window ([SecureDialogPolicy], i.e.
 * release only; debug / emulator streaming environments stay renderable).
 */
@Composable
fun secureDialogProperties(
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
    usePlatformDefaultWidth: Boolean = true,
    decorFitsSystemWindows: Boolean = true
): DialogProperties {
    val secure = SecureDialogPolicy.dialogWindowsAreSecure(BuildConfig.DEBUG)
    return remember(dismissOnBackPress, dismissOnClickOutside, secure, usePlatformDefaultWidth, decorFitsSystemWindows) {
        DialogProperties(
            dismissOnBackPress = dismissOnBackPress,
            dismissOnClickOutside = dismissOnClickOutside,
            securePolicy = if (secure) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit,
            usePlatformDefaultWidth = usePlatformDefaultWidth,
            decorFitsSystemWindows = decorFitsSystemWindows
        )
    }
}