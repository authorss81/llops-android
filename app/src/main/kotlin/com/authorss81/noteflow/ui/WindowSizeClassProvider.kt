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
 * The neutral default is EXPANDED/EXPANDED (built via the public
 * `calculateFromSize`) so any probe before MainActivity provides stays in the
 * roomy layout instead of flashing compact.
 */
@OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
val LocalWindowSizeClass = staticCompositionLocalOf {
    WindowSizeClass.calculateFromSize(DpSize(840.dp, 900.dp))
}