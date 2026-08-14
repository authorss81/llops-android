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
 * @param exclusive when true, only ONE enabled, available plugin may serve this
 *   capability at a time. If two enabled plugins claim it, [PluginRegistry]
 *   arbitration deterministically picks a winner (higher version; tie → earlier
 *   registration) and reports the loser as disabled with a reason.
 *
 * Adding a NEW capability is a framework change:
 * 1. Add an `object` to this sealed class (mark it `exclusive` if only one
 *    engine may serve it at a time).
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
    val label: String,
    val exclusive: Boolean = false
) {
    /**
     * Transform note text (e.g. ROT13, case changes). Served by
     * [TextTransformPlugin]. Not exclusive — several transforms may coexist.
     */
    data object TextTransform : PluginCapability("text_transform", "Text Transform")

    /** Extract text from images. Exclusive — one OCR engine at a time (Phase 12). */
    data object OCR : PluginCapability("ocr", "OCR", exclusive = true)

    /** Perform a web search (requires INTERNET). Exclusive (Phase 12). */
    data object WebSearch : PluginCapability("web_search", "Web Search", exclusive = true)

    /** Transfer files off-device. Exclusive (Phase 17). */
    data object FileTransfer : PluginCapability("file_transfer", "File Transfer", exclusive = true)

    /** On-device AI assistance. Exclusive — one assistant at a time (future). */
    data object Assistant : PluginCapability("assistant", "Assistant", exclusive = true)

    /** Export vault content to external formats. Exclusive (future). */
    data object Export : PluginCapability("export", "Export", exclusive = true)

    /** Note text statistics/analysis and diffing. Non-exclusive (Phase 15). */
    data object TextTools : PluginCapability("text_tools", "Text Tools")

    /** Natural-language detection + auto-tagging. Non-exclusive (Phase 15). */
    data object LanguageDetection : PluginCapability("language_detection", "Language Detection")

    /** Fetch a web page and store it as a clean Markdown note. Non-exclusive (Phase 15). */
    data object WebCapture : PluginCapability("web_capture", "Web Capture")

    /**
     * Ingest content shared into the app via ACTION_SEND / ACTION_SEND_MULTIPLE
     * ("Clip to InkFlow"). Non-exclusive (Phase 15).
     */
    data object ClipShare : PluginCapability("clip_share", "Clip to InkFlow")

    /** Convert speech into editor text (explicit mic press). Non-exclusive (Phase 16). */
    data object Dictation : PluginCapability("dictation", "Dictation")

    /** Read a passage aloud via the platform text-to-speech engine. Non-exclusive (Phase 16). */
    data object ReadAloud : PluginCapability("read_aloud", "Read Aloud")

    /** On-device language translation (ML Kit, keyless). Exclusive — one engine (Phase 16). */
    data object Translation : PluginCapability("translation", "Translation", exclusive = true)

    /** Capture the current canvas as an image note (optionally OCR). Non-exclusive (Phase 16). */
    data object ScreenshotNote : PluginCapability("screenshot_note", "Screenshot to Note")

    /** Convert a freehand ink stroke into a clean geometric shape on demand. Non-exclusive (Phase 25). */
    data object ShapeFromInk : PluginCapability("shape_from_ink", "Ink to Shape")

    /** Word definitions (online API + bundled offline fallback). Non-exclusive (Phase 26). */
    data object Dictionary : PluginCapability("dictionary", "Dictionary")

    /** Dated weather snapshot (keyless Open-Meteo, no GPS). Non-exclusive (Phase 26). */
    data object Weather : PluginCapability("weather", "Weather")

    /** Inline unit conversion ("2 km to mi"). Non-exclusive (Phase 26). */
    data object UnitConversion : PluginCapability("unit_conversion", "Unit Converter")

    /** Generate an outline or checkbox list from selected text. Non-exclusive (Phase 26). */
    data object OutlineGenerator : PluginCapability("outline_generator", "Outline & Checklist")

    /** Format a pasted URL/title into a Markdown `[title](url)` link. Non-exclusive (Phase 26). */
    data object CitationFormatter : PluginCapability("citation_formatter", "Citation Formatter")

    companion object {
        // LAZY: referencing the data-object instances during class-init would
        // capture nulls (their INSTANCE fields are set later in <clinit>).
        private val allCapabilities: List<PluginCapability> by lazy { listOf(
            TextTransform, OCR, WebSearch, FileTransfer, Assistant, Export,
            TextTools, LanguageDetection, WebCapture, ClipShare, Dictation,
            ReadAloud, Translation, ScreenshotNote, ShapeFromInk,
            Dictionary, Weather, UnitConversion, OutlineGenerator, CitationFormatter
        ) }

        /** Every known capability, for stable key↔object resolution (Phase 22). */
        val ALL: List<PluginCapability> get() = allCapabilities

        /** The canonical capability for [key], or null when unknown. */
        fun byKey(key: String): PluginCapability? = allCapabilities.firstOrNull { it.key == key }
    }
}