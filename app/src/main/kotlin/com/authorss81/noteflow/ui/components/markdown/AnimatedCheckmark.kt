package com.authorss81.noteflow.ui.components.markdown

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.services.MotionPolicy
import com.authorss81.noteflow.theme.LocalHapticsEnabled
import com.authorss81.noteflow.theme.LocalReduceMotion
import com.authorss81.noteflow.theme.MotionSystem

/**
 * Phase 37 — interactive checkbox with a subtle scale/check "pop" on toggle.
 *
 * Respects reduce-motion: [MotionSystem.spec] swaps the spring/tween specs for a
 * snap, and the haptic fires only when [MotionPolicy.hapticsAllowed] says so.
 */
@Composable
fun AnimatedCheckmark(
    checked: Boolean,
    modifier: Modifier = Modifier,
    checkedColor: Color = androidx.compose.material3.MaterialTheme.colorScheme.primary,
    enabled: Boolean = true,
    onToggle: (() -> Unit)? = null
) {
    val reduceMotion = LocalReduceMotion.current
    val hapticsEnabled = LocalHapticsEnabled.current
    val hapticFeedback = LocalHapticFeedback.current

    val scale by animateFloatAsState(
        targetValue = if (checked) 1f else 0.55f,
        animationSpec = MotionSystem.spec(MotionSystem.SpringMediumBouncy),
        label = "checkboxScale"
    )
    val checkAlpha by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = MotionSystem.spec(MotionSystem.TweenFast),
        label = "checkboxCheckAlpha"
    )
    val borderColor = if (checked) checkedColor else MaterialTheme.colorScheme.outlineVariant
    val background = if (checked) checkedColor else Color.Transparent

    Box(
        modifier = modifier
            .requiredSize(22.dp)
            .clip(RoundedCornerShape(5.dp))
            .then(
                if (enabled && onToggle != null) {
                    Modifier.clickable {
                        onToggle()
                        if (MotionPolicy.hapticsAllowed(hapticsEnabled, reduceMotion)) {
                            hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                    }
                } else {
                    Modifier
                }
            )
            .background(background)
            .border(1.5.dp, borderColor, RoundedCornerShape(5.dp))
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .requiredSize(12.dp)
                .graphicsLayer { alpha = checkAlpha }
        ) {
            if (checked) {
                val stroke = 2.2f
                val p1 = Offset(size.width * 0.12f, size.height * 0.55f)
                val p2 = Offset(size.width * 0.42f, size.height * 0.82f)
                val p3 = Offset(size.width * 0.9f, size.height * 0.18f)
                drawLine(Color.White, p1, p2, strokeWidth = stroke, cap = StrokeCap.Round)
                drawLine(Color.White, p2, p3, strokeWidth = stroke, cap = StrokeCap.Round)
            }
        }
    }
}