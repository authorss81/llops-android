package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.services.OverflowMenuPolicy

/**
 * Phase-120 (UI/UX): shared overflow-menu plumbing for every overflow
 * [androidx.compose.material3.DropdownMenu] in the app.
 *
 * Material 3's [androidx.compose.material3.DropdownMenu] already wraps its
 * content in a vertically-scrollable [androidx.compose.ui.layout.Column] driven
 * by its [androidx.compose.foundation.ScrollState] parameter (a thin scrollbar
 * appears automatically once the content overflows), but it caps that column at
 * a FIXED 288.dp — taller than the usable space on small screens, landscape or
 * large fonts, which is how the bottom entries ended up unreachable.
 *
 * [overflowMenuScrollModifier] therefore replaces the call-site default by a
 * cap derived from the CURRENT screen height (see
 * [OverflowMenuPolicy.maxMenuHeightDp]) so a menu can never be taller than the
 * on-screen space, and [overflowMenuScrollState] keeps one shared scroll pattern.
 * Both are cheap (a config read + a [Modifier.heightIn]) — no allocations, no
 * per-item layout — so they stay low-end safe (AGENTS.md hardware rule).
 *
 * Keyboard arrows keep working: once a menu item has focus, the underlying
 * vertical scroll handles Arrow-Up/Down the same way it always has.
 */

/** One shared scroll state per menu (M3 scrolls its internal column through this). */
@Composable
fun overflowMenuScrollState(): ScrollState = rememberScrollState()

/**
 * Max-height bound so the menu never overflows the on-screen space. Apply as the
 * `modifier` on [androidx.compose.material3.DropdownMenu].
 */
@Composable
fun overflowMenuScrollModifier(): Modifier {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    return Modifier.heightIn(max = OverflowMenuPolicy.maxMenuHeightDp(screenHeightDp).dp)
}