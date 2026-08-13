package com.authorss81.noteflow.plugins

import android.content.Context

/**
 * The one tiny, REAL plugin shipped with the framework — it proves the whole
 * wiring end-to-end (register → opt-in → route → invoke) without being a facade.
 *
 * ROT13 is the classic Caesar cipher where each ASCII letter is rotated 13
 * positions (a→n, B→O, …). Applying it twice returns the original text, and non
 * -letters pass through unchanged. It is served through the
 * [PluginCapability.TextTransform] capability via [TextTransformPlugin], so any
 * text-transform feature in the app (the Markdown editor's "Plugins" menu) picks
 * it up purely by capability — no special-casing.
 */
class Rot13TransformPlugin : NoteflowPlugin, TextTransformPlugin {

    override val id = "com.authorss81.noteflow.plugins.rot13"
    override val name = "ROT13 Text Transform"
    override val description = "Rotates the ASCII letters of note text by 13 positions (ROT13 cipher)."
    override val version = "1.0.0"
    override val capabilities: Set<PluginCapability> = setOf(PluginCapability.TextTransform)

    override fun isAvailable(context: Context?): Boolean = true

    override fun onEnable(context: Context?) {
        // Stateless — nothing to warm up. Kept real (called exactly once on the
        // store's enabled transition) rather than omitted from the interface.
    }

    override fun transformText(text: String): String {
        val out = StringBuilder(text.length)
        for (ch in text) {
            when (ch) {
                in 'a'..'z' -> out.append((((ch - 'a') + 13) % 26 + 'a'.code).toChar())
                in 'A'..'Z' -> out.append((((ch - 'A') + 13) % 26 + 'A'.code).toChar())
                else -> out.append(ch)
            }
        }
        return out.toString()
    }
}