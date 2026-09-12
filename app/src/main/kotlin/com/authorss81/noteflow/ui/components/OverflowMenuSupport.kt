package com.authorss81.noteflow.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import android.view.ViewTreeObserver
import com.authorss81.noteflow.services.AdaptiveLayoutPolicy
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
 * cap derived from the CURRENT window height (see
 * [OverflowMenuPolicy.maxMenuHeightDp]) so a menu can never be taller than the
 * on-screen space, and [overflowMenuScrollState] keeps one shared scroll pattern.
 * Both are cheap (a window-size read + a [Modifier.heightIn]) — no allocations, no
 * per-item layout — so they stay low-end safe (AGENTS.md hardware rule).
 *
 * Phase-264: the cap is measured from the live window size (a
 * ViewTreeObserver layout listener that re-emits on every freeform
 * drag-resize frame) — never from the configuration screen dims, whose value
 * only refreshes on the next configuration pulse and goes stale mid-drag.
 * (This Compose version has no WindowInfo.containerSize, so the listener is
 * the live source; BoxWithConstraints overloads were considered but rejected —
 * they break the 25 existing call sites that are not in a measured scope.)
 *
 * Keyboard arrows keep working: once a menu item has focus, the underlying
 * vertical scroll handles Arrow-Up/Down the same way it always has.
 */

/** One shared scroll state per menu (M3 scrolls its internal column through this). */
@Composable
fun overflowMenuScrollState(): ScrollState = rememberScrollState()

/**
 * Live window size in dp. Re-emitted on every global-layout pass, so a
 * freeform drag-resize updates the menu caps frame-by-frame instead of waiting
 * for the next configuration pulse. Pre-layout (0px) maps to 0dp and the
 * policies fail safe to their floors.
 */
@Composable
private fun rememberLiveWindowSizeDp(): Pair<Int, Int> {
    val view = LocalView.current
    val density = LocalDensity.current
    var sizePx by remember { mutableStateOf(view.width to view.height) }
    DisposableEffect(view) {
        // Review-fix: capture the observer once — looking it up fresh in
        // onDispose can return a different (or dead) instance and the listener
        // would leak. Guard isAlive on both paths, and only re-emit when the
        // size actually changed so idle layout passes don't recompose menus.
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            val next = view.width to view.height
            if (next != sizePx) sizePx = next
        }
        if (observer.isAlive) observer.addOnGlobalLayoutListener(listener)
        onDispose {
            if (observer.isAlive) observer.removeOnGlobalLayoutListener(listener)
        }
    }
    val (wPx, hPx) = sizePx
    return with(density) { wPx.toDp().value.toInt() to hPx.toDp().value.toInt() }
}

/**
 * Max-height bound so the menu never overflows the on-screen space. Apply as the
 * `modifier` on [androidx.compose.material3.DropdownMenu].
 *
 * Phase-264: window-measured via [rememberLiveWindowSizeDp] (live on freeform
 * drag-resize).
 */
@Composable
fun overflowMenuScrollModifier(): Modifier {
    val (_, windowHeightDp) = rememberLiveWindowSizeDp()
    return Modifier.heightIn(max = OverflowMenuPolicy.maxMenuHeightDp(windowHeightDp).dp)
}

/**
 * Width bound (Phase 238): a DropdownMenu never out-spans the current window.
 * Material 3 lets a menu run up to the natural content width, which on a narrow
 * floating window / landscape phone clips off-screen entries. The cap derives
 * from the current window width (see [AdaptiveLayoutPolicy.maxMenuWidthDp]) and
 * only bites where the window is actually narrow — roomy windows get Material 3's
 * default spread. Compose with [overflowMenuScrollModifier]:
 * `overflowMenuScrollModifier().then(overflowMenuWidthModifier())`.
 *
 * Phase-264: window-measured via [rememberLiveWindowSizeDp] (live on freeform
 * drag-resize).
 */
@Composable
fun overflowMenuWidthModifier(): Modifier {
    val (windowWidthDp, _) = rememberLiveWindowSizeDp()
    return Modifier.widthIn(max = AdaptiveLayoutPolicy.maxMenuWidthDp(windowWidthDp).dp)
}