package com.authorss81.noteflow.services

/**
 * Phase 209: Plugin Store discovery — the decision table for surfacing
 * "Browse Plugin Store…" inside plugin UIs so the store is no longer a hidden
 * feature reachable only through Home ⋮ → "Plugin Store" among ~18 items.
 *
 * Two dead ends are closed:
 *  1. The Markdown editor's Plugins menu renders a DISABLED "No text-transform
 *     plugins installed" placeholder when that capability list is empty — a
 *     dead end with no path forward.
 *  2. Every unserved capability section silently hides, so a vault with zero
 *     usable plugins shows an (almost) empty menu with no hint that more exist.
 *
 * Pure JVM — labels + the show/hide decision only; the navigation callback is
 * wired by the host screen (MainActivity hosts the store dialog).
 */
object PluginStoreDiscoveryPolicy {

    /** The appended menu entry's fixed label. */
    const val MENU_LABEL = "Browse Plugin Store…"

    /**
     * Palette quick-action routing key. Deliberately NOT a real
     * [com.authorss81.noteflow.plugins.PluginCapability] key — the palette
     * action routes to opening the store UI (a ViewModel/UI concern), not to a
     * plugin invocation. `NoteflowViewModel.runPaletteAction` intercepts it
     * before the capability dispatch.
     */
    const val PALETTE_CAPABILITY_KEY = "plugin_store"

    /** The palette quick-action's keyword ("store", e.g. typed alone or "store: …"). */
    const val PALETTE_KEYWORD = "store"

    /**
     * Whether a Plugins menu should append [MENU_LABEL] as its LAST item.
     *
     * @param servedEntries total plugin entries rendered across ALL capability
     *   sections of the menu.
     * @param emptyPlaceholderVisible true when the disabled "No text-transform
     *   plugins installed" placeholder row is showing (a capability list that
     *   RENDERED empty).
     */
    fun shouldShowEntry(servedEntries: Int, emptyPlaceholderVisible: Boolean): Boolean =
        servedEntries <= 0 || emptyPlaceholderVisible

    /**
     * Whether the command palette's "store" query should open the store. Kept
     * as a decision point (rather than inline) so tests can pin the routing
     * rule; any non-blank arg is accepted and ignored by design — `store` and
     * `store: ocr` both mean "open the store".
     */
    fun shouldOpenFromPalette(capabilityKey: String): Boolean =
        capabilityKey == PALETTE_CAPABILITY_KEY
}
