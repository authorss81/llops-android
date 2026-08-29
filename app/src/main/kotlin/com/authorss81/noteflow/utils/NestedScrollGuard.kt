package com.authorss81.noteflow.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints

/**
 * Phase 231 + Phase 237 — runtime protection against nested scrollable-container
 * crashes ("Vertically scrollable component was measured with infinity maximum
 * height constraints" / CheckScrollableContainerConstraints).
 *
 * Phase 237 (2026-08-29) makes the guard actually PREVENT the crash in BOTH
 * debug and release builds, rather than only throwing a post-hoc diagnostic in
 * debug:
 *
 *  1. [NestedScrollGuardConfig.enabled] no longer derives from `BuildConfig.DEBUG`.
 *     The phase-231 guard defaulted to `BuildConfig.DEBUG`, so it was a no-op in
 *     the release builds that Test Lab (`redfin-30`) actually runs — the exact
 *     environment where the crash reproduced. It now defaults to `true` (always
 *     on) because the guard is no longer a "warning in debug only" diagnostic: it
 *     constrains the inner scrollable so Compose's own
 *     `CheckScrollableContainerConstraints` never fires. That protection is
 *     needed in release too.
 *
 *  2. The guard no longer throws after the damage is done. Phase-231 threw at
 *     [NestedScrollReporter.enterUnboundedScroll] when nesting exceeded depth 1,
 *     but by that point the layout [Modifier.nestedScrollGuard] had already run
 *     `measurable.measure(constraints)` with an unbounded (Infinity) max height —
 *     so the inner scrollable had already been measured wrongly and the throw
 *     arrived too late. The new [Modifier.nestedScrollGuard] detects the nesting
 *     in advance via [NestedScrollReporter.isInsideScrollable] and hands the inner
 *     scrollable a BOUNDED max height (the parent's max − 1, or a sane fallback),
 *     so `CheckScrollableContainerConstraints` is never triggered at all.
 *
 *  3. The depth counter still records how many guarded vertical scrollables are
 *     currently mid-measure, which is what [NestedScrollReporter.isInsideScrollable]
 *     consults. Sibling scrollables measure sequentially (never overlapping) and
 *     stay at depth 1; a genuinely nested one measures while an ancestor is
 *     mid-measure and reads depth > 0.
 *
 * The guard is wired into every `verticalScroll(...)` / lazy scrollable site via
 * [Modifier.nestedScrollGuard] and provided at the root composition (inside
 * `NoteflowTheme` in MainActivity) so it is active for every screen.
 */
object NestedScrollGuardConfig {
    /**
     * Guard flag — always on. Exposed as a mutable `var` rather than a `const`
     * so the pure-JVM unit test can toggle it to simulate a disabled build
     * without rebuilding. Unlike phase-231 (which set this to
     * `BuildConfig.DEBUG` and therefore silently disabled the guard in release),
     * Phase 237 keeps it `true` by default because the guard now *prevents* the
     * crash (it is not merely a debug-only diagnostic) and must run in release.
     */
    var enabled: Boolean = true
}

/**
 * A hierarchy marker that records the current nesting depth of guarded vertical
 * scrollables during the *measure* phase.
 *
 * Why measure-phase: the crash we guard against happens when an inner
 * `verticalScroll` / lazy scrollable measures with `maxHeight = Infinity`
 * because it sits inside an unbounded-height vertical scroll parent. By
 * bracketing each guarded scrollable's own measure with
 * [enterUnboundedScroll] / [exitUnboundedScroll], an ancestor that is currently
 * mid-measure keeps the depth > 0 while a descendant's guard measures, so a
 * genuinely nested scrollable is detected exactly when it measures. Sibling
 * scrollables measure sequentially (never overlapping) and stay at depth 1.
 *
 * Phase 237: [enterUnboundedScroll] no longer throws on nesting. The nesting is
 * both detected *before* the inner measure (via [isInsideScrollable] from the
 * layout modifier) and neutralised by constraining the inner scrollable to a
 * bounded height, so there is nothing left to throw about.
 *
 * The counter is a `ThreadLocal`, safe to read/write from the UI thread's
 * measure passes and pure-JVM-testable without Robolectric.
 */
internal object NestedScrollReporter {
    private val depth = ThreadLocal.withInitial { 0 }

