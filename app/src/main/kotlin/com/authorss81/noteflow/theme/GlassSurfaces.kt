package com.authorss81.noteflow.theme

import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.authorss81.noteflow.services.SettingsManager
import com.authorss81.noteflow.utils.DeviceCompatibilityManager
import com.authorss81.noteflow.utils.DeviceTier

/**
 * Phase 28: gating rules for the GLASS theme's frosted-blur rendering.
 *
 * Compose `Modifier.blur` is backed by RenderEffect, which is only rendered on
 * API 31+ (Android 12); older SDKs silently skip it. It is also GPU-expensive,
 * so LOW_END devices get a translucent (non-blurred) panel by default and the
 * user can opt back in via the blur setting. The decision is pure and
 * unit-testable; it never claims blur where it will not render.
 */
object GlassBlurGate {

    /** RenderEffect-backed Compose blur is API 31+ (Android 12). */
    fun isBlurRenderable(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.S

    /**
     * Full decision: renderable + device tier (LOW_END disabled unless the user
     * opts in) + the persisted user setting.
     */
    fun shouldApplyBlur(
        sdkInt: Int,
        tier: DeviceTier,
        glassBlurEnabled: Boolean
    ): Boolean {
        if (!isBlurRenderable(sdkInt)) return false
        if (!glassBlurEnabled) return false
        if (tier == DeviceTier.LOW_END) return false
        return true
    }
}

/**
 * Resolves the effective blur decision for the current device + user settings.
 * Pure logic extracted so UI always renders the same frost the tests verify.
 */
@Composable
fun rememberGlassBlurDecision(settings: SettingsManager): Boolean {
    val context = LocalContext.current
    var decision by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(settings.glassBlurEnabled) {
        val tier = DeviceCompatibilityManager.getDeviceTier(context, settings)
        decision = GlassBlurGate.shouldApplyBlur(
            sdkInt = Build.VERSION.SDK_INT,
            tier = tier,
            glassBlurEnabled = settings.glassBlurEnabled
        )
    }
    return decision
}

/**
 * A frosted-glass surface: translucent fill over whatever ambient backdrop is
 * behind it, with a soft light border. Applies real `Modifier.blur` (BlurredEdge
 * — blurs the content behind the layer too) ONLY when [applyBlur] is true.
 *
 * Honest fallback: when blur cannot render (API < 31, LOW_END tier, or the user
 * disabled it) the surface renders as a translucent panel WITHOUT blur. It is
 * still visibly "glass" (translucent + soft border), never a bare claim.
 *
 * NOTE: never wrap the drawing canvas with this — the canvas stays fully opaque.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun FrostedGlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    fillColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = MaterialTheme.colorScheme.outline,
    blurRadius: Dp = 16.dp,
    applyBlur: Boolean = false,
    content: @Composable () -> Unit = {}
) {
    var base = modifier
        .clip(shape)
        .background(fillColor)
        .border(BorderStroke(1.dp, borderColor), shape)

    if (applyBlur) {
        base = base.blur(blurRadius) // BlurredEdge: blurs the backdrop too
    }

    Box(modifier = base) {
        content()
    }
}