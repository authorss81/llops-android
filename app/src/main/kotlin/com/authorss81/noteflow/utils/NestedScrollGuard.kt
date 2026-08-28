package com.authorss81.noteflow.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.layout.layout
import com.authorss81.noteflow.BuildConfig

/**
 * Phase 231 — Runtime guard against nested scrollable-container crashes
 * ("Vertically scrollable component was measured with infinity maximum height
 * constraints" / CheckScrollableContainerConstraints).
 *
 * This is a DEBUG-ONLY diagnostic tool. It intentionally has NO behaviour in
 * release builds: [NestedScrollGuardConfig.enabled] is a mutable flag
 * initialised from `BuildConfig.DEBUG` (which is `false` in release), so every
 * guarded code path short-circuits and does nothing at runtime in release.
 *
 * NOTE (review fix): this is a *runtime* no-op in release, NOT a guaranteed
 * compile-time dead-code-elimination. Because [NestedScrollGuardConfig.enabled]
 * is a mutable `var` (it must be, so the pure-JVM unit test can toggle it to
 * simulate a release/debug build without rebuilding), the JIT / shrinker cannot
 * prove the field is never reassigned and therefore cannot reliably strip the
 * guard body. The release cost is a single boolean read plus a dead branch per
 * scrollable re-layout — negligible, and there is zero *functional* risk because
 * the branch is always false in release.
 *
 * In release, the real protection is (a) correct modifier ordering (the
 * phase-230/c972b23 bound-before-scroll fix) and (b) Compose's own framework
 * assertion which already throws on an unbounded-height nested scrollable.
 *
 * The guard is wired into every `verticalScroll(...)` / lazy scrollable site via
 * [Modifier.nestedScrollGuard] and provided at the root composition (inside
 * `NoteflowTheme` in MainActivity) so it is active for every screen in debug
 * builds.
 */
object NestedScrollGuardConfig {
    /**
     * Guard flag — true only in debug builds. Exposed as a mutable `var` rather
     * than a `const val` so the pure-JVM unit test can toggle it to simulate
     * release (`false`) and debug (`true`) without needing to rebuild with a
     * different BuildConfig. The mutability trade-off is an honest, negligible
     * runtime branch in release; it is intentional and must not be "optimised"
     * back to a `const` or the test can no longer exercise both modes.
     */
    var enabled: Boolean = BuildConfig.DEBUG
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
 * The counter is a `ThreadLocal`, safe to read/write from the UI thread's
 * measure passes and pure-JVM-testable without Robolectric.
 */
internal object NestedScrollReporter {
    private val depth = ThreadLocal.withInitial { 0 }

    /**
     * Called at the start of a guarded scrollable's measure. If we are already
     * inside another guarded vertical scrollable (depth > 1), a [check] failure
     * is thrown in debug builds carrying a guidance message.
     */
    @JvmStatic
    fun enterUnboundedScroll() {
        if (NestedScrollGuardConfig.enabled) {
            val newDepth = (depth.get() ?: 0) + 1
            depth.set(newDepth)
            if (newDepth > 1) {
                check(newDepth <= 1) {
                    "NestedScrollGuard: a vertically scrollable component was " +
                        "measured while already inside an unbounded-height vertical " +
                        "scroller. This may crash with CheckScrollableContainerConstraints. " +
                        "Ensure the inner scrollable has .heightIn(max) BEFORE .verticalScroll() " +
                        "or uses .weight(1f)."
                }
            }
        }
    }

    /**
     * Called at the end of a guarded scrollable's measure. Decrements the depth
     * counter (never below 0).
     */
    @JvmStatic
    fun exitUnboundedScroll() {
        if (NestedScrollGuardConfig.enabled) {
            depth.set(((depth.get() ?: 0) - 1).coerceAtLeast(0))
        }
    }

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
 * `NoteflowTheme`) so the guard is active for every screen in debug builds.
 */
@Composable
fun NestedScrollGuardProvider(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalNestedScrollGuard provides NestedScrollGuardConfig.enabled) {
        content()
    }
}

/**
 * Transparent, debug-only layout modifier to attach to a vertically scrollable
 * (e.g. `Modifier.nestedScrollGuard().verticalScroll(...)`).
 *
 * During its measure it brackets the scroll's own measure with the depth
 * reporter, so a genuinely nested scrollable (one measured inside another
 * scrollable's measure) trips the debug guard. It is layout-transparent: it
 * passes the incoming [constraints] through unchanged and places the child at
 * (0, 0) with its measured size, so it never alters layout. In release it reads
 * the always-false [NestedScrollGuardConfig.enabled] flag and does nothing.
 */
fun Modifier.nestedScrollGuard(): Modifier = composed {
    if (LocalNestedScrollGuard.current) {
        layout { measurable, constraints ->
            NestedScrollReporter.enterUnboundedScroll()
            try {
                val placeable = measurable.measure(constraints)
                layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            } finally {
                NestedScrollReporter.exitUnboundedScroll()
            }
        }
    } else {
        this
    }
}
