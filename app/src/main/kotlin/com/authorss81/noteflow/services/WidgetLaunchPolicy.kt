package com.authorss81.noteflow.services

/**
 * Phase 158 (deferred ROADMAP 22.5b, lightweight in-base widget) — pure-JVM
 * decision table for the home-widget "New note" quick-capture launcher
 * shortcut.
 *
 * The widget is deliberately a LAUNCHER SHORTCUT ONLY: it carries NO vault
 * content, runs NO vault or Room code in the widget provider process, has NO
 * periodic refresh whatsoever (updatePeriodMillis = 0), and needs NO new
 * permission. Tapping it fires a normal `MainActivity` intent carrying one
 * explicit boolean extra ([EXTRA_QUICK_CAPTURE]); `MainActivity` reads that
 * extra and opens straight to a fresh note. Every string/boolean the widget
 * and the activity share lives here so the intent contract is unit-pinnable.
 */
object WidgetLaunchPolicy {

    /** The explicit EXTRAS flag MainActivity reads to trigger a quick-capture. */
    const val EXTRA_QUICK_CAPTURE = "com.authorss81.noteflow.intent.extra.QUICK_CAPTURE"

    /** The widget provider class final segment (registered in the manifest). */
    const val WIDGET_PROVIDER_CLASS = "com.authorss81.noteflow.ui.widget.QuickCaptureWidget"

    /** The RemoteViews root id used for both the layout and the PendingIntent. */
    const val WIDGET_ROOT_VIEW_ID = "widget_quick_capture_root"

    /** Provider-info XML resource (res/xml/quick_capture_widget_info.xml). */
    const val WIDGET_INFO_XML = "quick_capture_widget_info"

    /** Flat (no-inherent-size) home-screen launcher widget geometry — 1x1 default. */
    const val MIN_WIDTH_DP = 40
    const val MIN_HEIGHT_DP = 40
    const val MAX_RESIZE_WIDTH_DP = 250
    const val MAX_RESIZE_HEIGHT_DP = 110

    /**
     * Whether an intent asks for a quick capture. Parsed from an internal
     * extras bundle so the decision is pure-JVM testable (a `Map<String,Boolean>`
     * stands in for `Intent.getBooleanExtra` at the activity layer).
     */
    fun hasQuickCaptureExtra(extras: Map<String, Boolean?>, key: String = EXTRA_QUICK_CAPTURE): Boolean =
        extras[key] == true

    /** The user-visible add-note action that the widget tap ultimately performs. */
    const val QUICK_CAPTURE_DEFAULT_TITLE = "New Page"

    /** Fixed content descriptions / labels for the widget provider+activity. */
    const val WIDGET_WIDGET_LABEL = "New note"
    const val WIDGET_DESCRIPTION = "Open a new note in InkFlow (no content shown on the widget)"
    const val WIDGET_PREVIEW_TITLE = "New note"

    /** The PendingIntent request code (distinct from the share-sheet flow). */
    const val WIDGET_PENDING_INTENT_REQUEST_CODE = 0x51A71E

    /**
     * Honest caption for the ⋮-menu item that copies the quick-capture action
     * into a reminder flow: the widget itself is the launcher shortcut, this
     * is informational.
     */
    const val WIDGET_MENU_LABEL = "Add New-Note widget to home screen"
}