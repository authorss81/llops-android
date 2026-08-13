package com.authorss81.noteflow.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf

val LocalReduceMotion = compositionLocalOf { false }

object MotionSystem {
    val SpringNoBouncy: AnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMedium
    )

    val SpringMediumBouncy: AnimationSpec<Float> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
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
}
