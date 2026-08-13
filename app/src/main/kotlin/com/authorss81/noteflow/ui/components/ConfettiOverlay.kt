package com.authorss81.noteflow.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import java.util.Random

private data class ConfettiParticle(
    var x: Float,
    var y: Float,
    val vx: Float,
    val vy: Float,
    val color: Color,
    val radius: Float
)

@Composable
fun ConfettiOverlay(triggerTime: Long) {
    if (triggerTime <= 0) return

    val animProgress = remember(triggerTime) { Animatable(0f) }
    LaunchedEffect(triggerTime) {
        animProgress.snapTo(0f)
        animProgress.animateTo(1f, animationSpec = tween(1800, easing = LinearOutSlowInEasing))
    }

    val progress = animProgress.value
    if (progress >= 1f) return

    val particles = remember(triggerTime) {
        val rand = Random()
        val colors = listOf(Color.Red, Color.Blue, Color.Green, Color.Yellow, Color.Magenta, Color.Cyan)
        List(60) {
            ConfettiParticle(
                x = 0.5f,
                y = 0.3f,
                vx = (rand.nextFloat() - 0.5f) * 1.5f,
                vy = -rand.nextFloat() * 1.2f,
                color = colors[rand.nextInt(colors.size)],
                radius = rand.nextFloat() * 8f + 6f
            )
        }
    }

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        for (p in particles) {
            val px = (p.x + p.vx * progress) * w
            val py = (p.y + p.vy * progress + 0.8f * progress * progress) * h
            if (py < h) {
                drawCircle(color = p.color.copy(alpha = (1f - progress).coerceIn(0f, 1f)), radius = p.radius, center = Offset(px, py))
            }
        }
    }
}
