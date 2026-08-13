package com.authorss81.noteflow.plugins

/**
 * The extension points of the InkFlow plugin framework.
 *
 * A capability is a well-defined unit of behavior a plugin can provide. The
 * framework never calls into a plugin directly — it routes a capability request
 * through [PluginManager], which picks the enabled, device-available plugin that
 * declares that capability and hands the caller a typed result (or a loud,
 * user-facing failure when nothing can serve it).
 *
 * Adding a NEW capability is a framework change:
 * 1. Add an `object` to this sealed class.
 * 2. Define the *serving interface* the plugin must implement (e.g.
 *    [TextTransformPlugin]) in `NoteflowPlugin.kt`.
 * 3. Route through `PluginManager` (see docs/PLUGINS.md).
 *
 * @see NoteflowPlugin
 * @see PluginRegistry
 * @see PluginManager
 */
sealed class PluginCapability(
    val key: String,
    val label: String
) {
    /**
     * Transform note text (e.g. ROT13, case changes). Served by
     * [TextTransformPlugin]. The only capability shipped this phase.
     */
    data object TextTransform : PluginCapability("text_transform", "Text Transform")

    /** Extract text from images (Phase 12). */
    data object OCR : PluginCapability("ocr", "OCR")

    /** Perform a web search (Phase 12 — requires INTERNET). */
    data object WebSearch : PluginCapability("web_search", "Web Search")

    /** Transfer files off-device (Phase 17). */
    data object FileTransfer : PluginCapability("file_transfer", "File Transfer")

    /** On-device AI assistance (future). */
    data object Assistant : PluginCapability("assistant", "Assistant")

    /** Export vault content to external formats (future). */
    data object Export : PluginCapability("export", "Export")
}
