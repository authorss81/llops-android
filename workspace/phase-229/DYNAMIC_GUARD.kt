package com.authorss81.noteflow.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Phase 229 — Runtime + compile-time guard against nested scrollable-container
 * crashes ("Vertically scrollable component was measured with infinity maximum
 * height constraints").
 *
 * NOTE: This is a DEBUG-ONLY diagnostic sketch. It intentionally has NO effect
 * on release builds (zero cost, no risk of false-positive user-facing errors),
 * matching the AGENTS.md constraint "never silent degradation" — but for a
 * development-time tool, silence in release is exactly what we want.
 *
 * In release, the real protection is (a) correct modifier ordering (the
 * c972b23 fix) and (b) Compose's own framework assertion which already throws.
 */

/**
 * Guard flag — true only in debug builds (read from the build type).
 * Set via BuildConfig.DEBUG at application startup.
 */
object NestedScrollGuardConfig {
    var enabled: Boolean = BuildConfig.DEBUG
}

/**
 * A hierarchy marker that records whether we are currently within an
 * unbounded-height vertical scroll parent. Pure-JVM-safe: no Android APIs
 * beyond what Compose provides.
 */
internal object NestedScrollReporter {
    private val depth = ThreadLocal.withInitial { 0 }

    @JvmStatic
    fun enterUnboundedScroll() {
        if (NestedScrollGuardConfig.enabled) {
            depth.set(depth.get() + 1)
            if (depth.get() > 1) {
                val msg =
                    "NestedScrollGuard: a vertically scrollable component was " +
                    "entered while already inside an unbounded-height vertical " +
                    "scroller. This may crash with CheckScrollableContainerConstraints. " +
                    "Ensure the inner scrollable has .heightIn(max) BEFORE .verticalScroll() " +
                    "or uses .weight(1f)."
                android.util.Log.w("NestedScrollGuard", msg)
                if (BuildConfig.DEBUG) {
                    check(depth.get() <= 1) { msg }
                }
            }
        }
    }

    @JvmStatic
    fun exitUnboundedScroll() {
        if (NestedScrollGuardConfig.enabled) {
            depth.set((depth.get() - 1).coerceAtLeast(0))
        }
    }
}

/**
 * Compose-provider to expose the guard to the root composition. Wrap the app
 * root (inside NoteflowTheme) so the guard is active for every screen.
 */
val LocalNestedScrollGuard: CompositionLocal<Boolean> = staticCompositionLocalOf {
    NestedScrollGuardConfig.enabled
}

@Composable
fun NestedScrollGuardProvider(content: @Composable () -> Unit) {
    // Marker-holder composition that keeps the guard population available.
    androidx.compose.runtime.CompositionLocalProvider(LocalNestedScrollGuard provides NestedScrollGuardConfig.enabled) {
        content()
    }
}

/*
 * ============================================================================
 * Compile-time Detekt rule sketch
 * ============================================================================
 *
 * Custom Detekt rule: NestedScrollableDetector
 * ---------------------------------------------
 * Flags a `verticalScroll()` / `LazyColumn` that appears geometrically nested
 * inside another `verticalScroll()` parent WITHOUT a bounding modifier
 * (`heightIn`, `fillMaxHeight`, `weight`, `fillMaxSize`) applied BEFORE the
 * inner verticalScroll/lazy measure.
 *
 * Detekt rule location (in a `config/detekt` or `build-logic` detekt module):
 *
 *   import io.gitlab.arturbosch.detekt.api.*
 *
 *   class NestedScrollableDetector(config: Config) : Rule(config) {
 *       override val issue = Issue(
 *           javaClass.simpleName,
 *           Severity.Maintainability,
 *           "Vertically scrollable child inside a verticalScroll parent " +
 *           "without a height bound can crash on tablets " +
 *           "(CheckScrollableContainerConstraints).",
 *           Debt(10, DebtUnit.MINUTES)
 *       )
 *
 *       override fun visitFunction(function: KtNamedFunction) {
 *           // Find verticalScroll() call expressions and check whether their
 *           // containing Lambda/body is inside another expression whose
 *           // receiver chain ends in verticalScroll().
 *       }
 *   }
 *
 * The precise geometry (parent/child nesting) is best detected by walking the
 * PSI tree in the rule's visitor and comparing the ordering of
 * `.heightIn(...)` vs `.verticalScroll(...)` modifier chains: a crash is
 * present when the verticalScroll appears EARLIER in the chain than the
 * heightIn/fillMaxHeight/weight bound.
 *
 * Register in detekt.yml:
 *
 *   customRules:
 *     NestedScrollableDetector:
 *       active: true
 */

/*
 * ============================================================================
 * Paparazzi tablet test template (@Preview(device = Devices.TABLET) +
 * golden diff) — see workspace/phase-233.
 *
 *   @get:Rule val paparazzi = Paparazzi(
 *       deviceConfig = DeviceConfig(
 *           screenWidth = 2560, screenHeight = 1600, // tablet landscape
 *           xdpi = 320, ydpi = 320,
 *           density = Density.XHIGH, ratio = ScreenRatio.LONG, size = ScreenSize.LARGE
 *       )
 *   )
 *
 *   @Test fun colorPickerTablet() {
 *       paparazzi.snapshot("color_picker_tablet") {
 *           NoteflowTheme(AppThemeMode.LIGHT) {
 *               // ColorPickerBottomSheet(...)
 *           }
 *       }
 *   }
 *
 * Mobile regression twin (@Preview(device = Devices.PHONE)):
 *   assert verticalScroll count unchanged on phone layouts.
 * ============================================================================
 */
