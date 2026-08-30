package com.authorss81.noteflow.ui

import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Phase 238 — provides the OFFICIAL `WindowSizeClass` to every screen.
 *
 * The `material3-window-size-class` artifact (BOM 2024.12.01) exposes
 * `calculateWindowSizeClass(activity)` + the two axis classes, but NOT a
 * CompositionLocal — so this file owns the single `staticCompositionLocalOf`
 * the screens read, and MainActivity sets it from `calculateWindowSizeClass()`
 * at the activity root.
 *
 * The neutral default is Compact/Compact (the STRICTEST class, built via the
 * public `calculateFromSize` on a zero-size window) — any probe before
 * MainActivity provides the real class (i.e. a single pre-provider frame,
 * Compose previews, snapshot tests) briefly sees the compact layout instead
 * of falsely claiming an EXPANDED 840x900 tablet that would flash a wide
 * double-pane layout on a phone. MainActivity derives and provides the true
 * class on the very next composition (Phase 251 also re-derives it on every
 * config change, so the placeholder at most lasts one frame).
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
val LocalWindowSizeClass = staticCompositionLocalOf {
    WindowSizeClass.calculateFromSize(DpSize(0.dp, 0.dp))
}
