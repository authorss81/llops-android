package com.authorss81.noteflow.plugins.clipshare

import com.authorss81.noteflow.plugins.SharedClip

/**
 * B1-PLAT-2 (phase-58): builds the user-facing body of the "Clip into InkFlow?"
 * confirmation dialog.
 *
 * An incoming share is NEVER staged or copied on arrival — it is held behind an
 * explicit in-app confirmation that summarises exactly what will be clipped
 * (text preview, image/file counts). Pure JVM so the dialog copy is
 * unit-testable without a device.
 */
object ClipShareConfirmNotice {

    const val TEXT_PREVIEW_CHARS = 160

    /**
     * One-line, human-readable summary of [clip] for the confirmation dialog.
     */
    fun summary(clip: SharedClip): String {
        val streamCount = clip.streams.size
        return when {
            streamCount == 0 -> "Shared text"
            streamCount == 1 -> {
                val kind = when {
                    clip.kind.name == "IMAGES" -> "image"
                    clip.kind.name == "MULTIPART" -> "text + attachment"
                    else -> "file"
                }
                "Shared $kind"
            }
            else -> "$streamCount shared items"
        }
    }

    /**
     * Body copy describing what confirming will do, including a short text
     * preview when the share carries text.
     */
    fun body(clip: SharedClip): String {
        val itemLine = when {
            clip.streams.isEmpty() -> "InkFlow will create a new note from the shared text."
            clip.text.isNullOrBlank() ->
                "InkFlow will create a new note containing the ${clip.streams.size} shared item(s)."
            else ->
                "InkFlow will create a new note from the shared text plus its ${clip.streams.size} item(s)."
        }
        val preview = clip.text?.trim()
            ?.take(TEXT_PREVIEW_CHARS)
            ?.takeIf { it.isNotEmpty() } ?: return itemLine
        val ellipsis = if (clip.text.trim().length > TEXT_PREVIEW_CHARS) "…" else ""
        return "$itemLine\n\n\"$preview$ellipsis\""
    }
}