    /**
     * Called at the start of a guarded scrollable's measure. Increments the
     * depth counter so [isInsideScrollable] reports "nested" for any descendant
     * that measures while this one is still mid-measure. No-op when the guard is
     * disabled (test mode).
     */
    @JvmStatic
    fun enterUnboundedScroll() {
        if (NestedScrollGuardConfig.enabled) {
            depth.set(((depth.get() ?: 0) + 1))
        }
    }

    /**
     * Called at the end of a guarded scrollable's measure. Decrements the depth
     * counter (never below 0). No-op when the guard is disabled (test mode).
     */
    @JvmStatic
    fun exitUnboundedScroll() {
        if (NestedScrollGuardConfig.enabled) {
            depth.set(((depth.get() ?: 0) - 1).coerceAtLeast(0))
        }
    }

    /**
     * True when the current measure is happening inside another guarded vertical
     * scrollable (depth > 0). A top-level / sibling scrollable measures when the
     * counter is 0 and returns false here; a descendant of another scrollable
     * measures while its ancestor is mid-measure (depth ≥ 1) and returns true.
     */
    @JvmStatic
    fun isInsideScrollable(): Boolean = (depth.get() ?: 0) > 0

    /** Test/diagnostic helper: current nesting depth (0 when balanced). */
    @JvmStatic
    fun currentDepth(): Int = depth.get() ?: 0
}

/**
 * CompositionLocal exposing the guard flag so the root composition can read it.
 * Exists so guarded scrollables can consult whether the guard is active without
 * reaching into [NestedScrollGuardConfig] directly.
 */
val LocalNestedScrollGuard: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf {
    NestedScrollGuardConfig.enabled
}

/**
 * Composition provider for the guard. Wrap the app root (inside
 * `NoteflowTheme`) so the guard is active for every screen.
 */
@Composable
fun NestedScrollGuardProvider(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNestedScrollGuard provides NestedScrollGuardConfig.enabled) {
        content()
    }
}

/**
 * Transparent-*ish*, always-active layout modifier to attach to a vertically
 * scrollable (e.g. `Modifier.nestedScrollGuard().verticalScroll(...)`).
 *
 * During its measure it brackets the scroll's own measure with the depth
 * reporter. Crucially (Phase 237), when the block is **nested** inside another
 * guarded vertical scrollable ([NestedScrollReporter.isInsideScrollable] is
 * true) it hands the inner scrollable a **bounded** max height:
 *
 *  - if the incoming [constraints] are bounded (parent's max − 1), the inner
 *    scrollable measures with a finite max height, so Compose's own
 *    `CheckScrollableContainerConstraints` (which throws when a vertical
 *    scrollable is measured with Infinity) never fires;
 *  - if the incoming [constraints] are unbounded (maxHeight == Infinity), it
 *    substitutes a sane fixed fallback height, so the inner scrollable still
 *    measures with a finite max.
 *
 * This is the prevention that phase-231's post-hoc throw could not provide, and
 * it runs in BOTH debug and release because [NestedScrollGuardConfig.enabled]
 * no longer depends on `BuildConfig.DEBUG`.
 */
fun Modifier.nestedScrollGuard(): Modifier = composed {
    if (LocalNestedScrollGuard.current) {
        layout { measurable, constraints ->
            val isNested = NestedScrollReporter.isInsideScrollable()
            val adjustedConstraints = if (isNested && constraints.hasBoundedHeight) {
                // Inner scrollable gets a bounded height (parent's max − 1) so
                // CheckScrollableContainerConstraints never fires.
                constraints.copy(minHeight = 0, maxHeight = (constraints.maxHeight - 1).coerceAtLeast(0))
            } else if (isNested && !constraints.hasBoundedHeight) {
                // Parent scrollable is unbounded (Infinity). Use a sane fallback
                // height (~4x a tablet screen, safe for both phone and tablet) so
                // the inner scrollable doesn't measure with Infinity.
                val fallbackHeight = 4096
                Constraints(
                    minWidth = constraints.minWidth,
                    maxWidth = constraints.maxWidth,
                    minHeight = constraints.minHeight.coerceAtMost(fallbackHeight),
                    maxHeight = fallbackHeight
                )
            } else {
                constraints
            }
            NestedScrollReporter.enterUnboundedScroll()
            try {
                val placeable = measurable.measure(adjustedConstraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            } finally {
                NestedScrollReporter.exitUnboundedScroll()
            }
        }
    } else {
        this
    }
}
