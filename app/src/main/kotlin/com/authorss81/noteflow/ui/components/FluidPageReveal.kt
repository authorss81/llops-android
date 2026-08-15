package com.authorss81.noteflow.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch

/**
 * 36.0: shared-element state. HomeScreen's NotePageCard records the tapped card's
 * window bounds here just before navigating; the editor wraps its content in
 * [FluidPageReveal], which morphs from those bounds to full-screen on open.
 */
object SharedElementState {
    @Volatile
    var cardBoundsPx: Rect? = null
        private set

    fun rememberCard(bounds: Rect) {
        cardBoundsPx = bounds
    }

    fun consume(): Rect? {
        val b = cardBoundsPx
        cardBoundsPx = null
        return b
    }
}

/**
 * 36.0: fluid shared-element reveal for the note card → editor morph.
 *
 * On first composition of a page it animates a spring scale + alpha from the
 * tapped card's bounds (recorded via [SharedElementState]) to the full editor,
 * using the tuned [com.authorss81.noteflow.theme.MotionSystem.SpringReveal] spec.
 *
 * Reduce-motion or a missing source bounds fall back to no motion at all.
 */
@Composable
fun FluidPageReveal(
    pageKey: Any,
    content: @Composable () -> Unit
) {
    val reduceMotion = com.authorss81.noteflow.theme.LocalReduceMotion.current
    val source = remember(pageKey) { SharedElementState.consume() }
    val containerSize = remember { mutableStateOf(IntSize.Zero) }

    val scale = remember(pageKey) { Animatable(1f) }
    val alpha = remember(pageKey) { Animatable(1f) }
    val originX = remember(pageKey) { mutableFloatStateOf(0.5f) }
    val originY = remember(pageKey) { mutableFloatStateOf(0.5f) }

    LaunchedEffect(pageKey, containerSize.value, source, reduceMotion) {
        scale.snapTo(1f)
        alpha.snapTo(1f)
        val bounds = source
        val size = containerSize.value
        if (reduceMotion || bounds == null || size.width <= 0 || size.height <= 0) {
            return@LaunchedEffect
        }
        val startScale = com.authorss81.noteflow.services.MotionPolicy.revealStartScale(
            cardWidth = bounds.width,
            containerWidth = size.width.toFloat()
        )
        // Anchor the morph at the card's centre (relative to the container).
        originX.floatValue = (bounds.center.x / size.width.toFloat()).coerceIn(0f, 1f)
        originY.floatValue = (bounds.center.y / size.height.toFloat()).coerceIn(0f, 1f)
        scale.snapTo(startScale)
        alpha.snapTo(0.25f)
        val spec = com.authorss81.noteflow.theme.MotionSystem.SpringReveal
        kotlinx.coroutines.coroutineScope {
            launch { scale.animateTo(1f, spec) }
            launch { alpha.animateTo(1f, spec) }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize.value = it }
            .graphicsLayer {
                transformOrigin = TransformOrigin(originX.floatValue, originY.floatValue)
                scaleX = scale.value
                scaleY = scale.value
                this.alpha = alpha.value
            }
    ) {
        content()
    }
}