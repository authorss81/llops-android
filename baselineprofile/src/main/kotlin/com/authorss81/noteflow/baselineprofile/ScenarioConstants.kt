package com.authorss81.noteflow.baselineprofile

/**
 * Constants shared by every baseline-profile scenario in this module.
 *
 * NOTE: these are LITERAL copies of host-app values (:app cannot be referenced
 * at compile time from a com.android.test module):
 *  - PACKAGE_NAME mirrors `applicationId` in app/build.gradle.kts
 *    (com.aistudio.inkflow.app.bkxjrz).
 *  - EXTRA_QUICK_CAPTURE mirrors
 *    services/WidgetLaunchPolicy.kt EXTRA_QUICK_CAPTURE — MainActivity's
 *    SUPPORTED deep navigation: when set, the app itself opens a brand-new
 *    note page right after authentication (MainActivity.kt LaunchedEffect
 *    "quickCaptureRequested"). Using the app's own intent contract instead of
 *    UI scraping makes the open-note step deterministic.
 */
internal const val PACKAGE_NAME: String = "com.aistudio.inkflow.app.bkxjrz"

internal const val EXTRA_QUICK_CAPTURE: String =
    "com.authorss81.noteflow.intent.extra.QUICK_CAPTURE"

/** Generous single-step wait: CI-adjacent emulators are slow; waits only ever IDLE. */
internal const val STEP_TIMEOUT_MS: Long = 10_000
