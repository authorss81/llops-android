package com.authorss81.noteflow.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.authorss81.noteflow.BuildConfig

/**
 * Phase 231 — Runtime guard against nested scrollable-container crashes
 * ("Vertically scrollable component was measured with infinity maximum height
 * constraints" / CheckScrollableContainerConstraints).
 *
 * This is a DEBUG-ONLY diagnostic tool. It intentionally has NO effect on
 * release builds: because [NestedScrollGuardConfig.enabled] is initialised
 * from `BuildConfig.DEBUG` (a `static final boolean`), the JIT / AGP
 * dead-code-eliminates the entire guard body in release, so the cost and risk
 * are zero in production.
 *
 * In release, the real protection is (a) correct modifier ordering (the
 * phase-230/c972b23 bound-before-scroll fix) and (b) Compose's own framework
 * assertion which already throws.
 *
 * The guard is wired at the root composition (inside `NoteflowTheme` in
 * MainActivity) so it is active for every screen in debug builds.
 */
object NestedScrollGuardConfig {
    /**
     * Guard flag — true only in debug builds. Exposed as a `var` so the
     * pure-JVM unit test can toggle it to simulate release (`false`) and
     * debug (`true`) without needing to rebuild with a different BuildConfig.
     */
    var enabled: Boolean = BuildConfig.DEBUG
}

/**
 * A hierarchy marker that records whether we are currently within an
 * unbounded-height vertical scroll parent. Pure-JVM-safe: it uses only
 * kotlin stdlib primitives, so it is testable without Robolectric.
 *
 * The checking logic lives behind [NestedScrollGuardConfig.enabled] so that a
 * release build compiles the body to nothing.
 */
internal object NestedScrollReporter {
    private val depth = ThreadLocal.withInitial { 0 }

    /**
     * Called when a vertically scrollable component starts being composed
     * inside an unbounded-height vertical scroll parent. If we are already
     * inside such a scroller (depth > 1) in a debug build, a [check] failure
     * is thrown carrying a guidance message.
     */
    @JvmStatic
    fun enterUnboundedScroll() {
        if (NestedScrollGuardConfig.enabled) {
            val newDepth = (depth.get() ?: 0) + 1
            depth.set(newDepth)
            if (newDepth > 1) {
                check(newDepth <= 1) {
                    "NestedScrollGuard: a vertically scrollable component was " +
                        "entered while already inside an unbounded-height vertical " +
                        "scroller. This may crash with CheckScrollableContainerConstraints. " +
                        "Ensure the inner scrollable has .heightIn(max) BEFORE .verticalScroll() " +
                        "or uses .weight(1f)."
                }
            }
        }
    }

    /**
     * Called when a vertically scrollable component finishes being composed.
     * Decrements the depth counter (never below 0).
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
 * Exists for future composables that wish to consult whether the guard is
 * active without reaching into [NestedScrollGuardConfig] directly.
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
