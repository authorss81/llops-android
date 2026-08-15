package com.authorss81.noteflow.theme

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

val LocalReduceMotion = compositionLocalOf { false }

/** 36.0: true when the app haptics setting is on (haptics are additionally gated
 *  by [LocalReduceMotion] — see `MotionPolicy.hapticsAllowed`). Defaults to ON. */
val LocalHapticsEnabled = compositionLocalOf { true }

/**
 * 22.9: true when the OS-level "remove animations"/reduce-motion setting is on.
 *
 * API 30+: [android.view.accessibility.AccessibilityManager.getRecommendedTimeoutMillis]
 * shortens the returned timeout when the user disables animations (this is the
 * same heuristic Compose foundation uses to scale motion).
 *
 * API 26-29: fall back to the animator/transition duration scales — a 0 scale
 * disables animations globally. This is a conservative default (motion stays on
 * unless the user explicitly disabled system animations).
 */
fun isSystemReduceMotionEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE)
            as? android.view.accessibility.AccessibilityManager ?: return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val recommended = am.getRecommendedTimeoutMillis(1000, 0)
        recommended < 1000
    } else {
        val animatorScale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        )
        val transitionScale = Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.TRANSITION_ANIMATION_SCALE,
            1f
        )
        animatorScale == 0f || transitionScale == 0f
    }
}

object MotionSystem {
    val SpringNoBouncy: AnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    val SpringMediumBouncy: AnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )

    // 36.0: tuned constants — the single source of truth for gesture springs.
    // The numeric tuning lives in the pure-JVM MotionPolicy (testable); these
    // specs are the Compose glue, so the UI never hardcodes magic damping numbers.
    val SpringSheet: AnimationSpec<Float> = spring(
        dampingRatio = com.authorss81.noteflow.services.MotionPolicy.springFor(
            com.authorss81.noteflow.services.MotionPolicy.SpringKind.SHEET
        ).dampingRatio,
        stiffness = com.authorss81.noteflow.services.MotionPolicy.springFor(
            com.authorss81.noteflow.services.MotionPolicy.SpringKind.SHEET
        ).stiffness
    )

    val SpringDismiss: AnimationSpec<Float> = spring(
        dampingRatio = com.authorss81.noteflow.services.MotionPolicy.springFor(
            com.authorss81.noteflow.services.MotionPolicy.SpringKind.DISMISS
        ).dampingRatio,
        stiffness = com.authorss81.noteflow.services.MotionPolicy.springFor(
            com.authorss81.noteflow.services.MotionPolicy.SpringKind.DISMISS
        ).stiffness
    )

    val SpringCanvasPan: AnimationSpec<Float> = spring(
        dampingRatio = com.authorss81.noteflow.services.MotionPolicy.springFor(
            com.authorss81.noteflow.services.MotionPolicy.SpringKind.CANVAS_PAN
        ).dampingRatio,
        stiffness = com.authorss81.noteflow.services.MotionPolicy.springFor(
            com.authorss81.noteflow.services.MotionPolicy.SpringKind.CANVAS_PAN
        ).stiffness
    )

    val SpringReveal: AnimationSpec<Float> = spring(
        dampingRatio = com.authorss81.noteflow.services.MotionPolicy.springFor(
            com.authorss81.noteflow.services.MotionPolicy.SpringKind.REVEAL
        ).dampingRatio,
        stiffness = com.authorss81.noteflow.services.MotionPolicy.springFor(
            com.authorss81.noteflow.services.MotionPolicy.SpringKind.REVEAL
        ).stiffness
    )

    const val DurationFast = 150
    const val DurationMedium = 300
    const val DurationSlow = 500

    val TweenFast: AnimationSpec<Float> = tween(DurationFast)
    val TweenMedium: AnimationSpec<Float> = tween(DurationMedium)
    val TweenSlow: AnimationSpec<Float> = tween(DurationSlow)

    @Composable
    fun <T> spec(normalSpec: AnimationSpec<T>): AnimationSpec<T> {
        return if (LocalReduceMotion.current) {
            snap()
        } else {
            normalSpec
        }
    }

    /** 22.9: replace an enter transition with a jump when reduce-motion is on. */
    @Composable
    fun enter(normalEnter: EnterTransition): EnterTransition {
        return if (LocalReduceMotion.current) EnterTransition.None else normalEnter
    }

    /** 22.9: replace an exit transition with a jump when reduce-motion is on. */
    @Composable
    fun exit(normalExit: ExitTransition): ExitTransition {
        return if (LocalReduceMotion.current) ExitTransition.None else normalExit
    }
}
